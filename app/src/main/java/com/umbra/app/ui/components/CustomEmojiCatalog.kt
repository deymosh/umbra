package com.umbra.app.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.domain.nip30.EmojiGroup
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.usecase.ObserveOwnCustomEmojisUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * App-wide access to the user's grouped NIP-30 emoji catalog. [ObserveOwnCustomEmojisUseCase]
 * owns relay channels that get cleared by whichever collector closes first, so it must be
 * collected in exactly one place — everything below this provider reads the local instead.
 */
val LocalCustomEmojiGroups = staticCompositionLocalOf<List<EmojiGroup>> { emptyList() }

/**
 * The one collector of [ObserveOwnCustomEmojisUseCase]: exposes the signed-in user's grouped
 * emoji catalog for the whole tree. Empty while anonymous.
 */
@HiltViewModel
class CustomEmojiCatalogViewModel @Inject constructor(
    userPreferences: UserPreferences,
    observeOwnCustomEmojis: ObserveOwnCustomEmojisUseCase
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val groups: StateFlow<List<EmojiGroup>> = userPreferences.getPublicKeyFlow()
        .flatMapLatest { pubkey ->
            if (pubkey == null) emptyFlow() else observeOwnCustomEmojis(pubkey)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
