package com.umbra.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material3.Switch
import androidx.compose.runtime.collectAsState
import com.umbra.app.ui.auth.rememberPrivacyLogout
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.unit.sp
import com.umbra.app.ui.components.EclipseMark
import com.umbra.app.ui.components.SettingsGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.navigation.NavController
import com.umbra.app.BuildConfig
import com.umbra.app.R
import com.umbra.app.ui.Screen
import com.umbra.app.ui.auth.LoginViewModel
import com.umbra.app.ui.components.MenuItemRow
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import kotlin.OptIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue


/**
 * Settings screen main menu (NIP-01 compliant client configuration)
 * Provides navigation to relay configuration and feed settings
 */
@Composable
fun SettingsScreen(navController: NavController, loginViewModel: LoginViewModel) {
    val logout = rememberPrivacyLogout(navController, loginViewModel)
    val panicWipeEnabled by loginViewModel.panicWipeEnabled.collectAsState()

    SettingsContent(
        onBack = {
            val popped = navController.popBackStack()
            if (!popped) {
                navController.navigate(Screen.Feed.route) {
                    launchSingleTop = true
                }
            }
        },
        onOpen = { route -> navController.navigate(route) },
        onLogout = logout,
        panicWipeEnabled = panicWipeEnabled,
        onPanicWipeChange = loginViewModel::setPanicWipeEnabled
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    onBack: () -> Unit,
    onOpen: (route: String) -> Unit,
    onLogout: () -> Unit,
    versionName: String = BuildConfig.VERSION_NAME,
    panicWipeEnabled: Boolean = false,
    onPanicWipeChange: (Boolean) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        UmbraTopAppBar(
            title = { Text(stringResource(R.string.settings_title)) },
            navigationIcon = {
                UmbraTopAppBarDefaults.BackNavigationIcon(onClick = onBack)
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                SettingsGroup(title = stringResource(R.string.settings_network_configuration)) {
                    MenuItemRow(
                        icon = Icons.Outlined.Hub,
                        title = stringResource(R.string.settings_configure_relays_title),
                        subtitle = stringResource(R.string.settings_configure_relays_subtitle),
                        onClick = { onOpen(Screen.RelayConfig.route) }
                    )
                    MenuItemRow(
                        icon = Icons.Outlined.CloudUpload,
                        title = stringResource(R.string.settings_configure_blossom_servers_title),
                        subtitle = stringResource(R.string.settings_configure_blossom_servers_subtitle),
                        onClick = { onOpen(Screen.BlossomServers.route) }
                    )
                    MenuItemRow(
                        icon = Icons.Outlined.DataUsage,
                        title = stringResource(R.string.network_usage_title),
                        subtitle = stringResource(R.string.settings_network_usage_subtitle),
                        onClick = { onOpen(Screen.NetworkUsage.route) },
                        showDivider = false
                    )
                }
            }

            item {
                SettingsGroup(title = stringResource(R.string.settings_group_feed)) {
                    MenuItemRow(
                        icon = Icons.Outlined.Tune,
                        title = stringResource(R.string.settings_feed_preferences),
                        subtitle = stringResource(R.string.settings_feed_preferences_subtitle),
                        onClick = { onOpen(Screen.FeedConfig.route) }
                    )
                    MenuItemRow(
                        icon = Icons.Outlined.Palette,
                        title = stringResource(R.string.settings_appearance_title),
                        subtitle = stringResource(R.string.settings_appearance_subtitle),
                        onClick = { onOpen(Screen.Appearance.route) },
                        showDivider = false
                    )
                }
            }

            item {
                SettingsGroup(title = stringResource(R.string.settings_developer)) {
                    MenuItemRow(
                        icon = Icons.Outlined.Code,
                        title = stringResource(R.string.settings_developer_options_title),
                        subtitle = stringResource(R.string.settings_developer_options_subtitle),
                        onClick = { onOpen(Screen.DeveloperOptions.route) }
                    )
                    MenuItemRow(
                        icon = Icons.Outlined.Memory,
                        title = stringResource(R.string.settings_app_resource_usage_title),
                        subtitle = stringResource(R.string.settings_app_resource_usage_subtitle),
                        onClick = { onOpen(Screen.AppResourceUsage.route) }
                    )
                    MenuItemRow(
                        icon = Icons.Outlined.Storage,
                        title = stringResource(R.string.settings_db_inspector_title),
                        subtitle = stringResource(R.string.settings_db_inspector_subtitle),
                        onClick = { onOpen(Screen.DbInspector.route) },
                        showDivider = false
                    )
                }
            }

            item {
                SettingsGroup(title = stringResource(R.string.settings_about_umbra)) {
                    SettingInfoItem(
                        title = stringResource(R.string.settings_version),
                        value = versionName
                    )
                    SettingInfoItem(
                        title = stringResource(R.string.settings_privacy),
                        value = stringResource(R.string.settings_privacy_value)
                    )
                    SettingInfoItem(
                        title = stringResource(R.string.settings_architecture),
                        value = stringResource(R.string.settings_architecture_value),
                        showDivider = false
                    )
                }
            }

            item {
                SettingsGroup(title = stringResource(R.string.settings_account_security)) {
                    MenuItemRow(
                        icon = Icons.Outlined.LocalFireDepartment,
                        title = stringResource(R.string.settings_panic_wipe_title),
                        subtitle = stringResource(R.string.settings_panic_wipe_subtitle),
                        onClick = { onPanicWipeChange(!panicWipeEnabled) },
                        trailing = { Switch(checked = panicWipeEnabled, onCheckedChange = null) }
                    )
                    MenuItemRow(
                        icon = Icons.AutoMirrored.Outlined.Logout,
                        title = stringResource(R.string.settings_logout),
                        subtitle = stringResource(R.string.settings_logout_subtitle),
                        danger = true,
                        showDivider = false,
                        onClick = onLogout
                    )
                }
            }

            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    EclipseMark(size = 40.dp)
                    Text(
                        text = stringResource(R.string.app_name).lowercase(),
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = 26.sp, lineHeight = 28.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Info item (non-clickable)
 */
@Composable
private fun SettingInfoItem(
    title: String,
    value: String,
    showDivider: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
    }
    if (showDivider) {
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}
