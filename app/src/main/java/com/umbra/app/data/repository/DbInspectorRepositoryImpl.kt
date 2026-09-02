package com.umbra.app.data.repository

import com.umbra.app.data.db.dao.EventDao
import com.umbra.app.data.db.dao.EventTagDao
import com.umbra.app.data.db.dao.FeedFilterDao
import com.umbra.app.data.db.dao.ReactionEmojiDao
import com.umbra.app.data.db.dao.RelayDao
import com.umbra.app.data.db.dao.UserProfileDao
import com.umbra.app.data.db.entities.EventEntity
import com.umbra.app.domain.model.DbEventDetail
import com.umbra.app.domain.model.DbTableSummary
import com.umbra.app.domain.repository.DbInspectorRepository
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class DbInspectorRepositoryImpl @Inject constructor(
    @Named("encrypted") private val eventDao: EventDao,
    @Named("encrypted") private val eventTagDao: EventTagDao,
    @Named("encrypted") private val userProfileDao: UserProfileDao,
    @Named("encrypted") private val relayDao: RelayDao,
    @Named("encrypted") private val feedFilterDao: FeedFilterDao,
    @Named("encrypted") private val reactionEmojiDao: ReactionEmojiDao
) : DbInspectorRepository {

    override suspend fun getTableSummaries(): List<DbTableSummary> = withContext(Dispatchers.IO) {
        listOf(
            DbTableSummary("events", eventDao.countEvents()),
            DbTableSummary("event_tags", eventTagDao.countTags()),
            DbTableSummary("user_profiles", userProfileDao.countProfiles()),
            DbTableSummary("relays", relayDao.countRelays()),
            DbTableSummary("feed_filters", feedFilterDao.countFilters()),
            DbTableSummary("reaction_emojis", reactionEmojiDao.count())
        )
    }

    override suspend fun searchEvents(
        kind: Int?,
        pubkey: String?,
        contentQuery: String?,
        limit: Int,
        offset: Int
    ): List<DbEventDetail> = withContext(Dispatchers.IO) {
        eventDao.searchEvents(
            kind = kind,
            pubkey = pubkey?.takeIf { it.isNotBlank() },
            contentQuery = contentQuery?.takeIf { it.isNotBlank() },
            limit = limit,
            offset = offset
        ).map { it.toDbEventDetail() }
    }

    override suspend fun getEventDetail(id: String): DbEventDetail? = withContext(Dispatchers.IO) {
        eventDao.getEventById(id)?.toDbEventDetail()
    }

    private fun EventEntity.toDbEventDetail() = DbEventDetail(
        id = id,
        pubkey = pubkey,
        kind = kind,
        createdAt = createdAt,
        content = content,
        tagsJson = tagsJson,
        sig = sig
    )
}
