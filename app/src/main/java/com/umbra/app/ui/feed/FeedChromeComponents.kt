package com.umbra.app.ui.feed

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

