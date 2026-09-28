package com.umbra.app.ui.tor

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.ui.components.EclipseMark
import com.umbra.app.ui.components.ExternalUrlWarningDialog
import com.umbra.app.ui.components.UmbraIcons
import com.umbra.app.ui.components.launchExternalUrl
import com.umbra.app.ui.theme.UmbraTheme
import kotlinx.coroutines.delay

private const val ORBOT_WEB_FALLBACK_URL = "https://guardianproject.info/apps/org.torproject.android"

/** How long the fully-lit eclipse is held once Tor connects, before handing off to the app. */
private const val CONNECTED_HOLD_MS = 1200L

@Composable
fun TorGateScreen(
    onTorReady: () -> Unit,
    viewModel: TorGateViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val enteredAtMs = remember { SystemClock.elapsedRealtime() }
    var pendingExternalUrl by remember { mutableStateOf<String?>(null) }

    pendingExternalUrl?.let { url ->
        ExternalUrlWarningDialog(
            url = url,
            onConfirm = {
                launchExternalUrl(context, url)
                pendingExternalUrl = null
            },
            onDismiss = { pendingExternalUrl = null }
        )
    }

    LaunchedEffect(state) {
        if (state is TorState.Connected) {
            val elapsed = SystemClock.elapsedRealtime() - enteredAtMs
            val remaining = CONNECTED_HOLD_MS - elapsed
            if (remaining > 0L) delay(remaining)
            onTorReady()
        }
    }

    LaunchedEffect(viewModel.sideEffects) {
        viewModel.sideEffects.collect { effect ->
            if (effect is TorSideEffect.OpenOrbot) {
                context.startActivity(effect.intent)
            } else if (effect is TorSideEffect.OpenOrbotStore) {
                pendingExternalUrl = effect.intent.dataString ?: ORBOT_WEB_FALLBACK_URL
            }
        }
    }

    TorGateContent(
        state = state,
        onOpenOrbot = viewModel::openOrbot,
        onRetry = viewModel::retry
    )
}

/**
 * The app's front door. One eclipse carries the whole state: its corona breathes dimly while Tor
 * is being reached and ignites to full when the circuit is up — the single orchestrated motion
 * moment in the app. Everything else on the screen stays still.
 */
@Composable
fun TorGateContent(
    state: TorState,
    onOpenOrbot: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    // Snapshot tests pass false: an infinite animation never lets the test clock go idle.
    animate: Boolean = true
) {
    val working = state is TorState.Checking || state is TorState.StartingOrbot
    val pulse = if (animate && working) {
        val breathing = rememberInfiniteTransition(label = "corona")
        breathing.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
            label = "pulse"
        ).value
    } else {
        0.5f
    }
    val baseIgnition by animateFloatAsState(
        targetValue = when (state) {
            TorState.Connected -> 1f
            TorState.StartingOrbot -> 0.55f
            TorState.Checking -> 0.3f
            else -> 0.08f
        },
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label = "ignition"
    )
    val ignition = if (working) baseIgnition + 0.18f * pulse else baseIgnition

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 420.dp)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            EclipseMark(size = 196.dp, ignition = ignition)
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_name).lowercase(),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(28.dp))
            AnimatedContent(
                targetState = state,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                contentKey = { it::class },
                label = "torState"
            ) { current ->
                TorGateStatus(current, onOpenOrbot = onOpenOrbot, onRetry = onRetry)
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = UmbraIcons.Onion,
                contentDescription = null,
                tint = if (state is TorState.Connected) UmbraTheme.colors.secure else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = stringResource(R.string.tor_gate_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TorGateStatus(
    state: TorState,
    onOpenOrbot: () -> Unit,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        val (message, tone) = when (state) {
            TorState.Checking -> stringResource(R.string.tor_checking_orbot) to MaterialTheme.colorScheme.onSurfaceVariant
            TorState.StartingOrbot -> stringResource(R.string.tor_starting_orbot) to MaterialTheme.colorScheme.onSurfaceVariant
            TorState.WaitingForNetwork -> stringResource(R.string.tor_waiting_network) to MaterialTheme.colorScheme.onSurface
            TorState.WaitingForOrbot -> stringResource(R.string.tor_waiting_orbot) to MaterialTheme.colorScheme.onSurface
            TorState.Connected -> stringResource(R.string.tor_connected) to UmbraTheme.colors.secure
            is TorState.Error -> stringResource(R.string.tor_gate_error_title) to MaterialTheme.colorScheme.error
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = tone,
            textAlign = TextAlign.Center
        )
        if (state is TorState.Error && state.message.isNotBlank()) {
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        when (state) {
            TorState.WaitingForOrbot -> {
                Button(
                    onClick = onOpenOrbot,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = UmbraTheme.colors.corona)
                ) { Text(stringResource(R.string.tor_open_orbot), style = MaterialTheme.typography.titleSmall) }
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                ) { Text(stringResource(R.string.retry), style = MaterialTheme.typography.titleSmall) }
            }
            TorState.WaitingForNetwork, is TorState.Error -> {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                ) { Text(stringResource(R.string.retry), style = MaterialTheme.typography.titleSmall) }
            }
            else -> Unit
        }
    }
}
