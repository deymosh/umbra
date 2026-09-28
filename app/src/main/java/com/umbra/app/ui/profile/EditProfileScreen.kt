package com.umbra.app.ui.profile

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.umbra.app.R
import com.umbra.app.domain.util.TrackingTokenSanitizer
import com.umbra.app.ui.common.resolve
import com.umbra.app.ui.components.EclipseMark
import com.umbra.app.ui.components.GroupTextField
import com.umbra.app.ui.components.LoadingSpinner
import com.umbra.app.ui.components.MediaUploadDialog
import com.umbra.app.ui.components.SettingsGroup
import com.umbra.app.ui.components.TopBarPrimaryAction
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import com.umbra.app.util.MediaMetadataStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EditProfileScreen(
    onNavigateBack: () -> Unit,
    viewModel: EditProfileViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var trackingRemovalNoticeTick by remember { mutableIntStateOf(0) }

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

    fun sanitizing(set: (String) -> Unit): (String) -> Unit =
        TrackingTokenSanitizer.sanitizingOnValueChange(
            setText = set,
            onSanitized = { removed -> if (removed) trackingRemovalNoticeTick += 1 }
        )

    EditProfileContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onNavigateBack = onNavigateBack,
        onSave = viewModel::saveProfile,
        onFieldChange = { field, value ->
            when (field) {
                ProfileField.DISPLAY_NAME -> sanitizing(viewModel::onDisplayNameChange)(value)
                ProfileField.NAME -> sanitizing(viewModel::onNameChange)(value)
                ProfileField.ABOUT -> sanitizing(viewModel::onAboutChange)(value)
                ProfileField.WEBSITE -> sanitizing(viewModel::onWebsiteChange)(value)
                ProfileField.NIP05 -> sanitizing(viewModel::onNip05Change)(value)
                ProfileField.LUD16 -> sanitizing(viewModel::onLud16Change)(value)
                ProfileField.PICTURE -> viewModel.onPictureChange(value)
                ProfileField.BANNER -> viewModel.onBannerChange(value)
                ProfileField.LUD06 -> viewModel.onLud06Change(value)
            }
        },
        onPickBanner = {
            pickBannerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onPickPicture = {
            pickPictureLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        uploadDialog = {
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
            }
        }
    )
}

internal enum class ProfileField { DISPLAY_NAME, NAME, ABOUT, WEBSITE, NIP05, LUD16, PICTURE, BANNER, LUD06 }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditProfileContent(
    state: EditProfileState,
    onNavigateBack: () -> Unit,
    onSave: () -> Unit,
    onFieldChange: (ProfileField, String) -> Unit,
    onPickBanner: () -> Unit,
    onPickPicture: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    uploadDialog: @Composable () -> Unit = {}
) {
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    val editable = !state.isSaving

    Scaffold(
        topBar = {
            UmbraTopAppBar(
                title = { Text(stringResource(R.string.edit_profile_title)) },
                navigationIcon = {
                    UmbraTopAppBarDefaults.BackNavigationIcon(onClick = onNavigateBack)
                },
                actions = {
                    TopBarPrimaryAction(
                        label = stringResource(R.string.save),
                        onClick = onSave,
                        enabled = !state.isUploadingBanner && !state.isUploadingPicture,
                        loading = state.isSaving
                    )
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                LoadingSpinner()
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            ProfileImagesHeader(
                banner = state.banner,
                picture = state.picture,
                bannerUploading = state.isUploadingBanner,
                pictureUploading = state.isUploadingPicture,
                enabled = editable,
                onPickBanner = onPickBanner,
                onPickPicture = onPickPicture
            )

            uploadDialog()

            SettingsGroup(title = stringResource(R.string.edit_profile_section_identity)) {
                GroupTextField(
                    label = stringResource(R.string.edit_profile_display_name),
                    value = state.displayName,
                    onValueChange = { onFieldChange(ProfileField.DISPLAY_NAME, it) },
                    enabled = editable
                )
                GroupTextField(
                    label = stringResource(R.string.edit_profile_name),
                    value = state.name,
                    onValueChange = { onFieldChange(ProfileField.NAME, it) },
                    enabled = editable,
                    mono = true,
                    showDivider = false
                )
            }

            SettingsGroup(title = stringResource(R.string.edit_profile_bio)) {
                GroupTextField(
                    label = stringResource(R.string.edit_profile_bio_label),
                    value = state.about,
                    onValueChange = { onFieldChange(ProfileField.ABOUT, it) },
                    singleLine = false,
                    minLines = 4,
                    enabled = editable,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    showDivider = false
                )
            }

            SettingsGroup(title = stringResource(R.string.edit_profile_section_links)) {
                GroupTextField(
                    label = stringResource(R.string.edit_profile_website),
                    value = state.website,
                    onValueChange = { onFieldChange(ProfileField.WEBSITE, it) },
                    icon = Icons.Outlined.Link,
                    mono = true,
                    placeholder = "https://",
                    enabled = editable,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false)
                )
                GroupTextField(
                    label = stringResource(R.string.edit_profile_nip05),
                    value = state.nip05,
                    onValueChange = { onFieldChange(ProfileField.NIP05, it) },
                    icon = Icons.Outlined.Verified,
                    mono = true,
                    placeholder = "you@domain.com",
                    enabled = editable,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false)
                )
                GroupTextField(
                    label = stringResource(R.string.edit_profile_lud16),
                    value = state.lud16,
                    onValueChange = { onFieldChange(ProfileField.LUD16, it) },
                    icon = Icons.Outlined.Bolt,
                    mono = true,
                    placeholder = "you@wallet.com",
                    enabled = editable,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                    showDivider = false
                )
            }

            SettingsGroup(title = stringResource(R.string.edit_profile_other_fields)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { advancedExpanded = !advancedExpanded }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.edit_profile_advanced_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = if (advancedExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AnimatedVisibility(visible = advancedExpanded) {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        GroupTextField(
                            label = stringResource(R.string.edit_profile_picture_url),
                            value = state.picture,
                            onValueChange = { onFieldChange(ProfileField.PICTURE, it) },
                            mono = true,
                            enabled = editable && !state.isUploadingPicture
                        )
                        GroupTextField(
                            label = stringResource(R.string.edit_profile_banner_url),
                            value = state.banner,
                            onValueChange = { onFieldChange(ProfileField.BANNER, it) },
                            mono = true,
                            enabled = editable && !state.isUploadingBanner
                        )
                        GroupTextField(
                            label = stringResource(R.string.edit_profile_lud06),
                            value = state.lud06,
                            onValueChange = { onFieldChange(ProfileField.LUD06, it) },
                            mono = true,
                            enabled = editable,
                            showDivider = false
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.edit_profile_public_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Cover and avatar as they'll appear on the profile: the cover fades into the page, the avatar
 * sits on its lower edge, and each has its own camera badge.
 */
@Composable
private fun ProfileImagesHeader(
    banner: String,
    picture: String,
    bannerUploading: Boolean,
    pictureUploading: Boolean,
    enabled: Boolean,
    onPickBanner: () -> Unit,
    onPickPicture: () -> Unit
) {
    val background = MaterialTheme.colorScheme.background
    Box(modifier = Modifier.fillMaxWidth().height(196.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .padding(horizontal = 16.dp)
                .clip(MaterialTheme.shapes.large)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    )
                )
                .clickable(enabled = enabled && !bannerUploading, onClick = onPickBanner)
        ) {
            if (banner.isNotBlank()) {
                AsyncImage(
                    model = banner,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            EditIconOverlay(
                onClick = onPickBanner,
                enabled = enabled && !bannerUploading,
                isUploading = bannerUploading,
                contentDescription = stringResource(R.string.edit_profile_banner_upload_cd),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 32.dp)
                .size(92.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(background)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(enabled = enabled && !pictureUploading, onClick = onPickPicture),
                contentAlignment = Alignment.Center
            ) {
                if (picture.isNotBlank()) {
                    AsyncImage(
                        model = picture,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    EclipseMark(size = 44.dp, ignition = 0.4f)
                }
            }
            EditIconOverlay(
                onClick = onPickPicture,
                enabled = enabled && !pictureUploading,
                isUploading = pictureUploading,
                contentDescription = stringResource(R.string.edit_profile_picture_upload_cd),
                modifier = Modifier.align(Alignment.BottomEnd)
            )
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
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.background),
        modifier = modifier.size(32.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isUploading) {
                LoadingSpinner(size = 14.dp, strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface)
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
