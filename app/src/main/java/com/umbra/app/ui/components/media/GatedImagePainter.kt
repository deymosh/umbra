package com.umbra.app.ui.components.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import com.umbra.app.domain.nipb7.blossomFallbackCandidates
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.ui.components.LocalImageLoadGate
import com.umbra.app.util.ImageLoadGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

// Tor circuit builds to an image host occasionally blow past OkHttp's 30s connect timeout
// (NetworkModule's shared "tor" client) even though the image is genuinely reachable — the
// existing workaround was tapping through to the fullscreen viewer, which issues a fresh
// request that usually succeeds given a little more time. These retry an Error state
// automatically instead of leaving the attachment permanently failed: each attempt is a new
// ImageRequest for the same url, so a fullscreen view that succeeded in the meantime is picked
// up from Coil's memory/disk cache (near-instant) rather than re-hitting the network.
// internal (not private): reused directly by UserAvatar.kt's static and animated avatar paths
// and by ProfileScreen.kt's banner for the signed-in user's own avatar/banner, the same
// Tor-circuit-build failure mode as feed images.
internal const val MAX_IMAGE_LOAD_RETRIES = 4
internal val IMAGE_RETRY_DELAYS_MS = listOf(2_000L, 5_000L, 12_000L, 30_000L)

// BUD-03 client-retrieval fallback: once the original URL's own retry budget above is
// exhausted, each alternate Blossom server candidate (author's kind:10063 list, then the app
// default) gets exactly one attempt rather than the same multi-retry treatment — a genuinely
// broken original URL shouldn't multiply total wait time by retries-per-candidate, and transient
// Tor slowness is already what the retries above are for.
internal const val BLOSSOM_FALLBACK_DELAY_MS = 1_500L

/**
 * [painter] plus [isPending] — true while this image's load is queued behind [ImageLoadGate]
 * and hasn't been dispatched to Coil yet (still `Empty`, not `Loading`, from the painter's own
 * point of view). Distinct from Coil's `Loading`, which only starts once a permit is granted.
 */
internal data class GatedImagePainterState(
    val painter: AsyncImagePainter,
    val isPending: Boolean
)

/**
 * Owns an [ImageLoadGate] permit's entire lifecycle in one coroutine: acquire, wait for this
 * attempt's terminal state via [awaitTerminal], then release in `finally` — guaranteed to run on
 * both normal completion and on cancellation (key change, or the enclosing composable leaving
 * composition). The permit used to be acquired and released across three separately-keyed
 * effects; a window between Compose's synchronous DisposableEffect teardown and the acquiring
 * coroutine's own (suspension-point-gated) cancellation let a permit be acquired by a coroutine
 * that was already being torn down, with no other effect left alive to release it — permanently
 * shrinking the pool below the gate's configured concurrency limit and leaving later images stuck
 * pending even though their bytes were already cached (only "fixed" by disposing/recreating the
 * caller's state, e.g. scrolling the image away and back, or the fullscreen viewer's separate
 * ungated load path). [onDispatched] marks the point at which the request has actually been
 * handed off (e.g. to Coil) — kept as a separate parameter from [awaitTerminal] so callers can
 * flip local state (like `hasDispatched`) at exactly the right moment. Pulled out of the
 * composable body into its own top-level suspend function so this lifecycle is directly
 * unit-testable without a Compose composition.
 */
internal suspend fun runGatedImageLoad(
    gate: ImageLoadGate,
    onDispatched: () -> Unit,
    awaitTerminal: suspend () -> Unit
) {
    val lease = gate.acquire()
    try {
        onDispatched()
        awaitTerminal()
    } finally {
        gate.release(lease)
    }
}

@Composable
internal fun rememberRetryingAsyncImagePainter(
    url: String,
    targetWidthPx: Int,
    targetHeightPx: Int,
    // BUD-03 client-retrieval fallback inputs — both null for contexts with no known author
    // (custom emoji, avatars not yet wired), which just disables the fallback and keeps the
    // existing same-URL-only retry behavior.
    authorPubkey: String? = null,
    userRepository: UserRepository? = null
): GatedImagePainterState {
    val context = LocalContext.current
    val gate = LocalImageLoadGate.current

    // Original URL first, then (if a hash is extractable and the author has a kind:10063 list)
    // each of their servers, then the app default — see blossomFallbackCandidates's doc comment.
    // getServerList is a synchronous in-memory read (UserRepositoryImpl), safe to call directly.
    val candidates = remember(url, authorPubkey) {
        val serverList = authorPubkey?.let { userRepository?.getServerList(it) }
        blossomFallbackCandidates(url, serverList)
    }
    var candidateIndex by remember(url, authorPubkey) { mutableIntStateOf(0) }
    val effectiveUrl = candidates.getOrElse(candidateIndex) { url }
    // Only the original URL (candidateIndex == 0) gets the multi-retry treatment below — a
    // fallback candidate is tried once each, see BLOSSOM_FALLBACK_DELAY_MS's doc comment.
    var retryAttempt by remember(url, candidateIndex) { mutableIntStateOf(0) }
    // hasDispatched is sticky once true (for this candidate/retryAttempt) — it must never flip
    // back to false once the request has actually been handed to Coil, or the model below would
    // revert to null and blank out an already-loading/loaded image.
    var hasDispatched by remember(url, candidateIndex, retryAttempt) { mutableStateOf(false) }

    val imageRequest = remember(effectiveUrl, context, targetWidthPx, targetHeightPx, candidateIndex, retryAttempt) {
        ImageRequest.Builder(context)
            .data(effectiveUrl)
            .crossfade(false)
            .size(targetWidthPx, targetHeightPx)
            .precision(Precision.INEXACT)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .build()
    }
    val painter = rememberAsyncImagePainter(model = if (hasDispatched) imageRequest else null)
    val painterState by painter.state.collectAsState()

    // See runGatedImageLoad's doc comment for why the acquire/await/release lifecycle lives in
    // one coroutine's try/finally rather than three separately-keyed effects.
    LaunchedEffect(url, candidateIndex, retryAttempt) {
        runGatedImageLoad(
            gate = gate,
            onDispatched = { hasDispatched = true },
            awaitTerminal = {
                snapshotFlow { painterState }
                    .first { it is AsyncImagePainter.State.Success || it is AsyncImagePainter.State.Error }
            }
        )
    }

    LaunchedEffect(url, candidateIndex, painterState, retryAttempt) {
        if (painterState !is AsyncImagePainter.State.Error) return@LaunchedEffect
        if (candidateIndex == 0 && retryAttempt < MAX_IMAGE_LOAD_RETRIES) {
            delay(IMAGE_RETRY_DELAYS_MS.getOrElse(retryAttempt) { IMAGE_RETRY_DELAYS_MS.last() })
            retryAttempt += 1
        } else if (candidateIndex < candidates.lastIndex) {
            delay(BLOSSOM_FALLBACK_DELAY_MS)
            candidateIndex += 1
        }
    }

    return GatedImagePainterState(painter = painter, isPending = !hasDispatched)
}
