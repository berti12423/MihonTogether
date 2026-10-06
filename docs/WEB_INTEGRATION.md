# Design: web integration

Status (2026-10-03): **W1, W2 (web side), W3 and W4 are implemented** in `web/` and tested in a browser against the emulators. W4 shipped as a chat-only room member (roadmap F8), together with library grids, the global ranking, messages and read-along requests. The Android side of invite links (an App Link intent filter) waits for the final app ID. See [web/README.md](../web/README.md).

## What "web" can and can't be

The Android app reads manga through Mihon source extensions, which are Android APKs. A browser can't run them, so a web client **can't load the same pages** and can't be a full reader. Everything else in the social layer is plain Firebase (Auth, Firestore, RTDB, callables), and the security rules don't care which client reads. A web client is mostly a new UI over the same backend.

## Proposed scope, in order

### W1. Account and privacy pages (needed for the Play Store anyway)

- Static pages on Firebase Hosting: the privacy policy, plus a **"delete my account" page**. Google Play requires a web link where users can request deletion, not only an in-app button.
- The deletion page: sign in, re-authenticate, type `DELETE`, then call `socialDeleteAccount`. This reuses the callable from the account-deletion branch.
- Backend: none beyond account deletion. Privacy impact: none new.

### W2. Invite links

- `https://<project>.web.app/add/<handle>` opens the app through an Android App Link (`dev.mihontogether.reader`), pre-filled with "Send friend request to @handle".
- Without the app, the page shows install instructions. It never shows profile data: the handle is just echoed, there's no lookup and no enumeration.
- Backend: none. Android: an intent filter plus a verified `assetlinks.json` on Hosting. Privacy impact: a handle placed in a link is shared by the user's own choice.

### W3. Web companion (read-mostly)

- Sign in with the same account. See friends, online status, last seen, "reading now", friends' shared library, favorites and recent feed.
- Accept or reject requests, change privacy switches and invisible mode, restore hidden titles.
- All of it uses the existing callables and rules. The web never publishes library or favorites projections: those come from the device's local library, and the web has none.
- Stack: a small static SPA (TypeScript + Firebase JS SDK) under `web/` in this repo, deployed with Hosting. It shares the callable names and input shapes in `docs/SOCIAL_API.md`; add a drift test like `SocialCallableNamesTest`.

### W4. Room spectator and chat (optional)

- Join a friend's live room from the browser as a participant: see the host's title, chapter and page number, chat, and send reactions (with the same pop-out style).
- No pages are shown. Showing pages would need source-specific web adapters (MangaDex has a public web API) and raises copyright, CORS and edition-mapping problems, so it's out of scope.
- A web participant never follows or publishes positions; it's a chat-only member.

## Backend and security changes

- **App Check for web**: callables enforce App Check in production, so the web app needs the reCAPTCHA Enterprise App Check provider. Without it, every callable fails.
- **Auth**: add the Hosting domain to Authorized domains. Keep email/password; Google sign-in (deferred item 5) would also be easiest on the web.
- **Rules**: no changes for W1–W3. The rules are client-agnostic and already enforce friendship, consent, generation and expiry. W4 is unchanged for 2-person rooms because the browser user is one of the two participants.
- **Presence**: decide whether a web session counts as "online". Recommendation: no, at least initially. Only the Android app writes RTDB connection nodes, so a browser tab left open doesn't fake presence.
- **Content Security Policy** on Hosting: allow only Firebase endpoints, no inline scripts, and render every user string (display names, chat) as text, never HTML.

## Costs

Firebase Hosting's free tier covers static pages and a small SPA. The web clients use the same Firestore and callable quotas as the app; the per-user daily caps from Phase 7 apply to web calls too.

## Effort

| Item | Size | Depends on |
| --- | --- | --- |
| W1 privacy and deletion pages | Small | account deletion callable, real Firebase project |
| W2 invite links | Small | final app ID and signing key (for `assetlinks.json`) |
| W3 companion | Medium | real project, web App Check |
| W4 spectator chat | Medium | W3 |

Recommendation: do W1 together with Phase 7 (it's a Play requirement), W2 right after the final app ID is chosen, and W3/W4 only if you want a desktop or browser experience.

## Web: invites, notifications, discovery (2026-10-03)

- `/add/<handle>` signed out shows a landing card (get the app, open in the app, or use the website with "Create account" first); only the handle from the link is echoed. Signed in, home has "Your invite link" (copy/share) and a dismissible "Getting started" checklist (localStorage).
- The tab title shows `(N)` for unread messages, incoming friend requests and room invites. Browser notifications are opt-in (Account page, or the prompt on Messages), shown only while the tab is hidden, never for data present at page load, at most one per conversation per 30 s. No service worker.
- Installable: `manifest.webmanifest` + `icon.svg`. Chrome may still want 192/512 px PNG icons for the install prompt.
- `/activity` groups friends' shared feeds (only friends with feed sharing on; loaded on open, cached 5 min). `/stats` shows my own XP, last 8 `dailyStats` days, all achievements, and a canvas PNG to share (text only).
- Unknown paths show "Page not found"; the active nav link has `aria-current="page"`; notices sit in a polite live region.
- `h()` now applies `style` through CSSOM, because the CSP (`style-src 'self'`) blocks inline style attributes.
