package com.umbra.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.umbra.app.domain.nip19.Bech32Encoder
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.ui.components.media.UserAvatar
import com.umbra.app.ui.theme.MonoStyle

/**
 * One person in a list (a follow list, a follow set): avatar, name and the first line of their
 * bio, or their npub while the profile is unknown. [trailing] holds a per-row action.
 */
@Composable
fun PersonRow(
    pubkey: String,
    profile: UserProfile?,
    onClick: () -> Unit,
    userRepository: UserRepository? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = if (trailing == null) 16.dp else 4.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(
            userProfile = profile,
            pubkey = pubkey,
            size = 44.dp,
            shape = CircleShape,
            authorPubkey = pubkey,
            userRepository = userRepository
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            CustomEmojiText(
                text = profile?.getUserDisplayName() ?: pubkey.truncatePublicKey(8, 8),
                customEmojis = profile?.customEmojis.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val about = profile?.about?.trim()?.lineSequence()?.firstOrNull { it.isNotBlank() }
            Text(
                text = about ?: Bech32Encoder.encodeNpub(pubkey).truncatePublicKey(10, 8),
                style = if (about != null) MaterialTheme.typography.bodySmall else MonoStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        trailing?.invoke(this)
    }
}
