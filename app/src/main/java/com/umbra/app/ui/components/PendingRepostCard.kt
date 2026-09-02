package com.umbra.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.domain.profile.UserProfile

/**
 * Placeholder for a [com.umbra.app.domain.model.PendingRepost] — a repost we know is real (we
 * have the event, know who reposted and when) but whose target hasn't resolved yet, either
 * because it isn't cached/in Room or because a fallback relay lookup
 * (EventRepositoryImpl.scheduleRepostTargetFetch) is still in flight. Same role for reposts that
 * [UnresolvedQuoteReferenceChip] plays for an unresolved inline quote — sized like a resolved
 * note card (not a small chip) so a note's layout doesn't visibly jump once the repost resolves,
 * matching QuotedNoteCard/UnresolvedQuoteReferenceChip's own sizing rationale.
 */
@Composable
fun PendingRepostCard(
    pubkey: String,
    userProfile: UserProfile?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    repostedAt: Long? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RepostBanner(
                pubkey = pubkey,
                userProfile = userProfile,
                onClick = onClick,
                repostedAt = repostedAt
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LoadingSpinner(size = 14.dp, strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.event_repost_pending),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    }
}
