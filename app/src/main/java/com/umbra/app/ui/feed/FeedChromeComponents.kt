package com.umbra.app.ui.feed

import com.umbra.app.ui.theme.MonoStyle
import com.umbra.app.ui.components.truncatePublicKey
import com.umbra.app.ui.components.EclipseMark
import com.umbra.app.domain.nip19.Bech32Encoder
import com.umbra.app.domain.nip05.Nip05VerificationState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import com.umbra.app.ui.components.NetworkStatusPill
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.ui.components.privateKeyboardOptions
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.media.UserAvatar

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun FeedTopBar(
    currentProfile: UserProfile?,
    currentPubkey: String?,
    searchVisible: Boolean,
    relayCount: Int,
    isConnected: Boolean,
    isTorConnected: Boolean,
    isTorStarting: Boolean,
    onAvatarClick: () -> Unit,
    onToggleSearch: () -> Unit,
    onStatusClick: (() -> Unit)? = null,
    userRepository: UserRepository? = null
) {
    UmbraTopAppBar(
        navigationIcon = {
            UserAvatar(
                userProfile = currentProfile,
                pubkey = currentPubkey ?: "U",
                size = 34.dp,
                shape = CircleShape,
                authorPubkey = currentPubkey,
                userRepository = userRepository,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onAvatarClick)
            )
        },
        title = {
            // The wordmark, not the user's name: the avatar beside it already says who you are,
            // and this is the one place the brand gets to speak.
            Text(
                text = stringResource(R.string.app_name).lowercase(),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 30.sp, lineHeight = 32.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        },
        actions = {
            NetworkStatusPill(
                isTorConnected = isTorConnected,
                isTorStarting = isTorStarting,
                relayCount = relayCount,
                relaysConnected = isConnected,
                onClick = onStatusClick
            )
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = onToggleSearch) {
                Icon(
                    imageVector = if (searchVisible) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = if (searchVisible) stringResource(R.string.search_close) else stringResource(R.string.search_open),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    )
}

@Composable
internal fun FeedSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    autoFocus: Boolean
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .focusRequester(focusRequester)
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        singleLine = true,
        shape = CircleShape,
        keyboardOptions = privateKeyboardOptions(KeyboardOptions.Default),
        placeholder = {
            Text(
                stringResource(R.string.search_placeholder),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.search_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else null,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.primary
        )
    )
}

/**
 * The navigation drawer: who you are at the top, the handful of places you can go as plain
 * one-line items, Log out set apart at the bottom, and the brand at the foot.
 */
@Composable
internal fun FeedDrawerContent(
    currentProfile: UserProfile?,
    currentPubkey: String?,
    onProfile: () -> Unit,
    onRelays: () -> Unit,
    onFilters: () -> Unit,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    userRepository: UserRepository? = null
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 12.dp, vertical = 16.dp)
        ) {
            if (!currentPubkey.isNullOrBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .clickable(onClick = onProfile)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    UserAvatar(
                        userProfile = currentProfile,
                        pubkey = currentPubkey,
                        size = 56.dp,
                        shape = CircleShape,
                        authorPubkey = currentPubkey,
                        userRepository = userRepository
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = currentProfile?.getUserDisplayName() ?: currentPubkey.truncatePublicKey(8, 6),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val nip05 = currentProfile?.nip05?.takeIf {
                            it.isNotBlank() && currentProfile.nip05VerificationState == Nip05VerificationState.Verified
                        }
                        Text(
                            text = nip05?.removePrefix("_@") ?: Bech32Encoder.encodeNpub(currentPubkey).truncatePublicKey(10, 6),
                            style = if (nip05 != null) MaterialTheme.typography.bodyMedium else MonoStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    EclipseMark(size = 44.dp, ignition = 0.4f)
                    Text(
                        text = stringResource(R.string.drawer_anonymous),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            if (!currentPubkey.isNullOrBlank()) {
                DrawerItem(Icons.Outlined.Person, stringResource(R.string.menu_profile), onProfile)
            }
            DrawerItem(Icons.Outlined.Hub, stringResource(R.string.menu_relays), onRelays)
            DrawerItem(Icons.Outlined.Tune, stringResource(R.string.menu_feed_filters), onFilters)
            DrawerItem(Icons.Outlined.Settings, stringResource(R.string.menu_settings), onSettings)

            Spacer(modifier = Modifier.weight(1f))

            DrawerItem(
                icon = Icons.AutoMirrored.Outlined.Logout,
                label = stringResource(R.string.menu_logout),
                onClick = onLogout,
                tint = MaterialTheme.colorScheme.error
            )

            Row(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                EclipseMark(size = 28.dp)
                Column {
                    Text(
                        text = stringResource(R.string.app_name).lowercase(),
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = 22.sp, lineHeight = 24.sp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.drawer_title_orbot_powered),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    NavigationDrawerItem(
        label = { Text(label, style = MaterialTheme.typography.titleSmall) },
        icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
        selected = false,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(
            unselectedContainerColor = Color.Transparent,
            unselectedTextColor = tint,
            unselectedIconColor = if (tint == MaterialTheme.colorScheme.onSurface) MaterialTheme.colorScheme.onSurfaceVariant else tint
        )
    )
}
