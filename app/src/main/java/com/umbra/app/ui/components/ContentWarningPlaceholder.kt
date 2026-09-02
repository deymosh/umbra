package com.umbra.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.umbra.app.R

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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.VisibilityOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(28.dp)
        )

        Text(
            text = if (reason.isNullOrBlank()) {
                stringResource(R.string.content_warning_title)
            } else {
                stringResource(R.string.content_warning_reason, reason)
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (reason.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.content_warning_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        FilledTonalButton(onClick = onShowEvent) {
            Text(stringResource(R.string.content_warning_show_button))
        }
    }
}
