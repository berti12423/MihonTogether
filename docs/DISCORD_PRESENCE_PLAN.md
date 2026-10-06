# Discord Rich Presence: plan

Status (2026-10-02): **D0–D2 done.** Social SDK 1.10.19337 is in `third_party/` (git-ignored) and Application ID 1555632713023758372 is in `local.properties`. The JNI bridge builds for all four ABIs with NDK 27.3 and CMake 3.31.6. On the emulator (no Discord app) the SDK loads, connects its RPC manager with our client ID, gets "connection closed" and retries quietly, with no crash. Not yet seen on a real Discord profile; that needs a phone with Discord signed in.

Update 2026-10-03 (feedback F1): Berti's first test didn't show anything. Likely causes were that the status cleared the moment the reader left the screen (so it vanished when switching to Discord) and that failures were invisible. Settings → Discord now has **Send test status** with the result, the status stays 10 minutes after the reader goes to the background, and the bridge reports Discord's error text.

Build notes: `app/build.gradle.kts` finds the AAR, unpacks it under `build/discord-sdk/`, adds it as a dependency, and builds `app/src/main/cpp` against it. The manifest strips the SDK's voice permissions (microphone, Bluetooth, audio settings, microphone/media foreground service) and its login activity and service. The test APK grows by about 9 MB per ABI. Without the SDK folder the build uses the no-op backend.

Goal: while you read, your Discord profile shows **the title, its cover, and the chapter** (plus elapsed time). It only works for sources you allow, so nothing from other sources (NSFW, private) ever appears.

## How others do it, and which way we go

| Approach | How it works | Verdict |
| --- | --- | --- |
| **Discord Social SDK, RPC without auth** (official) | Since Social SDK 1.10 (July 2026), an Android app can publish Rich Presence to the **signed-in Discord app on the same phone**: no login, no token, only a registered Application ID. ([docs](https://docs.discord.com/developers/discord-social-sdk/development-guides/setting-rich-presence), [mobile](https://docs.discord.com/developers/discord-social-sdk/core-concepts/mobile)) | **Chosen.** Official and terms-compliant, and nothing to sign into in our app. Needs the Discord app installed and signed in. |
| Kizzy-style gateway (Animiru, Yomiko/TachiyomiSY-RPC) | The user logs into Discord in a WebView; the app takes the **user token** and speaks the Discord gateway as that user. | **Not doing it.** Automating a user account goes against Discord's terms, risks the account, and means holding a credential that controls the whole Discord account. |
| Social SDK with account linking (OAuth) | Same SDK, signed-in link per user. | Not needed on Android, and more moving parts. |

Upstream Mihon closed the request as not planned ([mihon#2842](https://github.com/mihonapp/mihon/issues/2842)), so there is nothing to stay compatible with.

## What it shows

- **Name:** the Discord application's name, "Mihon Together" ("Playing Mihon Together"; Discord has no "Reading" activity type, so we use Playing as the docs recommend).
- **Details:** the title (for example "Omniscient Reader").
- **State:** "Chapter 12", or "Chapter 12 · reading with Shikori1" during Read together. A Read together room can also show as party 2/2.
- **Large image:** the cover's `https` URL (external URLs are allowed, and GIF/WebP work). It falls back to the app logo for local or custom covers, or when "show covers" is off.
- **Small image:** the app logo (an uploaded asset named `logo`).
- **Timestamps:** start time of the current chapter, so Discord shows "12:34 elapsed".

## Privacy rules
- Off by default. An app-wide toggle in More → Settings → Discord.
- **Per-source allowlist** (Berti's requirement): only titles from sources you switch on are shown. New sources start off.
- Never shown for sources in incognito mode, or for titles hidden from friends.
- Separate toggles for the cover and the chapter.
- The presence clears when the reader is left, the app goes to the background, or the source/title stops qualifying.

## Architecture
```
ReaderViewModel state ──► DiscordPresenceController (pure rules: enabled? allowlisted? incognito? debounce)
                                │ PresenceInfo(details, state, coverUrl, startedAt, party)
                                ▼
                         DiscordPresence (interface)
                         ├─ NoopDiscordPresence        (no SDK in the build, or no Application ID)
                         └─ SocialSdkDiscordPresence   (JNI → libdiscord_partner_sdk.so: SetApplicationId,
                                                        UpdateRichPresence, ClearRichPresence, RunCallbacks)
```
- **Native bridge:** a tiny `discord_bridge.cpp` built with CMake (`externalNativeBuild`) **only when the SDK is present** in `third_party/discord-social-sdk/` (git-ignored, since the SDK is proprietary and must not be committed). Without it the build uses the no-op, so CI and other contributors still build.
- `discordpp::RunCallbacks()` must be pumped. A coroutine ticks it every 250 ms while presence is active, and stops otherwise.
- **Updates:** on chapter change and reader start, at most one every 15 s, never per page. Clear on reader stop.
- **Application ID:** from `-Pdiscord-app-id` or `local.properties` into `BuildConfig.DISCORD_APP_ID`. It's a public identifier, but kept out of git like the Firebase config.

## Steps for Berti (D0), about 10 minutes
1. https://discord.com/developers/teams → create a team.
2. https://discord.com/developers/applications → **New Application** named "Mihon Together" (the name shows on profiles), assigned to the team.
3. **Rich Presence → Art Assets:** upload the app icon (512×512 PNG) named `logo`.
4. **Discord Social SDK → Getting Started:** fill in the form ("a manga reader app; we use Rich Presence only") and submit.
5. **Discord Social SDK → Downloads:** download the latest **C++** SDK (1.10 or newer) and unzip it into `third_party/discord-social-sdk/`.
6. Send me the **Application ID** (General Information page).

## Phases
| Phase | Scope | Verification |
| --- | --- | --- |
| D0 | Berti's registration and SDK download | Application ID received, SDK unzipped |
| D1 | Settings (toggle, allowlist, cover/chapter toggles), the pure presence rules, no-op backend, reader wiring | Unit tests for the rules (allowlist, incognito, hidden, cover fallback, debounce, clear on leave) |
| D2 | JNI bridge and CMake gated on the SDK, `SocialSdkDiscordPresence`, callback pump | Builds with and without the SDK, logcat shows a successful `UpdateRichPresence` on a phone with Discord signed in |
| D3 | Read together party/state and polish | Two phones in a room, both profiles show "with …" |

## Open risks
- The Social SDK form asks about "your game". A reader app may need a sentence of explanation; approval is automatic per the docs, but that's untested.
- Cover hotlinking: some sources block requests without their referer, and then Discord shows no image. The fallback is the logo (we can't detect this from the app).
- Discord rate limits presence updates (undocumented); we stay well below by updating only per chapter.
