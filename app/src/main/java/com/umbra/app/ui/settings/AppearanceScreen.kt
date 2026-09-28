package com.umbra.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import com.umbra.app.ui.components.EclipseMark
import com.umbra.app.ui.theme.UmbraTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import com.umbra.app.ui.theme.toColorScheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    onNavigateBack: () -> Unit,
    viewModel: AppearanceViewModel
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        UmbraTopAppBar(
            title = { Text(stringResource(R.string.appearance_title)) },
            navigationIcon = {
                UmbraTopAppBarDefaults.BackNavigationIcon(onClick = onNavigateBack)
            }
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.appearance_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            items(
                state.options,
                key = { it.theme },
                contentType = { "appearance_option_row" }
            ) { option ->
                AppearanceOptionRow(
                    option = option,
                    onClick = { viewModel.selectTheme(option.theme) }
                )
            }
        }
    }
}

@Composable
private fun AppearanceOptionRow(
    option: AppearanceOptionItem,
    onClick: () -> Unit
) {
    val scheme = option.theme.toColorScheme()
    val shape = MaterialTheme.shapes.large
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                width = if (option.selected) 2.dp else 1.dp,
                color = if (option.selected) UmbraTheme.colors.corona else MaterialTheme.colorScheme.outlineVariant,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PalettePreview(scheme)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(option.nameRes),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemeSwatch(scheme.primary)
                ThemeSwatch(scheme.secondary)
                ThemeSwatch(scheme.tertiary)
            }
        }
        if (option.selected) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = stringResource(R.string.appearance_selected_cd),
                tint = UmbraTheme.colors.corona
            )
        }
    }
}

/** A tiny phone screen painted in [scheme]: its void, an eclipse in its corona, a skeleton note. */
@Composable
private fun PalettePreview(scheme: androidx.compose.material3.ColorScheme) {
    Column(
        modifier = Modifier
            .size(width = 76.dp, height = 96.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(scheme.background)
            .border(1.dp, scheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        EclipseMark(size = 26.dp, corona = scheme.primary)
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(scheme.onSurface.copy(alpha = 0.8f)))
        Box(Modifier.fillMaxWidth(0.7f).height(6.dp).clip(CircleShape).background(scheme.onSurfaceVariant.copy(alpha = 0.6f)))
        Box(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(5.dp)).background(scheme.surfaceContainerHigh))
    }
}

@Composable
private fun ThemeSwatch(color: Color) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(color)
    )
}
