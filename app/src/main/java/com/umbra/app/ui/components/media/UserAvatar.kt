package com.umbra.app.ui.components.media

import android.graphics.drawable.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.asDrawable
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.umbra.app.R
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.ui.components.LocalImageLoadGate
import com.umbra.app.ui.components.PubkeyEclipseAvatar
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private const val ANIMATED_AVATAR_PLACEHOLDER_DELAY_MS = 150L

@Composable
fun UserAvatar(
    modifier: Modifier = Modifier,
    userProfile: UserProfile?,
    pubkey: String,
    size: Dp = 40.dp,
    shape: Shape = CircleShape,
    animate: Boolean = true,
    // BUD-03 client-retrieval fallback inputs, threaded into rememberRetryingAsyncImagePainter's
    // own identically-named params — see that function's doc comment. Both null (the default)
    // simply disables the fallback for callers that don't have an author/repository in scope yet.
    // Compose-stability tradeoff: UserRepository is a plain (non-@Stable) interface, so taking it
    // as a parameter makes every UserAvatar instance unconditionally non-skippable, regardless of
    // whether a caller actually passes null or a real instance — stability is inferred from the
    // declared type, not the runtime value. UserAvatar is the app's single most frequently
    // instantiated composable (once per note header, repost banner, quoted-note card, gallery/
    // mention row, drawer/composer avatar), so this trades away recomposition-skipping for every
    // one of those call sites in exchange for Blossom-fallback candidacy. Accepted deliberately
    // rather than introducing a narrower stable wrapper type, since that would mean touching every
    // existing UserAvatar call site instead of the four that actually pass a non-null repository.
    authorPubkey: String? = null,
    userRepository: UserRepository? = null
) {
    val pictureUrl = userProfile?.picture?.takeIf { it.isNotBlank() }

    if (pictureUrl != null) {
        val context = LocalContext.current
        val avatarPx = with(LocalDensity.current) { size.roundToPx() }
        val memoryCacheKey = remember(pubkey, pictureUrl, avatarPx) {
            buildAvatarMemoryCacheKey(pubkey, pictureUrl, avatarPx)
        }

        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
        ) {
            if (isAnimatedAvatarUrl(pictureUrl)) {
                AnimatedUserAvatar(
                    pubkey = pubkey,
                    context = context,
                    pictureUrl = pictureUrl,
                    avatarPx = avatarPx,
                    memoryCacheKey = memoryCacheKey,
                    size = size,
                    shape = shape,
                    animate = animate
                )
            } else {
                // Unified with the feed's engine — this static avatar path now joins
                // ImageLoadGate's concurrency limit for the first time, and gains Blossom-fallback
                // (BUD-03) candidacy when a caller has authorPubkey/userRepository in scope. The
                // avatar-specific memoryCacheKey above is still used for AnimatedUserAvatar's own
                // (ungated-model) request below; rememberRetryingAsyncImagePainter builds its own
                // ImageRequest internally using Coil's default cache policy, matching every other
                // caller of that function.
                val gatedState = rememberRetryingAsyncImagePainter(
                    url = pictureUrl,
                    targetWidthPx = avatarPx,
                    targetHeightPx = avatarPx,
                    authorPubkey = authorPubkey,
                    userRepository = userRepository
                )
                val painter = gatedState.painter
                val painterState by painter.state.collectAsState()
                if (gatedState.isPending || painterState !is AsyncImagePainter.State.Success) {
                    AvatarDefaultPlaceholder(pubkey = pubkey, size = size, shape = shape)
                }
                Image(
                    painter = painter,
                    contentDescription = stringResource(R.string.profile_image_cd),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
    } else {
        AvatarDefaultPlaceholder(
            pubkey = pubkey,
            size = size,
            shape = shape,
            modifier = modifier
        )
    }
}

internal fun buildAvatarMemoryCacheKey(pubkey: String, pictureUrl: String, sizePx: Int): String {
    return "avatar:${pubkey.lowercase()}:$sizePx:$pictureUrl"
}

internal fun isAnimatedAvatarUrl(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#')
    return path.endsWith(".gif", ignoreCase = true)
}

internal fun buildAvatarImageRequest(
    context: android.content.Context,
    pictureUrl: String,
    avatarPx: Int,
    memoryCacheKey: String
): ImageRequest = ImageRequest.Builder(context)
    .data(pictureUrl)
    .size(avatarPx)
    .memoryCacheKey(memoryCacheKey)
    .placeholderMemoryCacheKey(memoryCacheKey)
    .diskCacheKey(pictureUrl)
    .memoryCachePolicy(CachePolicy.ENABLED)
    .diskCachePolicy(CachePolicy.ENABLED)
    .networkCachePolicy(CachePolicy.ENABLED)
    .crossfade(false)
    .build()

@Composable
private fun AnimatedUserAvatar(
    pubkey: String,
    context: android.content.Context,
    pictureUrl: String,
    avatarPx: Int,
    memoryCacheKey: String,
    size: Dp,
    shape: Shape,
    animate: Boolean
) {
    val gate = LocalImageLoadGate.current
    var showLoadingPlaceholder by remember(pictureUrl) { mutableStateOf(false) }
    var retryAttempt by remember(pictureUrl) { mutableIntStateOf(0) }
    // Sticky once true for this (pictureUrl, retryAttempt) key, mirroring
    // rememberRetryingAsyncImagePainter's hasDispatched — must never flip back to false once the
    // request has actually been handed to Coil, or the model below would revert to null and blank
    // out an already-loading/loaded image.
    var hasDispatched by remember(pictureUrl, retryAttempt) { mutableStateOf(false) }
    val imageRequest = remember(pictureUrl, avatarPx, memoryCacheKey, retryAttempt) {
        buildAvatarImageRequest(context, pictureUrl, avatarPx, memoryCacheKey)
    }

    LaunchedEffect(pictureUrl) {
        delay(ANIMATED_AVATAR_PLACEHOLDER_DELAY_MS)
        showLoadingPlaceholder = true
    }

    SubcomposeAsyncImage(
        model = if (hasDispatched) imageRequest else null,
        contentDescription = stringResource(R.string.profile_image_cd),
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
    ) {
        val painterState by painter.state.collectAsState()

        // Joins ImageLoadGate for the first time. SubcomposeAsyncImage's content lambda
        // reads painter.state directly, so this can't reuse rememberRetryingAsyncImagePainter's
        // return value — instead it calls the same runGatedImageLoad helper GatedImagePainter.kt
        // uses, one acquire/release pair per load attempt (keyed exactly like that engine's own
        // LaunchedEffect(url, candidateIndex, retryAttempt)), preserving the
        // acquire-before-try/release-in-finally discipline rather than leaving this path ungated.
        // The model above is withheld (kept null) until onDispatched actually flips hasDispatched,
        // so Coil doesn't dispatch the request over Tor until a gate permit is held.
        LaunchedEffect(pictureUrl, retryAttempt) {
            runGatedImageLoad(
                gate = gate,
                onDispatched = { hasDispatched = true },
                awaitTerminal = {
                    painter.state.first {
                        it is AsyncImagePainter.State.Success || it is AsyncImagePainter.State.Error
                    }
                }
            )
        }

        if (!hasDispatched) {
            // Queued behind ImageLoadGate, not yet handed to Coil — painter.state is still Empty
            // here (checked before the painterState when-branch below), so it can't be
            // distinguished from a plain not-yet-loading state without hasDispatched. Uses the
            // same elapsed-delay threshold as the Loading branch below so a queue that clears
            // quickly doesn't flash the heavier default placeholder.
            if (showLoadingPlaceholder) {
                AvatarDefaultPlaceholder(pubkey = pubkey, size = size, shape = shape)
            } else {
                AvatarPlaceholderBackground(size = size, shape = shape)
            }
        } else when (painterState) {
            is AsyncImagePainter.State.Success -> {
                val successState = painterState as AsyncImagePainter.State.Success
                val drawable = successState.result.image.asDrawable(LocalResources.current)
                DisposableEffect(drawable, animate) {
                    val animation = drawable as? Animatable
                    if (animate) animation?.start() else animation?.stop()
                    onDispose { animation?.stop() }
                }
                SubcomposeAsyncImageContent()
            }
            is AsyncImagePainter.State.Error -> {
                AvatarDefaultPlaceholder(pubkey = pubkey, size = size, shape = shape)
                // Same escalating-retry schedule as the static (non-GIF) avatar path above —
                // SubcomposeAsyncImage's content lambda has no direct access to that shared
                // helper (it needs painter.state from inside this scope), so the retry loop is
                // inlined here instead.
                LaunchedEffect(pictureUrl, retryAttempt) {
                    if (retryAttempt >= MAX_IMAGE_LOAD_RETRIES) return@LaunchedEffect
                    delay(IMAGE_RETRY_DELAYS_MS.getOrElse(retryAttempt) { IMAGE_RETRY_DELAYS_MS.last() })
                    retryAttempt += 1
                }
            }
            is AsyncImagePainter.State.Loading -> {
                if (showLoadingPlaceholder) {
                    AvatarDefaultPlaceholder(pubkey = pubkey, size = size, shape = shape)
                } else {
                    AvatarPlaceholderBackground(size = size, shape = shape)
                }
            }
            else -> AvatarPlaceholderBackground(size = size, shape = shape)
        }
    }
}

@Composable
private fun AvatarPlaceholderBackground(size: Dp, shape: Shape) {
    Surface(
        modifier = Modifier.size(size),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {}
}

@Composable
private fun AvatarDefaultPlaceholder(
    pubkey: String,
    size: Dp,
    shape: Shape,
    modifier: Modifier = Modifier
) {
    val description = stringResource(R.string.profile_image_cd)
    PubkeyEclipseAvatar(
        pubkey = pubkey,
        modifier = modifier
            .size(size)
            .clip(shape)
            .semantics { contentDescription = description }
    )
}
