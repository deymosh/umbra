package com.umbra.app.ui.common

import android.content.Context
import androidx.annotation.StringRes

sealed class UiMessage {
    data class Res(
        @StringRes val id: Int,
        val args: List<Any> = emptyList()
    ) : UiMessage()

    class ResWithArgs(
        @StringRes val id: Int,
        vararg formatArgs: Any
    ) : UiMessage() {
        val args: Array<out Any> = formatArgs
    }

    data class Literal(val text: String) : UiMessage()
}

fun UiMessage.resolve(context: Context): String = when (this) {
    is UiMessage.Res -> if (args.isEmpty()) {
        context.getString(id)
    } else {
        context.getString(id, *args.toTypedArray())
    }
    is UiMessage.ResWithArgs -> context.getString(id, *args)
    is UiMessage.Literal -> text
}