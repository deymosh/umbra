package com.umbra.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.umbra.app.domain.nip05.Nip05VerificationState
import com.umbra.app.domain.profile.UserProfile

@Composable
fun UserIdentityBadge(
    modifier: Modifier = Modifier,
    userProfile: UserProfile?,
    pubkey: String
) {
    Column(modifier = modifier) {
        Text(
            text = userProfile?.getUserDisplayName() ?: pubkey.truncatePublicKey(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val nip05 = userProfile?.nip05?.trim().orEmpty()
        val nip05VerificationState = userProfile?.nip05VerificationState ?: Nip05VerificationState.NotAvailable
        
        if (!nip05.isBlank() && nip05VerificationState != Nip05VerificationState.NotAvailable) {
            val (icon, iconColor) = when (nip05VerificationState) {
                Nip05VerificationState.Verified -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
                Nip05VerificationState.Failed -> Icons.Default.Error to MaterialTheme.colorScheme.error
                Nip05VerificationState.Pending -> Icons.Default.Schedule to Color(0xFFF9A825)
                Nip05VerificationState.NotAvailable -> Icons.Default.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier
                        .size(13.dp)
                        .align(Alignment.CenterVertically)
                )
                Text(
                    text = nip05,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else if (userProfile != null) {
            // Only show the truncated-pubkey subtitle when the name line above is a real
            // display name. When there's no profile at all, the name line already falls back
            // to the truncated pubkey — repeating it here (at a different truncation length)
            // just shows the same key twice, which reads as a rendering bug rather than data.
            Text(
                text = pubkey.truncatePublicKey(4, 4),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

