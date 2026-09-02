# DONE

Append-only completed-work log — validated bug fixes originally logged in
[KNOWN_ISSUES.md](KNOWN_ISSUES.md), and completed backlog items originally logged in
[TODO.md](TODO.md). See [`.claude/CLAUDE.md`](../.claude/CLAUDE.md)'s "Bug tracking" section for
the full convention. Entries move here verbatim from their source file:
- A bug fix moves once validated, with a `**Validated:**` date appended.
- A backlog item moves once shipped, with a `**Completed:**` date appended.

Never edit a past entry beyond adding that one line.

### LOG-5 — "Create new filter" reopens the last-edited filter in stale edit mode
- **Status:** fix applied — needs on-device validation
- **Found:** 2026-08-20
- **Where:** `ui/feedconfig/FeedConfigViewModel.kt` (`openAddDialog()`/`closeAddDialog()`) /
  `ui/feedconfig/FeedFilterEditScreen.kt`
- **Fix:** `openAddDialog()` now explicitly clears `editingFilter`, and `FeedFilterEditScreen`
  adds a local `BackHandler` calling `closeAddDialog()` so system back/gesture resets state at
  the source, not just the explicit close (X) button. See
  `claude/relay-client-and-subscriptions-ui` branch.
- **Validated:** 2026-08-20

Reported by the user: tap "Edit" on an existing filter, don't save, go back, then tap "create
new filter" — it reopens in edit mode of the previously-tapped filter instead of a blank create
form. Root cause: `FeedConfigViewModel`'s `editingFilter`/`showAddDialog` state is scoped to the
shared nested-nav-graph ViewModel (`FeedConfig`/`FeedFilterEdit` share one instance), and while
the in-screen close icon correctly calls `closeAddDialog()` (which clears `editingFilter`), the
system back button/gesture bypasses it entirely — `NavHost.kt`'s global `BackHandler` just pops
the nav stack with no knowledge of this ViewModel's state, leaving `editingFilter` set. The next
`openAddDialog()` call (from the "+" button) never cleared it either, so `FeedFilterEditScreen`
read the stale `editingFilter` and rendered pre-filled in edit mode.

### LOG-8 — Add a three-dot overflow menu to the repost banner
- **Status:** backlog
- **Added:** 2026-08-21
- **Why:** requested by the user — a normal note's EventCard has a three-dot menu (pin/unpin,
  mute, copy id/content/nevent/json, delete), but the "X reposted" banner above a NIP-18
  kind-6/16 repost had no menu at all, only tap-to-profile on the reposter.

Threaded the repost event through end-to-end (`ResolvedFeedEvent`, `NoteView`,
`FeedViewModel`/`ProfileViewModel` state, `NotesFeedSection`) alongside the already-threaded
`repostedByPubkey`/`repostedAt`, and added a right-aligned overflow button to `RepostBanner`
matching `NoteHeader`'s kebab in size/style. Per the user's explicit instruction ("este menú
tiene que tener las mismas opciones que cualquier evento normal, ni más ni menos"), the repost's
menu offers exactly the same actions a normal note's menu does — `EventCard`'s action-list
building was extracted into a shared `eventActionItems()` helper and invoked once for the note
and once for the repost event when present, instead of a smaller bespoke set.
**Completed:** 2026-08-21
**From:** TODO LOG-8

### LOG-9 — Add a developer option to inspect the encrypted database
- **Status:** backlog
- **Added:** 2026-08-21
- **Why:** requested by the user — wanted to be able to browse tables and search events in the
  SQLCipher-encrypted DB directly, for debugging during development.

Added a "DB Inspector" screen, reachable from Settings' Developer section as a sibling row to
Developer Options / App Resource Usage (same navigational pattern as `AppResourceUsageScreen`,
not nested inside the toggle-only `DeveloperOptionsScreen`): per-table row counts across all 6
tables, plus a kind/pubkey/content-substring search over the `events` table with a raw detail
view (id/pubkey/kind/created_at/content/tagsJson/sig). Deliberately a fixed set of read-only,
parameterized queries rather than arbitrary SQL passthrough, given the DB's security-sensitive,
encrypted nature. New `domain/repository/DbInspectorRepository.kt` interface (+
`DbInspectorRepositoryImpl`) keeps `ui/devoptions/dbinspector/` from importing `data/db/dao/*`
directly, consistent with the existing `ui -> domain -> data` layering.
**Completed:** 2026-08-21
**From:** TODO LOG-9

### LOG-10 — New feed events should push down existing content, not auto-scroll
- **Status:** backlog
- **Added:** 2026-08-21
- **Why:** requested by the user — a prior auto-scroll attempt (`FeedScreen.kt`'s
  `LaunchedEffect(Unit)` animate-scroll-to-top on new-head arrival) didn't feel right; wanted
  the Amethyst-style behavior where a new note simply pushes existing content down with no
  visible scroll.

`NotesFeedSection`'s `LazyColumn` already keys items by stable event id, so LazyColumn's own
scroll-position preservation already makes a newly-prepended note "push down" existing content
without any explicit scroll call — the animate-scroll `LaunchedEffect` was fighting behavior
that already worked correctly on its own. Removed the `LaunchedEffect(Unit)` block and its
now-unused `scrollToTopAnimated()` helper; `scrollToTopImmediate()` (used by the explicit
"go to top" affordances) is untouched.
**Completed:** 2026-08-21
**From:** TODO LOG-10

### LOG-15 — Add tap-to-explain info icons for relay type sections
- **Status:** backlog
- **Added:** 2026-08-22
- **Why:** the app had zero tap-to-explain affordance anywhere, and the meaning of
  Outbox/Inbox/DM/Search/Index/Discovered relay sections isn't obvious to a new user.

New `ui/components/InfoIcon.kt` (modeled on `ConfirmDialog.kt`'s plain `AlertDialog` wrapper
shape): a small `IconButton` showing `Icons.Default.Info` that opens a dismiss-only `AlertDialog`
with a title/message on tap. `SectionHeader.kt` and `RelaySections.kt`'s `relayRoleSection()` gained
an optional `infoContent` composable slot, wired into all 8 relay-section headers in
`RelayConfigScreen.kt` (Outbox/Inbox/DM/Search/Index/three Discovered variants) plus the five real
per-relay-type toggle rows in `RelayEditDialog.kt` (Read/Write/DM/Search/Index — the sixth "AUTH
NIP-42" row is a disabled switch mirroring DM for display, not an independent flag, so it shares
the DM explanation rather than getting its own). New `relay_help_*_body` strings in `strings.xml`
(a `relay_info_*` prefix was already taken by unrelated NIP-11 fetch-status strings).
**Completed:** 2026-08-22
**From:** TODO LOG-15

### LOG-16 — Default NIP-77 sync direction to download-only; drop redundant checkmark
- **Status:** backlog
- **Added:** 2026-08-22
- **Why:** a fresh/untouched sync-direction setting shouldn't silently start publishing (uploading)
  a user's local relay set to a relay without them opting in first; the segmented-button's
  selection checkmark was redundant since color already indicates the selected option.

Changed the default from `SyncDirection.BOTH` to `SyncDirection.DOWNLOAD_ONLY` in the three places
that actually assign it: `SyncPreferencesImpl.loadDirection()`'s fallback (the authoritative one —
what a fresh install/untouched setting resolves to), `RelayConfigState.negentropySyncDirection`'s
initial value, and `NegentropySyncOrchestrator.sync()`'s `direction` parameter default. Updated
three `NegentropySyncOrchestratorTest` cases that relied on the old default to publish (upload) —
they now pass `direction = SyncDirection.BOTH` explicitly, matching their actual intent of testing
push/publish behavior rather than the default. Also added `icon = {}` to `NegentropySyncCard`'s
`SegmentedButton` to suppress Material3's default active-state checkmark, leaving color-based
selection indication untouched.
**Completed:** 2026-08-22
**From:** TODO LOG-16

### LOG-25 — FeedScreen's logout flow swallowed exceptions with an empty catch block
- **Status:** fixed (validated by shipping — this entry retroactively closes the gap where
  an earlier cleanup fixed it ad hoc without filing a LOG-N entry first)
- **Found:** 2026-08-29
- **Where:** `ui/feed/FeedScreen.kt` (the logout flow's try/catch around `loginViewModel.logout()`)

The logout flow previously discarded any exception from `loginViewModel.logout()` with an
empty catch block, then navigated to the login screen unconditionally — a failed logout (e.g.
a database wipe leaving stale key material behind) was indistinguishable from a successful
one, with no record anywhere that it happened. The fix still navigates to the login screen
either way (there's no in-app state left to usefully retry from), but now logs the throwable
via the project's scrubbed logging utility so a failure is at least visible in a debug build's
logcat.
**Completed:** 2026-08-29
**Fix:** applied in the logout flow
