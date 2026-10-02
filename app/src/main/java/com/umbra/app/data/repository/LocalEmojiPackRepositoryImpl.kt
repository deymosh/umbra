package com.umbra.app.data.repository

import com.umbra.app.data.db.dao.LocalEmojiPackDao
import com.umbra.app.data.db.entities.LocalEmojiPackEntity
import com.umbra.app.domain.nip30.EmojiSetAddress
import com.umbra.app.domain.nip30.coordinate
import com.umbra.app.domain.nip30.parseEmojiSetCoordinate
import com.umbra.app.domain.repository.LocalEmojiPackRepository
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class LocalEmojiPackRepositoryImpl @Inject constructor(
    @Named("encrypted") private val dao: LocalEmojiPackDao
) : LocalEmojiPackRepository {

    override fun observeLocalPacks(ownerPubkey: String): Flow<List<EmojiSetAddress>> =
        dao.observeForAccount(ownerPubkey.lowercase())
            .map { rows -> rows.mapNotNull { parseEmojiSetCoordinate(it.coordinate) } }
            .distinctUntilChanged()

    override suspend fun addLocalPack(ownerPubkey: String, address: EmojiSetAddress) = withContext(Dispatchers.IO) {
        dao.insert(LocalEmojiPackEntity(ownerPubkey.lowercase(), address.coordinate(), System.currentTimeMillis()))
    }

    override suspend fun removeLocalPack(ownerPubkey: String, address: EmojiSetAddress) = withContext(Dispatchers.IO) {
        dao.delete(ownerPubkey.lowercase(), address.coordinate())
    }
}
