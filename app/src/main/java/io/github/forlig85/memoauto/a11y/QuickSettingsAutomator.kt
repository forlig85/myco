package io.github.forlig85.memoauto.a11y

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import io.github.forlig85.memoauto.AppLog
import io.github.forlig85.memoauto.Prefs
import io.github.forlig85.memoauto.recording.RecorderService
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class TileResult(val message: String) {
    AlreadyOn("이미 켜져 있어 누르지 않았습니다."),
    TurnedOn("타일을 눌러 켰습니다."),
    ClickedUnverified("타일을 눌렀지만 켜졌는지 확인하지 못했습니다(상태 정보 없음). 진단 로그를 확인하세요."),
    StillOff("타일을 눌렀지만 여전히 꺼져 있습니다."),
    UnknownStateSkipped("타일의 켜짐/꺼짐 상태를 읽을 수 없어 누르지 않았습니다. 설정에서 '상태를 모르면 누르기'를 켜거나 '빠른 설정 진단' 결과를 확인하세요."),
    NotFound("빠른 설정에서 타일을 찾지 못했습니다. 타일 이름 설정과 '빠른 설정 진단' 결과를 확인하세요."),
    ClickFailed("타일 클릭 동작이 거부되었습니다."),
    PanelNotOpened("빠른 설정 창이 열리지 않았습니다."),
    Locked("화면이 잠겨 있어 실행하지 않았습니다."),
    Busy("다른 빠른 설정 작업이 진행 중입니다."),
}

/**
 * 빠른 설정 패널을 열고, 타일을 찾아(필요하면 페이지 스크롤) 꺼져 있을 때만 누르고, 결과를 확인한 뒤
 * 알림창 닫기 전용 동작으로 패널을 닫는다. 좌표 클릭/BACK 은 쓰지 않는다.
 */
class QuickSettingsAutomator(private val svc: AccessibilityService) {
    companion object {
        private const val SYSTEMUI = "com.android.systemui"
    }

    private val mutex = Mutex()
    private val tag = "빠른설정"

    private class Candidate(val target: AccessibilityNodeInfo, val score: Int, val desc: String)

    // ── 패널 상태 ─────────────────────────────────────
    private fun screenHeight(): Int = svc.resources.displayMetrics.heightPixels

    /** 시스템 창 중 화면 높이의 25% 이상을 차지하는 것 = 펼쳐진 알림창/빠른 설정(제어 센터). */
    private fun panelWindows(): List<AccessibilityWindowInfo> {
        val r = Rect()
        val minH = screenHeight() / 4
        val own = svc.packageName
        return (runCatching { svc.windows }.getOrNull() ?: emptyList()).filter { w ->
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) return@filter false
            w.getBoundsInScreen(r)
            if (r.height() < minH) return@filter false
            // 빠른 설정은 시스템 UI 창만 인정(AI 기록 자막 창 같은 다른 시스템 창을 패널로 착각하지 않도록)
            val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull()
            pkg == SYSTEMUI && pkg != own
        }
    }

    /** AI 기록이 켜져 있을 때 뜨는 창(예: ZUI 자막 창 com.lenovo.levoice.caption)이 보이는지. */
    fun indicatorWindow(): String? {
        val pkgs = Prefs.aiIndicatorPackages
        if (pkgs.isEmpty()) return null
        val windows = runCatching { svc.windows }.getOrNull() ?: return null
        for (w in windows) {
            val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull() ?: continue
            if (pkg in pkgs) return pkg
        }
        return null
    }

    fun isPanelOpen(): Boolean = panelWindows().isNotEmpty()

    private suspend fun waitUntil(timeoutMs: Long, stepMs: Long = 150, cond: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (cond()) return true
            delay(stepMs)
        }
        return cond()
    }

    private suspend fun openPanel(): Boolean {
        if (isPanelOpen()) return true
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        val ok = waitUntil(3000) { isPanelOpen() }
        if (ok) delay(350) // 펼침 애니메이션이 끝나 타일 노드가 채워지도록 잠깐 대기
        AppLog.i(tag, if (ok) "빠른 설정 열림" else "빠른 설정이 3초 안에 열리지 않음")
        return ok
    }

    suspend fun dismissPanel() {
        if (!isPanelOpen()) return
        repeat(2) { attempt ->
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            if (waitUntil(1500) { !isPanelOpen() }) {
                AppLog.i(tag, "빠른 설정 닫음")
                return
            }
            AppLog.w(tag, "패널 닫기 재시도 ${attempt + 1}")
        }
        AppLog.w(tag, "패널이 닫히지 않음(BACK 은 메모 앱에 영향을 줄 수 있어 사용하지 않음)")
    }

    // ── 타일 탐색 ─────────────────────────────────────
    private fun norm(s: CharSequence?): String = s?.toString()?.replace(Regex("\\s+"), "")?.lowercase() ?: ""

    private fun findTile(name: String): Candidate? {
        val needle = norm(name)
        if (needle.isEmpty()) return null
        var best: Candidate? = null
        for (w in panelWindows()) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            walk(root, 0) { node ->
                val t = norm(node.text)
                val d = norm(node.contentDescription)
                if (!t.contains(needle) && !d.contains(needle)) return@walk
                val target = clickableAncestor(node) ?: node
                var score = 0
                if (t == needle || d == needle || d.startsWith("$needle,")) score += 3
                val cls = target.className?.toString()?.lowercase() ?: ""
                val vid = target.viewIdResourceName?.lowercase() ?: ""
                if ("switch" in cls || "button" in cls || "tile" in cls || "tile" in vid || "qs" in vid) score += 2
                if (target.isCheckable || target.stateDescription != null) score += 1
                if (target.isVisibleToUser) score += 1
                // 알림 목록 안의 글자(예: 이 앱의 "AI 기록 타일" 알림)는 제외. ZUI 는 빠른 설정 자체가
                // notification_panel 안에 있으므로 "notification" 전체가 아니라 알림 행만 본다.
                if (hasAncestorId(node, "notification_stack_scroller") || hasAncestorId(node, "expandablenotificationrow")) score -= 6
                val c = Candidate(target, score, "${target.className} id=${target.viewIdResourceName} t='${node.text}' d='${node.contentDescription}'")
                if (best == null || score > best!!.score) best = c
            }
        }
        return best?.takeIf { it.score >= 0 }
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, visit: (AccessibilityNodeInfo) -> Unit) {
        if (depth > 40) return
        visit(node)
        for (i in 0 until node.childCount) {
            val c = runCatching { node.getChild(i) }.getOrNull() ?: continue
            walk(c, depth + 1, visit)
        }
    }

    private fun isClickable(n: AccessibilityNodeInfo) =
        n.isClickable || n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        repeat(6) {
            val c = cur ?: return null
            if (isClickable(c)) return c
            cur = c.parent
        }
        return null
    }

    private fun hasAncestorId(node: AccessibilityNodeInfo, part: String): Boolean {
        var cur: AccessibilityNodeInfo? = node
        repeat(15) {
            val c = cur ?: return false
            if (c.viewIdResourceName?.lowercase()?.contains(part) == true) return true
            cur = c.parent
        }
        return false
    }

    // ── 상태 판독 ─────────────────────────────────────
    /** true=켜짐, false=꺼짐, null=알 수 없음 */
    fun readState(target: AccessibilityNodeInfo): Boolean? {
        if (target.isCheckable) return target.isChecked
        parseState(target.stateDescription)?.let { return it }
        parseState(target.contentDescription)?.let { return it }
        var found: Boolean? = null
        walk(target, 0) { n ->
            if (found != null || n === target) return@walk
            found = parseState(n.stateDescription) ?: if (n.isCheckable) n.isChecked else parseState(n.text)
        }
        return found
    }

    private val unavailableWords = listOf("사용할수없음", "사용불가", "unavailable")
    private val offWords = listOf("꺼짐", "꺼져", "사용안함", "사용안함", "사용중지", "비활성", "중지됨")
    private val onWords = listOf("켜짐", "켜져", "사용중", "활성", "녹음중", "기록중", "실행중")

    fun parseState(s: CharSequence?): Boolean? {
        val t = norm(s)
        if (t.isEmpty()) return null
        if (unavailableWords.any { it in t }) return null
        // "비활성"은 "활성"을, "사용중지"는 "사용중"을 포함하므로 꺼짐을 먼저 본다.
        if (offWords.any { it in t }) return false
        if (onWords.any { it in t }) return true
        val tokens = s.toString().lowercase().split(Regex("[^a-z]+"))
        if ("off" in tokens) return false
        if ("on" in tokens) return true
        return null
    }

    // ── 스크롤 ───────────────────────────────────────
    private fun scrollPanel(forward: Boolean): Boolean {
        val action = if (forward) AccessibilityAction.ACTION_SCROLL_FORWARD else AccessibilityAction.ACTION_SCROLL_BACKWARD
        for (w in panelWindows()) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            var done = false
            walk(root, 0) { n ->
                if (done || !n.isScrollable) return@walk
                if (n.actionList.any { it.id == action.id } && n.performAction(action.id)) done = true
            }
            if (done) return true
        }
        return false
    }

    // ── 공개 동작 ─────────────────────────────────────
    suspend fun ensureTileOn(name: String): TileResult {
        if (!mutex.tryLock()) return TileResult.Busy
        try {
            val r = doEnsureTileOn(name)
            AppLog.i(tag, "AI 기록 결과: ${r.name} — ${r.message}")
            return r
        } catch (e: Exception) {
            AppLog.e(tag, "타일 처리 중 예외", e)
            runCatching { dismissPanel() }
            return TileResult.ClickFailed
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun doEnsureTileOn(name: String): TileResult {
        if (svc.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return TileResult.Locked
        AppLog.i(tag, "'$name' 타일 켜기 시작")
        indicatorWindow()?.let {
            AppLog.i(tag, "AI 기록 표시 창($it)이 떠 있음 → 이미 켜진 것으로 판단, 패널을 열지 않음")
            return TileResult.AlreadyOn
        }
        if (!openPanel()) return TileResult.PanelNotOpened

        val deadline = SystemClock.uptimeMillis() + 8000
        var cand: Candidate? = null
        var pages = 0
        var reexpanded = false
        while (SystemClock.uptimeMillis() < deadline) {
            cand = findTile(name)
            if (cand != null) break
            if (!reexpanded) {
                // 일부 기기는 첫 동작에서 축약된 빠른 설정만 펼친다 → 한 번 더 요청해 전체 펼침
                reexpanded = true
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                delay(600)
                continue
            }
            if (pages < 6 && scrollPanel(forward = true)) {
                pages++
                AppLog.i(tag, "다음 페이지로 스크롤 ($pages)")
                delay(500)
                continue
            }
            delay(300)
        }
        if (cand == null) {
            dismissPanel()
            return TileResult.NotFound
        }

        val tileState = readState(cand.target)
        AppLog.i(tag, "타일 발견(점수 ${cand.score}, 페이지 ${pages + 1}): ${cand.desc} → 상태 ${stateText(tileState)}")

        // 타일이 상태를 알려주지 않으면(ZUI AI 기록은 Button + 빈 상태) ① 색 ② 마이크 사용 순으로 판단
        var state = tileState
        var micBefore = -1
        if (state == null) {
            AppLog.i(tag, "상태를 노출하지 않는 타일(class=${cand.target.className}, stateDescription='${cand.target.stateDescription}')")
            if (Prefs.tileStateByColor) {
                val v = colorReader.judge(panelRoots(), cand.target)
                state = v.on
                AppLog.i(tag, "색으로 판단: ${stateText(v.on)} — ${v.detail}")
            }
            if (state == null && Prefs.tileStateByMic && !RecorderService.isActive) {
                val recs = otherRecordings()
                micBefore = recs.size
                state = recs.isNotEmpty()
                AppLog.i(tag, "마이크로 판단: 다른 녹음 ${recs.size}개 ${describeRecs(recs)} → ${stateText(state)}")
            }
        }
        if (state == true) {
            dismissPanel()
            return TileResult.AlreadyOn
        }
        if (state == null && !Prefs.clickUnknownTile) {
            dismissPanel()
            return TileResult.UnknownStateSkipped
        }
        if (!cand.target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            dismissPanel()
            return TileResult.ClickFailed
        }
        AppLog.i(tag, "타일 클릭")

        // 결과 확인: 패널이 열려 있는 동안 상태(또는 색)가 켜짐으로 바뀌는지 폴링
        var after: Boolean? = null
        var panelClosed = false
        val verifyEnd = SystemClock.uptimeMillis() + 4000
        delay(500)
        while (SystemClock.uptimeMillis() < verifyEnd) {
            indicatorWindow()?.let { AppLog.i(tag, "클릭 후 AI 기록 표시 창($it) 확인"); after = true }
            if (after == true) break
            if (!isPanelOpen()) { panelClosed = true; break }
            after = currentState(name)
            if (after == true) break
            delay(500)
        }
        if (panelClosed) {
            AppLog.i(tag, "클릭 후 패널이 닫힘(타일이 화면을 띄웠을 수 있음): " +
                io.github.forlig85.memoauto.monitor.ForegroundTracker.describeWindows(svc))
            delay(800)
            if (indicatorWindow() != null) after = true
            else if (openPanel()) after = currentState(name)
        }
        if (after != true && micBefore >= 0) {
            val recs = otherRecordings()
            AppLog.i(tag, "클릭 후 다른 녹음 ${recs.size}개 ${describeRecs(recs)}")
            if (recs.size > micBefore) after = true
        }
        dismissPanel()
        AppLog.i(tag, "클릭 후 상태: ${stateText(after)}")
        return when (after) {
            true -> TileResult.TurnedOn
            false -> TileResult.StillOff
            null -> TileResult.ClickedUnverified
        }
    }

    private val colorReader = TileColorReader(svc)

    private fun panelRoots(): List<AccessibilityNodeInfo> =
        panelWindows().mapNotNull { runCatching { it.root }.getOrNull() }

    /** 타일을 다시 찾아 상태 → (설정 시) 색 순으로 판단. */
    private suspend fun currentState(name: String): Boolean? {
        val c = findTile(name) ?: return null
        readState(c.target)?.let { return it }
        if (!Prefs.tileStateByColor) return null
        val v = colorReader.judge(panelRoots(), c.target)
        AppLog.i(tag, "색 확인: ${stateText(v.on)} — ${v.detail}")
        return v.on
    }

    /** 이 앱이 녹음 중이 아닐 때 호출: 기기에서 진행 중인 (다른 앱의) 녹음 목록. 일반 앱에는 익명으로 보인다. */
    private fun otherRecordings(): List<AudioRecordingConfiguration> =
        runCatching { svc.getSystemService(AudioManager::class.java).activeRecordingConfigurations }.getOrDefault(emptyList())

    private fun describeRecs(list: List<AudioRecordingConfiguration>): String =
        list.joinToString(prefix = "[", postfix = "]") { "source=${it.clientAudioSource} silenced=${it.isClientSilenced}" }

    private fun stateText(s: Boolean?) = when (s) { true -> "켜짐"; false -> "꺼짐"; null -> "알 수 없음" }

    /** 패널을 열고 접근성 트리를 로그에 덤프(최대 3페이지) 후 닫는다. */
    suspend fun diagnose() {
        if (!mutex.tryLock()) {
            AppLog.w(tag, "다른 빠른 설정 작업 진행 중이라 진단을 건너뜀")
            return
        }
        try {
            val sb = StringBuilder()
            sb.appendLine("===== 빠른 설정 진단 =====")
            sb.appendLine("기기: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) / ${Build.DISPLAY}")
            sb.appendLine("찾는 타일 이름: '${Prefs.tileName}'")
            sb.appendLine("[열기 전] " + io.github.forlig85.memoauto.monitor.ForegroundTracker.describeWindows(svc))
            val opened = openPanel()
            sb.appendLine("패널 열림: $opened")
            delay(400)
            sb.appendLine("[연 후] " + io.github.forlig85.memoauto.monitor.ForegroundTracker.describeWindows(svc))
            for (page in 1..3) {
                val wins = panelWindows()
                sb.appendLine("--- 페이지 $page (패널 창 ${wins.size}개) ---")
                for (w in wins) {
                    val root = runCatching { w.root }.getOrNull() ?: continue
                    NodeDump.dump(root, sb, 1500)
                }
                val c = findTile(Prefs.tileName)
                sb.appendLine(">> 이 페이지 타일 탐색: ${c?.let { "${it.desc} 점수 ${it.score} 상태 ${stateText(readState(it.target))}" } ?: "없음"}")
                if (c != null && readState(c.target) == null) {
                    val v = colorReader.judge(panelRoots(), c.target)
                    sb.appendLine(">> 색 판단: ${stateText(v.on)} — ${v.detail}")
                }
                if (page == 3 || !scrollPanel(forward = true)) break
                delay(600)
            }
            // 원래 페이지로 되돌림
            repeat(3) { if (!scrollPanel(forward = false)) return@repeat; delay(300) }
            dismissPanel()
            sb.appendLine("===== 진단 끝 =====")
            AppLog.i("진단", sb.toString())
        } catch (e: Exception) {
            AppLog.e("진단", "빠른 설정 진단 실패", e)
            runCatching { dismissPanel() }
        } finally {
            mutex.unlock()
        }
    }
}
