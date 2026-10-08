package io.github.forlig85.memoauto.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.forlig85.memoauto.AppLog
import io.github.forlig85.memoauto.Notifier
import io.github.forlig85.memoauto.Prefs
import io.github.forlig85.memoauto.a11y.AutomationService

/**
 * 녹음 파일 + 회의록 프롬프트를 ChatGPT 앱으로 넘긴다(OpenAI API 는 호출하지 않음).
 * 프롬프트는 항상 클립보드에도 복사한다. ChatGPT 가 없거나 받지 못하면 일반 공유 선택창.
 */
object MeetingShare {
    private const val TAG = "공유"
    const val CHATGPT = "com.openai.chatgpt"

    fun isChatGptInstalled(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(CHATGPT, 0); true }.getOrDefault(false)

    fun copyPrompt(ctx: Context, prompt: String): Boolean = try {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("회의록 프롬프트", prompt))
        true
    } catch (e: Exception) {
        AppLog.w(TAG, "클립보드 복사 실패", e)
        false
    }

    private fun sendIntent(info: RecordingInfo, prompt: String?, type: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, info.uri)
            if (prompt != null) putExtra(Intent.EXTRA_TEXT, prompt)
            putExtra(Intent.EXTRA_TITLE, info.name)
            clipData = ClipData.newRawUri(info.name, info.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /**
     * 회의록 만들기. [ctx] 는 Activity 또는 접근성 서비스 컨텍스트(백그라운드 실행 허용).
     * @return ChatGPT 로 직접 넘겼으면 true, 선택창으로 대체했으면 false
     */
    fun makeMinutes(ctx: Context, info: RecordingInfo): Boolean {
        val prompt = PromptBuilder.build(info.name)
        val copied = copyPrompt(ctx, prompt)
        AppLog.i(TAG, "회의록 만들기: ${info.name} (프롬프트 ${prompt.length}자, 클립보드 ${if (copied) "복사됨" else "실패"})")

        if (isChatGptInstalled(ctx)) {
            val pm = ctx.packageManager
            // ChatGPT 가 audio/mp4 를 공유 대상으로 선언하지 않았을 수 있어 점점 넓은 타입으로 시도
            for (type in listOf(info.mime, "audio/*", "*/*")) {
                val i = sendIntent(info, prompt, type).setPackage(CHATGPT)
                val targets = pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY)
                if (targets.isEmpty()) {
                    AppLog.i(TAG, "ChatGPT 가 '$type' 공유를 받지 않음")
                    continue
                }
                val target = targets.first().activityInfo
                i.setClassName(target.packageName, target.name)
                try {
                    ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    AppLog.i(TAG, "ChatGPT 로 공유: type=$type activity=${target.name}")
                    Notifier.toast(ctx, "ChatGPT로 넘겼습니다. 프롬프트는 클립보드에도 복사돼 있습니다(안 보이면 입력창을 길게 눌러 붙여넣기).")
                    if (Prefs.autoSendChatGpt) armAutoSend(prompt)
                    return true
                } catch (e: Exception) {
                    AppLog.w(TAG, "ChatGPT 공유 실행 실패(type=$type)", e)
                }
            }
        } else {
            AppLog.w(TAG, "ChatGPT 앱($CHATGPT)이 설치돼 있지 않음 → 일반 공유")
        }

        // 대체: 일반 공유 선택창
        return try {
            val chooser = Intent.createChooser(sendIntent(info, prompt, info.mime), "회의록 만들기 — 앱 선택")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(chooser)
            Notifier.toast(ctx, "ChatGPT로 바로 넘기지 못해 공유 선택창을 엽니다. 프롬프트는 클립보드에 복사돼 있습니다.")
            false
        } catch (e: Exception) {
            Notifier.alert(ctx, "회의록 만들기 실패", "공유 화면을 열 수 없습니다: ${e.message}")
            false
        }
    }

    private fun armAutoSend(prompt: String) {
        val s = AutomationService.instance
        if (s == null) {
            AppLog.w(TAG, "접근성 서비스가 없어 자동 전송을 할 수 없음 — ChatGPT에서 직접 전송하세요")
            return
        }
        s.armChatGptAutoSend(prompt)
    }

    /** 파일만 다른 앱으로 공유. */
    fun shareFile(ctx: Context, info: RecordingInfo) {
        try {
            val chooser = Intent.createChooser(sendIntent(info, null, info.mime), "녹음 파일 공유")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(chooser)
        } catch (e: Exception) {
            Notifier.alert(ctx, "공유 실패", e.message ?: "공유 화면을 열 수 없습니다")
        }
    }

    fun playIntent(info: RecordingInfo): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(info.uri, info.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    fun play(ctx: Context, info: RecordingInfo) {
        try {
            ctx.startActivity(playIntent(info))
        } catch (e: Exception) {
            Notifier.alert(ctx, "재생 실패", "이 파일을 재생할 앱이 없습니다: ${e.message}")
        }
    }
}
