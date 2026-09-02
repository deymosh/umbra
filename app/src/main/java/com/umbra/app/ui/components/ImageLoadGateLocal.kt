package com.umbra.app.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.umbra.app.util.ImageLoadGate

val LocalImageLoadGate = staticCompositionLocalOf<ImageLoadGate> {
    error("ImageLoadGate is not available")
}
