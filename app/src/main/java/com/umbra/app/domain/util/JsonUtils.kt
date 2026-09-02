package com.umbra.app.domain.util

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * Shared JSON serialization utilities.
 * Singleton instances to avoid repeated instantiation.
 */
object JsonUtils {
    val CompactJson = Json {
        prettyPrint = false
        encodeDefaults = true
    }

    @OptIn(ExperimentalSerializationApi::class)
    val PrettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    @OptIn(ExperimentalSerializationApi::class)
    val PrettyJsonTwoSpace = Json {
        prettyPrint = true
        encodeDefaults = true
        prettyPrintIndent = "  "
    }

    val NostrJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}
