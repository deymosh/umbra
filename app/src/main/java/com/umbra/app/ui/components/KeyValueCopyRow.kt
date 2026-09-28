package com.umbra.app.ui.components

import com.umbra.app.ui.theme.MonoStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A label/value/copy row, used in pairs (hex + npub) where [labelWidth] pins both rows'
 * labels to the same width so the value column starts at the same x regardless of which
 * label ("Hex" vs "Npub") is longer.
 */
@Composable
fun KeyValueCopyRow(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    labelWidth: Dp = 40.dp,
    onCopy: () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.width(labelWidth),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            modifier = Modifier.widthIn(max = 260.dp),
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        CopyIconButton(onCopy = onCopy)
    }
}
