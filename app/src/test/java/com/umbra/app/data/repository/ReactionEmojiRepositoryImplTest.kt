package com.umbra.app.data.repository

import com.umbra.app.data.db.dao.ReactionEmojiDao
import com.umbra.app.data.db.entities.ReactionEmojiEntity
import com.umbra.app.domain.nip25.DEFAULT_REACTION_EMOJIS
import com.umbra.app.domain.nip25.ReactionEmoji
import com.umbra.app.domain.nip30.CustomEmoji
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionEmojiRepositoryImplTest {

    private class FakeReactionEmojiDao : ReactionEmojiDao {
        val entities = MutableStateFlow<List<ReactionEmojiEntity>>(emptyList())

        override fun observeAll(): Flow<List<ReactionEmojiEntity>> = entities

        override suspend fun count(): Int = entities.value.size

        override suspend fun insert(entity: ReactionEmojiEntity) {
            entities.value = (entities.value.filterNot { it.key == entity.key } + entity)
                .sortedBy { it.sortOrder }
        }

        override suspend fun insertAll(entities: List<ReactionEmojiEntity>) {
            entities.forEach { insert(it) }
        }

        override suspend fun deleteByKey(key: String) {
            entities.value = entities.value.filterNot { it.key == key }
        }
    }

    @Test
    fun `given an empty table when repository is created then seeds the default reaction set`() = runBlocking {
        val dao = FakeReactionEmojiDao()
        val repository = ReactionEmojiRepositoryImpl(dao)

        val result = waitForSeeded(repository)
        assertEquals(DEFAULT_REACTION_EMOJIS.map { it.key }, result.map { it.key })
    }

    @Test
    fun `given a non-empty table when repository is created then does not reseed`() = runBlocking {
        val dao = FakeReactionEmojiDao()
        dao.insert(ReactionEmojiEntity(key = "existing", unicodeEmoji = "existing", sortOrder = 0))
        val repository = ReactionEmojiRepositoryImpl(dao)

        val result = repository.observeAll().first()
        assertEquals(listOf("existing"), result.map { it.key })
    }

    @Test
    fun `given a custom emoji added when observing then it appears alongside seeded defaults`() = runBlocking {
        val dao = FakeReactionEmojiDao()
        val repository = ReactionEmojiRepositoryImpl(dao)
        waitForSeeded(repository)

        repository.add(ReactionEmoji.Custom(CustomEmoji(shortcode = "umbra", url = "https://example.com/umbra.png")))

        val result = repository.observeAll().first()
        assertTrue(result.any { it.key == "umbra" })
        assertEquals(DEFAULT_REACTION_EMOJIS.size + 1, result.size)
    }

    @Test
    fun `given a seeded default when removing it then it no longer appears`() = runBlocking {
        val dao = FakeReactionEmojiDao()
        val repository = ReactionEmojiRepositoryImpl(dao)
        val seeded = waitForSeeded(repository)
        val toRemove = seeded.first().key

        repository.remove(toRemove)

        val result = repository.observeAll().first()
        assertTrue(result.none { it.key == toRemove })
    }

    private suspend fun waitForSeeded(repository: ReactionEmojiRepositoryImpl): List<ReactionEmoji> =
        withTimeout(2_000) {
            repository.observeAll().first { it.size >= DEFAULT_REACTION_EMOJIS.size }
        }
}
