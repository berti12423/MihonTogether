# Mihon Together – web companion

A small browser client for the social side of Mihon Together. It has no reader: manga sources are Android extensions, so pages can't load in a browser. It talks to the same Firebase backend and rules as the app.

What it does:
- Sign in or create an account, and set up a profile.
- Today's friends ranking.
- Friend requests (accept, reject, send, cancel) and `/add/<handle>` invite links.
- Friends list with avatar, online status, last seen and reading now.
- Friend pages with banner, avatar, bio, recent activity, shared library and favorites. Avatar and banner GIFs animate.
- Remove or block a friend.
- All privacy switches, including invisible mode.
- Account deletion (password re-auth plus typing DELETE). This is the web deletion link Google Play requires.
- The privacy policy page, rendered from `docs/PRIVACY_POLICY.md`.

What stays in the app: publishing your library and favorites (they come from your local library), reading together, and editing your profile look.

## Run locally

```powershell
npm --prefix web ci
npm --prefix web run build
npm --prefix backend run emulators   # serves the web build (backend/hosting) on http://127.0.0.1:5050 (plus auth/firestore/database/functions/storage)
```

Local builds talk to the emulators. For production, build with `MIHON_WEB_FIREBASE_CONFIG='{"apiKey":"…","authDomain":"…","projectId":"…","storageBucket":"…","appId":"…"}'` and deploy with `firebase deploy --only hosting`. Production also needs web App Check (reCAPTCHA Enterprise) because callables enforce App Check, and the Hosting domain must be added to Auth's authorized domains.

## Checks

`npm --prefix web run typecheck`, `npm --prefix web test` (pure helpers: ranking, UTC day, invite paths, sharing payload, relative time), `npm --prefix web run build`. `scripts/verify-all.ps1` runs all three.

## Security notes

- Every user-provided string is rendered as a text node, never as HTML (`src/dom.ts`).
- Hosting sends a strict CSP: same-origin scripts and styles only, images from self/blob/data, and Firebase endpoints only.
- Profile media is fetched through authenticated Storage reads (rules: owner or friends), never through public download URLs.
- The web never writes presence, so an open tab doesn't make you look online.