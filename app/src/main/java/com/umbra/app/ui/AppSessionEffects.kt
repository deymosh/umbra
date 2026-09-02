package com.umbra.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

/**
 * Mounted once directly inside [UmbraNavHost] (same "created once, survives navigation" scoping
 * as that composable's own broadcastViewModel/torGateViewModel) — NOT inside any particular
 * screen/nav-destination's composable tree, so it keeps working regardless of which tab/screen
 * the user is on. Hosts the single app-wide Amber launcher every screen's ViewModel now shares
 * via AmberSignerGateway/Nip44Gateway's high-level suspend methods (signEvent/requestPublicKey/
 * nip44Encrypt/nip44Decrypt) — see [AppSessionViewModel]/AmberRequestCoordinator's doc comments
 * for why one shared, always-registered launcher (rather than one per screen) is what actually
 * matches how Amber itself expects to be driven.
 */
@Composable
fun AppSessionEffects() {
    val viewModel: AppSessionViewModel = hiltViewModel()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result -> viewModel.deliverResult(result.data) }

    DisposableEffect(viewModel, launcher) {
        val callback: (Intent) -> Unit = { intent -> launcher.launch(intent) }
        viewModel.registerLauncher(callback)
        onDispose { viewModel.unregisterLauncher(callback) }
    }
}
