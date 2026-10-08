package io.github.forlig85.memoauto.share

import android.content.Context
import io.github.forlig85.memoauto.AppLog
import io.github.forlig85.memoauto.Notifier
import io.github.forlig85.memoauto.Prefs
import io.github.forlig85.memoauto.a11y.AutomationService
import io.github.forlig85.memoauto.recording.RecEvent
import io.github.forlig85.memoauto.recording.RecorderService
import io.github.forlig85.memoauto.ui.ResultActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 녹음 저장 후 처리: 완료 알림, 결과 화면, (설정 시) ChatGPT 공유 자동 실행.
 * 백그라운드라 화면이 안 열리면 알림으로 대체한다.
 */
object PostRecording {
    private const val TAG = "녹음완료"
    private val scope = MainScope()

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            RecorderService.events.collect { e ->
                if (e is RecEvent.Saved) onSaved(app, e)
            }
        }
    }

    private suspend fun onSaved(app: Context, e: RecEvent.Saved) {
        val info = RecordingInfo(e.uri, e.name, e.mime, e.durationMs, e.bytes, e.isMediaStore)
        Prefs.lastRecording = info
        Notifier.saved(app, info)
        if (!e.automatic) {
            AppLog.i(TAG, "직접 시작한 녹음이라 결과 화면/ChatGPT 자동 실행은 하지 않음")
            return
        }

        val autoShare = Prefs.autoOpenChatGpt
        val before = ResultActivity.createdCount
        // 접근성 서비스 컨텍스트는 백그라운드 Activity 시작이 허용될 가능성이 높다
        val launcher: Context = AutomationService.instance ?: app
        try {
            launcher.startActivity(ResultActivity.intent(app, info, autoShare))
            AppLog.i(TAG, "결과 화면 실행 요청 (ChatGPT 자동 열기=$autoShare)")
        } catch (ex: Exception) {
            AppLog.w(TAG, "결과 화면 실행 실패", ex)
        }
        delay(3000)
        if (ResultActivity.createdCount == before) {
            AppLog.w(TAG, "백그라운드 제한으로 결과 화면이 열리지 않음 → 알림으로 대체")
            Notifier.minutesReminder(app, info)
        }
    }
}
