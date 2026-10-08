package io.github.forlig85.memoauto.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.forlig85.memoauto.AppLog
import io.github.forlig85.memoauto.Notifier

@Composable
fun LogScreen() {
    val ctx = LocalContext.current
    val lines by AppLog.lines.collectAsState()
    val newestFirst = lines.asReversed()

    fun fullText(): String {
        val all = AppLog.readAll()
        // 공유 인텐트 크기 제한을 피하려 뒤쪽 200KB 만
        return if (all.length > 200_000) "…(앞부분 생략)\n" + all.takeLast(200_000) else all
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("MemoAuto 로그", fullText()))
                Notifier.toast(ctx, "로그를 복사했습니다")
            }) { Text("복사") }
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "MemoAutoRecorder 로그")
                    .putExtra(Intent.EXTRA_TEXT, fullText())
                ctx.startActivity(Intent.createChooser(send, "로그 공유"))
            }) { Text("공유") }
            OutlinedButton(onClick = { AppLog.clear() }) { Text("지우기") }
        }
        Text("최신 항목이 위에 있습니다. 길게 눌러 일부만 선택할 수도 있습니다.", fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                itemsIndexed(newestFirst) { _, line ->
                    val color = when {
                        line.contains(" E/") -> Color(0xFFC62828)
                        line.contains(" W/") -> Color(0xFFE65100)
                        else -> Color.Unspecified
                    }
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = color, modifier = Modifier.padding(vertical = 3.dp))
                    HorizontalDivider()
                }
            }
        }
    }
}
