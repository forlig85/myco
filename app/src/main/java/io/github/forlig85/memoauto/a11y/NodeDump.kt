package io.github.forlig85.memoauto.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** 접근성 트리를 사람이 읽을 수 있게 덤프(진단용). */
object NodeDump {
    fun dump(root: AccessibilityNodeInfo, sb: StringBuilder, maxNodes: Int) {
        var count = 0
        val r = Rect()
        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (count >= maxNodes || depth > 40) return
            count++
            n.getBoundsInScreen(r)
            val cls = n.className?.toString()?.substringAfterLast('.') ?: "?"
            val flags = buildList {
                if (n.isClickable) add("click")
                if (n.isLongClickable) add("long")
                if (n.isCheckable) add(if (n.isChecked) "checked" else "unchecked")
                if (n.isScrollable) add("scroll")
                if (!n.isVisibleToUser) add("hidden")
                if (!n.isEnabled) add("disabled")
                if (n.isSelected) add("selected")
            }.joinToString(",")
            sb.append("  ".repeat(depth)).append('[').append(cls).append(']')
            n.text?.let { sb.append(" t='").append(it).append('\'') }
            n.contentDescription?.let { sb.append(" d='").append(it).append('\'') }
            n.stateDescription?.let { sb.append(" s='").append(it).append('\'') }
            n.viewIdResourceName?.let { sb.append(" id=").append(it.substringAfter(":id/")) }
            if (flags.isNotEmpty()) sb.append(" {").append(flags).append('}')
            sb.append(' ').append(r.toShortString())
            if (n.packageName != null && depth == 0) sb.append(" pkg=").append(n.packageName)
            sb.append('\n')
            for (i in 0 until n.childCount) {
                val c = runCatching { n.getChild(i) }.getOrNull() ?: continue
                visit(c, depth + 1)
            }
        }
        visit(root, 0)
        if (count >= maxNodes) sb.append("  … (노드 ${maxNodes}개에서 생략)\n")
    }
}
