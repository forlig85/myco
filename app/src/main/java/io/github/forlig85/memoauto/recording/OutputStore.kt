package io.github.forlig85.memoauto.recording

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import io.github.forlig85.memoauto.AppLog
import io.github.forlig85.memoauto.Notifier
import io.github.forlig85.memoauto.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 녹음 형식. MediaRecorder 설정과 파일 확장자/MIME 을 묶는다. */
enum class RecFormat(val label: String, val ext: String, val mime: String) {
    M4A("M4A (AAC) — 기본", "m4a", "audio/mp4"),
}

/** 작성 중인 녹음 파일 하나. */
class RecordingOutput(
    val uri: Uri,
    val displayName: String,
    val mime: String,
    val isMediaStore: Boolean,
    val pfd: ParcelFileDescriptor,
)

/**
 * 녹음 파일 생성/마무리/삭제. 기본 위치는 MediaStore 의 Recordings/MemoAuto,
 * 사용자가 폴더를 고르면 SAF(DocumentsContract)로 그 폴더에 만든다.
 */
object OutputStore {
    private const val TAG = "저장"
    const val DEFAULT_RELATIVE_PATH = "Recordings/MemoAuto"

    fun fileName(format: RecFormat, now: Date = Date()): String =
        SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.KOREA).format(now) + "_회의녹음." + format.ext

    fun describeLocation(ctx: Context): String {
        val tree = Prefs.storageTreeUri ?: return "기본: 내장 저장공간/$DEFAULT_RELATIVE_PATH"
        val id = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(tree)) }.getOrNull()
        return "선택한 폴더: ${id?.replace("primary:", "내장 저장공간/") ?: tree}"
    }

    fun create(ctx: Context, format: RecFormat): RecordingOutput {
        val name = fileName(format)
        val tree = Prefs.storageTreeUri
        return if (tree != null) createSaf(ctx, Uri.parse(tree), name, format) else createMediaStore(ctx, name, format)
    }

    private fun createMediaStore(ctx: Context, name: String, format: RecFormat): RecordingOutput {
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, format.mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "$DEFAULT_RELATIVE_PATH/")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = cr.insert(collection, values) ?: throw IllegalStateException("MediaStore 항목 생성 실패")
        val pfd = try {
            cr.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("파일 열기 실패")
        } catch (e: Exception) {
            runCatching { cr.delete(uri, null, null) }
            throw e
        }
        val actual = queryName(ctx, uri) ?: name
        rememberPending(uri, actual, true)
        return RecordingOutput(uri, actual, format.mime, true, pfd)
    }

    private fun createSaf(ctx: Context, tree: Uri, name: String, format: RecFormat): RecordingOutput {
        val cr = ctx.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val uri = try {
            DocumentsContract.createDocument(cr, parent, format.mime, name)
        } catch (e: Exception) {
            throw IllegalStateException("선택한 폴더에 파일을 만들 수 없음(폴더 권한이 사라졌을 수 있음): ${e.message}", e)
        } ?: throw IllegalStateException("선택한 폴더에 파일을 만들 수 없음")
        val pfd = try {
            cr.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("파일 열기 실패")
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(cr, uri) }
            throw e
        }
        val actual = queryName(ctx, uri) ?: name
        rememberPending(uri, actual, false)
        return RecordingOutput(uri, actual, format.mime, false, pfd)
    }

    private fun rememberPending(uri: Uri, name: String, mediaStore: Boolean) {
        Prefs.pendingUri = uri.toString()
        Prefs.pendingName = name
        Prefs.pendingIsMediaStore = mediaStore
    }

    fun queryName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    fun querySize(ctx: Context, uri: Uri): Long = runCatching {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    }.getOrDefault(-1L)

    /** 열린 fd 기준 현재 파일 크기. */
    fun size(out: RecordingOutput): Long = runCatching { Os.fstat(out.pfd.fileDescriptor).st_size }.getOrDefault(-1L)

    /** 열린 fd 가 있는 파일시스템의 여유 공간. */
    fun freeBytes(out: RecordingOutput): Long = runCatching {
        val st = Os.fstatvfs(out.pfd.fileDescriptor)
        st.f_bavail * st.f_bsize
    }.getOrDefault(-1L)

    /** 시작 전 여유 공간(근사치: 기본 저장공간). */
    fun freeBytesBeforeStart(): Long = runCatching {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    }.getOrDefault(-1L)

    fun finishSuccess(ctx: Context, out: RecordingOutput) {
        runCatching { out.pfd.close() }
        if (out.isMediaStore) {
            val v = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            ctx.contentResolver.update(out.uri, v, null, null)
        }
        Prefs.clearPending()
    }

    fun discard(ctx: Context, out: RecordingOutput) {
        runCatching { out.pfd.close() }
        delete(ctx, out.uri, out.isMediaStore)
        Prefs.clearPending()
    }

    fun delete(ctx: Context, uri: Uri, isMediaStore: Boolean): Boolean = runCatching {
        if (isMediaStore) ctx.contentResolver.delete(uri, null, null) > 0
        else DocumentsContract.deleteDocument(ctx.contentResolver, uri)
    }.onFailure { AppLog.w(TAG, "삭제 실패: $uri", it) }.getOrDefault(false)

    /**
     * 이전 프로세스가 녹음 도중 죽어서 남은 파일 정리.
     * 0 byte 면 삭제, 데이터가 있으면 이름에 _미완성 을 붙여 보이게 남긴다(M4A 는 재생이 안 될 수 있음).
     */
    fun recoverOrphan(ctx: Context) {
        val s = Prefs.pendingUri ?: return
        val uri = Uri.parse(s)
        val name = Prefs.pendingName ?: "?"
        val ms = Prefs.pendingIsMediaStore
        val size = querySize(ctx, uri)
        AppLog.w(TAG, "비정상 종료로 남은 녹음 파일 발견: $name (크기 $size byte)")
        if (size <= 0) {
            delete(ctx, uri, ms)
            Notifier.alert(ctx, "이전 녹음이 비정상 종료됨", "앱이 종료되어 녹음 '$name' 이(가) 비어 있었습니다. 빈 파일을 삭제했습니다. 로그에서 종료 사유를 확인하세요.", toast = false)
        } else {
            val newName = name.substringBeforeLast('.') + "_미완성." + name.substringAfterLast('.', "m4a")
            runCatching {
                if (ms) {
                    val v = ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                        put(MediaStore.Audio.Media.DISPLAY_NAME, newName)
                    }
                    ctx.contentResolver.update(uri, v, null, null)
                } else {
                    DocumentsContract.renameDocument(ctx.contentResolver, uri, newName)
                }
            }.onFailure { AppLog.w(TAG, "미완성 파일 이름 변경 실패", it) }
            Notifier.alert(ctx, "이전 녹음이 비정상 종료됨", "앱이 녹음 도중 종료되어 '$newName' 으로 남겼습니다. 재생되지 않을 수 있습니다. 로그에서 종료 사유를 확인하세요.", toast = false)
        }
        Prefs.clearPending()
    }
}
