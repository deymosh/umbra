package com.umbra.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.ui.components.media.UserAvatar

/**
 * "X reposted" banner shown above [NoteHeader] for a note that arrived via a NIP-18 kind-6/16
 * repost (see EventCard's repostedByPubkey/repostedByProfile params) — small reposter avatar +
 * icon + name, Twitter/Amethyst-style, positioned above the original author's own avatar/header
 * rather than composited into a single combined avatar image (NoteHeader's own avatar stays
 * exactly the original author's, unmodified).
 */
@Composable
fun RepostBanner(
    pubkey: String,
    userProfile: UserProfile?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    repostedAt: Long? = null,
    // null hides the trailing menu button entirely (e.g. a caller with no repost-specific actions
    // to offer) — matches NoteHeader's own kebab in size/icon/tint for visual consistency.
    onMenuClick: (() -> Unit)? = null,
    // Blossom-fallback (BUD-03) candidate retrieval inputs for the reposter's own avatar, threaded
    // straight into UserAvatar's identically-named params below. Both null (the default) simply
    // disables the fallback for callers with no author/repository in scope.
    // Compose-stability tradeoff: UserRepository is a plain (non-@Stable) interface, so this
    // parameter makes RepostBanner unconditionally non-skippable — see UserAvatar's own
    // userRepository doc comment for the full rationale. Accepted deliberately for BUD-03
    // candidacy rather than introducing a narrower stable wrapper type.
    authorPubkey: String? = null,
    userRepository: UserRepository? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Repeat,
            contentDescription = stringResource(R.string.event_repost_icon_cd),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp)
        )
        UserAvatar(
            userProfile = userProfile,
            pubkey = pubkey,
            size = 16.dp,
            shape = CircleShape,
            animate = false,
            authorPubkey = authorPubkey,
            userRepository = userRepository
        )
        val reposterLabel = stringResource(R.string.event_reposted_by, userProfile?.getUserDisplayName() ?: pubkey.truncatePublicKey())
        Text(
            text = if (repostedAt != null) {
                "$reposterLabel · ${TimeFormatter.formatRelativeTime(repostedAt)}"
            } else {
                reposterLabel
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (onMenuClick != null) {
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                // Matches NoteHeader's own kebab (EventCard.kt) in size/icon/tint for touch-target
                // and visual consistency between a repost banner's menu and a normal note's menu.
                IconButton(onClick = onMenuClick, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.repost_banner_more_actions),
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
