package com.umbra.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.umbra.app.R
import com.umbra.app.domain.util.TrackingTokenSanitizer
import com.umbra.app.ui.common.resolve
import com.umbra.app.ui.components.LoadingSpinner
import com.umbra.app.ui.components.MediaUploadDialog
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import com.umbra.app.util.MediaMetadataStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    onNavigateBack: () -> Unit,
    viewModel: EditProfileViewModel
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var trackingRemovalNoticeTick by remember { mutableIntStateOf(0) }
    var otherFieldsExpanded by remember { mutableStateOf(false) }

    // saveProfile()'s Amber sign round trip goes through the single app-wide launcher
    // (AppSessionEffects) now — no per-screen launcher needed here. confirmPendingUpload()'s
    // does too, once the upload dialog below is confirmed.

    val pickPictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (!viewModel.beginPictureUpload()) return@rememberLauncherForActivityResult

        coroutineScope.launch {
            // Strip EXIF/container metadata before anything leaves the device — fails closed:
            // any file MediaMetadataStripper can't confirm as cleaned is never uploaded.
            val picked = withContext(Dispatchers.IO) {
                val rawMimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                val result = MediaMetadataStripper.strip(uri, rawMimeType, context)
                if (!result.stripped) return@withContext null

                val bytes = context.contentResolver.openInputStream(result.uri)?.use { it.readBytes() }
                bytes?.let { Triple(it, result.mimeType, result.uri) }
            }
            if (picked == null) {
                viewModel.onPictureMetadataStripFailed()
            } else {
                val (bytes, mimeType, previewUri) = picked
                viewModel.onPictureReadyForDialog(bytes, mimeType, previewUri)
            }
        }
    }

    val pickBannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (!viewModel.beginBannerUpload()) return@rememberLauncherForActivityResult

        coroutineScope.launch {
            val picked = withContext(Dispatchers.IO) {
                val rawMimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                val result = MediaMetadataStripper.strip(uri, rawMimeType, context)
                if (!result.stripped) return@withContext null

                val bytes = context.contentResolver.openInputStream(result.uri)?.use { it.readBytes() }
                bytes?.let { Triple(it, result.mimeType, result.uri) }
            }
            if (picked == null) {
                viewModel.onBannerMetadataStripFailed()
            } else {
                val (bytes, mimeType, previewUri) = picked
                viewModel.onBannerReadyForDialog(bytes, mimeType, previewUri)
            }
        }
    }

    // Navigate back after a successful save
    LaunchedEffect(state.savedSuccessfully) {
        if (state.savedSuccessfully) {
            snackbarHostState.showSnackbar(context.getString(R.string.edit_profile_saved))
            onNavigateBack()
        }
    }

    // Show error in snackbar
    LaunchedEffect(state.errorMessage) {
        val msg = state.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg.resolve(context))
        viewModel.clearError()
    }

    LaunchedEffect(trackingRemovalNoticeTick) {
        if (trackingRemovalNoticeTick > 0) {
            snackbarHostState.showSnackbar(context.getString(R.string.tracking_token_removed_notice))
        }
    }

    Scaffold(
        topBar = {
            UmbraTopAppBar(
                title = { Text(stringResource(R.string.edit_profile_title)) },
                navigationIcon = {
                    UmbraTopAppBarDefaults.BackNavigationIcon(onClick = onNavigateBack)
                },
                actions = {
                    TextButton(
                        onClick = viewModel::saveProfile,
                        enabled = !state.isSaving
                    ) {
                        if (state.isSaving) {
                            LoadingSpinner(size = 18.dp, strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = stringResource(R.string.edit_profile_save)
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        if (state.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LoadingSpinner()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // Banner, with a small edit icon in its top-right corner.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f)
                ) {
                    if (state.banner.isNotBlank()) {
                        AsyncImage(
                            model = state.banner,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {}
                    }
                    EditIconOverlay(
                        onClick = {
                            pickBannerLauncher.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        enabled = !state.isUploadingBanner && !state.isSaving,
                        isUploading = state.isUploadingBanner,
                        contentDescription = stringResource(R.string.edit_profile_banner_upload_cd),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    )
                }

                // Avatar, overlapping the banner's bottom edge, with its own edit icon.
                Box(
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .offset(y = (-32).dp)
                ) {
                    Box(modifier = Modifier.size(72.dp)) {
                        if (state.picture.isNotBlank()) {
                            AsyncImage(
                                model = state.picture,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {}
                        }
                        EditIconOverlay(
                            onClick = {
                                pickPictureLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            enabled = !state.isUploadingPicture && !state.isSaving,
                            isUploading = state.isUploadingPicture,
                            contentDescription = stringResource(R.string.edit_profile_picture_upload_cd),
                            modifier = Modifier.align(Alignment.BottomEnd)
                        )
                    }
                }

                // Shown for every Blossom upload — picture and banner alike — right after
                // metadata stripping succeeds, before any bytes leave the device.
                state.pendingUpload?.let { pending ->
                    MediaUploadDialog(
                        previewUri = pending.previewUri,
                        mimeType = pending.mimeType,
                        availableServers = state.availableUploadServers,
                        selectedServer = pending.selectedServer,
                        onServerSelected = viewModel::onUploadServerSelected,
                        isUploading = false,
                        onConfirm = viewModel::confirmPendingUpload,
                        onCancel = viewModel::cancelPendingUpload,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    Box(modifier = Modifier.padding(bottom = 28.dp))
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .offset(y = (-20).dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = state.displayName,
                        onValueChange = TrackingTokenSanitizer.sanitizingOnValueChange(
                            setText = viewModel::onDisplayNameChange,
                            onSanitized = { removed -> if (removed) trackingRemovalNoticeTick += 1 }
                        ),
                        label = { Text(stringResource(R.string.edit_profile_display_name)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !state.isSaving
                    )
                    OutlinedTextField(
                        value = state.name,
                        onValueChange = TrackingTokenSanitizer.sanitizingOnValueChange(
                            setText = viewModel::onNameChange,
                            onSanitized = { removed -> if (removed) trackingRemovalNoticeTick += 1 }
                        ),
                        label = { Text(stringResource(R.string.edit_profile_name)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !state.isSaving
                    )
                    OutlinedTextField(
                        value = state.about,
                        onValueChange = TrackingTokenSanitizer.sanitizingOnValueChange(
                            setText = viewModel::onAboutChange,
                            onSanitized = { removed -> if (removed) trackingRemovalNoticeTick += 1 }
                        ),
                        label = { Text(stringResource(R.string.edit_profile_bio)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        enabled = !state.isSaving
                    )
                    OutlinedTextField(
                        value = state.website,
                        onValueChange = TrackingTokenSanitizer.sanitizingOnValueChange(
                            setText = viewModel::onWebsiteChange,
                            onSanitized = { removed -> if (removed) trackingRemovalNoticeTick += 1 }
                        ),
                        label = { Text(stringResource(R.string.edit_profile_website)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !state.isSaving
                    )
                    OutlinedTextField(
                        value = state.nip05,
                        onValueChange = TrackingTokenSanitizer.sanitizingOnValueChange(
                            setText = viewModel::onNip05Change,
                            onSanitized = { removed -> if (removed) trackingRemovalNoticeTick += 1 }
                        ),
                        label = { Text(stringResource(R.string.edit_profile_nip05)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !state.isSaving
                    )
                    OutlinedTextField(
                        value = state.lud16,
                        onValueChange = TrackingTokenSanitizer.sanitizingOnValueChange(
                            setText = viewModel::onLud16Change,
                            onSanitized = { removed -> if (removed) trackingRemovalNoticeTick += 1 }
                        ),
                        label = { Text(stringResource(R.string.edit_profile_lud16)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !state.isSaving
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .clip(MaterialTheme.shapes.small)
                            .clickable { otherFieldsExpanded = !otherFieldsExpanded }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.edit_profile_other_fields),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            imageVector = if (otherFieldsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    AnimatedVisibility(visible = otherFieldsExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = state.banner,
                                onValueChange = viewModel::onBannerChange,
                                label = { Text(stringResource(R.string.edit_profile_banner_url)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !state.isSaving && !state.isUploadingBanner
                            )
                            OutlinedTextField(
                                value = state.picture,
                                onValueChange = viewModel::onPictureChange,
                                label = { Text(stringResource(R.string.edit_profile_picture_url)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !state.isSaving && !state.isUploadingPicture
                            )
                            OutlinedTextField(
                                value = state.lud06,
                                onValueChange = viewModel::onLud06Change,
                                label = { Text(stringResource(R.string.edit_profile_lud06)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !state.isSaving
                            )
                        }
                    }

                    // Bottom breathing room now that Save lives in the top bar, not a full-width
                    // button here.
                    Box(modifier = Modifier.padding(bottom = 16.dp))
                }
            }
        }
    }
}

/** Small circular edit-icon affordance overlaid on a corner of an avatar/banner image. */
@Composable
private fun EditIconOverlay(
    onClick: () -> Unit,
    enabled: Boolean,
    isUploading: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 3.dp,
        modifier = modifier.size(28.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isUploading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.PhotoCamera,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
