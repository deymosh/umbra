package com.umbra.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.umbra.app.domain.preferences.AppearancePreferences
import com.umbra.app.ui.UmbraNavHost
import com.umbra.app.ui.components.LocalImageLoadGate
import com.umbra.app.ui.components.LocalMediaLoadPriorityGate
import com.umbra.app.ui.theme.UmbraTheme
import com.umbra.app.ui.theme.toUmbraThemeOption
import com.umbra.app.util.BatteryOptimizationHelper
import com.umbra.app.util.ImageLoadGate
import com.umbra.app.util.MediaLoadPriorityGate
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var mediaLoadPriorityGate: MediaLoadPriorityGate
    @Inject
    lateinit var imageLoadGate: ImageLoadGate
    @Inject
    lateinit var appearancePreferences: AppearancePreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestBatteryOptimizationExemptionOnce()
        // NIP-21: nostr: URI deep link, if this activity was launched from one (see the VIEW
        // intent-filter in AndroidManifest.xml) — resolved once the app finishes its normal
        // Tor/login bootstrap and lands on the feed, see UmbraNavHost.
        val deepLinkUri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.toString()
        setContent {
            CompositionLocalProvider(
                LocalMediaLoadPriorityGate provides mediaLoadPriorityGate,
                LocalImageLoadGate provides imageLoadGate
            ) {
                val selectedTheme by appearancePreferences.observeSelectedTheme().collectAsState()
                UmbraTheme(themeOption = selectedTheme.toUmbraThemeOption()) {
                    UmbraNavHost(deepLinkUri = deepLinkUri)
                }
            }
        }
    }

    /**
     * Asks the OS, once per install, to stop applying battery-optimization/App-Standby
     * restrictions to Umbra — see BatteryOptimizationHelper.kt for why this (not a foreground
     * service) is the mitigation this project opted into. Best-effort: silently no-ops if the
     * device has no activity handling this system settings action, or if already exempt.
     */
    private fun requestBatteryOptimizationExemptionOnce() {
        if (BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) return
        if (BatteryOptimizationHelper.hasPromptedBefore(this)) return
        BatteryOptimizationHelper.markPrompted(this)
        try {
            startActivity(BatteryOptimizationHelper.createExemptionRequestIntent(this))
        } catch (_: ActivityNotFoundException) {
            // No system settings screen for this action on this device/ROM — nothing to do.
        }
    }
}
