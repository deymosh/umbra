package com.umbra.app.ui.snapshot

import androidx.media3.datasource.DataSource
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip05.Nip05VerificationState
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.UserRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Deterministic sample data for Paparazzi snapshots. Profiles deliberately carry no `picture` URL
 * so avatars render their offline initials fallback — snapshots never touch the network (and
 * couldn't: all real image loads are Tor-gated).
 */
internal object SnapshotFixtures {
    // Fixed "now" so relative timestamps ("2h") are stable across runs.
    val now: Long = System.currentTimeMillis() / 1000

    const val ALICE = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
    const val BOB = "b0b0b0b0e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f91"
    const val CAROL = "c4a0c4a0e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f92"

    val alice = UserProfile(
        pubkey = ALICE,
        name = "alice",
        displayName = "Alice Moreau",
        nip05 = "alice@umbra.social",
        about = "Cryptographer. Night-sky photographer. Running relays over onion services since before it was cool.\n\nChasing totality around the world — next stop 2027. #eclipse #nostr",
        website = "https://alice.example",
        lud16 = "alice@getalby.com",
        nip05VerificationState = Nip05VerificationState.Verified
    )
    val bob = UserProfile(pubkey = BOB, name = "bob", displayName = "Bob Kade", nip05 = "_@kade.dev")
    val carol = UserProfile(pubkey = CAROL, name = "carol")

    fun note(
        id: String,
        pubkey: String,
        content: String,
        agoSeconds: Long,
        tags: List<List<String>> = emptyList()
    ) = Event(
        id = id.padEnd(64, '0'),
        pubkey = pubkey,
        createdAt = now - agoSeconds,
        kind = Event.KIND_TEXT_NOTE,
        tags = tags,
        content = content
    )

    val textNote = note(
        id = "e1",
        pubkey = ALICE,
        content = "Totality lasted 4 minutes 28 seconds from where we stood. The corona looked like " +
            "frayed silk — no photo does it justice, but here's my attempt anyway. Clear skies everyone.",
        agoSeconds = 2 * 3600,
        tags = listOf(listOf("t", "eclipse"), listOf("t", "astrophotography"))
    )

    val shortNote = note(
        id = "e2",
        pubkey = BOB,
        content = "gm. relays are fast over onion today ⚡",
        agoSeconds = 14 * 60
    )

    val reply = note(
        id = "e3",
        pubkey = CAROL,
        content = "Where did you watch it from? Planning for the next one already.",
        agoSeconds = 40 * 60,
        tags = listOf(listOf("e", "e1".padEnd(64, '0'), "", "reply"), listOf("p", ALICE))
    )

    val feed: List<Pair<Event, UserProfile>> get() = listOf(
        textNote to alice,
        shortNote to bob,
        reply to carol
    )

    val tors: DataSource.Factory = DataSource.Factory { error("no network in snapshots") }

    /** A UserRepository that answers everything with "nothing cached" — enough for rendering. */
    val userRepository: UserRepository = Proxy.newProxyInstance(
        UserRepository::class.java.classLoader,
        arrayOf(UserRepository::class.java)
    ) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            Integer.TYPE -> 0
            Flow::class.java -> emptyFlow<Any>()
            SharedFlow::class.java -> MutableSharedFlow<Any>()
            List::class.java -> emptyList<Any>()
            Map::class.java -> emptyMap<Any, Any>()
            else -> null
        }
    } as UserRepository
}
