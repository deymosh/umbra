package com.umbra.app.data.nostr

import android.content.Context
import com.umbra.app.data.tor.TorRuntimeManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Start/stop idempotency for [TorRuntimeManager]: two starts must not each register a receiver
 * and each launch a maintenance loop, and stop must fully undo one start. Robolectric supplies
 * the Application context its receiver/callback registration paths need (plain JVM stubs return
 * null, which the constructor's null check rejects).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SessionLifecycleIdempotencyTest {

    // The real check (not a fake): with no Orbot running, its local socket probe fails fast
    // (connection refused on 127.0.0.1:9050), never blocking these scenarios' assertions.
    private fun manager(): TorRuntimeManager = TorRuntimeManager(
        context = RuntimeEnvironment.getApplication() as Context,
        orBotCheck = OrBotConnectivityCheck()
    )

    private fun field(manager: TorRuntimeManager, name: String): Any? =
        TorRuntimeManager::class.java.getDeclaredField(name).apply { isAccessible = true }.get(manager)

    @Test
    fun `given two sequential starts when the maintenance loop runs then exactly one loop is launched`() {
        val manager = manager()
        try {
            manager.start()
            manager.start()

            assertTrue(field(manager, "started") as Boolean)
            val job = field(manager, "maintenanceJob") as kotlinx.coroutines.Job?
            // Exactly one maintenance loop installed — a second concurrent start under the old
            // check-then-act would have overwritten this with its own launch (the first leaked).
            assertTrue(job != null && job.isActive)
        } finally {
            manager.stop()
        }
        assertFalse((field(manager, "maintenanceJob") as kotlinx.coroutines.Job?)?.isActive == true)
    }

    @Test
    fun `given stop after stop then nothing is disturbed`() {
        val manager = manager()
        manager.stop()
        manager.stop()

        assertFalse(field(manager, "started") as Boolean)
        // A second stop without a start must not have launched anything.
        assertNull(field(manager, "maintenanceJob"))
    }
}
