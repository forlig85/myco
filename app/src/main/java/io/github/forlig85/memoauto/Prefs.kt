package io.github.forlig85.memoauto

import android.content.Context
import android.content.SharedPreferences

/** 녹음이 끝난 뒤 ChatGPT 전송 방식. */
enum class SendMode(val label: String) {
    ASK("물어보고 전송 — 녹음이 끝나면 확인창, '보내기'를 누르면 전송까지 자동"),
    AUTO("자동 전송 — 묻지 않고 전송까지 자동"),
    MANUAL("직접 전송 — 파일·프롬프트만 채우고 전송 버튼은 직접"),
}

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

    /** 타일이 상태를 알려주지 않으면, 화면 캡처로 타일 색을 같은 패널의 켜짐/꺼짐 타일과 비교해 판단. */
    var tileStateByColor: Boolean
        get() = sp.getBoolean("tile_state_by_color", true)
        set(v) = putBool("tile_state_by_color", v)

    /** AI 기록이 켜져 있을 때만 보이는 창의 패키지(쉼표 구분). 보이면 "이미 켜짐". */
    var aiIndicatorPackagesText: String
        get() = str("ai_indicator_packages", "com.lenovo.levoice.caption")
        set(v) = putStr("ai_indicator_packages", v)

    val aiIndicatorPackages: Set<String>
        get() = aiIndicatorPackagesText.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** 색으로도 판단이 안 될 때, 다른 앱이 마이크로 녹음 중인지로 AI 기록 켜짐을 판단. */
    var tileStateByMic: Boolean
        get() = sp.getBoolean("tile_state_by_mic", true)
        set(v) = putBool("tile_state_by_mic", v)

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

    // ── 회의록 (ChatGPT 공유) ─────────────────────────
    var promptTemplate: String
        get() = str("prompt_template", "").ifBlank { io.github.forlig85.memoauto.share.PromptBuilder.DEFAULT_TEMPLATE }
        set(v) = putStr("prompt_template", v)

    var projectName: String
        get() = str("project_name", "다이렉트")
        set(v) = putStr("project_name", v)

    var notionPath: String
        get() = str("notion_path", "업무 허브 / 10 과제·프로젝트")
        set(v) = putStr("notion_path", v)

    var meetingType: String
        get() = str("meeting_type", "정기 회의")
        set(v) = putStr("meeting_type", v)

    /** 자동 녹음이 끝나면 결과 화면을 열고 ChatGPT 공유까지 바로 실행. */
    var autoOpenChatGpt: Boolean
        get() = sp.getBoolean("auto_open_chatgpt", true)
        set(v) = putBool("auto_open_chatgpt", v)

    /** ChatGPT 전송 방식. */
    var sendMode: SendMode
        get() = runCatching { SendMode.valueOf(str("send_mode", SendMode.ASK.name)) }.getOrDefault(SendMode.ASK)
        set(v) = putStr("send_mode", v.name)

    // ── 최근 녹음 ───────────────────────────────────────
    var lastRecording: io.github.forlig85.memoauto.share.RecordingInfo?
        get() {
            val uri = sp.getString("last_rec_uri", null) ?: return null
            return io.github.forlig85.memoauto.share.RecordingInfo(
                uri = android.net.Uri.parse(uri),
                name = str("last_rec_name", "?"),
                mime = str("last_rec_mime", "audio/mp4"),
                durationMs = sp.getLong("last_rec_duration", 0),
                bytes = sp.getLong("last_rec_bytes", 0),
                isMediaStore = sp.getBoolean("last_rec_mediastore", true),
            )
        }
        set(v) {
            val e = sp.edit()
            if (v == null) {
                e.remove("last_rec_uri")
            } else {
                e.putString("last_rec_uri", v.uri.toString())
                    .putString("last_rec_name", v.name)
                    .putString("last_rec_mime", v.mime)
                    .putLong("last_rec_duration", v.durationMs)
                    .putLong("last_rec_bytes", v.bytes)
                    .putBoolean("last_rec_mediastore", v.isMediaStore)
            }
            e.apply()
        }

    var lastExitInfoTime: Long
        get() = sp.getLong("last_exit_info_time", 0L)
        set(v) = sp.edit().putLong("last_exit_info_time", v).apply()
}
