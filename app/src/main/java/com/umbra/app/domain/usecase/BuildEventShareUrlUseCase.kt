package com.umbra.app.domain.usecase

import com.umbra.app.domain.nip19.Bech32Encoder

/**
 * Builds the njump.me share URL for an event — the single share action Umbra exposes (a link,
 * opened directly in the platform's native share sheet).
 */
class BuildEventShareUrlUseCase {
    operator fun invoke(eventId: String): String =
        "https://njump.me/${Bech32Encoder.encodeNevent(eventId)}"
}
