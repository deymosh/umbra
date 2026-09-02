package com.umbra.app.util

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.net.toUri

private const val PREFS_NAME = "umbra_app_prefs"
private const val KEY_BATTERY_OPT_PROMPTED = "battery_optimization_prompted"

/**
 * Requests exemption from Android's battery-optimization/App-Standby restrictions so relay
 * WebSocket connections and the in-memory event cache are less likely to be killed by OEM
 * battery managers while Umbra is backgrounded. This is a best-effort, no-notification
 * mitigation — unlike a foreground service, it does nothing against the OS's real low-memory
 * killer under genuine memory pressure, only against battery-driven background-kill heuristics.
 * Deliberately not paired with a foreground service: that would additionally protect against the
 * low-memory killer, but requires an always-visible notification, a different privacy trade-off
 * this project hasn't opted into.
 *
 * Umbra ships outside the Play Store (F-Droid/direct APK/Zapstore), so Play's restricted-use
 * policy for `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — the reason lint's `BatteryLife` check is
 * suppressed below — doesn't apply here.
 *
 * Uses a plain (non-secure) SharedPreferences flag — not [com.umbra.app.data.security.
 * SecurePreferences] — since "have we shown this system dialog once" isn't sensitive data.
 */
object BatteryOptimizationHelper {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    @Suppress("BatteryLife")
    fun createExemptionRequestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:${context.packageName}".toUri()
        }

    /**
     * Whether the system exemption dialog has already been shown once this install — asked at
     * most once so a user who dismisses it isn't nagged again on every cold start.
     */
    fun hasPromptedBefore(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BATTERY_OPT_PROMPTED, false)

    fun markPrompted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_BATTERY_OPT_PROMPTED, true) }
    }
}
