package io.github.forlig85.memoauto.share

import android.content.Context
import android.net.Uri
import io.github.forlig85.memoauto.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class SendStatus(val label: String) {
    NOT_SENT("미전송"),
    SHARED("ChatGPT로 넘김 (전송 미확인)"),
    SENT("전송 완료"),
}

data class HistoryEntry(
    val info: RecordingInfo,
    val savedAt: Long,
    val status: SendStatus,
    val statusAt: Long,
)

/**
 * 저장된 녹음과 ChatGPT 전송 상태 목록(filesDir/recordings.json).
 * '나중에'로 미룬 녹음을 목록에서 다시 보낼 수 있게 한다.
 */
object RecordingHistory {
    private const val MAX = 300
    private lateinit var file: File
    private val _items = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val items: StateFlow<List<HistoryEntry>> = _items

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "recordings.json")
        _items.value = runCatching { parse(file.readText()) }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(info: RecordingInfo) {
        val now = System.currentTimeMillis()
        val list = _items.value.filterNot { it.info.uri == info.uri }
        update(listOf(HistoryEntry(info, now, SendStatus.NOT_SENT, now)) + list)
    }

    @Synchronized
    fun setStatus(uri: Uri, status: SendStatus) {
        var found = false
        val list = _items.value.map {
            if (it.info.uri == uri) { found = true; it.copy(status = status, statusAt = System.currentTimeMillis()) } else it
        }
        if (found) {
            update(list)
            AppLog.i("녹음목록", "상태 변경: ${list.first { it.info.uri == uri }.info.name} → ${status.label}")
        }
    }

    @Synchronized
    fun remove(uri: Uri) = update(_items.value.filterNot { it.info.uri == uri })

    fun pendingCount(): Int = _items.value.count { it.status == SendStatus.NOT_SENT }

    private fun update(list: List<HistoryEntry>) {
        val trimmed = list.take(MAX)
        _items.value = trimmed
        if (!::file.isInitialized) return
        runCatching { file.writeText(serialize(trimmed)) }.onFailure { AppLog.w("녹음목록", "저장 실패", it) }
    }

    private fun serialize(list: List<HistoryEntry>): String {
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject().apply {
                put("uri", e.info.uri.toString()); put("name", e.info.name); put("mime", e.info.mime)
                put("duration", e.info.durationMs); put("bytes", e.info.bytes); put("ms", e.info.isMediaStore)
                put("savedAt", e.savedAt); put("status", e.status.name); put("statusAt", e.statusAt)
            })
        }
        return arr.toString()
    }

    private fun parse(text: String): List<HistoryEntry> {
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                HistoryEntry(
                    RecordingInfo(
                        Uri.parse(o.getString("uri")), o.getString("name"), o.optString("mime", "audio/mp4"),
                        o.optLong("duration"), o.optLong("bytes"), o.optBoolean("ms", true),
                    ),
                    o.optLong("savedAt"),
                    runCatching { SendStatus.valueOf(o.getString("status")) }.getOrDefault(SendStatus.NOT_SENT),
                    o.optLong("statusAt"),
                )
            }.getOrNull()
        }
    }
}
