package com.umbra.app.ui

import androidx.lifecycle.ViewModel
import com.umbra.app.domain.preferences.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class AppLaunchViewModel @Inject constructor(
    userPreferences: UserPreferences
) : ViewModel() {

    private val _startDestination = MutableStateFlow(
        if (userPreferences.isLoggedIn()) Screen.TorGate.route else Screen.Login.route
    )
    val startDestination: StateFlow<String> = _startDestination.asStateFlow()
}
