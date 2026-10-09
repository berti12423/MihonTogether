# Mihon Together – privacy policy (DRAFT)

> Draft, updated 2026-10-06 (review fixes: hiding a title deletes its review, a separate reading-progress switch, ranking pictures only while actually on the ranking; earlier the same day: ranking pictures and the all-time ranking; reading progress, ratings and reviews); 2026-10-03 to cover the global ranking, XP, private and group messages, push notifications, read-along requests, group rooms, "Let's read this" matches, reading-together recaps and translation. Served on the web at `/privacy.html` (see [web integration](WEB_INTEGRATION.md)).

**Operator:** Berti (Mihon Together) · **Contact:** [GitHub issues](https://github.com/berti12423/MihonTogether/issues) (ask for a private channel for anything personal) · **Last updated:** 9 October 2026

## Short version

- The manga reader works without an account. Your local library, reading history and downloads stay on your device and are never uploaded.
- The social features (friends, sharing, reading together, messages) need an account. **Accounts created since 6 October 2026 share with their accepted friends by default** (library and favorites lists, reading activity, history, daily stats, online status and last seen), and you can turn any of it off, or go invisible, at any time. Accounts created earlier keep the choices they made. Nothing is shared beyond your friends except the global ranking described below. The one exception is the **global ranking**, which starts on: it shows only your name, handle, profile picture and banner, chapter counts and level, and you can turn it (or just the pictures) off at any time. Ratings and reviews you choose to write are shown to your friends unless you turn the "Reviews" switch off.
- There are no ads, no analytics and no crash reporting in this app.
- Messages are kept for 30 days and then deleted automatically.
- You can delete your account and all of its social data at any time, in the app or on the web.

## What we store when you create an account

| Data | Why | Who can see it | How long |
| --- | --- | --- | --- |
| Email address and password (handled by Firebase Authentication; we never see your password) | Sign-in, verification, password reset | Only you | Until you delete your account |
| Handle and display name | So friends can find and recognise you | Your accepted friends; someone you send a request to sees them on the request; everyone, if you're on the global ranking | Until you change them or delete your account |
| Friend requests, friendships and blocks | The friends feature | The two people involved (blocks: only you) | Until removed or your account is deleted |
| Privacy settings | To enforce what you share | Only you | Until account deletion |
| Optional bio, accent colour, avatar and banner images (PNG/JPEG/WebP/GIF) | Personalising your profile | Your accepted friends; the avatar and banner also every signed-in user you haven't blocked while you actually have a place on the all-time global ranking (you're opted in, not invisible, and have read at least one chapter since joining it) with "Show my picture and banner on the ranking" on (the default) | Until you remove them or delete your account |
| XP and level: total XP, chapters finished, chapters read together, reading streak | The level system | Only you; friends too if you share reading stats; your level appears on the global ranking if you're on it | Until you delete your account |
| Push notification tokens (up to 10 devices) | Sending notifications for friend requests, invites and messages | Nobody; only the server uses them | Until you sign out on that device or delete your account |

XP is counted for chapters you finish while signed in, outside incognito sources and outside invisible mode, even if you don't share stats. It stays private unless you share stats with friends; once you do, friends see your totals, achievements and weekly XP, including what you earned before you turned sharing on.

## What you can choose to share

| Setting | What is shared | Default | Retention |
| --- | --- | --- | --- |
| Reading progress (part of Library) | For library titles you've opened: how many chapters you've read, how many there are, and whether you've read them all. Shown to friends on that title's page ("Alex read this", "40 of 120 chapters read") and on your library covers. Updated when you publish your library and at most every 6 hours while you use the app (not while incognito; titles from incognito sources never get progress). Has its own "Share reading progress" switch: turned off, your library is shared without progress (the server drops it even if an older app sends it). | Off (only sent while Library is shared; then on unless you turn "Share reading progress" off) | Like the library: until you stop sharing it or delete your account |
| Ratings and reviews | Stars (1–5) and optional text (up to 1,000 characters) you write for a title, with that title's shared metadata. Shown to your accepted friends on the title's page, on your profile and on the website. | **On** for reviews you write (the "Reviews" switch; nothing is shared until you rate something) | Until you delete the review or your account; turning the switch off hides them from friends right away and keeps them for you |
| Library / Favorite titles | A metadata list of titles: title, an opaque source identifier, hashed keys, the AniList or MyAnimeList ID when you track the title there (not your progress) and, if "Share covers" is on, the public cover image link from the source plus a small copy of the cover (about 25 KB, friends-only, deleted when you stop sharing it or turn covers off). Never your device's database IDs or downloaded pages. | On for new accounts (since 2026-10-06); older accounts keep their choice | Until you stop sharing (old lists become unreadable immediately) or delete your account |
| Current reading activity | The title and chapter you're reading right now | On for new accounts (since 2026-10-06); older accounts keep their choice | Expires after about 2 minutes without updates |
| Recent activity history | "Read chapter X of Y" and "Added Y to favorites" | On for new accounts (since 2026-10-06); older accounts keep their choice | 7 days, at most 50 items |
| Daily reading stats | Today's chapters, pages and streak, for the friends ranking | On for new accounts (since 2026-10-06); older accounts keep their choice | 8 days |
| Global ranking | Your display name, handle, accent colour, avatar and banner (unless you turn pictures off), today's chapter count, streak and level, on a public top-50 list visible to every signed-in user, plus an all-time top 50 by total XP with your lifetime chapter count | **On** (shown once with a "Turn it off" button) | Each day's list is deleted after 8 days; the all-time list is kept; leaving removes you from both immediately |
| Online status / Last seen | Whether the app is open, and when you were last online | On for new accounts (since 2026-10-06); older accounts keep their choice | Updated while you use the app |
| Invisible mode | Hides all of the above, including the global ranking and XP counting | Off | n/a |

Hidden titles and titles read from incognito sources are never shared, and can't be rated. Hiding a title also deletes the review you wrote for it. A review you wrote before turning a source incognito stays visible to friends until you delete it (the server doesn't know which sources are incognito on your device). Reviews are hidden while you're in invisible mode or have the Reviews switch off; progress while you're not sharing your library or have "Share reading progress" off. Friends' apps notice such changes within a few minutes, or at once when they open your profile.

## Messages

- **Private messages** go between two friends. Only the two of you can read them, and only while you're still friends. Each message and your conversation list entry are deleted 30 days after they're sent.
- **Group chats** have up to 20 people. A friend can add you; you can leave at any time. Members see each other's display names, handles and accent colours, even if they aren't friends with each other, and messages from the point they joined. Messages, including notices like "X joined", are deleted 30 days after they're sent. If you leave, earlier messages you sent stay visible to the remaining members until they expire.
- **Reactions and replies:** an emoji reaction is stored on the message with your account ID, and a reply stores a short quote (who wrote the original and its first 100 characters). Everyone who can read the message sees them (in a group, the apps show a quote of a message from before someone joined only as "Earlier message" to that person), and they're deleted with the message after 30 days. If you leave a group or delete your account, reactions you left in a group stay on those messages until they expire (shown as a count, without your name). Reactions don't send notifications.
- **Reading-together recaps:** when you and one friend read together in a room for at least 5 minutes, a line like "Read together: {title}, chapter X to Y, N minutes" is added to your private chat with that friend when the room ends. It's an ordinary message in that chat: only the two of you see it, it doesn't send a notification, and it's deleted after 30 days like other messages. Rooms with three or more people don't create one.
- **Notifications** carry the sender's name and the first 100 characters of a message to your phone through Google Firebase Cloud Messaging. You can turn each kind off in the app.

## Reading together

Reading rooms hold up to 6 people. Everyone in a room sees the title, chapter and page position, which source and extension the host reads from (so others can install it), the members' names and colours, and chat messages and reactions sent in the room. **In a room with more than two people, members can see each other's names even if they aren't friends with each other.** When the host shares the source, members also see the title's address within that source (the link the extension uses, so their app can open the same title); it's stored with the room and goes away with it. Each device loads pages from its own sources; no pages are uploaded. Room chat is deleted when the room closes, and rooms expire about 90 seconds after nobody keeps them alive.

**"Let's read this":** you can mark a title you'd like to read with a particular friend. That wish is stored on the server (the title's shared metadata, the friend, and when you added it) but is **never shown to your friend unless they mark the same title for you too**. Only then do you both see the match, a line in your private chat and a notification to the friend who marked it first. You can take a wish back at any time; wishes are deleted automatically after 60 days and when you delete your account. You can add at most 50 a day.

**Ask to read along:** when you ask a friend to read along, they see your name and handle for 10 minutes or until they answer. You can ask at most 10 times an hour.

## Translation

Page translation is optional and off until you switch it on in the reader.

- **On this phone** (the default): text recognition and translation run on your device. The first use downloads a language pack from Google; no pages or text leave your phone.
- **DeepL, Papago or Gemini**: these use your own key with that company. Recognised text (DeepL, Papago) or the page image (Gemini) is sent **directly from your phone to that service** under its own terms; it never passes through our servers. Your keys are stored only on your device and are never included in backups.
- Translations are cached only on your device.

## Discord

If you turn on Discord status in the settings, the app tells the Discord app on your phone what you're reading, for the titles and sources you allow. That goes to Discord under Discord's terms; we don't receive it.

## Website

The website shows the same data as the app with the same rules. To show covers, your browser looks up titles on AniList's public API (graphql.anilist.co) and remembers the results in your browser for 30 days. The download page serves the app's installation file.

**My library on the website.** When you open "My library" on the website, your browser connects directly to your own phone (signed in to the same account) over an encrypted WebRTC connection, and your library, covers and pages travel straight from the phone to the browser. They are not stored on our servers. To set up the connection, the phone and the browser exchange a few short technical messages through our database: your phone's name (for example "Galaxy A35"), the app's build number and the connection details (network addresses). Only you can read them, and they are deleted automatically within minutes (at most 15). During setup both sides ask Google's public STUN server (stun.l.google.com) for their public IP address, as every WebRTC connection does. Reading progress you make on the website is saved on your phone like reading in the app. You can turn this off in the app under Friends → Me ("Library on the web").

## Technical and abuse-prevention data

- Short-lived records that stop actions from being applied twice (kept up to 30 days).
- Per-day usage counters and rate limits (for messages, requests, groups, rooms and read wishes).
- An app-integrity check (Firebase App Check / Play Integrity) on requests, when enabled.

## Where data is processed

Data is stored with Google Firebase (Cloud Firestore, Realtime Database, Cloud Functions, Authentication, Cloud Storage, Cloud Messaging) in the European Union (Google Cloud region europe-west1, Belgium). Google acts as our processor; see Google's Firebase privacy information.

## Your rights and choices

- Change what you share at any time in **More → Friends**. Turning a category off makes previously shared data unreadable immediately.
- **Delete your account** in **More → Friends → Delete account**, or on the website under Account. This permanently removes your profile, handle, friendships, pending requests, shared lists, activity, history, stats, ranking entries, XP, ratings and reviews, rooms, private conversations, "Let's read this" wishes (including the matching entry on your friend's side), read-along requests, push tokens and usage counters, takes you out of every group chat (ownership passes to another member), and then deletes your sign-in account. Other people keep only their own records (for example their own block list), and group messages you sent stay with the group, under your display name, until they expire.
- For access or correction requests, open an issue at [github.com/berti12423/MihonTogether/issues](https://github.com/berti12423/MihonTogether/issues) and ask for a private channel; don't post personal details publicly. You can also see and change most of your data in the app, and delete everything with **Delete account**.

## Children

The online features are not directed at children under 13, and you must be at least 13 (or older where the law requires it) to create an account.

## Changes

We will update this page and the "last updated" date when the policy changes.
