package com.umbra.app.testutil.fakes

import com.umbra.app.domain.logging.UmbraLogger

/**
 * Recording [UmbraLogger] test double — captures every invocation (level, throwable, resolved
 * message) instead of discarding it, so a test can assert what a fixed catch site actually
 * logged rather than only that some logger was present. Follows the same hand-rolled
 * `Fake[InterfaceName]` shape as the other doubles in this package; no mocking framework.
 */
class FakeUmbraLogger : UmbraLogger {
    data class Call(val level: String, val throwable: Throwable?, val message: String)

    private val recordedCalls = mutableListOf<Call>()
    val calls: List<Call> get() = recordedCalls
    val errorCalls: List<Call> get() = recordedCalls.filter { it.level == "e" }

    override fun d(message: () -> String) {
        recordedCalls += Call("d", null, message())
    }

    override fun w(message: () -> String) {
        recordedCalls += Call("w", null, message())
    }

    override fun e(throwable: Throwable, message: () -> String) {
        recordedCalls += Call("e", throwable, message())
    }
}
