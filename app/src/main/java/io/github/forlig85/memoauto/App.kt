package io.github.forlig85.memoauto

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import io.github.forlig85.memoauto.monitor.SessionController
import io.github.forlig85.memoauto.recording.OutputStore

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        AppLog.init(this)
        installCrashLogger()
        Notifier.createChannels(this)
        AppLog.i(
            "App",
            "프로세스 시작 v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                "${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}(API ${Build.VERSION.SDK_INT}) ${Build.DISPLAY}"
        )
        logPreviousExits()
        // 이 시점에 녹음 서비스는 절대 돌고 있지 않다(새 프로세스). 남은 작성 중 파일은 고아.
        OutputStore.recoverOrphan(this)
        SessionController.init(this)
        io.github.forlig85.memoauto.share.RecordingHistory.init(this)
        io.github.forlig85.memoauto.share.PostRecording.init(this)
    }

    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            AppLog.writeSync("E", "Crash", "처리되지 않은 예외 (스레드 ${t.name})", e)
            prev?.uncaughtException(t, e)
        }
    }

    /** ZUI 백그라운드 정리 등으로 죽었는지 확인하기 위해 직전 종료 사유를 기록. */
    private fun logPreviousExits() {
        runCatching {
            val am = getSystemService(ActivityManager::class.java)
            val infos = am.getHistoricalProcessExitReasons(packageName, 0, 5)
            val last = Prefs.lastExitInfoTime
            val fresh = infos.filter { it.timestamp > last }.sortedBy { it.timestamp }
            for (info in fresh) {
                AppLog.i(
                    "App",
                    "이전 종료: ${reasonName(info.reason)} (${info.description ?: "-"}) " +
                        "importance=${info.importance} " +
                        java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.KOREA).format(info.timestamp)
                )
            }
            infos.maxOfOrNull { it.timestamp }?.let { Prefs.lastExitInfoTime = it }
        }
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "크래시"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "네이티브 크래시"
        ApplicationExitInfo.REASON_EXIT_SELF -> "자체 종료"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "메모리 부족"
        ApplicationExitInfo.REASON_SIGNALED -> "시그널(시스템/제조사 정리 가능성)"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "사용자 강제 종료/정리"
        ApplicationExitInfo.REASON_USER_STOPPED -> "사용자 중지"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "리소스 과다 사용"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "권한 변경"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "의존 프로세스 종료"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "초기화 실패"
        ApplicationExitInfo.REASON_FREEZER -> "프리저"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "앱 업데이트"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "패키지 상태 변경"
        ApplicationExitInfo.REASON_OTHER -> "기타(시스템)"
        else -> "코드 $r"
    }
}
