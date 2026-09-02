package com.umbra.app.ui.tor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.umbra.app.R
import com.umbra.app.ui.components.LoadingSpinner
import android.os.SystemClock
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import com.umbra.app.ui.components.ExternalUrlWarningDialog
import com.umbra.app.ui.components.launchExternalUrl

private const val ORBOT_WEB_FALLBACK_URL = "https://guardianproject.info/apps/org.torproject.android"

@Composable
fun TorGateScreen(
    onTorReady: () -> Unit,
    viewModel: TorGateViewModel
) {
    val state by viewModel.state.collectAsState()
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
            val remaining = 1200L - elapsed
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text(stringResource(R.string.app_name).lowercase(), style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
            if (state is TorState.Checking || state is TorState.StartingOrbot) {
                LoadingSpinner()
                Text(
                    if (state is TorState.StartingOrbot) {
                        stringResource(R.string.tor_starting_orbot)
                    } else {
                        stringResource(R.string.tor_checking_orbot)
                    },
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground
                )
            } else if (state is TorState.WaitingForNetwork) {
                Text(
                    stringResource(R.string.tor_waiting_network),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground
                )
                OutlinedButton(onClick = { viewModel.retry() }) { Text(stringResource(R.string.retry)) }
            } else if (state is TorState.WaitingForOrbot) {
                Text(
                    stringResource(R.string.tor_waiting_orbot),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Button(onClick = { viewModel.openOrbot() }) { Text(stringResource(R.string.tor_open_orbot)) }
                OutlinedButton(onClick = { viewModel.retry() }) { Text(stringResource(R.string.retry)) }
            } else if (state is TorState.Connected) {
                Text(stringResource(R.string.tor_connected), color = MaterialTheme.colorScheme.primary)
            } else if (state is TorState.Error) {
                Text(stringResource(R.string.error_label), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                OutlinedButton(onClick = { viewModel.retry() }) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}
