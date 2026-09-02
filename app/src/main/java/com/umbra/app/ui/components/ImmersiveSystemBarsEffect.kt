package com.umbra.app.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Hides both system bars for as long as the caller stays composed, restoring them on dispose, and
 * lets content draw all the way under the display cutout (front camera punch-hole/notch).
 * `decorFitsSystemWindows = false` alone (already set on FullscreenImageDialog/
 * FullscreenVideoDialog) only stops the OS from shrinking the window around the bars — the bars
 * themselves stay drawn on top, so the viewer never actually goes edge-to-edge full screen without
 * hiding them explicitly, which is what this effect does. BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
 * still lets the user peek them with an edge swipe instead of losing access entirely.
 *
 * Separately, the cutout itself is a physical hole the OS clips window content away from unless
 * told otherwise, and the default clipping mode only spares that gap for a cutout on a *short*
 * edge (the top edge in portrait). A front camera punch-hole is physically fixed on the device,
 * so rotating to landscape puts it on what's now a *long* edge — exactly where MainActivity's own
 * enableEdgeToEdge() doesn't need to care (nothing else there goes truly edge-to-edge), but this
 * dialog does. LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS (API 30+ — the mode itself exists since API 28,
 * but this specific constant was only added in R) is the explicit opt-in for that case; this is a
 * fresh Window created per Dialog instance, so there's nothing to restore on dispose the way
 * there is for the system bars above.
 *
 * Must be called from inside a Dialog's content lambda — LocalView.current there resolves to a
 * view whose parent is the dialog's own DialogWindowProvider, not the host Activity's window.
 */
@Composable
fun ImmersiveSystemBarsEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())

        if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // MainActivity now declares android:configChanges for orientation/screenSize specifically so
    // rotating doesn't destroy/recreate the Activity mid-video-playback — but that also means this
    // Dialog's own separate Window never gets torn down and rebuilt at the new screen bounds the
    // way it used to. Left alone, the window keeps its pre-rotation MATCH_PARENT measurement until
    // some unrelated touch event (e.g. a scroll) incidentally forces Android to relayout it —
    // which is exactly the "briefly disappears, reappears once you touch it" symptom this fixes.
    // Re-applying the layout on every configuration change forces that relayout immediately.
    val configuration = LocalConfiguration.current
    LaunchedEffect(view, configuration) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }
}
