# Setting up the real Firebase project (Blaze + budget alert)

Decided 2026-10-02: host on Firebase's pay-as-you-go **Blaze** plan with a **$1 budget alert**. The free Spark plan can't deploy Cloud Functions, and new projects need Blaze for Cloud Storage. Blaze includes the same no-cost usage; for a friend group the expected bill is $0. Prices and quotas can change; check firebase.google.com/pricing.

Steps 1–6 need your Google account (about 15 minutes). Claude does steps 7–9.

## 1. Create the project
1. Go to https://console.firebase.google.com → **Create a project**.
2. Name it, e.g. `Mihon Together`. Note the **project ID** it shows (e.g. `mihon-together-1a2b3`); you can't change it later.
3. **Disable Google Analytics** (the app has no analytics). Create.

## 2. Switch to Blaze and cap the risk
1. In the project, click the plan badge (bottom-left, "Spark") → **Upgrade** → **Blaze**, and attach a billing account (card).
2. Open https://console.cloud.google.com/billing → your billing account → **Budgets & alerts** → **Create budget**.
   Scope: this project. Amount: **$1**. Alerts at 50%, 90% and 100% to your email.
   (A budget alerts you; it doesn't stop spending. The app's per-user daily limits keep usage bounded.)

## 3. Turn on the services (use one location for everything: `europe-west1`, Belgium)
Decided 2026-10-02: Europe, for lower latency for EU users and EU data residency. Functions are pinned to `europe-west1` too (`backend/src/index.ts`, `web/src/firebase.ts`, and the `functions-region` Gradle default).

Direct links: `https://console.firebase.google.com/project/PROJECT_ID/` + `authentication`, `firestore`, `database` or `storage`.
- **Authentication** → Get started → **Email/Password** → Enable (leave "Email link" off).
- **Firestore Database** → Create database → **Standard edition** → location **europe-west1 (Belgium)** (not the `nam5` default) → **Production mode** (our rules get deployed over it).
- **Realtime Database** → Create database → **Belgium (europe-west1)** → **Locked mode**. Its URL then ends in `.europe-west1.firebasedatabase.app`.
- **Storage** → Get started → location **EUROPE-WEST1** → production mode. The always-free Cloud Storage allowance only covers some US regions, so images bill at about $0.02 per GB-month: a few MB of avatars costs well under a cent.

## 4. Register the Android app
**Project settings** (gear) → **Your apps** → Android icon:
- Package name: **`dev.mihontogether.reader.dev`** (the test build's package). Register a second app later for the final release ID.
- Skip downloading `google-services.json` and skip the SDK steps (the app is configured differently).
- Afterwards, the Android app's card shows the **App ID** (`1:…:android:…`). The **Web API key** is under Project settings → General.

## 5. Register the web app
Your apps → **Web** (`</>`) → nickname `web` → **also set up Firebase Hosting**. It shows a `firebaseConfig` object: copy it.

## 6. Give Claude the values and log in the CLI
1. Copy `backend/production.example.json` to `backend/production.json` and fill it in. Or paste the project ID, Android App ID, API key and the web `firebaseConfig` into chat; they're public identifiers, not secrets.
2. In a terminal in the project folder, log the deploy tool in (opens a browser):
   ```bash
   npx --prefix backend firebase login
   ```

## 7. Deploy (Claude)
```bash
.\scripts\deploy-production.ps1
```
This runs the backend and web checks, writes `backend/.env.<projectId>`, builds the web companion against the project, and deploys Firestore rules and indexes, RTDB rules, Storage rules, Functions and Hosting. The first Functions deploy enables the required Google Cloud APIs, and the scheduled cleanup creates one Cloud Scheduler job.

Deploy notes (first deploy, 2026-10-02, project `mihontogether`):
- Functions are uploaded without `package-lock.json` (`functions.ignore` in `firebase.json`). Cloud Build uses Node 22's npm 10, which rejects our npm 11 lock file because of the dev-only `overrides`. The runtime dependencies are pinned exactly in `package.json`.
- The web build mirrors `web/dist` to `backend/hosting/` (git-ignored), because Hosting only serves folders inside the Firebase project directory.
- Container images for functions are cleaned up after 1 day (`firebase functions:artifacts:setpolicy --location europe-west1 --days 1`, done once).
- The presence trigger has no auto-retry, so the deploy needs no `--force`.
- The first attempt created the functions while their build failed, so the callables never got the public `allUsers → Cloud Run Invoker` binding (Google Front End 403 before the function ran). Berti added it by hand in the Cloud Run console for the 15 callables; `socialpresencechanged` and `socialcleanup` stay private. The callables now declare `invoker: "public"`. Verified: each callable answers `UNAUTHENTICATED` without sign-in.

## 8. Build the APK (Claude)
```bash
.\scripts\build-production-apk.ps1
```
Writes `Desktop\MihonTogether-online\MihonTogether-online-arm64.apk`. It installs next to Mihon and works on any network.

## 9. After the first deploy (Claude)
- Check the TTL policies (`expiresAt` on `items` and `activities`) are active; they deploy with the indexes (see [PRODUCTION.md](PRODUCTION.md)).
- Smoke-test sign-up, friends, presence, a room, chat and the web app on two real devices.

## App Check: off during private testing
App Check would block every sideloaded APK, because Play Integrity only vouches for Play-installed apps. So `production.json` starts with `"enforceAppCheck": false`. Sign-in, per-user daily limits and security rules still protect the backend. Before a public or Play release: integrate App Check in the app (Play Integrity) and the web (reCAPTCHA Enterprise), then set it to `true` and redeploy.

## Costs to keep in mind (Blaze still has the no-cost amounts)
- Firestore: about 50k reads and 20k writes per day free. The app's listeners and the ranking's one-doc reads stay far below that for a friend group.
- Functions, Hosting, Auth, RTDB (100 simultaneous connections free): well within the free amounts.
- Storage (avatars and banners): a few MB. New buckets bill as Google Cloud Storage; in europe-west1 that is about $0.02 per GB-month (the always-free allowance is US-only).

## Storage CORS for the website (one-time per bucket)

Browsers drop images downloaded from Storage unless the bucket allows the website's origin. Without it the web shows no avatars, banners or cover thumbnails; the app is unaffected. The setting lives in `backend/storage-cors.json` (GET/HEAD from the two hosting domains; Storage rules still decide who may read). Apply it with `gcloud storage buckets update gs://<bucket> --cors-file=backend/storage-cors.json` or the Storage JSON API (`PATCH /storage/v1/b/<bucket>` with `{"cors": [...]}`). Applied to `mihontogether.firebasestorage.app` on 2026-10-04.
