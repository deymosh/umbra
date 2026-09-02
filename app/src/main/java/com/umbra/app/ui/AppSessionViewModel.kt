package com.umbra.app.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import com.umbra.app.data.amber.AmberRequestCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Root-scoped ViewModel (created once at the app's top level — see [AppSessionEffects] — and
 * alive for as long as the Activity is, regardless of which screen/tab is currently showing).
 *
 * Bridges [AmberRequestCoordinator] — a plain `@Singleton`, with no Activity/Compose context of
 * its own — to the single Amber launcher [AppSessionEffects] hosts, so ANY interactive Amber
 * request (sign_event, nip44_encrypt/decrypt, login) from anywhere in the app can be dispatched
 * and resolved no matter which screen the user happens to be on. Most requests never actually
 * need this launcher: each gateway method tries Amber's ContentProvider fast path first, which
 * resolves silently once Amber has approved the permission once.
 */
@HiltViewModel
class AppSessionViewModel @Inject constructor(
    private val requestCoordinator: AmberRequestCoordinator
) : ViewModel() {
    fun registerLauncher(launcher: (Intent) -> Unit) = requestCoordinator.registerLauncher(launcher)

    fun unregisterLauncher(launcher: (Intent) -> Unit) = requestCoordinator.unregisterLauncher(launcher)

    fun deliverResult(data: Intent?) = requestCoordinator.deliverResult(data)
}
