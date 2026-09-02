package com.umbra.app.data.repository

import com.umbra.app.data.db.dao.ReactionEmojiDao
import com.umbra.app.data.db.mapper.toDomain
import com.umbra.app.data.db.mapper.toEntity
import com.umbra.app.domain.nip25.DEFAULT_REACTION_EMOJIS
import com.umbra.app.domain.nip25.ReactionEmoji
import com.umbra.app.domain.repository.ReactionEmojiRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class ReactionEmojiRepositoryImpl @Inject constructor(
    @Named("encrypted") private val reactionEmojiDao: ReactionEmojiDao
) : ReactionEmojiRepository {
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        repoScope.launch { ensureDefaultsSeeded() }
    }

    private suspend fun ensureDefaultsSeeded() {
        if (reactionEmojiDao.count() > 0) return
        reactionEmojiDao.insertAll(
            DEFAULT_REACTION_EMOJIS.mapIndexed { index, emoji -> emoji.toEntity(sortOrder = index) }
        )
    }

    override fun observeAll(): Flow<List<ReactionEmoji>> =
        reactionEmojiDao.observeAll()
            .map { entities -> entities.mapNotNull { it.toDomain() } }
            .flowOn(Dispatchers.IO)

    override suspend fun add(emoji: ReactionEmoji) = withContext(Dispatchers.IO) {
        val nextSortOrder = reactionEmojiDao.count()
        reactionEmojiDao.insert(emoji.toEntity(sortOrder = nextSortOrder))
    }

    override suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        reactionEmojiDao.deleteByKey(key)
    }
}
