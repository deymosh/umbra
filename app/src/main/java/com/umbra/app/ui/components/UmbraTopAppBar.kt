package com.umbra.app.ui.components

import com.umbra.app.ui.theme.UmbraTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
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
        // Chrome sits on the same void as the content — separation comes from the content's own
        // hairlines, not a tinted slab across the top of every screen.
        containerColor = MaterialTheme.colorScheme.background,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
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

/**
 * A screen's single primary action in the top bar (Post, Save): a filled corona pill with a
 * loading state, so the one thing the screen exists to do is never a faint text button.
 */
@Composable
fun TopBarPrimaryAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        colors = ButtonDefaults.buttonColors(containerColor = UmbraTheme.colors.corona),
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = Modifier
            .padding(end = 8.dp)
            .height(38.dp)
    ) {
        if (loading) {
            LoadingSpinner(size = 18.dp, strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}
