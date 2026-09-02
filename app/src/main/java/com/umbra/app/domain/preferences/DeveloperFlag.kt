package com.umbra.app.domain.preferences

/**
 * Developer/debug toggles surfaced in Settings > Developer Options. All default to disabled;
 * add a new entry here to introduce another toggle without touching the storage layer.
 */
enum class DeveloperFlag(val prefKey: String) {
    ENABLE_FEED_ERROR_BANNER("enable_feed_error_banner"),
    SHOW_ALL_RELAY_BANNERS("show_all_relay_banners"),
    SHOW_RELAY_TELEMETRY("show_relay_telemetry")
}
