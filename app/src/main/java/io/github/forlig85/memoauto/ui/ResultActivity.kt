package io.github.forlig85.memoauto.ui

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.forlig85.memoauto.AppLog
import io.github.forlig85.memoauto.Notifier
import io.github.forlig85.memoauto.Prefs
import io.github.forlig85.memoauto.SendMode
import io.github.forlig85.memoauto.share.MeetingShare
import io.github.forlig85.memoauto.share.PromptBuilder
import io.github.forlig85.memoauto.share.RecordingInfo

/** 녹음 결과 화면: 재생 / 회의록 만들기 / 파일 공유 / 삭제. */
class ResultActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_AUTO_SHARE = "auto_share"
        private const val EXTRA_FROM_AUTOMATION = "from_automation"
        private const val STATE_ASK = "ask_send"

        @Volatile var createdCount = 0
            private set

        /** @param fromAutomation 회의가 끝나 자동으로 연 경우(전송 방식이 '물어보고 전송'이면 확인창) */
        fun intent(ctx: Context, info: RecordingInfo, autoShare: Boolean, fromAutomation: Boolean = false): Intent =
            info.putInto(Intent(ctx, ResultActivity::class.java))
                .putExtra(EXTRA_AUTO_SHARE, autoShare)
                .putExtra(EXTRA_FROM_AUTOMATION, fromAutomation)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    private val info = mutableStateOf<RecordingInfo?>(null)
    private val askSend = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdCount++
        enableEdgeToEdge()
        info.value = RecordingInfo.from(intent) ?: Prefs.lastRecording
        if (savedInstanceState == null) maybeAutoShare(intent)
        else askSend.value = savedInstanceState.getBoolean(STATE_ASK, false)
        setContent { AppTheme { ResultScreen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        createdCount++
        RecordingInfo.from(intent)?.let { info.value = it }
        maybeAutoShare(intent)
    }

    private fun maybeAutoShare(i: Intent) {
        if (!i.getBooleanExtra(EXTRA_AUTO_SHARE, false)) return
        i.removeExtra(EXTRA_AUTO_SHARE) // 회전 등으로 다시 실행되지 않게
        val r = info.value ?: return
        if (i.getBooleanExtra(EXTRA_FROM_AUTOMATION, false) && Prefs.sendMode == SendMode.ASK) {
            AppLog.i("결과화면", "ChatGPT 전송 여부 확인창 표시")
            askSend.value = true
            return
        }
        AppLog.i("결과화면", "회의록 만들기 자동 실행")
        MeetingShare.makeMinutes(this, r)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ASK, askSend.value)
    }

    private fun deleteDirect(r: RecordingInfo): Boolean {
        return if (r.isMediaStore) contentResolver.delete(r.uri, null, null) > 0
        else DocumentsContract.deleteDocument(contentResolver, r.uri)
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ResultScreen() {
        val r = info.value
        var confirmDelete by remember { mutableStateOf(false) }
        var deleted by remember { mutableStateOf(false) }

        val deleteRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            if (res.resultCode == RESULT_OK) {
                deleted = true
                AppLog.i("결과화면", "삭제 완료(시스템 확인 후)")
                if (Prefs.lastRecording?.uri == r?.uri) Prefs.lastRecording = null
            }
        }

        fun doDelete(rec: RecordingInfo) {
            try {
                if (deleteDirect(rec)) {
                    deleted = true
                    AppLog.i("결과화면", "삭제: ${rec.name}")
                    if (Prefs.lastRecording?.uri == rec.uri) Prefs.lastRecording = null
                } else {
                    Notifier.alert(this, "삭제 실패", "파일이 이미 없거나 삭제할 수 없습니다.")
                }
            } catch (e: RecoverableSecurityException) {
                deleteRequest.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
            } catch (e: SecurityException) {
                if (rec.isMediaStore) {
                    val pi = MediaStore.createDeleteRequest(contentResolver, listOf(rec.uri))
                    deleteRequest.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else Notifier.alert(this, "삭제 실패", e.message ?: "권한 없음")
            } catch (e: Exception) {
                Notifier.alert(this, "삭제 실패", e.message ?: e.javaClass.simpleName)
            }
        }

        if (askSend.value && r != null) {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("ChatGPT로 회의록 요청을 보낼까요?") },
                text = {
                    Text(
                        "${r.name} (${r.durationText})\n\n'보내기'를 누르면 ChatGPT에 녹음 파일과 프롬프트를 넘기고 전송까지 자동으로 합니다. " +
                            "'나중에'를 누르면 보내지 않습니다(이 화면이나 알림의 '회의록 만들기'로 언제든 보낼 수 있음)."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        askSend.value = false
                        AppLog.i("결과화면", "사용자가 전송 확인 → 회의록 만들기")
                        MeetingShare.makeMinutes(this@ResultActivity, r)
                    }) { Text("보내기") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        askSend.value = false
                        AppLog.i("결과화면", "사용자가 전송 보류(나중에)")
                    }) { Text("나중에") }
                },
            )
        }

        if (confirmDelete && r != null) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("녹음 삭제") },
                text = { Text("${r.name} 을(를) 삭제할까요? 되돌릴 수 없습니다.") },
                confirmButton = { TextButton(onClick = { confirmDelete = false; doDelete(r) }) { Text("삭제") } },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
            )
        }

        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("회의 녹음 완료", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (r == null) {
                    Text("표시할 녹음이 없습니다.")
                } else if (deleted) {
                    Text(r.name, style = MaterialTheme.typography.titleMedium)
                    Text("삭제되었습니다.", color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { finish() }) { Text("닫기") }
                } else {
                Text(r.name, style = MaterialTheme.typography.titleMedium)
                Text("길이 ${r.durationText} · ${r.sizeText}", style = MaterialTheme.typography.bodyMedium)

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { MeetingShare.play(this@ResultActivity, r) }) { Text("재생") }
                    Button(onClick = { MeetingShare.makeMinutes(this@ResultActivity, r) }) { Text("회의록 만들기") }
                    OutlinedButton(onClick = { MeetingShare.shareFile(this@ResultActivity, r) }) { Text("파일 공유") }
                    OutlinedButton(onClick = { confirmDelete = true }) { Text("삭제") }
                }

                Spacer(Modifier.height(8.dp))
                Text("ChatGPT로 보낼 프롬프트", fontWeight = FontWeight.Bold)
                Hint(
                    "'회의록 만들기'를 누르면 ChatGPT 앱에 녹음 파일과 이 프롬프트를 넘기고, 클립보드에도 복사합니다." +
                        if (Prefs.sendMode != SendMode.MANUAL) " 전송 버튼도 자동으로 누릅니다." else " 전송 버튼은 직접 누르세요."
                )
                val prompt = remember(r.name) { PromptBuilder.build(r.name) }
                SelectionContainer { Text(prompt, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = {
                    if (MeetingShare.copyPrompt(this@ResultActivity, prompt)) Notifier.toast(this@ResultActivity, "프롬프트를 복사했습니다")
                }) { Text("프롬프트만 복사") }
                if (!MeetingShare.isChatGptInstalled(this@ResultActivity)) {
                    Text("ChatGPT 앱이 설치돼 있지 않아 일반 공유 선택창을 사용합니다.", color = MaterialTheme.colorScheme.error)
                }
                }
            }
        }
    }
}
