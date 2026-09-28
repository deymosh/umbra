package com.umbra.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.domain.nip05.Nip05VerificationState
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.ui.components.media.UserAvatar
import com.umbra.app.ui.theme.UmbraTheme

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
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.clickable(onClick = onProfileClick)) {
            UserAvatar(
                userProfile = userProfile,
                pubkey = pubkey,
                size = 36.dp,
                shape = CircleShape,
                animate = animateAvatar,
                authorPubkey = authorPubkey,
                userRepository = userRepository
            )
        }
        NoteAuthorLine(
            userProfile = userProfile,
            pubkey = pubkey,
            createdAt = createdAt,
            modifier = Modifier.weight(1f),
            trailingContent = trailingContent
        )
    }
}

/**
 * One line of note attribution: display name, NIP-05 state, handle and relative time. The
 * timestamp sits inline after the handle (rather than stacked in a right-hand column) so the eye
 * reads "who, when" in a single pass and the note's text starts one line higher.
 */
@Composable
fun NoteAuthorLine(
    userProfile: UserProfile?,
    pubkey: String,
    createdAt: Long,
    modifier: Modifier = Modifier,
    kindLabel: String? = null,
    trailingContent: @Composable (() -> Unit)? = null
) {
    val displayName = userProfile?.getUserDisplayName() ?: pubkey.truncatePublicKey()
    val nip05 = userProfile?.nip05?.trim().orEmpty()
    val verification = userProfile?.nip05VerificationState ?: Nip05VerificationState.NotAvailable
    // The handle is the NIP-05 when it's been verified, otherwise a short key — never an
    // unverified NIP-05, which would present an unproven identity claim as fact.
    val handle = when {
        nip05.isNotBlank() && verification == Nip05VerificationState.Verified ->
            nip05.removePrefix("_@")
        userProfile != null -> pubkey.truncatePublicKey(4, 4)
        else -> null
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (verification == Nip05VerificationState.Verified && nip05.isNotBlank()) {
                Icon(
                    imageVector = Icons.Default.Verified,
                    contentDescription = stringResource(R.string.nip05_verified_cd),
                    tint = UmbraTheme.colors.secure,
                    modifier = Modifier.size(14.dp)
                )
            }
            if (handle != null) {
                Text(
                    text = handle,
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            Text(
                text = "· ${TimeFormatter.formatCompactRelativeTime(createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                maxLines = 1
            )
            if (kindLabel != null) {
                Text(
                    text = "· $kindLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
        }
        if (trailingContent != null) {
            Box(modifier = Modifier.padding(start = 2.dp)) { trailingContent() }
        }
    }
}
