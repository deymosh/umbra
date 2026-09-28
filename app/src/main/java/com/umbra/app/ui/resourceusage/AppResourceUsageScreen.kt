package com.umbra.app.ui.resourceusage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.umbra.app.R
import com.umbra.app.domain.model.ResourceUsageSnapshot
import com.umbra.app.ui.components.LoadingSpinner
import com.umbra.app.ui.components.SettingsGroup
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import com.umbra.app.ui.components.UsageBar
import com.umbra.app.ui.theme.MonoStyle

private const val BYTES_PER_MB = 1024L * 1024L

private fun Long.toMb(): String = (this / BYTES_PER_MB).toString()

@Composable
fun AppResourceUsageScreen(
    onNavigateBack: () -> Unit,
    viewModel: AppResourceUsageViewModel
) {
    val state by viewModel.state.collectAsState()
    AppResourceUsageContent(
        state = state,
        onNavigateBack = onNavigateBack,
        onClearEventCache = viewModel::clearEventCache,
        onTrimAllCaches = viewModel::trimAllCaches
    )
}

@Composable
internal fun AppResourceUsageContent(
    state: AppResourceUsageState,
    onNavigateBack: () -> Unit,
    onClearEventCache: () -> Unit,
    onTrimAllCaches: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        UmbraTopAppBar(
            title = { Text(stringResource(R.string.resource_usage_title)) },
            navigationIcon = {
                UmbraTopAppBarDefaults.BackNavigationIcon(onClick = onNavigateBack)
            }
        )

        val snapshot = state.snapshot
        if (snapshot == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingSpinner()
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
        ) {
            HeapHero(snapshot)

            SettingsGroup(title = stringResource(R.string.resource_usage_section_caches)) {
                val memUsed = snapshot.imageMemoryCacheUsedBytes
                val memMax = snapshot.imageMemoryCacheMaxBytes
                MeterRow(
                    label = stringResource(R.string.resource_usage_image_memory_cache),
                    value = usedOfMax(memUsed, memMax),
                    fraction = fractionOf(memUsed, memMax)
                )
                val diskUsed = snapshot.imageDiskCacheUsedBytes
                val diskMax = snapshot.imageDiskCacheMaxBytes
                MeterRow(
                    label = stringResource(R.string.resource_usage_image_disk_cache),
                    value = usedOfMax(diskUsed, diskMax),
                    fraction = fractionOf(diskUsed, diskMax)
                )
                MeterRow(
                    label = stringResource(R.string.resource_usage_event_cache),
                    value = stringResource(
                        R.string.resource_usage_value_count,
                        snapshot.eventCacheSize,
                        snapshot.eventCacheMaxSize
                    ),
                    fraction = snapshot.eventCacheSize.toFloat() / snapshot.eventCacheMaxSize.coerceAtLeast(1)
                )
                ValueRow(
                    label = stringResource(R.string.resource_usage_profile_cache),
                    value = entries(snapshot.profileCacheEntries)
                )
                ValueRow(
                    label = stringResource(R.string.resource_usage_relaylist_cache),
                    value = entries(snapshot.relayListCacheEntries)
                )
                ValueRow(
                    label = stringResource(R.string.resource_usage_ownerlist_cache),
                    value = entries(snapshot.ownerListCacheEntries),
                    showDivider = false
                )
            }

            SettingsGroup(title = stringResource(R.string.resource_usage_section_storage)) {
                ValueRow(
                    label = stringResource(R.string.resource_usage_database_size),
                    value = stringResource(R.string.resource_usage_value_mb, snapshot.databaseFileBytes.toMb()),
                    showDivider = false
                )
            }

            SettingsGroup(title = stringResource(R.string.resource_usage_section_actions)) {
                ActionRow(
                    icon = Icons.Outlined.DeleteSweep,
                    label = stringResource(R.string.resource_usage_clear_event_cache),
                    busy = state.isClearingEventCache,
                    onClick = onClearEventCache
                )
                ActionRow(
                    icon = Icons.Outlined.CleaningServices,
                    label = stringResource(R.string.resource_usage_trim_all_caches),
                    busy = state.isTrimmingCaches,
                    onClick = onTrimAllCaches,
                    showDivider = false
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** JVM heap as the headline number, with native heap and the device memory class beneath. */
@Composable
private fun HeapHero(snapshot: ResourceUsageSnapshot) {
    val fraction = snapshot.jvmHeapUsedBytes.toFloat() / snapshot.jvmHeapMaxBytes.coerceAtLeast(1L)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(20.dp)
    ) {
        Text(
            text = stringResource(R.string.resource_usage_jvm_heap).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
            Text(
                text = snapshot.jvmHeapUsedBytes.toMb(),
                style = MonoStyle.copy(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = " / " + stringResource(R.string.resource_usage_value_mb, snapshot.jvmHeapMaxBytes.toMb()),
                style = MonoStyle.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${(fraction * 100).toInt()}%",
                style = MonoStyle.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        UsageBar(fraction = fraction, modifier = Modifier.padding(top = 12.dp))
        Row(modifier = Modifier.padding(top = 18.dp)) {
            MiniStat(
                label = stringResource(R.string.resource_usage_native_heap),
                value = stringResource(R.string.resource_usage_value_mb, snapshot.nativeHeapAllocatedBytes.toMb()),
                modifier = Modifier.weight(1f)
            )
            MiniStat(
                label = stringResource(R.string.resource_usage_device_memory_class),
                value = stringResource(R.string.resource_usage_value_mb, snapshot.deviceMemoryClassMb.toString()),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MonoStyle.copy(fontSize = 15.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun usedOfMax(used: Long?, max: Long?): String =
    if (used != null && max != null) {
        stringResource(R.string.resource_usage_value_used_max_mb, used.toMb(), max.toMb())
    } else {
        stringResource(R.string.resource_usage_value_mb, "0")
    }

private fun fractionOf(used: Long?, max: Long?): Float =
    if (used != null && max != null) used.toFloat() / max.coerceAtLeast(1L) else 0f

@Composable
private fun entries(count: Int): String =
    pluralStringResource(R.plurals.resource_usage_value_entries, count, count)

@Composable
private fun ColumnScope.RowDivider(show: Boolean) {
    if (show) {
        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@Composable
private fun ColumnScope.ValueRow(label: String, value: String, showDivider: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(text = value, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    RowDivider(showDivider)
}

@Composable
private fun ColumnScope.MeterRow(label: String, value: String, fraction: Float, showDivider: Boolean = true) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(text = value, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        UsageBar(fraction = fraction, modifier = Modifier.padding(top = 10.dp))
    }
    RowDivider(showDivider)
}

@Composable
private fun ColumnScope.ActionRow(
    icon: ImageVector,
    label: String,
    busy: Boolean,
    onClick: () -> Unit,
    showDivider: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (busy) {
                LoadingSpinner(size = 18.dp, strokeWidth = 2.dp)
            } else {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
    RowDivider(showDivider)
}
