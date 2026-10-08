package io.github.forlig85.memoauto.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import io.github.forlig85.memoauto.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * 상태를 노출하지 않는 타일의 켜짐/꺼짐을 "색"으로 판단한다.
 * 같은 패널 안에서 상태를 알려주는 타일(checked/unchecked Switch)을 기준으로 삼아
 * 대상 타일 아이콘 영역의 평균색이 켜짐/꺼짐 기준 중 어느 쪽에 가까운지 본다(테마·다크모드 무관).
 */
class TileColorReader(private val svc: AccessibilityService) {
    private val tag = "타일색"

    data class Verdict(val on: Boolean?, val detail: String)

    private suspend fun screenshotOnce(): Pair<Bitmap?, Int> = suspendCancellableCoroutine { cont ->
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    val bmp = try {
                        Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (e: Exception) {
                        AppLog.w(tag, "스크린샷 변환 실패", e); null
                    } finally {
                        hb.close()
                    }
                    if (cont.isActive) cont.resume(bmp to 0)
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null to errorCode)
                }
            })
        } catch (e: Exception) {
            AppLog.w(tag, "스크린샷 요청 실패(설정에서 접근성을 껐다 켜야 할 수 있음)", e)
            if (cont.isActive) cont.resume(null to -1)
        }
    }

    suspend fun screenshot(): Bitmap? {
        repeat(3) { attempt ->
            val (bmp, code) = screenshotOnce()
            if (bmp != null) return bmp
            AppLog.w(tag, "스크린샷 실패 code=$code (시도 ${attempt + 1})")
            if (code == -1) return null
            delay(400) // 연속 촬영 간격 제한
        }
        return null
    }

    /** 타일의 아이콘 영역(첫 자식) 또는 타일 전체. */
    private fun colorRect(tile: AccessibilityNodeInfo): Rect {
        val r = Rect()
        tile.getBoundsInScreen(r)
        val child = runCatching { if (tile.childCount > 0) tile.getChild(0) else null }.getOrNull()
        if (child != null) {
            val cr = Rect()
            child.getBoundsInScreen(cr)
            if (!cr.isEmpty && cr.width() <= r.width() && cr.height() < r.height()) return cr
        }
        return r
    }

    private fun meanColor(bmp: Bitmap, rect: Rect): Int? {
        val r = Rect(rect)
        if (!r.intersect(0, 0, bmp.width, bmp.height) || r.isEmpty) return null
        val step = maxOf(1, minOf(r.width(), r.height()) / 24)
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0L
        var y = r.top
        while (y < r.bottom) {
            var x = r.left
            while (x < r.right) {
                val c = bmp.getPixel(x, y)
                sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c); n++
                x += step
            }
            y += step
        }
        if (n == 0L) return null
        return Color.rgb((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())
    }

    private fun dist(a: Int, b: Int): Double {
        val dr = (Color.red(a) - Color.red(b)).toDouble()
        val dg = (Color.green(a) - Color.green(b)).toDouble()
        val db = (Color.blue(a) - Color.blue(b)).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    private fun avg(colors: List<Int>): Int = Color.rgb(
        colors.map { Color.red(it) }.average().toInt(),
        colors.map { Color.green(it) }.average().toInt(),
        colors.map { Color.blue(it) }.average().toInt(),
    )

    private fun hex(c: Int) = String.format("#%06X", 0xFFFFFF and c)

    private fun walk(n: AccessibilityNodeInfo, depth: Int = 0, visit: (AccessibilityNodeInfo) -> Unit) {
        if (depth > 40) return
        visit(n)
        for (i in 0 until n.childCount) {
            val c = runCatching { n.getChild(i) }.getOrNull() ?: continue
            walk(c, depth + 1, visit)
        }
    }

    /**
     * @param roots 빠른 설정 패널 창의 루트들
     * @param target 상태를 알고 싶은 타일(클릭 대상 노드)
     */
    suspend fun judge(roots: List<AccessibilityNodeInfo>, target: AccessibilityNodeInfo): Verdict {
        val tb = Rect().also { target.getBoundsInScreen(it) }
        // 기준 타일: 크기가 비슷하고 체크 상태를 알려주는 타일
        val onRefs = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        val offRefs = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        for (root in roots) {
            walk(root) { n ->
                if (!n.isCheckable || !n.isVisibleToUser) return@walk
                val b = Rect().also { n.getBoundsInScreen(it) }
                if (b == tb) return@walk
                val similar = b.width() in (tb.width() * 85 / 100)..(tb.width() * 115 / 100) &&
                    b.height() in (tb.height() * 85 / 100)..(tb.height() * 115 / 100)
                if (!similar) return@walk
                val label = (n.text ?: n.contentDescription ?: "?").toString()
                if (n.isChecked) onRefs += label to n else offRefs += label to n
            }
        }
        if (onRefs.isEmpty() || offRefs.isEmpty()) {
            return Verdict(null, "기준 타일 부족(켜짐 ${onRefs.size}개, 꺼짐 ${offRefs.size}개)")
        }
        val bmp = screenshot() ?: return Verdict(null, "스크린샷 실패")
        try {
            val targetColor = meanColor(bmp, colorRect(target)) ?: return Verdict(null, "대상 영역이 화면 밖")
            val onColors = onRefs.mapNotNull { (l, n) -> meanColor(bmp, colorRect(n))?.let { l to it } }
            val offColors = offRefs.mapNotNull { (l, n) -> meanColor(bmp, colorRect(n))?.let { l to it } }
            if (onColors.isEmpty() || offColors.isEmpty()) return Verdict(null, "기준 색 추출 실패")
            val onAvg = avg(onColors.map { it.second })
            val offAvg = avg(offColors.map { it.second })
            val dOn = dist(targetColor, onAvg)
            val dOff = dist(targetColor, offAvg)
            val refGap = dist(onAvg, offAvg)
            val detail = "대상 ${hex(targetColor)}, 켜짐 기준 ${hex(onAvg)} ${onColors.map { "${it.first}=${hex(it.second)}" }}, " +
                "꺼짐 기준 ${hex(offAvg)} ${offColors.map { "${it.first}=${hex(it.second)}" }}, 거리 켜짐 ${"%.0f".format(dOn)} / 꺼짐 ${"%.0f".format(dOff)}"
            // 켜짐/꺼짐 기준 자체가 비슷하거나, 대상이 양쪽과 비슷하게 떨어져 있으면 판단 보류
            val verdict = when {
                refGap < 20 -> null
                dOn * 1.5 < dOff -> true
                dOff * 1.5 < dOn -> false
                else -> null
            }
            return Verdict(verdict, detail)
        } finally {
            bmp.recycle()
        }
    }
}
