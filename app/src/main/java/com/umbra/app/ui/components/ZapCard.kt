package com.umbra.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.domain.nip57.ZapReceiptDisplay
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.ui.components.media.UserAvatar
import com.umbra.app.ui.theme.UmbraTheme
import java.text.NumberFormat
import java.util.Locale

/**
 * Compact inline embed for a NIP-57 zap receipt (kind 9735) referenced by note content, a `q`
 * tag, or a thread opened on the receipt's own id — same surface treatment as QuotedNoteCard
 * (shape, surface, border) so all referenced events read as one family of quotes.
 *
 * The zap colour and amounts appear as fact only on a verified receipt
 * ([ZapReceiptDisplay.isVerified]); an unverified one renders muted under an explicit
 * "Unverified zap" label with its amount in secondary styling — never presenting a
 * possibly-forged amount as fact.
 */
@Composable
fun ZapCard(
    receipt: ZapReceiptDisplay,
    senderProfile: UserProfile?,
    recipientProfile: UserProfile?,
    createdAt: Long,
    userRepository: UserRepository,
    onSenderClick: (String) -> Unit,
    onRecipientClick: (String) -> Unit,
    onTargetEventClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val zapColor = UmbraTheme.colors.zap
    val accent = if (receipt.isVerified) zapColor else MaterialTheme.colorScheme.onSurfaceVariant
    val nameColor = if (receipt.isVerified) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Bolt,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = receipt.amountSats?.let { stringResource(R.string.event_lightning_amount_sats, formatZapCardSats(it)) }
                        ?: stringResource(R.string.zap_card_unverified),
                    style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                    color = accent
                )
            }

            if (!receipt.isVerified) {
                Text(
                    text = stringResource(R.string.zap_card_unverified),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            ZapPartyLine(
                senderPubkey = receipt.senderPubkey,
                senderProfile = senderProfile,
                recipientPubkey = receipt.recipientPubkey,
                recipientProfile = recipientProfile,
                nameColor = nameColor,
                createdAt = createdAt,
                userRepository = userRepository,
                onSenderClick = onSenderClick,
                onRecipientClick = onRecipientClick
            )

            if (!receipt.comment.isNullOrBlank()) {
                Text(
                    text = receipt.comment,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (receipt.targetEventId != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onTargetEventClick)
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Notes,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.zap_card_on_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** "Alice zapped Bob · 2h" (or "Someone zapped Bob"), with avatars, each name tappable. */
@Composable
private fun ZapPartyLine(
    senderPubkey: String?,
    senderProfile: UserProfile?,
    recipientPubkey: String?,
    recipientProfile: UserProfile?,
    nameColor: androidx.compose.ui.graphics.Color,
    createdAt: Long,
    userRepository: UserRepository,
    onSenderClick: (String) -> Unit,
    onRecipientClick: (String) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (senderPubkey != null) {
            ZappedParty(
                pubkey = senderPubkey,
                profile = senderProfile,
                nameColor = nameColor,
                userRepository = userRepository,
                onClick = { onSenderClick(senderPubkey) }
            )
        } else {
            // Anonymous zap: no payer identity is proven, so no avatar is shown either — only
            // an avatar-less "Someone", keeping the anonymity visible rather than dressing an
            // unknown payer in a generic placeholder face.
            Text(
                text = stringResource(R.string.zap_card_anonymous),
                style = MaterialTheme.typography.bodyMedium,
                color = nameColor,
                maxLines = 1
            )
        }
        Text(
            text = stringResource(R.string.zap_card_zapped),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        if (recipientPubkey != null) {
            ZappedParty(
                pubkey = recipientPubkey,
                profile = recipientProfile,
                nameColor = nameColor,
                userRepository = userRepository,
                onClick = { onRecipientClick(recipientPubkey) }
            )
        }
        Text(
            text = "· ${TimeFormatter.formatCompactRelativeTime(createdAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** One named party in the "X zapped Y" line: avatar + display name (or truncated key). */
@Composable
private fun ZappedParty(
    pubkey: String,
    profile: UserProfile?,
    nameColor: androidx.compose.ui.graphics.Color,
    userRepository: UserRepository,
    onClick: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        UserAvatar(
            userProfile = profile,
            pubkey = pubkey,
            size = 20.dp,
            shape = CircleShape,
            animate = false,
            authorPubkey = pubkey,
            userRepository = userRepository
        )
        Text(
            text = profile?.getUserDisplayName() ?: pubkey.truncatePublicKey(),
            style = MaterialTheme.typography.bodyMedium,
            color = nameColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Thousands separators, per the design checklist's amount formatting. */
private fun formatZapCardSats(sats: Long): String =
    NumberFormat.getIntegerInstance(Locale.getDefault()).format(sats)
