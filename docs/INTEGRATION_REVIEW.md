# Independent client/backend integration review

Reviewed 2026-10-01. Scope: Friends/Auth/presence/repository code written by the platform agent, callable domain/server adapter, Firestore Rules and RTDB Rules. The reviewer implemented the reader and excluded that implementation from this independent review. This pass was static; root owns compilation and emulator verification.

## Findings sent to the implementation owner

- **P1 — SDK completion waits blocked privacy cancellation: implementation fixed, regression tests passed.** The repository originally awaited activity callables and room transactions inside `NonCancellable`. Cancelling an observing coroutine therefore waited for the entire SDK operation before privacy fencing/room termination could begin, and a five-second cleanup timeout could not bound the nested wait. The owner replaced this with cancellable waits and a completion-listener-owned gate. JVM tests were added for prompt caller cancellation, serialization until real completion, independent revocation and startup failure release. The actor is rechecked after acquiring the gate, before sending the request.
- **P2 — Known friendship revocation retained visible data.** The accepted-edge collector originally cleared a revoked selected friend only after fetching all remaining profiles. It also retained the revoked friend's rooms. The owner now clears selected friend/rooms and the selected room handoff before any suspending profile fetch. This should be checked with one revoked friend plus a deliberately stalled remaining-profile fetch.
- **P2 — Logout starts RTDB cleanup after dropping authentication: implementation fixed, device validation pending.** `FriendsViewModel.signOut` originally cleared sharing/activity and then called Firebase Auth sign-out. The owner added `stopPresenceBeforeSignOut`: pause the process presence owner, cancel its registered job and await bounded cleanup while the old token remains valid. Removal precedes cancellation of the armed disconnect handler; errors/timeouts retain the handler and expose pending feedback. Logout and account-switch behavior still require emulator/device validation. The disconnect fallback must not be assumed to bypass a failed authorization check.
- **P2 — Source-incognito library revocation was owned by the Friends screen: implementation fixed, regression test passed.** Source observers originally existed only while that screen was STARTED and fenced only an observed false-to-true transition. The owner added an application-foreground `SocialProjectionGuard`, observing current library sources and global/extension incognito. It blocks entry publication before privacy validation, fences newly private/initially shared private sources, and persists only successful acknowledgements. `SourcePrivacyFence` reconciles acknowledgement intersection on every emission, including the A/B public-then-private cycle identified by root. JVM tests were added; actual source-toggle/device behavior remains to be exercised.

Line locations may move during formatting. The relevant symbols are `SocialRepository.publishActivity`, `publishPosition`, `awaitSerialTask`, `FriendsViewModel.observe` accepted-edge/source observers, `FriendsViewModel.signOut`, and `SocialRepository.maintainConnection`.

The new [completion-gate regression tests](../app/src/test/java/mihon/feature/social/CompletionOwnedGateTest.kt) exercise cancellation and real operation ownership. The [source privacy fence tests](../app/src/test/java/mihon/feature/social/SourcePrivacyFenceTest.kt) exercise initial/offline acknowledgement and the A/B reprivatization cycle. Both passed in the verified native run: completion gate 2 tests and source privacy fence 1 test. The final native run included 91 passing tests overall, including the isolated callable protocol tests. See [native verification](NATIVE_BUILD_VERIFICATION.md).

Root's follow-up scan found an additional source-incognito gap: removing the last local title stopped observing that source while a manually published server snapshot could remain. The owner added persistent account-scoped source history, saved before publication, so source observers survive local removal and process restart. Four [source history regressions](../app/src/test/java/mihon/feature/social/ProjectionSourceHistoryTest.kt) cover removal/revocation, restored empty-library history, reprivatization/acknowledgement across restart and malformed IDs. Their execution is pending the latest native run.

## Rules and trusted domain assessment

No new stranger-read, guest-position-write, friendship-forgery or ownership-update bypass was found statically. Firestore denies all client writes except the host position update. That path derives authority from the existing room/lease, enforces live reciprocal friendship and both block directions, freezes membership/epoch/content, validates the entire resulting room and bounded position, advances sequence once, and requires a server timestamp. Callable mutations derive the actor from Auth and reject extra fields; trusted Admin writes do not depend on client Rules for validation. RTDB connection nodes are owner-private and never authorize Firestore reads.

The remaining Rules audit finding is resource exhaustion: an owner may create unlimited UUID connection nodes, each triggering a full read of that owner's connection branch, and several authenticated callable mutations can create unlimited idempotency records. Per-record bounds and `expiresAt` fields do not impose an aggregate quota or install a deletion policy. The existing release notes already defer production abuse tuning and retention; these should be closed before exposing production traffic.

## Evidence and limits

The cancellation reasoning follows [Kotlin Task await](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-play-services/kotlinx.coroutines.tasks/await.html) and [NonCancellable](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-non-cancellable/). RTDB disconnect operations are authorized at registration and checked again when executed, as documented in [Android offline and presence behavior](https://firebase.google.com/docs/database/android/offline-capabilities#how_ondisconnect_works).

An emulator observation reported by root showed that existing authorized listen targets may retain prior data after an ACL dependency changes, so a fresh `Source.SERVER` read is insufficient as the sole client revocation mechanism. Explicit own-edge cancellation/clearing is required. This review does not claim that an authorization rule can erase data a recipient already viewed or that every listener fails immediately. Full Android source/incognito, friend-revocation and account-switch lifecycle checks remain outstanding.

## Rules audit: recent-activity feed (2026-10-02)

Change: one new read rule, `feeds/{uid}/items/{slot}`. No client writes were added; the catch-all `allow read, write: if false` still covers `feedState` and all writes.

| Question | Finding |
| --- | --- |
| Who can read? | The owner, or an accepted, unblocked friend (`projection` → `shares` → `friends`) while `feed == 'friends'`, the owner is not invisible, `generation == sharing.generation`, and `expiresAt > request.time`. |
| Can a stale generation leak? | No. Every settings change bumps the generation, and old items fail the equality check (emulator test: a query with the old generation is denied). |
| Can expired items leak through queries? | Only if the query proves expiry. A bound in the past is denied; clients use now+1h. Expired documents remain stored until overwritten or removed by TTL. |
| Missing field on older settings | `sharing(uid)['feed']` errors, so the read is denied (fail-closed). Callables and Android treat absent as private. |
| Invisible mode | Rule denies reads, the callable rejects publishing, `getFriend` reports feed private, and the toggle bumps the generation. |
| Enumeration / cost | Reads are bounded by `limit(20)` and a 50-slot ring. Publishing writes 2 docs and reads at most 4 per call; dedupe is 10 minutes per identical item. There is no aggregate per-user rate limit beyond the callable itself; this is tracked with the Phase 7 quota work. |
| Content | Same validated metadata projection (`Content`) as library entries; no URLs, images or local IDs. |

Tests: domain (consent, generation, invisible, hidden, kind/label validation, dedupe, ring bound) and the emulator rules scenario "feed items are readable only by friends, for the current generation, unexpired and while visible".

## Rules audit: room messages (2026-10-02)

Change: one new read rule, `rooms/{roomId}/messages/{slot}`, which allows reads if `activeRoom(get(rooms/roomId).data)`. That is the same predicate as the room document: participant, `invited|active` state, unexpired lease, and reciprocal friendship without blocks. No client writes; `roomChat` and `roomChatRates` fall under the deny-all rule. Because the rule doesn't depend on `resource`, list queries are provable. After close or leave, reads are denied and the documents are deleted. After friend removal or lease expiry, reads are denied immediately; the documents remain until the room is closed, since a close by a no-longer-friend is refused by the friend ACL, so leftover messages from rooms ended by unfriending need the Phase 7 cleanup/TTL job.

Cost and abuse: per-sender gap and per-minute cap, 100-slot bound per room, and an existing room-creation limit of 20/hour. Clearing a room is 100 blind deletes plus 3, inside Firestore's 500-write transaction limit. Tests: domain (validation, ACL, rate limits, ring, clear on close) and the emulator rules scenario `room messages are readable only by participants of a live room and never client-writable`.


## Rules audit: daily stats (2026-10-02)

New read rule `dailyStats/{uid}/days/{day}`: owner, or `projection(uid,'stats') && !invisible` (friendship, consent, current generation). No client writes. Each document reveals only two counters for one day, never titles. Writes go through the callable, with a 10 s gap, a 300/day cap and the global daily quota. Account deletion removes the `dailyStats/{uid}` tree. Tests: domain (consent, gap, day roll-over, generation reset, invisible) and the emulator scenario `daily stats are readable by friends...`.


## Rules audit: Cloud Storage profile media (2026-10-02)

New file `backend/storage.rules`. Reads of `profileMedia/{uid}/{kind}` are allowed for the owner or an accepted friend; this is checked with `firestore.exists` on both friendship edges, the same edges the Firestore rules use, so removing or blocking a friend revokes media immediately (emulator test). Writes are owner-only, `kind` must be avatar or banner, `contentType` must be image/(png|jpeg|webp|gif) and the size must be within 2 MB or 5 MB. Owner-only delete. Everything else is denied. The style callable re-checks stored metadata, so a profile can't reference a missing or oversized object. Images are served through authenticated SDK reads, not public download URLs. Account deletion removes the `profileMedia/{uid}/` prefix; that path is not covered by an automated test. Residual risk: friends can see any image a user uploads; there is no content moderation, and the friend-only audience limits exposure.

