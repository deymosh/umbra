package com.umbra.app.ui.zap

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.umbra.app.R
import com.umbra.app.domain.nip57.DEFAULT_ZAP_AMOUNTS_SATS
import com.umbra.app.domain.nipa3.PaymentTarget
import com.umbra.app.domain.usecase.ZapFailure
import com.umbra.app.ui.components.ExternalUrlWarningDialog
import com.umbra.app.ui.components.GroupTextField
import com.umbra.app.ui.components.LoadingSpinner
import com.umbra.app.ui.components.launchPaymentUri
import com.umbra.app.ui.components.media.UserAvatar
import com.umbra.app.ui.components.truncatePublicKey
import com.umbra.app.ui.theme.MonoStyle
import com.umbra.app.ui.theme.UmbraTheme
import kotlinx.coroutines.launch

/** Opens the zap sheet for a recipient (and optionally a note). Null outside a [ZapHost]. */
val LocalZapLauncher = androidx.compose.runtime.staticCompositionLocalOf<((ZapTarget) -> Unit)?> { null }

/**
 * Owns the one zap sheet for everything below it: any note card or profile calls
 * [LocalZapLauncher] instead of each screen's ViewModel wiring its own zap flow.
 */
@Composable
fun ZapHost(content: @Composable () -> Unit) {
    var target by remember { mutableStateOf<ZapTarget?>(null) }
    androidx.compose.runtime.CompositionLocalProvider(LocalZapLauncher provides { target = it }) {
        content()
    }
    target?.let { ZapSheet(target = it, onDismiss = { target = null }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZapSheet(target: ZapTarget, onDismiss: () -> Unit) {
    val viewModel: ZapViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingUri by remember { mutableStateOf<String?>(null) }
    var openFailed by remember { mutableStateOf(false) }

    LaunchedEffect(target) { viewModel.open(target) }

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.close()
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        ZapSheetContent(
            state = state,
            onAmount = viewModel::setAmount,
            onComment = viewModel::setComment,
            onZap = viewModel::zap,
            onRetry = viewModel::retry,
            onReload = viewModel::reload,
            noAppFound = openFailed,
            onOpenUri = { pendingUri = it }
        )
    }

    pendingUri?.let { uri ->
        ExternalUrlWarningDialog(
            url = uri,
            onConfirm = {
                // No wallet claiming the scheme: the user still gets the URI as text rather
                // than the tap going nowhere.
                if (!launchPaymentUri(context, uri)) openFailed = true
                pendingUri = null
            },
            onDismiss = { pendingUri = null },
            message = stringResource(R.string.event_lightning_pay_warning)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ZapSheetContent(
    state: ZapUiState,
    onAmount: (Long) -> Unit,
    onComment: (String) -> Unit,
    onZap: () -> Unit,
    onRetry: () -> Unit,
    onReload: () -> Unit,
    noAppFound: Boolean = false,
    onOpenUri: (String) -> Unit
) {
    val target = state.target ?: return
    val zapColor = UmbraTheme.colors.zap
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
    ) {
        // Recipient
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            UserAvatar(userProfile = target.profile, pubkey = target.recipientPubkey, size = 44.dp, animate = false)
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.zap_title,
                        target.profile?.getUserDisplayName() ?: target.recipientPubkey.truncatePublicKey()
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val address = target.profile?.lud16?.takeIf { it.isNotBlank() }
                if (address != null) {
                    Text(address, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(Icons.Outlined.Bolt, contentDescription = null, tint = zapColor, modifier = Modifier.size(28.dp))
        }

        Spacer(Modifier.height(16.dp))

        when (val phase = state.phase) {
            ZapPhase.Resolving -> StatusLine(stringResource(R.string.zap_resolving))
            is ZapPhase.Failed -> FailureBlock(
                reason = phase.reason,
                onRetry = onRetry.takeIf { phase.reason == ZapFailure.INVOICE_FAILED || phase.reason == ZapFailure.AMOUNT_OUT_OF_RANGE },
                onReload = onReload.takeIf { phase.reason == ZapFailure.ENDPOINT_UNREACHABLE }
            )
            is ZapPhase.InvoiceReady -> InvoiceBlock(phase.bolt11, phase.isZap, onOpenUri, onRetry)
            ZapPhase.Ready, ZapPhase.Working -> AmountPicker(state, onAmount, onComment, onZap)
            ZapPhase.NoLightning -> if (state.paymentTargets.isEmpty()) {
                // The payto subscription may still be working; the spinner gives way to
                // either a targets list or the empty state once it has reported back.
                if (state.targetsLoaded) {
                    NoPaymentOptions(
                        name = target.profile?.getUserDisplayName() ?: target.recipientPubkey.truncatePublicKey()
                    )
                } else {
                    StatusLine(stringResource(R.string.zap_resolving))
                }
            }
        }

        // Payment targets get their own treatment whenever they exist: below the amount picker
        // when Lightning is also available, alone (with a "Pay with" header) when it isn't.
        val showTargets = state.paymentTargets.isNotEmpty() && state.phase !is ZapPhase.Ready &&
            state.phase !is ZapPhase.Working && state.phase !is ZapPhase.InvoiceReady
        if (showTargets) {
            Text(
                text = stringResource(R.string.zap_pay_with_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp)
            )
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            ) {
                state.paymentTargets.forEachIndexed { index, paymentTarget ->
                    PaymentTargetRow(paymentTarget, onOpen = { onOpenUri(paymentTarget.toUri()) })
                    if (index < state.paymentTargets.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }

        if (noAppFound) {
            Text(
                text = stringResource(R.string.zap_no_wallet_app),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AmountPicker(
    state: ZapUiState,
    onAmount: (Long) -> Unit,
    onComment: (String) -> Unit,
    onZap: () -> Unit
) {
    val zapColor = UmbraTheme.colors.zap
    val working = state.phase == ZapPhase.Working
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = "%,d".format(state.amountSats),
            style = MonoStyle.copy(fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Text(
            text = stringResource(R.string.zap_sats_unit),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(16.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 3
        ) {
            DEFAULT_ZAP_AMOUNTS_SATS.forEach { amount ->
                val selected = amount == state.amountSats
                val allowed = state.maxSats <= 0 || amount in state.minSats..state.maxSats
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(if (selected) zapColor.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(1.dp, if (selected) zapColor else MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                        .clickable(enabled = allowed && !working) { onAmount(amount) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = formatSats(amount),
                        style = MonoStyle.copy(fontSize = 15.sp),
                        color = when {
                            !allowed -> MaterialTheme.colorScheme.outline
                            selected -> zapColor
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            GroupTextField(
                label = stringResource(R.string.zap_custom_amount),
                value = state.amountSats.takeIf { it > 0 }?.toString().orEmpty(),
                onValueChange = { raw -> onAmount(raw.filter(Char::isDigit).take(9).toLongOrNull() ?: 0) },
                mono = true,
                enabled = !working,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            GroupTextField(
                label = stringResource(R.string.zap_comment),
                value = state.comment,
                onValueChange = onComment,
                placeholder = stringResource(R.string.zap_comment_placeholder),
                enabled = !working,
                showDivider = false
            )
        }
        Text(
            text = stringResource(if (state.supportsReceipts) R.string.zap_receipt_note else R.string.zap_no_receipt_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp)
        )
        Button(
            onClick = onZap,
            enabled = state.canZap,
            colors = ButtonDefaults.buttonColors(containerColor = zapColor, contentColor = MaterialTheme.colorScheme.background),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (working) {
                LoadingSpinner(size = 18.dp, strokeWidth = 2.dp, color = MaterialTheme.colorScheme.background)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.zap_working))
            } else {
                Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.zap_action, formatSats(state.amountSats)))
            }
        }
    }
}

@Composable
private fun InvoiceBlock(bolt11: String, isZap: Boolean, onOpenUri: (String) -> Unit, onBack: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(if (isZap) R.string.zap_invoice_ready else R.string.zap_invoice_ready_plain),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = bolt11,
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(12.dp)
        )
        Button(
            onClick = { onOpenUri("lightning:$bolt11") },
            colors = ButtonDefaults.buttonColors(containerColor = UmbraTheme.colors.zap, contentColor = MaterialTheme.colorScheme.background),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.zap_open_wallet))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, bolt11))) } },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.zap_copy_invoice))
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.zap_change_amount))
            }
        }
    }
}

@Composable
private fun NoPaymentOptions(name: String) {
    // Reuses the shared empty-state component inside the sheet's own padding.
    com.umbra.app.ui.components.EmptyState(
        modifier = Modifier.padding(vertical = 24.dp),
        title = stringResource(R.string.zap_no_options_title),
        message = stringResource(R.string.zap_no_options_body, name)
    )
}

@Composable
private fun StatusLine(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LoadingSpinner(size = 18.dp, strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FailureBlock(reason: ZapFailure, onRetry: (() -> Unit)?, onReload: (() -> Unit)?) {
    val message = when (reason) {
        ZapFailure.NO_LIGHTNING_ADDRESS -> R.string.zap_error_no_address
        ZapFailure.ENDPOINT_UNREACHABLE -> R.string.zap_error_unreachable
        ZapFailure.AMOUNT_OUT_OF_RANGE -> R.string.zap_error_amount
        ZapFailure.INVOICE_FAILED -> R.string.zap_error_invoice
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = UmbraTheme.colors.caution, modifier = Modifier.size(20.dp))
            Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        if (onRetry != null) {
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.zap_change_amount)) }
        }
        if (onReload != null) {
            OutlinedButton(onClick = onReload) { Text(stringResource(R.string.zap_try_again)) }
        }
    }
}

@Composable
private fun PaymentTargetRow(target: PaymentTarget, onOpen: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(target.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                target.address,
                style = MonoStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis
            )
        }
        IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, target.address))) } }) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.copy), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

internal fun formatSats(sats: Long): String = when {
    sats >= 1_000_000 && sats % 1_000_000 == 0L -> "${sats / 1_000_000}M"
    sats >= 1_000 && sats % 1_000 == 0L -> "${sats / 1_000}k"
    else -> "%,d".format(sats)
}
