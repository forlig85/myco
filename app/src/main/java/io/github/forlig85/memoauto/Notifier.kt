package io.github.forlig85.memoauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.forlig85.memoauto.recording.RecorderService
import io.github.forlig85.memoauto.ui.MainActivity
import java.util.concurrent.atomic.AtomicInteger

/** 알림·토스트. 실패는 조용히 넘기지 않고 여기로 모은다. */
object Notifier {
    const val CH_RECORDING = "recording"
    const val CH_ALERT = "alerts"
    const val CH_DONE = "done"

    const val ID_RECORDING = 1001
    const val ID_DONE = 3001
    private val alertIds = AtomicInteger(2000)
    private val main = Handler(Looper.getMainLooper())

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_RECORDING, "회의 녹음 중", NotificationManager.IMPORTANCE_LOW).apply {
                description = "녹음이 진행 중일 때 표시"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "오류·경고", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "녹음 실패, 무음 감지, 타일을 못 찾음 등"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "녹음 완료", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "회의 녹음이 저장되었을 때"
            }
        )
    }

    private fun openAppIntent(ctx: Context, tab: Int = 0): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_TAB, tab)
        return PendingIntent.getActivity(ctx, 10 + tab, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun recording(ctx: Context, title: String, text: String): Notification {
        val stop = Intent(ctx, RecorderService::class.java).setAction(RecorderService.ACTION_STOP)
        val stopPi = PendingIntent.getService(ctx, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(ctx, CH_RECORDING)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setContentIntent(openAppIntent(ctx))
            .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_mic), "녹음 중지", stopPi).build())
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * 오류/경고: 로그 + 알림 + 토스트. [key] 가 같으면 같은 알림을 갱신한다.
     */
    fun alert(ctx: Context, title: String, text: String, key: Int? = null, toast: Boolean = true) {
        AppLog.w("알림", "$title — $text")
        val id = key ?: alertIds.incrementAndGet()
        val n = Notification.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx, MainActivity.TAB_LOG))
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(id, n) }
        if (toast) toast(ctx, "$title: $text")
    }

    fun cancel(ctx: Context, id: Int) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(id) }
    }

    fun saved(ctx: Context, name: String, uri: Uri, mime: String) {
        val play = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val playPi = PendingIntent.getActivity(ctx, 20, play, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("회의 녹음 완료")
            .setContentText(name)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx))
            .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_mic), "재생", playPi).build())
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID_DONE, n) }
    }

    fun toast(ctx: Context, text: String) {
        val app = ctx.applicationContext
        main.post { runCatching { Toast.makeText(app, text, Toast.LENGTH_LONG).show() } }
    }
}
