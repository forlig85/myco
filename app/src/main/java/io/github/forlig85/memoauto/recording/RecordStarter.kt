package io.github.forlig85.memoauto.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.forlig85.memoauto.AppLog
import io.github.forlig85.memoauto.Notifier
import io.github.forlig85.memoauto.Prefs
import io.github.forlig85.memoauto.StartMode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 녹음 시작 경로 선택.
 *  - 직접: 접근성 서비스 프로세스에서 startForegroundService. Android 14+ 에서 백그라운드 마이크 FGS 가
 *    거부되면 서비스가 FGS_DENIED/SILENCED_AT_START 로 실패를 알려준다.
 *  - 보조 화면: 투명한 [RecorderKickActivity] 를 잠깐 띄워 "앱 사용 중" 상태에서 시작.
 */
object RecordStarter {
    private const val TAG = "녹음시작"
    const val VIA_DIRECT = "직접"
    const val VIA_KICK = "보조화면"
    const val VIA_UI = "앱화면"

    private val mutex = Mutex()

    enum class Origin { UI, AUTOMATION }

    fun hasMicPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** @return 녹음이 시작되어 검증까지 통과했는지 */
    suspend fun start(ctx: Context, origin: Origin): Boolean = mutex.withLock {
        val app = ctx.applicationContext
        if (RecorderService.isActive) {
            AppLog.i(TAG, "이미 녹음 중")
            return true
        }
        if (!hasMicPermission(app)) {
            Notifier.alert(app, "녹음 시작 실패", "마이크 권한이 없습니다. 앱을 열어 '권한 허용'을 눌러주세요.")
            return false
        }
        if (origin == Origin.UI) {
            return attempt(app, VIA_UI) { app.startForegroundService(RecorderService.startIntent(app, VIA_UI)) } is RecEvent.Started
        }

        if (Prefs.startMode == StartMode.AUTO) {
            val r = attempt(app, VIA_DIRECT) { ctx.startForegroundService(RecorderService.startIntent(ctx, VIA_DIRECT)) }
            if (r is RecEvent.Started) return true
            val retry = r == null ||
                (r is RecEvent.Failed && (r.reason == FailReason.FGS_DENIED || r.reason == FailReason.SILENCED_AT_START))
            if (!retry) return false
            AppLog.i(TAG, "직접 시작 실패 → 보조 화면으로 재시도")
        }

        val r2 = attempt(app, VIA_KICK) {
            val i = Intent(app, RecorderKickActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_USER_ACTION
            )
            // 접근성 서비스 컨텍스트로 시작해야 백그라운드 Activity 시작 허용을 받는다.
            ctx.startActivity(i)
        }
        if (r2 == null) {
            Notifier.alert(
                app, "녹음 시작 실패",
                "백그라운드에서 녹음 보조 화면이 열리지 않았습니다. '다른 앱 위에 표시' 권한과 ZUI의 백그라운드 팝업/자동 실행 허용을 확인하세요."
            )
        }
        return r2 is RecEvent.Started
    }

    /**
     * 시작 동작을 실행하고 서비스의 첫 결과(Started/Failed)를 기다린다.
     * @return 결과 이벤트, 시간 초과·예외면 null
     */
    private suspend fun attempt(ctx: Context, via: String, launch: () -> Unit): RecEvent? = coroutineScope {
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(9000) {
                RecorderService.events.first { it is RecEvent.Started || it is RecEvent.Failed }
            }
        }
        try {
            AppLog.i(TAG, "녹음 시작 시도: $via")
            launch()
        } catch (e: Exception) {
            AppLog.w(TAG, "$via 시작 호출 예외: ${e.javaClass.simpleName}: ${e.message}")
            waiter.cancel()
            return@coroutineScope RecEvent.Failed(FailReason.FGS_DENIED, e.message ?: e.javaClass.simpleName, via)
        }
        val r = waiter.await()
        if (r == null) AppLog.w(TAG, "$via: 9초 안에 서비스 응답 없음")
        else AppLog.i(TAG, "$via 결과: $r")
        r
    }
}
