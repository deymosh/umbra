package com.umbra.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.navigation.NavController
import com.umbra.app.R
import com.umbra.app.ui.Screen
import com.umbra.app.ui.auth.LoginViewModel
import com.umbra.app.ui.components.MenuItemRow
import com.umbra.app.ui.components.PrivacyLogoutProgressDialog
import com.umbra.app.ui.components.SectionHeader
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import kotlin.OptIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.umbra.app.util.logging.UmbraLog

private val settingsScreenLogger = UmbraLog.tag("SettingsScreen")

/**
 * Settings screen main menu (NIP-01 compliant client configuration)
 * Provides navigation to relay configuration and feed settings
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, loginViewModel: LoginViewModel) {
    val scope = rememberCoroutineScope()
    var isLoggingOut by remember { mutableStateOf(false) }

    if (isLoggingOut) {
        PrivacyLogoutProgressDialog()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        UmbraTopAppBar(
            title = { Text(stringResource(R.string.settings_title)) },
            navigationIcon = {
                UmbraTopAppBarDefaults.BackNavigationIcon(onClick = {
                    val popped = navController.popBackStack()
                    if (!popped) {
                        navController.navigate(Screen.Feed.route) {
                            launchSingleTop = true
                        }
                    }
                })
            }
        )

        // Settings menu
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionHeader(title = stringResource(R.string.settings_network_configuration))
            }

            item {
                MenuItemRow(
                    icon = Icons.Default.Build,
                    title = stringResource(R.string.settings_configure_relays_title),
                    subtitle = stringResource(R.string.settings_configure_relays_subtitle),
                    onClick = { navController.navigate(Screen.RelayConfig.route) }
                )
            }

            item {
                SectionHeader(title = stringResource(R.string.settings_feed_preferences))
            }

            item {
                MenuItemRow(
                    icon = Icons.Default.Build,
                    title = stringResource(R.string.settings_feed_preferences),
                    subtitle = stringResource(R.string.settings_feed_preferences_subtitle),
                    onClick = { navController.navigate(Screen.FeedConfig.route) }
                )
            }

            item {
                SectionHeader(title = stringResource(R.string.settings_media))
            }

            item {
                MenuItemRow(
                    icon = Icons.Default.Build,
                    title = stringResource(R.string.settings_configure_blossom_servers_title),
                    subtitle = stringResource(R.string.settings_configure_blossom_servers_subtitle),
                    onClick = { navController.navigate(Screen.BlossomServers.route) }
                )
            }

            item {
                SectionHeader(title = stringResource(R.string.settings_appearance))
            }

            item {
                MenuItemRow(
                    icon = Icons.Default.Build,
                    title = stringResource(R.string.settings_appearance_title),
                    subtitle = stringResource(R.string.settings_appearance_subtitle),
                    onClick = { navController.navigate(Screen.Appearance.route) }
                )
            }

            item {
                SectionHeader(title = stringResource(R.string.settings_developer))
            }

            item {
                MenuItemRow(
                    icon = Icons.Default.Build,
                    title = stringResource(R.string.settings_developer_options_title),
                    subtitle = stringResource(R.string.settings_developer_options_subtitle),
                    onClick = { navController.navigate(Screen.DeveloperOptions.route) }
                )
            }

            item {
                MenuItemRow(
                    icon = Icons.Default.Build,
                    title = stringResource(R.string.settings_app_resource_usage_title),
                    subtitle = stringResource(R.string.settings_app_resource_usage_subtitle),
                    onClick = { navController.navigate(Screen.AppResourceUsage.route) }
                )
            }

            item {
                MenuItemRow(
                    icon = Icons.Default.Build,
                    title = stringResource(R.string.settings_db_inspector_title),
                    subtitle = stringResource(R.string.settings_db_inspector_subtitle),
                    onClick = { navController.navigate(Screen.DbInspector.route) }
                )
            }

            item {
                SectionHeader(title = stringResource(R.string.settings_about_umbra))
            }

            item {
                SettingInfoItem(
                    title = stringResource(R.string.settings_version),
                    value = stringResource(R.string.settings_version_value)
                )
            }

            item {
                SettingInfoItem(
                    title = stringResource(R.string.settings_privacy),
                    value = stringResource(R.string.settings_privacy_value)
                )
            }

            item {
                SettingInfoItem(
                    title = stringResource(R.string.settings_architecture),
                    value = stringResource(R.string.settings_architecture_value)
                )
            }

            item {
                SectionHeader(title = stringResource(R.string.settings_account_security))
            }

            item {
                MenuItemRow(
                    icon = Icons.AutoMirrored.Filled.ExitToApp,
                    title = stringResource(R.string.settings_logout),
                    subtitle = stringResource(R.string.settings_logout_subtitle),
                    danger = true,
                    showDivider = false,
                    onClick = {
                        if (isLoggingOut) return@MenuItemRow
                        scope.launch {
                            try {
                                isLoggingOut = true
                                loginViewModel.logout()
                            } catch (e: Exception) {
                                // A failed logout must not be silently indistinguishable
                                // from a successful one — still proceed to the login screen
                                // below since there's no in-app state left to usefully retry
                                // from, but at least record that it happened.
                                settingsScreenLogger.e(e) { "Logout failed" }
                            }
                            isLoggingOut = false
                            navController.navigate(Screen.Login.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    }
                )
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
    value: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
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
    }
}
