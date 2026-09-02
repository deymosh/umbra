package com.umbra.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.umbra.app.R

@Composable
fun QuickActionBottomBar(
    modifier: Modifier = Modifier,
    onGoTop: () -> Unit,
    onCompose: () -> Unit,
    onRelays: () -> Unit,
    onSettings: () -> Unit
) {
    // Barra inferior pixel-perfect
    Box(
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
            tonalElevation = 4.dp,
            shadowElevation = 16.dp,
            modifier = Modifier
                .fillMaxWidth(0.80f)
                .widthIn(min = 240.dp, max = 380.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Home
                IconButton(
                    onClick = onGoTop,
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = stringResource(R.string.go_to_top),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                // Redactar
                IconButton(
                    onClick = onCompose,
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        Icons.Default.Create,
                        contentDescription = stringResource(R.string.compose_note_cd),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                // Buscar
                IconButton(
                    onClick = onRelays,
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Hub,
                        contentDescription = stringResource(R.string.menu_relays),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                // Ajustes
                IconButton(
                    onClick = onSettings,
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = stringResource(R.string.settings_title),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}
