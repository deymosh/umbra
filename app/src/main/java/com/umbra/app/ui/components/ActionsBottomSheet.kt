package com.umbra.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outline) }
    ) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            val (regular, destructive) = actions.partition { !it.destructive }
            regular.forEach { action -> ActionRow(action, onDismissRequest) }
            if (destructive.isNotEmpty()) {
                // Destructive actions are set apart so they're never hit by a slip of the thumb.
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                destructive.forEach { action -> ActionRow(action, onDismissRequest) }
            }
        }
    }
}

@Composable
private fun ActionRow(action: ActionItem, onDismissRequest: () -> Unit) {
    val contentColor = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                action.onClick()
                onDismissRequest()
            }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            tint = if (action.destructive) contentColor else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Text(action.label, style = MaterialTheme.typography.bodyLarge, color = contentColor)
    }
}
