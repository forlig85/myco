package io.github.forlig85.memoauto

import android.content.Context
import android.content.SharedPreferences

/** 녹음 시작 방식. */
enum class StartMode(val label: String) {
    AUTO("자동 (직접 시작 → 실패하면 보조 화면)"),
    ALWAYS_KICK("항상 보조 화면으로 시작"),
}

/**
 * 설정 저장소. "녹음 중" 같은 실행 상태는 여기 두지 않는다(실제 서비스 상태가 기준).
 * 예외로, 비정상 종료 복구를 위한 "작성 중 파일" 정보만 저장한다.
 */
object Prefs {
    const val DEFAULT_TILE = "AI 기록"
    const val DEFAULT_AWAY_SECONDS = 60

    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("memo_auto_v2", Context.MODE_PRIVATE)
    }

    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun putStr(key: String, v: String?) = sp.edit().putString(key, v).apply()
    private fun putBool(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()

    // ── 자동화 ──────────────────────────────────────────
    var automationEnabled: Boolean
        get() = sp.getBoolean("automation_enabled", false)
        set(v) = putBool("automation_enabled", v)

    var targetPackage: String
        get() = str("target_package", "")
        set(v) = putStr("target_package", v)

    var targetLabel: String
        get() = str("target_label", "")
        set(v) = putStr("target_label", v)

    var tileName: String
        get() = str("tile_name", DEFAULT_TILE).ifBlank { DEFAULT_TILE }
        set(v) = putStr("tile_name", v)

    var autoAiTile: Boolean
        get() = sp.getBoolean("auto_ai_tile", true)
        set(v) = putBool("auto_ai_tile", v)

    var autoRecord: Boolean
        get() = sp.getBoolean("auto_record", true)
        set(v) = putBool("auto_record", v)

    var awaySeconds: Int
        get() = sp.getInt("away_seconds", DEFAULT_AWAY_SECONDS).coerceIn(5, 3600)
        set(v) = sp.edit().putInt("away_seconds", v.coerceIn(5, 3600)).apply()

    /** 타일의 켜짐/꺼짐을 읽을 수 없을 때도 클릭할지. 기본은 안전하게 클릭하지 않음. */
    var clickUnknownTile: Boolean
        get() = sp.getBoolean("click_unknown_tile", false)
        set(v) = putBool("click_unknown_tile", v)

    var startMode: StartMode
        get() = runCatching { StartMode.valueOf(str("start_mode", StartMode.AUTO.name)) }.getOrDefault(StartMode.AUTO)
        set(v) = putStr("start_mode", v.name)

    // ── 저장 ────────────────────────────────────────────
    /** null 이면 기본 위치(Recordings/MemoAuto). 아니면 SAF 폴더 트리 URI. */
    var storageTreeUri: String?
        get() = sp.getString("storage_tree_uri", null)
        set(v) = putStr("storage_tree_uri", v)

    // ── 복구용: 작성 중인 녹음 파일 ─────────────────────
    var pendingUri: String?
        get() = sp.getString("pending_uri", null)
        set(v) = putStr("pending_uri", v)

    var pendingName: String?
        get() = sp.getString("pending_name", null)
        set(v) = putStr("pending_name", v)

    var pendingIsMediaStore: Boolean
        get() = sp.getBoolean("pending_mediastore", true)
        set(v) = putBool("pending_mediastore", v)

    fun clearPending() {
        sp.edit().remove("pending_uri").remove("pending_name").remove("pending_mediastore").apply()
    }

    var lastExitInfoTime: Long
        get() = sp.getLong("last_exit_info_time", 0L)
        set(v) = sp.edit().putLong("last_exit_info_time", v).apply()
}
