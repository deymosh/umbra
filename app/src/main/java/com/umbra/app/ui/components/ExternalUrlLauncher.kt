package com.umbra.app.ui.components

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import java.util.Locale

internal fun launchExternalUrl(context: Context, rawUrl: String): Boolean {
    val normalizedUrl = normalizeAndValidateExternalUrl(rawUrl) ?: return false
    val uri = normalizedUrl.toUri()
    val scheme = uri.scheme?.lowercase(Locale.ROOT)

    if (scheme != "http" && scheme != "https") {
        return false
    }

    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        // LocalContext can be a wrapper/application context in some flows.
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    return runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

/**
 * Launches a `lightning:<bolt11>` URI in whatever wallet app is registered for it — deliberately
 * separate from [launchExternalUrl] rather than widening its scheme allowlist: that function's
 * http(s)-only gate (and normalizeAndValidateExternalUrl's, which it depends on) is the general
 * "any link in note content" path, and loosening it there would accept a `lightning:` URI
 * anywhere a plain web link is expected. This is scoped to exactly one known scheme, reachable
 * only from the Lightning invoice card's explicit "Pay" action.
 */
internal fun launchLightningInvoice(context: Context, rawUri: String): Boolean {
    if (!rawUri.startsWith("lightning:", ignoreCase = true)) return false
    val uri = runCatching { rawUri.toUri() }.getOrNull() ?: return false
    if (uri.schemeSpecificPart.isNullOrBlank()) return false

    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    return runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
/** URI schemes a payment hand-off may use: NIP-A3 targets' own schemes plus RFC 8905 `payto:`. */
private val PAYMENT_URI_SCHEMES = setOf(
    "lightning", "bitcoin", "ethereum", "litecoin", "monero", "bitcoincash", "zcash", "solana", "nano", "payto"
)

/**
 * Hands a payment URI to whichever wallet app claims it. Scoped to [PAYMENT_URI_SCHEMES] so it
 * can never be used to open an arbitrary link; callers show [ExternalUrlWarningDialog] first.
 */
internal fun launchPaymentUri(context: Context, rawUri: String): Boolean {
    val uri = runCatching { rawUri.trim().toUri() }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
    if (scheme !in PAYMENT_URI_SCHEMES || uri.schemeSpecificPart.isNullOrBlank()) return false
    val intent = Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
