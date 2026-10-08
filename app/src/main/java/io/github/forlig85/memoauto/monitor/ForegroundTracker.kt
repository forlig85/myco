package io.github.forlig85.memoauto.monitor

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo

enum class Presence {
    /** 메모 앱 창이 화면에 보임(전체/분할/팝업 창 포함). */
    TARGET_VISIBLE,
    /** 메모 앱은 안 보이고 다른 앱 창이 보임. */
    OTHER_APP,
    /** 판단 보류: 잠금 화면, 키보드·시스템 UI·이 앱 창만 보임, 창 정보 없음. */
    NEUTRAL,
}

data class PresenceSnapshot(
    val presence: Presence,
    val appWindows: List<String>,
    val note: String,
)

/**
 * 실제 화면에 보이는 창 목록(getWindows)으로 메모 앱 사용 여부를 판단한다.
 * 이벤트의 패키지명은 쓰지 않는다(키보드·알림창 이벤트로 오판하던 V1 문제).
 */
object ForegroundTracker {
    /** 앱 창이라도 "이탈"로 보지 않을 패키지. */
    private val IGNORED_PACKAGES = setOf(
        "com.android.systemui",
        "android",
        "com.android.intentresolver",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
    )

    fun evaluate(svc: AccessibilityService, target: String): PresenceSnapshot {
        val km = svc.getSystemService(KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) return PresenceSnapshot(Presence.NEUTRAL, emptyList(), "잠금 화면")
        if (target.isBlank()) return PresenceSnapshot(Presence.NEUTRAL, emptyList(), "메모 앱 미지정")

        val windows: List<AccessibilityWindowInfo> = try {
            svc.windows ?: emptyList()
        } catch (e: Exception) {
            return PresenceSnapshot(Presence.NEUTRAL, emptyList(), "창 목록 오류: ${e.message}")
        }
        val own = svc.packageName
        val apps = mutableListOf<String>()
        var targetVisible = false
        var otherVisible = false
        val r = Rect()
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            w.getBoundsInScreen(r)
            if (r.isEmpty) continue
            val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull() ?: continue
            apps += pkg
            when {
                pkg == target -> targetVisible = true
                pkg == own || pkg in IGNORED_PACKAGES -> Unit
                else -> otherVisible = true
            }
        }
        val presence = when {
            targetVisible -> Presence.TARGET_VISIBLE
            otherVisible -> Presence.OTHER_APP
            else -> Presence.NEUTRAL
        }
        return PresenceSnapshot(presence, apps.distinct(), if (apps.isEmpty()) "앱 창 없음" else "")
    }

    /** 진단용: 현재 창 목록 전체를 문자열로. */
    fun describeWindows(svc: AccessibilityService): String {
        val sb = StringBuilder()
        val r = Rect()
        val windows = runCatching { svc.windows }.getOrNull() ?: emptyList()
        sb.appendLine("창 ${windows.size}개:")
        for (w in windows) {
            w.getBoundsInScreen(r)
            val pkg = runCatching { w.root?.packageName }.getOrNull()
            sb.appendLine(
                "  - ${typeName(w.type)} layer=${w.layer} active=${w.isActive} focused=${w.isFocused} " +
                    "pkg=$pkg title=${w.title} bounds=${r.toShortString()}"
            )
        }
        return sb.toString()
    }

    fun typeName(t: Int) = when (t) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "APP"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "IME"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y_OVERLAY"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "SPLIT_DIVIDER"
        AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY -> "MAGNIFICATION"
        else -> "TYPE$t"
    }
}
