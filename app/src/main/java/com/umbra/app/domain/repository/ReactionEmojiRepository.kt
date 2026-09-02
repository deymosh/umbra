package com.umbra.app.domain.repository

import com.umbra.app.domain.nip25.ReactionEmoji
import kotlinx.coroutines.flow.Flow

/**
 * User-owned reaction picker list — both the shipped Unicode defaults and any image-backed
 * custom emoji the user adds are just normal, editable entries here (see ReactionEmoji), the
 * same "default the user can see and turn off" shape as FeedFilter.
 */
interface ReactionEmojiRepository {
    fun observeAll(): Flow<List<ReactionEmoji>>
    suspend fun add(emoji: ReactionEmoji)
    suspend fun remove(key: String)
}
