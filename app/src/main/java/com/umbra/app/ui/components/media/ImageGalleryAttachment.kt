package com.umbra.app.ui.components.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import com.umbra.app.R
import com.umbra.app.domain.repository.UserRepository

internal val IMAGE_GALLERY_SPACING = 6.dp
internal val IMAGE_GALLERY_CORNER = RoundedCornerShape(12.dp)

// Matches UserIdentityBadge.kt's NIP-05-pending badge exactly, so "queued" reads as the same
// visual language everywhere in the app rather than inventing a second one.
private val PENDING_AMBER = Color(0xFFF9A825)

@Composable
fun ImageGalleryAttachment(
    urls: List<String>,
    onOpenFullscreen: (String) -> Unit,
    // BUD-03 client-retrieval fallback — see rememberRetryingAsyncImagePainter's doc comment.
    authorPubkey: String? = null,
    userRepository: UserRepository? = null
) {
    if (urls.isEmpty()) return

    if (urls.size == 1) {
        ImageAttachment(
            url = urls.first(),
            onOpenFullscreen = { onOpenFullscreen(urls.first()) },
            authorPubkey = authorPubkey,
            userRepository = userRepository
        )
        return
    }

    val previewUrls = urls.take(4)
    val overflowCount = urls.size - previewUrls.size

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .padding(vertical = 4.dp)
    ) {
        when (previewUrls.size) {
            2 -> {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(IMAGE_GALLERY_SPACING)
                ) {
                    previewUrls.forEach { url ->
                        GalleryImageCell(
                            url = url,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            onClick = { onOpenFullscreen(url) },
                            authorPubkey = authorPubkey,
                            userRepository = userRepository
                        )
                    }
                }
            }

            3 -> {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(IMAGE_GALLERY_SPACING)
                ) {
                    GalleryImageCell(
                        url = previewUrls[0],
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        onClick = { onOpenFullscreen(previewUrls[0]) },
                        authorPubkey = authorPubkey,
                        userRepository = userRepository
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(IMAGE_GALLERY_SPACING)
                    ) {
                        GalleryImageCell(
                            url = previewUrls[1],
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            onClick = { onOpenFullscreen(previewUrls[1]) },
                            authorPubkey = authorPubkey,
                            userRepository = userRepository
                        )
                        GalleryImageCell(
                            url = previewUrls[2],
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            onClick = { onOpenFullscreen(previewUrls[2]) },
                            authorPubkey = authorPubkey,
                            userRepository = userRepository
                        )
                    }
                }
            }

            else -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(IMAGE_GALLERY_SPACING)
                ) {
                    for (rowIndex in 0 until 2) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(IMAGE_GALLERY_SPACING)
                        ) {
                            for (columnIndex in 0 until 2) {
                                val index = rowIndex * 2 + columnIndex
                                val url = previewUrls[index]
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                ) {
                                    GalleryImageCell(
                                        url = url,
                                        modifier = Modifier.fillMaxSize(),
                                        onClick = { onOpenFullscreen(url) },
                                        authorPubkey = authorPubkey,
                                        userRepository = userRepository
                                    )

                                    if (index == 3 && overflowCount > 0) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(alpha = 0.45f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "+$overflowCount",
                                                style = MaterialTheme.typography.headlineSmall,
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryImageCell(
    url: String,
    modifier: Modifier,
    onClick: () -> Unit,
    authorPubkey: String? = null,
    userRepository: UserRepository? = null
) {
    val windowInfo = LocalWindowInfo.current
    val targetCellPx = remember(windowInfo) {
        (windowInfo.containerSize.width / 2).coerceAtLeast(1)
    }

    Box(
        modifier = modifier
            .clip(IMAGE_GALLERY_CORNER)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .clickable { onClick() }
    ) {
        val gatedState = rememberRetryingAsyncImagePainter(
            url,
            targetCellPx,
            targetCellPx,
            authorPubkey = authorPubkey,
            userRepository = userRepository
        )
        val painter = gatedState.painter
        val painterState by painter.state.collectAsState()

        Image(
            painter = painter,
            contentDescription = stringResource(R.string.event_image_cd),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        when {
            // No blurhash in the gallery-cell case, so this mirrors ImageAttachment's plain
            // (no-blurhash) pending/loading/error handling only — see its doc comment.
            gatedState.isPending -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = PENDING_AMBER,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            painterState is AsyncImagePainter.State.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        strokeWidth = 2.dp
                    )
                }
            }
            painterState is AsyncImagePainter.State.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.image_load_error),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
            else -> Unit
        }
    }
}
