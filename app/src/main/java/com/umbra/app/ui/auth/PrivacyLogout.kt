package com.umbra.app.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import com.umbra.app.ui.Screen
import com.umbra.app.ui.components.PrivacyLogoutProgressDialog
import com.umbra.app.util.logging.UmbraLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val privacyLogoutLogger = UmbraLog.tag("PrivacyLogout")

/**
 * The one logout path (drawer, Settings, panic wipe): shows the wipe progress dialog, wipes local
 * data, then lands on sign-in with the back stack cleared. A failed wipe is logged but still
 * leaves the session — there is no in-app state left to retry from. Returns the trigger.
 */
@Composable
fun rememberPrivacyLogout(
    navController: NavController,
    loginViewModel: LoginViewModel,
    onFinished: () -> Unit = {}
): () -> Unit {
    val scope = rememberCoroutineScope()
    var inProgress by remember { mutableStateOf(false) }
    val finished by rememberUpdatedState(onFinished)
    if (inProgress) PrivacyLogoutProgressDialog()
    return remember(navController, loginViewModel) {
        {
            if (!inProgress) {
                inProgress = true
                scope.launch {
                    try {
                        loginViewModel.logout()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        privacyLogoutLogger.e(e) { "Logout failed" }
                    }
                    inProgress = false
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                    finished()
                }
            }
        }
    }
}
