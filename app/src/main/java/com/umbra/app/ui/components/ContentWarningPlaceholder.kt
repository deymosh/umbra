package com.umbra.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.ui.theme.UmbraTheme

/**
 * Inline NIP-36 content-warning gate for a single note's content block — shown in place of the
 * real content until the user taps "Show event". Session-scoped only (no persisted/global
 * bypass): same lifetime as [ShowMoreLessToggle]'s expand state in [com.umbra.app.ui.feed.EventCard].
 */
@Composable
fun ContentWarningPlaceholder(
    reason: String?,
    onShowEvent: () -> Unit,
    modifier: Modifier = Modifier
) {
    // A veiled card in the note's own column: what's hidden and why, one tap to reveal.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.VisibilityOff,
            contentDescription = null,
            tint = UmbraTheme.colors.caution,
            modifier = Modifier.size(20.dp)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (reason.isNullOrBlank()) {
                    stringResource(R.string.content_warning_title)
                } else {
                    stringResource(R.string.content_warning_reason, reason)
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (reason.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.content_warning_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TextButton(onClick = onShowEvent) {
            Text(stringResource(R.string.content_warning_show_button), style = MaterialTheme.typography.labelLarge)
        }
    }
}
