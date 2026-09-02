package com.umbra.app.ui.components

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
        title = {
            Text(text = stringResource(R.string.logout_privacy_wipe_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.logout_privacy_wipe_message),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = androidx.compose.ui.Modifier.height(2.dp))
                LoadingSpinner(size = 26.dp)
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}
