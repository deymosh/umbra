package com.umbra.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.umbra.app.TorProxyConfig
import com.umbra.app.R
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Named

private val SAFE_FILE_NAME_REGEX = Regex("[^A-Za-z0-9._-]")

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface NostrMediaActionsEntryPoint {
    @Named("tor")
    fun torOkHttpClient(): OkHttpClient
}

internal suspend fun enqueueImageDownload(context: Context, imageUrl: String): Boolean =
    downloadMediaViaTor(
        context = context,
        mediaUrl = imageUrl,
        defaultBase = "umbra_image",
        defaultExtension = "png",
        mediaDirectory = android.os.Environment.DIRECTORY_PICTURES,
        mediaSubdirectory = "Umbra/Images",
        fallbackMimeType = "image/*"
    )

internal suspend fun enqueueVideoDownload(context: Context, videoUrl: String): Boolean =
    downloadMediaViaTor(
        context = context,
        mediaUrl = videoUrl,
        defaultBase = "umbra_video",
        defaultExtension = "mp4",
        mediaDirectory = android.os.Environment.DIRECTORY_MOVIES,
        mediaSubdirectory = "Umbra/Videos",
        fallbackMimeType = "video/*"
    )

private suspend fun downloadMediaViaTor(
    context: Context,
    mediaUrl: String,
    defaultBase: String,
    defaultExtension: String,
    mediaDirectory: String,
    mediaSubdirectory: String,
    fallbackMimeType: String
): Boolean = withContext(Dispatchers.IO) {
    if (!TorProxyConfig.isReady) {
        return@withContext false
    }

    runCatching {
        val outputRoot = context.getExternalFilesDir(mediaDirectory) ?: context.filesDir
        val outputDir = File(outputRoot, mediaSubdirectory).apply { mkdirs() }
        val outputFile = File(
            outputDir,
            guessFileName(
                url = mediaUrl,
                defaultBase = defaultBase,
                defaultExtension = defaultExtension
            )
        )
        // The shared "tor" client has readTimeout=0 (unbounded), correct for long-lived relay
        // websockets but not for this one-shot download — without a bound, a stalled connection
        // (e.g. right after the app resumes from background) would hang forever with no error
        // and no user feedback. A generous callTimeout since media downloads can legitimately
        // take a while over Tor, but must eventually fail rather than hang indefinitely. Same
        // bounded-call pattern already used by RelayInfoRepositoryImpl/Nip05RepositoryImpl/
        // TorStatusRepositoryImpl/UrlPrefetcher for their own one-shot HTTP fetches.
        val client = EntryPointAccessors.fromApplication(
            context.applicationContext,
            NostrMediaActionsEntryPoint::class.java
        ).torOkHttpClient().newBuilder()
            .callTimeout(120, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(mediaUrl)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return@use false
            }

            val body = response.body
            body.byteStream().use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }

            val mimeType = body.contentType()?.toString()?.substringBefore(';') ?: fallbackMimeType
            MediaScannerConnection.scanFile(
                context,
                arrayOf(outputFile.absolutePath),
                arrayOf(mimeType),
                null
            )
            true
        }
    }.getOrDefault(false)
}

internal suspend fun materializeImageContentUri(context: Context, imageUrl: String): Uri? {
    return withContext(Dispatchers.IO) {
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
                .allowHardware(false)
                .build()
            val result = context.imageLoader.execute(request)
            val drawable = result.image?.asDrawable(context.resources) ?: return@withContext null
            val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: drawable.toBitmap()

            val dir = File(context.cacheDir, "shared_images").apply { mkdirs() }
            val outputFile = File(
                dir,
                guessFileName(
                    url = imageUrl,
                    defaultBase = "shared_image",
                    defaultExtension = "png"
                )
            )
            FileOutputStream(outputFile).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }

            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outputFile)
        }.getOrNull()
    }
}

internal fun copyImageToClipboard(context: Context, imageUri: Uri) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newUri(context.contentResolver, "Umbra image", imageUri)
    clipboard.setPrimaryClip(clip)
}

internal fun shareImage(context: Context, imageUri: Uri) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "image/*"
        putExtra(Intent.EXTRA_STREAM, imageUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.image_share_action)))
}

internal fun shareVideoUrl(context: Context, videoUrl: String) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, videoUrl)
    }
    context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.video_share_action)))
}

private fun guessFileName(
    url: String,
    defaultBase: String,
    defaultExtension: String
): String {
    val rawName = url
        .substringAfterLast('/')
        .substringBefore('?')
        .substringBefore('#')
        .ifBlank { defaultBase }

    val sanitizedBase = rawName
        .replace(SAFE_FILE_NAME_REGEX, "_")
        .trim('.')
        .ifBlank { defaultBase }
        .take(80)

    return if (sanitizedBase.contains('.')) {
        sanitizedBase
    } else {
        "$sanitizedBase.$defaultExtension"
    }
}
