package com.umbra.app.ui.components

import android.content.Context
import android.content.Intent
import com.umbra.app.R

internal fun shareEventUrl(context: Context, url: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_TEXT, url)
        type = "text/plain"
    }
    context.startActivity(
        Intent.createChooser(sendIntent, context.getString(R.string.share_event_url_chooser))
    )
}
