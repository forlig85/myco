package io.github.forlig85.memoauto.recording

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import io.github.forlig85.memoauto.AppLog
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 녹음 보조 화면. 1px 투명 창으로 잠깐 resumed 상태가 되어 "앱 사용 중"일 때
 * 마이크 포그라운드 서비스를 시작하고, 서비스가 포그라운드로 올라가면 바로 닫힌다.
 * 별도 task(taskAffinity="")라 앱의 메인 화면을 끌어올리지 않는다.
 */
class RecorderKickActivity : Activity() {
    private val scope = MainScope()
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setGravity(Gravity.TOP or Gravity.START)
        window.setLayout(1, 1)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )
    }

    override fun onResume() {
        super.onResume()
        if (started) return
        started = true
        try {
            startForegroundService(RecorderService.startIntent(this, RecordStarter.VIA_KICK))
        } catch (e: Exception) {
            AppLog.e("녹음시작", "보조 화면에서도 서비스 시작 실패", e)
            finishQuietly()
            return
        }
        scope.launch {
            // 서비스가 포그라운드로 올라가 녹음 상태가 되거나 실패할 때까지(최대 4초) 화면을 유지
            withTimeoutOrNull(4000) {
                RecorderService.state.first { it != RecState.Idle }
                RecorderService.state.first { it is RecState.Recording || it == RecState.Idle }
            }
            delay(200)
            finishQuietly()
        }
    }

    private fun finishQuietly() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
