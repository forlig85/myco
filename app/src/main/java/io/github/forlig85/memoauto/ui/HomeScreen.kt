package io.github.forlig85.memoauto.ui

import android.Manifest
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.forlig85.memoauto.AppLog
import io.github.forlig85.memoauto.Notifier
import io.github.forlig85.memoauto.Prefs
import io.github.forlig85.memoauto.a11y.AutomationService
import io.github.forlig85.memoauto.monitor.Phase
import io.github.forlig85.memoauto.monitor.SessionController
import io.github.forlig85.memoauto.recording.RecState
import io.github.forlig85.memoauto.recording.RecordStarter
import io.github.forlig85.memoauto.recording.RecorderService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(resumeTick: Int, onOpenLog: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rec by RecorderService.state.collectAsState()
    val session by SessionController.ui.collectAsState()
    val a11yConnected by AutomationService.connected.collectAsState()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var refresh by remember { mutableStateOf(0) }
    var automation by remember { mutableStateOf(Prefs.automationEnabled) }
    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) { delay(1000); now = SystemClock.elapsedRealtime() }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        AppLog.i("권한", "요청 결과: " + res.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" })
        if (res[Manifest.permission.RECORD_AUDIO] == false) {
            Notifier.toast(ctx, "마이크 권한이 거부되었습니다. 다시 거부되면 앱 정보 > 권한에서 직접 허용하세요.")
        }
        refresh++
    }

    // resumeTick/refresh 가 바뀔 때마다 다시 계산
    val checks = remember(resumeTick, refresh) {
        Checks(
            mic = SystemChecks.hasMic(ctx),
            notif = SystemChecks.hasNotif(ctx),
            a11y = SystemChecks.accessibilityEnabled(ctx),
            overlay = SystemChecks.overlayAllowed(ctx),
            battery = SystemChecks.batteryUnrestricted(ctx),
            target = Prefs.targetPackage,
            targetLabel = Prefs.targetLabel,
        )
    }

    if (showPicker) {
        AppPickerDialog(onDismiss = { showPicker = false }) {
            Prefs.targetPackage = it.pkg
            Prefs.targetLabel = it.label
            AppLog.i("설정", "메모 앱: ${it.label} (${it.pkg})")
            showPicker = false
            refresh++
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        Spacer(Modifier.height(8.dp))
        Section("상태") {
            SwitchRow(
                "자동화 사용", automation,
                hint = "켜면 메모 앱을 열 때 AI 기록·녹음을 자동 실행"
            ) {
                automation = it
                Prefs.automationEnabled = it
                AppLog.i("설정", "자동화 사용 = $it")
                if (it) AutomationService.instance?.requestEvaluate(0)
            }
            Text("세션: ${session.phase.label}")
            Text("화면 판정: ${session.presence} ${session.visibleApps.joinToString()}", style = MaterialTheme.typography.bodySmall)
            session.awayDeadlineElapsed?.let {
                val left = ((it - now) / 1000).coerceAtLeast(0)
                Text("메모 앱 이탈 중 — ${left}초 뒤 녹음 종료", color = Color(0xFFE65100))
            }
            when (val r = rec) {
                is RecState.Recording -> {
                    val sec = (now - r.startedAtElapsed) / 1000
                    Text("● 녹음 중 ${sec / 60}:${"%02d".format(sec % 60)} — ${r.name} [${r.via}]", color = Color(0xFFC62828))
                    r.warning?.let { Text("경고: $it", color = Color(0xFFE65100)) }
                }
                RecState.Starting -> Text("녹음 시작 중…")
                RecState.Idle -> Text("녹음: 없음")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rec is RecState.Recording) {
                    Button(onClick = { RecorderService.stop("앱에서 중지") }) { Text("녹음 중지") }
                } else {
                    OutlinedButton(onClick = { scope.launch { RecordStarter.start(ctx, RecordStarter.Origin.UI) } }) { Text("지금 녹음 시작") }
                }
                if (session.phase != Phase.IDLE) {
                    OutlinedButton(onClick = { SessionController.reset() }) { Text("세션 초기화") }
                }
            }
        }

        Section("준비 (위에서부터 차례로)") {
            CheckRow(
                "메모 앱", checks.target.isNotEmpty(),
                hint = if (checks.target.isEmpty()) "선택 안 됨" else "${checks.targetLabel} (${checks.target})",
                action = "선택"
            ) { showPicker = true }
            CheckRow("마이크·알림 권한", checks.mic && checks.notif, hint = "마이크 ${if (checks.mic) "허용" else "필요"} / 알림 ${if (checks.notif) "허용" else "필요"}", action = "허용") {
                permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
            }
            CheckRow(
                "접근성 서비스", checks.a11y && a11yConnected,
                hint = when {
                    !checks.a11y -> "꺼짐. 회색으로 막혀 있으면 앱 정보 ⋮ > '제한된 설정 허용' 후 다시 시도"
                    !a11yConnected -> "켜져 있지만 연결 안 됨 — 껐다 켜보세요"
                    else -> "연결됨"
                },
                action = "열기"
            ) { SystemChecks.openAccessibility(ctx) }
            CheckRow("배터리 제한 없음", checks.battery, hint = "ZUI가 백그라운드에서 앱을 종료하지 않도록", action = "설정") { SystemChecks.openBattery(ctx) }
            CheckRow("다른 앱 위에 표시 (권장)", checks.overlay, hint = "백그라운드에서 녹음 보조 화면을 띄울 때 도움", action = "설정") { SystemChecks.openOverlay(ctx) }
            CheckRow("ZUI 자동 실행·백그라운드 허용", null, hint = "앱 정보에서 자동 실행/백그라운드 실행 허용, 최근 앱에서 잠금", action = "앱 정보") { SystemChecks.openAppDetails(ctx) }
        }

        Section("테스트·진단") {
            Hint("결과는 '로그' 탭에 기록됩니다. 문제가 있으면 로그를 복사해 붙여주세요.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val s = AutomationService.instance
                    if (s == null) Notifier.toast(ctx, "접근성 서비스를 먼저 켜주세요.") else s.runTileTest()
                }) { Text("AI 기록 타일 테스트") }
                OutlinedButton(onClick = {
                    val s = AutomationService.instance
                    if (s == null) Notifier.toast(ctx, "접근성 서비스를 먼저 켜주세요.")
                    else { s.runDiagnostics(); Notifier.toast(ctx, "빠른 설정을 열어 구조를 기록합니다…") }
                }) { Text("빠른 설정 진단") }
                OutlinedButton(onClick = {
                    val s = AutomationService.instance
                    if (s == null) Notifier.toast(ctx, "접근성 서비스를 먼저 켜주세요.")
                    else scope.launch {
                        Notifier.toast(ctx, "3초 뒤 화면의 창 목록을 기록합니다. 메모 앱으로 전환해 보세요.")
                        delay(3000); s.logWindows()
                    }
                }) { Text("창 상태 기록(3초 후)") }
                OutlinedButton(onClick = {
                    scope.launch {
                        if (RecordStarter.start(ctx, RecordStarter.Origin.UI)) {
                            Notifier.toast(ctx, "15초 녹음 테스트 시작")
                            delay(15_000)
                            RecorderService.stop("15초 테스트 종료")
                        }
                    }
                }) { Text("15초 녹음 테스트") }
                OutlinedButton(onClick = onOpenLog) { Text("로그 보기") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private data class Checks(
    val mic: Boolean,
    val notif: Boolean,
    val a11y: Boolean,
    val overlay: Boolean,
    val battery: Boolean,
    val target: String,
    val targetLabel: String,
)
