// Pure helpers shared with the Android app's behaviour; unit-tested in test/logic.test.mjs.

export interface Sharing {
  generation: number;
  library: string;
  favorites: string;
  activity: string;
  presence: string;
  lastSeen: string;
  feed: string;
  stats: string;
  invisible: boolean;
  /** Global ranking: the one switch that starts on (absent means on). */
  globalRanking: boolean;
  /** While on the global ranking, everyone signed in sees my avatar and banner (absent means on). */
  rankingMedia: boolean;
  /** Friends see my ratings and reviews (absent means on; a friend's copy is false while they're invisible). */
  reviews: boolean;
  /** Friends see the reading progress on my shared library entries (absent means on). */
  progress: boolean;
  /** The server stored [progress]; until then it's sent only when off, so an older backend keeps accepting changes. */
  progressStored: boolean;
}

export const SHARING_FIELDS = ["library", "favorites", "activity", "feed", "stats", "presence", "lastSeen"] as const;

export interface RankingEntry {
  uid: string; name: string; chapters: number; pages: number; me: boolean; streak?: number;
  handle?: string; accent?: number; friend?: boolean; level?: number;
  /** Pictures from a ranking board, when the person shows them there. */
  avatar?: { version: number; contentType: string } | null; banner?: { version: number; contentType: string } | null;
}

/** Same ordering as the app: most chapters, then pages, then name; empty days are dropped. */
export function rank(entries: RankingEntry[]): RankingEntry[] {
  return entries
    .filter((entry) => entry.chapters > 0)
    .sort((a, b) => b.chapters - a.chapters || b.pages - a.pages || a.name.localeCompare(b.name, undefined, { sensitivity: "base" }));
}

/** Global board rows like the app: blocked accounts dropped, me and friends marked, most chapters then name. */
export function globalRank(
  board: Array<{ uid: string; handle: string; displayName: string; accent: number; chapters: number; streak: number; level?: number;
    avatar?: RankingEntry["avatar"]; banner?: RankingEntry["banner"] }>,
  me: string, friends: Set<string>, blocked: Set<string>,
): RankingEntry[] {
  return board.filter((e) => !blocked.has(e.uid) && e.chapters > 0)
    .map((e) => ({ uid: e.uid, name: e.displayName, chapters: e.chapters, pages: 0, me: e.uid === me, streak: e.streak,
      handle: e.handle, accent: e.accent, friend: friends.has(e.uid), level: e.level, avatar: e.avatar ?? null, banner: e.banner ?? null }))
    .sort((a, b) => b.chapters - a.chapters || a.name.localeCompare(b.name, undefined, { sensitivity: "base" }) || a.uid.localeCompare(b.uid));
}

/** "Bob", "Bob and Cat", "Bob, Cat +2": who else is in a room, joined people first. */
/** A friend's open room as their friends see it (`openRooms/{hostUid}`): enough to offer Join, no members or chat. */
export interface OpenRoom { roomId: string; hostUid: string; title: string; memberCount: number; maxMembers: number; leaseExpiresAt: number }
export function parseOpenRoom(hostUid: string, raw: Record<string, unknown>, millis: (value: unknown) => number = (v) => Number(v ?? 0)): OpenRoom | null {
  if (typeof raw.roomId !== "string") return null;
  return { roomId: raw.roomId, hostUid, title: String(raw.title ?? "").slice(0, 200), memberCount: Number(raw.memberCount ?? 0),
    maxMembers: Number(raw.maxMembers ?? 6), leaseExpiresAt: millis(raw.leaseExpiresAt) };
}
/** Open rooms I can drop in to (same order as the app): live, not full, not already mine; fullest first, then by title. */
export function joinableRooms(cards: Array<OpenRoom | null | undefined>, myRooms: Set<string>, now: number = Date.now()): OpenRoom[] {
  const seen = new Set<string>();
  return cards.filter((c): c is OpenRoom => !!c && c.leaseExpiresAt > now && c.memberCount < c.maxMembers && !myRooms.has(c.roomId))
    .filter((c) => !seen.has(c.roomId) && seen.add(c.roomId) !== undefined)
    .sort((a, b) => b.memberCount - a.memberCount || a.title.localeCompare(b.title) || a.roomId.localeCompare(b.roomId));
}
export function companions(members: Array<{ uid: string; state: string; name: string; joinedAt: number | null }>, me: string): string {
  const others = members.filter((m) => m.uid !== me && m.name);
  const joined = others.filter((m) => m.state === "joined").sort((a, b) => (a.joinedAt ?? 0) - (b.joinedAt ?? 0));
  const names = (joined.length ? joined : others).map((m) => m.name);
  if (names.length <= 1) return names[0] ?? "";
  if (names.length === 2) return `${names[0]} and ${names[1]}`;
  return `${names[0]}, ${names[1]} +${names.length - 2}`;
}

/** The backend's level curve: level L needs 25·L·(L−1) XP in total (50, 150, 300, 500, ...). */
export function xpForLevel(level: number): number { return 25 * level * (level - 1); }
export function levelFor(total: number): number {
  let level = 1;
  while (xpForLevel(level + 1) <= total) level++;
  return level;
}
/** Same titles as the app. */
export function levelTitle(level: number): string {
  if (level >= 40) return "Legend";
  if (level >= 25) return "Manga Sage";
  if (level >= 15) return "Binge Reader";
  if (level >= 10) return "Bookworm";
  if (level >= 5) return "Page Turner";
  return "Newcomer";
}

/** Achievements the backend unlocks (backend BADGES), in display order: id, icon, name, how. */
export const BADGES: Array<[string, string, string, string]> = [
  ["first_chapter", "📖", "First Page", "Finish your first chapter."],
  ["chapters_50", "🪝", "Getting Hooked", "Finish 50 chapters."],
  ["chapters_250", "📚", "Shelf Climber", "Finish 250 chapters."],
  ["chapters_1000", "🏛️", "Thousand Chapters", "Finish 1,000 chapters."],
  ["together_1", "🤝", "Better Together", "Finish a chapter in a room with a friend."],
  ["together_25", "🎎", "Reading Club", "Finish 25 chapters together."],
  ["together_100", "💞", "Inseparable", "Finish 100 chapters together."],
  ["streak_3", "🔥", "Warming Up", "Read 3 days in a row."],
  ["streak_7", "⚔️", "Week Warrior", "Read 7 days in a row."],
  ["streak_30", "🗓️", "Monthly Devotion", "Read 30 days in a row."],
  ["streak_100", "💎", "Unbreakable", "Read 100 days in a row."],
  ["binge_20", "🌙", "Binge Night", "Finish 20 chapters in one day (UTC)."],
  ["days_30", "☕", "Regular", "Read on 30 different days."],
  ["level_10", "⭐", "Rising Star", "Reach level 10."],
  ["level_25", "🧙", "Sage in Training", "Reach level 25."],
  ["recruit_1", "📨", "Brought a Friend", "A friend you invited read 5 chapters."],
  ["recruit_3", "🎉", "Brought 3 Friends", "3 friends you invited read 5 chapters each."],
  ["recruit_10", "🏰", "Brought 10 Friends", "10 friends you invited read 5 chapters each."],
];

/**
 * The backend's XP rules (domain.ts `XP`, `MAX_FREEZES`, `CHALLENGE_XP`) for "How XP works". The backend's
 * "XP rules table" test and logic.test.mjs assert the same values, so drift fails a test.
 */
export const XP_RULES = { chapter: 10, together: 5, streakDay: 5, streakMaxDays: 7, dailyCap: 1000, maxFreezes: 2, freezeEveryDays: 7, challengeXp: 100 } as const;

export type ChallengeField = "chapters" | "days" | "together" | "streak" | "bestDay" | "xp";
/** The backend's CHALLENGES pool: id, counter, target, icon, label. */
export const CHALLENGES: Array<{ id: string; field: ChallengeField; min: number; icon: string; label: string }> = [
  { id: "chapters_100", field: "chapters", min: 100, icon: "📚", label: "Read 100 chapters" },
  { id: "chapters_200", field: "chapters", min: 200, icon: "🏛️", label: "Read 200 chapters" },
  { id: "days_15", field: "days", min: 15, icon: "📅", label: "Read on 15 different days" },
  { id: "days_20", field: "days", min: 20, icon: "🗓️", label: "Read on 20 different days" },
  { id: "together_5", field: "together", min: 5, icon: "🤝", label: "Read 5 chapters together" },
  { id: "together_10", field: "together", min: 10, icon: "💞", label: "Read 10 chapters together" },
  { id: "streak_7", field: "streak", min: 7, icon: "🔥", label: "Reach a 7-day streak" },
  { id: "streak_14", field: "streak", min: 14, icon: "⚔️", label: "Reach a 14-day streak" },
  { id: "binge_15", field: "bestDay", min: 15, icon: "🌙", label: "Read 15 chapters in one day" },
  { id: "xp_2000", field: "xp", min: 2000, icon: "⭐", label: "Earn 2000 XP from reading" },
];
/** January..December, like the backend's CHALLENGE_ROTATION. */
export const CHALLENGE_ROTATION: string[][] = [
  ["chapters_100", "days_15", "streak_7"], ["chapters_200", "together_5", "binge_15"],
  ["days_20", "together_10", "xp_2000"], ["chapters_100", "streak_14", "together_5"],
  ["days_15", "binge_15", "xp_2000"], ["chapters_200", "days_20", "together_10"],
  ["chapters_100", "together_5", "streak_14"], ["days_20", "streak_7", "xp_2000"],
  ["chapters_200", "binge_15", "together_10"], ["chapters_100", "days_20", "together_10"],
  ["days_15", "streak_14", "xp_2000"], ["chapters_200", "together_5", "streak_7"],
];
/** The UTC month, "yyyy-mm". */
export function monthOf(now: number = Date.now()): string { return new Date(now).toISOString().slice(0, 7); }
export function challengesFor(month: string) {
  return (CHALLENGE_ROTATION[Number(month.slice(5, 7)) - 1] ?? []).map((id) => CHALLENGES.find((c) => c.id === id)!);
}
export interface XpMonth { id: string; chapters: number; days: number; together: number; streak: number; bestDay: number; xp: number }
/** [month]'s challenges with progress from `xp.month` (another month's counters count as 0) and the stamps. */
export function challengeProgress(xp: { month: XpMonth | null; challenges: Record<string, string[]> } | null, month: string = monthOf()) {
  const counters = xp?.month?.id === month ? xp.month : null;
  const done = xp?.challenges[month] ?? [];
  return challengesFor(month).map((challenge) => {
    const value = counters ? counters[challenge.field] : 0;
    return { challenge, value, done: done.includes(challenge.id) || value >= challenge.min };
  });
}
const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
/** "October 2026" for "yyyy-mm". */
export function monthName(month: string): string { return `${MONTHS[Number(month.slice(5, 7)) - 1] ?? "?"} ${month.slice(0, 4)}`; }
/** Season badges (`season_yyyy_mm`) as "yyyy-mm" with the unlock time, newest first. */
export function seasons(badges: Record<string, number>): Array<{ month: string; at: number }> {
  return Object.entries(badges).flatMap(([id, at]) => {
    const match = /^season_(\d{4})_(0[1-9]|1[0-2])$/.exec(id);
    return match ? [{ month: `${match[1]}-${match[2]}`, at: Number(at) }] : [];
  }).sort((a, b) => b.month.localeCompare(a.month));
}

/** The UTC Monday that starts the week, yyyymmdd (weekly XP resets then), like the backend's weekOf. */
export function weekOf(now: number = Date.now()): string {
  const daysSinceMonday = (new Date(now).getUTCDay() + 6) % 7;
  return statsDay(now - daysSinceMonday * 86_400_000);
}

/** The backend's UTC ranking day, yyyymmdd. */
export function statsDay(now: number = Date.now()): string {
  return new Date(now).toISOString().slice(0, 10).replaceAll("-", "");
}

/** "just now", "5 min ago", "3 h ago", "2 d ago". */
export function relativeTime(then: number, now: number = Date.now()): string {
  const seconds = Math.max(0, Math.round((now - then) / 1000));
  if (seconds < 60) return "just now";
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`;
  if (seconds < 86_400) return `${Math.floor(seconds / 3600)} h ago`;
  return `${Math.floor(seconds / 86_400)} d ago`;
}

/** Handles as the backend accepts them. */
export function validHandle(handle: string): boolean {
  return /^[a-z0-9_]{3,24}$/.test(handle);
}

/** `/add/<handle>` invite paths; anything else is not an invite. */
export function inviteHandle(path: string): string | null {
  const match = /^\/add\/([a-z0-9_]{3,24})\/?$/.exec(path);
  return match ? match[1] : null;
}

/** Settings payload for socialUpdateSharing (defaults absent categories to private, like the app). */
export function sharingPayload(sharing: Sharing): Record<string, unknown> {
  const settings: Record<string, unknown> = { invisible: sharing.invisible, globalRanking: sharing.globalRanking, rankingMedia: sharing.rankingMedia,
    reviews: sharing.reviews, ...(sharing.progressStored || !sharing.progress ? { progress: sharing.progress } : {}) };
  for (const field of SHARING_FIELDS) settings[field] = sharing[field] === "friends" ? "friends" : "private";
  return settings;
}

export function parseSharing(raw: Record<string, unknown>): Sharing {
  const value = (field: string) => (raw[field] === "friends" ? "friends" : "private");
  return {
    generation: Number(raw.generation ?? 0),
    library: value("library"), favorites: value("favorites"), activity: value("activity"), presence: value("presence"),
    lastSeen: value("lastSeen"), feed: value("feed"), stats: value("stats"), invisible: raw.invisible === true,
    globalRanking: raw.globalRanking !== false,
    rankingMedia: raw.rankingMedia !== false,
    reviews: raw.reviews !== false,
    progress: raw.progress !== false,
    progressStored: typeof raw.progress === "boolean",
  };
}

// ---------- friends who read this, ratings and reviews ----------

/** Reading progress on a friend's library entry: chapters read, chapters known, all read. */
export interface Progress { read: number; total: number; finished: boolean }
export function parseProgress(raw: unknown): Progress | null {
  const data = raw as Record<string, unknown> | null | undefined;
  if (!data || !Number.isInteger(data.read) || !Number.isInteger(data.total)) return null;
  const read = data.read as number, total = data.total as number;
  if (read < 0 || read > total) return null;
  return { read, total, finished: data.finished === true && total > 0 && read === total };
}
/** "Finished", "40/120 chapters read" or "Started" (a chapter opened, none read yet). */
export function progressLabel(progress: Progress): string {
  if (progress.finished) return "Finished";
  return progress.read > 0 && progress.total > 0 ? `${progress.read}/${progress.total} chapters read` : "Started";
}

export function stars(rating: number): string {
  const n = Math.max(0, Math.min(5, Math.round(rating)));
  return "★".repeat(n) + "☆".repeat(5 - n);
}
export interface Review { workKey: string; rating: number; text: string | null; title: string; coverUrl: string | null; anilistId: number | null; malId: number | null; updatedAt: number }
/** One review document (reviews/{uid}/items/{workKey}); null when malformed. [millis] converts timestamps. */
export function parseReview(raw: Record<string, unknown>, millis: (value: unknown) => number = (v) => Number(v ?? 0)): Review | null {
  const content = (raw.content ?? null) as Record<string, unknown> | null;
  const rating = raw.rating;
  if (typeof raw.workKey !== "string" || !content || typeof content.title !== "string" || !Number.isInteger(rating) || (rating as number) < 1 || (rating as number) > 5) return null;
  const tracked = (provider: string) => content.canonicalProvider === provider && /^[0-9]{1,12}$/.test(String(content.canonicalId)) ? Number(content.canonicalId) : null;
  const text = typeof raw.text === "string" && raw.text.trim() ? raw.text.slice(0, 1000) : null;
  return { workKey: raw.workKey, rating: rating as number, text, title: content.title,
    coverUrl: typeof content.coverUrl === "string" && /^https:\/\/\S+$/.test(content.coverUrl) ? content.coverUrl : null,
    anilistId: tracked("anilist"), malId: tracked("mal"), updatedAt: millis(raw.updatedAt) };
}

// ---------- invites, tab badge, notifications, discovery ----------

/** The shareable invite link for [handle] on this site. */
export function inviteUrl(origin: string, handle: string): string {
  return `${origin.replace(/\/+$/, "")}/add/${handle}`;
}

/**
 * The friend code copied on the invite page. The app reads it from the clipboard once on first start (no install
 * referrer without the Play Store) or takes it typed in; same format as Android's `FriendInvite.parseCode`.
 */
export function inviteCode(handle: string): string {
  return `mihontogether-invite:${handle}`;
}

/** Paths the site knows; anything else shows "Page not found". */
const ROUTES = [
  /^\/$/, /^\/add\/[a-z0-9_]{3,24}\/?$/, /^\/download$/, /^\/patch-notes$/, /^\/library$/, /^\/messages$/, /^\/activity$/, /^\/stats$/,
  /^\/privacy-settings$/, /^\/account$/, /^\/friend\/[^/]+$/, /^\/friend\/[^/]+\/(library|favorites)$/, /^\/chat\/[^/]+$/,
  /^\/group\/[^/]+$/, /^\/room\/[^/]+$/,
];
export function knownRoute(path: string): boolean {
  return ROUTES.some((route) => route.test(path));
}

/** Everything waiting for me: unread messages (chats and groups), incoming friend requests and room invites. */
export function attentionCount(parts: { chatUnread: number[]; groupUnread: number[]; requests: number; invites: number }): number {
  const sum = (values: number[]) => values.reduce((total, n) => total + Math.max(0, n || 0), 0);
  return sum(parts.chatUnread) + sum(parts.groupUnread) + Math.max(0, parts.requests) + Math.max(0, parts.invites);
}
/** "(3) Mihon Together", or the plain name when nothing is waiting. */
export function tabTitle(count: number, name = "Mihon Together"): string {
  return count > 0 ? `(${count > 99 ? "99+" : count}) ${name}` : name;
}

/**
 * What the notifier remembers between listener updates. A section is null until its listener has answered once,
 * so whatever was already there when the page opened never notifies.
 */
export interface NotifySnapshot {
  chats: Record<string, { unread: number; name: string; text: string; friend: boolean }> | null;
  groups: Record<string, { unread: number; name: string; sender: string; text: string }> | null;
  requests: Record<string, { name: string }> | null;
  invites: Record<string, { host: string; title: string }> | null;
}
export interface NotifyEvent { key: string; title: string; body: string; path: string }

/** New incoming things between two snapshots: more unread in a chat or group, a new request, a new room invite. */
export function notificationEvents(previous: NotifySnapshot | null, next: NotifySnapshot): NotifyEvent[] {
  if (!previous) return [];
  const events: NotifyEvent[] = [];
  if (previous.chats && next.chats) {
    for (const [uid, chat] of Object.entries(next.chats)) {
      if (chat.friend && chat.unread > (previous.chats[uid]?.unread ?? 0)) {
        events.push({ key: `chat:${uid}`, title: chat.name || "New message", body: chat.text, path: `/chat/${uid}` });
      }
    }
  }
  if (previous.groups && next.groups) {
    for (const [id, group] of Object.entries(next.groups)) {
      if (group.unread > (previous.groups[id]?.unread ?? 0)) {
        events.push({ key: `group:${id}`, title: group.name || "Group", body: group.sender ? `${group.sender}: ${group.text}` : group.text, path: `/group/${id}` });
      }
    }
  }
  if (previous.requests && next.requests) {
    for (const [id, request] of Object.entries(next.requests)) {
      if (!(id in previous.requests)) events.push({ key: `request:${id}`, title: "Friend request", body: `${request.name || "Someone"} wants to be friends`, path: "/" });
    }
  }
  if (previous.invites && next.invites) {
    for (const [id, invite] of Object.entries(next.invites)) {
      if (!(id in previous.invites)) events.push({ key: `room:${id}`, title: "Read together", body: `${invite.host || "Someone"} invited you to read ${invite.title}`, path: "/" });
    }
  }
  return events;
}

/** At most one notification per [key] every [gap] ms; records the time when it allows one. */
export function allowNotification(shown: Map<string, number>, key: string, now: number, gap = 30_000): boolean {
  const last = shown.get(key);
  if (last !== undefined && now - last < gap) return false;
  shown.set(key, now);
  return true;
}

/** "Ana", "Ana and Bo", "Ana, Bo +2". */
export function namesLabel(names: string[]): string {
  if (names.length <= 1) return names[0] ?? "";
  if (names.length === 2) return `${names[0]} and ${names[1]}`;
  return `${names[0]}, ${names[1]} +${names.length - 2}`;
}

export interface FeedEntry { kind: string; title: string; chapterLabel: string | null; createdAt: number }
export interface ActivityGroup { title: string; names: string[]; count: number; latest: number }

/**
 * Friends' recent feeds grouped by title (case-insensitive): who, most recent first; how many items; when last.
 * Chapters read and new favorites are separate lists, each newest first.
 */
export function groupActivity(feeds: Array<{ name: string; items: FeedEntry[] }>): { reading: ActivityGroup[]; favorited: ActivityGroup[] } {
  const collect = (kind: string): ActivityGroup[] => {
    const groups = new Map<string, { title: string; byName: Map<string, number>; count: number; latest: number }>();
    for (const feed of feeds) {
      for (const item of feed.items) {
        const title = item.title.trim();
        if (item.kind !== kind || !title) continue;
        const key = title.toLowerCase();
        const group = groups.get(key) ?? { title, byName: new Map<string, number>(), count: 0, latest: 0 };
        group.count++;
        group.latest = Math.max(group.latest, item.createdAt);
        group.byName.set(feed.name, Math.max(group.byName.get(feed.name) ?? 0, item.createdAt));
        groups.set(key, group);
      }
    }
    return [...groups.values()]
      .map((g) => ({
        title: g.title, count: g.count, latest: g.latest,
        names: [...g.byName.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).map(([name]) => name),
      }))
      .sort((a, b) => b.latest - a.latest || a.title.localeCompare(b.title));
  };
  return { reading: collect("chapter_read"), favorited: collect("favorite_added") };
}

/** The last [n] UTC days, oldest first, with the chapters read on each (0 without a record). */
export function lastDays(records: Record<string, number>, now: number = Date.now(), n = 8): Array<{ day: string; label: string; chapters: number }> {
  const names = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
  return Array.from({ length: n }, (_, i) => {
    const at = now - (n - 1 - i) * 86_400_000;
    const day = statsDay(at);
    return { day, label: names[new Date(at).getUTCDay()], chapters: records[day] ?? 0 };
  });
}

/** Chat reactions: the quick ones (like the app), any other emoji via "+". */
export const QUICK_REACTIONS = ["❤️", "😂", "😮", "😢", "😡", "👍"];
const PICTOGRAPH = String.raw`\p{Extended_Pictographic}️?\p{Emoji_Modifier}?️?`;
const EMOJI = new RegExp(String.raw`^(?:${PICTOGRAPH}(?:‍${PICTOGRAPH})*|[#*0-9]️?⃣|\p{Regional_Indicator}{2})$`, "u");
/** One emoji of at most 16 UTF-8 bytes, exactly as the backend accepts it (`isReactionEmoji`). */
export function isReactionEmoji(value: string): boolean {
  return new TextEncoder().encode(value).length <= 16 && EMOJI.test(value);
}

/** Reaction chips under a message: one per emoji with its count, mine marked; most used first, ties in a fixed order (like the app). */
export function reactionChips(reactions: Record<string, string>, me: string): Array<{ emoji: string; count: number; mine: boolean }> {
  const chips = new Map<string, { emoji: string; count: number; mine: boolean }>();
  for (const [uid, emoji] of Object.entries(reactions)) {
    const chip = chips.get(emoji) ?? { emoji, count: 0, mine: false };
    chip.count++;
    chip.mine ||= uid === me;
    chips.set(emoji, chip);
  }
  return [...chips.values()].sort((a, b) => b.count - a.count || (a.emoji < b.emoji ? -1 : a.emoji > b.emoji ? 1 : 0));
}

/**
 * A group quote as I may see it, like the app: null (shown as "Earlier message") when it quotes a message from
 * before I joined ([since]; the server can't hide it per reader) or by someone I blocked.
 */
export function visibleQuote<Q extends { seq: number; senderUid: string }>(quote: Q, since: number, blocked: Set<string>): Q | null {
  return quote.seq >= since && !blocked.has(quote.senderUid) ? quote : null;
}

/** Reactions without the people I blocked (left out of the chips and counts). */
export function withoutBlocked(reactions: Record<string, string>, blocked: Set<string>): Record<string, string> {
  return Object.fromEntries(Object.entries(reactions).filter(([uid]) => !blocked.has(uid)));
}

/** The "Getting started" checklist: each step's state, how many are done, and whether all are. */
export function gettingStarted(flags: { profile: boolean; privacy: boolean; library: boolean; friend: boolean }) {
  const steps = (["profile", "privacy", "library", "friend"] as const).map((id) => ({ id, done: flags[id] }));
  return { steps, done: steps.filter((s) => s.done).length, complete: steps.every((s) => s.done) };
}

/**
 * A friend's recent activity, one entry per title (newest first): the latest event, how many chapters they read
 * recently, and whether they favorited it. Keyed by content key, or the title when an old item has none.
 */
export interface RecentTitle<T> { latest: T; chapters: number; favorited: boolean; lastChapter: string | null }
export function groupRecent<T extends { kind: string; title: string; contentKey: string; chapterLabel: string | null; createdAt: number }>(
  items: T[],
): RecentTitle<T>[] {
  const groups = new Map<string, RecentTitle<T>>();
  for (const item of [...items].sort((a, b) => b.createdAt - a.createdAt)) {
    const key = item.contentKey || `title:${item.title.toLowerCase()}`;
    let group = groups.get(key);
    if (!group) groups.set(key, group = { latest: item, chapters: 0, favorited: false, lastChapter: null });
    if (item.kind === "chapter_read") {
      group.chapters++;
      group.lastChapter ??= item.chapterLabel;
    } else if (item.kind === "favorite_added") {
      group.favorited = true;
    }
  }
  return [...groups.values()];
}
