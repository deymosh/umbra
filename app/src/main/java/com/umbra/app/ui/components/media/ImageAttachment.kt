package com.umbra.app.ui.components.media

import android.graphics.drawable.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import coil3.asDrawable
import coil3.compose.AsyncImagePainter
import com.umbra.app.R
import com.umbra.app.domain.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.umbra.app.util.BlurHash

// See ImageAttachment's !hasKnownAspectRatio branch — a floor under Coil's own intrinsic-size-
// driven height so a loaded-but-imeta-less image can never render at zero height.
private val MIN_IMAGE_ATTACHMENT_HEIGHT = 160.dp

// See ImageAttachment's `compact` param — the capped height used for a note shown as context
// (e.g. the "replying to" card) rather than as its own post. Internal (not private) since
// InlineVideoAttachment's own `compact` sizing reuses the same cap — one shared "compact media"
// height rather than two constants that could drift apart.
internal val COMPACT_MEDIA_ATTACHMENT_HEIGHT = 180.dp

// Decoded small and scaled up by Compose — a blurhash placeholder is meant to look soft, so
// there's no benefit decoding at anything close to display resolution.
private const val BLURHASH_DECODE_WIDTH = 32

// Matches UserIdentityBadge.kt's NIP-05-pending badge exactly, so "queued" reads as the same
// visual language everywhere in the app rather than inventing a second one.
private val PENDING_AMBER = Color(0xFFF9A825)

/**
 * Small top-end corner status badge drawn over a blurhash placeholder — the placeholder already
 * fills the whole attachment, so unlike the no-blurhash case there's no need for a full-size
 * overlay; this is just enough to tell "queued" (clock) apart from "downloading" (spinner) apart
 * from "failed" (X) without hiding the soft preview underneath in any of the three states.
 */
@Composable
private fun BoxScope.BlurHashStatusBadge(icon: androidx.compose.ui.graphics.vector.ImageVector?, showSpinner: Boolean, isError: Boolean = false) {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(6.dp)
            .size(20.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center
    ) {
        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                color = Color.White,
                strokeWidth = 1.5.dp
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isError) MaterialTheme.colorScheme.error else PENDING_AMBER,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
fun ImageAttachment(
    url: String,
    onOpenFullscreen: () -> Unit,
    animate: Boolean = true,
    // NIP-92 imeta `alt` — falls back to the generic content description when the event
    // didn't provide one.
    contentDescription: String? = null,
    // NIP-92 imeta `dim`, as width/height — reserves the correct layout space before the
    // image loads instead of the container jumping from 0 to full height once it decodes.
    aspectRatio: Float? = null,
    // NIP-92 imeta `blurhash` — decoded off the main thread and shown in place of the plain
    // gray loading box while the real image fetches.
    blurHash: String? = null,
    // Caps the rendered height instead of the normal full aspect-ratio/natural-growth sizing —
    // for contexts showing a note as context rather than as its own post (e.g. the "replying to"
    // card above a reply composer), where a full-bleed image would otherwise dominate the screen.
    // Always crops to fill the capped box, same as a link-preview thumbnail.
    compact: Boolean = false,
    // BUD-03 client-retrieval fallback — see rememberRetryingAsyncImagePainter's doc comment.
    authorPubkey: String? = null,
    userRepository: UserRepository? = null
) {
    val windowInfo = LocalWindowInfo.current
    val targetWidthPx = remember(windowInfo) {
        windowInfo.containerSize.width.coerceAtLeast(1)
    }
    val targetHeightPx = remember(windowInfo) {
        (windowInfo.containerSize.height * 3).coerceAtLeast(1)
    }

    // Only reserve a fixed height (and crop to fill it) when imeta declared trustworthy
    // dimensions — otherwise keep the original natural-growth behavior unchanged.
    val hasKnownAspectRatio = aspectRatio != null && aspectRatio > 0f

    // Loading/Error placeholders always claim the full reserved area — the aspect-ratio box
    // when known, otherwise the MIN_IMAGE_ATTACHMENT_HEIGHT floor below — instead of sizing to
    // the Coil painter's own intrinsic size (Size.Unspecified while loading/failed), which used
    // to collapse them to near-nothing and left the error icon pinned near the top-start corner
    // instead of centered in the reserved space.
    val placeholderModifier = if (compact) {
        Modifier.fillMaxSize()
    } else if (hasKnownAspectRatio) {
        Modifier.fillMaxSize()
    } else {
        Modifier.fillMaxWidth().height(MIN_IMAGE_ATTACHMENT_HEIGHT)
    }

    val blurHashBitmap by produceState<android.graphics.Bitmap?>(initialValue = null, blurHash, aspectRatio) {
        value = if (blurHash.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.Default) {
                BlurHash.decode(blurHash, width = BLURHASH_DECODE_WIDTH, aspectRatio = aspectRatio)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .let {
                if (compact && hasKnownAspectRatio) {
                    // Height derived from the image's own aspect ratio (capped at the compact
                    // max) rather than an unconditional fixed box — a smaller/shorter-than-cap
                    // image is sized to its real proportions instead of being stretched to fill
                    // COMPACT_MEDIA_ATTACHMENT_HEIGHT. heightIn before aspectRatio matters here:
                    // it constrains the max height aspectRatio is allowed to compute into, so a
                    // taller image still gets capped-and-cropped exactly as before.
                    it.heightIn(max = COMPACT_MEDIA_ATTACHMENT_HEIGHT).aspectRatio(aspectRatio)
                } else if (compact) {
                    it.heightIn(max = COMPACT_MEDIA_ATTACHMENT_HEIGHT)
                } else if (hasKnownAspectRatio) {
                    it.aspectRatio(aspectRatio)
                } else {
                    // Without imeta dimensions, height is otherwise driven entirely by the
                    // Coil painter's own intrinsic size, which is Size.Unspecified until the
                    // first frame decodes — collapsing this to zero height for a beat (or, in
                    // a LazyColumn with unbounded max-height item constraints, indefinitely)
                    // even for an image that loads successfully. A minimum height guarantees
                    // the attachment is never invisible while genuinely loaded content is
                    // sitting behind a zero-size layout.
                    it.heightIn(min = MIN_IMAGE_ATTACHMENT_HEIGHT)
                }
            }
            .clip(IMAGE_GALLERY_CORNER)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .let {
                    // Mirrors the outer Box's own height handling — without this, the outer
                    // Box's heightIn(min=...)/heightIn(max=...) floor/ceiling constrains only
                    // itself, not this inner Box. If the Image below ends up measured at
                    // (momentarily or persistently) near-zero height — e.g. the painter's
                    // intrinsic size not yet reflecting a just-finished Success state — this
                    // Box would independently collapse to that size instead of inheriting the
                    // outer bound, rendering as blank space with no Loading/Error overlay to
                    // explain it (Success shows no overlay at all).
                    when {
                        compact && hasKnownAspectRatio ->
                            it.heightIn(max = COMPACT_MEDIA_ATTACHMENT_HEIGHT).aspectRatio(aspectRatio)
                        compact -> it.heightIn(max = COMPACT_MEDIA_ATTACHMENT_HEIGHT)
                        hasKnownAspectRatio -> it.fillMaxHeight()
                        else -> it.heightIn(min = MIN_IMAGE_ATTACHMENT_HEIGHT)
                    }
                }
                .clickable { onOpenFullscreen() }
        ) {
            val gatedState = rememberRetryingAsyncImagePainter(
                url,
                targetWidthPx,
                targetHeightPx,
                authorPubkey = authorPubkey,
                userRepository = userRepository
            )
            val painter = gatedState.painter
            val painterState by painter.state.collectAsState()
            val currentBlurHashBitmap = blurHashBitmap

            Image(
                painter = painter,
                contentDescription = contentDescription ?: stringResource(R.string.event_image_cd),
                modifier = if (compact || hasKnownAspectRatio) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                contentScale = if (compact || hasKnownAspectRatio) ContentScale.Crop else ContentScale.FillWidth
            )

            when {
                // Queued behind ImageLoadGate, not yet handed to Coil — see
                // rememberRetryingAsyncImagePainter's isPending doc comment. Checked before
                // painterState below since a pending image's painter is still Empty, not Loading.
                gatedState.isPending -> {
                    if (currentBlurHashBitmap != null) {
                        Image(
                            bitmap = currentBlurHashBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = placeholderModifier,
                            contentScale = ContentScale.Crop
                        )
                        BlurHashStatusBadge(icon = Icons.Default.Schedule, showSpinner = false)
                    } else {
                        Box(
                            modifier = placeholderModifier
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = PENDING_AMBER,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
                painterState is AsyncImagePainter.State.Loading -> {
                    if (currentBlurHashBitmap != null) {
                        Image(
                            bitmap = currentBlurHashBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = placeholderModifier,
                            contentScale = ContentScale.Crop
                        )
                        BlurHashStatusBadge(icon = null, showSpinner = true)
                    } else {
                        // No blurhash to show while this loads — a plain color box alone read as
                        // "nothing here yet" rather than "an image is on its way", so a small
                        // spinner makes the in-flight state unambiguous.
                        Box(
                            modifier = placeholderModifier
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }
                painterState is AsyncImagePainter.State.Error -> {
                    if (currentBlurHashBitmap != null) {
                        // Keep showing the blurhash preview instead of discarding it for a plain
                        // error box — a retry-exhausted failure shouldn't lose the soft preview
                        // the user already had.
                        Image(
                            bitmap = currentBlurHashBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = placeholderModifier,
                            contentScale = ContentScale.Crop
                        )
                        BlurHashStatusBadge(icon = Icons.Default.Close, showSpinner = false, isError = true)
                    } else {
                        Box(modifier = placeholderModifier, contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.image_load_error),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(42.dp)
                            )
                        }
                    }
                }
                painterState is AsyncImagePainter.State.Success -> {
                    // A static drawable isn't an Animatable, so start()/stop() are no-ops for
                    // non-GIF images — this only affects actually-animated (GIF/animated WebP)
                    // content, driven by the caller-supplied `animate` flag.
                    val successState = painterState as AsyncImagePainter.State.Success
                    val drawable = successState.result.image.asDrawable(LocalResources.current)
                    DisposableEffect(drawable, animate) {
                        val animation = drawable as? Animatable
                        if (animate) animation?.start() else animation?.stop()
                        onDispose { animation?.stop() }
                    }
                }
                else -> Unit
            }
        }
    }
}
