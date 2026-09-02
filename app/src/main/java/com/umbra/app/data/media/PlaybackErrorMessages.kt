package com.umbra.app.data.media

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.umbra.app.R

internal fun resolvePlaybackHttpResponseCode(error: PlaybackException): Int? {
    return when (val cause = error.cause) {
        is HttpDataSource.InvalidResponseCodeException -> cause.responseCode
        is PlaybackException -> {
            val nestedCause = cause.cause
            if (nestedCause is HttpDataSource.InvalidResponseCodeException) nestedCause.responseCode else null
        }
        else -> null
    }
}

internal fun playbackErrorMessageResId(httpCode: Int?): Int {
    return when (httpCode) {
        404 -> R.string.video_error_not_found
        502, 503, 504 -> R.string.video_error_temporary_unavailable
        else -> R.string.video_error_generic
    }
}

internal fun describeVideoPlaybackError(context: Context, error: PlaybackException): String {
    val code = resolvePlaybackHttpResponseCode(error)
    return context.getString(playbackErrorMessageResId(code))
}
