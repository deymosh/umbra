package com.umbra.app.domain.usecase

import com.umbra.app.domain.nip11.RelayInfo
import com.umbra.app.domain.nip86.RelayManagementData
import com.umbra.app.domain.nip86.RelayManagementEntry
import com.umbra.app.domain.nip86.RelayManagementMethod
import com.umbra.app.domain.nip86.RelayManagementResult
import com.umbra.app.domain.nip86.parseSupportedMethods
import com.umbra.app.domain.nip86.supportsNip86
import com.umbra.app.domain.repository.RelayManagementRepository

/** One NIP-86 round trip against a relay: POST <method/params>, NIP-98-authenticated. */
class CallRelayManagementUseCase(private val repository: RelayManagementRepository) {
    suspend operator fun invoke(
        relayUrl: String,
        method: RelayManagementMethod,
        params: List<String> = emptyList()
    ): RelayManagementResult = repository.call(relayUrl, method, params)
}

/**
 * Advertised-ness is decided by [supportsNip86] (NIP-11); the *live* method list comes from a
 * supportedmethods call, which this use case wraps and parses.
 */
class ProbeRelayManagementMethodsUseCase(private val repository: RelayManagementRepository) {
    suspend operator fun invoke(relayUrl: String): RelayManagementResult =
        repository.call(relayUrl, RelayManagementMethod.SUPPORTED_METHODS, emptyList())
}

/**
 * From the live supportedmethods answer, which of the enum's known methods this relay accepts.
 * Handled here rather than in the repository so the parsing is pure-Kotlin testable.
 */
class ParseSupportedRelayManagementMethodsUseCase {
    operator fun invoke(rawMethodNamesJson: String): Set<RelayManagementMethod> {
        val names = RelayManagementLists.supportedMethodNamesOf(
            RelayManagementResult.Ok(
                com.umbra.app.domain.nip86.RelayManagementData(
                    raw = rawMethodNamesJson
                )
            )
        )
        return parseSupportedMethods(names)
    }
}

/** Convenience wrappers the UI uses for the two list shapes NIP-86 returns. */
object RelayManagementLists {
    fun entriesOf(result: RelayManagementResult): List<RelayManagementEntry> =
        (result as? RelayManagementResult.Ok)?.data?.entries ?: emptyList()

    fun kindNumbersOf(result: RelayManagementResult): List<Int> =
        (result as? RelayManagementResult.Ok)?.data?.kindNumbers ?: emptyList()

    fun supportedMethodNamesOf(result: RelayManagementResult): List<String> =
        (result as? RelayManagementResult.Ok)?.data?.raw
            ?.removePrefix("[")?.removeSuffix("]")
            ?.split(',')?.mapNotNull { name ->
                name.trim().removeSurrounding("\"").takeIf { it.isNotBlank() }
            }
            ?: emptyList()
}
