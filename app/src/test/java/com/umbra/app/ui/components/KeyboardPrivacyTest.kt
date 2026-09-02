package com.umbra.app.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardPrivacyTest {

    @Test
    fun `given_defaultKeyboardOptions_when_settingPrivate_then_noPersonalizedLearning`() {
        val options = privateKeyboardOptions()
        val privateImeOptions = options.platformImeOptions?.privateImeOptions.orEmpty()

        assertTrue(privateImeOptions.contains("noPersonalizedLearning=true"))
        assertTrue(privateImeOptions.contains("com.google.android.inputmethod.latin.noPersonalizedLearning=true"))
        assertFalse(privateImeOptions.contains("hideSuggestionStrip=true"))
        assertFalse(privateImeOptions.contains("noEmojiSuggestions=true"))
    }

    @Test
    fun `given_baseKeyboardOptions_when_settingPrivate_then_preservesConfig`() {
        val base = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Search,
            autoCorrectEnabled = true
        )

        val options = privateKeyboardOptions(base)

        assertEquals(KeyboardType.Uri, options.keyboardType)
        assertEquals(ImeAction.Search, options.imeAction)
        assertEquals(true, options.autoCorrectEnabled)
        assertNotNull(options.platformImeOptions)
    }
}
