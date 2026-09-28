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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.draw.clip
import com.umbra.app.ui.theme.UmbraTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Repeat as RepeatFilled
import androidx.compose.material.icons.outlined.Repeat as RepeatOutlined
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
    val idle = MaterialTheme.colorScheme.onSurfaceVariant
    // Write actions read slightly quieter when there's no signer (anonymous mode) — they still
    // respond to a tap (which explains why nothing can be published), so they aren't disabled.
    val writeIdle = idle.copy(alpha = if (canSign) 1f else 0.6f)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionChip(
            icon = Icons.Outlined.ChatBubbleOutline,
            contentDescription = stringResource(R.string.event_reply),
            count = replyCount,
            tint = idle,
            onClick = onReply
        )
        ActionChip(
            icon = if (isReposted) Icons.Filled.RepeatFilled else Icons.Outlined.RepeatOutlined,
            contentDescription = stringResource(R.string.event_repost_cd),
            count = repostCount,
            tint = if (isReposted) UmbraTheme.colors.repost else writeIdle,
            onClick = onRepost
        )
        ActionChip(
            icon = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = stringResource(R.string.event_like_cd),
            count = reactionCount,
            tint = if (isLiked) UmbraTheme.colors.like else writeIdle,
            onClick = onLike
        )
        onQuote?.let { quoteAction ->
            ActionChip(
                icon = Icons.Rounded.FormatQuote,
                contentDescription = stringResource(R.string.event_quote_cd),
                tint = writeIdle,
                showCount = false,
                onClick = quoteAction
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        eventKindLabel?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        ActionChip(
            icon = Icons.Outlined.IosShare,
            contentDescription = stringResource(R.string.event_share_cd),
            tint = idle,
            showCount = false,
            onClick = onShare
        )
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
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 36.dp, minWidth = 40.dp)
            .padding(horizontal = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        if (showCount && count > 0) {
            AnimatedContent(
                targetState = count,
                transitionSpec = { ContentTransform(EnterTransition.None, ExitTransition.None) },
                label = contentDescription
            ) { current ->
                Text(
                    text = formatCount(current),
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = tint
                )
            }
        }
    }
}

internal fun formatCount(count: Int): String = when {
    count >= 1_000_000 -> formatScaled(count, 1_000_000, "M")
    count >= 1_000 -> formatScaled(count, 1_000, "k")
    else -> count.toString()
}

private fun formatScaled(count: Int, unit: Int, suffix: String): String {
    val whole = count / unit
    val tenth = (count % unit) / (unit / 10)
    return if (whole < 10 && tenth > 0) "$whole.$tenth$suffix" else "$whole$suffix"
}
