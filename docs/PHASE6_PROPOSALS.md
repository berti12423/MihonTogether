# Phase 6 proposals (2026-10-02)

Short design notes for the feature gaps. Each one, once approved, gets its own `feat/` branch with tests, a rules audit if rules change, and doc updates.

## 1. Activity feed (opt-in recent history)

- **Data**: `feeds/{uid}/items/{itemId}` = `{kind: "chapter_read"|"favorite_added", content: Content, chapterLabel?, generation, createdAt, expiresAt}`. Max 50 per user (oldest dropped in the same transaction); `expiresAt = createdAt + 7d` (Firestore TTL policy in production, read-side filter everywhere).
- **Callable**: `socialPublishFeed {operationId, generation, kind, content, chapterLabel|null}`. Same validation as `socialPublishActivity`; rejected when the generation is stale, the content is hidden, or sharing is private. Rate limit: 1 `chapter_read` per content per 10 min.
- **Sharing**: new category `feed` (default `private`). Toggling it bumps `generation`, so old items become unreadable (same mechanism as library).
- **Rules**: friends may read when `shares(uid,'feed') && !invisible && item.generation == sharing.generation`; owner reads own. No client writes.
- **Android**: a "Recent" section in friend detail (last 20, relative time) and a "Share recent activity" switch. Publishing hooks: chapter marked read (reader) and social favorite added. Incognito, hidden titles and invisible mode suppress publishing.
- **Privacy impact**: new history beyond "current activity"; bounded, expiring, opt-in, generation-fenced.

## 2. Notifications (FCM)

- **Data**: `devices/{uid}/tokens/{tokenHash}` (owner-only) + per-user toggles in `notificationSettings/{uid}`: friend requests, read-together invites, friend came online (off by default).
- **Backend**: Firestore triggers on `friendRequests` create and `roomInvites` create; the presence reconciler sends "came online" only when the friend shares presence, isn't invisible, the recipient opted in, and not more than once per friend per 6 h. Payload: notification text only, no content keys.
- **Android**: `FirebaseMessagingService` (debug + release), POST_NOTIFICATIONS permission, settings toggles.
- **Blocker**: FCM doesn't run in the Firebase emulators and the test AVD image has no Google Play services. Only the backend triggers can be tested locally. **Recommend: build after Phase 7 creates the real project.**

## 3. Friends list UX (pure client, no backend change)

- Online-first sorting, a "Reading now: <title>" badge, last seen ("5 min ago"), pull-to-refresh, empty/loading/error states.
- Fixes from the two-device run: invitations at the top naming the inviter ("Alice invited you to read X"), "Select room" feedback, the whole privacy row toggles, and an explanation when a room ends because of friend removal.
- From a friend's library/favorite title: open my matching local title (exact source + URL path match, never title) or fall back to a global search for the title.
- **Privacy impact**: none (reads only what is already shared).

## 4. Restore hidden titles

- **Callable**: extend `socialUpdateSharing` with `unhideContentKey` (mirrors the existing `hideContentKey`), or add `socialUnhide {operationId, contentKey}`. Deletes `hiddenTitles/{uid}/entries/{key}`; does **not** republish (the user shares again deliberately).
- **Android**: a "Hidden from friends" list in Friends with Restore buttons.
- **Rules**: unchanged (owner-read only already).
- **Privacy impact**: none; explicit user action.

## 5. Google sign-in

- Credential Manager + `GoogleIdTokenCredential` → `signInWithCredential`. Account linking for existing email/password users.
- **Blocker**: needs a real Firebase/Google Cloud OAuth client and SHA-1, and the AVD has no Play services. The Auth emulator can fake it in a unit/emulator test only. **Recommend: after Phase 7.**

## 6. Rooms with more than 2 people

Design doc only for now: the protocol assumes exactly one guest (`guestUid`, ACL = host and guest friendship). More than 2 needs a member list, a pairwise-friendship or invite-chain ACL decision, per-member leave, and quota changes. **Recommend: write `docs/ROOMS_MULTI.md` only.**

## 7. Room chat / reactions

- **Data**: `rooms/{roomId}/messages/{id}` = `{senderUid, kind: "text"|"reaction", text (≤200 chars, plain), emoji (allow-list), createdAt}`; at most 100 kept per room, deleted when the room closes.
- **Callable**: `socialRoomMessage {operationId, roomId, kind, text|emoji}`. Requires a live lease and accepted friendship; rate limit 1 message/s and 30/min per user.
- **Rules**: room participants read only while the room is readable (same as the room doc).
- **Android**: a small chat sheet in the Read together dialog, plus a reaction strip.
- **Privacy impact**: user-generated text, kept only for the room's lifetime.

## 8. Web integration (added 2026-10-02)

Browsers can't run Mihon source extensions, so a web client can't be a full reader. It reuses the same Firebase backend and rules. Proposed steps:

- **W1:** privacy policy and account-deletion pages. Play requires a web deletion link.
- **W2:** invite links (`/add/<handle>`) that open the app.
- **W3:** a read-mostly web companion for friends, status, shared library, feed and privacy settings.
- **W4:** optional spectator chat in a friend's room, without pages.

Needs web App Check (reCAPTCHA Enterprise) and an authorized Hosting domain; no rules changes. Full design: [WEB_INTEGRATION.md](WEB_INTEGRATION.md).

## Recommended order

3 → 4 → 1 → 7 now; 6 as a design doc; 2 and 5 after the real Firebase project (Phase 7).

## Decision (Berti, 2026-10-02)

Build 3 → 1 → 7 → 4. For 7, reactions also pop out from the side of the reader screen (Clash Royale-style emote bubbles). Defer 2 and 5 to after Phase 7; write the design doc for 6 only.
