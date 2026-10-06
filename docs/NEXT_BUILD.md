# Next build plan (proposed 2026-10-06)

Five features, built in this order: each one is useful on its own and ships when it's tested. Ask Berti before deploying. Open questions are at the end.

## 1. No notifications for the chat you're looking at

**Problem:** a message push still pops up when that chat (or group, or room) is open on screen.

**Plan:**
- A small app-wide "what's on screen" holder (`SocialForeground`): the chat, group or room that's open while the app is in the foreground. Chat, group and room screens set it when they open and clear it when they close or the app goes to the background.
- `SocialMessagingService.onMessageReceived`: skip the notification when the push is for what's on screen (same chat partner, group or room) and the app is in the foreground.
- Opening a chat also clears that chat's existing notifications.
- App only, no backend change.

**Test:** unit test for the "skip?" decision; on the emulator, a push for the open chat shows nothing, and a push for another chat still shows.

## 2. Chat like a messaging app: reactions and replies

Private chats and group chats (room chat already has reactions).

**Backend (`socialMessage`, `socialGroupMessage`):**
- **Reply:** `send` takes an optional `replyTo` (the message's seq). The server stores a small quote with it: `{seq, senderUid, preview}`, the preview being the first 100 characters (or "📖 title" for a recommendation).
- **React:** a new action `react` with `{seq, emoji}`. Each person has at most one reaction per message (sending the same emoji again removes it), stored on the message as `reactions: {uid: emoji}`. Emoji from a fixed set (see question 2). It doesn't count as unread and doesn't push.
- Same limits as today (rate limit, friends only, members only for groups). Tests for both.

**App and website:**
- Long-press a message: a reaction bar (the emoji set) plus **Reply** and **Copy**.
- Swipe a message right to reply (app).
- A reply shows the quoted message above the text; tapping the quote scrolls to the original.
- Reactions show as small chips under the bubble with counts ("❤️ 2"); tapping one adds or removes yours.

## 3. Recommend menu with covers

**Problem:** the 📖 picker in chats only lists names.

**Plan:**
- Change `TitlePickerDialog` into a full-screen picker with a search field and a cover grid (3 per row, title under each cover), like the library.
- Covers come from the phone's own library (the same cover cache the library uses), so they load fast and work for sources that block outside requests.
- The same picker is used everywhere it appears: chat recommendations, group recommendations, "Read together" from a chat.
- App only.

## 4. "Friends who read this"

**What it does:** on a title's page in the app (and in a friend's library on the website), a line like "Read by Alex and Shikori1" with their avatars, and how far they got ("finished", or "chapter 40").

**Plan:**
- Friends' shared libraries gain optional progress per title: `progress: {read, total, finished}`. They're published from the phone together with the library, and only when that person shares their library with friends (no new data goes out otherwise).
- **Matching titles across sources:** by tracker ID (AniList/MAL) when the title is tracked; otherwise by the same source title (`sourceWorkKey`); otherwise by a normalized title (lowercase, without punctuation or "(Official)"-style suffixes). A friend reading the same series on another source still matches.
- **Title page:** a "Friends" row under the description with avatars; tapping it lists each friend and their progress, and opens their profile.
- Friends' libraries are already loaded for the friend list, so this adds no reads beyond the small progress field.

## 5. Ratings and reviews

**What it does:** rate a title you've read (stars) and optionally write a short review; friends see it on that title's page, on your profile, and in their feed.

**Backend:**
- A new callable `socialReview` (`set` / `delete`): `{content, rating, text}`. Rating as chosen in question 3; text up to 1,000 characters, optional.
- Stored as `reviews/{uid}/items/{workKey}`, with `workKey` from the same matching as feature 4, so friends' reviews of the same series line up.
- **Who sees it:** controlled by a new privacy switch "Reviews", starting at friends (see question 4). Firestore rules: owner, plus friends while the switch is on. Rate limit of 30 reviews a day. Deleted with the account.
- A feed item "Alex rated Solo Leveling ★★★★☆", using the existing feed sharing rules.

**App:**
- **Title page:** a "Rate" button (only after you've read at least one chapter of it). It opens stars plus an optional text box, and shows your existing review for editing or deleting.
- **Title page:** the "Friends" row from feature 4 also shows each friend's stars; tapping it shows their review text.
- **Friend profile:** a "Reviews" tab with their rated titles and covers.

**Website:** friends' reviews on their profile page; writing reviews stays in the app at first.

**Privacy policy:** add reviews and reading progress (who sees them, how long they're kept, deleted with the account).

## 6. Discord status stops working after leaving the app ("Discord refused it: ErrorType 9")

**Problem (Berti, 2026-10-06):** after leaving the app and coming back, the Discord status no longer updates, and Settings → Discord shows "Discord refused it: ErrorType 9" (an RPC error).

**Likely cause:** `SocialSdkDiscordPresence.attach()` gives the SDK an Activity (`DiscordSocialSdkInit.setEngineActivity`) only on the first call, because it returns early once `initialized` is set. After the reader closes, the SDK still holds the destroyed Activity, so its RPC connection to the Discord app fails.

**Plan:**
- Call `setEngineActivity` on every `attach` (each new reader or settings screen), not just the first time.
- On an RPC failure (ErrorType 9): tear down and recreate the client (`nativeInit` again), re-attach, and resend the last status once, with a short back-off. Show "Reconnecting to Discord…" instead of the raw error.
- Clear the SDK's Activity reference when that Activity is destroyed, so nothing holds a dead screen.
- Unit test the reconnect decision; on-device check by Berti (the emulator has no Discord): open a chapter, switch to another app for a minute, come back, and the status still updates.

## Order and size

1. Notifications (small, app only).
2. Recommend picker with covers (small, app only).
3. Reactions and replies (medium: backend, app, website).
4. Friends who read this (medium: app, small backend field).
5. Ratings and reviews (largest: backend, rules, app, website, policy).

Features 1 and 2 can ship in one build quickly; 3 next; 4 and 5 together, since reviews reuse the title matching and the title-page row.

## Decisions (Berti, 2026-10-06)

1. Notifications: other chats still notify while a chat is open (default kept).
2. Reactions: the six quick emojis ❤️ 😂 😮 😢 😡 👍 plus a "+" that opens any emoji. The server accepts any single emoji (validated as one grapheme, at most 16 bytes).
3. Ratings: 5 stars.
4. Reviews: friends only; the "Reviews" switch starts on.
5. "Read": anyone who opened at least one chapter, shown with their progress (default kept).

Status (2026-10-06): all six items built by agents and merged into main; waiting for the device check and Berti's OK to deploy.
