import { createHash } from "node:crypto";

export type Data = Record<string, unknown>;
export interface Transaction {
  get(path: string): Promise<Data | null>;
  /**
   * Documents of one collection whose (dotted) [field] equals [value], read inside the transaction before any write
   * is applied. Only small, owner-scoped collections may be queried (e.g. one person's reviews).
   */
  query(collection: string, field: string, value: string): Promise<Array<{ path: string; data: Data }>>;
  set(path: string, data: Data): void;
  delete(path: string): void;
}
export interface Store {
  transact<T>(body: (tx: Transaction) => Promise<T>): Promise<T>;
}
/** Read-only view of uploaded profile media (Cloud Storage in production, a fake in unit tests). */
export interface MediaStore {
  inspect(path: string): Promise<{ size: number; contentType: string } | null>;
}
/** A push for one user's devices; data values are short strings (FCM data message). Texts are built on the device. */
export type NoticeKind = "friend_request" | "friend_accepted" | "room_invite" | "message" | "join_request" | "group_message" | "group_added" | "read_match" | "nudge";
export interface Notice { to: string; kind: NoticeKind; data: Record<string, string> }
/** Sends pushes after a mutation committed (FCM in production, a recorder in tests). Must not throw. */
export interface Notifier {
  send(notices: Notice[]): Promise<void>;
}
/** Device push tokens per user, newest first, at most this many. */
export const MAX_DEVICES = 10;
const PUSH_TOKEN = /^[A-Za-z0-9:_\-]+$/;
export type ErrorCode = "unauthenticated" | "invalid-argument" | "not-found" | "permission-denied" |
  "already-exists" | "failed-precondition" | "resource-exhausted";
export class DomainError extends Error {
  constructor(public readonly code: ErrorCode, message: string) { super(message); }
}
export const CALLABLES = ["socialGetSelf", "socialUpdateProfile", "socialLookupHandle", "socialFriendAction",
  "socialGetFriend", "socialUpdateSharing", "socialPublishEntries", "socialPublishActivity", "socialRoomAction",
  "socialPublishFeed", "socialRoomMessage", "socialUnhideTitle", "socialRecordChapter", "socialUpdateProfileStyle",
  "socialRegisterDevice", "socialMessage", "socialJoinRequest", "socialGroupChat", "socialReadWish", "socialReview",
  "socialNudge"] as const;
export type CallableName = typeof CALLABLES[number];
/** Callables implemented outside SocialService (they need queries / Admin APIs). Clients allow-list both lists. */
export const ACCOUNT_CALLABLES = ["socialDeleteAccount"] as const;

const VISIBILITY_FIELDS = ["library", "favorites", "activity", "presence", "lastSeen", "feed", "stats"] as const;
const FEED_KINDS = ["chapter_read", "favorite_added"];
/** Ring-buffer size: each user keeps at most this many feed items, overwritten oldest-first. */
export const FEED_SLOTS = 50;
export const FEED_TTL = 7 * 86_400_000;
const FEED_DEDUPE = 10 * 60_000;
/** Room chat: ring of the latest messages per room, cleared on close. */
export const ROOM_MESSAGE_SLOTS = 100;
export const ROOM_REACTIONS = ["👍", "😂", "😮", "😢", "😡", "❤️", "🔥", "👏"];
const ROOM_MESSAGE_GAP = 1_000;
/** Ready check in rooms: "ready" (done, ready for the next chapter) and "wait" (wait for me). */
export const ROOM_SIGNAL_FALLBACK: Record<string, string> = { ready: "✅ Ready for the next chapter", wait: "⏸ Wait for me" };
/** Group rooms: host plus up to five friends (Berti's decision, 2026-10-03). */
export const ROOM_MAX_MEMBERS = 6;
const ROOM_LEASE = 90_000;
/**
 * Room positions (2026-10-06): a joined member shares the chapter and page they're on with the room, at most once per
 * [ROOM_POSITION_GAP] and [ROOM_POSITIONS_PER_DAY] times a day (counted apart from other room actions).
 */
export const ROOM_POSITION_GAP = 3_000;
export const ROOM_POSITIONS_PER_DAY = 6_000;
/** Private messages between friends: kept 30 days after they're sent (TTL), the chat list entry likewise. */
export const CHAT_TTL = 30 * 86_400_000;
/** "Let's read this": one-sided wishes stay hidden, kept 60 days; at most this many new wishes per UTC day. */
export const READ_WISH_TTL = 60 * 86_400_000;
export const READ_WISHES_PER_DAY = 50;
/** Nudges: one per pair per 24 h, at most this many people per UTC day. */
export const NUDGE_PAIR_GAP = 86_400_000;
export const NUDGES_PER_DAY = 20;
/** Room recap: a two-person session must last this long to leave a line in their chat. */
export const RECAP_MIN_DURATION = 5 * 60_000;
const CHAT_MESSAGE_MAX = 500;
const CHAT_PREVIEW = 100;
/** "Ask to read along": pending for 10 minutes, at most 10 asks an hour per person. */
export const JOIN_REQUEST_TTL = 10 * 60_000;
const JOIN_REQUESTS_PER_HOUR = 10;
/** Daily ranking: UTC days, kept 8 days so a weekly view stays possible. */
const STATS_GAP = 10_000;
/** Profile customisation: friends-only media under profileMedia/{uid}/{avatar|banner}; GIFs allowed. */
export const PROFILE_MEDIA = { avatar: 2 * 1024 * 1024, banner: 5 * 1024 * 1024 } as const;
export const PROFILE_MEDIA_TYPES = ["image/png", "image/jpeg", "image/webp", "image/gif"];
export const PROFILE_ACCENTS = 8;
const STYLE_FIELDS = ["bio", "accent", "avatar", "banner"] as const;
/** What anyone may learn from an exact handle: never bio or media. */
function publicProfile(profile: Data): Data {
  return { schemaVersion: profile.schemaVersion, uid: profile.uid, handle: profile.handle, displayName: profile.displayName };
}
const STATS_TTL = 8 * 86_400_000;
/**
 * XP and levels: earned by reading, kept for good in `xp/{uid}`. Counted whenever a chapter is recorded outside
 * incognito and invisible mode (also without sharing); friends see it only with stats sharing, everyone sees the
 * level on the global ranking.
 */
export const XP = { chapter: 10, together: 5, streakDay: 5, streakMaxDays: 7, dailyCap: 1_000 } as const;
/**
 * Double XP (Berti, 2026-10-07): every Friday and Saturday in Israel time (Asia/Jerusalem, so it follows daylight
 * saving) and during events ([start, end) in epoch ms; the 1.0 launch week first, midnight to midnight Israel time).
 * Reading XP and the daily cap double; challenge and recruit XP don't.
 */
export const DOUBLE_XP = {
  timeZone: "Asia/Jerusalem",
  weekdays: ["Fri", "Sat"] as readonly string[],
  // 7 Oct 2026 00:00 to 14 Oct 2026 00:00 Israel time (IDT, UTC+3).
  events: [{ id: "launch_1_0", start: Date.UTC(2026, 9, 6, 21), end: Date.UTC(2026, 9, 13, 21) }] as ReadonlyArray<{ id: string; start: number; end: number }>,
};
const doubleXpWeekday = new Intl.DateTimeFormat("en-US", { timeZone: DOUBLE_XP.timeZone, weekday: "short" });
export function xpMultiplier(now: number): number {
  const weekend = DOUBLE_XP.weekdays.includes(doubleXpWeekday.format(now));
  return weekend || DOUBLE_XP.events.some((event) => now >= event.start && now < event.end) ? 2 : 1;
}
/** Total XP needed to reach [level] (level 1 at 0): 50, 150, 300, 500, ... */
export function xpForLevel(level: number): number { return 25 * level * (level - 1); }
export function levelFor(total: number): number {
  let level = 1;
  while (xpForLevel(level + 1) <= total) level++;
  return level;
}
/**
 * Achievements, unlocked inside `awardXp` from the counters in `xp/{uid}` (no extra reads). Stored as
 * `badges: {id: unlockedAt}`; the app and web hold the names and icons.
 */
export const BADGES: ReadonlyArray<{ id: string; field: string; min: number }> = [
  { id: "first_chapter", field: "chapters", min: 1 }, { id: "chapters_50", field: "chapters", min: 50 },
  { id: "chapters_250", field: "chapters", min: 250 }, { id: "chapters_1000", field: "chapters", min: 1000 },
  { id: "together_1", field: "together", min: 1 }, { id: "together_25", field: "together", min: 25 },
  { id: "together_100", field: "together", min: 100 },
  { id: "streak_3", field: "bestStreak", min: 3 }, { id: "streak_7", field: "bestStreak", min: 7 },
  { id: "streak_30", field: "bestStreak", min: 30 }, { id: "streak_100", field: "bestStreak", min: 100 },
  { id: "binge_20", field: "bestDay", min: 20 }, { id: "days_30", field: "days", min: 30 },
  { id: "level_10", field: "level", min: 10 }, { id: "level_25", field: "level", min: 25 },
  { id: "recruit_1", field: "recruits", min: 1 }, { id: "recruit_3", field: "recruits", min: 3 },
  { id: "recruit_10", field: "recruits", min: 10 },
];
/**
 * Recruiting: a friend request sent with `via: "invite"` (the newcomer opened the inviter's link or friend code) and
 * accepted by the inviter files a server-only `invites/{newcomer}` doc (one inviter per account, ever). The inviter is
 * credited once the newcomer has recorded [chapters] chapters since then: [xp] XP (outside the daily cap, not counted
 * in the weekly XP) and the `recruits` counter behind the `recruit_*` badges, at most [monthlyCap] credits a UTC month.
 */
export const RECRUIT = { chapters: 5, xp: 50, monthlyCap: 10 } as const;
/** `xp/{uid}` fields `awardXp` carries over unchanged (recruiting bookkeeping written elsewhere). */
const XP_CARRIED = ["recruits", "recruitMonth", "recruitMonthCount", "recruitedAt", "invitePending"] as const;
/** Streak freezes: one earned every 7 streak days, at most this many held; each covers one missed day. */
export const MAX_FREEZES = 2;
/**
 * Monthly challenges (UTC calendar month): the same three for everyone, picked from [CHALLENGE_ROTATION] by the
 * month. Progress lives in `xp/{uid}.month` ({id: "yyyy-mm", chapters, days, together, streak, bestDay, xp}), reset
 * when the month changes: `streak` is the longest streak reached this month, `bestDay` the most chapters in one UTC
 * day this month, `xp` the reading XP earned this month (challenge XP excluded). A completed challenge gives
 * [CHALLENGE_XP] once (outside the daily cap and the weekly XP) and is stamped in `challenges: {"yyyy-mm": [ids]}`;
 * all three in a month add the `season_yyyy_mm` badge.
 */
export const CHALLENGE_XP = 100;
export type MonthField = "chapters" | "days" | "together" | "streak" | "bestDay" | "xp";
export const CHALLENGES: ReadonlyArray<{ id: string; field: MonthField; min: number }> = [
  { id: "chapters_100", field: "chapters", min: 100 }, { id: "chapters_200", field: "chapters", min: 200 },
  { id: "days_15", field: "days", min: 15 }, { id: "days_20", field: "days", min: 20 },
  { id: "together_5", field: "together", min: 5 }, { id: "together_10", field: "together", min: 10 },
  { id: "streak_7", field: "streak", min: 7 }, { id: "streak_14", field: "streak", min: 14 },
  { id: "binge_15", field: "bestDay", min: 15 }, { id: "xp_2000", field: "xp", min: 2000 },
];
/** January..December: each month's three challenge IDs. */
export const CHALLENGE_ROTATION: ReadonlyArray<readonly [string, string, string]> = [
  ["chapters_100", "days_15", "streak_7"], ["chapters_200", "together_5", "binge_15"],
  ["days_20", "together_10", "xp_2000"], ["chapters_100", "streak_14", "together_5"],
  ["days_15", "binge_15", "xp_2000"], ["chapters_200", "days_20", "together_10"],
  ["chapters_100", "together_5", "streak_14"], ["days_20", "streak_7", "xp_2000"],
  ["chapters_200", "binge_15", "together_10"], ["chapters_100", "days_20", "together_10"],
  ["days_15", "streak_14", "xp_2000"], ["chapters_200", "together_5", "streak_7"],
];
/** The UTC calendar month of [now], "yyyy-mm". */
export function monthOf(now: number): string { return new Date(now).toISOString().slice(0, 7); }
/** The three challenges of [month] ("yyyy-mm"). */
export function challengesFor(month: string): Array<{ id: string; field: MonthField; min: number }> {
  return CHALLENGE_ROTATION[Number(month.slice(5, 7)) - 1]!.map((id) => CHALLENGES.find((c) => c.id === id)!);
}
/** The badge for finishing all of [month]'s challenges: "season_yyyy_mm". */
export function seasonBadge(month: string): string { return `season_${month.replace("-", "_")}`; }
/** The UTC Monday that starts [now]'s week, yyyymmdd (weekly XP resets then). */
export function weekOf(now: number): string {
  const daysSinceMonday = (new Date(now).getUTCDay() + 6) % 7;
  return statsDay(now - daysSinceMonday * DAY);
}
function dayNumber(day: string): number {
  return Math.floor(Date.UTC(Number(day.slice(0, 4)), Number(day.slice(4, 6)) - 1, Number(day.slice(6, 8))) / DAY);
}
/**
 * A message's optional recommended title (`content`, validated like shared library entries) and its text: with a
 * title the text is optional. Returns the stored text ("" when none) and the content or null.
 */
function messageBody(input: Data, hasContent: boolean): { body: string; content: Data | null } {
  const content = hasContent && input.content !== null ? validateContent(input.content) : null;
  if (content && input.text === null) return { body: "", content };
  const body = text(input.text, 1, CHAT_MESSAGE_MAX, PLAIN_TEXT).trim();
  check(body.length > 0 || content, "invalid-argument", "Empty message.");
  return { body, content };
}
/** The list preview for a message: the recommended title first. */
function messagePreview(body: string, content: Data | null): string {
  return (content ? `📖 ${String(content.title)}${body ? ` · ${body}` : ""}` : body).slice(0, CHAT_PREVIEW);
}
/**
 * Replies and reactions in private and group chats (2026-10-06). Reactions have their own daily quota and a
 * per-person burst limit across all chats, so they never eat into the message limits.
 */
export const REACTIONS_PER_DAY = 2_000;
export const REACTIONS_PER_MINUTE = 60;
const REACTION_QUOTA = { key: "socialReaction", limit: REACTIONS_PER_DAY } as const;
const EMOJI_MAX_BYTES = 16;
// One emoji: a pictograph (optional variation selector and skin tone), ZWJ sequences of those, a keycap or a flag.
const PICTOGRAPH = String.raw`\p{Extended_Pictographic}️?\p{Emoji_Modifier}?️?`;
const EMOJI = new RegExp(String.raw`^(?:${PICTOGRAPH}(?:‍${PICTOGRAPH})*|[#*0-9]️?⃣|\p{Regional_Indicator}{2})$`, "u");
/** Whether [value] is a single emoji of at most 16 UTF-8 bytes (Berti's decision: any emoji may be a reaction). */
export function isReactionEmoji(value: unknown): value is string {
  return typeof value === "string" && Buffer.byteLength(value, "utf8") <= EMOJI_MAX_BYTES && EMOJI.test(value);
}
/** Which of [fields] the raw input carries (optional fields newer apps send; older ones leave them out). */
function optionalFields(raw: unknown, fields: readonly string[]): string[] {
  return raw !== null && typeof raw === "object" ? fields.filter((field) => Object.hasOwn(raw, field)) : [];
}
/** The optional `replyTo` (a message seq), only on `send`; absent or null means no reply. */
function replyTarget(input: Data, action: string): number | null {
  const value = input.replyTo ?? null;
  check(value === null || action === "send", "invalid-argument", "replyTo not allowed.");
  return value === null ? null : integer(value, 0, Number.MAX_SAFE_INTEGER);
}
/** `seq` and `emoji` of a `react`; every other action must leave them out (or null). */
function reactionInput(input: Data, action: string): { seq: number; emoji: string } | null {
  if (action !== "react") {
    check((input.seq ?? null) === null && (input.emoji ?? null) === null, "invalid-argument", "Only reactions carry seq and emoji.");
    return null;
  }
  check(input.text === null && (input.content ?? null) === null && (input.replyTo ?? null) === null,
    "invalid-argument", "Reactions carry no text.");
  check(isReactionEmoji(input.emoji), "invalid-argument", "Invalid emoji.");
  return { seq: integer(input.seq, 0, Number.MAX_SAFE_INTEGER), emoji: input.emoji };
}
/** A chat message's document ID: its sequence number, zero-padded so IDs sort like the numbers. */
function messageId(seq: number): string { return String(seq).padStart(10, "0"); }
/** The quote a reply stores: who wrote the original and its first 100 characters ("📖 title" for a recommendation). */
function quote(original: Data): Data {
  const content = original.content as Data | undefined;
  const preview = content ? `📖 ${String(content.title)}` : String(original.text ?? "");
  return { seq: original.seq, senderUid: original.senderUid, preview: Array.from(preview).slice(0, CHAT_PREVIEW).join("") };
}
/** Group chats: up to 20 people, added by a friend; messages kept 30 days like private chats. */
export const GROUP_MAX_MEMBERS = 20;
const GROUPS_CREATED_PER_DAY = 10;
/** Global ranking: one document per UTC day holding the top readers who haven't opted out. */
export const LEADERBOARD_SIZE = 50;
/** The all-time board (`leaderboards/alltime`): top [LEADERBOARD_SIZE] by total XP, kept for good. */
export const ALL_TIME_BOARD = "alltime";
/** Absent means on: the global ranking is the one sharing switch that starts enabled (Berti's decision, 2026-10-03). */
export function rankedGlobally(settings: Data): boolean { return settings.globalRanking !== false && !settings.invisible; }
/**
 * Avatar and banner visible to every signed-in user while on the global ranking (Berti's decision, 2026-10-06);
 * absent means on. storage.rules applies the same test to profileMedia reads.
 */
export function rankingMedia(settings: Data): boolean { return rankedGlobally(settings) && settings.rankingMedia !== false; }
/** The picture fields a ranking entry carries: the profile's avatar/banner versions, or none when not public. */
function boardMedia(profile: Data, settings: Data): Data {
  const pick = (media: unknown) => {
    const m = media as Data | null | undefined;
    return rankingMedia(settings) && m && typeof m.version === "number" ? { version: m.version, contentType: m.contentType } : null;
  };
  return { avatar: pick(profile.avatar), banner: pick(profile.banner) };
}
export function statsDay(now: number): string { return new Date(now).toISOString().slice(0, 10).replaceAll("-", ""); }
const ROOM_MESSAGES_PER_MINUTE = 30;
// Plain single-line text: no control characters (including newlines).
const PLAIN_TEXT = /^[^\u0000-\u001f\u007f]+$/u;
/**
 * Aggregate abuse/cost guard: per-actor, per-UTC-day caps on top of each callable's own limits. Generous for real
 * use (e.g. a 30 s room heartbeat for 24 h is 2,880 room actions) but bounded for scripted abuse.
 */
export const DAILY_LIMITS: Partial<Record<CallableName, number>> = {
  socialUpdateProfile: 50, socialFriendAction: 500, socialUpdateSharing: 200, socialPublishEntries: 2_000,
  socialPublishActivity: 2_000, socialRoomAction: 3_500, socialPublishFeed: 300, socialRoomMessage: 2_000,
  socialUnhideTitle: 200, socialRecordChapter: 300, socialUpdateProfileStyle: 100, socialRegisterDevice: 50,
  socialMessage: 2_000, socialJoinRequest: 200, socialGroupChat: 2_000, socialReadWish: 100, socialReview: 60, socialNudge: 60,
};
export const DAILY_TOTAL = 8_000;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const KEY = /^[0-9a-f]{64}$/;
const CONTENT_FIELDS = ["contentKey", "title", "sourceKey", "sourceWorkKey", "editionKey", "canonicalProvider", "canonicalId"];
const POSITION_FIELDS = ["chapterKey", "chapterLabel", "pageIndex", "pageCount", "pageMappingKey"];
const DAY = 86_400_000;
function fail(code: ErrorCode, message: string): never { throw new DomainError(code, message); }
function check(value: unknown, code: ErrorCode, message: string): asserts value { if (!value) fail(code, message); }
function object(value: unknown, fields: readonly string[]): Data {
  check(value !== null && typeof value === "object" && !Array.isArray(value), "invalid-argument", "Expected an object.");
  const data = value as Data;
  check(Object.keys(data).length === fields.length && fields.every((field) => Object.hasOwn(data, field)),
    "invalid-argument", "Unexpected or missing fields.");
  return data;
}
function text(value: unknown, min: number, max: number, pattern?: RegExp): string {
  check(typeof value === "string" && value.length >= min && value.length <= max && (!pattern || pattern.test(value)),
    "invalid-argument", "Invalid string.");
  return value;
}
function integer(value: unknown, min: number, max: number): number {
  check(typeof value === "number" && Number.isSafeInteger(value) && value >= min && value <= max,
    "invalid-argument", "Invalid integer.");
  return value;
}
function bool(value: unknown): boolean { check(typeof value === "boolean", "invalid-argument", "Invalid boolean."); return value; }
function uid(value: unknown): string { return text(value, 1, 128, /^[^/]+$/); }
function key(value: unknown): string { return text(value, 64, 64, KEY); }
function uuid(value: unknown): string { return text(value, 36, 36, UUID); }
function generation(value: unknown): number { return integer(value, 1, 2_147_483_647); }
function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") return Object.fromEntries(Object.entries(value).sort(([a], [b]) => a.localeCompare(b)).map(([k, v]) => [k, canonical(v)]));
  return value;
}
export function digest(value: unknown): string { return createHash("sha256").update(JSON.stringify(canonical(value))).digest("hex"); }
export function pairId(a: string, b: string): string { return digest([a, b].sort()); }
/** Optional cover: a public https image URL only (never a local file or a custom cover). */
const COVER_URL = /^https:\/\/[^\s\u0000-\u001f\u007f]+$/u;
/**
 * Optional `source` (rooms): which extension source the host reads from, so a friend without it can be told what to
 * install. `id` is the source's numeric ID as a string; `pkg` the extension package, or null for built-in sources.
 * `url` (optional): the host's manga URL as the extension knows it (SManga.url), so a guest can open the same entry.
 */
const SOURCE_URL = /^(?:\/[^\s\u0000-\u001f\u007f]*|https:\/\/[^\s\u0000-\u001f\u007f]+)$/u;
function validateSource(value: unknown): Data {
  const url = value !== null && typeof value === "object" && Object.hasOwn(value, "url");
  const data = object(value, ["id", "name", "lang", "pkg", ...(url ? ["url"] : [])]);
  text(data.id, 1, 20, /^-?[0-9]+$/); text(data.name, 1, 80, PLAIN_TEXT); text(data.lang, 1, 12, /^[A-Za-z0-9-]+$/);
  if (data.pkg !== null) text(data.pkg, 3, 150, /^[A-Za-z0-9_.]+$/);
  if (url) text(data.url, 1, 500, SOURCE_URL);
  return { ...data };
}
export function validateContent(value: unknown): Data {
  const has = (field: string) => value !== null && typeof value === "object" && Object.hasOwn(value, field);
  const cover = has("coverUrl"), source = has("source"), thumb = has("thumb");
  const data = object(value, [...CONTENT_FIELDS, ...(cover ? ["coverUrl"] : []), ...(source ? ["source"] : []), ...(thumb ? ["thumb"] : [])]);
  if (cover) text(data.coverUrl, 9, 500, COVER_URL);
  // thumb (optional, true only): the owner uploaded a small cover to coverThumbs/{uid}/{contentKey}.
  if (thumb) check(data.thumb === true, "invalid-argument", "Invalid thumb flag.");
  if (source) data.source = validateSource(data.source);
  key(data.contentKey); text(data.title, 1, 200); text(data.sourceKey, 1, 160);
  key(data.sourceWorkKey); key(data.editionKey);
  check(data.canonicalProvider === null || (typeof data.canonicalProvider === "string" && ["anilist", "mal", "kitsu", "mangaupdates"].includes(data.canonicalProvider)),
    "invalid-argument", "Invalid canonical provider.");
  if (data.canonicalId !== null) text(data.canonicalId, 1, 64);
  check((data.canonicalId === null) === (data.canonicalProvider === null), "invalid-argument", "Canonical fields must be set together.");
  return { ...data };
}
/**
 * The work a title belongs to, independent of the source it's read on: its tracker entry when the owner tracks it
 * (AniList, MyAnimeList, Kitsu, MangaUpdates), otherwise the exact source work. One review per work and owner lives at
 * `reviews/{uid}/items/{workKey}`; friends line titles up with the app's wider TitleMatch rules, not with this key.
 * Android derives the same key (TitleMatch.workKey).
 */
export function workKey(content: Data): string {
  const basis = content.canonicalProvider !== null && content.canonicalId !== null
    ? `canonical|${String(content.canonicalProvider)}|${String(content.canonicalId)}` : `source|${String(content.sourceWorkKey)}`;
  return createHash("sha256").update(`work-v1|${basis}`).digest("hex");
}
/** Reading progress on a shared library entry: chapters read, chapters known, and whether all of them are read. */
export const PROGRESS_MAX = 100_000;
function validateProgress(value: unknown): Data {
  const data = object(value, ["read", "total", "finished"]);
  const read = integer(data.read, 0, PROGRESS_MAX), total = integer(data.total, 0, PROGRESS_MAX), finished = bool(data.finished);
  check(read <= total && (!finished || (total > 0 && read === total)), "invalid-argument", "Invalid progress.");
  return { read, total, finished };
}
/** Ratings and reviews: 1–5 stars, optional plain text (line breaks allowed), at most 30 new or edited a UTC day. */
export const REVIEW_TEXT_MAX = 1_000;
export const REVIEWS_PER_DAY = 30;
const REVIEW_TEXT = /^[^\u0000-\u0009\u000b-\u001f\u007f]+$/u;
const REVIEW_LINES = 30;
function reviewText(value: unknown): string | null {
  if (value === null) return null;
  const body = text(value, 1, REVIEW_TEXT_MAX, REVIEW_TEXT).trim();
  check(body.split("\n").length <= REVIEW_LINES, "invalid-argument", "Too many lines.");
  return body.length ? body : null;
}
/** Absent means on: friends see my reviews unless I turn the switch off (Berti's decision, 2026-10-06). */
export function reviewsShared(settings: Data): boolean { return settings.reviews !== false && !settings.invisible; }
/**
 * Absent means on: friends see the reading progress on my shared library entries unless I turn it off (review fix,
 * 2026-10-06). While off, publishEntries drops any progress an (older) app still sends.
 */
export function progressShared(settings: Data): boolean { return settings.progress !== false; }
export function validatePosition(value: unknown): Data {
  // stripPosition (optional): long-strip scroll, in pages from the chapter start to the top of the screen.
  const strip = value !== null && typeof value === "object" && Object.hasOwn(value, "stripPosition");
  const data = object(value, strip ? [...POSITION_FIELDS, "stripPosition"] : POSITION_FIELDS);
  key(data.chapterKey); text(data.chapterLabel, 1, 100); key(data.pageMappingKey);
  const count = integer(data.pageCount, 1, 2000); integer(data.pageIndex, 0, count - 1);
  if (strip) check(typeof data.stripPosition === "number" && Number.isFinite(data.stripPosition) &&
    data.stripPosition >= 0 && data.stripPosition < count, "invalid-argument", "Invalid strip position.");
  return { ...data };
}
/** A room member's own place (`position` action): exactly the chapter key and the page index, nothing else. */
export function validateMemberPage(value: unknown): Data {
  const data = object(value, ["chapterKey", "pageIndex"]);
  return { chapterKey: key(data.chapterKey), pageIndex: integer(data.pageIndex, 0, 1999) };
}
export function privateSettings(now: number): Data {
  // New accounts share everything with friends; turning things off is the user's choice (Berti's decision,
  // 2026-10-06). Existing accounts keep whatever they had: this only runs when no settings document exists yet.
  return { schemaVersion: 1, generation: 1, library: "friends", favorites: "friends", activity: "friends",
    presence: "friends", lastSeen: "friends", feed: "friends", stats: "friends", invisible: false, globalRanking: true, reviews: true, progress: true, updatedAt: now };
}

export class SocialService {
  constructor(private readonly store: Store, private readonly clock: () => number = Date.now, private readonly media: MediaStore | null = null,
    private readonly notifier: Notifier | null = null) {}

  async call(name: CallableName, actor: string | null, raw: unknown): Promise<Data> {
    if (!actor) fail("unauthenticated", "Sign in first.");
    uid(actor);
    switch (name) {
      case "socialGetSelf": return this.getSelf(actor, raw);
      case "socialUpdateProfile": return this.updateProfile(actor, raw);
      case "socialLookupHandle": return this.lookup(actor, raw);
      case "socialFriendAction": return this.friendAction(actor, raw);
      case "socialGetFriend": return this.getFriend(actor, raw);
      case "socialUpdateSharing": return this.updateSharing(actor, raw);
      case "socialPublishEntries": return this.publishEntries(actor, raw);
      case "socialPublishActivity": return this.publishActivity(actor, raw);
      case "socialRoomAction": return this.roomAction(actor, raw);
      case "socialPublishFeed": return this.publishFeed(actor, raw);
      case "socialRoomMessage": return this.roomMessage(actor, raw);
      case "socialUnhideTitle": return this.unhideTitle(actor, raw);
      case "socialRecordChapter": return this.recordChapter(actor, raw);
      case "socialUpdateProfileStyle": return this.updateProfileStyle(actor, raw);
      case "socialRegisterDevice": return this.registerDevice(actor, raw);
      case "socialMessage": return this.message(actor, raw);
      case "socialJoinRequest": return this.joinRequest(actor, raw);
      case "socialGroupChat": return this.groupChat(actor, raw);
      case "socialReadWish": return this.readWish(actor, raw);
      case "socialReview": return this.review(actor, raw);
      case "socialNudge": return this.nudge(actor, raw);
    }
  }

  /**
   * Runs [body] once per operation ID (replays return the stored result). Pushes queued with `notify` are sent only
   * after the transaction committed, and never for a replay.
   */
  private async mutation(actor: string, name: CallableName, input: Data,
    body: (tx: Transaction, now: number, notify: (notice: Notice) => void) => Promise<Data>,
    quota: { key: string; limit: number } = { key: name, limit: DAILY_LIMITS[name] ?? DAILY_TOTAL }): Promise<Data> {
    const operation = uuid(input.operationId);
    const hash = digest({ name, input });
    let outbox: Notice[] = [];
    const result = await this.store.transact(async (tx) => {
      outbox = []; // a retried transaction starts over
      const path = `operations/${actor}/items/${operation}`;
      const previous = await tx.get(path);
      if (previous) {
        check(previous.payloadHash === hash, "failed-precondition", "Operation ID was already used for different input.");
        if (name === "socialRoomAction") {
          const result = previous.result as Data, room = result.room as Data;
          if (room.hostUid !== actor) await this.friends(tx, String(room.hostUid), actor);
          const current = await tx.get(`rooms/${result.roomId as string}`);
          check(current && (room.state === "closed" || (current.state !== "closed" && Number(current.leaseExpiresAt) > this.clock())),
            "failed-precondition", "Room lease expired or closed.");
        }
        return previous.result as Data;
      }
      const now = this.clock();
      await this.dailyQuota(tx, actor, quota.key, quota.limit, now);
      const result = await body(tx, now, (notice) => outbox.push(notice));
      tx.set(path, { schemaVersion: 1, payloadHash: hash, result, createdAt: now, expiresAt: now + 30 * DAY });
      return result;
    });
    if (outbox.length && this.notifier) await this.notifier.send(outbox);
    return result;
  }

  /** Counts one call under [name] (a callable, or a separate key such as reactions) against [limit] and the total. */
  private async dailyQuota(tx: Transaction, actor: string, name: string, limit: number, now: number): Promise<void> {
    const path = `quotas/${actor}`, day = Math.floor(now / DAY), current = await tx.get(path);
    const counts = current?.day === day ? { ...(current.counts as Record<string, number>) } : {};
    const total = current?.day === day ? Number(current.total) : 0;
    counts[name] = (counts[name] ?? 0) + 1;
    check(counts[name] <= limit && total + 1 <= DAILY_TOTAL, "resource-exhausted", "Daily limit reached. Try again tomorrow.");
    tx.set(path, { schemaVersion: 1, day, counts, total: total + 1, updatedAt: now });
  }
  /**
   * Server-side cleanup for a room whose lease ran out without a close (e.g. both apps were killed). Closes it like
   * `leave` would, clears chat and the invite index. No-op for live or already closed rooms.
   */
  async expireRoom(roomId: string): Promise<Data> {
    key(roomId);
    return this.store.transact(async (tx) => {
      const now = this.clock(), path = `rooms/${roomId}`, room = await tx.get(path);
      if (!room || room.state === "closed" || Number(room.leaseExpiresAt) > now) return { expired: false };
      await this.closeRoom(tx, roomId, room, now);
      return { expired: true };
    });
  }  private async settings(tx: Transaction, actor: string, now: number): Promise<Data> {
    const path = `sharingSettings/${actor}`;
    const current = await tx.get(path);
    if (current) return current;
    const initial = privateSettings(now);
    tx.set(path, initial);
    tx.set(`accounts/${actor}`, { schemaVersion: 1, uid: actor, createdAt: now });
    return initial;
  }
  private async unblocked(tx: Transaction, a: string, b: string): Promise<void> {
    const [forward, reverse] = await Promise.all([tx.get(`blocks/${a}/users/${b}`), tx.get(`blocks/${b}/users/${a}`)]);
    check(!forward && !reverse, "permission-denied", "Relationship unavailable.");
  }
  private async friends(tx: Transaction, a: string, b: string): Promise<void> {
    check(a !== b, "invalid-argument", "Choose another account.");
    await this.unblocked(tx, a, b);
    const [forward, reverse] = await Promise.all([tx.get(`friendships/${a}/accepted/${b}`), tx.get(`friendships/${b}/accepted/${a}`)]);
    check(forward?.friendUid === b && reverse?.friendUid === a, "permission-denied", "Accepted friendship required.");
  }
  private async getSelf(actor: string, raw: unknown): Promise<Data> {
    object(raw, []);
    return this.store.transact(async (tx) => ({ profile: await tx.get(`profiles/${actor}`), settings: await this.settings(tx, actor, this.clock()) }));
  }
  private async updateProfile(actor: string, raw: unknown): Promise<Data> {
    const input = object(raw, ["operationId", "handle", "displayName"]);
    const handle = text(input.handle, 3, 24, /^[a-z0-9_]+$/);
    const displayName = text(input.displayName, 1, 40);
    check(displayName.trim().length > 0, "invalid-argument", "Display name cannot be blank.");
    return this.mutation(actor, "socialUpdateProfile", input, async (tx, now) => {
      const [existing, claimed] = await Promise.all([tx.get(`profiles/${actor}`), tx.get(`handles/${handle}`)]);
      check(!claimed || claimed.uid === actor, "already-exists", "Handle unavailable.");
      await this.settings(tx, actor, now);
      const style = Object.fromEntries(STYLE_FIELDS.filter((field) => existing && field in existing).map((field) => [field, existing![field]]));
      const profile = { ...style, schemaVersion: 1, uid: actor, handle, displayName, createdAt: existing?.createdAt ?? now, updatedAt: now };
      if (existing && existing.handle !== handle) tx.delete(`handles/${existing.handle as string}`);
      tx.set(`handles/${handle}`, { schemaVersion: 1, uid: actor });
      tx.set(`profiles/${actor}`, profile);
      await this.renameOnLeaderboard(tx, actor, profile, now);
      return { profile };
    });
  }
  private async lookup(actor: string, raw: unknown): Promise<Data> {
    const input = object(raw, ["handle"]);
    const handle = text(input.handle, 3, 24, /^[a-z0-9_]+$/);
    return this.store.transact(async (tx) => {
      const now = this.clock();
      const ratePath = `rateLimits/${actor}`;
      const rate = await tx.get(ratePath);
      const within = rate && now - Number(rate.windowStartedAt) < 60_000;
      const count = within ? Number(rate.count) + 1 : 1;
      check(count <= 30, "resource-exhausted", "Try discovery again later.");
      const target = await tx.get(`handles/${handle}`);
      // Persist misses too: unsuccessful discovery must not evade the limit.
      tx.set(ratePath, { schemaVersion: 1, count, windowStartedAt: within ? rate!.windowStartedAt : now });
      if (!target) return { missing: true };
      await this.unblocked(tx, actor, String(target.uid));
      const profile = await tx.get(`profiles/${target.uid as string}`);
      return profile ? { profile: publicProfile(profile) } : { missing: true };
    }).then((result) => {
      if (result.missing) fail("not-found", "Handle unavailable.");
      return result;
    });
  }
  private async friendAction(actor: string, raw: unknown): Promise<Data> {
    // via (optional, request only): "invite" when the request came from the other's invite link or friend code.
    const hasVia = raw !== null && typeof raw === "object" && Object.hasOwn(raw, "via");
    const input = object(raw, ["operationId", "action", "otherUid", ...(hasVia ? ["via"] : [])]);
    const other = uid(input.otherUid);
    check(other !== actor, "invalid-argument", "Choose another account.");
    const action = text(input.action, 1, 10);
    check(["request", "accept", "reject", "cancel", "remove", "block", "unblock"].includes(action), "invalid-argument", "Unknown friendship action.");
    check(!hasVia || input.via === null || (input.via === "invite" && action === "request"), "invalid-argument", "Unknown request origin.");
    const viaInvite = hasVia && input.via === "invite";
    return this.mutation(actor, "socialFriendAction", input, async (tx, now, notify) => {
      const id = pairId(actor, other), requestPath = `friendRequests/${id}`;
      const request = await tx.get(requestPath);
      if (["block", "remove", "unblock"].includes(action)) {
        if (action === "unblock") { tx.delete(`blocks/${actor}/users/${other}`); return { requestId: null, state: "unblocked" }; }
        if (action === "block") tx.set(`blocks/${actor}/users/${other}`, { schemaVersion: 1, uid: other, createdAt: now });
        tx.delete(`friendships/${actor}/accepted/${other}`);
        tx.delete(`friendships/${other}/accepted/${actor}`);
        // The chat disappears from both lists; its messages stay unreadable (rules need the friendship) until TTL.
        tx.delete(`chatIndex/${actor}/chats/${other}`);
        tx.delete(`chatIndex/${other}/chats/${actor}`);
        tx.delete(`joinRequests/${actor}/items/${other}`);
        tx.delete(`joinRequests/${other}/items/${actor}`);
        if (request?.state === "pending") tx.set(requestPath, { ...request, state: action === "block" ? "blocked" : "removed", updatedAt: now });
        return { requestId: request ? id : null, state: action === "block" ? "blocked" : "removed" };
      }
      await this.unblocked(tx, actor, other);
      const [ownProfile, otherProfile, ownEdge, otherEdge] = await Promise.all([
        tx.get(`profiles/${actor}`), tx.get(`profiles/${other}`),
        tx.get(`friendships/${actor}/accepted/${other}`), tx.get(`friendships/${other}/accepted/${actor}`),
      ]);
      check(ownProfile && otherProfile, "failed-precondition", "Both accounts need profiles.");
      if (action === "request") {
        if (ownEdge && otherEdge) return { requestId: id, state: "accepted" };
        if (request?.state === "pending") return { requestId: id, state: "pending" };
        // A bounded send window limits unsolicited requests independently of handle discovery.
        const ratePath = `requestLimits/${actor}`;
        const rate = await tx.get(ratePath), within = rate && now - Number(rate.windowStartedAt) < DAY;
        const count = within ? Number(rate.count) + 1 : 1;
        check(count <= 100, "resource-exhausted", "Friend request limit reached.");
        tx.set(ratePath, { schemaVersion: 1, count, windowStartedAt: within ? rate!.windowStartedAt : now });
        // A pair that was ever friends keeps that mark across later requests, and never counts as an invite.
        const everFriends = Boolean(request?.everFriends) || request?.state === "accepted";
        tx.set(requestPath, { schemaVersion: 1, senderUid: actor, recipientUid: other,
          senderHandle: ownProfile.handle, senderDisplayName: ownProfile.displayName,
          recipientHandle: otherProfile.handle, recipientDisplayName: otherProfile.displayName,
          state: "pending", createdAt: now, updatedAt: now,
          ...(everFriends ? { everFriends: true } : viaInvite ? { via: "invite" } : {}) });
        notify({ to: other, kind: "friend_request", data: { name: String(ownProfile.displayName), handle: String(ownProfile.handle), uid: actor } });
        return { requestId: id, state: "pending" };
      }
      check(request?.state === "pending", "failed-precondition", "Pending request required.");
      check(action === "cancel" ? request.senderUid === actor : request.recipientUid === actor,
        "permission-denied", "Wrong request participant.");
      const state = action === "accept" ? "accepted" : action === "reject" ? "rejected" : "cancelled";
      if (action === "accept") {
        if (request.via === "invite" && !request.everFriends) await this.fileInvite(tx, actor, other, now);
        tx.set(`friendships/${actor}/accepted/${other}`, { schemaVersion: 1, friendUid: other, createdAt: now });
        tx.set(`friendships/${other}/accepted/${actor}`, { schemaVersion: 1, friendUid: actor, createdAt: now });
        notify({ to: other, kind: "friend_accepted", data: { name: String(ownProfile.displayName), handle: String(ownProfile.handle), uid: actor } });
      }
      tx.set(requestPath, { ...request, state, updatedAt: now, ...(action === "accept" ? { everFriends: true } : {}) });
      return { requestId: id, state };
    });
  }
  /**
   * [inviter] accepted an invite request from [newcomer]: files `invites/{newcomer}` unless the newcomer was already
   * invited by anyone (one inviter per account, ever). The chapter count at this point is the baseline for the credit.
   */
  private async fileInvite(tx: Transaction, inviter: string, newcomer: string, now: number): Promise<void> {
    const invitePath = `invites/${newcomer}`, xpPath = `xp/${newcomer}`;
    const [existing, xp] = await Promise.all([tx.get(invitePath), tx.get(xpPath)]);
    if (existing) return;
    tx.set(invitePath, { schemaVersion: 1, inviterUid: inviter, baseChapters: Number(xp?.chapters ?? 0), state: "pending",
      createdAt: now, updatedAt: now });
    // Lets recordChapter skip the invite read for everyone else (a newcomer without an xp doc is checked anyway).
    if (xp) tx.set(xpPath, { ...xp, invitePending: true });
  }
  /**
   * Called from recordChapter while the reader may have a pending invite: once they recorded [RECRUIT.chapters]
   * chapters since the invite was accepted, credits the inviter (if they are still friends and under the monthly cap).
   * Returns whether the invite is settled (credited, dropped or absent), so the reader's `invitePending` flag can go.
   */
  private async creditInviter(tx: Transaction, newcomer: string, chapters: number, now: number): Promise<boolean> {
    const invitePath = `invites/${newcomer}`, invite = await tx.get(invitePath);
    if (!invite || invite.state !== "pending") return true;
    if (chapters - Number(invite.baseChapters) < RECRUIT.chapters) return false;
    const inviter = String(invite.inviterUid), inviterXpPath = `xp/${inviter}`;
    const [edge, previous] = await Promise.all([tx.get(`friendships/${inviter}/accepted/${newcomer}`), tx.get(inviterXpPath)]);
    if (!edge) {
      tx.set(invitePath, { ...invite, state: "dropped", updatedAt: now });
      return true;
    }
    const month = statsDay(now).slice(0, 6);
    const monthCount = previous?.recruitMonth === month ? Number(previous.recruitMonthCount ?? 0) : 0;
    if (monthCount >= RECRUIT.monthlyCap) return false; // retried on a later chapter, e.g. next month
    // Outside the daily cap and the weekly XP; `updatedAt` stays so the inviter's own chapter gap is unaffected.
    const total = Number(previous?.total ?? 0) + RECRUIT.xp;
    const doc: Data = { ...(previous ?? { schemaVersion: 1, createdAt: now, updatedAt: 0 }), total, level: levelFor(total),
      recruits: Number(previous?.recruits ?? 0) + 1, recruitMonth: month, recruitMonthCount: monthCount + 1, recruitedAt: now };
    const badges: Record<string, number> = { ...((previous?.badges as Record<string, number> | undefined) ?? {}) };
    for (const badge of BADGES) if (!(badge.id in badges) && Number(doc[badge.field] ?? 0) >= badge.min) badges[badge.id] = now;
    doc.badges = badges;
    tx.set(inviterXpPath, doc);
    tx.set(invitePath, { ...invite, state: "credited", creditedAt: now, updatedAt: now });
    return true;
  }
  private async getFriend(actor: string, raw: unknown): Promise<Data> {
    const input = object(raw, ["otherUid"]), other = uid(input.otherUid);
    return this.store.transact(async (tx) => {
      await this.friends(tx, actor, other);
      const [profile, settings] = await Promise.all([tx.get(`profiles/${other}`), tx.get(`sharingSettings/${other}`)]);
      check(profile && settings, "not-found", "Profile unavailable.");
      const sharing: Data = { generation: settings.generation };
      // Settings written before a category existed lack the field: absent means private.
      for (const field of VISIBILITY_FIELDS) sharing[field] = settings[field] ?? "private";
      if (settings.invisible) { sharing.presence = "private"; sharing.lastSeen = "private"; sharing.feed = "private"; sharing.stats = "private"; }
      sharing.reviews = reviewsShared(settings);
      sharing.progress = progressShared(settings);
      return { profile, sharing };
    });
  }
  private async updateSharing(actor: string, raw: unknown): Promise<Data> {
    const input = object(raw, ["operationId", "expectedGeneration", "settings", "hideContentKey"]);
    // globalRanking, rankingMedia, reviews and progress are optional so older clients keep working; leaving one out keeps
    // the current choice.
    const settingsFields = [...VISIBILITY_FIELDS, "invisible"];
    const optional = ["globalRanking", "rankingMedia", "reviews", "progress"].filter((field) =>
      input.settings !== null && typeof input.settings === "object" && Object.hasOwn(input.settings, field));
    const expected = generation(input.expectedGeneration), supplied = object(input.settings, [...settingsFields, ...optional]);
    for (const field of VISIBILITY_FIELDS) check(typeof supplied[field] === "string" && ["private", "friends"].includes(supplied[field]), "invalid-argument", "Invalid visibility.");
    bool(supplied.invisible);
    for (const field of optional) bool(supplied[field]);
    const hidden = input.hideContentKey === null ? null : key(input.hideContentKey);
    return this.mutation(actor, "socialUpdateSharing", input, async (tx, now) => {
      const current = await this.settings(tx, actor, now);
      check(current.generation === expected, "failed-precondition", "Sharing generation changed.");
      check(expected < 2_147_483_647, "resource-exhausted", "Sharing generation exhausted.");
      const settings = { schemaVersion: 1, generation: expected + 1, globalRanking: current.globalRanking !== false,
        rankingMedia: current.rankingMedia !== false, reviews: current.reviews !== false, progress: current.progress !== false,
        ...supplied, updatedAt: now };
      const profile = await tx.get(`profiles/${actor}`);
      // A hidden title takes my reviews of it along (one per work, found by the title they were written for).
      const hiddenReviews = hidden ? await tx.query(`reviews/${actor}/items`, "content.contentKey", hidden) : [];
      // Any privacy change closes my open room to drop-ins (the room itself goes on for its members).
      const listed = await tx.get(`openRooms/${actor}`);
      const openRoom = listed ? await tx.get(`rooms/${String(listed.roomId)}`) : null;
      tx.set(`sharingSettings/${actor}`, settings);
      tx.delete(`activities/${actor}`);
      if (listed) tx.delete(`openRooms/${actor}`);
      if (openRoom && openRoom.hostUid === actor && openRoom.open === true) {
        tx.set(`rooms/${String(listed!.roomId)}`, { ...openRoom, open: false, updatedAt: now });
      }
      if (!rankedGlobally(settings)) await this.leaveLeaderboard(tx, actor, now);
      else if (profile) await this.renameOnLeaderboard(tx, actor, profile, now, settings); // pictures shown or hidden
      if (hidden) {
        tx.set(`hiddenTitles/${actor}/entries/${hidden}`, { schemaVersion: 1, contentKey: hidden, createdAt: now });
        tx.delete(`sharedLibraries/${actor}/entries/${hidden}`);
        tx.delete(`sharedFavorites/${actor}/entries/${hidden}`);
        for (const review of hiddenReviews) tx.delete(review.path);
      }
      return { settings };
    });
  }
  private async publishEntries(actor: string, raw: unknown): Promise<Data> {
    const input = object(raw, ["operationId", "generation", "kind", "entries", "deleteKeys"]);
    const gen = generation(input.generation), kind = text(input.kind, 1, 9);
    check(kind === "library" || kind === "favorites", "invalid-argument", "Unknown projection kind.");
    check(Array.isArray(input.entries) && Array.isArray(input.deleteKeys), "invalid-argument", "Expected bounded arrays.");
    check(input.entries.length + input.deleteKeys.length <= 50, "invalid-argument", "At most 50 projections/deletions per call; send additional pages.");
    // A library entry may carry the owner's reading progress next to its content fields (optional, library only).
    const progress: Array<Data | null> = [];
    const entries = input.entries.map((value: unknown) => {
      const has = value !== null && typeof value === "object" && Object.hasOwn(value, "progress");
      check(!has || kind === "library", "invalid-argument", "Only library entries carry progress.");
      if (!has) { progress.push(null); return validateContent(value); }
      const { progress: raw, ...fields } = value as Data;
      progress.push(validateProgress(raw));
      return validateContent(fields);
    });
    const deletes = input.deleteKeys.map(key);
    const keys = [...entries.map((entry) => String(entry.contentKey)), ...deletes];
    check(new Set(keys).size === keys.length, "invalid-argument", "Duplicate content keys.");
    return this.mutation(actor, "socialPublishEntries", input, async (tx, now) => {
      const settings = await this.settings(tx, actor, now);
      check(settings.generation === gen, "failed-precondition", "Sharing generation changed.");
      if (entries.length) check(settings[kind] === "friends", "failed-precondition", "Enable sharing before publishing.");
      const hidden = await Promise.all(entries.map((entry) => tx.get(`hiddenTitles/${actor}/entries/${entry.contentKey as string}`)));
      check(hidden.every((entry) => !entry), "failed-precondition", "A hidden title cannot be published.");
      const collection = kind === "library" ? "sharedLibraries" : "sharedFavorites";
      // Progress sharing off: entries are stored without it, whatever the app sent (older apps don't know the switch).
      const withProgress = progressShared(settings);
      entries.forEach((entry, i) => tx.set(`${collection}/${actor}/entries/${entry.contentKey as string}`,
        { schemaVersion: 1, content: entry, ...(withProgress && progress[i] ? { progress: progress[i] } : {}), generation: gen, updatedAt: now }));
      for (const id of deletes) tx.delete(`${collection}/${actor}/entries/${id}`);
      return { generation: gen, published: entries.length, deleted: deletes.length };
    });
  }
  private async publishActivity(actor: string, raw: unknown): Promise<Data> {
    const input = object(raw, ["operationId", "generation", "activity"]), gen = generation(input.generation);
    let activity: Data | null = null;
    if (input.activity !== null) {
      activity = object(input.activity, ["content", "chapterKey", "chapterLabel"]);
      activity = { content: validateContent(activity.content), chapterKey: key(activity.chapterKey), chapterLabel: text(activity.chapterLabel, 1, 100) };
    }
    return this.mutation(actor, "socialPublishActivity", input, async (tx, now) => {
      if (!activity) { tx.delete(`activities/${actor}`); return { expiresAt: null }; }
      const settings = await this.settings(tx, actor, now);
      check(settings.generation === gen && settings.activity === "friends", "failed-precondition", "Activity consent changed.");
      const hidden = await tx.get(`hiddenTitles/${actor}/entries/${(activity.content as Data).contentKey as string}`);
      check(!hidden, "failed-precondition", "A hidden title cannot be published.");
      const expiresAt = now + 120_000;
      tx.set(`activities/${actor}`, { schemaVersion: 1, ...activity, generation: gen, updatedAt: now, expiresAt });
      return { expiresAt };
    });
  }
  private async publishFeed(actor: string, raw: unknown): Promise<Data> {
    const input = object(raw, ["operationId", "generation", "kind", "content", "chapterLabel"]), gen = generation(input.generation);
    const kind = text(input.kind, 1, 20);
    check(FEED_KINDS.includes(kind), "invalid-argument", "Unknown feed kind.");
    const content = validateContent(input.content);
    const chapterLabel = kind === "chapter_read" ? text(input.chapterLabel, 1, 100) : input.chapterLabel;
    check(kind === "chapter_read" || chapterLabel === null, "invalid-argument", "Only chapter items carry a label.");
    return this.mutation(actor, "socialPublishFeed", input, async (tx, now) => {
      const settings = await this.settings(tx, actor, now);
      check(settings.generation === gen && settings.feed === "friends" && !settings.invisible, "failed-precondition", "Feed consent changed.");
      const hidden = await tx.get(`hiddenTitles/${actor}/entries/${content.contentKey as string}`);
      check(!hidden, "failed-precondition", "A hidden title cannot be published.");
      const state = await tx.get(`feedState/${actor}`);
      const next = Number(state?.next ?? 0);
      const slot = (n: number) => String(n % FEED_SLOTS).padStart(2, "0");
      const previous = next > 0 ? await tx.get(`feeds/${actor}/items/${slot(next - 1)}`) : null;
      // Re-reading or re-favoriting the same thing shortly after is not news.
      if (previous && previous.kind === kind && previous.generation === gen && previous.chapterLabel === chapterLabel &&
        (previous.content as Data).contentKey === content.contentKey && now - Number(previous.createdAt) < FEED_DEDUPE) {
        return { published: false, expiresAt: previous.expiresAt };
      }
      const expiresAt = now + FEED_TTL;
      tx.set(`feeds/${actor}/items/${slot(next)}`, { schemaVersion: 1, kind, content, chapterLabel, generation: gen, createdAt: now, expiresAt });
      tx.set(`feedState/${actor}`, { schemaVersion: 1, next: next + 1, updatedAt: now });
      return { published: true, expiresAt };
    });
  }
  private async updateProfileStyle(actor: string, raw: unknown): Promise<Data> {
    const input = object(raw, ["operationId", "bio", "accent", "avatar", "banner"]);
    check(typeof input.bio === "string" && input.bio.length <= 160 && (input.bio === "" || PLAIN_TEXT.test(input.bio)), "invalid-argument", "Bio must be one line of at most 160 characters.");
    const bio = (input.bio as string).trim(), accent = integer(input.accent, 0, PROFILE_ACCENTS - 1);
    const media = { avatar: input.avatar, banner: input.banner };
    for (const action of Object.values(media)) check(action === "keep" || action === "set" || action === "remove", "invalid-argument", "Media action must be keep, set or remove.");
    return this.mutation(actor, "socialUpdateProfileStyle", input, async (tx, now) => {
      const existing = await tx.get(`profiles/${actor}`);
      check(existing, "failed-precondition", "Create your profile first.");
      const profile: Data = { ...existing, bio, accent, updatedAt: now };
      for (const kind of ["avatar", "banner"] as const) {
        if (media[kind] === "remove") profile[kind] = null;
        if (media[kind] !== "set") continue;
        // The upload must already exist, be small enough and be a supported image (GIFs included).
        const meta = this.media ? await this.media.inspect(`profileMedia/${actor}/${kind}`) : null;
        check(meta, "failed-precondition", "Upload the image first.");
        check(meta.size > 0 && meta.size <= PROFILE_MEDIA[kind] && PROFILE_MEDIA_TYPES.includes(meta.contentType), "invalid-argument", "Unsupported or too large image.");
        profile[kind] = { version: now, contentType: meta.contentType, size: meta.size };
      }
      tx.set(`profiles/${actor}`, profile);
      await this.renameOnLeaderboard(tx, actor, profile, now);
      return { profile };
    });
  }
  private async recordChapter(actor: string, raw: unknown): Promise<Data> {
    // roomId (optional): the room the chapter was read in, for the reading-together XP bonus.
    const inRoom = raw !== null && typeof raw === "object" && Object.hasOwn(raw, "roomId");
    const input = object(raw, ["operationId", "generation", "pages", ...(inRoom ? ["roomId"] : [])]), gen = generation(input.generation);
    const pages = integer(input.pages, 1, 2000);
    const roomId = inRoom && input.roomId !== null ? key(input.roomId) : null;
    return this.mutation(actor, "socialRecordChapter", input, async (tx, now) => {
      const settings = await this.settings(tx, actor, now);
      // Invisible counts nothing. Stats and the ranking need consent; XP is counted for the reader either way.
      check(settings.generation === gen && !settings.invisible, "failed-precondition", "Stats consent changed.");
      const counted = settings.stats === "friends" || rankedGlobally(settings);
      const day = statsDay(now), path = `dailyStats/${actor}/days/${day}`, xpPath = `xp/${actor}`;
      const [current, xpDoc] = await Promise.all([tx.get(path), tx.get(xpPath)]);
      check(!current || now - Number(current.updatedAt) >= STATS_GAP, "resource-exhausted", "Slow down.");
      check(!xpDoc || now - Number(xpDoc.updatedAt) >= STATS_GAP, "resource-exhausted", "Slow down.");
      // A generation change starts the day's public counter fresh, so revoked consent never carries over.
      const same = current && current.generation === gen;
      // Streak: consecutive UTC days with at least one chapter, continued only within the same consent generation.
      let streak = same ? Number(current.streak ?? 1) : 1;
      if (!same) {
        const yesterday = await tx.get(`dailyStats/${actor}/days/${statsDay(now - DAY)}`);
        if (yesterday && yesterday.generation === gen && Number(yesterday.chapters) > 0) streak = Number(yesterday.streak ?? 1) + 1;
      }
      let together = false;
      if (roomId) {
        const room = await tx.get(`rooms/${roomId}`), members = (room?.members ?? {}) as Record<string, Data>;
        together = Boolean(room && room.state === "active" && Number(room.leaseExpiresAt) > now && members[actor]?.state === "joined" &&
          Object.entries(members).some(([uid, m]) => uid !== actor && m.state === "joined"));
      }
      const xp = this.awardXp(xpDoc, now, together);
      if (!xpDoc || xpDoc.invitePending) {
        if (await this.creditInviter(tx, actor, Number(xp.doc.chapters), now)) delete xp.doc.invitePending;
        else xp.doc.invitePending = true;
      }
      tx.set(xpPath, xp.doc);
      const reward = { gained: xp.gained, total: xp.doc.total, level: xp.level, leveledUp: xp.leveledUp, unlocked: xp.unlocked, freezeUsed: xp.freezeUsed,
        challenges: xp.challenges, challengeXp: xp.challengeXp, season: xp.season };
      if (!counted) return { day, counted: false, xp: reward };
      const stats = { schemaVersion: 1, day, generation: gen, chapters: (same ? Number(current.chapters) : 0) + 1,
        pages: (same ? Number(current.pages) : 0) + pages, streak, updatedAt: now, expiresAt: now + STATS_TTL };
      tx.set(path, stats);
      if (rankedGlobally(settings)) await this.placeOnLeaderboard(tx, actor, stats.chapters, streak, now, xp.level, xp.doc, settings);
      return { day, chapters: stats.chapters, pages: stats.pages, streak, xp: reward };
    });
  }
  /**
   * XP for one chapter: [XP.chapter], plus [XP.together] in a room with someone else, plus a streak bonus on the
   * day's first chapter. At most [XP.dailyCap] a day. Also keeps lifetime counters for the profile.
   */
  private awardXp(previous: Data | null, now: number, together: boolean) {
    const day = statsDay(now), sameDay = previous?.day === day;
    const today = sameDay ? Number(previous!.today) : 0;
    // Its own reading streak (independent of sharing): consecutive UTC days with a chapter. Held freezes cover
    // missed days; a freeze is earned every 7 streak days.
    let streak = 1, freezes = Number(previous?.freezes ?? 0), freezeUsed = 0;
    if (sameDay) {
      streak = Number(previous!.streak);
    } else if (previous?.day) {
      const missed = dayNumber(day) - dayNumber(String(previous.day)) - 1;
      if (missed === 0) streak = Number(previous.streak) + 1;
      else if (missed > 0 && missed <= freezes) { streak = Number(previous.streak) + 1; freezes -= missed; freezeUsed = missed; }
    }
    if (!sameDay && streak % 7 === 0) freezes = Math.min(MAX_FREEZES, freezes + 1);
    let gained: number = XP.chapter + (together ? XP.together : 0);
    if (!sameDay && streak > 1) gained += Math.min(streak, XP.streakMaxDays) * XP.streakDay;
    const multiplier = xpMultiplier(now);
    gained = Math.max(0, Math.min(gained * multiplier, XP.dailyCap * multiplier - today));
    const week = weekOf(now), sameWeek = previous?.week === week;
    const dayChapters = (sameDay ? Number(previous!.dayChapters ?? 0) : 0) + 1;
    // Monthly challenge counters (an older doc without them starts the month from this chapter).
    const monthId = monthOf(now), last = previous?.month as Data | undefined, sameMonth = last?.id === monthId;
    const was = (field: MonthField) => (sameMonth ? Number(last![field] ?? 0) : 0);
    const month: Record<MonthField, number> & { id: string } = {
      id: monthId, chapters: was("chapters") + 1, days: was("days") + (sameDay && sameMonth ? 0 : 1),
      together: was("together") + (together ? 1 : 0), streak: Math.max(was("streak"), streak),
      bestDay: Math.max(was("bestDay"), dayChapters), xp: was("xp") + gained,
    };
    const stamps: Record<string, string[]> = { ...((previous?.challenges as Record<string, string[]> | undefined) ?? {}) };
    const done = [...(stamps[monthId] ?? [])], current = challengesFor(monthId);
    const completed = current.filter((c) => !done.includes(c.id) && month[c.field] >= c.min).map((c) => c.id);
    if (completed.length) stamps[monthId] = [...done, ...completed];
    const challengeXp = completed.length * CHALLENGE_XP;
    const before = Number(previous?.total ?? 0), total = before + gained + challengeXp;
    const level = levelFor(total);
    const doc: Data = {
      schemaVersion: 1, total, level, day, today: today + gained,
      chapters: Number(previous?.chapters ?? 0) + 1, together: Number(previous?.together ?? 0) + (together ? 1 : 0),
      bestStreak: Math.max(Number(previous?.bestStreak ?? 0), streak), streak, freezes,
      days: Number(previous?.days ?? 0) + (sameDay ? 0 : 1), dayChapters, bestDay: Math.max(Number(previous?.bestDay ?? 0), dayChapters),
      week, weekXp: (sameWeek ? Number(previous!.weekXp ?? 0) : 0) + gained,
      // Chapters this week, for the weekly ranking (docs from before it start counting at their next chapter).
      weekChapters: (sameWeek ? Number(previous!.weekChapters ?? 0) : 0) + 1,
      lastWeekXp: sameWeek ? Number(previous!.lastWeekXp ?? 0) : previous?.week === weekOf(now - 7 * DAY) ? Number(previous!.weekXp ?? 0) : 0,
      month, challenges: stamps, createdAt: previous?.createdAt ?? now, updatedAt: now,
    };
    for (const field of XP_CARRIED) if (previous?.[field] !== undefined) doc[field] = previous[field];
    const badges: Record<string, number> = { ...((previous?.badges as Record<string, number> | undefined) ?? {}) };
    const unlocked = BADGES.filter((badge) => !(badge.id in badges) && Number(doc[badge.field] ?? 0) >= badge.min).map((badge) => badge.id);
    for (const id of unlocked) badges[id] = now;
    const allDone = completed.length > 0 && current.every((c) => stamps[monthId]!.includes(c.id));
    const season = allDone && !(seasonBadge(monthId) in badges) ? seasonBadge(monthId) : null;
    if (season) badges[season] = now;
    doc.badges = badges;
    return { doc, gained: gained + challengeXp, level, leveledUp: level > levelFor(before), unlocked, freezeUsed, challenges: completed, challengeXp, season };
  }
  /**
   * Puts the actor on today's global ranking with only public fields (name, handle, accent, counts), keeping the
   * top [LEADERBOARD_SIZE]: the lowest count drops out, and among equal counts the latest arrival.
   */
  private async placeOnLeaderboard(tx: Transaction, actor: string, chapters: number, streak: number, now: number, level: number, xp: Data, settings: Data): Promise<void> {
    const allTimePath = `leaderboards/${ALL_TIME_BOARD}`;
    const [profile, allTime] = await Promise.all([tx.get(`profiles/${actor}`), tx.get(allTimePath)]);
    if (!profile) return;
    const media = boardMedia(profile, settings);
    // All time: by total XP (lifetime chapters as the tie-break), no expiry.
    const lifetime: Record<string, Data> = { ...((allTime?.entries as Record<string, Data> | undefined) ?? {}) };
    lifetime[actor] = { handle: profile.handle, displayName: profile.displayName, accent: profile.accent ?? 0, ...media, xp: Number(xp.total),
      chapters: Number(xp.chapters), level, updatedAt: now };
    const byXp = Object.entries(lifetime).sort(([, a], [, b]) =>
      Number(b.xp) - Number(a.xp) || Number(b.chapters) - Number(a.chapters) || Number(a.updatedAt) - Number(b.updatedAt));
    tx.set(allTimePath, { schemaVersion: 1, day: ALL_TIME_BOARD, entries: Object.fromEntries(byXp.slice(0, LEADERBOARD_SIZE)), updatedAt: now });
    const day = statsDay(now), path = `leaderboards/${day}`, board = await tx.get(path);
    const entries: Record<string, Data> = { ...((board?.entries as Record<string, Data> | undefined) ?? {}) };
    entries[actor] = { handle: profile.handle, displayName: profile.displayName, accent: profile.accent ?? 0, ...media, chapters, streak, level, updatedAt: now };
    const ordered = Object.entries(entries).sort(([, a], [, b]) =>
      Number(b.chapters) - Number(a.chapters) || Number(a.updatedAt) - Number(b.updatedAt));
    tx.set(path, { schemaVersion: 1, day, entries: Object.fromEntries(ordered.slice(0, LEADERBOARD_SIZE)), updatedAt: now, expiresAt: now + STATS_TTL });
  }
  /** Opting out (or going invisible) removes today's and the all-time entry at once. */
  private async leaveLeaderboard(tx: Transaction, actor: string, now: number): Promise<void> {
    const paths = [`leaderboards/${statsDay(now)}`, `leaderboards/${ALL_TIME_BOARD}`];
    const boards = await Promise.all(paths.map((path) => tx.get(path)));
    boards.forEach((board, index) => {
      const entries = board?.entries as Record<string, Data> | undefined;
      if (!board || !entries || !Object.hasOwn(entries, actor)) return;
      const { [actor]: _removed, ...rest } = entries;
      tx.set(paths[index]!, { ...board, entries: rest, updatedAt: now });
    });
  }
  /** A new display name, handle, accent or picture (or its visibility) shows on today's and the all-time ranking right away. */
  private async renameOnLeaderboard(tx: Transaction, actor: string, profile: Data, now: number, known?: Data): Promise<void> {
    const paths = [`leaderboards/${statsDay(now)}`, `leaderboards/${ALL_TIME_BOARD}`];
    const [settings, ...boards] = await Promise.all([known ?? this.settings(tx, actor, now), ...paths.map((path) => tx.get(path))]);
    const media = boardMedia(profile, settings!);
    boards.forEach((board, index) => {
      const entries = board?.entries as Record<string, Data> | undefined;
      if (!board || !entries || !Object.hasOwn(entries, actor)) return;
      tx.set(paths[index]!, { ...board, entries: { ...entries, [actor]: { ...entries[actor], handle: profile.handle, displayName: profile.displayName, accent: profile.accent ?? 0, ...media } } });
    });
  }
  /**
   * Private messages between accepted friends: `send` appends to `chats/{pairId}/messages` and updates both people's
   * chat lists (`chatIndex/{uid}/chats/{other}`, with an unread count); `read` clears my unread count; `react`
   * sets, replaces or (same emoji again) removes my reaction to a message.
   */
  private async message(actor: string, raw: unknown): Promise<Data> {
    // Optional: content (a recommended title, shown as a card), replyTo (send) and seq + emoji (react).
    const optional = optionalFields(raw, ["content", "replyTo", "seq", "emoji"]), hasContent = optional.includes("content");
    const input = object(raw, ["operationId", "action", "otherUid", "text", ...optional]);
    const other = uid(input.otherUid), action = text(input.action, 1, 5);
    check(other !== actor, "invalid-argument", "Choose another account.");
    check(["send", "read", "react"].includes(action), "invalid-argument", "Unknown message action.");
    const replyTo = replyTarget(input, action), reaction = reactionInput(input, action);
    let body = "", content: Data | null = null;
    if (action === "send") {
      ({ body, content } = messageBody(input, hasContent));
    } else if (action === "read") {
      check(input.text === null && (!hasContent || input.content === null), "invalid-argument", "Reading carries no text.");
    }
    const preview = messagePreview(body, content);
    if (reaction) {
      return this.mutation(actor, "socialMessage", input, async (tx, now) => {
        await this.friends(tx, actor, other);
        await this.reactionRate(tx, actor, now);
        const chatId = pairId(actor, other);
        const emoji = await this.react(tx, `chats/${chatId}/messages/${messageId(reaction.seq)}`, actor, reaction.emoji, now);
        return { chatId, seq: reaction.seq, emoji };
      }, REACTION_QUOTA);
    }
    return this.mutation(actor, "socialMessage", input, async (tx, now, notify) => {
      await this.friends(tx, actor, other);
      const chatId = pairId(actor, other), chatPath = `chats/${chatId}`;
      const minePath = `chatIndex/${actor}/chats/${other}`, theirsPath = `chatIndex/${other}/chats/${actor}`;
      const ratePath = `chatRates/${chatId}/users/${actor}`;
      const [chat, mine, theirs, rate, profile] = await Promise.all([
        tx.get(chatPath), tx.get(minePath), tx.get(theirsPath), tx.get(ratePath), tx.get(`profiles/${actor}`),
      ]);
      if (action === "read") {
        if (mine && Number(mine.unread) > 0) tx.set(minePath, { ...mine, unread: 0 });
        return { chatId, unread: 0 };
      }
      check(!rate || now - Number(rate.lastAt) >= ROOM_MESSAGE_GAP, "resource-exhausted", "Slow down.");
      const within = rate && now - Number(rate.windowStartedAt) < 60_000;
      const count = within ? Number(rate.count) + 1 : 1;
      check(count <= ROOM_MESSAGES_PER_MINUTE, "resource-exhausted", "Too many messages. Try again in a minute.");
      tx.set(ratePath, { schemaVersion: 1, count, windowStartedAt: within ? rate!.windowStartedAt : now, lastAt: now, expiresAt: now + DAY });
      const quoted = replyTo === null ? null : quote(await this.target(tx, `${chatPath}/messages/${messageId(replyTo)}`, now));
      const seq = Number(chat?.next ?? 0), expiresAt = now + CHAT_TTL;
      tx.set(`${chatPath}/messages/${messageId(seq)}`, { schemaVersion: 1, seq, senderUid: actor, text: body,
        ...(content ? { content } : {}), ...(quoted ? { replyTo: quoted } : {}), createdAt: now, expiresAt });
      tx.set(chatPath, { schemaVersion: 1, participants: [actor, other].sort(), next: seq + 1, updatedAt: now, expiresAt });
      const last = { schemaVersion: 1, chatId, lastText: preview, lastSenderUid: actor, updatedAt: now, expiresAt };
      tx.set(minePath, { ...last, otherUid: other, unread: 0 });
      tx.set(theirsPath, { ...last, otherUid: actor, unread: Number(theirs?.unread ?? 0) + 1 });
      notify({ to: other, kind: "message", data: { name: String(profile?.displayName ?? ""), title: preview, uid: actor } });
      return { chatId, seq, createdAt: now };
    });
  }
  /**
   * "Let's read this" (read matches): `add` flags a title for one friend, `remove` takes the flag back. The pair's
   * flags live in the server-only `readWishes/{pairId}/items/{contentKey}`; each person sees only their own list
   * (`readWishIndex/{uid}/items/{otherUid}_{contentKey}`). Only when both flagged the same title is it revealed: both
   * entries become `matched`, a "match" line goes into their private chat and the other person gets a push.
   */
  private async readWish(actor: string, raw: unknown): Promise<Data> {
    const input = object(raw, ["operationId", "action", "otherUid", "content"]);
    const other = uid(input.otherUid), action = text(input.action, 1, 6);
    check(other !== actor, "invalid-argument", "Choose another account.");
    check(action === "add" || action === "remove", "invalid-argument", "Unknown wish action.");
    const content = validateContent(input.content), contentKey = String(content.contentKey);
    return this.mutation(actor, "socialReadWish", input, async (tx, now, notify) => {
      const pairPath = `readWishes/${pairId(actor, other)}/items/${contentKey}`;
      const minePath = `readWishIndex/${actor}/items/${other}_${contentKey}`, theirsPath = `readWishIndex/${other}/items/${actor}_${contentKey}`;
      if (action === "add") await this.friends(tx, actor, other);
      const [pair, mine, theirs] = await Promise.all([tx.get(pairPath), tx.get(minePath), tx.get(theirsPath)]);
      const flags: Record<string, number> = { ...((pair?.flags as Record<string, number> | undefined) ?? {}) };
      if (action === "remove") {
        // Taking a wish back needs no friendship, so a former friend can still clean up their own list.
        if (actor in flags) {
          delete flags[actor];
          if (Object.keys(flags).length) tx.set(pairPath, { ...pair, flags, updatedAt: now });
          else tx.delete(pairPath);
        }
        tx.delete(minePath);
        if (mine?.matched && theirs) tx.set(theirsPath, { ...theirs, matched: false, matchedAt: null });
        return { state: "removed" };
      }
      if (actor in flags && mine) return { state: mine.matched ? "matched" : "wished" };
      const limitPath = `readWishLimits/${actor}`, limit = await tx.get(limitPath), day = statsDay(now);
      const count = limit?.day === day ? Number(limit.count) + 1 : 1;
      check(count <= READ_WISHES_PER_DAY, "resource-exhausted", "Too many wishes today. Try again tomorrow.");
      tx.set(limitPath, { schemaVersion: 1, day, count });
      flags[actor] = now;
      const expiresAt = now + READ_WISH_TTL, matched = other in flags;
      tx.set(pairPath, { schemaVersion: 1, contentKey, content, flags, createdAt: pair?.createdAt ?? now, updatedAt: now, expiresAt });
      const entry = (otherUid: string) => ({ schemaVersion: 1, otherUid, contentKey, content });
      tx.set(minePath, { ...entry(other), matched, matchedAt: matched ? now : null, createdAt: now, expiresAt });
      if (!matched) return { state: "wished" };
      tx.set(theirsPath, { ...entry(actor), ...(theirs ?? {}), matched: true, matchedAt: now, createdAt: theirs?.createdAt ?? now, expiresAt });
      const title = String(content.title), profile = await tx.get(`profiles/${actor}`);
      await this.systemChatMessage(tx, actor, other, actor, { content, system: "match" }, `🤝 You both want to read ${title}`, other, now);
      notify({ to: other, kind: "read_match", data: { name: String(profile?.displayName ?? ""), title, uid: actor } });
      return { state: "matched" };
    });
  }
  /**
   * A light "keep reading" push to one friend. Server-only bookkeeping in `nudgeLimits/{actor}`: the UTC day's count
   * and when each friend was last nudged (one per pair per 24 h, [NUDGES_PER_DAY] people a day). The push carries the
   * target's streak (`title`) only when they share stats with friends, are visible, have a streak of 2+ and last read
   * yesterday (UTC), i.e. it is at risk. Nothing client-readable is written.
   */
  private async nudge(actor: string, raw: unknown): Promise<Data> {
    const input = object(raw, ["operationId", "otherUid"]), other = uid(input.otherUid);
    check(other !== actor, "invalid-argument", "Choose another account.");
    return this.mutation(actor, "socialNudge", input, async (tx, now, notify) => {
      await this.friends(tx, actor, other);
      const limitPath = `nudgeLimits/${actor}`, day = statsDay(now);
      const [limit, profile, sharing, xp] = await Promise.all([tx.get(limitPath), tx.get(`profiles/${actor}`),
        tx.get(`sharingSettings/${other}`), tx.get(`xp/${other}`)]);
      const stored = (limit?.pairs as Record<string, number> | undefined) ?? {};
      const pairs: Record<string, number> = {};
      for (const [id, at] of Object.entries(stored)) if (now - Number(at) < NUDGE_PAIR_GAP) pairs[id] = Number(at);
      check(!(other in pairs), "resource-exhausted", "You nudged them recently. Try again tomorrow.");
      const count = limit?.day === day ? Number(limit.count) + 1 : 1;
      check(count <= NUDGES_PER_DAY, "resource-exhausted", "Too many nudges today. Try again tomorrow.");
      pairs[other] = now;
      tx.set(limitPath, { schemaVersion: 1, day, count, pairs, updatedAt: now });
      const atRisk = sharing?.stats === "friends" && !sharing.invisible && xp !== null && Number(xp.streak) >= 2 &&
        xp.day === statsDay(now - DAY);
      notify({ to: other, kind: "nudge", data: { name: String(profile?.displayName ?? ""),
        ...(atRisk ? { title: String(Number(xp!.streak)) } : {}), uid: actor } });
      return { nudged: true };
    });
  }
  /**
   * Ratings and reviews: `set` stores my stars (1–5) and optional text for a title's work at
   * `reviews/{me}/items/{workKey}` (one per work, editing keeps `createdAt`); `delete` removes it. Friends read them
   * directly while my reviews switch is on and I'm visible (rules). Hidden titles can't be reviewed.
   */
  private async review(actor: string, raw: unknown): Promise<Data> {
    const input = object(raw, ["operationId", "action", "content", "rating", "text"]);
    const action = text(input.action, 1, 6);
    check(action === "set" || action === "delete", "invalid-argument", "Unknown review action.");
    const content = validateContent(input.content);
    // Reviews carry the library projection only: no room source (and with it no source URL).
    check(!Object.hasOwn(content, "source"), "invalid-argument", "Reviews carry no source.");
    let rating: number | null = null, body: string | null = null;
    if (action === "set") {
      rating = integer(input.rating, 1, 5);
      body = reviewText(input.text);
    } else {
      check(input.rating === null && input.text === null, "invalid-argument", "Deleting carries no rating or text.");
    }
    const id = workKey(content), path = `reviews/${actor}/items/${id}`;
    return this.mutation(actor, "socialReview", input, async (tx, now) => {
      const existing = await tx.get(path);
      if (action === "delete") {
        tx.delete(path);
        return { workKey: id, deleted: Boolean(existing) };
      }
      const hidden = await tx.get(`hiddenTitles/${actor}/entries/${content.contentKey as string}`);
      check(!hidden, "failed-precondition", "A hidden title cannot be reviewed.");
      const limitPath = `reviewLimits/${actor}`, limit = await tx.get(limitPath), day = statsDay(now);
      const count = limit?.day === day ? Number(limit.count) + 1 : 1;
      check(count <= REVIEWS_PER_DAY, "resource-exhausted", "Too many reviews today. Try again tomorrow.");
      tx.set(limitPath, { schemaVersion: 1, day, count });
      const review = { schemaVersion: 1, workKey: id, content, rating, text: body, createdAt: existing?.createdAt ?? now, updatedAt: now };
      tx.set(path, review);
      return { workKey: id, review };
    });
  }
  /**
   * "Ask to read along": `ask` puts a 10-minute request in the friend's list (`joinRequests/{friend}/items/{me}`) and
   * pushes it; only while that friend shares what they're reading. The friend accepts by inviting (which clears the
   * request) or `decline`s it. One pending request per pair.
   */
  private async joinRequest(actor: string, raw: unknown): Promise<Data> {
    const input = object(raw, ["operationId", "action", "otherUid"]);
    const other = uid(input.otherUid), action = text(input.action, 1, 8);
    check(other !== actor, "invalid-argument", "Choose another account.");
    check(action === "ask" || action === "decline", "invalid-argument", "Unknown request action.");
    return this.mutation(actor, "socialJoinRequest", input, async (tx, now, notify) => {
      if (action === "decline") {
        tx.delete(`joinRequests/${actor}/items/${other}`);
        return { state: "declined" };
      }
      await this.friends(tx, actor, other);
      const path = `joinRequests/${other}/items/${actor}`;
      const [settings, activity, existing, profile, rate] = await Promise.all([
        tx.get(`sharingSettings/${other}`), tx.get(`activities/${other}`), tx.get(path), tx.get(`profiles/${actor}`),
        tx.get(`joinRequestLimits/${actor}`),
      ]);
      check(settings?.activity === "friends" && !settings.invisible && activity && Number(activity.expiresAt) > now,
        "failed-precondition", "Your friend isn't sharing what they read right now.");
      if (existing && Number(existing.expiresAt) > now) return { state: "pending", expiresAt: existing.expiresAt };
      const within = rate && now - Number(rate.windowStartedAt) < 3_600_000;
      const count = within ? Number(rate.count) + 1 : 1;
      check(count <= JOIN_REQUESTS_PER_HOUR, "resource-exhausted", "Too many requests. Try again later.");
      tx.set(`joinRequestLimits/${actor}`, { schemaVersion: 1, count, windowStartedAt: within ? rate!.windowStartedAt : now });
      const expiresAt = now + JOIN_REQUEST_TTL;
      tx.set(path, { schemaVersion: 1, requesterUid: actor, name: String(profile?.displayName ?? ""), handle: String(profile?.handle ?? ""),
        createdAt: now, expiresAt });
      notify({ to: other, kind: "join_request", data: { name: String(profile?.displayName ?? ""), handle: String(profile?.handle ?? ""), uid: actor } });
      return { state: "pending", expiresAt };
    });
  }
  /**
   * Group chats (`groups/{groupId}`, messages under it, each member's list in `groupIndex/{uid}/groups/{groupId}`).
   * Actions: `create` (name + friends), `add` (my friends), `remove` (owner only), `leave` (ownership passes to the
   * longest member; the last one out deletes the group), `rename`, `send`, `read`, `react`. Members see messages from
   * when they joined (`names[uid].since`), enforced by the rules too.
   */
  private async groupChat(actor: string, raw: unknown): Promise<Data> {
    const optional = optionalFields(raw, ["content", "replyTo", "seq", "emoji"]), hasContent = optional.includes("content");
    const input = object(raw, ["operationId", "action", "groupId", "name", "uids", "text", ...optional]);
    const action = text(input.action, 1, 8);
    check(["create", "add", "remove", "leave", "rename", "send", "read", "react"].includes(action), "invalid-argument", "Unknown group action.");
    const replyTo = replyTarget(input, action), reaction = reactionInput(input, action);
    const needs = (field: string, wanted: boolean) =>
      check((input[field] !== null) === wanted, "invalid-argument", `${field} ${wanted ? "required" : "not allowed"}.`);
    needs("groupId", action !== "create");
    needs("name", action === "create" || action === "rename");
    needs("uids", ["create", "add", "remove"].includes(action));
    check(action === "send" || !hasContent || input.content === null, "invalid-argument", "content not allowed.");
    if (action !== "send") needs("text", false);
    const groupId = action === "create" ? digest(["group", actor, input.operationId]) : key(input.groupId);
    let name = "";
    if (input.name !== null) {
      name = text(input.name, 1, 40, PLAIN_TEXT).trim();
      check(name.length > 0, "invalid-argument", "Name the group.");
    }
    let uids: string[] = [];
    if (input.uids !== null) {
      check(Array.isArray(input.uids) && input.uids.length >= 1 && input.uids.length < GROUP_MAX_MEMBERS, "invalid-argument", "Invalid members.");
      uids = [...new Set((input.uids as unknown[]).map(uid))];
      check(!uids.includes(actor), "invalid-argument", "Choose other people.");
      check(action !== "remove" || uids.length === 1, "invalid-argument", "Remove one person at a time.");
    }
    let body = "", content: Data | null = null;
    if (action === "send") ({ body, content } = messageBody(input, hasContent));
    if (reaction) {
      return this.mutation(actor, "socialGroupChat", input, async (tx, now) => {
        const group = await tx.get(`groups/${groupId}`);
        check(group && (group.members as string[]).includes(actor), "permission-denied", "Group member required.");
        await this.reactionRate(tx, actor, now);
        const since = Number((group.names as Record<string, Data>)[actor]?.since ?? 0);
        const emoji = await this.react(tx, `groups/${groupId}/messages/${messageId(reaction.seq)}`, actor, reaction.emoji, now, since);
        return { groupId, seq: reaction.seq, emoji };
      }, REACTION_QUOTA);
    }
    return this.mutation(actor, "socialGroupChat", input, async (tx, now, notify) => {
      const path = `groups/${groupId}`, me = await tx.get(`profiles/${actor}`);
      check(me, "failed-precondition", "Create your profile first.");
      const card = (profile: Data, since: number) => ({ name: profile.displayName, handle: profile.handle, accent: profile.accent ?? 0, since, joinedAt: now });
      const group = await tx.get(path);
      if (action === "create") {
        check(!group, "already-exists", "Group exists.");
        const limitPath = `groupLimits/${actor}`, limit = await tx.get(limitPath), day = statsDay(now);
        const count = limit?.day === day ? Number(limit.count) + 1 : 1;
        check(count <= GROUPS_CREATED_PER_DAY, "resource-exhausted", "Too many new groups today.");
        tx.set(limitPath, { schemaVersion: 1, day, count });
        const profiles = await this.addable(tx, actor, uids);
        const names: Record<string, Data> = { [actor]: card(me, 0) };
        uids.forEach((uid, i) => { names[uid] = card(profiles[i]!, 0); });
        const created = { schemaVersion: 1, groupId, name, ownerUid: actor, members: [actor, ...uids], names, next: 0, createdAt: now, updatedAt: now };
        const after = await this.groupEvent(tx, created, actor, "created", name, now);
        for (const uid of uids) notify({ to: uid, kind: "group_added", data: { name: String(me.displayName), title: name, groupId, uid: actor } });
        return { groupId, group: after };
      }
      check(group && (group.members as string[]).includes(actor), "permission-denied", "Group member required.");
      let members = [...(group.members as string[])], names = { ...(group.names as Record<string, Data>) };
      const seq = Number(group.next);
      const indexOf = (uid: string) => `groupIndex/${uid}/groups/${groupId}`;
      switch (action) {
        case "read": {
          const mine = await tx.get(indexOf(actor));
          if (mine && Number(mine.unread) > 0) tx.set(indexOf(actor), { ...mine, unread: 0 });
          return { groupId, unread: 0 };
        }
        case "send": {
          const ratePath = `groupRates/${groupId}/users/${actor}`, rate = await tx.get(ratePath);
          check(!rate || now - Number(rate.lastAt) >= ROOM_MESSAGE_GAP, "resource-exhausted", "Slow down.");
          const within = rate && now - Number(rate.windowStartedAt) < 60_000;
          const count = within ? Number(rate.count) + 1 : 1;
          check(count <= ROOM_MESSAGES_PER_MINUTE, "resource-exhausted", "Too many messages. Try again in a minute.");
          tx.set(ratePath, { schemaVersion: 1, count, windowStartedAt: within ? rate!.windowStartedAt : now, lastAt: now, expiresAt: now + DAY });
          // A group quote also keeps the sender's name (they may have left by the time it's read).
          const original = replyTo === null ? null
            : await this.target(tx, `groups/${groupId}/messages/${messageId(replyTo)}`, now, Number(names[actor]?.since ?? 0));
          const quoted = original ? { ...quote(original), senderName: String(original.senderName ?? "") } : null;
          const after = await this.groupMessage(tx, group, { kind: "text", senderUid: actor, text: body, ...(content ? { content } : {}),
            ...(quoted ? { replyTo: quoted } : {}) }, now);
          for (const uid of members) if (uid !== actor && !(await this.blocked(tx, actor, uid))) {
            notify({ to: uid, kind: "group_message", data: { name: String(me.displayName), title: messagePreview(body, content), group: String(group.name), groupId, uid: actor } });
          }
          return { groupId, seq: Number(after.next) - 1, createdAt: now };
        }
        case "rename": {
          const after = await this.groupEvent(tx, { ...group, name }, actor, "renamed", name, now);
          return { groupId, group: after };
        }
        case "add": {
          check(uids.every((uid) => !members.includes(uid)), "already-exists", "Already in the group.");
          check(members.length + uids.length <= GROUP_MAX_MEMBERS, "resource-exhausted", `Groups hold up to ${GROUP_MAX_MEMBERS} people.`);
          const profiles = await this.addable(tx, actor, uids, members);
          let current: Data = group;
          for (const [i, uid] of uids.entries()) {
            members = [...members, uid];
            names = { ...names, [uid]: card(profiles[i]!, seq) };
            current = await this.groupEvent(tx, { ...current, members, names }, actor, "added", String(profiles[i]!.displayName), now);
            notify({ to: uid, kind: "group_added", data: { name: String(me.displayName), title: String(group.name), groupId, uid: actor } });
          }
          return { groupId, group: current };
        }
        case "remove":
        case "leave": {
          const gone = action === "leave" ? actor : uids[0]!;
          if (action === "remove") {
            check(group.ownerUid === actor, "permission-denied", "Only the group owner can remove people.");
            check(members.includes(gone), "not-found", "Not in the group.");
          }
          const goneName = String(names[gone]?.name ?? "");
          members = members.filter((uid) => uid !== gone);
          const { [gone]: _removed, ...rest } = names;
          tx.delete(indexOf(gone));
          tx.delete(`groupRates/${groupId}/users/${gone}`);
          if (!members.length) { tx.delete(path); return { groupId, deleted: true }; }
          let current = await this.groupEvent(tx, { ...group, members, names: rest }, actor, action === "leave" ? "left" : "removed", goneName, now);
          if (group.ownerUid === gone) {
            // Ownership passes to the member who has been in the group longest (members keep join order).
            current = await this.groupEvent(tx, { ...current, ownerUid: members[0] }, members[0]!, "owner", String(rest[members[0]!]?.name ?? ""), now);
          }
          return { groupId, group: current };
        }
      }
      fail("invalid-argument", "Unknown group action.");
    });
  }
  /**
   * People I may add to a group: my friends, in order, none of whom has a block with anyone already in the group
   * (or with each other), so a group never puts two people who blocked each other together.
   */
  private async addable(tx: Transaction, actor: string, uids: string[], members: string[] = [actor]): Promise<Data[]> {
    const profiles: Data[] = [];
    const together = [...members];
    for (const uid of uids) {
      await this.friends(tx, actor, uid);
      for (const other of together) if (other !== actor) await this.unblocked(tx, uid, other);
      const profile = await tx.get(`profiles/${uid}`);
      check(profile, "not-found", "Profile unavailable.");
      profiles.push(profile);
      together.push(uid);
    }
    return profiles;
  }
  /** Whether either person blocked the other (pushes between them are skipped). */
  private async blocked(tx: Transaction, a: string, b: string): Promise<boolean> {
    const [forward, reverse] = await Promise.all([tx.get(`blocks/${a}/users/${b}`), tx.get(`blocks/${b}/users/${a}`)]);
    return Boolean(forward || reverse);
  }
  /** A system line in a group chat (created, added, removed, left, new owner, renamed); the apps write the words. */
  private groupEvent(tx: Transaction, group: Data, who: string, event: string, subject: string, now: number): Promise<Data> {
    return this.groupMessage(tx, group, { kind: "event", senderUid: who, event, text: subject }, now);
  }
  /** Appends a message, saves [group] with the next sequence number and updates every member's chat list. */
  private async groupMessage(tx: Transaction, group: Data, message: Data, now: number): Promise<Data> {
    const groupId = String(group.groupId), seq = Number(group.next), expiresAt = now + CHAT_TTL;
    const names = group.names as Record<string, Data>, members = group.members as string[];
    const senderName = String(names[String(message.senderUid)]?.name ?? "");
    const indexes = await Promise.all(members.map((uid) => tx.get(`groupIndex/${uid}/groups/${groupId}`)));
    tx.set(`groups/${groupId}/messages/${messageId(seq)}`, { schemaVersion: 1, seq, senderName, ...message, createdAt: now, expiresAt });
    const after = { ...group, next: seq + 1, updatedAt: now };
    tx.set(`groups/${groupId}`, after);
    const preview = message.kind === "text" ? messagePreview(String(message.text), (message.content as Data | undefined) ?? null) : "";
    members.forEach((uid, i) => {
      const previous = indexes[i];
      const unread = message.kind === "text" && uid !== message.senderUid ? Number(previous?.unread ?? 0) + 1 : Number(previous?.unread ?? 0);
      // The preview is a message too: the scheduled cleanup blanks it 30 days after it was sent.
      const previewExpiresAt = preview ? expiresAt : Number(previous?.previewExpiresAt ?? 0) || null;
      tx.set(`groupIndex/${uid}/groups/${groupId}`, { schemaVersion: 1, groupId, name: group.name, members: members.length,
        lastText: preview || String(previous?.lastText ?? ""), lastSenderName: preview ? senderName : String(previous?.lastSenderName ?? ""),
        previewExpiresAt, unread, updatedAt: now });
    });
    return after;
  }
  /**
   * A message one may reply or react to: it exists, hasn't expired, is a regular message (no group event, no
   * private system line) and, in a group, is one I can read (seq from when I joined, [since]).
   */
  private async target(tx: Transaction, path: string, now: number, since = 0): Promise<Data> {
    const message = await tx.get(path);
    check(message && Number(message.expiresAt) > now && !message.system && message.kind !== "event" && Number(message.seq) >= since,
      "invalid-argument", "That message isn't available.");
    return message;
  }
  /**
   * One reaction per person per message (`reactions: {uid: emoji}` on the message): a new emoji replaces mine, the
   * same one again removes it. Returns my reaction afterwards (null when removed). Unread counts, the chat lists and
   * pushes are left alone.
   */
  private async react(tx: Transaction, path: string, actor: string, emoji: string, now: number, since = 0): Promise<string | null> {
    const { reactions: previous, ...message } = await this.target(tx, path, now, since);
    const reactions: Record<string, string> = { ...((previous as Record<string, string> | undefined) ?? {}) };
    const mine = reactions[actor] === emoji ? null : emoji;
    if (mine) reactions[actor] = mine;
    else delete reactions[actor];
    tx.set(path, Object.keys(reactions).length ? { ...message, reactions } : message);
    return mine;
  }
  /** Burst limit for reactions, per person across all chats (`reactionLimits/{uid}`, server-only). */
  private async reactionRate(tx: Transaction, actor: string, now: number): Promise<void> {
    const path = `reactionLimits/${actor}`, rate = await tx.get(path);
    const within = rate && now - Number(rate.windowStartedAt) < 60_000;
    const count = within ? Number(rate.count) + 1 : 1;
    check(count <= REACTIONS_PER_MINUTE, "resource-exhausted", "Too many reactions. Try again in a minute.");
    tx.set(path, { schemaVersion: 1, count, windowStartedAt: within ? rate!.windowStartedAt : now });
  }
  /** Push tokens live in one owner-private document (`devices/{uid}`), newest [MAX_DEVICES] kept. */
  private async registerDevice(actor: string, raw: unknown): Promise<Data> {
    const input = object(raw, ["operationId", "action", "token"]);
    const action = text(input.action, 1, 10), token = text(input.token, 20, 4096, PUSH_TOKEN);
    check(action === "register" || action === "unregister", "invalid-argument", "Unknown device action.");
    return this.mutation(actor, "socialRegisterDevice", input, async (tx, now) => {
      const path = `devices/${actor}`, current = await tx.get(path);
      const tokens: Record<string, Data> = { ...((current?.tokens as Record<string, Data> | undefined) ?? {}) };
      const id = digest(token).slice(0, 32);
      if (action === "register") tokens[id] = { token, updatedAt: now };
      else delete tokens[id];
      const kept = Object.entries(tokens).sort(([, a], [, b]) => Number(b.updatedAt) - Number(a.updatedAt)).slice(0, MAX_DEVICES);
      if (kept.length) tx.set(path, { schemaVersion: 1, tokens: Object.fromEntries(kept), updatedAt: now });
      else tx.delete(path);
      return { devices: kept.length };
    });
  }
  private async unhideTitle(actor: string, raw: unknown): Promise<Data> {
    const input = object(raw, ["operationId", "contentKey"]), contentKey = key(input.contentKey);
    return this.mutation(actor, "socialUnhideTitle", input, async (tx) => {
      // Only clears the marker. Nothing is republished and the generation is unchanged: sharing again stays deliberate.
      const path = `hiddenTitles/${actor}/entries/${contentKey}`;
      const existed = Boolean(await tx.get(path));
      tx.delete(path);
      return { contentKey, restored: existed };
    });
  }
  private roomMessageSlot(seq: number): string { return String(seq % ROOM_MESSAGE_SLOTS).padStart(3, "0"); }
  private clearRoomChat(tx: Transaction, roomId: string, participants: string[]): void {
    // Messages live only as long as the room: blind deletes of every ring slot (missing docs are no-ops).
    for (let slot = 0; slot < ROOM_MESSAGE_SLOTS; slot++) tx.delete(`rooms/${roomId}/messages/${this.roomMessageSlot(slot)}`);
    tx.delete(`roomChat/${roomId}`);
    for (const participant of participants) tx.delete(`roomChatRates/${roomId}/users/${participant}`);
  }
  private async roomMessage(actor: string, raw: unknown): Promise<Data> {
    const input = object(raw, ["operationId", "roomId", "kind", "text", "emoji"]);
    const roomId = key(input.roomId), kind = text(input.kind, 1, 8);
    check(["text", "reaction", "ready", "wait"].includes(kind), "invalid-argument", "Unknown message kind.");
    let body: string | null = null, emoji: string | null = null;
    if (kind === "ready" || kind === "wait") {
      // Ready check: carries nothing; the stored text is what older apps (which don't know the kind) show.
      check(input.text === null && input.emoji === null, "invalid-argument", "Signals carry no text.");
      body = ROOM_SIGNAL_FALLBACK[kind] ?? null;
    } else if (kind === "text") {
      body = text(input.text, 1, 200, PLAIN_TEXT).trim();
      check(body.length > 0 && input.emoji === null, "invalid-argument", "Text messages carry text only.");
    } else {
      check(input.text === null && typeof input.emoji === "string" && ROOM_REACTIONS.includes(input.emoji), "invalid-argument", "Unknown reaction.");
      emoji = input.emoji as string;
    }
    return this.mutation(actor, "socialRoomMessage", input, async (tx, now) => {
      const room = await tx.get(`rooms/${roomId}`);
      check(room, "not-found", "Room unavailable.");
      const member = (room.members as Record<string, Data> | undefined)?.[actor];
      check((room.participants as string[]).includes(actor) && member?.state === "joined", "permission-denied", "Room participant required.");
      if (room.hostUid !== actor) await this.friends(tx, String(room.hostUid), actor);
      check(room.state === "active" && Number(room.leaseExpiresAt) > now, "failed-precondition", "Room is not active.");
      const ratePath = `roomChatRates/${roomId}/users/${actor}`, rate = await tx.get(ratePath);
      check(!rate || now - Number(rate.lastAt) >= ROOM_MESSAGE_GAP, "resource-exhausted", "Slow down.");
      const within = rate && now - Number(rate.windowStartedAt) < 60_000;
      const count = within ? Number(rate.count) + 1 : 1;
      check(count <= ROOM_MESSAGES_PER_MINUTE, "resource-exhausted", "Too many messages. Try again in a minute.");
      tx.set(ratePath, { schemaVersion: 1, count, windowStartedAt: within ? rate!.windowStartedAt : now, lastAt: now });
      const state = await tx.get(`roomChat/${roomId}`);
      const seq = Number(state?.next ?? 0);
      tx.set(`rooms/${roomId}/messages/${this.roomMessageSlot(seq)}`, { schemaVersion: 1, seq, senderUid: actor, kind, text: body, emoji, createdAt: now });
      tx.set(`roomChat/${roomId}`, { schemaVersion: 1, next: seq + 1, updatedAt: now });
      return { seq, createdAt: now };
    });
  }
  private roomIndex(tx: Transaction, roomId: string, room: Data): void {    for (const participant of room.participants as string[]) tx.set(`roomInvites/${participant}/items/${roomId}`, {
      schemaVersion: 1, roomId, state: room.state, updatedAt: room.updatedAt,
    });
  }
  /**
   * Group rooms (schemaVersion 2, up to [ROOM_MAX_MEMBERS]): the host invites accepted friends; everyone follows the
   * host. `participants` lists everyone with access (host first); `members` is the server-written snapshot of names
   * and join state, so members can name each other without reading non-friend profiles. Every member must be the
   * current host's friend: the rules check that edge on each read, and heartbeats prune members who lost it.
   */
  private async roomAction(actor: string, raw: unknown): Promise<Data> {
    // `open` (optional, only with create and open): the host lets friends drop in without an invite.
    const hasOpen = raw !== null && typeof raw === "object" && Object.hasOwn(raw, "open");
    const input = object(raw, ["operationId", "action", "roomId", "otherUid", "sessionId", "content", "position", ...(hasOpen ? ["open"] : [])]);
    const action = text(input.action, 1, 9);
    check(["create", "invite", "join", "heartbeat", "kick", "leave", "close", "open", "position"].includes(action), "invalid-argument", "Unknown room action.");
    const roomId = input.roomId === null ? null : key(input.roomId);
    const other = input.otherUid === null ? null : uid(input.otherUid);
    const session = input.sessionId === null ? null : uuid(input.sessionId);
    const content = input.content === null ? null : validateContent(input.content);
    // A member's own place: only the chapter key and page index (null clears it).
    const page = action === "position" && input.position !== null ? validateMemberPage(input.position) : null;
    const position = action === "position" || input.position === null ? null : validatePosition(input.position);
    const open = hasOpen ? bool(input.open) : false;
    check(!hasOpen || action === "create" || action === "open", "invalid-argument", "Only create and open take the open flag.");
    if (action === "create") check(!roomId && other && session && content, "invalid-argument", "Room invitation requires friend, session and content.");
    else if (action === "invite" || action === "kick") check(roomId && other && content === null && position === null, "invalid-argument", "Choose the room and the friend.");
    else if (action === "open") check(roomId && hasOpen && other === null && content === null && position === null, "invalid-argument", "Choose the room and whether it is open.");
    else check(roomId && other === null && content === null && position === null, "invalid-argument", "Only room/session are accepted for this action.");
    if (action === "heartbeat") check(session, "invalid-argument", "Host session required.");
    const quota = action === "position" ? { key: "roomPosition", limit: ROOM_POSITIONS_PER_DAY } : undefined;
    return this.mutation(actor, "socialRoomAction", input, async (tx, now, notify) => {
      if (action === "create") {
        await this.friends(tx, actor, other!);
        const id = digest([actor, input.operationId]);
        const existing = await tx.get(`rooms/${id}`);
        check(!existing, "already-exists", "Room already exists.");
        await this.roomInviteQuota(tx, actor, now);
        if (open) await this.openConsent(tx, actor);
        const [hostProfile, guestProfile] = await Promise.all([tx.get(`profiles/${actor}`), tx.get(`profiles/${other!}`)]);
        const room: Data = { schemaVersion: 2, hostUid: actor, participants: [actor, other!],
          members: { [actor]: this.member(hostProfile, "joined", now), [other!]: this.member(guestProfile, "invited", now) },
          maxMembers: ROOM_MAX_MEMBERS, content: content!, state: "invited", epoch: 1, sequence: 0, position, writerSessionId: null,
          startChapterLabel: position ? String(position.chapterLabel) : null, open,
          createdAt: now, updatedAt: now, leaseExpiresAt: now + ROOM_LEASE };
        tx.set(`rooms/${id}`, room);
        tx.set(`roomLeases/${id}`, { schemaVersion: 1, hostUid: actor, sessionId: session!, epoch: 1 });
        this.roomIndex(tx, id, room);
        await this.syncOpenRoom(tx, id, room, now);
        tx.delete(`joinRequests/${actor}/items/${other!}`); // answers their "ask to read along"
        notify({ to: other!, kind: "room_invite", data: { name: String(hostProfile?.displayName ?? ""), title: String(content!.title), roomId: id, uid: actor } });
        return { roomId: id, room };
      }
      const path = `rooms/${roomId!}`;
      const [existing, lease] = await Promise.all([tx.get(path), tx.get(`roomLeases/${roomId!}`)]);
      const leaving = action === "close" || action === "leave";
      // A closed room has no lease document; closing or leaving it again is an idempotent no-op.
      check(existing && (lease || (leaving && existing.state === "closed")), "not-found", "Room unavailable.");
      const participants = existing.participants as string[];
      // Drop-in: a friend of the host joins an open room without an invite (checked in full below).
      const dropIn = action === "join" && !participants.includes(actor) && existing.open === true && existing.state !== "closed";
      check(participants.includes(actor) || dropIn, "permission-denied", "Room participant required.");
      const host = String(existing.hostUid), isHost = host === actor;
      // Every operation returning protected content must retain the live ACL: members need the host's friendship.
      if (!isHost) await this.friends(tx, host, actor);
      if (existing.state === "closed") return { roomId, room: existing };
      const live = Number(existing.leaseExpiresAt) > now;
      // Leaving is revocation, so it must still work after lease expiry.
      check(leaving || live, "failed-precondition", "Room lease expired or closed.");
      const members: Record<string, Data> = { ...(existing.members as Record<string, Data>) };
      const event = (kind: string, who: string) => this.roomEvent(tx, roomId!, kind, who, now);
      if (action === "close" && isHost || (action === "leave" && isHost && !live)) {
        return { roomId, room: await this.closeRoom(tx, roomId!, existing, now) };
      }
      if (leaving && isHost) {
        // The longest-present member takes over; members who aren't the new host's friends have to go.
        const successor = Object.entries(members)
          .filter(([uid, m]) => uid !== actor && m.state === "joined")
          .sort(([, a], [, b]) => Number(a.joinedAt) - Number(b.joinedAt))[0]?.[0];
        if (!successor) return { roomId, room: await this.closeRoom(tx, roomId!, existing, now) };
        const remaining = participants.filter((uid) => uid !== actor);
        const keep = [successor];
        for (const uid of remaining) if (uid !== successor && await this.isFriend(tx, successor, uid)) keep.push(uid);
        const removed = participants.filter((uid) => !keep.includes(uid));
        // A two-person session ends here even though the room stays open for the successor: recap it now.
        if (keep.length === 1) await this.roomRecap(tx, existing, now);
        const epoch = Number(existing.epoch) + 1;
        // The new host didn't choose to let friends drop in: an open room closes to drop-ins.
        const room = { ...existing, hostUid: successor, participants: keep, members: Object.fromEntries(keep.map((uid) => [uid, members[uid]])),
          epoch, writerSessionId: null, updatedAt: now, leaseExpiresAt: now + ROOM_LEASE, ...(existing.open === undefined ? {} : { open: false }) };
        tx.set(path, room);
        await this.dropOpenRoom(tx, actor, roomId!);
        // The new host's device claims the lease (its session) with its next heartbeat.
        tx.set(`roomLeases/${roomId!}`, { schemaVersion: 1, hostUid: successor, sessionId: null, epoch });
        this.roomIndex(tx, roomId!, room);
        for (const uid of removed) this.dropFromIndex(tx, roomId!, uid, now);
        await event("host", successor);
        return { roomId, room };
      }
      if (leaving || action === "kick") {
        const target = action === "kick" ? other! : actor;
        if (action === "kick") check(isHost && target !== host && participants.includes(target), "permission-denied", "Only the host can remove a member.");
        const keep = participants.filter((uid) => uid !== target);
        const { [target]: _gone, ...rest } = members;
        // Nobody left to read with: the room ends like a two-person room did.
        if (keep.length === 1) return { roomId, room: await this.closeRoom(tx, roomId!, existing, now) };
        const room = { ...existing, participants: keep, members: rest, updatedAt: now };
        tx.set(path, room);
        this.roomIndex(tx, roomId!, room);
        await this.syncOpenRoom(tx, roomId!, room, now);
        this.dropFromIndex(tx, roomId!, target, now);
        await event(action === "kick" ? "removed" : "left", target);
        return { roomId, room };
      }
      check(lease, "not-found", "Room unavailable.");
      if (action === "invite") {
        check(isHost, "permission-denied", "Only the host can invite.");
        check(!participants.includes(other!), "already-exists", "Already in the room.");
        check(participants.length < Number(existing.maxMembers ?? ROOM_MAX_MEMBERS), "resource-exhausted", "The room is full.");
        await this.friends(tx, actor, other!);
        await this.roomInviteQuota(tx, actor, now);
        const [hostProfile, guestProfile] = await Promise.all([tx.get(`profiles/${actor}`), tx.get(`profiles/${other!}`)]);
        const room = { ...existing, participants: [...participants, other!], members: { ...members, [other!]: this.member(guestProfile, "invited", now) }, updatedAt: now };
        tx.set(path, room);
        this.roomIndex(tx, roomId!, room);
        await this.syncOpenRoom(tx, roomId!, room, now);
        tx.delete(`joinRequests/${actor}/items/${other!}`);
        notify({ to: other!, kind: "room_invite", data: { name: String(hostProfile?.displayName ?? ""), title: String((existing.content as Data).title), roomId: roomId!, uid: actor } });
        return { roomId, room };
      }
      if (action === "join") {
        check(!isHost, "permission-denied", "The host is already in the room.");
        check(existing.state === "invited" || existing.state === "active", "failed-precondition", "Invitation unavailable.");
        if (dropIn) {
          // One answer for every refusal except a full room, so a drop-in can't probe who blocked them.
          const listed = await tx.get(`openRooms/${host}`), settings = await tx.get(`sharingSettings/${host}`);
          check(listed?.roomId === roomId && settings && listed.generation === settings.generation &&
            settings.activity === "friends" && settings.invisible !== true, "permission-denied", "Room unavailable.");
          for (const uid of participants) check(!(await this.blocked(tx, uid, actor)), "permission-denied", "Room unavailable.");
          check(participants.length < Number(existing.maxMembers ?? ROOM_MAX_MEMBERS), "resource-exhausted", "The room is full.");
          const profile = await tx.get(`profiles/${actor}`);
          const room = { ...existing, state: "active", participants: [...participants, actor],
            members: { ...members, [actor]: this.member(profile, "joined", now) }, updatedAt: now };
          tx.set(path, room);
          this.roomIndex(tx, roomId!, room);
          await this.syncOpenRoom(tx, roomId!, room, now);
          await event("joined", actor);
          return { roomId, room };
        }
        const already = members[actor]?.state === "joined";
        const room = { ...existing, state: "active", members: { ...members, [actor]: { ...members[actor], state: "joined", joinedAt: already ? members[actor]!.joinedAt : now } }, updatedAt: now };
        tx.set(path, room);
        this.roomIndex(tx, roomId!, room);
        await this.syncOpenRoom(tx, roomId!, room, now);
        if (!already) await event("joined", actor);
        return { roomId, room };
      }
      if (action === "open") {
        check(isHost, "permission-denied", "Only the host can open the room.");
        if (open) await this.openConsent(tx, actor);
        const room = { ...existing, open, updatedAt: now };
        tx.set(path, room);
        this.roomIndex(tx, roomId!, room);
        await this.syncOpenRoom(tx, roomId!, room, now);
        return { roomId, room };
      }
      if (action === "position") {
        // Written into the server-written member snapshot; room members (all the host's friends) see each other's page.
        const self = members[actor];
        check(self?.state === "joined", "permission-denied", "Join the room first.");
        const previous = self.page as Data | null | undefined;
        if (page) {
          check(existing.state === "active", "failed-precondition", "Room is not active.");
          check(!previous || now - Number(previous.at) >= ROOM_POSITION_GAP, "resource-exhausted", "Slow down.");
        }
        // Positions leave updatedAt (and the invite index) alone: they change often and mean nothing to the list.
        tx.set(path, { ...existing, members: { ...members, [actor]: { ...self, page: page ? { ...page, at: now } : null } } });
        return { roomId, room: { hostUid: host, state: existing.state } };
      }
      // Heartbeat: only the host's device holding the lease, or the new host claiming an unclaimed lease after a hand-off.
      const claim = lease.sessionId === null && lease.hostUid === actor;
      check(isHost && lease.hostUid === actor && (lease.sessionId === session || claim) && lease.epoch === existing.epoch,
        "permission-denied", "This device does not hold the host lease.");
      if (claim) tx.set(`roomLeases/${roomId!}`, { ...lease, sessionId: session });
      // Members who are no longer the host's friends are removed (their reads already fail in the rules).
      const keep = [actor];
      for (const uid of participants) if (uid !== actor && await this.isFriend(tx, actor, uid)) keep.push(uid);
      if (keep.length === 1) return { roomId, room: await this.closeRoom(tx, roomId!, existing, now) };
      const room = { ...existing, participants: keep, members: Object.fromEntries(keep.map((uid) => [uid, members[uid]])), updatedAt: now, leaseExpiresAt: now + ROOM_LEASE };
      tx.set(path, room);
      this.roomIndex(tx, roomId!, room);
      await this.syncOpenRoom(tx, roomId!, room, now);
      for (const uid of participants.filter((uid) => !keep.includes(uid))) this.dropFromIndex(tx, roomId!, uid, now);
      return { roomId, room };
    }, quota);
  }
  /** Letting friends drop in needs the host's activity shared with friends and invisible mode off. */
  private async openConsent(tx: Transaction, host: string): Promise<void> {
    const settings = await tx.get(`sharingSettings/${host}`);
    check(settings && settings.activity === "friends" && settings.invisible !== true, "failed-precondition",
      "Share your activity with friends to let them drop in.");
  }
  /**
   * `openRooms/{hostUid}`: the friend-readable card of the host's open room (title, host, member count, lease), with
   * the host's sharing generation so a privacy change hides it at once. Deleted when the room closes or stops being open.
   */
  private async syncOpenRoom(tx: Transaction, roomId: string, room: Data, now: number): Promise<void> {
    const host = String(room.hostUid);
    if (room.open !== true || room.state === "closed") return this.dropOpenRoom(tx, host, roomId);
    const settings = await tx.get(`sharingSettings/${host}`);
    const members = (room.members ?? {}) as Record<string, Data>;
    tx.set(`openRooms/${host}`, { schemaVersion: 1, roomId, hostUid: host, title: String((room.content as Data).title),
      memberCount: Object.values(members).filter((member) => member?.state === "joined").length,
      maxMembers: Number(room.maxMembers ?? ROOM_MAX_MEMBERS), generation: Number(settings?.generation ?? 0),
      updatedAt: now, leaseExpiresAt: room.leaseExpiresAt });
  }
  private async dropOpenRoom(tx: Transaction, host: string, roomId: string): Promise<void> {
    const listed = await tx.get(`openRooms/${host}`);
    if (listed?.roomId === roomId) tx.delete(`openRooms/${host}`);
  }
  private member(profile: Data | null, state: string, now: number): Data {
    return { state, name: String(profile?.displayName ?? ""), accent: Number(profile?.accent ?? 0), joinedAt: state === "joined" ? now : null, invitedAt: now };
  }
  /** A one-way accepted edge is enough here: blocking and unfriending always remove both edges. */
  private async isFriend(tx: Transaction, a: string, b: string): Promise<boolean> {
    return Boolean(await tx.get(`friendships/${a}/accepted/${b}`));
  }
  private async roomInviteQuota(tx: Transaction, actor: string, now: number): Promise<void> {
    const ratePath = `roomLimits/${actor}`, rate = await tx.get(ratePath);
    const within = rate && now - Number(rate.windowStartedAt) < 3_600_000;
    const count = within ? Number(rate.count) + 1 : 1;
    check(count <= 20, "resource-exhausted", "Room invitation limit reached. Try again later.");
    tx.set(ratePath, { schemaVersion: 1, count, windowStartedAt: within ? rate!.windowStartedAt : now });
  }
  private async closeRoom(tx: Transaction, roomId: string, existing: Data, now: number): Promise<Data> {
    const room = { ...existing, state: "closed", epoch: Number(existing.epoch) + 1, updatedAt: now, leaseExpiresAt: now,
      ...(existing.open === undefined ? {} : { open: false }) };
    tx.set(`rooms/${roomId}`, room); tx.delete(`roomLeases/${roomId}`); this.roomIndex(tx, roomId, room);
    await this.dropOpenRoom(tx, String(existing.hostUid), roomId);
    this.clearRoomChat(tx, roomId, existing.participants as string[]);
    await this.roomRecap(tx, existing, now);
    return room;
  }
  /**
   * Recap line in the private chat when a two-person reading session ends: exactly two people had joined (host + one
   * guest), it lasted at least [RECAP_MIN_DURATION] and they are still friends. No push, no unread. Groups of 3+: none (v1).
   */
  private async roomRecap(tx: Transaction, room: Data, now: number): Promise<void> {
    const members = (room.members ?? {}) as Record<string, Data | undefined>;
    const joined = Object.entries(members).filter(([, member]) => member && typeof member.joinedAt === "number");
    const host = String(room.hostUid);
    if (joined.length !== 2 || !joined.some(([uid]) => uid === host)) return;
    const guest = joined.find(([uid]) => uid !== host)![0];
    const start = Math.max(...joined.map(([, member]) => Number(member!.joinedAt)));
    const end = Math.min(now, Number(room.leaseExpiresAt ?? now));
    if (end - start < RECAP_MIN_DURATION) return;
    if (!(await this.isFriend(tx, host, guest)) || !(await this.isFriend(tx, guest, host)) || await this.blocked(tx, host, guest)) return;
    const title = String((room.content as Data).title);
    const position = room.position as Data | null | undefined;
    const recap = { title, fromChapter: (room.startChapterLabel as string | null | undefined) ?? null,
      toChapter: position ? String(position.chapterLabel) : null, minutes: Math.floor((end - start) / 60_000) };
    await this.systemChatMessage(tx, host, guest, host, { system: "recap", recap }, `📖 Read together: ${title}`, null, now);
  }
  /**
   * A server-written line (`system`: "match" | "recap") in two friends' private chat (`text` is ""), with both chat
   * list entries updated. [unreadFor] gets one more unread; null leaves both counts as they are.
   */
  private async systemChatMessage(tx: Transaction, a: string, b: string, senderUid: string, fields: Data, preview: string,
    unreadFor: string | null, now: number): Promise<number> {
    const chatId = pairId(a, b), chatPath = `chats/${chatId}`;
    const [chat, ofA, ofB] = await Promise.all([tx.get(chatPath), tx.get(`chatIndex/${a}/chats/${b}`), tx.get(`chatIndex/${b}/chats/${a}`)]);
    const seq = Number(chat?.next ?? 0), expiresAt = now + CHAT_TTL;
    tx.set(`${chatPath}/messages/${String(seq).padStart(10, "0")}`, { schemaVersion: 1, seq, senderUid, text: "", ...fields, createdAt: now, expiresAt });
    tx.set(chatPath, { schemaVersion: 1, participants: [a, b].sort(), next: seq + 1, updatedAt: now, expiresAt });
    const last = { schemaVersion: 1, chatId, lastText: preview.slice(0, CHAT_PREVIEW), lastSenderUid: senderUid, updatedAt: now, expiresAt };
    tx.set(`chatIndex/${a}/chats/${b}`, { ...last, otherUid: b, unread: Number(ofA?.unread ?? 0) + (unreadFor === a ? 1 : 0) });
    tx.set(`chatIndex/${b}/chats/${a}`, { ...last, otherUid: a, unread: Number(ofB?.unread ?? 0) + (unreadFor === b ? 1 : 0) });
    return seq;
  }
  /** Someone who left or was removed: the room drops off their list (state closed for them). */
  private dropFromIndex(tx: Transaction, roomId: string, uid: string, now: number): void {
    tx.set(`roomInvites/${uid}/items/${roomId}`, { schemaVersion: 1, roomId, state: "closed", updatedAt: now });
    tx.delete(`roomChatRates/${roomId}/users/${uid}`);
  }
  /** A system line in the room chat (joined, left, removed, new host); the app writes the words. */
  private async roomEvent(tx: Transaction, roomId: string, kind: string, who: string, now: number): Promise<void> {
    const state = await tx.get(`roomChat/${roomId}`);
    const seq = Number(state?.next ?? 0);
    tx.set(`rooms/${roomId}/messages/${this.roomMessageSlot(seq)}`, { schemaVersion: 1, seq, senderUid: who, kind, text: null, emoji: null, createdAt: now });
    tx.set(`roomChat/${roomId}`, { schemaVersion: 1, next: seq + 1, updatedAt: now });
  }

  /** Retries reread RTDB inside the destination transaction. Trigger payloads never establish authority. */
  async reconcilePresence(actor: string, readConnections: () => Promise<boolean>): Promise<void> {
    uid(actor);
    await this.store.transact(async (tx) => {
      const [previous, lastSeen] = await Promise.all([tx.get(`presence/${actor}`), tx.get(`lastSeen/${actor}`)]);
      const online = await readConnections(), now = this.clock();
      tx.set(`presence/${actor}`, { schemaVersion: 1, state: online ? "online" : "offline", updatedAt: now });
      if (!online && previous?.state === "online") tx.set(`lastSeen/${actor}`, {
        schemaVersion: 1, lastSeenAt: Math.max(now, Number(lastSeen?.lastSeenAt ?? 0)),
      });
      // Nobody reads while every device is offline; a killed app can't retract its own activity.
      if (!online) tx.delete(`activities/${actor}`);
    });
  }
}
