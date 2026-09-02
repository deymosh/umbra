package com.umbra.app.util

import com.umbra.app.util.logging.UmbraLog
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/**
 * Bounds how many feed-visible images dispatch their Coil request concurrently. Coil delegates
 * network concurrency entirely to the shared OkHttp Dispatcher (see NetworkModule, sized at
 * maxRequests=1500 for relay WebSockets), which never meaningfully throttles image fetches on its
 * own — a media-heavy feed can otherwise have dozens of images all competing for Tor bandwidth
 * and CPU decode time at once. This also gives rememberRetryingAsyncImagePainter
 * (NostrImageComponents.kt) a real "queued, not yet dispatched" state distinct from Coil's own
 * Loading (which only fires once a request actually starts processing, not before).
 *
 * Two pools back every [acquire]/[release] pair:
 * - [normalSemaphore] ([MAX_CONCURRENT_IMAGE_LOADS] permits) — the feed-visible image path
 *   (ImageAttachment/GalleryImageCell) and any caller not currently flagged interactive.
 * - [reservedSemaphore] ([RESERVED_INTERACTIVE_PERMITS] permits) — additive capacity, not carved
 *   out of [normalSemaphore]'s cap, exclusively for loads whose [acquire] call observes
 *   [MediaLoadPriorityGate.isInteractiveLoadActive]. This is what actually delivers on this
 *   class's original fullscreen-priority guarantee now that the fullscreen viewer participates in
 *   this gate at all: a single, deliberate, user-initiated fullscreen load (or one of its
 *   pre-composed pager neighbors) must not queue behind feed thumbnails. An interactive caller
 *   that finds both reserved permits already held falls through to compete for the normal pool
 *   exactly like a non-interactive caller — it never blocks forever waiting on the reserved pool
 *   alone.
 *
 * Callers MUST call [release] exactly once per successful [acquire] (e.g. once on reaching a
 * terminal Success/Error state, or on early disposal) — kotlinx.coroutines' Semaphore does not
 * guard against an unmatched release silently growing the effective permit count. [release] routes
 * the permit back to whichever pool the matching [acquire] drew from, via the [AcquireLease] it
 * returned — never a heuristic guess at which pool is "probably" the right one.
 */
@Singleton
class ImageLoadGate private constructor(
    private val mediaLoadPriorityGate: MediaLoadPriorityGate,
    startTelemetryLoop: Boolean
) {
    // The actual production entry point Hilt resolves — NOT the primary constructor above, which
    // is private specifically so this one can carry the @Inject annotation while delegating a
    // fixed `startTelemetryLoop = true` rather than exposing it as a default parameter value (a
    // default value directly on the @Inject constructor makes KSP see two constructor signatures
    // and fails Hilt component generation with "may only contain one injected constructor").
    @Inject constructor(mediaLoadPriorityGate: MediaLoadPriorityGate) : this(
        mediaLoadPriorityGate,
        startTelemetryLoop = true
    )

    // Zero-arg convenience constructor for tests/call sites that don't need to construct a real
    // MediaLoadPriorityGate. Passes startTelemetryLoop = false so the 60s telemetry loop below
    // never gets scheduled for these instances — the loop runs on a self-owned CoroutineScope with
    // no close()/cancel() exposed, so a test constructing many of these (ImageLoadGateTest,
    // GatedImagePainterTest both construct a fresh instance per @Test) would otherwise leave one
    // permanently-running coroutine per instance for the rest of the test JVM's life.
    constructor() : this(MediaLoadPriorityGate(), startTelemetryLoop = false)

    private val normalSemaphore = Semaphore(MAX_CONCURRENT_IMAGE_LOADS)
    private val reservedSemaphore = Semaphore(RESERVED_INTERACTIVE_PERMITS)

    private val logger = UmbraLog.tag("ImageLoadGate")

    // Cumulative for the process lifetime — never reset. Counts and timings only, no event/url/
    // pubkey content ever flows through here; see GateStats' own doc comment.
    private val totalAcquires = AtomicLong(0)
    private val totalWaitTimeMs = AtomicLong(0)
    private val maxWaitTimeMs = AtomicLong(0)
    private val currentInFlight = AtomicInteger(0)

    // Self-owned — unlike EventIngestCache's telemetry loop (started by EventRepositoryImpl.init),
    // ImageLoadGate has no external bootstrap call site, so it launches its own loop the same way
    // ImagePrefetcher does.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        if (startTelemetryLoop) {
            scope.launch {
                while (true) {
                    delay(GATE_TELEMETRY_LOG_INTERVAL_MS)
                    val s = stats
                    val avgWaitMs = if (s.totalAcquires > 0) s.totalWaitTimeMs / (s.totalAcquires) else 0
                    logger.d {
                        "Gate telemetry: inFlight=${s.currentInFlight} totalAcquires=${s.totalAcquires} " +
                            "avgWaitMs=$avgWaitMs maxWaitMs=${s.maxWaitTimeMs}"
                    }
                }
            }
        }
    }

    internal suspend fun acquire(): AcquireLease {
        val waitStart = System.currentTimeMillis()
        val lease = if (mediaLoadPriorityGate.isInteractiveLoadActive && reservedSemaphore.tryAcquire()) {
            AcquireLease(fromReservedPool = true)
        } else {
            normalSemaphore.acquire()
            AcquireLease(fromReservedPool = false)
        }
        val waitMs = System.currentTimeMillis() - waitStart
        totalAcquires.incrementAndGet()
        totalWaitTimeMs.addAndGet(waitMs)
        currentInFlight.incrementAndGet()
        maxWaitTimeMs.updateAndGet { existing -> maxOf(existing, waitMs) }
        return lease
    }

    internal fun release(lease: AcquireLease? = null) {
        if (lease?.fromReservedPool == true) reservedSemaphore.release() else normalSemaphore.release()
        currentInFlight.decrementAndGet()
    }

    /**
     * A fresh, immutable snapshot of this gate's cumulative acquire/wait-time counters and current
     * in-flight permit count, taken at the moment of the call — a point-in-time gauge, not a
     * time-windowed average (matching [com.umbra.app.data.repository.cache.EventLruCache]'s
     * `stats`/`CacheStats` shape). Counts and timings only — no event content, pubkey, or relay
     * URL ever flows through here. Read in-process for a local debug log only; never sent
     * off-device.
     */
    val stats: GateStats
        get() = GateStats(
            totalAcquires = totalAcquires.get(),
            totalWaitTimeMs = totalWaitTimeMs.get(),
            maxWaitTimeMs = maxWaitTimeMs.get(),
            currentInFlight = currentInFlight.get()
        )

    companion object {
        private const val MAX_CONCURRENT_IMAGE_LOADS = 6
        private const val RESERVED_INTERACTIVE_PERMITS = 2
        private const val GATE_TELEMETRY_LOG_INTERVAL_MS = 60_000L
    }
}

/**
 * Returned by [ImageLoadGate.acquire] and (optionally) threaded back into [ImageLoadGate.release].
 * [fromReservedPool] records which of the gate's two pools a lease was drawn from, so [release]
 * can route the permit back to the same pool it came from rather than guessing.
 */
@JvmInline
internal value class AcquireLease(val fromReservedPool: Boolean)

/**
 * Immutable snapshot of [ImageLoadGate]'s cumulative acquire/wait-time counters and current
 * in-flight permit count, taken via [ImageLoadGate.stats]. Reports one combined total across both
 * the normal and reserved-interactive pools, not a per-pool breakdown — the reserved slice is
 * small and usually idle, so splitting it out adds complexity with no evidence yet that it needs
 * separate visibility. Counts and timings only — no event content, pubkey, or relay URL. Read
 * in-process for a local debug log; never sent off-device (Umbra ships no analytics or crash
 * reporter, and this type must never become the first).
 */
data class GateStats(
    val totalAcquires: Long,
    val totalWaitTimeMs: Long,
    val maxWaitTimeMs: Long,
    val currentInFlight: Int
)
