# Building Mihon Together

## Requirements

* JDK 21
* Android SDK (platform and build tools from `gradle/libs.versions.toml`)
* Node.js 22+ for the backend and the website

## App

```bash
./gradlew assembleDebug -Pdist=local -Pinclude-telemetry=false -Penable-updater=false
```

Debug builds talk to the local Firebase emulators (project `demo-mihon-together`) at `10.0.2.2`, so the social features work without any cloud project. For a phone on USB, build with `-Psocial-emulator-host=127.0.0.1` and run `adb reverse` for ports 9099, 8080, 9000, 5001 and 9199.

Checks:

```bash
./gradlew spotlessCheck testDebugUnitTest verifySqlDelightMigration -Pdist=local -Pinclude-telemetry=false -Penable-updater=false
```

Optional local settings go in the git-ignored `local.properties`, for example `mihon.discordAppId=<your Discord application ID>` for Discord status (with the Discord Social SDK placed in `third_party/discord-social-sdk/`).

## Backend (Firebase)

```bash
npm --prefix backend ci
npm --prefix backend test              # domain tests
npm --prefix backend run test:emulators # rules and callables on the emulators
```

To run your own production instance, follow [docs/FIREBASE_SETUP.md](docs/FIREBASE_SETUP.md) and [docs/PRODUCTION.md](docs/PRODUCTION.md): copy `backend/production.example.json` to `backend/production.json` (git-ignored) and fill it from your Firebase console.

## Website

```bash
npm --prefix web ci
npm --prefix web run typecheck
npm --prefix web test
npm --prefix web run build
```
