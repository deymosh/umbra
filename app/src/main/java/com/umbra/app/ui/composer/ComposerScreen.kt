package com.umbra.app.ui.composer

import com.umbra.app.domain.nip30.CustomEmoji
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.insert
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.datasource.DataSource
import com.umbra.app.R
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.ui.common.resolve
import com.umbra.app.ui.components.LoadingSpinner
import com.umbra.app.ui.components.LocalCustomEmojiGroups
import com.umbra.app.domain.nip30.allEmojis
import com.umbra.app.ui.components.MediaUploadDialog
import com.umbra.app.ui.components.NoteAuthorLine
import com.umbra.app.ui.components.TopBarPrimaryAction
import com.umbra.app.ui.components.UmbraIcons
import com.umbra.app.ui.components.UmbraTopAppBar
import com.umbra.app.ui.components.UmbraTopAppBarDefaults
import com.umbra.app.ui.components.media.UserAvatar
import com.umbra.app.ui.components.mentionLabelFor
import com.umbra.app.ui.components.truncatePublicKey
import com.umbra.app.ui.feed.EventCard
import com.umbra.app.ui.theme.MonoStyle
import com.umbra.app.ui.theme.UmbraTheme
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
 * [ComposerViewModel] was given a `replyTo` route argument). The optional live preview is a real
 * [EventCard] fed a synthetic in-progress event, so quotes, mentions, and inline media render
 * exactly as they would once actually posted.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComposerScreen(
    onNavigateBack: () -> Unit,
    onManageEmojiPacks: () -> Unit,
    viewModel: ComposerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Gallery picks and keyboard-inserted images/GIFs both land here and are processed one at a
    // time — see the LaunchedEffect below — rather than building a full multi-item upload dialog:
    // each queued Uri gets the same single-item MediaUploadDialog treatment in sequence.
    var mediaQueue by remember { mutableStateOf(emptyList<Uri>()) }
    var showEmojiSheet by remember { mutableStateOf(false) }

    // The single app-wide catalog collector lives above this screen (CustomEmojiCatalog); hand
    // its content to the ViewModel so publishing can tag the emoji actually used.
    val customEmojiGroups = LocalCustomEmojiGroups.current
    LaunchedEffect(customEmojiGroups) {
        viewModel.setCustomEmojis(customEmojiGroups.allEmojis())
    }

    if (showEmojiSheet) {
        ComposerEmojiSheet(
            onInsert = viewModel::insertCustomEmoji,
            onManagePacks = {
                showEmojiSheet = false
                onManageEmojiPacks()
            },
            onDismissRequest = { showEmojiSheet = false }
        )
    }

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

    ComposerLayout(
        state = state,
        textState = viewModel.textState,
        userRepository = viewModel.userRepositoryPublic,
        dataSourceFactory = viewModel.mediaCacheDataSourceFactory,
        displayNameForPubkey = viewModel::displayNameForPubkey,
        getQuotedEvent = viewModel::getQuotedEvent,
        getQuotedEventAuthorProfile = viewModel::getQuotedEventAuthorProfile,
        onClose = onNavigateBack,
        onPublish = viewModel::publish,
        onPickMedia = {
            pickMediaLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
            )
        },
        onEmoji = { showEmojiSheet = true },
        onSelectMention = viewModel::selectMention,
        onSelectEmoji = viewModel::selectEmoji,
        onSensitiveChange = viewModel::onSensitiveContentChange,
        snackbarHostState = snackbarHostState,
        editorModifier = Modifier
            .contentReceiver(contentReceiverListener)
            .focusRequester(focusRequester),
        uploadDialog = {
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
        }
    )
}

/**
 * Stateless composer layout: reply context threaded into the writing surface, a bare editor,
 * notices and mention suggestions, an optional live preview, and a toolbar docked above the
 * keyboard. Side effects (pickers, clipboard, publish navigation) stay in [ComposerScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerLayout(
    state: ComposerState,
    textState: TextFieldState,
    userRepository: UserRepository,
    dataSourceFactory: DataSource.Factory,
    displayNameForPubkey: (String) -> String?,
    getQuotedEvent: (String) -> Event?,
    getQuotedEventAuthorProfile: (String) -> UserProfile?,
    onClose: () -> Unit,
    onPublish: () -> Unit,
    onPickMedia: () -> Unit,
    onSelectMention: (UserProfile) -> Unit,
    onSensitiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onEmoji: () -> Unit = {},
    onSelectEmoji: (CustomEmoji) -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    editorModifier: Modifier = Modifier,
    initialShowPreview: Boolean = false,
    uploadDialog: @Composable () -> Unit = {}
) {
    var showPreview by rememberSaveable { mutableStateOf(initialShowPreview) }
    val hasText = textState.text.isNotBlank()

    Scaffold(
        modifier = modifier,
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
                        onClick = onClose,
                        icon = Icons.Default.Close,
                        contentDescription = stringResource(R.string.cancel)
                    )
                },
                actions = {
                    TopBarPrimaryAction(
                        label = stringResource(R.string.publish),
                        onClick = onPublish,
                        enabled = hasText && state.canSign && !state.isUploadingAttachment,
                        loading = state.isPublishing
                    )
                }
            )
        },
        bottomBar = {
            ComposerToolbar(
                characterCount = textState.text.length,
                sensitive = state.sensitiveContent,
                showPreview = showPreview,
                uploading = state.isUploadingAttachment,
                onPickMedia = onPickMedia,
                onEmoji = onEmoji,
                onSensitiveChange = onSensitiveChange,
                onTogglePreview = { showPreview = !showPreview },
                modifier = Modifier.imePadding()
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            if (state.isReplyMode) {
                val target = state.replyToEvent
                if (target != null) {
                    ReplyContext(
                        event = target,
                        profile = state.replyToProfile,
                        userRepository = userRepository
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingSpinner(size = 20.dp)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = if (state.isReplyMode) 0.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                UserAvatar(
                    userProfile = state.currentUserProfile,
                    pubkey = state.currentUserPubkey.orEmpty(),
                    size = AVATAR_SIZE,
                    animate = false,
                    authorPubkey = state.currentUserPubkey,
                    userRepository = userRepository
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ComposerEditor(
                        textState = textState,
                        isReplyMode = state.isReplyMode,
                        quotedAuthorProfiles = state.quotedAuthorProfiles,
                        displayNameForPubkey = displayNameForPubkey,
                        customEmojis = state.customEmojis,
                        modifier = editorModifier
                    )

                    if (state.removedTrackingToken) {
                        ComposerNotice(
                            icon = Icons.Outlined.Shield,
                            text = stringResource(R.string.tracking_token_removed_notice),
                            color = UmbraTheme.colors.secure
                        )
                    }
                    if (state.sensitiveContent) {
                        ComposerNotice(
                            icon = Icons.Outlined.VisibilityOff,
                            text = stringResource(R.string.media_upload_dialog_sensitive_description),
                            color = UmbraTheme.colors.caution
                        )
                    }

                    if (state.mentionSuggestions.isNotEmpty()) {
                        MentionSuggestions(
                            suggestions = state.mentionSuggestions,
                            userRepository = userRepository,
                            onSelect = onSelectMention
                        )
                    }
                    if (state.emojiSuggestions.isNotEmpty()) {
                        EmojiSuggestions(suggestions = state.emojiSuggestions, onSelect = onSelectEmoji)
                    }
                }
            }

            uploadDialog()

            if (showPreview && hasText) {
                Text(
                    text = stringResource(R.string.composer_preview_label).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp)
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .clip(MaterialTheme.shapes.large)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                ) {
                    EventCard(
                        event = state.draftEvent(textState.text.toString()),
                        enableEventClick = false,
                        initiallyExpanded = true,
                        userProfile = state.currentUserProfile,
                        userRepository = userRepository,
                        torDataSourceFactory = dataSourceFactory,
                        currentUserPubkey = state.currentUserPubkey,
                        getQuotedEvent = getQuotedEvent,
                        getQuotedEventAuthorProfile = getQuotedEventAuthorProfile,
                        animateAvatars = false
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private val AVATAR_SIZE = 40.dp

/**
 * The note being replied to, as a compact quote: author line, a few lines of its text, and a
 * thread rule running down from its avatar into the writer's own avatar below.
 */
@Composable
private fun ReplyContext(
    event: Event,
    profile: UserProfile?,
    userRepository: UserRepository
) {
    val threadColor = MaterialTheme.colorScheme.outline
    val name = profile?.getUserDisplayName() ?: event.pubkey.truncatePublicKey()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.width(AVATAR_SIZE).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            UserAvatar(
                userProfile = profile,
                pubkey = event.pubkey,
                size = 32.dp,
                animate = false,
                authorPubkey = event.pubkey,
                userRepository = userRepository
            )
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .width(2.dp)
                    .weight(1f)
                    .clip(RoundedCornerShape(1.dp))
                    .drawBehind { drawRect(threadColor) }
            )
        }
        Column(
            modifier = Modifier.weight(1f).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            NoteAuthorLine(userProfile = profile, pubkey = event.pubkey, createdAt = event.createdAt)
            Text(
                text = event.content.trim(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildAnnotatedString {
                    append(stringResource(R.string.composer_replying_to_prefix))
                    append(" ")
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("@$name") }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/**
 * Bare writing surface: no box around the text, the page is the field. The raw text keeps
 * `nostr:` mention URIs and `:shortcode:` emoji; a transparent field sits over the same text
 * drawn with mentions as highlighted "@name" labels and each known custom emoji as its image.
 * Both layers come from one [composerTokens] pass, so the caret always lines up with what's drawn
 * and a token is stepped over, and deleted, as one unit.
 */
@Composable
private fun ComposerEditor(
    textState: TextFieldState,
    isReplyMode: Boolean,
    quotedAuthorProfiles: Map<String, UserProfile>,
    displayNameForPubkey: (String) -> String?,
    customEmojis: List<CustomEmoji>,
    modifier: Modifier = Modifier
) {
    val mentionColor = MaterialTheme.colorScheme.primary
    val interactionSource = remember { MutableInteractionSource() }
    val textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp)
    val emojiByShortcode = remember(customEmojis) { customEmojis.associateBy { it.shortcode } }
    val mentionLabel: (String) -> String = { uri -> mentionLabelFor(uri, displayNameForPubkey) }

    val outputTransformation = remember(quotedAuthorProfiles, emojiByShortcode) {
        OutputTransformation {
            val tokens = composerTokens(toString(), mentionLabel, emojiByShortcode)
            var offsetDelta = 0
            tokens.forEach { token ->
                replace(token.start + offsetDelta, token.endExclusive + offsetDelta, token.display)
                offsetDelta += token.display.length - (token.endExclusive - token.start)
            }
        }
    }

    BasicTextField(
        state = textState,
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp),
        interactionSource = interactionSource,
        outputTransformation = outputTransformation,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        textStyle = textStyle.copy(color = Color.Transparent),
        decorator = { innerTextField ->
            Box(modifier = Modifier.padding(top = 8.dp)) {
                if (textState.text.isEmpty()) {
                    Text(
                        text = stringResource(
                            if (isReplyMode) R.string.reply_note_hint else R.string.compose_note_hint
                        ),
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                ComposerOverlayText(
                    text = textState.text.toString(),
                    tokens = composerTokens(textState.text.toString(), mentionLabel, emojiByShortcode),
                    mentionColor = mentionColor,
                    style = textStyle.copy(color = MaterialTheme.colorScheme.onSurface)
                )
                innerTextField()
            }
        }
    )
}

/**
 * The visible layer under the transparent field: the same display text, mentions coloured, and
 * each emoji image drawn over the placeholder character the field lays out in its place.
 */
@Composable
private fun ComposerOverlayText(
    text: String,
    tokens: List<ComposerToken>,
    mentionColor: Color,
    style: TextStyle
) {
    val annotated = remember(text, tokens, mentionColor) {
        buildAnnotatedString {
            var cursor = 0
            tokens.forEach { token ->
                append(text.substring(cursor, token.start))
                when (token) {
                    is ComposerToken.Mention -> withStyle(SpanStyle(color = mentionColor)) { append(token.display) }
                    is ComposerToken.Emoji -> append(token.display)
                }
                cursor = token.endExclusive
            }
            append(text.substring(cursor))
        }
    }
    // Where each emoji's placeholder sits in the display text.
    val emojiOffsets = remember(tokens) {
        var displayLength = 0
        var rawCursor = 0
        buildList {
            tokens.forEach { token ->
                displayLength += token.start - rawCursor
                if (token is ComposerToken.Emoji) add(displayLength to token.emoji)
                displayLength += token.display.length
                rawCursor = token.endExclusive
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val placeholderColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Box {
        Text(text = annotated, style = style, onTextLayout = { layout = it })
        val currentLayout = layout
        if (currentLayout != null && currentLayout.layoutInput.text == annotated) {
            emojiOffsets.forEach { (offset, emoji) ->
                val box = currentLayout.getBoundingBox(offset)
                val sizePx = minOf(box.width, box.height)
                AsyncImage(
                    model = emoji.url,
                    contentDescription = emoji.shortcode,
                    placeholder = ColorPainter(placeholderColor),
                    error = ColorPainter(placeholderColor),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .offset { IntOffset(box.left.toInt(), (box.top + (box.height - sizePx) / 2).toInt()) }
                        .size(with(density) { sizePx.toDp() })
                        .clip(RoundedCornerShape(20))
                )
            }
        }
    }
}

@Composable
private fun ComposerNotice(icon: ImageVector, text: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** NIP-30: the user's custom emoji matching the `:query` being typed, as scrollable chips. */
@Composable
private fun EmojiSuggestions(suggestions: List<CustomEmoji>, onSelect: (CustomEmoji) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        LazyRow(
            modifier = Modifier.padding(vertical = 6.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(suggestions, key = { it.shortcode }) { emoji ->
                Row(
                    modifier = Modifier
                        .height(40.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onSelect(emoji) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AsyncImage(
                        model = emoji.url,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = ":${emoji.shortcode}:",
                        style = MonoStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun MentionSuggestions(
    suggestions: List<UserProfile>,
    userRepository: UserRepository,
    onSelect: (UserProfile) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        // A plain Column rather than a LazyColumn: this sits inside the screen's vertical scroll,
        // and the list is capped at a handful of rows anyway.
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            suggestions.forEach { profile ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(profile) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    UserAvatar(
                        userProfile = profile,
                        pubkey = profile.pubkey,
                        size = 32.dp,
                        animate = false,
                        authorPubkey = profile.pubkey,
                        userRepository = userRepository
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile.getUserDisplayName(),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val handle = profile.nip05?.takeIf { it.isNotBlank() }
                            ?: profile.pubkey.truncatePublicKey(8, 4)
                        Text(
                            text = handle,
                            style = MonoStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Docked above the keyboard: attach media, content-warning toggle, preview toggle, and a quiet
 * trailing readout (upload progress, or the character count plus the Tor route).
 */
@Composable
private fun ComposerToolbar(
    characterCount: Int,
    sensitive: Boolean,
    showPreview: Boolean,
    uploading: Boolean,
    onPickMedia: () -> Unit,
    onEmoji: () -> Unit,
    onSensitiveChange: (Boolean) -> Unit,
    onTogglePreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background
    ) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPickMedia, enabled = !uploading) {
                    Icon(
                        Icons.Outlined.Image,
                        contentDescription = stringResource(R.string.composer_attach_media_cd),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onEmoji, enabled = !uploading) {
                    Icon(
                        Icons.Outlined.EmojiEmotions,
                        contentDescription = stringResource(R.string.composer_add_emoji_cd),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                ToolbarToggle(
                    checked = sensitive,
                    onCheckedChange = onSensitiveChange,
                    icon = Icons.Outlined.VisibilityOff,
                    contentDescription = stringResource(R.string.media_upload_dialog_sensitive_label),
                    activeColor = UmbraTheme.colors.caution
                )
                ToolbarToggle(
                    checked = showPreview,
                    onCheckedChange = { onTogglePreview() },
                    icon = Icons.Outlined.Visibility,
                    contentDescription = stringResource(R.string.composer_preview_label),
                    activeColor = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                if (uploading) {
                    LoadingSpinner(size = 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.composer_uploading),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    if (characterCount > 0) {
                        Text(
                            text = characterCount.toString(),
                            style = MonoStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "  ·  ",
                            style = MonoStyle,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Icon(
                        imageVector = UmbraIcons.Onion,
                        contentDescription = null,
                        tint = UmbraTheme.colors.secure,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.composer_route_tor),
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

@Composable
private fun ToolbarToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector,
    contentDescription: String,
    activeColor: Color
) {
    IconToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = IconButtonDefaults.iconToggleButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            checkedContentColor = activeColor,
            checkedContainerColor = activeColor.copy(alpha = 0.14f)
        )
    ) {
        Icon(icon, contentDescription = contentDescription)
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
