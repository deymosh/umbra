package com.umbra.app.ui.snapshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.junit.Test
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SNAPSHOT_SDK], application = Application::class, qualifiers = PHONE)
class ZapCardSnapshotTest {
    private val sender = SnapshotFixtures.ALICE
    private val recipient = SnapshotFixtures.BOB
    private val targetNoteId = "e1".padEnd(64, '0')

    private fun receipt(
        senderPubkey: String?,
        amountSats: Long?,
        comment: String?,
        targetEventId: String?,
        isVerified: Boolean
    ) = com.umbra.app.domain.nip57.ZapReceiptDisplay(
        senderPubkey = senderPubkey,
        recipientPubkey = recipient,
        amountSats = amountSats,
        comment = comment,
        targetEventId = targetEventId,
        isVerified = isVerified
    )

    @Composable
    private fun Card(receipt: com.umbra.app.domain.nip57.ZapReceiptDisplay, senderProfile: com.umbra.app.domain.profile.UserProfile?) {
        com.umbra.app.ui.components.ZapCard(
            receipt = receipt,
            senderProfile = senderProfile,
            recipientProfile = SnapshotFixtures.bob,
            createdAt = SnapshotFixtures.now - 2 * 3600,
            userRepository = SnapshotFixtures.userRepository,
            onSenderClick = {},
            onRecipientClick = {},
            onTargetEventClick = {}
        )
    }

    @Test
    fun zapCards() {
        snapshot("Zap_cards") {
            Column(Modifier.fillMaxWidth()) {
                Card(
                    receipt(
                        senderPubkey = sender,
                        amountSats = 1_000L,
                        comment = "Loved your totality photo — here's for the next one.",
                        targetEventId = targetNoteId,
                        isVerified = true
                    ),
                    senderProfile = SnapshotFixtures.alice
                )
                Card(
                    receipt(
                        senderPubkey = null,
                        amountSats = 21_000L,
                        comment = null,
                        targetEventId = null,
                        isVerified = true
                    ),
                    senderProfile = null
                )
                Card(
                    receipt(
                        senderPubkey = sender,
                        amountSats = 5_500L,
                        comment = null,
                        targetEventId = null,
                        isVerified = false
                    ),
                    senderProfile = SnapshotFixtures.alice
                )
            }
        }
    }
}
