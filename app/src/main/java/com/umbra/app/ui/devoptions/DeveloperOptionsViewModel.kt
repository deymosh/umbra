package com.umbra.app.ui.devoptions

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import com.umbra.app.R
import com.umbra.app.domain.preferences.DeveloperFlag
import com.umbra.app.domain.preferences.DeveloperPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class DeveloperOptionsState(
    val toggles: List<DeveloperToggleItem> = emptyList()
)

@Immutable
data class DeveloperToggleItem(
    val flag: DeveloperFlag,
    val titleRes: Int,
    val subtitleRes: Int,
    val enabled: Boolean
)

@HiltViewModel
class DeveloperOptionsViewModel @Inject constructor(
    private val developerPreferences: DeveloperPreferences
) : ViewModel() {

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<DeveloperOptionsState> = _state.asStateFlow()

    fun setFlag(flag: DeveloperFlag, enabled: Boolean) {
        developerPreferences.setEnabled(flag, enabled)
        _state.update { buildState() }
    }

    private fun buildState() = DeveloperOptionsState(
        toggles = listOf(
            DeveloperToggleItem(
                flag = DeveloperFlag.ENABLE_FEED_ERROR_BANNER,
                titleRes = R.string.dev_options_enable_error_banner_title,
                subtitleRes = R.string.dev_options_enable_error_banner_subtitle,
                enabled = developerPreferences.isEnabled(DeveloperFlag.ENABLE_FEED_ERROR_BANNER)
            ),
            DeveloperToggleItem(
                flag = DeveloperFlag.SHOW_ALL_RELAY_BANNERS,
                titleRes = R.string.dev_options_verbose_relay_banners_title,
                subtitleRes = R.string.dev_options_verbose_relay_banners_subtitle,
                enabled = developerPreferences.isEnabled(DeveloperFlag.SHOW_ALL_RELAY_BANNERS)
            ),
            DeveloperToggleItem(
                flag = DeveloperFlag.SHOW_RELAY_TELEMETRY,
                titleRes = R.string.dev_options_show_relay_telemetry_title,
                subtitleRes = R.string.dev_options_show_relay_telemetry_subtitle,
                enabled = developerPreferences.isEnabled(DeveloperFlag.SHOW_RELAY_TELEMETRY)
            )
        )
    )
}
