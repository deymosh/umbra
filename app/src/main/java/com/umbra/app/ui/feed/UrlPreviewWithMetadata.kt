package com.umbra.app.ui.feed

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.datasource.DataSource
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.umbra.app.R
import com.umbra.app.domain.util.TrackingTokenSanitizer
import com.umbra.app.ui.common.UrlMetadata
import kotlinx.coroutines.launch

// Square, not the source image's own aspect ratio: og:image can be landscape, portrait or
// square with no reliable dimension hint in UrlMetadata to tell which ahead of render, and a
// fixed Fit-scaled box left landscape images as a barely-visible sliver while still not filling
// a portrait one either. Cropping to a square makes every orientation read as a substantial
// thumbnail instead.
private val PREVIEW_THUMBNAIL_SIZE = 88.dp

/**
 * URL preview card with metadata (title, description, image)
 * Displays cached metadata from prefetch, with fallback to simple link card
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UrlPreviewWithMetadata(
    modifier: Modifier = Modifier,
    metadata: UrlMetadata,
    torDataSourceFactory: DataSource.Factory? = null,
    onUrlClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.copied)
    // Sanitized for display/open/copy — the untouched metadata.url (the key the prefetch cache
    // used to fetch this metadata) is never itself shown or acted on, only this stripped version.
    val displayUrl = remember(metadata.url) { TrackingTokenSanitizer.sanitizeUrl(metadata.url) }

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
        tonalElevation = 4.dp
    ) {
        if (metadata.hasMetadata && (!metadata.imageUrl.isNullOrBlank() || !metadata.title.isNullOrBlank())) {
            // Rich preview with metadata — height is intrinsic (no fixed row height), so a
            // host+title-only preview stays compact instead of always reserving the same tall
            // slot a description-and-image preview needs.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    metadata.host?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    metadata.title?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    metadata.description?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Image thumbnail (if available)
                val imageUrl = metadata.imageUrl
                if (!imageUrl.isNullOrBlank() && torDataSourceFactory != null) {
                    val density = LocalDensity.current
                    val imageRequest = remember(context, imageUrl, density) {
                        val sizePx = with(density) { PREVIEW_THUMBNAIL_SIZE.roundToPx() }
                        ImageRequest.Builder(context)
                            .data(imageUrl)
                            .size(sizePx, sizePx)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .networkCachePolicy(CachePolicy.ENABLED)
                            .crossfade(false)
                            .build()
                    }
                    Box(
                        modifier = Modifier
                            .size(PREVIEW_THUMBNAIL_SIZE)
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            // Crop (not Fit) so a landscape source image doesn't shrink to a
                            // thin sliver inside a square slot, and a portrait one doesn't
                            // letterbox with empty space on either side — every orientation
                            // fills the thumbnail the same way.
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        } else {
            // Fallback: simple link card (no metadata)
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = metadata.host ?: "Link",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
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
}
