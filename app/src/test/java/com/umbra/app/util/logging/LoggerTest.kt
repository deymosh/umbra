package com.umbra.app.util.logging

import com.umbra.app.domain.logging.NoOpUmbraLogger
import com.umbra.app.domain.logging.UmbraLogger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * `app/build.gradle.kts` sets `testOptions.unitTests.isReturnDefaultValues = true`,
 * so `android.util.Log.isLoggable` returns `false` in a plain JVM test — that is
 * exactly the "not loggable" branch these tests exercise, no Robolectric needed.
 */
class LoggerTest {

    @Test
    fun `tag returns a Logger assignable to UmbraLogger`() {
        val logger: UmbraLogger = UmbraLog.tag("UmbraTest")
        assertNotNull(logger)
    }

    @Test
    fun `d does not invoke the message lambda when tag is not loggable`() {
        val logger = UmbraLog.tag("UmbraTest")
        var invoked = false
        logger.d { invoked = true; "message" }
        assertFalse(invoked)
    }

    @Test
    fun `w does not invoke the message lambda when tag is not loggable`() {
        val logger = UmbraLog.tag("UmbraTest")
        var invoked = false
        logger.w { invoked = true; "message" }
        assertFalse(invoked)
    }

    @Test
    fun `e does not invoke the message lambda when tag is not loggable`() {
        val logger = UmbraLog.tag("UmbraTest")
        var invoked = false
        logger.e(RuntimeException("boom")) { invoked = true; "message" }
        assertFalse(invoked)
    }

    @Test
    fun `NoOpUmbraLogger never invokes the message lambda and never throws`() {
        var invoked = false
        NoOpUmbraLogger.d { invoked = true; "message" }
        NoOpUmbraLogger.w { invoked = true; "message" }
        NoOpUmbraLogger.e(RuntimeException("boom")) { invoked = true; "message" }
        assertFalse(invoked)
    }

    @Test
    fun `two tag calls each return a usable Logger`() {
        // Declared type is UmbraLogger (not the concrete Logger) so this doubles as a
        // compile-time proof that the factory's return type satisfies the domain port.
        val first: UmbraLogger = UmbraLog.tag("X")
        val second: UmbraLogger = UmbraLog.tag("X")
        assertNotNull(first)
        assertNotNull(second)
    }
}
