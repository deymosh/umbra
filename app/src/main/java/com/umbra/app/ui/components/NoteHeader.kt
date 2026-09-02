package com.umbra.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.ui.components.media.UserAvatar

@Composable
fun NoteHeader(
    modifier: Modifier = Modifier,
    userProfile: UserProfile?,
    pubkey: String,
    createdAt: Long,
    onProfileClick: () -> Unit,
    animateAvatar: Boolean = true,
    trailingContent: @Composable (() -> Unit)? = null,
    // Blossom-fallback (BUD-03) candidate retrieval inputs for this note's own avatar, threaded
    // straight into UserAvatar's identically-named params below. Both null (the default) simply
    // disables the fallback for callers with no author/repository in scope.
    // Compose-stability tradeoff: UserRepository is a plain (non-@Stable) interface, so this
    // parameter makes NoteHeader unconditionally non-skippable — see UserAvatar's own
    // userRepository doc comment for the full rationale. Accepted deliberately for BUD-03
    // candidacy rather than introducing a narrower stable wrapper type.
    authorPubkey: String? = null,
    userRepository: UserRepository? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.clickable(onClick = onProfileClick)) {
                UserAvatar(
                    userProfile = userProfile,
                    pubkey = pubkey,
                    size = 42.dp,
                    shape = CircleShape,
                    animate = animateAvatar,
                    authorPubkey = authorPubkey,
                    userRepository = userRepository
                )
            }

            UserIdentityBadge(
                userProfile = userProfile,
                pubkey = pubkey,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Plain text, no background chip: a colored pill on every single note's
                // timestamp — the one piece of chrome guaranteed to repeat on every card in the
                // feed — read as noise rather than signal once there were more than a couple of
                // notes on screen at once.
                Text(
                    text = TimeFormatter.formatRelativeTime(createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = TimeFormatter.formatShortDate(createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.85f
                )
            }

            trailingContent?.invoke()
        }
    }
}
