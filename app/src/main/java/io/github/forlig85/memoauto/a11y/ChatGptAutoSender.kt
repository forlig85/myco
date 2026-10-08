package io.github.forlig85.memoauto.a11y

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.forlig85.memoauto.AppLog
import io.github.forlig85.memoauto.Notifier
import io.github.forlig85.memoauto.share.MeetingShare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ChatGPT 앱으로 공유한 직후, 입력창에 프롬프트가 들어갔는지 확인(없으면 직접 입력)하고
 * 첨부 업로드가 끝나 전송 버튼이 활성화되면 한 번만 누른다.
 * 음성 모드/받아쓰기 버튼은 절대 누르지 않는다. 찾지 못하면 화면 구조를 로그에 덤프하고 알린다.
 */
class ChatGptAutoSender(private val svc: AccessibilityService) {
    private val tag = "자동전송"
    private var job: Job? = null

    private val sendWords = listOf("보내기", "전송", "send", "submit")
    private val excludeWords = listOf("음성", "voice", "받아쓰기", "dictat", "마이크", "mic", "녹음", "record", "중지", "stop")

    fun arm(scope: CoroutineScope, prompt: String, timeoutMs: Long = 180_000) {
        job?.cancel()
        job = scope.launch { run(prompt, timeoutMs) }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private fun norm(s: CharSequence?) = s?.toString()?.replace(Regex("\\s+"), "")?.lowercase() ?: ""

    private fun chatGptRoot(): AccessibilityNodeInfo? {
        val windows = runCatching { svc.windows }.getOrNull() ?: return null
        return windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { runCatching { it.root }.getOrNull() }
            .firstOrNull { it.packageName?.toString() == MeetingShare.CHATGPT }
    }

    private fun walk(n: AccessibilityNodeInfo, depth: Int = 0, visit: (AccessibilityNodeInfo) -> Unit) {
        if (depth > 60) return
        visit(n)
        for (i in 0 until n.childCount) {
            val c = runCatching { n.getChild(i) }.getOrNull() ?: continue
            walk(c, depth + 1, visit)
        }
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        walk(root) { n ->
            if (n.isEditable && n.isVisibleToUser && (best == null || n.isFocused)) best = n
        }
        return best
    }

    private fun clickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        repeat(4) {
            val c = cur ?: return null
            if (c.isClickable) return c
            cur = c.parent
        }
        return null
    }

    private fun findSend(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root) { n ->
            if (found != null || !n.isVisibleToUser) return@walk
            val label = norm(n.contentDescription) + "|" + norm(n.text)
            if (label == "|") return@walk
            if (n.isEditable) return@walk
            if (excludeWords.any { it in label }) return@walk
            if (sendWords.none { it in label }) return@walk
            found = clickable(n)
        }
        return found
    }

    private fun hasProgress(root: AccessibilityNodeInfo): Boolean {
        var p = false
        walk(root) { n ->
            if (!p && n.isVisibleToUser && n.className?.toString()?.contains("ProgressBar") == true) p = true
        }
        return p
    }

    private suspend fun run(prompt: String, timeoutMs: Long) {
        AppLog.i(tag, "ChatGPT 전송 대기 시작(최대 ${timeoutMs / 1000}초)")
        val start = SystemClock.uptimeMillis()
        val end = start + timeoutMs
        val snippet = norm(prompt.take(20))
        var seenAt = 0L
        var textChecked = false
        var dumped = false
        var lastWait = ""

        while (SystemClock.uptimeMillis() < end) {
            delay(700)
            val root = chatGptRoot() ?: continue
            val now = SystemClock.uptimeMillis()
            if (seenAt == 0L) {
                seenAt = now
                AppLog.i(tag, "ChatGPT 화면 감지 (${(now - start) / 1000}초)")
            }

            val edit = findEditable(root)
            if (edit != null && !textChecked) {
                textChecked = true
                if (!norm(edit.text).contains(snippet)) {
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt)
                    }
                    val ok = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    AppLog.i(tag, "입력창에 프롬프트가 없어 직접 입력: ${if (ok) "성공" else "실패"}")
                    continue
                } else {
                    AppLog.i(tag, "입력창에 프롬프트가 들어와 있음")
                }
            }

            val send = findSend(root)
            val wait = when {
                send == null -> "전송 버튼 없음"
                !send.isEnabled -> "전송 버튼 비활성(첨부 업로드 중일 수 있음)"
                hasProgress(root) -> "진행 표시 있음(업로드 중)"
                else -> ""
            }
            if (wait.isNotEmpty()) {
                if (wait != lastWait) AppLog.i(tag, "대기: $wait")
                lastWait = wait
                if (!dumped && now - seenAt > 20_000) {
                    dumped = true
                    dump(root, "20초 동안 전송 준비가 안 됨")
                }
                continue
            }

            // 화면이 안정되도록 잠깐 기다린 뒤 다시 찾아 한 번만 누른다
            delay(1000)
            val root2 = chatGptRoot() ?: continue
            val send2 = findSend(root2)?.takeIf { it.isEnabled } ?: continue
            val desc = "${send2.className} d='${send2.contentDescription}' t='${send2.text}' id=${send2.viewIdResourceName}"
            val ok = send2.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            AppLog.i(tag, "전송 버튼 탭: ${if (ok) "성공" else "거부됨"} — $desc")
            if (ok) {
                delay(2000)
                val after = chatGptRoot()?.let { findEditable(it) }
                AppLog.i(tag, "전송 후 입력창: '${after?.text?.toString()?.take(30) ?: "(없음)"}'")
                Notifier.toast(svc, "ChatGPT로 회의록 요청을 전송했습니다")
                return
            }
            break
        }

        val root = chatGptRoot()
        if (root == null) {
            Notifier.alert(svc, "자동 전송 안 됨", "ChatGPT 화면이 나타나지 않았습니다. 결과 화면에서 '회의록 만들기'를 다시 눌러주세요.")
        } else {
            if (!dumped) dump(root, "시간 초과")
            Notifier.alert(svc, "자동 전송 안 됨", "ChatGPT 전송 버튼을 누르지 못했습니다. ChatGPT에서 직접 전송을 눌러주세요. (화면 구조를 로그에 남겼습니다)")
        }
    }

    private fun dump(root: AccessibilityNodeInfo, why: String) {
        val sb = StringBuilder("===== ChatGPT 화면 덤프 ($why) =====\n")
        NodeDump.dump(root, sb, 800)
        sb.append("===== 덤프 끝 =====")
        AppLog.i(tag, sb.toString())
    }
}
