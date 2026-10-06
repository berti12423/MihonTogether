# Android build verification

## Re-verification 2026-10-02 (current)

Branch `test/reverify` on top of `checkpoint-codex-2026-10-01`, same JDK 21.0.10 / workspace SDK. This is the first native run that includes the source-history privacy fix.

| Check | Result |
| --- | --- |
| `npm --prefix backend ci` | Passed |
| `npm --prefix backend test` | 18 passed, 0 failed |
| `npm --prefix backend run test:emulators` | 11 passed, 0 failed |
| `spotlessCheck testDebugUnitTest verifySqlDelightMigration` (`-Pdist=local -Pinclude-telemetry=false -Penable-updater=false`) | BUILD SUCCESSFUL; 13 suites, **95 tests**, 0 failures/errors/skipped. Includes the 4 `ProjectionSourceHistoryTest` cases that had never run. |
| `assembleDebug` (same flags) | BUILD SUCCESSFUL in 50s |
| Single-device smoke (AVD API 36) | Passed: signup, verify, profile, presence on/off, invisible, sign-out cleanup. See [evidence](evidence/REVERIFY_SMOKE.md). |
| Two-device reader | Not yet run in this pass |

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| app-universal-debug.apk | 142185367 | `71B40DD64D4DAE7A4041B9C5AA7327F015506C5AB8CFF09C574EA9D25547A45A` |
| app-arm64-v8a-debug.apk | 69467887 | `0DEDBA5C07AFE7438960109F2E63B9A01A6C34600AEFECD7AD7687BA2A5E9328` |
| app-armeabi-v7a-debug.apk | 62503673 | `1D652B276CBB53BE6D08D532087E9D62F0A3BAD7524FD27033C90B1821F79C84` |
| app-x86_64-debug.apk | 71993766 | `54EB9AC9B78E8DD4131EA2782846563D4697E24399F819B15EC0BD3F0F7383B3` |
| app-x86-debug.apk | 72149633 | `0E72DB756DE7AD9E3A1CA64943CA914CECAEC527E23F9E6C22CF3859FB53A572` |

Environment note: on Windows, `firebase emulators:exec` left the Firestore and RTDB emulator JVMs running after the test suite exited, which blocked a later `emulators:start` (port taken). Kill stale `java.exe` processes holding ports 8080/9000 before starting the emulators.

APK signature/manifest was not re-checked with `apksigner` in this pass; the install and launch on the AVD succeeded with package `dev.mihontogether.reader.dev`.

## Previous checkpoint (2026-10-01)

Checked on 2026-10-01 after the Firebase social client, privacy guards, reader integration, activity recovery and isolated emulator callable transport landed. Final formatting, 91 unit tests, SQLDelight migration verification and debug APK assembly passed. A repeat Android smoke on this final APK is running; two-device reader validation remains unfinished.

## Installed workspace tools

- JDK 21.0.10: `C:/Program Files/Java/jdk-21.0.10` (pre-existing installation).
- Gradle 9.8.0: downloaded by the unmodified wrapper into the outer workspace's `work/gradle-home`.
- Android SDK: outer workspace `work/android-sdk`, selected through the repository's ignored `local.properties`.
- Android command-line tools 23.0: Google's `commandlinetools-win-16111833_latest.zip`, verified against SHA-1 `57d04f2d75eb8e8fffc5000a987e5de4b5a63e9d` from [Google's repository metadata](https://dl.google.com/android/repository/repository2-3.xml).
- Android platform 37.2 revision 1, Build Tools 37.0.0, platform-tools 37.0.1: downloaded from Google's repository; expected `android.jar`, `aapt2.exe`, and `adb.exe` exist. Gradle additionally installed its default Build Tools 36.0.0 while preparing compilation.

The user explicitly accepted the [Android SDK License Agreement](https://developer.android.com/studio/terms) and requested workspace installation. No global environment variables were changed. Android Emulator 37.2.12 and the AOSP API 36 default x86_64 revision 2 image are installed in the workspace. The hidden `mihon-together-api36` AVD booted as `emulator-5554` using WHPX, with `sys.boot_completed=1`.

NDK 29.0.14206865 is declared but not installed. Source inspection found no `externalNativeBuild` tasks, and the complete debug build passed without that NDK. The raw environment checker treats the declaration as mandatory; [the assessed environment report](environment-report.json) preserves its false result while recording the actual build evidence.

## Checks and limits

| Check | Result |
| --- | --- |
| `gradlew --version` on JDK 21 | Passed; Gradle 9.8.0, launcher/daemon JDK 21.0.10. |
| `gradlew help -Pdist=local -Pinclude-telemetry=false -Penable-updater=false` | Passed after Firebase catalog aliases landed; build logic and application configuration succeeded. |
| `:app:compileDebugKotlin` with the same flags | Passed, including the social/reader integration. |
| Environment helper execution | Passed with the selected JDK; created workspace cache directories and selected the SDK. |
| Both new PowerShell scripts' parser check | Passed. Installer commands match the successful installation flow, but the complete installer script has not been exercised from an empty SDK directory. |
| `git diff --check` | Passed at this checkpoint. |
| `spotlessCheck` | Passed in the combined Gradle run. |
| `testDebugUnitTest` | Passed: 12 JUnit suites, 91 tests, 0 failures, 0 errors, 0 skipped. The app has 30 tests: 6 migration tests and 24 new social tests. |
| `verifySqlDelightMigration` | Passed in the combined Gradle run. |
| `:app:assembleDebug` | Passed; universal and four ABI-specific debug APKs produced. |
| APK manifest and signature | Passed with `aapt2`/`apksigner`; expected debug identity and AndroidDebug v2 signature verified. |
| ADB/AVD | Prepared AVD booted. Interim APK passed account/profile/friend/presence cleanup smoke; final transport APK retest pending. No two-device reader or instrumented UI test completed. |

The verified invocation completed with exit code 0 in **39 seconds**, with 472 actionable tasks (29 executed, 443 up-to-date). Its log is in the outer workspace at `work/social-verified-build.log`. The 24 new app tests comprise completion gate (2), content identity (4), source privacy fence (1), reader protocol (6) and callable protocol (11) tests. They cover cancellation/operation ownership, ambiguous identities, privacy acknowledgement cycles, explicit mapping, stale sequence/epoch handling, coalescing and echo suppression.

## Verified debug artifacts

Artifacts from the passing run are under `app/build/outputs/apk/debug/`. Manifest validation reports application ID `dev.mihontogether.reader.dev`, label **Mihon Together Dev**, version code `33`, version `0.20.4-1`, target SDK `36` and compile SDK `37` (Android 17). The verified certificate is the development **AndroidDebug** certificate; these are debug-signed artifacts. A later build can replace these files; the hashes below identify this checkpoint.

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| [Universal](../app/build/outputs/apk/debug/app-universal-debug.apk) | 148152639 | `F227C95E08BF476D4F917A7AB5FC1351202CB60F0800D894B2748CCBA5BA80D2` |
| [arm64-v8a](../app/build/outputs/apk/debug/app-arm64-v8a-debug.apk) | 75435159 | `201D133CF763B71889136BB6327914A49A0EA1D5377AA1B1922AB1328B5AD6C3` |
| [x86_64](../app/build/outputs/apk/debug/app-x86_64-debug.apk) | 77961038 | `18260AEA732F412B3BDC06D8ADF9D34914013590B8C3D9ACF9B3DD4C1D4D4C5C` |

`app-armeabi-v7a-debug.apk` and `app-x86-debug.apk` were also assembled. [Implementation status](IMPLEMENTATION_CHECKPOINT.md) and [local setup](MULTIPLAYER_SETUP.md) describe the implemented flows and remaining device/release work.

An interim debug APK with corrected emulator configuration passed native signup, email verification/refresh, profile save with private defaults, handle lookup/request, incoming request acceptance, friend library/favorites, opted-in presence and sign-out cleanup on AOSP API 36 x86_64/WHPX. The final transport-isolated APK still requires a repeat smoke tied to its hash. Evidence includes [onboarding](evidence/native-start.png), [signed-out library](evidence/native-library-signed-out.png), [profile](evidence/native-profile-saved.png), [friend detail](evidence/native-friend-detail.png) and [logged-out UI](evidence/native-logged-out.png).

The final source isolates emulator callables through the [official callable HTTP protocol](https://firebase.google.com/docs/functions/callable-reference), without Functions/Instance ID registration or redirects; production retains the pinned SDK registrar path. Eleven pure JVM protocol regressions passed for nested values, typed 64-bit integers, envelopes and failure handling. Production registration compiles but has not been runtime tested; revalidate the version-bound registration when changing the Firebase BoM.

The first sandboxed wrapper download failed with `Permission denied: getsockopt`. The required official downloads and dependency resolution succeeded through approved network escalation. Early configuration attempts encountered incomplete catalog aliases during concurrent implementation, and an initial ADB attempt could not create its user directory. Workspace paths and subsequent runs resolved these setup failures. The passing build reports Gradle deprecations that should be reviewed before upgrading to Gradle 10.

## Reproduce in PowerShell

From the repository root, select the installed workspace environment (dot-source the script so the current terminal keeps its process-local settings):

```powershell
. .\scripts\use-workspace-build.ps1 -JavaHome 'C:\Program Files\Java\jdk-21.0.10'
.\gradlew.bat spotlessCheck testDebugUnitTest verifySqlDelightMigration '-Pdist=local' '-Pinclude-telemetry=false' '-Penable-updater=false' --console=plain
.\gradlew.bat assembleDebug '-Pdist=local' '-Pinclude-telemetry=false' '-Penable-updater=false' --console=plain
```

For an empty workspace SDK, first read the license linked above and run only if you accept it:

```powershell
.\scripts\install-workspace-sdk.ps1 -AcceptAndroidSdkLicense -JavaHome 'C:\Program Files\Java\jdk-21.0.10'
```

Both scripts accept `-WorkRoot` if the outer workspace differs, and the environment helper accepts another JDK 21 installation through `-JavaHome`. The installer preserves the repository's pinned platform version. Android command-line tools 23 delegate to [Android CLI](https://developer.android.com/tools/agents/android-cli); package IDs use `/` rather than deprecated sdkmanager's `;`. CLI calls disable metrics. Package installation is verified by required files because the deprecated wrapper returned exit code zero even when it failed to resolve some semicolon package names.
