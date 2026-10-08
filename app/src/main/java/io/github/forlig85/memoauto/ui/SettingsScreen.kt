package io.github.forlig85.memoauto.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.forlig85.memoauto.AppLog
import io.github.forlig85.memoauto.Prefs
import io.github.forlig85.memoauto.StartMode
import io.github.forlig85.memoauto.recording.OutputStore

@Composable
fun SettingsScreen(resumeTick: Int) {
    val ctx = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    var targetLabel by remember { mutableStateOf(Prefs.targetLabel) }
    var targetPkg by remember { mutableStateOf(Prefs.targetPackage) }
    var tile by remember { mutableStateOf(Prefs.tileName) }
    var autoTile by remember { mutableStateOf(Prefs.autoAiTile) }
    var autoRec by remember { mutableStateOf(Prefs.autoRecord) }
    var away by remember { mutableStateOf(Prefs.awaySeconds) }
    var customAway by remember { mutableStateOf(Prefs.awaySeconds.toString()) }
    var unknownClick by remember { mutableStateOf(Prefs.clickUnknownTile) }
    var startMode by remember { mutableStateOf(Prefs.startMode) }
    var location by remember(resumeTick) { mutableStateOf(OutputStore.describeLocation(ctx)) }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                Prefs.storageTreeUri = uri.toString()
                AppLog.i("설정", "저장 폴더: $uri")
            } catch (e: Exception) {
                AppLog.e("설정", "폴더 권한 저장 실패", e)
            }
            location = OutputStore.describeLocation(ctx)
        }
    }

    if (showPicker) {
        AppPickerDialog(onDismiss = { showPicker = false }) {
            Prefs.targetPackage = it.pkg
            Prefs.targetLabel = it.label
            targetPkg = it.pkg
            targetLabel = it.label
            AppLog.i("설정", "메모 앱: ${it.label} (${it.pkg})")
            showPicker = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding()) {
        Spacer(Modifier.height(8.dp))
        Section("대상 메모 앱") {
            Text(if (targetPkg.isEmpty()) "선택 안 됨" else "$targetLabel\n$targetPkg")
            OutlinedButton(onClick = { showPicker = true }) { Text("메모 앱 선택") }
        }

        Section("AI 기록 (빠른 설정 타일)") {
            SwitchRow("메모 앱을 열면 AI 기록 켜기", autoTile) { autoTile = it; Prefs.autoAiTile = it }
            OutlinedTextField(
                value = tile,
                onValueChange = { tile = it; Prefs.tileName = it.trim() },
                label = { Text("타일 이름") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Hint("빠른 설정에 보이는 글자 그대로. 띄어쓰기는 무시하고 비교합니다.")
            SwitchRow(
                "상태를 모르면 누르기", unknownClick,
                hint = "끔(권장): 켜짐/꺼짐을 읽을 수 없으면 누르지 않음. 켜면 이미 켜진 AI 기록이 꺼질 수 있음"
            ) { unknownClick = it; Prefs.clickUnknownTile = it }
        }

        Section("녹음") {
            SwitchRow("메모 앱을 열면 녹음 시작", autoRec) { autoRec = it; Prefs.autoRecord = it }
            Text("녹음 시작 방식")
            RadioGroup(StartMode.entries.map { it to it.label }, startMode) { startMode = it; Prefs.startMode = it }
            Hint("'자동'은 먼저 화면 전환 없이 시작하고, Android가 막거나 무음 처리하면 1px 투명 보조 화면으로 다시 시작합니다.")
        }

        Section("종료 대기시간") {
            Hint("메모 앱을 벗어난 뒤 이 시간 안에 돌아오지 않으면 녹음을 끝냅니다.")
            val presets = listOf(10, 30, 60)
            val isCustom = away !in presets
            RadioGroup(
                presets.map { it to "${it}초" } + listOf(-1 to "사용자 지정"),
                if (isCustom) -1 else away
            ) { v ->
                if (v == -1) {
                    val c = customAway.toIntOrNull()?.coerceIn(5, 3600) ?: 120
                    away = if (c in presets) 120 else c
                } else away = v
                Prefs.awaySeconds = away
                customAway = away.toString()
            }
            if (isCustom) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customAway,
                        onValueChange = { s ->
                            customAway = s.filter { it.isDigit() }.take(4)
                            customAway.toIntOrNull()?.let { v ->
                                if (v >= 5) { away = v.coerceAtMost(3600); Prefs.awaySeconds = away }
                            }
                        },
                        label = { Text("초 (5~3600)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Section("저장 폴더") {
            Text(location)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { runCatching { folderLauncher.launch(null) } }) { Text("폴더 선택") }
                OutlinedButton(onClick = {
                    Prefs.storageTreeUri = null
                    location = OutputStore.describeLocation(ctx)
                    AppLog.i("설정", "저장 폴더: 기본값")
                }) { Text("기본값") }
            }
            Hint("파일명 예: 2026-10-08_1000_회의녹음.m4a")
        }
        Spacer(Modifier.height(24.dp))
    }
}
