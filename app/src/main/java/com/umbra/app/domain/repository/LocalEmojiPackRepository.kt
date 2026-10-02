package com.umbra.app.domain.repository

import com.umbra.app.domain.nip30.EmojiSetAddress
import kotlinx.coroutines.flow.Flow

/**
 * Emoji packs (kind-30030 sets) a user keeps only on this device. Unlike the packs in their
 * published kind-10030 list, these are never signed or sent anywhere — relays can't see which
 * packs the user picked — but they feed the composer's emoji catalog the same way. Kept per
 * account so one account's packs never show up under another.
 */
interface LocalEmojiPackRepository {
    fun observeLocalPacks(ownerPubkey: String): Flow<List<EmojiSetAddress>>
    suspend fun addLocalPack(ownerPubkey: String, address: EmojiSetAddress)
    suspend fun removeLocalPack(ownerPubkey: String, address: EmojiSetAddress)
}
