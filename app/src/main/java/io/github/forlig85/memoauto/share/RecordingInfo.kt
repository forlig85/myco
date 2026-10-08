package io.github.forlig85.memoauto.share

import android.content.Intent
import android.net.Uri

/** 저장이 끝난 녹음 한 건. 결과 화면·알림·공유가 공통으로 쓴다. */
data class RecordingInfo(
    val uri: Uri,
    val name: String,
    val mime: String,
    val durationMs: Long,
    val bytes: Long,
    val isMediaStore: Boolean,
) {
    fun putInto(i: Intent): Intent = i
        .putExtra(K_URI, uri.toString())
        .putExtra(K_NAME, name)
        .putExtra(K_MIME, mime)
        .putExtra(K_DUR, durationMs)
        .putExtra(K_BYTES, bytes)
        .putExtra(K_MS, isMediaStore)

    val durationText: String
        get() {
            val s = durationMs / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }

    val sizeText: String
        get() = if (bytes >= 1024 * 1024) "%.1fMB".format(bytes / 1024.0 / 1024.0) else "${bytes / 1024}KB"

    companion object {
        private const val K_URI = "rec_uri"
        private const val K_NAME = "rec_name"
        private const val K_MIME = "rec_mime"
        private const val K_DUR = "rec_duration"
        private const val K_BYTES = "rec_bytes"
        private const val K_MS = "rec_mediastore"

        fun from(i: Intent?): RecordingInfo? {
            val uri = i?.getStringExtra(K_URI) ?: return null
            return RecordingInfo(
                Uri.parse(uri),
                i.getStringExtra(K_NAME) ?: "?",
                i.getStringExtra(K_MIME) ?: "audio/mp4",
                i.getLongExtra(K_DUR, 0),
                i.getLongExtra(K_BYTES, 0),
                i.getBooleanExtra(K_MS, true),
            )
        }
    }
}
