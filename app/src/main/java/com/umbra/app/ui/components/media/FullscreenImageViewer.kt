package com.umbra.app.ui.components.media

import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImagePainter
import com.umbra.app.R
import com.umbra.app.ui.components.FullscreenMediaAction
import com.umbra.app.ui.components.FullscreenMediaActionMenu
import com.umbra.app.ui.components.ImmersiveSystemBarsEffect
import com.umbra.app.ui.components.LocalMediaLoadPriorityGate
import com.umbra.app.ui.components.copyImageToClipboard
import com.umbra.app.ui.components.enqueueImageDownload
import com.umbra.app.ui.components.materializeImageContentUri
import com.umbra.app.ui.components.shareImage
import kotlinx.coroutines.launch

@Composable
fun FullscreenImageDialog(
    imageUrls: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    if (imageUrls.isEmpty()) return

    val context = LocalContext.current
    val mediaLoadPriorityGate = LocalMediaLoadPriorityGate.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val clampedInitialIndex = initialIndex.coerceIn(0, imageUrls.lastIndex)
    val pagerState = rememberPagerState(
        initialPage = clampedInitialIndex,
        pageCount = { imageUrls.size }
    )
    var currentPageScale by remember { mutableFloatStateOf(1f) }
    var zoomResetToken by remember { mutableIntStateOf(0) }
    var actionMenuExpanded by remember { mutableStateOf(false) }

    // Swiping to a different image with the menu open would otherwise leave it expanded over
    // whatever's now showing, anchored to a different image's aspect ratio.
    LaunchedEffect(pagerState.currentPage) {
        actionMenuExpanded = false
    }

    DisposableEffect(mediaLoadPriorityGate) {
        val priorityLease = mediaLoadPriorityGate.beginInteractiveLoad()
        onDispose { priorityLease.close() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        // decorFitsSystemWindows = false: without it the dialog window is auto-fitted by the OS
        // to avoid the status/navigation bars, so fillMaxSize() below fills that *shrunk* area,
        // not the true screen — a visible gap at the top in portrait, and at the top and
        // whichever side the nav bar landed on in landscape (see the matching fix on
        // FullscreenVideoDialog for the same root cause).
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            dismissOnBackPress = false,
            decorFitsSystemWindows = false
        )
    ) {
        ImmersiveSystemBarsEffect()

        BackHandler {
            if (currentPageScale > 1.01f) {
                zoomResetToken += 1
            } else {
                onDismiss()
            }
        }

        val currentImageUrl = imageUrls[pagerState.currentPage]

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f)),
            contentAlignment = Alignment.Center
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = currentPageScale <= 1.01f,
                // Up to 2 neighbor pages (one on each side of
                // the current page) are pre-composed and start loading under the same
                // interactive-priority signal as the current page, instead of Compose
                // Foundation's default of 0 (only the current page composed). This is a
                // deliberate swipe-responsiveness/bandwidth tradeoff, not a preservation of
                // prior behavior — it gives the reserved-interactive-permit pool in
                // ImageLoadGate real neighbor pages to protect.
                beyondViewportPageCount = 1
            ) { page ->
                ZoomableFullscreenImagePage(
                    imageUrl = imageUrls[page],
                    isCurrentPage = page == pagerState.currentPage,
                    zoomResetToken = zoomResetToken,
                    onScaleChanged = { nextScale ->
                        if (page == pagerState.currentPage) {
                            currentPageScale = nextScale
                        }
                    }
                )
            }

            if (imageUrls.size > 1) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .systemBarsPadding()
                        .padding(bottom = 20.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(50)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(imageUrls.size) { index ->
                        val isCurrent = index == pagerState.currentPage
                        Box(
                            modifier = Modifier
                                .size(if (isCurrent) 8.dp else 6.dp)
                                .background(
                                    color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.4f),
                                    shape = CircleShape
                                )
                        )
                    }
                }
            }

            FullscreenMediaActionMenu(
                // Always the vertical dropdown layout regardless of the current image's aspect
                // ratio — FullscreenMediaActionMenu still supports the horizontal layout for a
                // future caller that wants it, this call site just no longer opts into it.
                isContentPortrait = true,
                expanded = actionMenuExpanded,
                onExpandedChange = { actionMenuExpanded = it },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // systemBarsPadding (status+nav), not safeDrawingPadding — the latter also
                    // reserves space for the display cutout, which pushed this corner menu down
                    // further than it needed to be; a fullscreen viewer has no reason to reserve
                    // cutout space for a corner menu. Landscape can still put the nav bar on
                    // whichever side this TopEnd-aligned menu would otherwise sit flush against,
                    // which systemBars covers.
                    .systemBarsPadding()
                    .padding(12.dp),
                actions = listOf(
                    FullscreenMediaAction(
                        icon = Icons.Default.FileDownload,
                        contentDescription = stringResource(R.string.image_download_action),
                        onClick = {
                            scope.launch {
                                val saved = enqueueImageDownload(context, currentImageUrl)
                                val messageId = if (saved) {
                                    R.string.image_download_completed
                                } else {
                                    R.string.image_download_failed
                                }
                                Toast.makeText(context, context.getString(messageId), Toast.LENGTH_SHORT).show()
                            }
                        }
                    ),
                    FullscreenMediaAction(
                        icon = Icons.Default.Link,
                        contentDescription = "Copy image URL",
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, currentImageUrl)))
                            }
                            Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                        }
                    ),
                    FullscreenMediaAction(
                        icon = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.image_copy_action),
                        onClick = {
                            scope.launch {
                                val imageUri = materializeImageContentUri(context, currentImageUrl)
                                if (imageUri == null) {
                                    Toast.makeText(context, context.getString(R.string.image_copy_failed), Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                copyImageToClipboard(context, imageUri)
                                Toast.makeText(context, context.getString(R.string.image_copy_success), Toast.LENGTH_SHORT).show()
                            }
                        }
                    ),
                    FullscreenMediaAction(
                        icon = Icons.Default.Share,
                        contentDescription = stringResource(R.string.image_share_action),
                        onClick = {
                            scope.launch {
                                val imageUri = materializeImageContentUri(context, currentImageUrl)
                                if (imageUri == null) {
                                    Toast.makeText(context, context.getString(R.string.image_share_failed), Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                shareImage(context, imageUri)
                            }
                        }
                    )
                )
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ZoomableFullscreenImagePage(
    imageUrl: String,
    isCurrentPage: Boolean,
    zoomResetToken: Int,
    onScaleChanged: (Float) -> Unit
) {
    var scale by remember(imageUrl) { mutableFloatStateOf(1f) }
    var offset by remember(imageUrl) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember(imageUrl) { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(zoomResetToken) {
        scale = 1f
        offset = Offset.Zero
    }

    LaunchedEffect(scale, isCurrentPage) {
        if (isCurrentPage) {
            onScaleChanged(scale)
        }
    }

    val transformState = rememberTransformableState { _, zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
        if (nextScale <= 1.01f) {
            scale = 1f
            offset = Offset.Zero
        } else {
            scale = nextScale
            val rawOffset = offset + (panChange * nextScale)
            offset = clampImageOffset(rawOffset, nextScale, viewportSize)
        }
    }

    // Fullscreen has no author/repository context to thread (FullscreenImageDialog's own
    // signature takes only image URLs) — null/null is the documented, correct default per
    // rememberRetryingAsyncImagePainter's own nullable params: retry+gate still apply in full,
    // just without the BUD-03 Blossom-fallback candidate list, matching how custom emoji already
    // uses this engine gate-and-retry-only.
    val windowInfo = LocalWindowInfo.current
    val targetWidthPx = remember(windowInfo) { windowInfo.containerSize.width.coerceAtLeast(1) }
    val targetHeightPx = remember(windowInfo) { windowInfo.containerSize.height.coerceAtLeast(1) }
    val gatedState = rememberRetryingAsyncImagePainter(
        url = imageUrl,
        targetWidthPx = targetWidthPx,
        targetHeightPx = targetHeightPx,
        authorPubkey = null,
        userRepository = null
    )
    val painter = gatedState.painter
    val painterState by painter.state.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { viewportSize = it }
            .pointerInput(scale, viewportSize) {
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        if (viewportSize == IntSize.Zero) return@detectTapGestures

                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val targetScale = 2.5f
                            scale = targetScale
                            offset = clampImageOffset(
                                offset = calculateDoubleTapOffset(
                                    tapOffset = tapOffset,
                                    viewportSize = viewportSize,
                                    targetScale = targetScale
                                ),
                                scale = targetScale,
                                viewportSize = viewportSize
                            )
                        }
                    }
                )
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .transformable(
                state = transformState,
                canPan = { scale > 1.01f }
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            // Queued behind ImageLoadGate, not yet handed to Coil, or Coil's own Loading state —
            // same pending-then-loading distinction ImageAttachment.kt's when-branch documents.
            gatedState.isPending || painterState is AsyncImagePainter.State.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            painterState is AsyncImagePainter.State.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.image_load_error),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(54.dp)
                    )
                }
            }
            else -> {
                Image(
                    painter = painter,
                    contentDescription = stringResource(R.string.event_image_cd),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

private fun clampImageOffset(
    offset: Offset,
    scale: Float,
    viewportSize: IntSize
): Offset {
    if (viewportSize == IntSize.Zero || scale <= 1f) return Offset.Zero

    val maxTranslationX = (viewportSize.width * (scale - 1f)) / 2f
    val maxTranslationY = (viewportSize.height * (scale - 1f)) / 2f

    return Offset(
        x = offset.x.coerceIn(-maxTranslationX, maxTranslationX),
        y = offset.y.coerceIn(-maxTranslationY, maxTranslationY)
    )
}

private fun calculateDoubleTapOffset(
    tapOffset: Offset,
    viewportSize: IntSize,
    targetScale: Float
): Offset {
    val center = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
    return (center - tapOffset) * (targetScale - 1f)
}
