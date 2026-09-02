package com.umbra.app.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.ui.components.privateKeyboardOptions
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import com.umbra.app.ui.components.media.UserAvatar
import com.umbra.app.ui.components.truncatePublicKey

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
    userRepository: UserRepository? = null
) {
    UmbraTopAppBar(
        navigationIcon = {
            UserAvatar(
                userProfile = currentProfile,
                pubkey = currentPubkey ?: "U",
                size = 36.dp,
                shape = CircleShape,
                authorPubkey = currentPubkey,
                userRepository = userRepository,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .clickable(onClick = onAvatarClick)
            )
        },
        title = {
            Text(
                text = currentProfile?.getUserDisplayName()
                    ?: currentPubkey?.truncatePublicKey(8, 8)
                    ?: stringResource(R.string.app_name).lowercase(),
                modifier = Modifier.padding(start = 6.dp),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Serif,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        actions = {
            RelayStatusBadge(
                relayCount = relayCount,
                isConnected = isConnected
            )
            Spacer(modifier = Modifier.width(4.dp))
            TorStatusBadge(isTorConnected = isTorConnected, isTorStarting = isTorStarting)
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = onToggleSearch) {
                Icon(
                    imageVector = if (searchVisible) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = if (searchVisible) stringResource(R.string.search_close) else stringResource(R.string.search_open),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        colors = UmbraTopAppBarDefaults.colors()
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

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .focusRequester(focusRequester)
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        singleLine = true,
        shape = RoundedCornerShape(50),
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
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
        )
    )
}

