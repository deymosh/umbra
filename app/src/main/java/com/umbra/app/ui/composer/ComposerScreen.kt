package com.umbra.app.ui.composer

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.insert
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.umbra.app.R
import com.umbra.app.ui.common.resolve
import com.umbra.app.ui.components.MENTION_URI_REGEX
import com.umbra.app.ui.components.LoadingSpinner
import com.umbra.app.ui.components.MediaUploadDialog
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import com.umbra.app.ui.components.MentionVisualTransformation
import com.umbra.app.ui.components.media.UserAvatar
import com.umbra.app.ui.components.mentionLabelFor
import com.umbra.app.ui.feed.EventCard
import com.umbra.app.util.BlurHash
import com.umbra.app.util.MediaMetadataStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

// Same cap ImageGalleryAttachment previews (a 4-up grid, +overflow badge beyond that) — picking
// more than the feed can meaningfully preview in one note isn't useful.
private const val MAX_ATTACHMENTS_PER_PICK = 4

// Decode target for the downsampled bitmap BlurHash.encode() itself further downscales to
// <=100px — sized to avoid ever fully decoding a multi-megapixel camera photo into memory just
// to compute a handful of DCT coefficients from it.
private const val BLURHASH_DECODE_TARGET_PX = 128

/**
 * Full-screen composer for both a brand-new note and a reply (mode selected by whether
 * [ComposerViewModel] was given a `replyTo` route argument) — one screen handling both cases
 * rather than two near-identical dialogs. The live preview
 * below the input is a real [EventCard] fed a synthetic in-progress event, so quotes, mentions,
 * and inline media render exactly as they would once actually posted.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ComposerScreen(
    onNavigateBack: () -> Unit,
    viewModel: ComposerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Gallery picks and keyboard-inserted images/GIFs both land here and are processed one at a
    // time — see the LaunchedEffect below — rather than building a full multi-item upload dialog:
    // each queued Uri gets the same single-item MediaUploadDialog treatment in sequence.
    var mediaQueue by remember { mutableStateOf(emptyList<Uri>()) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    LaunchedEffect(viewModel) {
        viewModel.published.collect { onNavigateBack() }
    }

    LaunchedEffect(state.attachmentError) {
        val msg = state.attachmentError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg.resolve(context))
        viewModel.clearAttachmentError()
    }

    // Pop and process one queued Uri at a time — only once there's no dialog already showing and
    // no upload already in flight, so queued picks don't race each other into overlapping state.
    LaunchedEffect(Unit) {
        while (true) {
            val next = snapshotFlow {
                if (state.pendingUpload == null && !state.isUploadingAttachment) {
                    mediaQueue.firstOrNull()
                } else null
            }.filterNotNull().first()

            mediaQueue = mediaQueue.drop(1)
            val info = withContext(Dispatchers.IO) { computePickedAttachmentInfo(next, context) }
            if (info == null) {
                viewModel.onAttachmentStripFailed()
            } else {
                viewModel.onMediaReadyForDialog(info.bytes, info.mimeType, info.previewUri, info.width, info.height, info.blurHash)
            }
        }
    }

    val pickMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS_PER_PICK)
    ) { uris ->
        if (uris.isNotEmpty()) mediaQueue = mediaQueue + uris
    }

    val mentionColor = MaterialTheme.colorScheme.primary
    val outputTransformation = remember(state.quotedAuthorProfiles) {
        OutputTransformation {
            val originalText = toString()
            val matches = MENTION_URI_REGEX.findAll(originalText).toList()
            if (matches.isEmpty()) return@OutputTransformation

            var offsetDelta = 0
            for (match in matches) {
                val start = match.range.first + offsetDelta
                val endExclusive = match.range.last + 1 + offsetDelta
                val label = mentionLabelFor(match.value, viewModel::displayNameForPubkey)

                delete(start, endExclusive)
                insert(start, label)

                offsetDelta += label.length - (match.range.last + 1 - match.range.first)
            }
        }
    }

    // Gate content pulled off the clipboard/IME to images/GIFs only — anything else (plain text,
    // contacts, files) is left for the default text-insertion behavior to handle.
    val contentReceiverListener = remember {
        object : ReceiveContentListener {
            override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
                if (!transferableContent.hasMediaType(MediaType.Image)) {
                    return transferableContent
                }
                return transferableContent.consume { item ->
                    item.uri?.let {
                        mediaQueue = mediaQueue + it
                        true
                    } ?: false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            UmbraTopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isReplyMode) R.string.event_reply else R.string.compose_note_title
                        )
                    )
                },
                navigationIcon = {
                    UmbraTopAppBarDefaults.BackNavigationIcon(
                        onClick = onNavigateBack,
                        icon = Icons.Default.Close,
                        contentDescription = stringResource(R.string.cancel)
                    )
                },
                actions = {
                    TextButton(
                        onClick = viewModel::publish,
                        enabled = viewModel.textState.text.isNotBlank() && state.canSign && !state.isPublishing
                    ) {
                        if (state.isPublishing) {
                            LoadingSpinner(size = 18.dp, strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.publish))
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .imePadding()
        ) {
            if (state.isReplyMode) {
                val target = state.replyToEvent
                if (target != null) {
                    EventCard(
                        event = target,
                        enableEventClick = false,
                        userProfile = state.replyToProfile,
                        userRepository = viewModel.userRepositoryPublic,
                        torDataSourceFactory = viewModel.mediaCacheDataSourceFactory,
                        currentUserPubkey = state.currentUserPubkey,
                        getQuotedEvent = viewModel::getQuotedEvent,
                        getQuotedEventAuthorProfile = viewModel::getQuotedEventAuthorProfile,
                        animateAvatars = false,
                        compactMedia = true
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                }
                HorizontalDivider()
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                UserAvatar(
                    userProfile = state.currentUserProfile,
                    pubkey = state.currentUserPubkey.orEmpty(),
                    size = 44.dp,
                    authorPubkey = state.currentUserPubkey,
                    userRepository = viewModel.userRepositoryPublic
                )

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val interactionSource = remember { MutableInteractionSource() }

                    BasicTextField(
                        state = viewModel.textState,
                        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 6),
                        modifier = Modifier
                            .contentReceiver(contentReceiverListener)
                            .fillMaxWidth()
                            .heightIn(min = 160.dp)
                            .focusRequester(focusRequester),
                        interactionSource = interactionSource,
                        outputTransformation = outputTransformation,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = Color.Transparent
                        ),
                        decorator = { innerTextField ->
                            val transformedText = remember(viewModel.textState.text, state.quotedAuthorProfiles) {
                                val visualTransformation = MentionVisualTransformation(mentionColor) { pubkey ->
                                    viewModel.displayNameForPubkey(pubkey)
                                }
                                visualTransformation.filter(AnnotatedString(viewModel.textState.text.toString())).text
                            }

                            OutlinedTextFieldDefaults.DecorationBox(
                                value = transformedText.text,
                                innerTextField = {
                                    Box {
                                        Text(
                                            text = transformedText,
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        )
                                        innerTextField()
                                    }
                                },
                                enabled = true,
                                singleLine = false,
                                visualTransformation = VisualTransformation.None,
                                interactionSource = interactionSource,
                                placeholder = {
                                    Text(
                                        stringResource(
                                            if (state.isReplyMode) R.string.reply_note_hint else R.string.compose_note_hint
                                        )
                                    )
                                },
                                container = {
                                    OutlinedTextFieldDefaults.Container(
                                        enabled = true,
                                        isError = false,
                                        interactionSource = interactionSource,
                                        colors = OutlinedTextFieldDefaults.colors(),
                                        shape = OutlinedTextFieldDefaults.shape
                                    )
                                }
                            )
                        }
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                pickMediaLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Image,
                                contentDescription = stringResource(R.string.composer_attach_media_cd)
                            )
                        }
                    }

                    if (state.removedTrackingToken) {
                        Text(
                            text = stringResource(R.string.tracking_token_removed_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (state.mentionSuggestions.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                                items(state.mentionSuggestions, key = { it.pubkey }, contentType = { "mention_suggestion_row" }) { profile ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.selectMention(profile) }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        UserAvatar(
                                            userProfile = profile,
                                            pubkey = profile.pubkey,
                                            size = 32.dp,
                                            authorPubkey = profile.pubkey,
                                            userRepository = viewModel.userRepositoryPublic
                                        )
                                        Text(
                                            text = profile.getUserDisplayName(),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Shown for every attachment, gallery-picked or keyboard-inserted alike, right after
            // metadata stripping succeeds and before any bytes leave the device.
            state.pendingUpload?.let { pending ->
                MediaUploadDialog(
                    previewUri = pending.previewUri,
                    mimeType = pending.mimeType,
                    availableServers = state.availableUploadServers,
                    selectedServer = pending.selectedServer,
                    onServerSelected = viewModel::onUploadServerSelected,
                    isUploading = false,
                    onConfirm = viewModel::confirmAttachmentUpload,
                    onCancel = viewModel::cancelAttachmentUpload,
                    altText = pending.altText,
                    onAltTextChange = viewModel::onAttachmentAltTextChange,
                    sensitiveContent = state.sensitiveContent,
                    onSensitiveContentChange = viewModel::onSensitiveContentChange,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (viewModel.textState.text.isNotBlank()) {
                HorizontalDivider()
                Box(modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))) {
                    EventCard(
                        event = state.draftEvent(viewModel.textState.text.toString()),
                        enableEventClick = false,
                        initiallyExpanded = true,
                        userProfile = state.currentUserProfile,
                        userRepository = viewModel.userRepositoryPublic,
                        torDataSourceFactory = viewModel.mediaCacheDataSourceFactory,
                        currentUserPubkey = state.currentUserPubkey,
                        getQuotedEvent = viewModel::getQuotedEvent,
                        getQuotedEventAuthorProfile = viewModel::getQuotedEventAuthorProfile,
                        animateAvatars = false
                    )
                }
            }
        }
    }
}

private data class PickedAttachmentInfo(
    val bytes: ByteArray,
    val mimeType: String,
    val previewUri: Uri,
    val width: Int?,
    val height: Int?,
    val blurHash: String?
)

/** Strips metadata, then decodes dimensions + a best-effort blurhash from the cleaned bytes. */
private suspend fun computePickedAttachmentInfo(uri: Uri, context: Context): PickedAttachmentInfo? =
    withContext(Dispatchers.IO) {
        val rawMimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
        val stripped = MediaMetadataStripper.strip(uri, rawMimeType, context)
        if (!stripped.stripped) return@withContext null

        val bytes = context.contentResolver.openInputStream(stripped.uri)?.use { it.readBytes() }
            ?: return@withContext null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth.takeIf { it > 0 }
        val height = bounds.outHeight.takeIf { it > 0 }

        val blurHash = runCatching {
            val sampledOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds, BLURHASH_DECODE_TARGET_PX)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sampledOptions)?.let { BlurHash.encode(it) }
        }.getOrNull()

        PickedAttachmentInfo(bytes, stripped.mimeType, stripped.uri, width, height, blurHash)
    }

private fun calculateInSampleSize(options: BitmapFactory.Options, targetPx: Int): Int {
    var inSampleSize = 1
    if (options.outHeight > targetPx || options.outWidth > targetPx) {
        val halfHeight = options.outHeight / 2
        val halfWidth = options.outWidth / 2
        while ((halfHeight / inSampleSize) >= targetPx && (halfWidth / inSampleSize) >= targetPx) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}
