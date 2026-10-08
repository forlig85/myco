package io.github.forlig85.memoauto.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import io.github.forlig85.memoauto.AppLog
import io.github.forlig85.memoauto.Prefs
import io.github.forlig85.memoauto.monitor.ForegroundTracker
import io.github.forlig85.memoauto.monitor.SessionController
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 접근성 이벤트 수신 창구. 판단은 [ForegroundTracker], 상태 머신은 [SessionController],
 * 빠른 설정 조작은 [QuickSettingsAutomator] 가 맡는다.
 */
class AutomationService : AccessibilityService() {

    companion object {
        @Volatile var instance: AutomationService? = null
            private set
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected
    }

    private val scope = MainScope()
    private var evalJob: Job? = null
    lateinit var quickSettings: QuickSettingsAutomator
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        quickSettings = QuickSettingsAutomator(this)
        instance = this
        _connected.value = true
        AppLog.i("접근성", "서비스 연결됨")
        SessionController.attach(this)
        requestEvaluate(0)
        // 이벤트를 놓쳐도 상태가 어긋나지 않도록 주기적으로 재평가
        scope.launch {
            while (isActive) {
                delay(5000)
                requestEvaluate(0)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> requestEvaluate(250)
        }
    }

    /** 이벤트가 몰려도 마지막 한 번만 평가(디바운스). */
    fun requestEvaluate(delayMs: Long) {
        evalJob?.cancel()
        evalJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            val snap = ForegroundTracker.evaluate(this@AutomationService, Prefs.targetPackage)
            SessionController.onPresence(snap)
        }
    }

    private var chatGptSender: ChatGptAutoSender? = null

    fun armChatGptAutoSend(prompt: String) {
        val s = chatGptSender ?: ChatGptAutoSender(this).also { chatGptSender = it }
        s.arm(scope, prompt)
    }

    fun runTileTest() {
        scope.launch { SessionController.reportTile(quickSettings.ensureTileOn(Prefs.tileName)) }
    }

    fun runDiagnostics() {
        scope.launch { quickSettings.diagnose() }
    }

    fun logWindows() {
        AppLog.i("진단", "현재 " + ForegroundTracker.describeWindows(this) +
            "판정: " + ForegroundTracker.evaluate(this, Prefs.targetPackage))
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        AppLog.w("접근성", "서비스 연결 해제(onUnbind)")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        if (instance === this) instance = null
        _connected.value = false
        SessionController.detach(this)
        super.onDestroy()
    }
}
