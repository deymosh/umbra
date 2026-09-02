package com.umbra.app.ui.resourceusage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.domain.model.ResourceUsageSnapshot
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import com.umbra.app.ui.components.UsageBar

private const val BYTES_PER_MB = 1024L * 1024L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppResourceUsageScreen(
    onNavigateBack: () -> Unit,
    viewModel: AppResourceUsageViewModel
) {
    val state by viewModel.state.collectAsState()

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
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                MetricRowWithBar(
                    label = stringResource(R.string.resource_usage_jvm_heap),
                    value = stringResource(
                        R.string.resource_usage_value_used_max_mb,
                        (snapshot.jvmHeapUsedBytes / BYTES_PER_MB).toString(),
                        (snapshot.jvmHeapMaxBytes / BYTES_PER_MB).toString()
                    ),
                    fraction = snapshot.jvmHeapUsedBytes.toFloat() / snapshot.jvmHeapMaxBytes.coerceAtLeast(1L).toFloat()
                )
            }
            item {
                val memUsed = snapshot.imageMemoryCacheUsedBytes
                val memMax = snapshot.imageMemoryCacheMaxBytes
                MetricRowWithBar(
                    label = stringResource(R.string.resource_usage_image_memory_cache),
                    value = if (memUsed != null && memMax != null) {
                        stringResource(
                            R.string.resource_usage_value_used_max_mb,
                            (memUsed / BYTES_PER_MB).toString(),
                            (memMax / BYTES_PER_MB).toString()
                        )
                    } else {
                        stringResource(R.string.resource_usage_value_mb, "0")
                    },
                    fraction = if (memUsed != null && memMax != null) {
                        memUsed.toFloat() / memMax.coerceAtLeast(1L).toFloat()
                    } else {
                        0f
                    }
                )
            }
            item {
                val diskUsed = snapshot.imageDiskCacheUsedBytes
                val diskMax = snapshot.imageDiskCacheMaxBytes
                MetricRowWithBar(
                    label = stringResource(R.string.resource_usage_image_disk_cache),
                    value = if (diskUsed != null && diskMax != null) {
                        stringResource(
                            R.string.resource_usage_value_used_max_mb,
                            (diskUsed / BYTES_PER_MB).toString(),
                            (diskMax / BYTES_PER_MB).toString()
                        )
                    } else {
                        stringResource(R.string.resource_usage_value_mb, "0")
                    },
                    fraction = if (diskUsed != null && diskMax != null) {
                        diskUsed.toFloat() / diskMax.coerceAtLeast(1L).toFloat()
                    } else {
                        0f
                    }
                )
            }
            item {
                MetricRow(
                    label = stringResource(R.string.resource_usage_native_heap),
                    value = stringResource(R.string.resource_usage_value_mb, (snapshot.nativeHeapAllocatedBytes / BYTES_PER_MB).toString())
                )
            }
            item {
                MetricRow(
                    label = stringResource(R.string.resource_usage_device_memory_class),
                    value = stringResource(R.string.resource_usage_value_mb, snapshot.deviceMemoryClassMb.toString())
                )
            }
            item {
                MetricRow(
                    label = stringResource(R.string.resource_usage_event_cache),
                    value = stringResource(
                        R.string.resource_usage_value_count,
                        snapshot.eventCacheSize,
                        snapshot.eventCacheMaxSize
                    )
                )
            }
            item {
                OutlinedButton(
                    onClick = viewModel::clearEventCache,
                    enabled = !state.isClearingEventCache,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.resource_usage_clear_event_cache))
                }
            }
            item {
                MetricRow(
                    label = stringResource(R.string.resource_usage_profile_cache),
                    value = pluralStringResource(
                        R.plurals.resource_usage_value_entries,
                        snapshot.profileCacheEntries,
                        snapshot.profileCacheEntries
                    )
                )
            }
            item {
                MetricRow(
                    label = stringResource(R.string.resource_usage_relaylist_cache),
                    value = pluralStringResource(
                        R.plurals.resource_usage_value_entries,
                        snapshot.relayListCacheEntries,
                        snapshot.relayListCacheEntries
                    )
                )
            }
            item {
                MetricRow(
                    label = stringResource(R.string.resource_usage_ownerlist_cache),
                    value = pluralStringResource(
                        R.plurals.resource_usage_value_entries,
                        snapshot.ownerListCacheEntries,
                        snapshot.ownerListCacheEntries
                    )
                )
            }
            item {
                OutlinedButton(
                    onClick = viewModel::trimAllCaches,
                    enabled = !state.isTrimmingCaches,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.resource_usage_trim_all_caches))
                }
            }
            item {
                MetricRow(
                    label = stringResource(R.string.resource_usage_database_size),
                    value = stringResource(R.string.resource_usage_value_mb, (snapshot.databaseFileBytes / BYTES_PER_MB).toString())
                )
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MetricRowWithBar(label: String, value: String, fraction: Float) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            UsageBar(fraction = fraction, modifier = Modifier.padding(top = 10.dp))
        }
    }
}
