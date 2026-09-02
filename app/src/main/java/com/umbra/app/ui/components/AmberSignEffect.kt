package com.umbra.app.ui.components

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.SharedFlow

/**
 * Reusable side-effect composable that collects Amber requests from a SharedFlow and forwards
 * each Intent to the provided ActivityResultLauncher. Mounted once, at the app root, by
 * [com.umbra.app.ui.AppSessionEffects] — every screen's ViewModel dispatches through
 * AmberRequestCoordinator's single app-wide launcher now rather than each owning its own.
 */
@Composable
fun AmberSignEffect(
    signRequests: SharedFlow<Intent>,
    launcher: ActivityResultLauncher<Intent>
) {
    LaunchedEffect(signRequests) {
        signRequests.collect { intent -> launcher.launch(intent) }
    }
}
