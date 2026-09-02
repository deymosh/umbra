package com.umbra.app.ui.components

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.domain.util.TrackingTokenSanitizer
import kotlinx.coroutines.launch

/**
 * Fallback link preview for a URL with no fetched [com.umbra.app.ui.common.UrlMetadata] yet (or
 * fetch failed) — used by NostrTextRenderer's InlineMediaSegment.Url branch when
 * getUrlMetadata(url) returns null. Long-press-to-copy matches UrlPreviewWithMetadata's own
 * no-metadata fallback (metadata != null but hasMetadata false) — a URL preview shouldn't lose
 * that just because metadata hasn't loaded at all.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SimpleLinkCard(
    modifier: Modifier = Modifier,
    url: String,
    onUrlClick: (String) -> Unit = {}
) {
    // Sanitized for display/open/copy — the untouched `url` (the key getUrlMetadata prefetch used)
    // is never itself shown or acted on, only this tracking-token-stripped version of it.
    val displayUrl = remember(url) { TrackingTokenSanitizer.sanitizeUrl(url) }
    val host = remember(displayUrl) {
        runCatching { java.net.URI(displayUrl).host }
            .getOrNull()
            ?.removePrefix("www.")
            ?.ifBlank { null }
            ?: "Link"
    }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.copied)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onUrlClick(displayUrl) },
                onLongClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, displayUrl)))
                    }
                    Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                }
            ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = host,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = displayUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
