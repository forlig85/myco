package io.github.forlig85.memoauto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.forlig85.memoauto.recording.OutputStore
import io.github.forlig85.memoauto.share.HistoryEntry
import io.github.forlig85.memoauto.share.MeetingShare
import io.github.forlig85.memoauto.share.RecordingHistory
import io.github.forlig85.memoauto.share.SendStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 녹음 목록: 전송 상태 확인, 미전송 녹음 보내기, 재전송, 결과 화면 열기. */
@Composable
fun RecordingsScreen() {
    val ctx = LocalContext.current
    val items by RecordingHistory.items.collectAsState()
    var missing by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmResend by remember { mutableStateOf<HistoryEntry?>(null) }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.KOREA) }

    // 파일이 지워졌는지 확인(다른 앱에서 삭제한 경우)
    LaunchedEffect(items) {
        missing = withContext(Dispatchers.IO) {
            items.filter { OutputStore.querySize(ctx, it.info.uri) < 0 }.map { it.info.uri.toString() }.toSet()
        }
    }

    confirmResend?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmResend = null },
            title = { Text("다시 보낼까요?") },
            text = { Text("${e.info.name}\n이미 ${e.status.label} 상태입니다. ChatGPT에 같은 회의록 요청을 다시 보냅니다.") },
            confirmButton = {
                TextButton(onClick = { confirmResend = null; MeetingShare.makeMinutes(ctx, e.info) }) { Text("재전송") }
            },
            dismissButton = { TextButton(onClick = { confirmResend = null }) { Text("취소") } },
        )
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        val pending = items.count { it.status == SendStatus.NOT_SENT && it.info.uri.toString() !in missing }
        Text(
            if (items.isEmpty()) "아직 저장된 회의 녹음이 없습니다." else "전체 ${items.size}건 · 전송 대기 ${pending}건",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.info.uri.toString() }) { e ->
                val gone = e.info.uri.toString() in missing
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(e.info.name, fontWeight = FontWeight.Bold)
                        Text(
                            "${fmt.format(Date(e.savedAt))} · ${e.info.durationText} · ${e.info.sizeText}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        val (label, color) = when {
                            gone -> "파일 없음" to Color.Gray
                            e.status == SendStatus.NOT_SENT -> e.status.label to Color(0xFFC62828)
                            e.status == SendStatus.SHARED -> e.status.label to Color(0xFFE65100)
                            else -> "${e.status.label} (${fmt.format(Date(e.statusAt))})" to Color(0xFF2E7D32)
                        }
                        Text(label, color = color, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (gone) {
                                OutlinedButton(onClick = { RecordingHistory.remove(e.info.uri) }) { Text("목록에서 지우기") }
                            } else {
                                if (e.status == SendStatus.NOT_SENT) {
                                    Button(onClick = { MeetingShare.makeMinutes(ctx, e.info) }) { Text("회의록 전송") }
                                } else {
                                    OutlinedButton(onClick = { confirmResend = e }) { Text("재전송") }
                                }
                                OutlinedButton(onClick = { ctx.startActivity(ResultActivity.intent(ctx, e.info, autoShare = false)) }) {
                                    Text("열기")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
