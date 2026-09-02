package com.umbra.app.ui.blossom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.umbra.app.R
import com.umbra.app.ui.common.resolve
import com.umbra.app.ui.components.LoadingSpinner
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlossomServersScreen(
    onNavigateBack: () -> Unit,
    viewModel: BlossomServersViewModel
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.savedSuccessfully) {
        if (state.savedSuccessfully) {
            snackbarHostState.showSnackbar(context.getString(R.string.blossom_servers_saved))
            viewModel.clearSavedFlag()
        }
    }

    LaunchedEffect(state.errorMessage) {
        val msg = state.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg.resolve(context))
        viewModel.clearError()
    }

    Scaffold(
        topBar = {
            UmbraTopAppBar(
                title = { Text(stringResource(R.string.blossom_servers_title)) },
                navigationIcon = {
                    UmbraTopAppBarDefaults.BackNavigationIcon(onClick = onNavigateBack)
                },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = !state.isSaving) {
                        if (state.isSaving) {
                            LoadingSpinner(size = 18.dp, strokeWidth = 2.dp)
                        } else {
                            Icon(imageVector = Icons.Filled.Check, contentDescription = stringResource(R.string.save))
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        if (state.isLoading) {
            Column(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LoadingSpinner()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.blossom_servers_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (state.servers.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.blossom_servers_empty, state.defaultServerUrl),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            itemsIndexed(state.servers, key = { _, server -> server }, contentType = { _, _ -> "blossom_server_row" }) { index, server ->
                BlossomServerRow(
                    server = server,
                    isFirst = index == 0,
                    isLast = index == state.servers.lastIndex,
                    onMoveUp = { viewModel.moveServerUp(index) },
                    onMoveDown = { viewModel.moveServerDown(index) },
                    onRemove = { viewModel.removeServer(server) }
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = state.newServerInput,
                        onValueChange = viewModel::onNewServerInputChange,
                        label = { Text(stringResource(R.string.blossom_server_url_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = viewModel::addServer) {
                        Icon(imageVector = Icons.Filled.Add, contentDescription = stringResource(R.string.blossom_server_add))
                    }
                }
            }
        }
    }
}

@Composable
private fun BlossomServerRow(
    server: String,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = server,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp)
            )
            IconButton(onClick = onMoveUp, enabled = !isFirst) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.blossom_server_move_up)
                )
            }
            IconButton(onClick = onMoveDown, enabled = !isLast) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.blossom_server_move_down)
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.blossom_server_remove),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
