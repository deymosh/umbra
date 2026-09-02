package com.umbra.app.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.umbra.app.util.MediaLoadPriorityGate

val LocalMediaLoadPriorityGate = staticCompositionLocalOf<MediaLoadPriorityGate> {
    error("MediaLoadPriorityGate is not available")
}