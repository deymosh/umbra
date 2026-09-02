package com.umbra.app.testutil.fakes

import com.umbra.app.domain.nostr.NostrSessionController

class FakeNostrSessionController : NostrSessionController {
    var startCalls = 0
        private set
    var stopCalls = 0
        private set

    override fun start() {
        startCalls++
    }

    override fun stop() {
        stopCalls++
    }
}
