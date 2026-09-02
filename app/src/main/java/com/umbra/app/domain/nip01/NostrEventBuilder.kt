package com.umbra.app.domain.nip01

import com.umbra.app.domain.nip21.NostrUriEntity
import com.umbra.app.domain.nip21.resolveNostrUri
import com.umbra.app.domain.nip30.CustomEmoji
import com.umbra.app.domain.nip36.contentWarningTag
import com.umbra.app.domain.nip92.ImetaTag
import com.umbra.app.domain.nip92.toTag
import com.umbra.app.domain.util.TrackingTokenSanitizer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds unsigned Nostr event JSON strings (NIP-01).
 *
 * Responsibilities:
 * - Knows Nostr protocol (kinds, tag shapes, NIP rules).
 * - Does NOT store keys, sign, or perform I/O.
 * - Output is the JSON body AMBER (or any other signer) needs to fill in pubkey and sig.
 *
 * Usage pattern:
 *   val json = NostrEventBuilder.reaction(targetEvent)
 *   amberLauncher.launch(AmberConnector.buildSignIntent(json))
 *   // On AMBER result → parse signed JSON → PublishSignedEventUseCase
 */
object NostrEventBuilder {

    /**
     * NIP-25: Reaction (kind 7). [content] defaults to "+" (plain like); pass a Unicode emoji or
     * a ":shortcode:" (with the matching [emoji] tag data) for a custom reaction. [emoji]'s tag
     * shape (`["emoji", shortcode, url]`) is spec-identical to NIP-30's, so CustomEmoji is reused
     * rather than introducing a NIP-25-specific type.
     */
    fun reaction(targetEvent: Event, content: String = "+", emoji: CustomEmoji? = null): String = buildUnsignedJson(
        kind = Event.KIND_REACTION,
        tags = buildList {
            add(listOf("e", targetEvent.id))
            add(listOf("p", targetEvent.pubkey))
            emoji?.let { add(listOf("emoji", it.shortcode, it.url)) }
        },
        content = content
    )

    /**
     * NIP-18: Repost (kind 6).
     * Content is the serialized original event JSON per NIP-18.
     */
    fun repost(targetEvent: Event): String {
        val contentJson = buildJsonObject {
            put("id", targetEvent.id)
            put("pubkey", targetEvent.pubkey)
            put("created_at", targetEvent.createdAt)
            put("kind", targetEvent.kind)
            put("tags", buildJsonArray {
                targetEvent.tags.forEach { tagList ->
                    add(buildJsonArray {
                        tagList.forEach { add(JsonPrimitive(it)) }
                    })
                }
            })
            put("content", targetEvent.content)
            put("sig", targetEvent.sig)
        }.toString()

        return buildUnsignedJson(
            kind = Event.KIND_REPOST,
            tags = listOf(
                listOf("e", targetEvent.id, ""),
                listOf("p", targetEvent.pubkey)
            ),
            content = contentJson
        )
    }

    /**
     * NIP-01: Text note (kind 1).
     * @param replyTo Optional parent event (NIP-10 reply tags).
     * @param imetaTags NIP-92 metadata for any uploaded media attached to this note (empty for a
     *   plain text-only note) — see [ImetaTag.toTag].
     * @param sensitiveReason When non-null, marks the note NIP-36 sensitive with this reason
     *   (empty string is a valid "sensitive, no reason given" per [contentWarningTag]); null
     *   means don't add a content-warning tag at all.
     */
    fun textNote(
        content: String,
        replyTo: Event? = null,
        imetaTags: List<ImetaTag> = emptyList(),
        sensitiveReason: String? = null
    ): String {
        val sanitizedContent = TrackingTokenSanitizer.sanitizeText(content.trim())
        val replyTags = if (replyTo != null) {
            listOf(
                listOf("e", replyTo.id, "", "reply"),
                listOf("p", replyTo.pubkey)
            )
        } else {
            emptyList()
        }
        // NIP-27: nostr: entities mentioned in the content get p (profile) / q (quoted
        // event/address) tags, so relays/clients can notify the mentioned author and readers
        // can find the quoted content — skips a pubkey already covered by a reply's own p tag.
        val alreadyTaggedPubkeys = replyTags.filter { it.getOrNull(0) == "p" }
            .mapNotNull { it.getOrNull(1)?.lowercase() }
            .toSet()
        val tags = replyTags +
            mentionTags(sanitizedContent, alreadyTaggedPubkeys) +
            imetaTags.map { it.toTag() } +
            attachmentTags(sensitiveReason)
        return buildUnsignedJson(
            kind = Event.KIND_TEXT_NOTE,
            tags = tags,
            content = sanitizedContent
        )
    }

    /** `[contentWarningTag(reason)]` when [sensitiveReason] is non-null, else empty. */
    private fun attachmentTags(sensitiveReason: String?): List<List<String>> =
        if (sensitiveReason != null) listOf(contentWarningTag(sensitiveReason)) else emptyList()

    /**
     * NIP-7D: Forum thread (kind 11). Replies use NIP-22 comments (see
     * [com.umbra.app.domain.nip22]) scoped to the thread event as root — always root, per spec,
     * to avoid arbitrarily nested reply hierarchies — not a separate builder here.
     */
    fun forumThread(title: String, content: String): String {
        val sanitizedTitle = TrackingTokenSanitizer.sanitizeText(title.trim())
        val sanitizedContent = TrackingTokenSanitizer.sanitizeText(content.trim())
        return buildUnsignedJson(
            kind = Event.KIND_THREAD,
            tags = listOf(listOf("title", sanitizedTitle)),
            content = sanitizedContent
        )
    }

    /**
     * NIP-10: Reply (kind 1 with root/reply markers).
     * @param imetaTags See [textNote]'s matching parameter.
     * @param sensitiveReason See [textNote]'s matching parameter.
     */
    fun reply(
        content: String,
        replyToEvent: Event,
        replyToRelayUrl: String = "",
        imetaTags: List<ImetaTag> = emptyList(),
        sensitiveReason: String? = null
    ): String {
        val sanitizedContent = TrackingTokenSanitizer.sanitizeText(content.trim())
        val rootId = replyToEvent.getRootEventId() ?: replyToEvent.id
        val isDirectReplyToRoot = rootId == replyToEvent.id

        val participantPubkeys = (replyToEvent.getTagValues("p") + replyToEvent.pubkey)
            .asSequence()
            .map { it.lowercase() }
            .filter { it.length == 64 }
            .distinct()
            .toList()

        // NIP-27: nostr: entities mentioned in the content get p/q tags — see textNote()'s
        // matching comment. Participants are already covered by their own p tags below.
        val mentioned = mentionTags(sanitizedContent, alreadyTaggedPubkeys = participantPubkeys.toSet())

        val tags = buildJsonArray {
            if (isDirectReplyToRoot) {
                // NIP-10: direct reply to root must carry only one marked e-tag: "root".
                add(buildJsonArray {
                    add(JsonPrimitive("e"))
                    add(JsonPrimitive(rootId))
                    add(JsonPrimitive(replyToRelayUrl))
                    add(JsonPrimitive("root"))
                    add(JsonPrimitive(replyToEvent.pubkey.lowercase()))
                })
            } else {
                add(buildJsonArray {
                    add(JsonPrimitive("e"))
                    add(JsonPrimitive(rootId))
                    add(JsonPrimitive(replyToRelayUrl))
                    add(JsonPrimitive("root"))
                })
                add(buildJsonArray {
                    add(JsonPrimitive("e"))
                    add(JsonPrimitive(replyToEvent.id))
                    add(JsonPrimitive(replyToRelayUrl))
                    add(JsonPrimitive("reply"))
                    add(JsonPrimitive(replyToEvent.pubkey.lowercase()))
                })
            }

            participantPubkeys.forEach { pubkey ->
                add(buildJsonArray {
                    add(JsonPrimitive("p"))
                    add(JsonPrimitive(pubkey))
                })
            }

            mentioned.forEach { tag ->
                add(buildJsonArray {
                    tag.forEach { add(JsonPrimitive(it)) }
                })
            }

            imetaTags.forEach { imetaTag ->
                add(buildJsonArray {
                    imetaTag.toTag().forEach { add(JsonPrimitive(it)) }
                })
            }

            attachmentTags(sensitiveReason).forEach { tag ->
                add(buildJsonArray {
                    tag.forEach { add(JsonPrimitive(it)) }
                })
            }
        }

        return buildUnsignedEvent(
            kind = Event.KIND_TEXT_NOTE,
            content = sanitizedContent,
            tags = tags
        )
    }

    /**
     * NIP-09: Event deletion request (kind 5) for a single event. See the [List] overload for
     * the full tag shape (`e`, `a` for addressable kinds, `k`).
     */
    fun deleteEvent(event: Event, reason: String = ""): String = deleteEvent(listOf(event), reason)

    /**
     * NIP-09: Event deletion request (kind 5), batching one or more events into a single
     * request. Each gets an `e` tag; addressable-range events (kind 30000-39999, NIP-01) also
     * get an `a` tag (`kind:pubkey:d-identifier`) so relays drop every revision up to this
     * request's timestamp, not just the referenced one. `k` tags (kind of each deleted event)
     * are included per spec's SHOULD, deduplicated across the batch.
     */
    fun deleteEvent(events: List<Event>, reason: String = ""): String {
        val tags = buildJsonArray {
            events.forEach { event ->
                add(buildJsonArray {
                    add(JsonPrimitive("e"))
                    add(JsonPrimitive(event.id))
                })
            }
            events.filter { it.kind in 30000..39999 }.forEach { event ->
                val identifier = event.getTagValue("d").orEmpty()
                add(buildJsonArray {
                    add(JsonPrimitive("a"))
                    add(JsonPrimitive("${event.kind}:${event.pubkey}:$identifier"))
                })
            }
            events.map { it.kind }.distinct().forEach { kind ->
                add(buildJsonArray {
                    add(JsonPrimitive("k"))
                    add(JsonPrimitive(kind.toString()))
                })
            }
        }
        return buildUnsignedEvent(
            kind = Event.KIND_EVENT_DELETION,
            content = TrackingTokenSanitizer.sanitizeText(reason.trim()),
            tags = tags
        )
    }

    /**
     * NIP-22: Comment (kind 1111) on [root], or — when [replyTo] is a different event within
     * that same root thread (e.g. another comment) — a reply to [replyTo] instead. A top-level
     * comment omits [replyTo] and root/parent end up identical, matching spec's examples.
     *
     * Only the event-pointer and addressable-pointer scopes are covered (root/parent are
     * regular or addressable Nostr events) — NIP-73 external-identifier scopes (URLs, geohashes,
     * podcast episodes, etc.) aren't built here, only parsed by `extractCommentTarget`.
     *
     * Per spec, comments MUST NOT reply to kind-1 notes — use [reply] for those instead.
     */
    fun comment(root: Event, replyTo: Event = root, content: String): String {
        require(root.kind != Event.KIND_TEXT_NOTE) {
            "NIP-22 comments must not target kind 1 text notes — use NostrEventBuilder.reply()"
        }
        require(replyTo.kind != Event.KIND_TEXT_NOTE) {
            "NIP-22 comments must not reply to kind 1 text notes — use NostrEventBuilder.reply()"
        }

        val tags = buildJsonArray {
            appendCommentScope(root, uppercase = true)
            appendCommentScope(replyTo, uppercase = false)
        }

        return buildUnsignedEvent(
            kind = Event.KIND_COMMENT,
            content = TrackingTokenSanitizer.sanitizeText(content.trim()),
            tags = tags
        )
    }

    /**
     * NIP-A4: Public message (kind 24) — a plaintext message to one or more receivers, `p`-tagged,
     * with no reply chain (`e` tags are prohibited per spec: "There are no message chains").
     * NIP-27 `nostr:` mentions in the content still get their own `p`/`q` tags, same as [textNote].
     */
    fun publicMessage(content: String, receiverPubkeys: List<String>): String {
        val sanitizedContent = TrackingTokenSanitizer.sanitizeText(content.trim())
        val receiverTags = receiverPubkeys.map { it.lowercase() }.distinct().map { listOf("p", it) }
        val alreadyTagged = receiverTags.mapNotNull { it.getOrNull(1) }.toSet()
        val tags = receiverTags + mentionTags(sanitizedContent, alreadyTagged)
        return buildUnsignedJson(
            kind = Event.KIND_PUBLIC_MESSAGE,
            tags = tags,
            content = sanitizedContent
        )
    }

    /**
     * NIP-C7: Chat message (kind 9). Spec models chat as a flat, ordered stream rather than a
     * reply tree — a reply quotes its parent via a NIP-18 `q` tag instead of a threading `e` tag.
     */
    fun chatMessage(content: String, quoting: Event? = null, quotingRelayUrl: String = ""): String {
        val sanitizedContent = TrackingTokenSanitizer.sanitizeText(content.trim())
        val tags = buildJsonArray {
            if (quoting != null) {
                add(buildJsonArray {
                    add(JsonPrimitive("q"))
                    add(JsonPrimitive(quoting.id))
                    add(JsonPrimitive(quotingRelayUrl))
                    add(JsonPrimitive(quoting.pubkey.lowercase()))
                })
            }
            val alreadyTagged = if (quoting != null) setOf(quoting.pubkey.lowercase()) else emptySet()
            mentionTags(sanitizedContent, alreadyTaggedPubkeys = alreadyTagged).forEach { tag ->
                add(buildJsonArray { tag.forEach { add(JsonPrimitive(it)) } })
            }
        }
        return buildUnsignedEvent(
            kind = Event.KIND_CHAT_MESSAGE,
            content = sanitizedContent,
            tags = tags
        )
    }

    /**
     * NIP-01: User metadata update (kind 0).
     * Publishes updated profile fields. Only non-blank values are included in the JSON.
     * The relay will replace the previous kind-0 for this pubkey.
     */
    fun updateProfile(
        name: String?,
        displayName: String?,
        about: String?,
        website: String?,
        nip05: String?,
        lud16: String?,
        picture: String?,
        banner: String? = null,
        lud06: String? = null
    ): String {
        val content = buildJsonObject {
            name?.trim()?.takeIf { it.isNotBlank() }?.let {
                put("name", TrackingTokenSanitizer.sanitizeText(it))
            }
            displayName?.trim()?.takeIf { it.isNotBlank() }?.let {
                put("display_name", TrackingTokenSanitizer.sanitizeText(it))
            }
            about?.trim()?.takeIf { it.isNotBlank() }?.let {
                put("about", TrackingTokenSanitizer.sanitizeText(it))
            }
            website?.trim()?.takeIf { it.isNotBlank() }?.let {
                put("website", TrackingTokenSanitizer.sanitizeText(it))
            }
            nip05?.trim()?.takeIf { it.isNotBlank() }?.let { put("nip05", it) }
            lud16?.trim()?.takeIf { it.isNotBlank() }?.let { put("lud16", it) }
            picture?.trim()?.takeIf { it.isNotBlank() }?.let {
                put("picture", TrackingTokenSanitizer.sanitizeText(it))
            }
            banner?.trim()?.takeIf { it.isNotBlank() }?.let {
                put("banner", TrackingTokenSanitizer.sanitizeText(it))
            }
            lud06?.trim()?.takeIf { it.isNotBlank() }?.let { put("lud06", it) }
        }.toString()
        return buildUnsignedEvent(
            kind = Event.KIND_METADATA,
            content = content,
            tags = buildJsonArray {}
        )
    }

    /**
     * NIP-02: Contact list (kind 3).
     */
    fun contactList(followedPubkeys: Set<String>): String = buildTagListEvent(
        kind = Event.KIND_CONTACT_LIST,
        tagName = "p",
        values = followedPubkeys,
        valueTransform = { it.lowercase() },
        valueFilter = { it.length == 64 }
    )

    /**
     * NIP-51: public mute list (kind 10000). Only the public "p" tag form is written —
     * no NIP-44 encrypted content — matching the plain-tag approach used for contactList().
     */
    fun muteList(mutedPubkeys: Set<String>): String = buildTagListEvent(
        kind = Event.KIND_MUTED_USERS,
        tagName = "p",
        values = mutedPubkeys,
        valueTransform = { it.lowercase() },
        valueFilter = { it.length == 64 }
    )

    /**
     * NIP-51: public pin list (kind 10001). "e" tags referencing pinned event ids.
     */
    fun pinList(pinnedEventIds: Set<String>): String = buildTagListEvent(
        kind = Event.KIND_PINNED_EVENTS,
        tagName = "e",
        values = pinnedEventIds,
        valueTransform = { it.lowercase() },
        valueFilter = { it.length == 64 }
    )

    /**
     * NIP-51: bookmarks list (kind 10003) — uncategorized notes ("e") and articles ("a").
     */
    fun bookmarkList(noteIds: Set<String>, articleAddresses: Set<String> = emptySet()): String {
        val tags = buildJsonArray {
            noteIds.map { it.lowercase() }.filter { it.length == 64 }.distinct().sorted().forEach { id ->
                add(buildJsonArray { add(JsonPrimitive("e")); add(JsonPrimitive(id)) })
            }
            articleAddresses.distinct().sorted().forEach { address ->
                add(buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive(address)) })
            }
        }
        return buildUnsignedEvent(kind = Event.KIND_BOOKMARK_LIST, content = "", tags = tags)
    }

    /**
     * NIP-51 / NIP-72: communities list (kind 10004) — "a" pointers to kind-34550 community
     * definitions.
     */
    fun communitiesList(communityAddresses: Set<String>): String =
        buildTagListEvent(kind = Event.KIND_COMMUNITIES_LIST, tagName = "a", values = communityAddresses)

    /**
     * NIP-51: blocked relays list (kind 10006) — relays clients should never connect to.
     */
    fun blockedRelaysList(relayUrls: Set<String>): String =
        buildTagListEvent(kind = Event.KIND_BLOCKED_RELAYS, tagName = "relay", values = relayUrls)

    /**
     * NIP-51: search relays list (kind 10007) — relays clients should use for search queries.
     */
    fun searchRelaysList(relayUrls: Set<String>): String =
        buildTagListEvent(kind = Event.KIND_SEARCH_RELAYS, tagName = "relay", values = relayUrls)

    /**
     * NIP-65: relay list metadata (kind 10002) — where the user publishes to and where they
     * expect mentions. [writeOnly]/[readOnly]/[both] should be disjoint (a URL present in one of
     * the three role sets, not more than one) — the caller derives them from local relay role
     * flags, this only encodes the resulting tag markers per NIP-65's `["r", url]` (both),
     * `["r", url, "write"]`, `["r", url, "read"]` shape.
     */
    fun relayList(writeOnly: Set<String>, readOnly: Set<String>, both: Set<String>): String {
        val tags = buildList {
            both.distinct().sorted().forEach { add(listOf("r", it)) }
            writeOnly.distinct().sorted().forEach { add(listOf("r", it, "write")) }
            readOnly.distinct().sorted().forEach { add(listOf("r", it, "read")) }
        }
        return buildUnsignedJson(kind = Event.KIND_RELAY_LIST_METADATA, tags = tags, content = "")
    }

    /**
     * NIP-17 / NIP-51: DM relay list (kind 10050) — where the user expects to receive private
     * direct messages. Plain `"relay"` tags, same shape as [searchRelaysList].
     */
    fun dmRelayList(relayUrls: Set<String>): String {
        val tags = relayUrls.distinct().sorted().map { listOf("relay", it) }
        return buildUnsignedJson(kind = Event.KIND_DM_RELAY_LIST, tags = tags, content = "")
    }

    /**
     * Blossom (BUD-03): user server list (kind 10063). Unlike [dmRelayList]/[relayList], tag
     * order is preserved as given rather than sorted — BUD-03 defines the first `server` tag as
     * the user's most "reliable"/"trusted" one, so caller-supplied priority order is significant.
     */
    fun blossomServerList(servers: List<String>): String {
        val tags = servers.distinct().map { listOf("server", it) }
        return buildUnsignedJson(kind = Event.KIND_BLOSSOM_SERVER_LIST, tags = tags, content = "")
    }

    /**
     * NIP-51 "private list" variant of [searchRelaysList] (kind 10007) — [encryptedContent] is
     * the NIP-44 self-encrypted ciphertext of the relay-tag JSON (see
     * RelayTagListParsing.encodeRelayTagUrls + Nip44Gateway.createNip44EncryptIntent),
     * with public tags left empty. This is the private-by-default convention for this kind — see
     * the NIP-44 relay-list plan for why (declaring which relays you search with is itself a
     * privacy leak). The encryption itself never happens here or anywhere in Umbra locally — only
     * Amber ever touches the private key.
     */
    fun searchRelaysListEncrypted(encryptedContent: String): String =
        buildUnsignedEvent(kind = Event.KIND_SEARCH_RELAYS, content = encryptedContent, tags = buildJsonArray {})

    /** Same as [searchRelaysListEncrypted], for kind 10086 (index relays). */
    fun indexRelaysListEncrypted(encryptedContent: String): String =
        buildUnsignedEvent(kind = Event.KIND_INDEX_RELAYS, content = encryptedContent, tags = buildJsonArray {})

    /**
     * NIP-51: interests list (kind 10015) — hashtags ("t") and interest-set ("a", kind 30015)
     * pointers.
     */
    fun interestsList(hashtags: Set<String>, interestSetAddresses: Set<String> = emptySet()): String {
        val tags = buildJsonArray {
            hashtags.map { it.lowercase() }.distinct().sorted().forEach { tag ->
                add(buildJsonArray { add(JsonPrimitive("t")); add(JsonPrimitive(tag)) })
            }
            interestSetAddresses.distinct().sorted().forEach { address ->
                add(buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive(address)) })
            }
        }
        return buildUnsignedEvent(kind = Event.KIND_INTERESTS_LIST, content = "", tags = tags)
    }

    /**
     * NIP-42: relay authentication event (kind 22242).
     */
    fun relayAuth(challenge: String, relayUrl: String): String {
        val safeChallenge = challenge.trim()
        val safeRelayUrl = relayUrl.trim()
        val tags = buildJsonArray {
            add(buildJsonArray {
                add(JsonPrimitive("relay"))
                add(JsonPrimitive(safeRelayUrl))
            })
            add(buildJsonArray {
                add(JsonPrimitive("challenge"))
                add(JsonPrimitive(safeChallenge))
            })
        }
        return buildUnsignedEvent(
            kind = Event.KIND_CLIENT_AUTH,
            content = "",
            tags = tags
        )
    }

    /**
     * Blossom (BUD-01/BUD-11): HTTP authorization event (kind 24242).
     * Signed and base64-encoded by the caller into an `Authorization: Nostr <base64>` header —
     * this event is never published to relays, only sent to the Blossom server itself.
     *
     * [serverTags] scopes the token to specific server domains (BUD-11 `server` tag) — each
     * entry MUST be a bare lowercase domain (e.g. `cdn.example.com`), not a full URL; see
     * [com.umbra.app.domain.nipb7.blossomServerDomain]. Leaving this empty makes the token valid
     * on any server that accepts it, which BUD-11 flags as the main replay risk for `delete`
     * tokens specifically — callers building a delete/mirror auth should always scope it.
     */
    fun blossomAuth(
        verb: String,
        sha256Hex: String,
        expirationEpochSeconds: Long,
        description: String = "",
        serverTags: List<String> = emptyList()
    ): String {
        val tags = buildJsonArray {
            add(buildJsonArray {
                add(JsonPrimitive("t"))
                add(JsonPrimitive(verb))
            })
            add(buildJsonArray {
                add(JsonPrimitive("x"))
                add(JsonPrimitive(sha256Hex))
            })
            add(buildJsonArray {
                add(JsonPrimitive("expiration"))
                add(JsonPrimitive(expirationEpochSeconds.toString()))
            })
            serverTags.forEach { domain ->
                add(buildJsonArray {
                    add(JsonPrimitive("server"))
                    add(JsonPrimitive(domain))
                })
            }
        }
        return buildUnsignedEvent(
            kind = Event.KIND_BLOSSOM_AUTH,
            content = description,
            tags = tags
        )
    }

    /**
     * Appends one NIP-22 comment scope (root if [uppercase], else parent) for [target]:
     * an address tag (A/a) for addressable-range kinds (30000-39999) or an id tag (E/e)
     * otherwise, always paired with the kind tag (K/k) and, when known, the author tag (P/p).
     */
    private fun JsonArrayBuilder.appendCommentScope(target: Event, uppercase: Boolean) {
        val kindTag = if (uppercase) "K" else "k"
        val pubkeyTag = if (uppercase) "P" else "p"

        if (target.kind in 30000..39999) {
            val identifier = target.getTagValue("d").orEmpty()
            add(buildJsonArray {
                add(JsonPrimitive(if (uppercase) "A" else "a"))
                add(JsonPrimitive("${target.kind}:${target.pubkey}:$identifier"))
            })
        } else {
            add(buildJsonArray {
                add(JsonPrimitive(if (uppercase) "E" else "e"))
                add(JsonPrimitive(target.id))
            })
        }
        add(buildJsonArray {
            add(JsonPrimitive(kindTag))
            add(JsonPrimitive(target.kind.toString()))
        })
        add(buildJsonArray {
            add(JsonPrimitive(pubkeyTag))
            add(JsonPrimitive(target.pubkey))
        })
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private val NOSTR_ENTITY_REGEX = Regex(
        """nostr:(npub1[a-z0-9]+|nprofile1[a-z0-9]+|note1[a-z0-9]+|nevent1[a-z0-9]+|naddr1[a-z0-9]+)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * NIP-27: scans [content] for `nostr:` entity references and returns the `p` (mentioned
     * profile) / `q` (quoted event or address) tags that should accompany the event, per spec.
     * [alreadyTaggedPubkeys] skips a `p` tag for a pubkey some other tag already covers (e.g. a
     * reply's own participant tags), so a mentioned author doesn't get a duplicate.
     */
    private fun mentionTags(content: String, alreadyTaggedPubkeys: Set<String> = emptySet()): List<List<String>> {
        val tags = mutableListOf<List<String>>()
        val seenPubkeys = alreadyTaggedPubkeys.toMutableSet()
        val seenQuoted = mutableSetOf<String>()

        NOSTR_ENTITY_REGEX.findAll(content).forEach { match ->
            when (val resolved = resolveNostrUri("nostr:${match.groupValues[1]}")) {
                is NostrUriEntity.Profile -> {
                    if (seenPubkeys.add(resolved.pubkey.lowercase())) tags.add(listOf("p", resolved.pubkey))
                }
                is NostrUriEntity.Note -> {
                    // Conventional q-tag shape is ["q", eventId, relayUrl, pubkey] — relay/pubkey
                    // are only appended when known (a bare note1 reference carries neither), and
                    // the relay slot is left "" rather than omitted when only pubkey is known, to
                    // keep pubkey in its expected 4th position.
                    if (seenQuoted.add(resolved.eventId)) {
                        val relay = resolved.relays.firstOrNull()
                        val author = resolved.authorPubkey
                        tags.add(
                            buildList {
                                add("q")
                                add(resolved.eventId)
                                if (relay != null || author != null) add(relay.orEmpty())
                                if (author != null) add(author)
                            }
                        )
                    }
                }
                is NostrUriEntity.Address -> {
                    val coordinate = "${resolved.kind}:${resolved.authorPubkey}:${resolved.identifier}"
                    if (seenQuoted.add(coordinate)) tags.add(listOf("q", coordinate))
                }
                null -> Unit
            }
        }
        return tags
    }

    /**
     * Serialises an unsigned event to JSON with an empty pubkey (to be filled
     * by the signer) and an empty sig.  The id is also left empty because
     * NIP-01 requires the signer to compute id = SHA256(serialized_event).
     */
    /**
     * Shared builder for the common "one tag per sorted, deduped, optionally-filtered value"
     * NIP-51 list-event shape — contactList/muteList/pinList/blockedRelaysList/searchRelaysList/
     * communitiesList only differ in kind/tag name/value transform+filter.
     */
    private fun buildTagListEvent(
        kind: Int,
        tagName: String,
        values: Set<String>,
        valueTransform: (String) -> String = { it },
        valueFilter: (String) -> Boolean = { true }
    ): String {
        val tags = values.asSequence()
            .map(valueTransform)
            .filter(valueFilter)
            .distinct()
            .sorted()
            .map { value -> listOf(tagName, value) }
            .toList()
        return buildUnsignedJson(kind = kind, tags = tags, content = "")
    }

    private fun buildUnsignedJson(
        kind: Int,
        tags: List<List<String>>,
        content: String
    ): String {
        val tagsJson = buildJsonArray {
            tags.forEach { tagList ->
                add(buildJsonArray {
                    tagList.forEach { add(JsonPrimitive(it)) }
                })
            }
        }
        return buildJsonObject {
            put("id", "")
            put("pubkey", "")
            put("created_at", System.currentTimeMillis() / 1000)
            put("kind", kind)
            put("tags", tagsJson)
            put("content", content)
            put("sig", "")
        }.toString()
    }

    private fun buildUnsignedEvent(
        kind: Int,
        content: String,
        tags: JsonArray
    ): String {
        return buildJsonObject {
            put("id", "")
            put("pubkey", "")
            put("created_at", System.currentTimeMillis() / 1000)
            put("kind", kind)
            put("tags", tags)
            put("content", content)
            put("sig", "")
        }.toString()
    }
}



