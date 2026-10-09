# Plan: "My library" on the website over a direct phone link (option A)

Status: phase 1 done 2026-10-09 (protocol doc, rules, TTL, emulator test, deletion, privacy text); phase 2 agents started. Build with subagents per the phases below; ask Berti before deploying.

## Goal

Users open mihontogether.web.app → **My library** and read their phone's library in the browser: covers, titles, chapters and pages. The manga travels **directly from the phone to the browser** over an encrypted WebRTC data channel, on the home Wi-Fi when both are there. Firebase only carries a few small signaling messages (no manga, no covers), and they are deleted after minutes.

Why not plain HTTP on the LAN: the site is `https://`, so browsers block requests to `http://192.168.x.x` (mixed content), and Chrome's Private Network Access rules restrict public pages calling local devices. WebRTC data channels aren't affected and are encrypted (DTLS).

## Who can connect

Only the same account: the browser must be signed in as the phone's account. Firestore rules make the signaling documents owner-only, and the phone checks the session belongs to its own uid. Friends never get access through this. (Sharing with friends is a separate, existing feature.)

## Architecture

### Signaling (Firestore, owner-only, short-lived)

- `libraryHosts/{uid}/phones/{deviceId}`: written by the phone while it can serve: `{name ("Galaxy A35"), appBuild, protocol, updatedAt, expiresAt}`. The web lists these to show "Connect to Galaxy A35". Heartbeat every 60 s while sharing; `expiresAt` = now + 3 min (TTL policy deletes stale ones).
- `libraryLinks/{uid}/links/{linkId}`: created by the **web** with `{deviceId, offer (SDP), createdAt, expiresAt (now+10 min), state: "offered"}`. The phone writes `answer` + `state: "answered"`; either side sets `state: "closed"`.
- No candidates subcollection: each side waits for ICE gathering (at most 3 s) and sends a complete SDP, so a connection costs 2 writes. Exact shapes: `docs/WEB_LIBRARY_PROTOCOL.md`.
- Rules: read/write only `request.auth.uid == uid`; size limits on SDP (≤ 20 KB) and candidates; no other fields. Delete `libraryHosts/{uid}` and `libraryLinks/{uid}` on account deletion. TTL policy on `expiresAt` for both collections (add to `firestore.indexes.json` field overrides like the other TTL fields).
- Emulator tests: owner can create/read; another user can't read or write; oversized SDP rejected.

### Connection

- Web: `RTCPeerConnection` with one ordered, reliable `RTCDataChannel` ("library"). ICE servers: `stun:stun.l.google.com:19302` only (no TURN: no relay costs; same-Wi-Fi works through host candidates; across networks it works when NATs allow, otherwise "Couldn't connect, make sure both are on the same Wi-Fi").
- Android: WebRTC library `io.getstream:stream-webrtc-android` (Apache-2.0 prebuilt libwebrtc; check the current version and ABI sizes; arm64 adds roughly 8–12 MB to the online APK). Data channel only, no audio/video, no camera/mic permissions.
- Note for the privacy policy: STUN tells Google's STUN server the device's public IP during connection setup (standard WebRTC); nothing else.

### Protocol over the data channel (write `docs/WEB_LIBRARY_PROTOCOL.md` first; both sides follow it)

- Messages are JSON text frames `{id, method, params}` → `{id, result}` / `{id, error: {code, message}}`; binary payloads (images) are sent as binary frames in chunks of ≤ 16 KB with a small header `{id, seq, last}` so large pages stream without blocking (respect `bufferedAmount` / `bufferedAmountLowThreshold` on the web side and the equivalent on Android).
- `hello {protocol: 1, appBuild}` → `{deviceName, librarySize}`.
- `library.list {category?}` → `[{mangaId, title, author, sourceName, unread, downloaded, categoryIds, coverVersion}]` + `categories`.
- `manga.cover {mangaId, maxWidth}` → JPEG bytes (from the phone's cover cache, resized like `CoverThumbnails`).
- `manga.details {mangaId}` → description, status, genres, and chapters `[{chapterId, name, number, read, lastPageRead, downloaded, scanlator, dateUpload}]`.
- `chapter.pages {chapterId}` → page count (downloaded chapters from disk; otherwise via the source's page list, the same path as the reader's `HttpPageLoader`, honouring the existing retry for 503).
- `page.image {chapterId, index}` → image bytes (downloaded file, or fetched through the source client with its headers and cookies, then cached in the chapter cache).
- `chapter.progress {chapterId, lastPageRead, read}` → updates the phone's history and read state (and records the chapter for XP/stats through the existing reader path when a chapter is finished, unless the source is incognito).
- Errors: `not_found`, `source_error` (with the source's message), `busy`, `unsupported_protocol`.
- Incognito sources: listed and readable (it's the user's own device) but progress from the web isn't recorded to history/XP, the same as the app's incognito behaviour.

### Phone side (Android)

- New package `mihon.feature.weblibrary`: `WebLibraryHost` (owns the session lifecycle), `WebLibraryProtocol` (pure request handling, unit-tested), `WebLibrarySignaling` (Firestore docs, owner-only), `WebLibraryService` (foreground service, type `dataSync`, notification "Sharing your library with your browser • Disconnect").
- Phase 1 behaviour: while the app is open (or the user taps "Share library with my browser" in Friends → Me or Settings), the phone publishes its `libraryHosts` device doc and listens for sessions. A session request starts the foreground service; it stops after 15 minutes without requests or when the browser disconnects.
- Phase 3 (later): wake the phone with an FCM data message when the web asks (high priority), so the app doesn't need to be open.
- Setting: Settings → "Library on the web" (on by default per Berti's rule; it's the user's own access). Turning it off removes the device doc and refuses sessions.
- Reuse existing code: `GetLibraryManga`, `CoverCache`, `DownloadManager`/`DownloadProvider` for downloaded pages, `SourceManager` + `HttpSource` for online pages, `ChapterCache`, history updates via the same interactors the reader uses.
- Tests: protocol handler unit tests with fakes (list, details, pages from download vs source, errors, incognito progress rule).

### Website side

- New routes: `/my-library` (devices → connect → cover grid with categories and search), `/my-library/:mangaId` (details + chapter list with read state), `/my-library/:mangaId/:chapterId` (reader).
- Reader: vertical long-strip and paged (left/right, RTL option) modes, keyboard arrows, prefetch the next 3 pages, page images as blob URLs (revoked when leaving), progress sent on page change (throttled) and at chapter end, "Next chapter" button.
- States: "Open Mihon Together on your phone" (no device online), connecting, connected (device name, Disconnect), lost connection (retry).
- Nav: "My library" link in the signed-in header. Download page and README mention it.
- Tests: protocol client unit tests (framing/chunk reassembly, request/response matching, timeouts) in `web/test`.

## Phases and agents

1. **Contract (me, small):** write `docs/WEB_LIBRARY_PROTOCOL.md`, Firestore rules + TTL + emulator tests for `libraryHosts`/`libraryLinks`, account deletion cleanup, privacy-policy paragraph.
2. **In parallel, two agents in worktrees** (each starts by loading the `mihon-together` skill and reading the protocol doc):
   - **Android host agent:** dependency, `mihon.feature.weblibrary` (signaling, WebRTC peer, protocol handler, foreground service, setting, Friends → Me entry), unit tests, `assembleDebug` + the preview build (check APK size growth).
   - **Web client agent:** WebRTC client + signaling, protocol client, `/my-library` pages and the reader, styles (CSP: no inline styles; `connect-src` already allows Firebase; WebRTC needs no CSP change), tests.
3. **Integration (me):** merge, run all tests, then a real test: Berti's phone and his PC browser on the same Wi-Fi (the AVD's NAT makes AVD↔host WebRTC unreliable). Measure page load times on large chapters.
4. **Later:** wake by FCM, offline caching in the browser, reading together from the web, progress sync polish.

## Risks to watch

- Phones killing the app in the background (foreground service needed; some vendors still kill it).
- Large pages and long chapters: stream in chunks, cap concurrent page requests at 2–3.
- Connection failures across networks without TURN (explain clearly in the UI; same Wi-Fi always works).
- APK size growth from libwebrtc.
- Sources that need Cloudflare cookies: fetching through the phone keeps working because the phone has the cookies.
