package com.umbra.app.util

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Exemption from Android's battery-optimization/App-Standby restrictions makes relay
 * WebSocket connections and the in-memory event cache less likely to be killed by OEM
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
 * The exemption is requested only from Settings (a user-initiated action), never at startup —
 * a silent on-launch jump into a system dialog is not something Umbra does.
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

    /** Opens the system's all-apps battery-optimization list, where the user can find Umbra. */
    fun createSettingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
