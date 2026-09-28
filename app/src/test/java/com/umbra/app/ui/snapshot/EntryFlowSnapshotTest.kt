package com.umbra.app.ui.snapshot

import android.app.Application
import com.umbra.app.ui.auth.AuthState
import com.umbra.app.ui.auth.LoginContent
import com.umbra.app.ui.common.UiMessage
import com.umbra.app.ui.tor.TorGateContent
import com.umbra.app.ui.tor.TorState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
class EntryFlowSnapshotTest {
    @Test
    fun login() = snapshot("EntryFlow_login") {
        LoginContent(AuthState(), amberInstalled = true, onAmberLogin = {}, onInstallAmber = {}, onAnonymous = {})
    }

    @Test
    fun loginNoAmberWithError() = snapshot("EntryFlow_loginNoAmber") {
        LoginContent(
            AuthState(errorMessage = UiMessage.Literal("The signer didn't return a public key.")),
            amberInstalled = false, onAmberLogin = {}, onInstallAmber = {}, onAnonymous = {}
        )
    }

    @Test
    fun torStarting() = snapshot("EntryFlow_torStarting") {
        TorGateContent(TorState.StartingOrbot, onOpenOrbot = {}, onRetry = {}, animate = false)
    }

    @Test
    fun torWaitingForOrbot() = snapshot("EntryFlow_torWaitingForOrbot") {
        TorGateContent(TorState.WaitingForOrbot, onOpenOrbot = {}, onRetry = {}, animate = false)
    }

    @Test
    fun torConnected() = snapshot("EntryFlow_torConnected") {
        TorGateContent(TorState.Connected, onOpenOrbot = {}, onRetry = {}, animate = false)
    }
}
