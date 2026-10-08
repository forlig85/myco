package io.github.forlig85.memoauto

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * logcat 대용 파일 로그. filesDir/logs/app.log 에 기록하고 1MB 를 넘으면 app.log.1 로 넘긴다.
 * 화면 표시용으로 최근 줄을 StateFlow 로 제공한다.
 */
object AppLog {
    private const val MAX_BYTES = 1_000_000L
    private const val MEMORY_LINES = 1500

    private lateinit var file: File
    private lateinit var oldFile: File
    private val io = Executors.newSingleThreadExecutor()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.KOREA)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun init(ctx: Context) {
        val dir = File(ctx.filesDir, "logs").apply { mkdirs() }
        file = File(dir, "app.log")
        oldFile = File(dir, "app.log.1")
        io.execute {
            val tail = runCatching { file.readLines().takeLast(MEMORY_LINES) }.getOrDefault(emptyList())
            _lines.update { (tail + it).takeLast(MEMORY_LINES) }
        }
    }

    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    private fun format(level: String, tag: String, msg: String, t: Throwable?): String {
        val time = synchronized(fmt) { fmt.format(Date()) }
        val sb = StringBuilder("$time $level/$tag: $msg")
        if (t != null) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            sb.append('\n').append(sw.toString().lines().take(25).joinToString("\n"))
        }
        return sb.toString()
    }

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val line = format(level, tag, msg, t)
        Log.println(
            when (level) { "E" -> Log.ERROR; "W" -> Log.WARN; else -> Log.INFO },
            "MemoAuto/$tag", msg
        )
        _lines.update { (it + line).takeLast(MEMORY_LINES) }
        if (!::file.isInitialized) return
        io.execute { append(line) }
    }

    /** 크래시 핸들러 등 프로세스가 곧 죽는 상황에서 동기로 기록. */
    fun writeSync(level: String, tag: String, msg: String, t: Throwable?) {
        if (!::file.isInitialized) return
        runCatching { append(format(level, tag, msg, t)) }
    }

    @Synchronized
    private fun append(line: String) {
        runCatching {
            if (file.length() > MAX_BYTES) {
                oldFile.delete()
                file.renameTo(oldFile)
            }
            file.appendText(line + "\n")
        }
    }

    fun readAll(): String {
        val parts = mutableListOf<String>()
        runCatching { if (oldFile.exists()) parts += oldFile.readText() }
        runCatching { if (file.exists()) parts += file.readText() }
        return parts.joinToString("")
    }

    fun clear() {
        io.execute {
            runCatching { file.delete(); oldFile.delete() }
        }
        _lines.value = emptyList()
    }
}
