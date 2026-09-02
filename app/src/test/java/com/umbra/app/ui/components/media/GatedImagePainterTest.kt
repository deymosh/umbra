package com.umbra.app.ui.components.media

import com.umbra.app.util.ImageLoadGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

// Regression coverage for the LOG-2 permit lifecycle surviving its extraction out of
// NostrImageComponents.kt's LaunchedEffect and into the standalone runGatedImageLoad function,
// including a mid-disposal stress test. These tests drive the real
// production runGatedImageLoad directly rather than reimplementing its acquire/try/finally shape,
// mirroring util/ImageLoadGateTest.kt's own cancellation-during-suspend test.

class GatedImagePainterTest {

    @Test
    fun `given runGatedImageLoad suspended in awaitTerminal when cancelled mid-suspend then the permit is still released exactly once`() = runTest {
        val gate = ImageLoadGate()
        val enteredAwaitTerminal = CompletableDeferred<Unit>()

        val holder = launch {
            runGatedImageLoad(
                gate = gate,
                onDispatched = {},
                awaitTerminal = {
                    enteredAwaitTerminal.complete(Unit)
                    CompletableDeferred<Unit>().await() // suspend forever until cancelled
                }
            )
        }
        enteredAwaitTerminal.await()
        holder.cancel()
        holder.join()

        // The permit acquired above must be back in the pool exactly once — acquiring all 6 must
        // succeed without suspending, and a 7th must still block.
        repeat(6) { gate.acquire() }
        var seventhAcquired = false
        val seventh = launch { gate.acquire(); seventhAcquired = true }
        yield()
        assertFalse(seventhAcquired)
        seventh.cancel()
    }

    @Test
    fun `given runGatedImageLoad when awaitTerminal completes normally then onDispatched fires once and the permit returns to the pool`() = runTest {
        val gate = ImageLoadGate()
        var onDispatchedCallCount = 0

        runGatedImageLoad(
            gate = gate,
            onDispatched = { onDispatchedCallCount += 1 },
            awaitTerminal = { /* completes immediately */ }
        )

        assertEquals(1, onDispatchedCallCount)

        // The single permit acquired above must have been released — all 6 must be acquirable
        // without suspending.
        repeat(6) { gate.acquire() }
        var seventhAcquired = false
        val seventh = launch { gate.acquire(); seventhAcquired = true }
        yield()
        assertFalse(seventhAcquired)
        seventh.cancel()
    }
}
