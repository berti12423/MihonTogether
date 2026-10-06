# Production readiness

Prepared 2026-10-02 without any cloud resources. Nothing here has been deployed. Everything marked *(needs project)* waits for Berti's decision on the Firebase project.

## Decisions still needed from Berti

- Final app name, application ID, launcher icon (currently **Mihon Together Dev** / `dev.mihontogether.reader`). Keep the Apache-2.0 license and Mihon attribution.
- Release signing key: create it locally, never commit it; `.gitignore` already blocks `*.jks`, `*.keystore` and `*.p12`.
- Firebase project ID and region. Functions are pinned to `europe-west1` in `backend/src/index.ts` (plus `web/src/firebase.ts` and the `functions-region` Gradle default); change all three together.
- Billing: scheduled functions and Node 22 Functions need the Blaze plan.

## Server-side limits now in place

| Guard | Where |
| --- | --- |
| Per-callable daily caps per user (e.g. profile 50, friend actions 500, sharing changes 200, feed 300, room messages 2,000, room actions 3,500) plus a total of 8,000 mutations/day | `DAILY_LIMITS` / `DAILY_TOTAL` in `backend/src/domain.ts`, enforced in the shared `mutation()` transaction. Idempotent replays don't count. Counter doc `quotas/{uid}` (server-only). |
| Handle discovery 30/min, friend requests 100/day, room invitations 20/h, chat 1/s and 30/min | Per-callable limits (unchanged) |
| Bounded storage | Feed: a 50-slot ring per user. Room chat: a 100-slot ring per room, deleted on close. Library pages: ≤50 entries per call. |
| Stale rooms | `socialCleanup` (every 30 min) closes rooms whose lease expired without a close, clears chat and fixes the invite index (`backend/src/cleanup.ts`; index `rooms(state, leaseExpiresAt)`). |

## TTL policies (deployed with the indexes)

Firestore TTL deletes expired documents within about 24 hours. Rules already deny reads of expired data, so TTL is about storage and cost, not privacy.

Both policies (`expiresAt` on the `items` and `activities` collection groups) are `fieldOverrides` with `"ttl": true` in `backend/firestore.indexes.json`, so `firebase deploy --only firestore:indexes` enables them and no `gcloud` is needed. The overrides keep the default single-field indexes, which the feed queries (`expiresAt >` with `orderBy`) rely on.

The `items` collection group covers `operations/{uid}/items` (idempotency records, 30 days) and `feeds/{uid}/items` (7 days). `roomInvites/{uid}/items` and room `messages` have no `expiresAt`, so they're unaffected.

Not covered by TTL:

- `quotas/{uid}` and the rate-limit docs: one small doc per user, overwritten in place.
- Chat in rooms that ended through friend removal: the friend ACL blocks the participants' close, so `socialCleanup` closes these rooms once their lease expires.

## Deploy checklist *(needs project)*

1. Create the project with **Firestore Standard** (native mode) and the Realtime Database, then enable Email/Password Auth.
2. Add an Android app per application ID and download `google-services.json`. It is git-ignored; never commit it.
3. App Check: Play Integrity provider for release. Callables already set `enforceAppCheck` outside the emulator.
4. Enable Cloud Storage (default bucket) and deploy `storage.rules` together with the rest: `firebase deploy --only firestore:rules,firestore:indexes,database,storage,functions --project <id>`.
5. From `backend/`, run `npm ci`, `npm test` and `npm run test:emulators`, then `firebase deploy --only firestore:rules,firestore:indexes,database,functions --project <id>`.
6. Check the TTL policies are active (Firestore → TTL tab in the console).
7. Run a release build with real config and smoke-test on two physical phones (the [two-device checklist](evidence/TWO_DEVICE_READER.md)).
8. Publish the privacy policy and the web account-deletion page on Hosting (see [web integration](WEB_INTEGRATION.md) W1), and wire in-app account deletion.

## CI

This repository uses local git only (no GitHub remote), so `scripts/verify-all.ps1` is the local equivalent of CI. `.github/workflows/social-backend.yml` is kept for whenever a remote exists.
