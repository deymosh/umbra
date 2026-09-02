package com.umbra.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.umbra.app.R
import com.umbra.app.ui.Screen
import com.umbra.app.ui.common.UiMessage
import com.umbra.app.ui.components.ExternalUrlWarningDialog
import com.umbra.app.ui.components.LoadingSpinner
import com.umbra.app.ui.components.launchExternalUrl
import androidx.compose.runtime.Immutable

private const val AMBER_PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.greenart7c3.nostrsigner"

/**
 * LoginScreen - Nostr authentication with AMBER
 */
@Composable
fun LoginScreen(
    navController: NavController,
    viewModel: LoginViewModel
) {
    val context = LocalContext.current
    val authState by viewModel.authState.collectAsState()
    val amberInstalled = viewModel.isAmberInstalled()
    var pendingExternalUrl by remember { mutableStateOf<String?>(null) }

    pendingExternalUrl?.let { externalUrl ->
        ExternalUrlWarningDialog(
            url = externalUrl,
            onConfirm = {
                launchExternalUrl(context, externalUrl)
                pendingExternalUrl = null
            },
            onDismiss = { pendingExternalUrl = null }
        )
    }

    // Amber login round trip now goes through the single app-wide launcher (AppSessionEffects) —
    // no per-screen launcher needed here.

    // Navigate to TorGate when authenticated (including anonymous)
    LaunchedEffect(authState.isAuthenticated) {
        if (authState.isAuthenticated) {
            navController.navigate(Screen.TorGate.route) {
                popUpTo(Screen.Login.route) { inclusive = true }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically)
    ) {
        // Header
        Image(
            painter = painterResource(R.drawable.ic_umbra_foreground_totality),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.size(148.dp)
        )

        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = stringResource(R.string.nostr_powered_tor),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Login options
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
                    shape = RoundedCornerShape(28.dp)
                )
                .padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (amberInstalled) {
                Button(
                    onClick = {
                        viewModel.requestAmberLogin()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !authState.isLoading
                ) {
                    if (authState.isLoading) {
                        LoadingSpinner(
                            modifier = Modifier.size(20.dp),
                            size = 20.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.login_with_amber), style = MaterialTheme.typography.titleMedium)
                }
            } else {
                OutlinedButton(
                    onClick = { pendingExternalUrl = AMBER_PLAY_STORE_URL },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(stringResource(R.string.install_amber_signer))
                }

                Text(
                    text = stringResource(R.string.amber_recommended),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Continue anonymously option
            OutlinedButton(
                onClick = { viewModel.loginAnonymously() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !authState.isLoading,
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(stringResource(R.string.continue_anonymously), style = MaterialTheme.typography.titleMedium)
            }

            Text(
                text = stringResource(R.string.anonymous_limited_features),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Error display
        if (authState.errorMessage != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp)),
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                val errorText = when (val message = authState.errorMessage) {
                    is UiMessage.Res -> context.getString(message.id, *message.args.toTypedArray())
                    is UiMessage.ResWithArgs -> context.getString(message.id, *message.args)
                    is UiMessage.Literal -> message.text
                    null -> stringResource(R.string.unknown_error)
                }
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                    textAlign = TextAlign.Center
                )
            }
        }

        // Info text
        Text(
            text = stringResource(R.string.privacy_info),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Auth state for login
 */
@Immutable
data class AuthState(
    val isLoading: Boolean = false,
    val errorMessage: UiMessage? = null,
    val isAuthenticated: Boolean = false
)
