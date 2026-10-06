# Run Mihon Together locally

This development fork adds Firebase accounts, accepted friends, opt-in online status/activity, shared libraries, separate social favorites and two-person reading rooms. It uses a local demo Firebase project; no production backend has been created or deployed.

## Tools and endpoints

Work from the repository root. The workspace tools (SDK, AVDs, Gradle cache) live outside the repository; the build helper finds them via `-WorkRoot`, `$env:MIHON_WORK_ROOT`, or `mihon.workRoot=<path>` in the git-ignored `local.properties`, in that order. The pre-existing JDK 21.0.10 is at `C:\Program Files\Java\jdk-21.0.10`. Installed workspace tools include Android platform 37.2, Build Tools and platform-tools; the SDK and Gradle caches live under the outer workspace's `work/`. Debug assembly passed without installing the declared optional NDK. Node 24 was used for local backend checks; backend deployment targets Node 22.

Dot-source `scripts/use-workspace-build.ps1` in each PowerShell terminal. Its environment changes apply to that terminal only. Android Emulator 37.2.12 and an AOSP API 36 default x86_64 revision 2 image are installed in the workspace. The hidden `mihon-together-api36` AVD booted as `emulator-5554` using WHPX. Two Android devices/emulators are still needed to validate shared reading; one booted AVD does not prove multiplayer behavior. Consult the native verification report for actual test evidence.

| Service | Port | Android emulator host |
| --- | --- | --- |
| Firebase Auth | 9099 | `10.0.2.2` |
| Standard Firestore | 8080 | `10.0.2.2` |
| Realtime Database | 9000 | `10.0.2.2` |
| Callable Functions | 5001 | `10.0.2.2` |

The shared project is `demo-mihon-together` and Functions region is `us-central1`. Backend services bind `127.0.0.1` by default. Debug Android builds select the emulator environment automatically; release builds select social disabled unless explicitly configured.

The emulator configuration uses the syntactically valid fake API key `A00000000000000000000000000000000000000` (39 characters) for SDK compatibility. Emulator runs require no real Firebase credentials.

Emulator callables use the [official callable HTTP protocol](https://firebase.google.com/docs/functions/callable-reference) directly at the configured Functions emulator endpoint, with the emulator Auth token, bounded timeouts and redirects disabled. Emulator mode does not initialize Functions/Instance ID cloud registration. Production uses the pinned Firebase SDK registrar path; that branch compiles, but production runtime behavior remains unverified. The registrar selection is tied to Functions 22.1.1 and Instance ID 21.1.0 and must be checked again when the Firebase BoM changes.

## Restart the prepared Android virtual device

After dot-sourcing the build helper, select the AVD's existing discovery directory. The helper normally selects `work/android-user-home`; this AVD was created under `work/android-user`, with userdata in `work/android-avd/mihon-together-api36.avd`.

```powershell
$taskWorkRoot = Split-Path -Parent $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $taskWorkRoot 'android-user'
$env:ANDROID_AVD_HOME = Join-Path $env:ANDROID_USER_HOME 'avd'
$taskAvdLogs = Join-Path $taskWorkRoot 'android-avd'
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices
```

If `emulator-5554` is already present, use that running AVD. To start it when stopped:

```powershell
Start-Process -WindowStyle Hidden -FilePath "$env:ANDROID_HOME\emulator\emulator.exe" -ArgumentList @('-avd','mihon-together-api36','-no-window','-no-audio','-no-boot-anim','-no-snapshot','-no-metrics','-gpu','swiftshader','-memory','2048','-cores','2','-port','5554') -RedirectStandardOutput (Join-Path $taskAvdLogs 'emulator.stdout.log') -RedirectStandardError (Join-Path $taskAvdLogs 'emulator.stderr.log')
& "$env:ANDROID_HOME\platform-tools\adb.exe" -s emulator-5554 wait-for-device
& "$env:ANDROID_HOME\platform-tools\adb.exe" -s emulator-5554 shell getprop sys.boot_completed
```

Wait until `sys.boot_completed` returns `1`. Then forward the Realtime Database port into the emulator:

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" -s emulator-5554 reverse tcp:9000 tcp:9000
```

The RTDB emulator's handshake tells clients to reconnect to `127.0.0.1:9000` (its bind address), and the Android SDK caches that host. Without the reverse mapping, the first connection via `10.0.2.2` works, but after any network drop the client keeps retrying `127.0.0.1` on the device and presence stays offline until the app process restarts. Verified 2026-10-02 (see [two-device evidence](evidence/TWO_DEVICE_READER.md)). Repeat the command per emulator (`-s emulator-5556` for the second one) and after every emulator restart. Production RTDB hosts are not affected.

Once the debug APK has built, install its x86_64 or universal variant:

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" -s emulator-5554 install -r '<absolute-path-to-x86_64-or-universal-debug-apk>'
```

Stop this AVD when finished with `adb -s emulator-5554 emu kill`, using the workspace `adb.exe` path above. These restart/install commands describe the prepared environment; actual application smoke results belong in the native verification report.

## Start the backend and build the app

**Terminal 1**, from the repository root:

```powershell
. .\scripts\use-workspace-build.ps1 -JavaHome 'C:\Program Files\Java\jdk-21.0.10'
npm --prefix backend ci
npm --prefix backend run emulators
```

Keep that terminal running while using social features. Restarting the emulators without export/import resets the local test accounts and data.

**Terminal 2**, also from the repository root:

```powershell
. .\scripts\use-workspace-build.ps1 -JavaHome 'C:\Program Files\Java\jdk-21.0.10'
.\gradlew.bat assembleDebug '-Pdist=local' '-Pinclude-telemetry=false' '-Penable-updater=false' --console=plain
```

The final debug APK assembly passed. The artifacts are under `app/build/outputs/apk/debug/`; install the APK matching each device's ABI, or use `app-universal-debug.apk`. The app label is **Mihon Together Dev** and debug package is `dev.mihontogether.reader.dev`, allowing separate app data from Mihon. [Native verification](NATIVE_BUILD_VERIFICATION.md) records the verified hashes and debug signature validation. The final APK includes activity recovery and the isolated emulator callable transport.

## Connect physical phones

The `10.0.2.2` default is for Android emulators. For USB-connected physical phones, rebuild with the loopback override:

```powershell
.\gradlew.bat assembleDebug '-Pdist=local' '-Pinclude-telemetry=false' '-Penable-updater=false' '-Psocial-emulator-host=127.0.0.1' --console=plain
$taskAdb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
& $taskAdb devices
```

Install that build on each phone. Replace `<device-serial>` below with the serial shown by `adb devices`, and run all four mappings separately for **each** phone:

```powershell
& $taskAdb -s '<device-serial>' reverse tcp:9099 tcp:9099
& $taskAdb -s '<device-serial>' reverse tcp:8080 tcp:8080
& $taskAdb -s '<device-serial>' reverse tcp:9000 tcp:9000
& $taskAdb -s '<device-serial>' reverse tcp:5001 tcp:5001
```

An alternative is a reachable development-host LAN address: explicitly change all four backend emulator bindings to that interface in `backend/firebase.json`, build with `-Psocial-emulator-host=<reachable-host-address>`, and permit those ports on the development machine. Both devices must reach the same host/project. Keep this setup on a trusted development network.

## Create two friends

1. On each device, open **More → Friends** and register a separate email/password account. Email verification/recovery controls are present; emulator messages/actions are local development flows.
2. Save a unique handle and display name on each account. Profiles contain these public identifiers, while email remains account-private.
3. From one account, enter the other's exact handle and send a request. On the recipient, accept the incoming request. Pending requests do not grant access to a library or room.
4. Enable the desired private/friends controls separately: library, favorites, activity, presence and last seen. All begin private. Invisible mode suppresses visible status and reader sharing.
5. Add local titles normally, choose separate social favorites in Friends, then press **Share current library and favorites**. Mihon's library membership and social favorites are independent.
6. Open the accepted friend to inspect their published library/favorites, available online/last-seen status and opted-in current reading activity. Remove or block a friend to revoke access.

Publication is deliberate. A privacy change advances the sharing generation, so older library/favorite entries become unreadable. Use **Share current library and favorites** again to intentionally share eligible current entries. Hidden titles cannot be republished until you press **Restore** on them in Friends. Restoring only clears the hidden marker; share again to make the title visible.

## Read together

1. Install the same supported source/edition on both devices, add the same title locally, and load the intended chapter. Each device supplies its own pages; the backend does not transfer manga images or source credentials.
2. On the host device, open the reader overflow menu → **Read together**. Check the edition, chapter and page order with the friend, then confirm page order for this chapter. Select the accepted friend to send an invitation.
3. On the guest device, open **More → Friends**, join the invitation, and open the matching local title in the reader. The selected room is handed to the reader in memory.
4. Open **Read together**. Choose **Follow host** to load the exact matching local chapter, then confirm that chapter's edition and page order. Page movement waits for explicit confirmation. Confirm again when the chapter changes.
5. Turn Follow host off to read independently. Leave/close ends this two-person room. Exiting the reader attempts room leave; backgrounding pauses following and stops host renewals.

Matching checks include source, work, edition, chapter and explicit page mapping. Equal titles or page counts are insufficient. Query-addressed URLs and local/unsupported source identities fail closed in this implementation; these need source-specific stable-locator support. Missing chapters or mismatched editions show a mismatch instead of a blind page jump. See [reader behavior](READER_INTEGRATION.md) for the exact protocol.

Incognito, hidden titles, invisible mode, account changes and friendship removal stop local sharing/following. Network failures can leave server removal pending; activity expires after 120 seconds and host room leases expire after 90 seconds without renewal. Offline reading continues, and stale page turns are not queued for later publication.

The foreground privacy guard retains observed source IDs per account before permitting publication. Removing the last local title or restarting the app keeps those source observers, so previously published manual snapshots can still be revoked by source incognito. Four regressions for this additional guard fix are awaiting the latest native run.

## Verification and remaining work

[Backend verification](../backend/VERIFICATION.md) records TypeScript compilation, **18 domain tests and 11 emulator scenarios passing**. They cover authorization, privacy generations, pagination, room writes and multi-device presence. Native formatting, **91 unit tests**, migration checks and debug APK assembly passed; [native verification](NATIVE_BUILD_VERIFICATION.md) records that run and artifact validation. The backend activity missing/publish/clear/republish regression and native callable protocol regressions passed. Android application smoke testing is still running. No two-device Android reader or instrumented UI test has been completed. [The implementation checkpoint](IMPLEMENTATION_CHECKPOINT.md) collects current evidence and remaining work.

Reproduce checks from the repository root:

```powershell
npm --prefix backend test
npm --prefix backend run test:emulators
.\gradlew.bat spotlessCheck testDebugUnitTest verifySqlDelightMigration '-Pdist=local' '-Pinclude-telemetry=false' '-Penable-updater=false' --console=plain
```

Production requires the final fork identity/signing and distribution setup, a real fork-owned Firebase project with Standard Firestore, an Android App Check provider, reviewed Rules, tested quotas/retention/account deletion, and real two-device source mapping and lifecycle/presence checks. Production callables enforce App Check; supplying project keys alone does not complete that setup. The [API](SOCIAL_API.md), [integration review](INTEGRATION_REVIEW.md) and original [build plan](SOCIAL_BUILD_PLAN.md) document these boundaries.
