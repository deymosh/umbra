package com.umbra.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Repeat as RepeatFilled
import androidx.compose.material.icons.outlined.Repeat as RepeatOutlined
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.umbra.app.R

@Composable
fun ReactionBar(
    modifier: Modifier = Modifier,
    replyCount: Int,
    reactionCount: Int,
    repostCount: Int,
    isLiked: Boolean,
    canSign: Boolean,
    onReply: () -> Unit,
    onLike: () -> Unit,
    onRepost: () -> Unit,
    onShare: () -> Unit,
    // Null hides the chip entirely — quoting is currently scoped to kind-1 text notes only, see
    // EventCard's onQuote wiring.
    onQuote: (() -> Unit)? = null,
    isReposted: Boolean = false,
    eventKindLabel: String? = null
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionChip(
            icon = Icons.AutoMirrored.Outlined.Comment,
            contentDescription = stringResource(R.string.event_reply),
            count = replyCount,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onReply
        )
        ActionChip(
            icon = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = stringResource(R.string.event_like_cd),
            count = reactionCount,
            tint = if (isLiked) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (canSign) 1f else 0.72f)
            },
            onClick = onLike
        )
        ActionChip(
            icon = if (isReposted) Icons.Filled.RepeatFilled else Icons.Outlined.RepeatOutlined,
            contentDescription = stringResource(R.string.event_repost_cd),
            count = repostCount,
            tint = if (isReposted) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (canSign) 1f else 0.72f)
            },
            onClick = onRepost
        )
        onQuote?.let { quoteAction ->
            ActionChip(
                icon = Icons.Rounded.FormatQuote,
                contentDescription = stringResource(R.string.event_quote_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (canSign) 1f else 0.72f),
                showCount = false,
                onClick = quoteAction
            )
        }
        ActionChip(
            icon = Icons.Outlined.Share,
            contentDescription = stringResource(R.string.event_share_cd),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            showCount = false,
            onClick = onShare
        )
        eventKindLabel?.let { label ->
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ActionChip(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    count: Int = 0,
    showCount: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        if (showCount) {
            AnimatedContent(
                targetState = count,
                transitionSpec = { ContentTransform(EnterTransition.None, ExitTransition.None) },
                label = contentDescription
            ) { current ->
                Text(
                    text = formatCount(current),
                    style = MaterialTheme.typography.labelSmall,
                    color = tint
                )
            }
        }
    }
}

private fun formatCount(count: Int): String = when {
    count >= 1_000_000 -> "${count / 1_000_000}M"
    count >= 1_000 -> "${count / 1_000}k"
    else -> count.toString()
}