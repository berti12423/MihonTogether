# Multiplayer implementation checkpoint

## Current state (2026-10-03)

- **Production:** Firebase project `mihontogether` (europe-west1) is live: Functions, Firestore rules and indexes, RTDB and Hosting at https://mihontogether.web.app (with `/download` serving the arm64 APK). App Check is off for the sideloaded test builds. The newest work below (free translation, group chats, XP, the `dailyStats` expiry) is **merged locally but not deployed yet**: it needs a functions, rules, indexes and hosting deploy plus a new APK, both waiting for Berti's approval.
- **Built and verified locally:**
  - Friends, privacy generations, presence, profiles, feed, the daily and global ranking, and streaks.
  - Account deletion; FCM pushes; private messages (30 days); group reading rooms up to 6 with host hand-off; "ask to read along"; room source and extension guide; web companion (library grids, ranking, messages, room chat, AniList covers); Discord status card.
  - Translation with four engines (free on-device by default).
  - **Group chats (up to 20) and XP and levels.**
  - **Achievements, weekly XP ranking, streak freezes; room ready check (Ready / Wait for me); recommending titles in chats.** These came from the sub-agent brainstorm, and an independent code review's fixes are applied.
- **Verification (2026-10-03, `main` at the `feat/achievements` merge):**
  - backend 47 domain tests and 25 emulator rules scenarios;
  - web 11 tests plus typecheck;
  - Android spotless, 171 unit tests, migrations and `assembleDebug`;
  - emulator UI: on-device OCR and translation of the Korean test chapter; group create, live messages, group info; XP card with achievements; the weekly tab; a recommended title card with its cover, opened from the chat.
- **Not verified yet:**
  - the web group chat, badges and title cards in a browser (typechecked and unit-tested only);
  - on a device: the level-up and achievement toasts, the reading-together bonus, and the room ready check;
  - DeepL and Papago against the live services;
  - a real push for group messages.
- **Berti's phone checks (2026-10-03):** the Discord card and button, push notifications, group rooms on several phones, and "install extension and join" all work. Gemini translation worked but used up the free quota right away, which led to the free on-device engine.
- **Before a public release:** final app name and ID, release signing key, App Check on, and a privacy policy contact email (the [policy draft](PRIVACY_POLICY.md) is otherwise up to date).

- **Open rooms + room positions (2026-10-06, branch `feat/open-rooms-positions`, not deployed):** hosts can let friends drop in (`open` flag, an `openRooms/{host}` card for friends who see their activity, closed by any privacy change or a hand-off; drop-in join for friends of the host who aren't blocked by any member, room not full). Joined members share chapter + page with the room (`position` action, 3 s server gap, own daily quota), and the reader's page slider shows the other members on my chapter while I'm not following. The Friends tab and web list friends' open rooms with Join. Verified: backend 75 domain tests, emulator suite 31/31 (alternate ports), web typecheck + 24 tests, Android spotless + app unit tests (219) + migrations + `assembleDebug`. Not verified on a device: marker positions on the slider, especially the vertical navigator, are unchecked. **Deploy the backend (functions + rules) before shipping an app with these features.**
- **Review fixes (2026-10-06, branch `fix/review-2026-10-06`, not deployed):** hiding a title deletes my reviews of it; `sharingSettings.progress` ("Share reading progress", absent = on; the server drops progress while off; no progress for incognito sources, no quiet refresh in global incognito); ranking pictures only while actually on `leaderboards/alltime` and not for people the owner blocked; group quotes from before I joined or by blocked people show as "Earlier message", blocked people's reactions left out (app and web); "friends who read this" cached 5 minutes, reloads while watched and on foreground, drops what a friend stops sharing when seen on their profile, reuses known settings instead of a `socialGetFriend` per friend, loads in its own scope, caps 500 started titles / 100 reviews; title-only matches no longer delete another work's review; hidden titles not rateable; drafts kept until a send succeeds; reactions roll back without the send-failed line or overwriting newer snapshots; library tables hashed off the main thread; Discord recovery waits for a live Activity. Verified: backend 68 domain tests, emulator suite 30/30 (alternate ports), web typecheck + 23 tests, Android spotless + app unit tests (213) + migrations + `assembleDebug`. Not verified on a device. **Deploy the backend before shipping an app that turns progress off** (an older backend rejects the field; the app only sends it once stored or when off).

- **Invites that survive the install + recruiter badges (2026-10-06, branch `feat/invite-recruiter`, not deployed):** web invite page friend code with Copy (`mihontogether-invite:<handle>`); Android reads the clipboard once on an empty Friends tab, plus a friend-code field, and shows the inviter's card with a one-tap request (`via: "invite"`); server-only `invites/{newcomer}`, inviter credited after 5 newcomer chapters (+50 XP outside the daily cap, max 10/month), badges `recruit_1/3/10`. See [SOCIAL_API](SOCIAL_API.md#invites-and-recruiter-badges-2026-10-06). Domain and web tests cover it; the clipboard read and the card are not yet tried on a device.

Everything below is history from the first local build (2026-10-01 to 2026-10-02).

> **Latest (2026-10-02, end of autonomous session):** `main` passes `scripts/verify-all.ps1`: backend 29 domain + 18 emulator scenarios, web companion 5 tests + typecheck + build, Android spotless + 110 unit tests + migrations + assembleDebug. A final two-device regression (invite, follow, page sync, reactions) passed on the current APK (x86_64 SHA-256 `C6EACE3C0C3876BCA39493EDC5396C2B85A719A636A029A18FB86594865FC227`). Added today on top of the earlier work: account deletion, privacy policy draft, quotas and cleanup, daily ranking with streaks, customizable profiles (GIF avatar and banner), invite links, and the web companion. Still not done: a real Firebase project and deploy, physical-phone testing, FCM notifications, Google sign-in, rooms with more than 2 people.

Updated 2026-10-02. Re-verified on the current source, including the source-history privacy fix: backend 18 domain + 11 emulator tests pass; `spotlessCheck testDebugUnitTest verifySqlDelightMigration` passes with **95 tests** (the 4 `ProjectionSourceHistoryTest` regressions now ran and passed); `assembleDebug` passes; a single-device smoke on the new APK passed (account, profile, presence on/off, invisible, sign-out). See [native verification](NATIVE_BUILD_VERIFICATION.md) and [re-verify smoke](evidence/REVERIFY_SMOKE.md). Production deployment and two-device reader validation remain unfinished.

**Two-device read-together validated 2026-10-02** on two API 36 emulators with the MangaDex extension: invite/join, follow, page turns, chapter change, follow off/on, background/foreground, lease expiry, network loss on either side, friend removal mid-session, leave and host exit. Five bugs found and fixed (stale page on followed chapter change, follow re-enable not snapping, raw rules error on expiry, backend refusing leave/close of expired rooms, offline treated as friend removal) plus an emulator-only RTDB reconnect issue documented in setup. After the fixes: 98 app unit tests, backend 19 domain + 11 emulator scenarios pass. **After Phase 6 (friends UX, recent-activity feed, room chat/reactions, restore hidden titles): 106 app unit tests, backend 24 domain + 14 emulator scenarios pass** (2026-10-02). Rooms with more than two people are design-only ([ROOMS_MULTI.md](ROOMS_MULTI.md)). FCM notifications and Google sign-in are deferred until a real Firebase project exists. Details: [two-device evidence](evidence/TWO_DEVICE_READER.md).

The repository is now under local git (`main`, tag `checkpoint-codex-2026-10-01`); there is no GitHub remote. The "Executed evidence" table below is the 2026-10-01 checkpoint, kept for history.

## Implemented behavior

- **Streaks and invite links (2026-10-02)**: 🔥 reading streaks in the ranking (app and web); `mihontogether://add/<handle>` deep link pre-fills a friend request; **Share invite link** in Friends; the web invite page links into the app. Device-verified ([streak](evidence/streak-ranking.png), [deep link](evidence/invite-deeplink-prefill.png), [share sheet](evidence/invite-share-sheet.png)).

- **Web companion (2026-10-02)**: `web/` (TypeScript and esbuild, Firebase Hosting). Sign-in and profile setup, ranking, friends and requests, `/add/<handle>` invites, friend pages (banner, avatar, bio, recent activity, library, favorites), privacy switches, account deletion, and the privacy policy page. Tested in the browser against the emulators: sign-in, friend page, privacy toggle (generation bump), sign-up, friend request and full account deletion.

- **Customizable profiles (2026-10-02)**: avatar and banner (GIFs animate), bio and accent colour, visible to friends only (Cloud Storage rules plus a style callable). Friend rows show avatars with an online dot, and friend detail shows a banner header. Handle lookup no longer reveals anything beyond handle and name. Device-verified ([editor](evidence/profile-a-editor.png), [friend view](evidence/profile-b-friend-detail.png)).

- **Daily ranking (2026-10-02)**: opt-in stats; finishing a chapter counts toward today's (UTC) chapters and pages; Friends shows **Today's ranking** among you and friends who share stats. Device-verified ([screenshot](evidence/ranking-a-today.png)).
- **Account deletion (2026-10-02)**: Friends → Delete account (password re-auth and type DELETE) removes all social data and the sign-in account. Privacy policy draft in [PRIVACY_POLICY.md](PRIVACY_POLICY.md); local CI is `scripts/verify-all.ps1`.

- **Room chat and Clash Royale-style reactions (2026-10-02)**: plain text (≤200 characters) and 8 emoji reactions inside a live two-person room. Rate limited, stored in a 100-message ring, deleted on close. Reactions and messages pop out from the screen edge over the reader with a bounce; a floating emote button opens the picker. Device-verified ([friend's reaction](evidence/chat-a-reaction-from-friend.png), [own reaction](evidence/chat-b-own-reaction.png), [text](evidence/chat-b-text-from-friend.png)).

- **Recent-activity feed (2026-10-02)**: opt-in `feed` category (private by default). Items are `chapter_read` (first completion of a chapter in the reader) and `favorite_added`, with 7-day expiry, a 50-item ring per user, a 10-minute dedupe, generation fencing, and suppression for invisible, hidden and incognito. A friend's detail screen shows a "Recent" section (20 items). See the [API](SOCIAL_API.md) and the [rules audit](INTEGRATION_REVIEW.md). Device-verified on two emulators ([screenshot](evidence/feed-a-friend-recent.png)).

- **Friends list UX (2026-10-02)**: reading invitations and incoming requests at the top, invitations name the inviter and confirm selection; friends sorted online → reading now → last seen → name, each row showing a status dot plus "Reading now"/"Online"/"Last seen …" (only what that friend shares; denied reads stay blank); pull-to-refresh; whole privacy rows toggle; a friend's library/favorite title opens my exact local copy (source + path match, never title) or falls back to global search; the reader says when a room ended because the friendship was removed; ended rooms no longer raise the generic error banner in Friends. Evidence: `docs/evidence/friends-ux-*.png`.

- **More → Friends** supports email/password accounts, email verification/recovery, handles/display names, requests, accepted friends, removal and blocking.
- Library, separate social favorites, activity, presence and last seen have independent private/friends controls. Shared library/favorites publication is deliberate through **Share current library and favorites**. Privacy generations revoke old projections; incognito, hidden titles and invisible mode suppress eligible sharing.
- Process-owned presence maintains owner-private RTDB connection nodes and a Firestore friend-facing projection. Logout stops presence while the old token remains valid, with bounded cleanup and honest pending feedback.
- Reader overflow → **Read together** supports accepted-friend invitations, guest room handoff, host page publication and opt-in following. Devices load their own manga pages. Exact source/work/edition/chapter identity and explicit per-chapter page mapping are required; matching title text or page counts is insufficient.
- Source-specific privacy and accepted-edge observers stop sharing and clear held friend/room state on revocation. SDK completion gates retain operation ordering while allowing coroutine cancellation and independent privacy fencing. Host page updates coalesce; stale turns and incoming navigation echoes are suppressed.
- Signed-out and offline local reading remain available. Query-addressed, local or otherwise ambiguous source identities cannot be shared automatically. Offline removal may remain pending; activity and host room leases expire without renewal.

[Local setup](MULTIPLAYER_SETUP.md) gives backend/build commands, the prepared AVD paths, physical-device endpoint overrides and the two-account flow. [Reader integration](READER_INTEGRATION.md) records mapping, lifecycle and synchronization details. [The API](SOCIAL_API.md) is the Android/backend wire contract.

## Executed evidence

| Check | Result |
| --- | --- |
| Backend TypeScript build | Passed |
| Backend domain tests | 18 passed, 0 failed |
| Firebase Auth/Functions/Standard Firestore/RTDB emulator suite | 11 scenarios passed, 0 failed; includes activity missing/publish/clear/republish |
| Native `spotlessCheck testDebugUnitTest verifySqlDelightMigration :app:assembleDebug` | Passed; exit 0, 39s, 472 actionable tasks |
| Native JUnit results | 12 suites, 91 tests, 0 failures/errors/skipped |
| App tests | 30 total: 6 migration tests and 24 new social tests (completion gate 2, identity 4, source privacy fence 1, reader protocol 6, callable protocol 11) |
| APK validation | Expected manifest identity and AndroidDebug v2 signature verified with `aapt2`/`apksigner` |
| Android application smoke | Interim APK passed account/profile/friend/presence cleanup smoke on API 36 x86_64; final transport APK retest pending |
| Two-device reader/instrumented UI validation | Not completed |

[Backend verification](../backend/VERIFICATION.md) describes authorization, privacy-generation, pagination, room and multi-device-presence coverage. [Native verification](NATIVE_BUILD_VERIFICATION.md) lists APK hashes and environment evidence. The pre-existing JDK 21.0.10 was used; SDK, Gradle caches, emulator and system image were installed in the workspace with the user's accepted SDK license. Debug assembly passed without the declared NDK.

An interim debug APK passed native signup/email verification, profile save/private defaults, request/acceptance, friend library/favorites, presence opt-in and sign-out cleanup. Evidence includes [onboarding](evidence/native-start.png), [signed-out library](evidence/native-library-signed-out.png), [profile](evidence/native-profile-saved.png), [friend detail](evidence/native-friend-detail.png) and [logged-out UI](evidence/native-logged-out.png). The final APK isolates emulator callables from Functions/Instance ID registration and is awaiting a repeat smoke tied to its hash. These account/friend checks do not establish reader page synchronization or two-device lifecycle behavior.

The [independent integration review](INTEGRATION_REVIEW.md) records findings and owner fixes. Completion-gate and source-fence regressions passed in the native suite. The Rules review found no new unauthorized-read/ownership bypass statically; aggregate quotas, trigger amplification and retention remain production work.

## Remaining verification and release work

Complete the repeat Android smoke on the final emulator-transport-isolated APK. Eleven callable protocol unit tests and the backend activity missing/publish/clear/republish regression passed. Real two-device checks must exercise supported-source chapter mapping, invitations/following, source changes, background/foreground, account switching, friend removal/blocking and online/offline cleanup.

Choose the final fork name, application ID, GitHub destination and release signing/distribution. Provision a fork-owned Firebase project with Standard Firestore, configure an Android App Check provider, validate the Node 22 deployment runtime, and review quotas, abuse controls, retention and account deletion. Production callables enforce App Check. No cloud Firebase project, production deployment or published GitHub fork/PR is claimed by this checkpoint.

Nudges (2026-10-06): `socialNudge` callable (friends only, one per pair per 24 h, 20 people/day, server-only `nudgeLimits`), push kind `nudge`, profile button and at-risk icon in the app, web profile button. Backend domain tests, app unit tests, spotlessCheck, SQLDelight check, assembleDebug and web typecheck/tests passed; the emulator scenario suite was not run (emulator ports were taken by another process) and no emulator scenario covers nudges.

Stats and monthly challenges (2026-10-06): backend `awardXp` keeps `xp/{uid}.month` counters (UTC month) and `challenges` stamps; three challenges a month from a fixed 12-month rotation, +100 XP each once (outside the daily cap and weekly XP), `season_yyyy_mm` badge for all three; the reply adds `challenges`, `challengeXp`, `season` (see SOCIAL_API.md). App: My stats screen from the Me tab (server stats, challenges, badges with dates, plus 12-week chart, top titles, reading time, longest day and This month card from local history, never uploaded), challenge card and How XP works dialog on the Me tab, Stats section on friend profiles, reader toasts for completed challenges. Web: /stats shows challenges, season badges, days/best day and How XP works. Rules unchanged. Backend domain tests (84), spotlessCheck, testDebugUnitTest, verifySqlDelightMigration, assembleDebug and web typecheck/tests passed; the emulator scenario suite was not run (no rules change) and no device/emulator check of the new screens was done.
