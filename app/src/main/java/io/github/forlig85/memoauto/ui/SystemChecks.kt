package io.github.forlig85.memoauto.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import io.github.forlig85.memoauto.AppLog
import io.github.forlig85.memoauto.a11y.AutomationService

/** 권한·시스템 설정 상태 확인과 설정 화면 열기. */
object SystemChecks {
    fun hasMic(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun hasNotif(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun accessibilityEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val cn = ComponentName(ctx, AutomationService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == cn }
    }

    fun overlayAllowed(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun batteryUnrestricted(ctx: Context) =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun open(ctx: Context, intent: Intent, fallback: Intent? = null) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.w("설정", "설정 화면 열기 실패: ${intent.action}", e)
            if (fallback != null) runCatching { ctx.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    fun appDetails(ctx: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    fun openAccessibility(ctx: Context) = open(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openOverlay(ctx: Context) =
        open(ctx, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")), appDetails(ctx))

    @android.annotation.SuppressLint("BatteryLife")
    fun openBattery(ctx: Context) =
        open(
            ctx,
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )

    fun openAppDetails(ctx: Context) = open(ctx, appDetails(ctx))
}
