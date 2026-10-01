package com.umbra.app.data.repository

import com.umbra.app.data.db.dao.UserProfileDao
import com.umbra.app.data.db.entities.UserProfileEntity
import com.umbra.app.domain.nip17.DmRelayList
import com.umbra.app.domain.nip51.IndexRelaysList
import com.umbra.app.domain.nip51.SearchRelaysList
import com.umbra.app.domain.nipb7.UserServerList
import com.umbra.app.domain.relay.Relay
import com.umbra.app.domain.repository.Nip05Repository
import com.umbra.app.domain.repository.RelayRepository
import com.umbra.app.domain.nip05.Nip05VerificationState
import com.umbra.app.testutil.fakes.FakeUserPreferences
import com.umbra.app.util.AvatarPrefetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Newer-wins regression tests for the relay-list save paths: with concurrent delivery (e.g.
 * EventRepositoryImpl's flatMapMerge branches racing the decryption coordinator), an older list
 * must never overwrite a newer one, no matter which caller lands last. The staleness check and
 * the map/StateFlow write must be one atomic step per key.
 *
 * Instantiates the real [UserRepositoryImpl] against in-memory fakes — the race window lives
 * inside the four save methods, so exercising the facade (not a reimplementation) is the point.
 * The saves here target a pubkey no one is signed in as, so `isCurrentUser` is always false and
 * the side-effect coroutines (applyDmRelayListToLocalConfig etc.) — the only paths that touch the
 * DB or relay config — are never reached; the throwing DAO/relay fakes guarantee that loudly if
 * it ever changes.
 *
 * [ImagePrefetcher]'s real constructor takes a Coil [coil3.ImageLoader] and an Android
 * [android.content.Context], neither obtainable on the plain JVM test classpath. Both are only
 * consulted lazily (inside `prefetchAsync`/its lazy size properties), and nothing in these tests
 * saves a profile, so passing nulls via reflection is safe — [MediaLoadPriorityGate], which the
 * init block DOES touch eagerly, is a plain-JVM class.
 */
class UserRepositorySaveRaceTest {

    private val ownerKey = "a".repeat(64)

    private class ThrowingUserProfileDao : UserProfileDao {
        override suspend fun getProfile(pubkey: String): UserProfileEntity? = null
        override fun observeProfile(pubkey: String): Flow<UserProfileEntity?> = flowOf(null)
        override suspend fun getProfiles(pubkeys: List<String>): List<UserProfileEntity> =
            emptyList()
        override suspend fun countProfiles(): Int = 0
        override suspend fun insertProfile(profile: UserProfileEntity) {
            throw IllegalStateException("test scenario must not reach the DB")
        }
        override suspend fun insertProfiles(profiles: List<UserProfileEntity>) {
            throw IllegalStateException("test scenario must not reach the DB")
        }
        override suspend fun deleteAll() {}
        override suspend fun deleteStaleProfiles(threshold: Long, excludePubkey: String?): Int = 0
        override suspend fun isFresh(pubkey: String, freshThreshold: Long): Int = 0
        override suspend fun searchProfilesByName(query: String, limit: Int): List<UserProfileEntity> =
            emptyList()
    }

    private class ThrowingRelayRepository : RelayRepository {
        override fun getAllRelays(): Flow<List<Relay>> = flowOf(emptyList())
        override suspend fun getRelayById(id: String): Relay? = null
        override suspend fun addRelay(relay: Relay) {
            throw IllegalStateException("test scenario must not reach relay config")
        }
        override suspend fun updateRelay(relay: Relay) {
            throw IllegalStateException("test scenario must not reach relay config")
        }
        override suspend fun removeRelay(id: String) {}
        override suspend fun bootstrapDefaultsOnFirstLogin() {}
        override suspend fun clearUserRelayConfig() {}
    }

    private class NeverSignNip05Repository : Nip05Repository {
        override suspend fun verifyNip05(nip05: String, pubkey: String): Result<Nip05VerificationState> =
            Result.failure(IllegalStateException("not exercised in this test"))
    }

    private val noopAvatarPrefetcher = object : AvatarPrefetcher {
        override fun prefetchAsync(url: String, scopeTag: String) {}
    }

    private fun repository(): UserRepositoryImpl =
        UserRepositoryImpl(
            userProfileDao = ThrowingUserProfileDao(),
            userPreferences = FakeUserPreferences(),
            relayRepository = ThrowingRelayRepository(),
            nip05Repository = NeverSignNip05Repository(),
            imagePrefetcher = noopAvatarPrefetcher
        )

    @Test
    fun `given older and newer DM relay lists racing through saveDmRelayList then the newer always wins`() = runTest {
        val repo = repository()
        val newer = DmRelayList(ownerKey, relays = listOf("wss://new.example"), lastUpdated = 2_000)
        val older = DmRelayList(ownerKey, relays = listOf("wss://old.example"), lastUpdated = 1_000)

        // 200 concurrent pairs; interleaving is nondeterministic, so run the two saves in
        // shuffled order on many parallel workers until the older one has landed last at least
        // once — which the old read-check-write let clobber the newer value.
        val workers = (1..50).map { round ->
            async(Dispatchers.Default) {
                val (first, second) = if (round % 2 == 0) older to newer else newer to older
                repo.saveDmRelayList(first)
                withContext(Dispatchers.IO) { repo.saveDmRelayList(second) }
            }
        }
        workers.forEach { it.await() }

        assertEquals(newer, repo.getDmRelayList(ownerKey))
    }

    @Test
    fun `given older and newer server lists racing through saveServerList then the newer always wins`() = runTest {
        val repo = repository()
        val newer = UserServerList(pubkey = ownerKey, servers = listOf("https://new.blossom"), lastUpdated = 2_000)
        val older = UserServerList(pubkey = ownerKey, servers = listOf("https://old.blossom"), lastUpdated = 1_000)

        val workers = (1..50).map { round ->
            async(Dispatchers.Default) {
                val (first, second) = if (round % 2 == 0) older to newer else newer to older
                repo.saveServerList(first)
                withContext(Dispatchers.IO) { repo.saveServerList(second) }
            }
        }
        workers.forEach { it.await() }

        assertEquals(newer, repo.getServerList(ownerKey))
    }

    @Test
    fun `given older and newer search relay lists racing through saveSearchRelaysList then the newer always wins`() = runTest {
        val repo = repository()
        val newer = SearchRelaysList(ownerPubkey = ownerKey, relayUrls = setOf("wss://new.example"), updatedAt = 2_000)
        val older = SearchRelaysList(ownerPubkey = ownerKey, relayUrls = setOf("wss://old.example"), updatedAt = 1_000)

        val workers = (1..50).map { round ->
            async(Dispatchers.Default) {
                val (first, second) = if (round % 2 == 0) older to newer else newer to older
                repo.saveSearchRelaysList(first)
                withContext(Dispatchers.IO) { repo.saveSearchRelaysList(second) }
            }
        }
        workers.forEach { it.await() }

        assertEquals(newer, repo.observeSearchRelaysList(ownerKey).first())
    }

    @Test
    fun `given older and newer index relay lists racing through saveIndexRelaysList then the newer always wins`() = runTest {
        val repo = repository()
        val newer = IndexRelaysList(ownerPubkey = ownerKey, relayUrls = setOf("wss://new.example"), updatedAt = 2_000)
        val older = IndexRelaysList(ownerPubkey = ownerKey, relayUrls = setOf("wss://old.example"), updatedAt = 1_000)

        val workers = (1..50).map { round ->
            async(Dispatchers.Default) {
                val (first, second) = if (round % 2 == 0) older to newer else newer to older
                repo.saveIndexRelaysList(first)
                withContext(Dispatchers.IO) { repo.saveIndexRelaysList(second) }
            }
        }
        workers.forEach { it.await() }

        assertEquals(newer, repo.observeIndexRelaysList(ownerKey).first())
    }
}
