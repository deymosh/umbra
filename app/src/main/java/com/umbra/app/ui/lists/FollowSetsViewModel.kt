package com.umbra.app.ui.lists

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umbra.app.domain.nip01.Event
import com.umbra.app.domain.nip51.FollowSetMember
import com.umbra.app.domain.nip51.ListEdit
import com.umbra.app.domain.nip51.ListSet
import com.umbra.app.domain.nip51.followSetMembersOf
import com.umbra.app.domain.nip51.latestListSets
import com.umbra.app.domain.preferences.UserPreferences
import com.umbra.app.domain.profile.UserProfile
import com.umbra.app.domain.repository.EventRepository
import com.umbra.app.domain.repository.UserRepository
import com.umbra.app.domain.usecase.DecryptOwnListItemsUseCase
import com.umbra.app.domain.usecase.TrackReferencedAuthorUseCase
import com.umbra.app.ui.common.InteractionActionsCoordinator
import com.umbra.app.ui.components.ListSetChip
import com.umbra.app.ui.components.ListSetChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class FollowSetsState(
    val sets: List<ListSetChip> = emptyList(),
    /** The set shown; the first one until the user picks another. */
    val selectedSet: String? = null,
    val members: List<FollowSetMember> = emptyList(),
    val profiles: Map<String, UserProfile> = emptyMap(),
    /** The shown set has private members the signer hasn't been asked to read yet. */
    val privateLocked: Boolean = false,
    /** The signer was asked and couldn't read them. */
    val privateUnreadable: Boolean = false
) {
    val selectedSetTitle: String? get() = sets.firstOrNull { it.identifier == selectedSet }?.title
}

/**
 * The user's NIP-51 follow sets (kind 30000): named groups of people, read from their own archive
 * and edited through the shared list-edit path, so sets made in other clients keep everything.
 * A set can be shown as its own feed from Feed filters.
 *
 * People are added publicly; private members other clients added are shown once the signer reads
 * them, which the screen offers rather than doing on open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FollowSetsViewModel @Inject constructor(
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val userPreferences: UserPreferences,
    private val decryptOwnListItems: DecryptOwnListItemsUseCase,
    private val trackReferencedAuthor: TrackReferencedAuthorUseCase,
    coordinatorFactory: InteractionActionsCoordinator.Factory
) : ViewModel() {

    private val actions = coordinatorFactory.create(viewModelScope)
    private val me = userPreferences.getPublicKey()?.takeIf { userPreferences.canSignWithAmber() }

    private val sets: StateFlow<List<ListSet>> =
        (if (me == null) emptyFlow() else eventRepository.observeEventsByPubkeyAndKind(me, Event.KIND_FOLLOW_SET, MAX_SETS))
            .map(::latestListSets)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val selectedSet = MutableStateFlow<String?>(null)

    /** Bumped when the signer opened private members, so sets re-read them from the cache. */
    private val unlocks = MutableStateFlow(0)

    /** Ciphertexts the signer was asked for and couldn't open. */
    private val unreadable = MutableStateFlow<Set<String>>(emptySet())

    private val _state = MutableStateFlow(FollowSetsState())
    val state: StateFlow<FollowSetsState> = _state.asStateFlow()

    private val _pickerTarget = MutableStateFlow<String?>(null)

    /** The person the "Add to list" sheet is open for. */
    val pickerTarget: StateFlow<String?> = _pickerTarget.asStateFlow()

    /** Every follow set, marked by whether [pickerTarget] is in it. */
    val pickerChoices: StateFlow<List<ListSetChoice>> = combine(sets, _pickerTarget, unlocks) { sets, target, _ -> sets to target }
        .mapLatest { (sets, target) ->
            if (target == null) {
                emptyList()
            } else {
                sets.map { set -> ListSetChoice(set.identifier, set.title, contains = membersOf(set.event).any { it.pubkey == target }) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val userRepositoryPublic: UserRepository get() = userRepository
    val canEdit: Boolean get() = actions.canSignEvents()

    init {
        viewModelScope.launch {
            var knownIds = emptySet<String>()
            combine(sets, selectedSet, unlocks, unreadable) { sets, selected, _, unreadable -> Triple(sets, selected, unreadable) }
                .collectLatest { (sets, selected, unreadable) ->
                    val ids = sets.mapTo(HashSet()) { it.identifier }
                    // A shown set deleted elsewhere falls back to the first one. One just created
                    // isn't known yet, so it stays selected until it arrives.
                    if (selected != null && selected in knownIds && selected !in ids) selectedSet.value = null
                    knownIds = ids
                    val shownId = selectedSet.value ?: sets.firstOrNull()?.identifier
                    val shown = sets.firstOrNull { it.identifier == shownId }
                    val content = shown?.event?.content.orEmpty()
                    val privateTags = decryptOwnListItems(content, interactive = false)
                    val members = followSetMembersOf(shown?.event, privateTags)
                    _state.update {
                        it.copy(
                            sets = sets.map { set -> ListSetChip(set.identifier, set.title) },
                            selectedSet = shownId,
                            members = members,
                            privateLocked = privateTags == null && content !in unreadable,
                            privateUnreadable = privateTags == null && content in unreadable
                        )
                    }
                    loadProfiles(members.map { it.pubkey })
                }
        }
    }

    private suspend fun membersOf(set: Event): List<FollowSetMember> =
        followSetMembersOf(set, decryptOwnListItems(set.content, interactive = false))

    /** Profiles for [pubkeys]; unknown ones are looked up and read again once they've had time to arrive. */
    private suspend fun loadProfiles(pubkeys: List<String>) {
        var profiles = userRepository.getProfilesByPubkey(pubkeys)
        _state.update { it.copy(profiles = profiles) }
        val missing = pubkeys.filterNot { it in profiles }
        if (missing.isEmpty()) return
        missing.forEach { trackReferencedAuthor(it) }
        delay(PROFILE_RETRY_MS)
        profiles = userRepository.getProfilesByPubkey(pubkeys)
        _state.update { it.copy(profiles = profiles) }
    }

    fun selectSet(identifier: String?) {
        selectedSet.value = identifier
    }

    fun openPicker(pubkey: String) {
        _pickerTarget.value = pubkey.lowercase()
    }

    fun closePicker() {
        _pickerTarget.value = null
    }

    /** Adds [pubkey] to the set [identifier], or takes them out when they're already in it. */
    fun togglePerson(pubkey: String, identifier: String) {
        val set = sets.value.firstOrNull { it.identifier == identifier } ?: return
        viewModelScope.launch {
            val members = membersOf(set.event)
            edit(identifier, members, if (members.any { it.pubkey == pubkey }) ListEdit("p", remove = setOf(pubkey)) else ListEdit("p", add = setOf(pubkey)))
        }
    }

    fun removeMember(pubkey: String) {
        val identifier = _state.value.selectedSet ?: return
        edit(identifier, _state.value.members, ListEdit("p", remove = setOf(pubkey)))
    }

    private fun edit(identifier: String, members: List<FollowSetMember>, listEdit: ListEdit) {
        if (!actions.canSignEvents()) return
        actions.requestSignAndPublish(
            buildEventJson = {
                actions.buildListEdit(
                    Event.KIND_FOLLOW_SET,
                    listEdit,
                    fallbackValues = members.filterNot { it.isPrivate }.mapTo(HashSet()) { it.pubkey },
                    identifier = identifier
                )
            },
            currentUserHex = userPreferences.getPublicKey()
        )
    }

    /** Creates a set titled [title], holding [initial] when given. */
    fun createSet(title: String, initial: String? = null) {
        val name = title.trim()
        if (name.isEmpty() || !actions.canSignEvents()) return
        val identifier = UUID.randomUUID().toString()
        actions.requestSignAndPublish(
            buildEventJson = {
                actions.buildListEdit(
                    Event.KIND_FOLLOW_SET,
                    if (initial == null) ListEdit("p") else ListEdit("p", add = setOf(initial.lowercase())),
                    fallbackValues = emptySet(),
                    identifier = identifier,
                    newTitle = name
                )
            },
            currentUserHex = userPreferences.getPublicKey(),
            onSigned = { if (initial == null) selectedSet.value = identifier }
        )
    }

    fun renameSet(identifier: String, title: String) {
        val name = title.trim()
        if (name.isEmpty() || !actions.canSignEvents()) return
        actions.requestSignAndPublish(
            buildEventJson = {
                actions.buildListEdit(Event.KIND_FOLLOW_SET, ListEdit("p"), fallbackValues = emptySet(), identifier = identifier, newTitle = name)
            },
            currentUserHex = userPreferences.getPublicKey()
        )
    }

    /** Deletes the set with a NIP-09 request for its address. */
    fun deleteSet(identifier: String) {
        val me = userPreferences.getPublicKey() ?: return
        val set = sets.value.firstOrNull { it.identifier == identifier } ?: return
        actions.deleteEvent(set.event, me, onDeleteConfirmed = { selectedSet.value = null })
    }

    /** Asks the signer to read the shown set's private members — the user's explicit request. */
    fun unlockPrivate() {
        val content = sets.value.firstOrNull { it.identifier == _state.value.selectedSet }?.event?.content.orEmpty()
        viewModelScope.launch {
            if (decryptOwnListItems(content, interactive = true) == null) {
                unreadable.update { it + content }
            } else {
                unlocks.update { it + 1 }
            }
        }
    }

    private companion object {
        const val MAX_SETS = 500
        const val PROFILE_RETRY_MS = 4_000L
    }
}
