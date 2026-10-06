# Roadmap: Berti's feedback round (2026-10-03)

Eight requests from testing the online build, grouped into phases that build on each other. Each phase is one or two `feat/` branches, merged locally after its verification passes.

**Decided by Berti (2026-10-03):** group rooms hold up to **6**; when the host leaves, the room **passes to the longest-present member and announces the new host** in the chat; the global ranking is **on for everyone by default** with an opt-out; private messages are kept **30 days**. Items still open are marked **(decide)**.

## The order, at a glance

| # | Phase | Requests covered | Size | Depends on |
| --- | --- | --- | --- | --- |
| F1 | Discord fix | Discord didn't work | Small | Berti's phone (logcat) |
| F2 | Ranking rework | Chapters only, global ranking, tap a user for their profile or to add them | Medium | — |
| F3 | Friend library grid | Big friend libraries look bad | Small–medium | — |
| F4 | Notifications (FCM) | Room invites, friend requests | Medium | Blaze (already on), no extra cost |
| F5 | Private messages | Friend-to-friend chat | Medium | F4 for push |
| F6 | Group rooms | More than 2 people, see who's in the room and who wrote each message | Medium–large | F4 for invites |
| F7 | Join requests | Ask to read along with a friend | Small | F6, F4 |
| F8 | Web library sync | Web shows and searches your synced library | Medium | F3 (shared grid ideas), F2 profile |
| F9 | Missing extension guide | Joining a room tells you which extension you need, offers to install it, then enters the room | Medium | F6 |

Why this order: F1–F3 are quick wins that fix what you saw. F4 (push) is plumbing that F5–F7 all need, so it comes before them. Group rooms (F6) change the room schema, so join requests (F7) and the web room view build on the new shape rather than the two-person one.

---

## F1. Discord status that actually shows

**What's likely wrong** (from reading the code, before seeing your phone's log):
1. **The status clears when the reader goes to the background.** If you opened Discord on the same phone to check, Mihon Together had just stopped, so it cleared the status. Your friends would have seen it while you read, but you never could.
2. **RPC on Android may need the Discord app's own approval.** The "no login" Rich Presence talks to the Discord app over RPC. If the Discord app refuses (not up to date, the app not approved for the Social SDK, or mobile RPC limits), our log level only prints warnings, so nothing obvious appears.
3. The SDK only loads when you open the reader **after** turning Discord on. That's correct, but a "Test" button would make it visible.

**Plan**
- A **"Send test status"** button in Settings → Discord, with a status line: *SDK loaded → connected to Discord → status sent ✓* or the exact error.
- Log at Info level, and keep the last error in memory to show it on that screen.
- Keep the status for a **grace period of 10 minutes** after leaving the reader or switching apps, so you can check it in Discord and quick app switches don't flicker it. It still clears at once when you close the chapter, turn the feature off, or the source stops being allowed.
- If the log shows mobile RPC is refused: **fallback to official account linking** (the Social SDK's "Link Discord" with Discord's own authorization screen; still no tokens stored by us). That brings back the SDK's login activity we stripped from the manifest.

**Verify:** unit tests for the grace period; on your phone, the test button shows "status sent" and a friend sees it on your profile.

**Status (2026-10-03): built** on `feat/discord-diagnostics`. Settings → Discord has "Send test status" with a live result line: not installed, Discord too old (no RPC service), sent/waiting, shown, or Discord's own error text (from `ClientResult::ToString` when the message is empty). The reader clears the status as soon as it leaves the foreground (changed 2026-10-03 after a beta report: the 10-minute grace timer never ran while Android froze the app), the SDK keeps pumping while a status is shown, and turning Discord off or changing the allowed sources clears at once. Emulator (no Discord app): the button reports "not installed". Still needs a run on a phone with Discord.

## F2. Ranking: chapters only, global board, tappable users

**Today:** "Today's ranking" among friends shows chapters and pages; rows aren't tappable.

**Plan**
- Show **chapters only** (pages are dropped from the label; still stored for streaks).
- Two tabs: **Friends** and **Global**.
- **Global ranking shows your name to people who aren't your friends.** Berti chose **on by default**, so this is the one sharing switch that starts on, an explicit exception to "sharing starts private". To keep that fair: an **opt-out** switch in Sharing settings ("Appear on the global ranking"), a one-time notice the first time you open the app after the update (with a direct link to that switch), invisible mode also hides you, and only name, handle, avatar style and chapter count are ever shown.
- The backend keeps a `leaderboards/{day}` document with the top 50, updated from `socialRecordChapter` (one write per chapter, already happening for daily stats). Only users who haven't opted out, only display name, handle, avatar style and chapter count.
- **Tap any row** → a profile sheet: for friends, their full profile (today's screen). For non-friends, a **public card** (name, handle, today's chapters, streak) with **"Add friend"**. Nothing else about a non-friend is readable; the rules stay friend-only for everything else.
- **(decide)** Global = all opted-in users of the app (default) or also a weekly board.

**Status (2026-10-03): built** on `feat/global-ranking`. Backend 32 domain + 19 emulator tests pass; Android social unit tests and `assembleDebug` pass. Emulator UI check with seeded accounts (`backend/scripts/seed-emulator.cjs`): notice shown, Friends/Global tabs, a stranger's public card, "Add friend" sent a request, tapping a friend opened their profile.

**Verify:** domain tests (default on, opt-out removes you at once, invisible hides you, top-50 trim, day rollover), rules emulator tests (non-friends can read only the leaderboard), Compose check of both tabs.

## F3. Friend library like Mihon's library

**Today:** a long text list with a "Search sources" link per row (your screenshot).

**Plan**
- A **cover grid** (same comfortable-grid look as Mihon's library), with a list/grid toggle.
- **Search box**, **sort** (title, recently added) and a **"In my library" badge** for titles you also have.
- Tapping a cover opens a sheet: title, source name, "Open in my library" if you have it, otherwise "Search sources".
- Covers: the shared projection doesn't include covers today. Add an optional **`coverUrl`** (https only, length-capped), plus a sharing switch "Share covers" (on by default when library sharing is on). Local or custom covers are never shared.
- Paging: load 60 at a time instead of everything at once (fewer Firestore reads on big libraries).

**Status (2026-10-03): built** on `feat/friend-library-grid`. `content.coverUrl` is optional everywhere (https only, 9–500 chars, validated in domain and rules). The profile shows a cover strip of the first 12 titles A–Z and the count (aggregate query); **See all** opens a Mihon-style comfortable grid that pages 60 at a time A–Z (index `entries: generation + content.title`); search or "Not in my library" loads the rest. The 15 s full-library reload is gone (preview refresh every 5 minutes). "Share covers" is a device switch next to Publish. Backend 33 domain + 20 emulator tests pass; emulator UI check with a seeded 130-title library (strip, count, grid paging, search loading all pages, back navigation). Needs the backend deploy before phones on the new APK can publish covers.

**Verify:** validation tests for `coverUrl`, rules test, UI check with 300+ entries on the emulator.

## F4. Notifications (Firebase Cloud Messaging)

**Plan**
- Add FCM (free on every plan). Each device stores its token at `deviceTokens/{uid}/tokens/{id}`, which only the owner can write.
- The backend sends a push for: **room invite**, **friend request**, **friend request accepted**, **new private message** (F5), **join request** (F7).
- **Notification settings** with one switch per type; Android 13+ asks for the notification permission the first time it's needed.
- Push content is minimal: "Shikori1 invited you to read together". The title being read is shown only if that friend shares activity with you.
- Tapping opens the right screen (room, request, chat).

**Status (2026-10-03): built** on `feat/push-notifications`. Backend: `socialRegisterDevice {action: register|unregister, token}` keeps the newest 10 tokens in owner-private `devices/{uid}` (deleted with the account); mutations queue pushes that are sent only after commit and never on replays (friend request, request accepted, room invite; messages and join requests come with F5/F7). The FCM sender drops tokens FCM reports as gone; the emulator only logs. Android: `firebase-messaging` bound to the social Firebase app (sender ID from the app ID), auto-init off, tokens registered on foreground in production builds only and unregistered on sign-out; `SocialMessagingService` builds localized texts, honours per-kind switches and opens Friends; a "Friends and Read together" channel; Notifications section with the Android 13 permission prompt. Backend 35 domain + 20 emulator tests, Android social tests (incl. push parsing and kind drift) and `assembleDebug` pass; emulator UI check of the settings and permission flow. **Not verified:** a real push (FCM isn't emulated); needs the production deploy and a phone.

**Verify:** backend tests with a fake messaging sender; on the emulator, a real push arrives and opens the right screen.

## F5. Private messages

**Plan**
- 1:1 chat between **accepted friends only**, opened from the friend's profile and a new **Messages** list.
- `chats/{pairId}/messages/{id}`, written through a callable (rate-limited, 500 characters, text only). Rules: only the two friends can read. Unfriending or blocking hides the chat at once (same revocation signal the rooms use).
- Messages expire after **30 days** via the existing TTL policy, to keep storage and reads small.
- Unread badge on the Friends tab; push via F4.

**Status (2026-10-03): built** on `feat/private-messages`. `socialMessage {action: send|read, otherUid, text}`: friends only, single-line text up to 500, 1 s gap and 30/min per sender, `chats/{pairId}/messages/{seq}` plus a per-person `chatIndex/{uid}/chats/{other}` with preview and unread count; everything carries `expiresAt` 30 days (TTL on `chats`, `messages`, `users` groups). Unfriending/blocking deletes both list entries, and rules need the live friendship to read messages. Push `message` with the sender's name and a 100-character preview. Android: Messages section on Friends, Message button on profiles, chat view with live updates, read-clearing while open, "N unread messages" on More → Friends. Backend 36 domain + 21 emulator tests, Android social tests and `assembleDebug` pass; emulator run: Alice sent from the app, Bob (script, real callables) saw unread 1 and replied, the reply appeared live, the list and More badges showed the next unread message.

**Verify:** domain and rules tests (non-friend can't read or write, block hides), UI check.

## F6. Group rooms, with a member list

Design already in [ROOMS_MULTI.md](ROOMS_MULTI.md); summary with your requests added:
- Everyone in a room must be **friends with the host**; joining shows who's there and asks for consent.
- Cap: **6 people**.
- Everyone follows the host; the host can invite, remove, and hand over the host role. When the host leaves, the **longest-present member becomes host** and the room chat shows "X is now the host".
- **Member list** in the room panel: avatars, names, who's following, who's on a different chapter.
- **Every chat message and reaction shows the sender's name and avatar color** (today they're unlabeled because there were only two of you).
- Existing two-person rooms keep working (schema version 1 stays readable until it expires).

**Status (2026-10-03): built** on `feat/group-rooms`. Backend: room schema v2 (`participants`, server-written `members` name/state snapshot, `maxMembers` 6, no `guestUid`), actions `invite` and `kick` (host), `join`, `leave` (member: removed; host: hand-off to the longest-present joined member with a fresh unclaimed lease, epoch + 1, non-friends of the new host removed; nobody left: close), `heartbeat` (claims an unclaimed lease, prunes members who stopped being the host's friends), member `close` = leave; server chat events `joined/left/removed/host`. Rules: readable by the host and by participants who are the host's friends. Android: coordinator resyncs on a hand-off epoch instead of ending, claims the lease when it becomes host, invites into the current room, kick; reader panel lists members (host/invited tags, Remove for the host), "Invite more friends", named chat lines and pop-ups, localized event lines; Friends room card shows "N of 6 people" and a consent line naming everyone before joining; Discord party shows the real count. Backend 39 domain + 22 emulator tests and the full Android suite (151 tests, spotless, SQLDelight, assembleDebug) pass. Emulator run: scripted Bob hosts and invites Carol and Alice; Alice (app) saw the group invite with the consent line, joined ("with Bob Smoke and Carol Smoke"), Bob left and Carol became host live, and the reader panel showed members, event lines and Carol's named message.

**Verify:** domain and rules emulator tests (non-member can't read, cap enforced, kick revokes), coordinator unit tests, a 3-emulator run.

## F7. Request to join

**Plan**
- On a friend's profile or "Reading now" row: **"Ask to read along"** (shown only if they share activity with you).
- The host gets a push and an in-app prompt: **Accept / Decline**. Accept sends a normal invite, so the consent step is the same as F6.
- Limits: one pending request per friend, expires after 10 minutes, at most 10 requests an hour.

**Status (2026-10-03): built** on `feat/join-requests`. `socialJoinRequest {action: ask|decline, otherUid}`: asking needs a friend who shares activity, isn't invisible and has a live activity; one pending request per pair (`joinRequests/{friend}/items/{me}`, 10-minute TTL), 10 asks an hour, `join_request` push; inviting (create or invite) answers it, unfriending deletes it. Android: "Ask to read along" on a friend's profile while they read; a prompt over the reader ("Bob asks to read along" · Invite / Not now) that invites into my room or a new one from the current chapter; a "Want to read with you" list on Friends (Invite there when I host a room, otherwise a hint to open a chapter). Backend 41 domain + 23 emulator tests, Android social tests and `assembleDebug` pass; emulator run: Alice shared activity and opened a chapter, scripted Bob asked, the prompt appeared in Alice's reader, Invite created a room with Alice hosting and Bob invited.

**Verify:** domain tests (expiry, rate limit, non-friend refused), an end-to-end emulator run.

## F8. Web: your library and the new social features

The web can't load pages (sources are Android extensions), so "sync with library" means the web **shows and searches your library as synced from the phone**, and links out.

**Plan**
- **My library** on the web: the same cover grid as F3, from your own shared projection (always readable by you). Search, sort, categories if we add them to the projection. **(decide)** whether you want edits from the web (for example remove from library), which would sync back to the phone on next launch.
- **Friends' libraries** with the same grid.
- **Ranking** (F2), **messages** (F5) and **join requests** (F7) on the web.
- **Room chat from the browser** (W4 in [WEB_INTEGRATION.md](WEB_INTEGRATION.md)): join a room as a chat-only member once F6 lands.
- Web App Check (reCAPTCHA Enterprise) must be set up first, or production callables reject the web. **Needs your OK** (it has a free tier of 10k checks a month).

**Status (2026-10-03): built** on `feat/web-social` (read-only library, no weekly board, as defaulted). New `web/src/api-social.ts`: paged A–Z cover libraries with counts, the global board, chats, read-along requests, and rooms as a chat-only member (join, leave, text and reactions; pages and following stay in the app). `main.ts`: Friends/Global ranking tabs with public cards and Add friend, rooms and read-along requests on the home page (requests accepted in the app), **Library** page (my shared library as a grid), friends' library/favorites strips with **See all** grids (scroll paging, search loads the rest), **Messages** with an unread count in the nav and live chats, Message and Ask to read along on profiles, the global-ranking switch in Privacy. CSP allows `https:` images for covers. Web tests (8, incl. the reaction-list drift check) and `tsc` pass; browser run against the emulators: group invite with consent line, joining and chatting in the room, reading and answering Bob's message (unread cleared), Bob's 130-title library as a grid (60 loaded, covers shown), no console errors.

**Verify:** web logic tests, browser run against the emulators, then a production deploy after you approve it.

## F9. "You need this extension" when joining a room

Added by Berti on 2026-10-03.

**Today:** if you don't have the host's source, the room only says the title isn't in your library.

**Plan**
- The room shares the host's **source name, language and extension package name** (for example `eu.kanade.tachiyomi.extension.en.mangadex`), never the host's repo list or other extensions. The host can switch this off along with the title.
- When you accept an invite (or open the room) the app checks: is that extension installed? Is it in one of **your** added extension repos?
  - **Installed:** jump straight to the title and chapter (today's room guidance).
  - **Available in your repos:** a prompt "Install MangaDex (EN) to read with Berti?" with **Install and join**. It uses Mihon's normal extension installer (the system install prompt still appears; Android requires it), then trusts it if needed, opens the title through the source, adds nothing to your library unless you choose to, and enters the room.
  - **Not in your repos:** shows the extension name and language and explains that you need to add a repo that has it. The app never adds a repo by itself, since repos run code on your phone.
- If the host's title isn't found by ID in the newly installed source, it falls back to a search by title in that source.

**Status (2026-10-03): built** on `feat/room-source-guide`. Content gains an optional `source {id, name, lang, pkg}` (validated in domain and rules); the host's app fills it only when creating a room (`describeSource`: source name and language from the source manager, package from the loaded extension; local sources share nothing). On Friends, a joined room whose title isn't in my library shows: **Search in X** (installed: opens that source's search for the title), **Install X and join** (in my extension stores: Mihon's installer, then the source search), **Trust extension** (installed but untrusted), or the extension's name and language with a hint to add a store (never added automatically); older rooms without `source` keep **Find it**. Backend 42 domain + 23 emulator tests and the full Android suite (154 tests, spotless, SQLDelight, assembleDebug) pass. Emulator run: a room naming a non-existent source showed the "add a store" hint; a MangaDex room showed **Search in MangaDex**, which opened MangaDex's search for the title. **Not run:** the install path on a device (needs a store and the system install prompt); its decision logic is unit-tested.

**Verify:** unit tests for the decision (installed / installable / missing), an emulator run installing an extension from a test repo and entering the room.

---

## Costs and privacy summary

- Firestore: the leaderboard adds one write per chapter read; chats and push tokens are small. Still far inside the free tier (50k reads and 20k writes per day) at your scale.
- FCM is free; reCAPTCHA Enterprise has a free tier.
- New things visible to **non-friends**: the global ranking entry (name, handle, today's chapters, streak) unless you opt out. Everything else stays friends-only.
- Every new sharing switch starts off, except the global ranking (Berti's choice, with a notice) and "Share covers", which follows library sharing.

## Things I'll need from you

- F1: your phone's log while testing Discord (I can read it over USB with adb if the phone is connected), or a screenshot of the new test button's result.
- F4/F8: deploying the new functions and rules to production, and enabling App Check for web.
- The remaining **(decide)** items: a weekly board (F2) and web edits to the library (F8). I'll default to no weekly board and read-only web until you say otherwise.
