package com.umbra.app.ui.components

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.domain.lightning.isExpired
import com.umbra.app.domain.lightning.parseBolt11
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/**
 * A BOLT11 Lightning invoice detected in note content (see InlineMediaSegment.LightningInvoice /
 * LIGHTNING_INVOICE_REGEX). Decoding happens here rather than at detection time — a malformed or
 * partially-decodable invoice still renders with just the copy/pay actions (see Bolt11Invoice's
 * doc comment: detection never depends on decode success). The raw bech32 string itself is never
 * shown — it adds nothing readable to the card; copy/pay already carry it directly.
 *
 * An invoice past its decoded expiry shows an "Expired" notice and disables Pay — matches
 * Amethyst's handling of the same case.
 */
@Composable
fun LightningInvoiceCard(invoice: String, onPay: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val decoded = remember(invoice) { parseBolt11(invoice) }
    val copiedLabel = stringResource(R.string.copied)
    val now = remember(invoice) { System.currentTimeMillis() / 1000 }
    val expired = decoded?.isExpired(now) ?: false

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.padding(10.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = 30.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = "⚡", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(R.string.event_lightning_invoice),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = decoded?.amountMsat?.let {
                        stringResource(R.string.event_lightning_amount_sats, formatSats(it))
                    } ?: stringResource(R.string.event_lightning_any_amount),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                val description = decoded?.description
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (expired) {
                    Text(
                        text = stringResource(R.string.event_lightning_expired),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Button(
                    onClick = { onPay("lightning:$invoice") },
                    enabled = !expired,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.event_lightning_pay))
                }
            }
            CopyIconButton(
                onCopy = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, invoice)))
                    }
                    Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }
}

private fun formatSats(amountMsat: Long): String {
    val sats = amountMsat / 1000
    return NumberFormat.getIntegerInstance(Locale.getDefault()).format(sats)
}
