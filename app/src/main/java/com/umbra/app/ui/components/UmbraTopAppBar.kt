package com.umbra.app.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.umbra.app.R

/**
 * Shared TopAppBar shell applying Umbra's standard colors/height. Title and navigationIcon stay
 * @Composable slots (not primitives) so callers with fully custom chrome — e.g. FeedTopBar's
 * clickable avatar navigation icon and serif-styled title — can still use it for just the colors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UmbraTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    expandedHeight: Dp = 56.dp,
    colors: TopAppBarColors = UmbraTopAppBarDefaults.colors()
) {
    TopAppBar(
        modifier = modifier,
        title = title,
        navigationIcon = navigationIcon,
        actions = actions,
        expandedHeight = expandedHeight,
        colors = colors
    )
}

object UmbraTopAppBarDefaults {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun colors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
        actionIconContentColor = MaterialTheme.colorScheme.onSurface
    )

    /** Covers the plain back-arrow sites and, with icon = Icons.Default.Close, a cancel action. */
    @Composable
    fun BackNavigationIcon(
        onClick: () -> Unit,
        icon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription: String = stringResource(R.string.back)
    ) {
        IconButton(onClick = onClick) {
            Icon(imageVector = icon, contentDescription = contentDescription)
        }
    }
}
