package com.umbra.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.umbra.app.R

/**
 * A small copy-icon button meant to sit in the top-right corner of a content card (see
 * NostrTextRenderer's JsonContentBlock, LightningInvoiceCard, LnurlPaymentCard) — [modifier]
 * carries the caller's own `.align(...)` since that's a `BoxScope`-only modifier this composable
 * can't apply itself.
 */
@Composable
fun CopyIconButton(
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.copy)
) {
    IconButton(
        onClick = onCopy,
        modifier = modifier.size(30.dp)
    ) {
        Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(15.dp)
        )
    }
}
