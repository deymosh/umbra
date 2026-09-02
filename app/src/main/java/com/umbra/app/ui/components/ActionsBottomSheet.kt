package com.umbra.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * One row in an [ActionsBottomSheet] (pin, mute, copy, delete, ...).
 */
data class ActionItem(
    val icon: ImageVector,
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

/**
 * Bottom sheet listing [actions] as clickable rows — the kebab-menu replacement for a plain
 * DropdownMenu wherever a screen needs more than a couple of per-item actions. Dismisses itself
 * after any row is tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionsBottomSheet(
    actions: List<ActionItem>,
    onDismissRequest: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column {
            actions.forEach { action ->
                val contentColor = if (action.destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
                ListItem(
                    headlineContent = { Text(action.label, color = contentColor) },
                    leadingContent = {
                        Icon(imageVector = action.icon, contentDescription = null, tint = contentColor)
                    },
                    modifier = Modifier.clickable {
                        action.onClick()
                        onDismissRequest()
                    }
                )
            }
        }
    }
}
