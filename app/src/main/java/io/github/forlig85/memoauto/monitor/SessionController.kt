package io.github.forlig85.memoauto.monitor

import android.content.Context
import android.os.SystemClock
import io.github.forlig85.memoauto.AppLog
import io.github.forlig85.memoauto.Notifier
import io.github.forlig85.memoauto.Prefs
import io.github.forlig85.memoauto.a11y.AutomationService
import io.github.forlig85.memoauto.a11y.TileResult
import io.github.forlig85.memoauto.recording.RecState
import io.github.forlig85.memoauto.recording.RecordStarter
import io.github.forlig85.memoauto.recording.RecorderService
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Phase(val label: String) { IDLE("대기"), STARTING("시작 중"), ACTIVE("회의 세션 진행 중") }

data class SessionUi(
    val phase: Phase = Phase.IDLE,
    val awayDeadlineElapsed: Long? = null,
    val presence: Presence = Presence.NEUTRAL,
    val visibleApps: List<String> = emptyList(),
)

/**
 * 회의 세션 상태 머신.
 *  메모 앱 보임 → (AI 기록 타일 켜기) → (녹음 시작) → ACTIVE
 *  ACTIVE 에서 다른 앱으로 이탈 → 종료 타이머 → 복귀하면 취소, 만료되면 녹음 종료 → IDLE
 * 녹음이 다른 이유(알림에서 중지 등)로 끝나면 세션도 끝나고, 메모 앱을 벗어나기 전까지 재시작하지 않는다.
 */
object SessionController {
    private const val TAG = "세션"

    private lateinit var app: Context
    private val scope = MainScope()
    private var svc: AutomationService? = null

    private val _ui = MutableStateFlow(SessionUi())
    val ui: StateFlow<SessionUi> = _ui

    private var suppressUntilLeave = false
    private var awayJob: Job? = null
    private var lastPresence = Presence.NEUTRAL
    private var lastLogKey = ""

    private var phase: Phase
        get() = _ui.value.phase
        set(v) = _ui.update { it.copy(phase = v) }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        scope.launch {
            var prev: RecState = RecState.Idle
            RecorderService.state.collect { s ->
                if (prev is RecState.Recording && s == RecState.Idle && phase == Phase.ACTIVE && Prefs.autoRecord) {
                    AppLog.i(TAG, "녹음이 종료되어 세션 종료")
                    endSession()
                    suppressUntilLeave = lastPresence == Presence.TARGET_VISIBLE
                    if (suppressUntilLeave) AppLog.i(TAG, "메모 앱을 벗어났다가 다시 열면 새 세션을 시작합니다")
                }
                prev = s
            }
        }
    }

    fun attach(service: AutomationService) {
        svc = service
        AppLog.i(TAG, "접근성 서비스 연결 — 현재 세션 ${phase.label}, 녹음 ${if (RecorderService.isActive) "중" else "없음"}")
        // 프로세스가 유지된 채 접근성만 재연결된 경우: 실제 녹음 상태로 세션을 맞춘다.
        if (phase == Phase.IDLE && RecorderService.isActive) phase = Phase.ACTIVE
    }

    fun detach(service: AutomationService) {
        if (svc === service) svc = null
        AppLog.w(TAG, "접근성 서비스 연결 끊김")
    }

    fun onPresence(snap: PresenceSnapshot) {
        lastPresence = snap.presence
        _ui.update { it.copy(presence = snap.presence, visibleApps = snap.appWindows) }
        val key = "${snap.presence}|${snap.appWindows}"
        if (key != lastLogKey) {
            lastLogKey = key
            AppLog.i(TAG, "화면: ${snap.presence} ${snap.appWindows} ${snap.note}".trim())
        }

        when (snap.presence) {
            Presence.TARGET_VISIBLE -> {
                cancelAway("메모 앱 복귀")
                if (phase == Phase.IDLE && !suppressUntilLeave && Prefs.automationEnabled) startSession()
            }
            Presence.OTHER_APP -> {
                suppressUntilLeave = false
                if (phase == Phase.ACTIVE) startAway()
            }
            Presence.NEUTRAL -> Unit
        }
    }

    private fun startSession() {
        phase = Phase.STARTING
        scope.launch {
            AppLog.i(TAG, "메모 앱 감지 → 세션 시작 (AI 기록 자동=${Prefs.autoAiTile}, 자동 녹음=${Prefs.autoRecord})")
            if (Prefs.autoAiTile) {
                val s = svc
                if (s == null) {
                    Notifier.alert(app, "AI 기록 실행 실패", "접근성 서비스가 연결되어 있지 않습니다.")
                } else {
                    reportTile(s.quickSettings.ensureTileOn(Prefs.tileName))
                }
            }
            if (Prefs.autoRecord) {
                RecordStarter.start(svc ?: app, RecordStarter.Origin.AUTOMATION)
            }
            phase = Phase.ACTIVE
            AppLog.i(TAG, "세션 활성")
            // 시작하는 동안 바뀐 화면 상태를 다시 반영
            svc?.requestEvaluate(300)
        }
    }

    fun reportTile(r: TileResult) {
        when (r) {
            TileResult.AlreadyOn, TileResult.TurnedOn -> Unit
            else -> Notifier.alert(app, "AI 기록 타일", r.message)
        }
    }

    private fun startAway() {
        if (awayJob?.isActive == true) return
        val sec = Prefs.awaySeconds
        val deadline = SystemClock.elapsedRealtime() + sec * 1000L
        _ui.update { it.copy(awayDeadlineElapsed = deadline) }
        AppLog.i(TAG, "메모 앱 이탈 → ${sec}초 안에 돌아오지 않으면 종료")
        awayJob = scope.launch {
            // elapsedRealtime 기준이라 기기가 잠들었다 깨어나도 만료를 놓치지 않는다.
            while (true) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) break
                delay(minOf(left, 1000L))
            }
            awayJob = null
            AppLog.i(TAG, "${sec}초 동안 메모 앱 미복귀 → 세션 종료")
            endSession()
            if (RecorderService.isActive) RecorderService.stop("메모 앱 미복귀 ${sec}초")
        }
    }

    private fun cancelAway(reason: String) {
        val j = awayJob ?: return
        if (j.isActive) {
            j.cancel()
            AppLog.i(TAG, "$reason → 종료 타이머 취소")
        }
        awayJob = null
        _ui.update { it.copy(awayDeadlineElapsed = null) }
    }

    private fun endSession() {
        awayJob?.cancel()
        awayJob = null
        _ui.update { it.copy(phase = Phase.IDLE, awayDeadlineElapsed = null) }
    }

    /** 사용자가 앱에서 세션 상태를 초기화. */
    fun reset() {
        AppLog.i(TAG, "사용자가 세션 상태 초기화")
        endSession()
        suppressUntilLeave = false
        svc?.requestEvaluate(0)
    }
}
