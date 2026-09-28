package com.umbra.app.ui.components

import androidx.compose.ui.graphics.Color
import com.umbra.app.ui.theme.UmbraTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.Icons
import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import kotlinx.coroutines.launch

/**
 * An LNURL string detected in note content (see InlineMediaSegment.LnurlReference / LNURL_REGEX).
 * Unlike [LightningInvoiceCard], there's no local decode here — resolving an LNURL into a
 * pay/withdraw request requires an HTTP fetch of its callback URL, out of scope for inline
 * rendering — so this just offers Copy/Open, letting whatever wallet app is registered for the
 * `lightning:` scheme resolve it. The raw bech32 string itself is never shown, same reasoning as
 * LightningInvoiceCard.
 */
@Composable
fun LnurlPaymentCard(lnurl: String, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copiedLabel = stringResource(R.string.copied)

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, UmbraTheme.colors.zap.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.padding(14.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = 30.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Bolt,
                        contentDescription = null,
                        tint = UmbraTheme.colors.zap,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.event_lnurl_reference),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Button(
                    onClick = { onOpen("lightning:$lnurl") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UmbraTheme.colors.zap,
                        contentColor = Color(0xFF231A07)
                    )
                ) {
                    Text(stringResource(R.string.event_lnurl_open))
                }
            }
            CopyIconButton(
                onCopy = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, lnurl)))
                    }
                    Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }
}
