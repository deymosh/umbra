package com.umbra.app.ui.components

import com.umbra.app.ui.theme.UmbraTheme
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.umbra.app.R

/**
 * Non-dismissible progress dialog shown while logout wipes local user data.
 */
@Composable
fun PrivacyLogoutProgressDialog() {
    AlertDialog(
        onDismissRequest = { },
        icon = {
            Icon(
                imageVector = Icons.Outlined.Shield,
                contentDescription = null,
                tint = UmbraTheme.colors.secure
            )
        },
        title = {
            Text(text = stringResource(R.string.logout_privacy_wipe_title), style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.logout_privacy_wipe_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LoadingSpinner(size = 28.dp)
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}
