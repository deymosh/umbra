package com.umbra.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import com.umbra.app.ui.components.EclipseMark
import com.umbra.app.ui.components.UmbraIcons
import com.umbra.app.ui.theme.UmbraTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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

    LoginContent(
        authState = authState,
        amberInstalled = amberInstalled,
        onAmberLogin = viewModel::requestAmberLogin,
        onInstallAmber = { pendingExternalUrl = AMBER_PLAY_STORE_URL },
        onAnonymous = viewModel::loginAnonymously
    )
}

@Composable
fun LoginContent(
    authState: AuthState,
    amberInstalled: Boolean,
    onAmberLogin: () -> Unit,
    onInstallAmber: () -> Unit,
    onAnonymous: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        EclipseMark(size = 150.dp)
        Text(
            text = stringResource(R.string.app_name).lowercase(),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.nostr_powered_tor),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp)
        )
        Spacer(Modifier.height(36.dp))
        Column(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            LoginPromise(UmbraIcons.Onion, stringResource(R.string.login_promise_tor))
            LoginPromise(Icons.Outlined.Key, stringResource(R.string.login_promise_amber))
            LoginPromise(Icons.Outlined.Tune, stringResource(R.string.login_promise_moderation))
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(32.dp))

        Column(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            authState.errorMessage?.let { message ->
                val errorText = when (message) {
                    is UiMessage.Res -> context.getString(message.id, *message.args.toTypedArray())
                    is UiMessage.ResWithArgs -> context.getString(message.id, *message.args)
                    is UiMessage.Literal -> message.text
                }
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f), MaterialTheme.shapes.medium)
                        .padding(12.dp)
                )
            }
            Button(
                onClick = if (amberInstalled) onAmberLogin else onInstallAmber,
                enabled = !authState.isLoading,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = UmbraTheme.colors.corona)
            ) {
                if (authState.isLoading) {
                    LoadingSpinner(
                        modifier = Modifier.size(20.dp),
                        size = 20.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Text(
                    text = stringResource(if (amberInstalled) R.string.login_with_amber else R.string.install_amber_signer),
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Text(
                text = stringResource(R.string.amber_recommended),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = onAnonymous,
                enabled = !authState.isLoading,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(
                    text = stringResource(R.string.continue_anonymously),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = stringResource(R.string.anonymous_limited_features),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LoginPromise(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
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
