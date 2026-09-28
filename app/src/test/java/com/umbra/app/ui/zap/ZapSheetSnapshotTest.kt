package com.umbra.app.ui.zap

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.umbra.app.domain.nip57.LnurlPayInfo
import com.umbra.app.domain.nipa3.PaymentTarget
import com.umbra.app.domain.usecase.ZapFailure
import com.umbra.app.ui.snapshot.PHONE
import com.umbra.app.ui.snapshot.SNAPSHOT_SDK
import com.umbra.app.ui.snapshot.SnapshotFixtures
import com.umbra.app.ui.snapshot.snapshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
class ZapSheetSnapshotTest {

    private val target = ZapTarget(SnapshotFixtures.ALICE, SnapshotFixtures.alice, SnapshotFixtures.textNote)
    private val payInfo = LnurlPayInfo("https://getalby.com/cb", 1_000, 100_000_000, true, SnapshotFixtures.ALICE, 140, "LNURL1X")

    private fun render(name: String, state: ZapUiState) = snapshot(name) {
        Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).padding(top = 16.dp)) {
            ZapSheetContent(state = state, onAmount = {}, onComment = {}, onZap = {}, onRetry = {}, onOpenUri = {})
        }
    }

    @Test
    fun ready() = render(
        "ZapSheet_ready",
        ZapUiState(
            target = target,
            phase = ZapPhase.Ready,
            payInfo = payInfo,
            amountSats = 1_000,
            comment = "Incredible shot",
            paymentTargets = listOf(PaymentTarget("bitcoin", "bc1qxq66e0t8d7ugdecwnmv58e90tpry23nc84pg9k"), PaymentTarget("paypal", "alicemoreau"))
        )
    )

    @Test
    fun invoice() = render(
        "ZapSheet_invoice",
        ZapUiState(target = target, phase = ZapPhase.InvoiceReady("lnbc10u1p3xnhl2pp5jptserfk3zk4qy42tlucycrfwxhydvlemu9pqr93tuzlv9cc7g3sdqsvfhkcap3xyhx7un8cqzpgxqzjcsp5f8c52y2stc300gl6s4xswtjpc37hrnnr3c9wvtgjfuvqmpm35evq9qyyssqy4lgd8tj637qcjp05rdpxxykjenthxftej7a2zzmwrmrl70fyj9hvj0rewhzj7jfyuwkwcg9g2jpwtk3mkx5hc3p9sne3a5gppv6r7cqxl8pn3", true), payInfo = payInfo)
    )

    @Test
    fun noAddress() = render(
        "ZapSheet_noAddress",
        ZapUiState(target = target.copy(profile = SnapshotFixtures.bob), phase = ZapPhase.Failed(ZapFailure.NO_LIGHTNING_ADDRESS))
    )
}
