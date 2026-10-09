# Web library protocol (v1)

The contract between the phone (host) and the website (client) for "My library". Plan and reasoning: `docs/WEB_LIBRARY_PLAN.md`. Both sides follow this document exactly; change it first if something needs to change.

## 1. Signaling (Firestore, owner-only)

Both sides write directly with the Firestore SDK (no callables). The rules (`backend/firestore.rules`) allow only the signed-in owner (`request.auth.uid == uid`) to read or write, and check the shapes below. TTL deletes old documents through `expiresAt`.

### Phone document: `libraryHosts/{uid}/phones/{deviceId}`

| Field | Type | Notes |
|---|---|---|
| `name` | string, 1–60 | `Settings.Global.DEVICE_NAME`, else `Build.MODEL` ("Galaxy A35") |
| `appBuild` | int | the app's build number |
| `protocol` | int | `1` |
| `updatedAt` | timestamp | must equal the server time (`FieldValue.serverTimestamp()`) |
| `expiresAt` | timestamp | now + 3 minutes (at most 10 minutes ahead) |

- `deviceId`: random, generated once per install and kept in preferences, `[A-Za-z0-9_-]{16,64}` (e.g. 22-char base64url of 16 random bytes).
- The phone writes it (set, whole document) every **60 s** while it can serve, and deletes it when the user turns the setting off or signs out. If it stops writing (app closed), it disappears for the website as soon as `expiresAt` passes.
- The website shows phones with `expiresAt > now` (query `where('expiresAt', '>', now)`, live listener), and re-checks the time locally.

### Link document: `libraryLinks/{uid}/links/{linkId}`

`linkId` is a random UUID made by the website.

| Field | Written by | Type | Notes |
|---|---|---|---|
| `deviceId` | web, on create | string | the phone it is for |
| `offer` | web, on create | string, ≤ 20 000 chars | complete SDP offer (type `offer`) |
| `answer` | phone | string ≤ 20 000, or null | complete SDP answer |
| `state` | both | `offered` → `answered` → `closed` | create must be `offered` |
| `createdAt` | web | timestamp | server time on create; never changes |
| `expiresAt` | web | timestamp | now + 10 minutes (at most 15); never changes |

- Updates may change only `answer` and `state`; `state` can't go back to `offered`.
- **No trickle ICE.** Each side waits for ICE gathering to finish (`iceGatheringState == complete`) **or 3 seconds, whichever comes first**, then writes its local description's SDP (which then includes the gathered candidates). This keeps signaling to 2 writes per connection.
- ICE servers on both sides: `[{ urls: 'stun:stun.l.google.com:19302' }]`. No TURN.
- Same Wi-Fi: Chrome hides its own LAN address behind an `.local` mDNS name; that's fine, because the phone's host candidates are plain IPs and the browser's checks to them create a peer-reflexive candidate on the phone.

### Flow

1. Web: `RTCPeerConnection` → `createDataChannel('library', { ordered: true })` → `createOffer` → `setLocalDescription` → wait for gathering (≤ 3 s) → create the link document with `state: 'offered'`.
2. Phone: listens to `libraryLinks/{uid}/links` with `where('deviceId', '==', myDeviceId)` and `where('state', '==', 'offered')`. It ignores links whose `createdAt` is more than 2 minutes old. For a new offer it starts the foreground service, `setRemoteDescription(offer)` → `createAnswer` → `setLocalDescription` → wait for gathering (≤ 3 s) → update the link with `{ answer, state: 'answered' }`.
3. Web: listens to its link; on `answered` it calls `setRemoteDescription(answer)`. When the data channel opens, it sends `hello`.
4. One browser session per phone: a new offer for the same phone closes the previous connection.
5. Closing: whoever ends the session sets `state: 'closed'` (or the web deletes the link). The phone also closes the connection when the data channel closes or the peer connection goes `failed`/`closed`, after **15 minutes without requests**, or when the user taps Disconnect in the notification.
6. Web timeouts: no answer in 20 s → "Your phone didn't answer. Open Mihon Together on it and try again." Answer but no open channel in 15 s → "Couldn't connect. Make sure your phone and computer are on the same Wi-Fi."

## 2. Data channel messages

One data channel, label `library`, ordered and reliable. Every message is either a **text frame** (UTF-8 JSON) or a **binary frame** (image chunk).

### Requests and responses (text)

- Request (web → phone): `{"id": 7, "method": "manga.details", "params": {...}}`. `id` is a positive integer, unique per connection, chosen by the web.
- Success: `{"id": 7, "result": {...}}`.
- Failure: `{"id": 7, "error": {"code": "not_found", "message": "Manga 123 isn't in the library"}}`.
- The phone handles up to **4 requests at a time**; extra requests get `busy`. The web keeps at most 4 in flight (it queues the rest).
- Web timeouts: 20 s per request, 60 s for `page.image` (some sources are slow).

Error codes: `bad_request`, `not_found`, `source_error` (message = the source's error text), `busy`, `unsupported_protocol`, `internal`.

### Binary results

`manga.cover` and `page.image` return bytes:

1. A text response: `{"id": 9, "result": {"contentType": "image/jpeg", "size": 183402, "chunks": 12}}`.
2. Then exactly `chunks` binary frames. Each frame is an **8-byte header** followed by the payload: bytes 0–3 = request `id` (uint32, big-endian), bytes 4–7 = chunk index from 0 (uint32, big-endian). The payload is at most **16 376 bytes**, so a frame is at most 16 384 bytes. Chunks of one response are sent in order; chunks of different responses may interleave.
3. The web joins the payloads when it has all of them, and checks the total equals `size`. `contentType` is what the image really is (`image/jpeg`, `image/png`, `image/webp`, `image/gif`, `image/avif`); the phone sniffs it from the bytes when unsure.

Flow control (phone): stop sending while the channel's `bufferedAmount` is above **1 MB**, and resume when it drops below **256 KB** (`DataChannel.Observer.onBufferedAmountChange`).

### IDs

All IDs are **strings** (the phone's database IDs are 64-bit and don't fit a JavaScript number): `mangaId`, `chapterId`, `categoryId`, `sourceId`.

## 3. Methods

### `hello`
- params: `{"protocol": 1, "client": "web"}`
- result: `{"protocol": 1, "deviceName": "Galaxy A35", "appBuild": 8300, "librarySize": 412}`
- If `protocol` isn't 1: error `unsupported_protocol`. The web shows "Reload this page" when the phone's protocol is higher than the web's, or "Update Mihon Together on your phone" when it's lower.
- Other requests sent before `hello` get `bad_request`.

### `library.list`
- params: `{}`
- result:
```json
{
  "categories": [{"categoryId": "0", "name": "Default", "order": 0}, {"categoryId": "3", "name": "Reading", "order": 1}],
  "manga": [{
    "mangaId": "123", "title": "Frieren", "author": "Yamada Kanehito", "sourceId": "2499283573021220255",
    "sourceName": "MangaDex", "unread": 3, "downloaded": 12, "categoryIds": ["3"],
    "coverVersion": "1728400000000", "lastRead": 1728450000000, "dateAdded": 1720000000000
  }]
}
```
- Library titles only (`favorite = true`). `categoryIds` is empty for the default category. `author` may be null. `coverVersion` changes when the cover changes (cover's last-modified time), so the web can cache covers by `mangaId + coverVersion`. `lastRead` is null if never read.
- Category `"0"` is the default category; leave it out when it has no titles (like the app).

### `manga.cover`
- params: `{"mangaId": "123", "maxWidth": 300}` (`maxWidth` 100–600, default 300)
- result: binary image (JPEG, resized so width ≤ `maxWidth`). From the phone's cover cache, or the cover URL through the source's client. Error `not_found` when there's no cover.

### `manga.details`
- params: `{"mangaId": "123"}`
- result:
```json
{
  "mangaId": "123", "title": "Frieren", "author": "…", "artist": "…", "description": "…",
  "status": "ongoing", "genres": ["Adventure", "Fantasy"], "sourceName": "MangaDex", "url": "https://mangadex.org/title/…",
  "readingMode": "rtl",
  "chapters": [{
    "chapterId": "9876", "name": "Chapter 140", "number": 140, "scanlator": "Group", "read": false,
    "bookmark": false, "lastPageRead": 0, "downloaded": true, "dateUpload": 1728000000000
  }]
}
```
- `status`: `unknown`, `ongoing`, `completed`, `licensed`, `publishing_finished`, `cancelled`, `on_hiatus`.
- `readingMode`: the title's reader mode resolved against the app default: `ltr`, `rtl`, `vertical` or `webtoon`. The web starts in paged mode for `ltr`/`rtl` (with that direction) and long strip for `vertical`/`webtoon`.
- `url`: the title's web page from the source, or null.
- `chapters`: every chapter, newest first (the app's default source order), with the app's chapter-list filters not applied. `number` is -1 when unknown. `dateUpload` is 0 when unknown. `description`, `author`, `artist`, `scanlator` may be null.

### `chapter.pages`
- params: `{"chapterId": "9876"}`
- result: `{"chapterId": "9876", "pageCount": 42, "downloaded": true}`
- Downloaded chapters are read from disk (folder or CBZ, same as the reader). Otherwise the phone gets the page list through the source (the same path the reader uses), and keeps it for this connection.
- Errors: `not_found`, `source_error`.

### `page.image`
- params: `{"chapterId": "9876", "index": 0}` (index from 0)
- result: binary image. Downloaded: from the file. Online: through the source's client (its headers and cookies, the app's retry on 503), and saved in the chapter cache like the reader does.
- The web may call this before `chapter.pages` returned only for pages it already knows exist; otherwise `bad_request`.
- Errors: `not_found` (no such page), `source_error`.

### `chapter.progress`
- params: `{"chapterId": "9876", "lastPageRead": 12, "read": false}`
- result: `{"ok": true}`
- Updates the chapter's `lastPageRead` and history (last read now). `read: true` marks the chapter read; `read: false` never un-marks a read chapter. When a chapter becomes read, the phone counts it the same way the reader does (stats, XP, the activity), **unless** the title's source is in incognito or incognito mode is on; then nothing is recorded, like the app.
- The web sends it at most every 5 seconds while reading, when leaving the chapter, and with `read: true` when the last page is reached.

## 4. Phone behaviour summary

- Setting: Friends → Me → "Library on the web", **on by default**. Off: delete the phone document, stop listening, refuse sessions.
- Publishes and heartbeats the phone document while the app is in the foreground and signed in, and while a session (foreground service) is running. Listens for offers while the process is alive.
- Foreground service type `dataSync`, notification "Sharing your library with your browser" with a **Disconnect** action. It starts with the first offer, and also when the user taps "Share library with my browser" (Friends → Me), which keeps the phone available for 15 minutes.
- No camera or microphone: data channel only.

## 5. Website behaviour summary

- Routes: `/my-library` (pick phone, connect, cover grid with category tabs and search), `/my-library/:mangaId` (details and chapters), `/my-library/:mangaId/:chapterId` (reader).
- States: no phone ("Open Mihon Together on your phone with the same account; this page connects by itself"), connecting, connected (device name, Disconnect), connection lost (Retry).
- Reader: long strip and paged (left/right with RTL), arrow keys, prefetch the next 3 pages, images as blob URLs (revoked when they leave), "Next chapter" at the end.
- Covers are cached in memory for the connection (by `mangaId` + `coverVersion`), loaded as they scroll into view.
