package com.umbra.app.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PlatformImeOptions

private const val PRIVATE_IME_OPTIONS =
    "nm;" +
        "noMicrophoneKey;" +
        "noPersonalizedLearning=true;" +
        "com.google.android.inputmethod.latin.noPersonalizedLearning=true;" +
        "com.samsung.android.keypad.personalized_data=false"

internal fun privateKeyboardOptions(base: KeyboardOptions = KeyboardOptions.Default): KeyboardOptions {
    return base.copy(
        platformImeOptions = PlatformImeOptions(
            privateImeOptions = PRIVATE_IME_OPTIONS
        )
    )
}