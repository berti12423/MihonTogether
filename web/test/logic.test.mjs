import { test } from "node:test";
import assert from "node:assert/strict";
import { build } from "esbuild";

// Bundle the TypeScript helpers on the fly so tests run without a separate compile step.
const bundled = await build({ entryPoints: ["src/logic.ts"], bundle: true, format: "esm", write: false, platform: "neutral" });
const logic = await import(`data:text/javascript;base64,${Buffer.from(bundled.outputFiles[0].text).toString("base64")}`);

test("ranking matches the app: chapters, then pages, then name; empty days dropped", () => {
  const ranked = logic.rank([
    { uid: "a", name: "Amy", chapters: 2, pages: 40, me: false },
    { uid: "b", name: "bob", chapters: 3, pages: 10, me: true },
    { uid: "c", name: "Cat", chapters: 2, pages: 55, me: false },
    { uid: "d", name: "Dan", chapters: 0, pages: 0, me: false },
  ]);
  assert.deepEqual(ranked.map((e) => e.uid), ["b", "c", "a"]);
});

test("stats day is the UTC calendar day", () => {
  assert.equal(logic.statsDay(Date.UTC(2026, 9, 2, 23, 59)), "20261002");
  assert.equal(logic.statsDay(Date.UTC(2026, 9, 3, 0, 0)), "20261003");
});

test("invite paths only accept valid handles", () => {
  assert.equal(logic.inviteHandle("/add/alice_smoke"), "alice_smoke");
  assert.equal(logic.inviteHandle("/add/alice_smoke/"), "alice_smoke");
  assert.equal(logic.inviteHandle("/add/Al"), null);
  assert.equal(logic.inviteHandle("/add/<script>"), null);
  assert.equal(logic.inviteHandle("/friend/x"), null);
});

test("sharing payload always sends every category and defaults unknowns to private", () => {
  const sharing = logic.parseSharing({ generation: 3, library: "friends", presence: "everyone", invisible: true });
  assert.equal(sharing.presence, "private");
  assert.deepEqual(logic.sharingPayload(sharing), {
    invisible: true, globalRanking: true, rankingMedia: true, reviews: true, library: "friends", favorites: "private", activity: "private", feed: "private",
    stats: "private", presence: "private", lastSeen: "private",
  });
  // The global ranking starts on; only an explicit false turns it off.
  assert.equal(logic.parseSharing({ globalRanking: false }).globalRanking, false);
  assert.equal(logic.parseSharing({ rankingMedia: false }).rankingMedia, false);
  assert.equal(logic.parseSharing({}).rankingMedia, true);
  // Reviews too: absent means on.
  assert.equal(logic.parseSharing({}).reviews, true);
  assert.equal(logic.parseSharing({ reviews: false }).reviews, false);
  // Progress: absent means on, and it's only sent once the server stored it (or to turn it off).
  assert.equal(logic.parseSharing({}).progress, true);
  assert.equal("progress" in logic.sharingPayload(logic.parseSharing({})), false);
  assert.equal(logic.sharingPayload({ ...logic.parseSharing({}), progress: false }).progress, false);
  assert.equal(logic.sharingPayload(logic.parseSharing({ progress: true })).progress, true);
  assert.equal(logic.parseSharing({ progress: false }).progress, false);
});

test("group quotes from before I joined or by people I blocked are hidden; so are blocked people's reactions", () => {
  const quote = { seq: 5, senderUid: "a", senderName: "A", preview: "hi" };
  assert.equal(logic.visibleQuote(quote, 3, new Set()), quote);
  assert.equal(logic.visibleQuote(quote, 5, new Set()), quote);
  assert.equal(logic.visibleQuote(quote, 6, new Set()), null);
  assert.equal(logic.visibleQuote(quote, 0, new Set(["a"])), null);
  assert.deepEqual(logic.withoutBlocked({ a: "❤️", b: "😂", me: "❤️" }, new Set(["b"])), { a: "❤️", me: "❤️" });
});

test("reading progress: parsed strictly, labelled like the app", () => {
  assert.deepEqual(logic.parseProgress({ read: 40, total: 120, finished: false }), { read: 40, total: 120, finished: false });
  assert.deepEqual(logic.parseProgress({ read: 3, total: 9, finished: true }), { read: 3, total: 9, finished: false });
  assert.equal(logic.parseProgress({ read: 10, total: 9, finished: false }), null);
  assert.equal(logic.parseProgress({ read: "1", total: 9 }), null);
  assert.equal(logic.parseProgress(undefined), null);
  assert.equal(logic.progressLabel({ read: 12, total: 12, finished: true }), "Finished");
  assert.equal(logic.progressLabel({ read: 40, total: 120, finished: false }), "40/120 chapters read");
  assert.equal(logic.progressLabel({ read: 0, total: 120, finished: false }), "Started");
});

test("review stars and ratings", () => {
  assert.equal(logic.stars(4), "★★★★☆");
  assert.equal(logic.stars(9), "★★★★★");
  assert.deepEqual(logic.parseReview({ workKey: "k", rating: 5, text: "Great", content: { title: "Solo Leveling", coverUrl: "https://x.example/c.jpg", canonicalProvider: "anilist", canonicalId: "105398" }, updatedAt: 7 }),
    { workKey: "k", rating: 5, text: "Great", title: "Solo Leveling", coverUrl: "https://x.example/c.jpg", anilistId: 105398, malId: null, updatedAt: 7 });
  assert.equal(logic.parseReview({ workKey: "k", rating: 0, content: { title: "x" } }), null);
  assert.equal(logic.parseReview({ workKey: "k", rating: 3, content: { title: "x", coverUrl: "javascript:alert(1)" }, text: "  " }).coverUrl, null);
});

test("global ranking drops blocked accounts and marks me and friends", () => {
  const rows = logic.globalRank([
    { uid: "x", handle: "x", displayName: "Xena", accent: 1, chapters: 4, streak: 2 },
    { uid: "me", handle: "me", displayName: "Me", accent: 0, chapters: 5, streak: 1 },
    { uid: "bad", handle: "bad", displayName: "Bad", accent: 0, chapters: 9, streak: 1 },
    { uid: "pal", handle: "pal", displayName: "Pal", accent: 0, chapters: 4, streak: 1 },
  ], "me", new Set(["pal"]), new Set(["bad"]));
  assert.deepEqual(rows.map((r) => r.uid), ["me", "pal", "x"]);
  assert.deepEqual(rows.map((r) => [r.me, r.friend]), [[true, false], [false, true], [false, false]]);
});

test("room companions name joined people first", () => {
  const m = (uid, state, name, joinedAt) => ({ uid, state, name, joinedAt });
  const members = [m("me", "joined", "Me", 1), m("b", "joined", "Bob", 2), m("c", "invited", "Cat", null), m("d", "joined", "Dan", 3), m("e", "joined", "Eve", 4)];
  assert.equal(logic.companions(members, "me"), "Bob, Dan +1");
  assert.equal(logic.companions(members.slice(0, 2), "me"), "Bob");
  assert.equal(logic.companions([m("me", "joined", "Me", 1), m("c", "invited", "Cat", null)], "me"), "Cat");
});

test("web reaction list matches the backend", async () => {
  const { readFile } = await import("node:fs/promises");
  const domain = await readFile(new URL("../../backend/src/domain.ts", import.meta.url), "utf8");
  const web = await readFile(new URL("../src/api-social.ts", import.meta.url), "utf8");
  const list = (source) => /ROOM_REACTIONS = \[(.*?)\]/.exec(source)[1].replace(/\s/g, "");
  assert.equal(list(web), list(domain));
});

test("relative time buckets", () => {
  const now = 1_000_000_000;
  assert.equal(logic.relativeTime(now - 5_000, now), "just now");
  assert.equal(logic.relativeTime(now - 5 * 60_000, now), "5 min ago");
  assert.equal(logic.relativeTime(now - 3 * 3_600_000, now), "3 h ago");
  assert.equal(logic.relativeTime(now - 2 * 86_400_000, now), "2 d ago");
});

test("AniList query batches one aliased search per title with variables, never inlined text", async () => {
  const bundledCovers = await build({ entryPoints: ["src/covers.ts"], bundle: true, format: "esm", write: false, platform: "neutral",
    define: { localStorage: "undefined", document: "undefined" } });
  const covers = await import(`data:text/javascript;base64,${Buffer.from(bundledCovers.outputFiles[0].text).toString("base64")}`);
  const { query, variables } = covers.aniListQuery(["Solo Leveling", 'Tricky "title"']);
  assert.deepEqual(variables, { t0: "Solo Leveling", t1: 'Tricky "title"' });
  // Page { media } so an unknown title is an empty list instead of failing the whole batch (AniList answers 404).
  assert.match(query, /^query \(\$t0: String, \$t1: String\) \{ a0: Page\(perPage: 1\) \{ media\(search: \$t0, type: MANGA\)/);
  assert.ok(!query.includes("Tricky"));
  // Tracked titles look up the exact AniList / MyAnimeList entry.
  const exact = covers.aniListQuery([{ anilistId: 30013 }, { malId: 2 }, { search: "x" }]);
  assert.deepEqual(exact.variables, { t0: 30013, t1: 2, t2: "x" });
  assert.match(exact.query, /^query \(\$t0: Int, \$t1: Int, \$t2: String\) \{ a0: Page\(perPage: 1\) \{ media\(id: \$t0, type: MANGA\)/);
  assert.match(exact.query, /a1: Page\(perPage: 1\) \{ media\(idMal: \$t1, type: MANGA\)/);
});

test("levels follow the backend curve and carry over from the global board", () => {
  assert.deepEqual([1, 2, 3, 4, 5].map(logic.xpForLevel), [0, 50, 150, 300, 500]);
  assert.deepEqual([0, 49, 50, 149, 150, 10_000].map(logic.levelFor), [1, 1, 2, 2, 3, 20]);
  assert.equal(logic.levelTitle(1), "Newcomer");
  assert.equal(logic.levelTitle(12), "Bookworm");
  assert.equal(logic.levelTitle(40), "Legend");
  const rows = logic.globalRank([{ uid: "a", handle: "a", displayName: "A", accent: 0, chapters: 2, streak: 1, level: 7 }], "me", new Set(), new Set());
  assert.equal(rows[0].level, 7);
});

test("weeks start on Monday UTC and the badge list matches the backend", () => {
  assert.equal(logic.weekOf(Date.UTC(2026, 9, 5, 12)), "20261005");
  assert.equal(logic.weekOf(Date.UTC(2026, 9, 11, 23)), "20261005");
  assert.equal(logic.weekOf(Date.UTC(2026, 9, 12, 0)), "20261012");
  assert.equal(logic.BADGES.length, 18);
  assert.equal(new Set(logic.BADGES.map((b) => b[0])).size, 18);
});

test("invite links and known routes", () => {
  assert.equal(logic.inviteUrl("https://mihontogether.web.app/", "ana_b"), "https://mihontogether.web.app/add/ana_b");
  assert.equal(logic.inviteCode("ana_b"), "mihontogether-invite:ana_b");
  for (const id of ["recruit_1", "recruit_3", "recruit_10"]) assert.ok(logic.BADGES.some(([badge]) => badge === id), id);
  for (const path of ["/", "/add/ana_b", "/activity", "/stats", "/friend/u1", "/friend/u1/library", "/chat/u1", "/group/g", "/room/r", "/download"]) {
    assert.ok(logic.knownRoute(path), path);
  }
  for (const path of ["/nope", "/add/<x>", "/friend/u1/other", "/stats/extra", "/activity/"]) assert.ok(!logic.knownRoute(path), path);
});

test("tab title counts unread messages, requests and room invites", () => {
  const count = logic.attentionCount({ chatUnread: [2, 0], groupUnread: [3], requests: 1, invites: 1 });
  assert.equal(count, 7);
  assert.equal(logic.attentionCount({ chatUnread: [], groupUnread: [-1], requests: 0, invites: 0 }), 0);
  assert.equal(logic.tabTitle(7), "(7) Mihon Together");
  assert.equal(logic.tabTitle(0), "Mihon Together");
  assert.equal(logic.tabTitle(150), "(99+) Mihon Together");
});

test("notifications only for new incoming things, never for the first snapshot", () => {
  const empty = { chats: null, groups: null, requests: null, invites: null };
  const first = {
    chats: { bo: { unread: 1, name: "Bo", text: "hi", friend: true } },
    groups: { g: { unread: 0, name: "Club", sender: "", text: "" } },
    requests: { r1: { name: "Cat" } },
    invites: {},
  };
  assert.deepEqual(logic.notificationEvents(null, first), []);
  // A section that just loaded counts as its first snapshot.
  assert.deepEqual(logic.notificationEvents(empty, first), []);
  const next = {
    chats: { bo: { unread: 2, name: "Bo", text: "there?", friend: true }, x: { unread: 4, name: "", text: "spam", friend: false } },
    groups: { g: { unread: 1, name: "Club", sender: "Ana", text: "ch 12!" } },
    requests: { r1: { name: "Cat" }, r2: { name: "Dan" } },
    invites: { room1: { host: "Eve", title: "Solo Leveling" } },
  };
  assert.deepEqual(logic.notificationEvents(first, next), [
    { key: "chat:bo", title: "Bo", body: "there?", path: "/chat/bo" },
    { key: "group:g", title: "Club", body: "Ana: ch 12!", path: "/group/g" },
    { key: "request:r2", title: "Friend request", body: "Dan wants to be friends", path: "/" },
    { key: "room:room1", title: "Read together", body: "Eve invited you to read Solo Leveling", path: "/" },
  ]);
  // Reading a chat (unread going down) is not news.
  assert.deepEqual(logic.notificationEvents(next, { ...next, chats: { bo: { unread: 0, name: "Bo", text: "there?", friend: true } } }), []);
});

test("notifications are throttled per conversation", () => {
  const shown = new Map();
  assert.ok(logic.allowNotification(shown, "chat:bo", 1_000));
  assert.ok(!logic.allowNotification(shown, "chat:bo", 20_000));
  assert.ok(logic.allowNotification(shown, "group:g", 20_000));
  assert.ok(logic.allowNotification(shown, "chat:bo", 31_000));
});

test("friends' activity is grouped by title, newest first", () => {
  const item = (kind, title, createdAt) => ({ kind, title, chapterLabel: null, createdAt });
  const { reading, favorited } = logic.groupActivity([
    { name: "Ana", items: [item("chapter_read", "Solo Leveling", 10), item("chapter_read", "Solo Leveling", 30), item("favorite_added", "Berserk", 5)] },
    { name: "Bo", items: [item("chapter_read", "solo leveling ", 40), item("chapter_read", "Vagabond", 20)] },
    { name: "Cat", items: [item("chapter_read", "Solo Leveling", 1), item("favorite_added", "", 50)] },
    { name: "Dan", items: [item("chapter_read", "Solo Leveling", 2)] },
  ]);
  assert.deepEqual(reading.map((g) => [g.title, g.count, g.latest]), [["Solo Leveling", 5, 40], ["Vagabond", 1, 20]]);
  assert.deepEqual(reading[0].names, ["Bo", "Ana", "Dan", "Cat"]);
  assert.equal(logic.namesLabel(reading[0].names), "Bo, Ana +2");
  assert.equal(logic.namesLabel(["Bo", "Ana"]), "Bo and Ana");
  assert.deepEqual(favorited.map((g) => [g.title, g.names]), [["Berserk", ["Ana"]]]);
});

test("last eight UTC days, oldest first, zero when missing", () => {
  const days = logic.lastDays({ "20261003": 4, "20260926": 1, "20261001": 2 }, Date.UTC(2026, 9, 3, 12));
  assert.equal(days.length, 8);
  assert.deepEqual(days.map((d) => d.day), ["20260926", "20260927", "20260928", "20260929", "20260930", "20261001", "20261002", "20261003"]);
  assert.deepEqual(days.map((d) => d.chapters), [1, 0, 0, 0, 0, 2, 0, 4]);
  assert.equal(days[7].label, "Sat");
});

test("getting started counts finished steps", () => {
  const list = logic.gettingStarted({ profile: true, privacy: false, library: true, friend: false });
  assert.equal(list.done, 2);
  assert.equal(list.complete, false);
  assert.deepEqual(list.steps.map((s) => s.id), ["profile", "privacy", "library", "friend"]);
  assert.ok(logic.gettingStarted({ profile: true, privacy: true, library: true, friend: true }).complete);
});

test("reaction emojis match the backend: one emoji, at most 16 bytes, no text", () => {
  for (const good of ["❤️", "😂", "👍🏽", "🏳️‍🌈", "❤️‍🔥", "1️⃣", "🇩🇪"]) assert.ok(logic.isReactionEmoji(good), good);
  for (const bad of ["", "a", "1", "ok", " ", "😂😂", "👨‍👩‍👧", "🏽", "❤ ", "‍"]) assert.equal(logic.isReactionEmoji(bad), false, JSON.stringify(bad));
  assert.deepEqual(logic.QUICK_REACTIONS, ["❤️", "😂", "😮", "😢", "😡", "👍"]);
});

test("reaction chips count each emoji and mark mine, most used first, ties in a fixed order", () => {
  const chips = logic.reactionChips({ a: "😂", me: "❤️", b: "❤️", c: "👍" }, "me");
  assert.deepEqual(chips, [
    { emoji: "❤️", count: 2, mine: true },
    { emoji: "👍", count: 1, mine: false },
    { emoji: "😂", count: 1, mine: false },
  ]);
  assert.deepEqual(logic.reactionChips({}, "me"), []);
});

test("open rooms: live, not full and not mine, fullest first; cards parse to the minimal fields", () => {
  const now = 1_000;
  const card = (roomId, memberCount, extra = {}) => ({ roomId, hostUid: `h-${roomId}`, title: `T ${roomId}`, memberCount, maxMembers: 6, leaseExpiresAt: now + 1, ...extra });
  const rooms = logic.joinableRooms([card("a", 2), card("b", 4), card("c", 3, { leaseExpiresAt: now }), card("d", 6), card("e", 5), null, undefined, card("a", 2)], new Set(["e"]), now);
  assert.deepEqual(rooms.map((r) => r.roomId), ["b", "a"]);
  assert.deepEqual(logic.parseOpenRoom("host", { roomId: "r", title: "X", memberCount: 2, maxMembers: 6, leaseExpiresAt: 5, generation: 3 }),
    { roomId: "r", hostUid: "host", title: "X", memberCount: 2, maxMembers: 6, leaseExpiresAt: 5 });
  assert.equal(logic.parseOpenRoom("host", { title: "no id" }), null);
});

test("XP rules and monthly challenges mirror the backend (same list as its XP rules table test)", async () => {
  assert.deepEqual({ ...logic.XP_RULES }, { chapter: 10, together: 5, streakDay: 5, streakMaxDays: 7, dailyCap: 1000, maxFreezes: 2, freezeEveryDays: 7, challengeXp: 100 });
  assert.deepEqual([1, 2, 3, 4, 5, 6, 10].map(logic.xpForLevel), [0, 50, 150, 300, 500, 750, 2250]);
  assert.deepEqual(logic.CHALLENGES.map((c) => [c.id, c.field, c.min]), [
    ["chapters_100", "chapters", 100], ["chapters_200", "chapters", 200], ["days_15", "days", 15], ["days_20", "days", 20],
    ["together_5", "together", 5], ["together_10", "together", 10], ["streak_7", "streak", 7], ["streak_14", "streak", 14],
    ["binge_15", "bestDay", 15], ["xp_2000", "xp", 2000]]);
  assert.deepEqual(logic.CHALLENGE_ROTATION, [
    ["chapters_100", "days_15", "streak_7"], ["chapters_200", "together_5", "binge_15"], ["days_20", "together_10", "xp_2000"],
    ["chapters_100", "streak_14", "together_5"], ["days_15", "binge_15", "xp_2000"], ["chapters_200", "days_20", "together_10"],
    ["chapters_100", "together_5", "streak_14"], ["days_20", "streak_7", "xp_2000"], ["chapters_200", "binge_15", "together_10"],
    ["chapters_100", "days_20", "together_10"], ["days_15", "streak_14", "xp_2000"], ["chapters_200", "together_5", "streak_7"]]);
  // And straight from domain.ts.
  const { readFile } = await import("node:fs/promises");
  const domain = await readFile(new URL("../../backend/src/domain.ts", import.meta.url), "utf8");
  const xp = domain.split("export const XP = {")[1].split("}")[0];
  for (const key of ["chapter", "together", "streakDay", "streakMaxDays", "dailyCap"]) {
    assert.equal(Number(new RegExp(`${key}: ([\\d_]+)`).exec(xp)[1].replaceAll("_", "")), logic.XP_RULES[key], key);
  }
  assert.equal(Number(/MAX_FREEZES = (\d+)/.exec(domain)[1]), logic.XP_RULES.maxFreezes);
  assert.equal(Number(/CHALLENGE_XP = (\d+)/.exec(domain)[1]), logic.XP_RULES.challengeXp);
  const pool = domain.split("export const CHALLENGES")[1].split("];")[0];
  assert.deepEqual([...pool.matchAll(/id: "(\w+)", field: "(\w+)", min: (\d+)/g)].map((m) => [m[1], m[2], Number(m[3])]),
    logic.CHALLENGES.map((c) => [c.id, c.field, c.min]));
  const rotation = domain.split("export const CHALLENGE_ROTATION")[1].split("];")[0].split("= [")[1];
  assert.deepEqual([...rotation.matchAll(/"(\w+)"/g)].map((m) => m[1]), logic.CHALLENGE_ROTATION.flat());
});

test("challenge progress: this month's counters and stamps; season badges render generically", () => {
  assert.equal(logic.monthOf(Date.UTC(2026, 9, 31, 23, 59)), "2026-10");
  assert.equal(logic.monthName("2026-10"), "October 2026");
  const xp = { month: { id: "2026-10", chapters: 40, days: 20, together: 3, streak: 0, bestDay: 0, xp: 0 }, challenges: { "2026-10": ["days_20"] } };
  assert.deepEqual(logic.challengeProgress(xp, "2026-10").map((p) => [p.challenge.id, p.value, p.done]),
    [["chapters_100", 40, false], ["days_20", 20, true], ["together_10", 3, false]]);
  assert.ok(logic.challengeProgress(xp, "2026-11").every((p) => p.value === 0 && !p.done));
  assert.ok(logic.challengeProgress(null, "2026-10").every((p) => !p.done));
  assert.deepEqual(logic.seasons({ first_chapter: 1, season_2026_09: 5, season_2026_10: 9, season_2026_13: 3 }),
    [{ month: "2026-10", at: 9 }, { month: "2026-09", at: 5 }]);
});

test("recent activity collapses repeats of a title into one entry, newest first", () => {
  const item = (kind, contentKey, title, chapterLabel, createdAt) => ({ kind, contentKey, title, chapterLabel, createdAt });
  const groups = logic.groupRecent([
    item("chapter_read", "a", "Frieren", "Chapter 40", 300),
    item("chapter_read", "b", "Kaguya", "Chapter 2", 250),
    item("chapter_read", "a", "Frieren", "Chapter 41", 400),
    item("favorite_added", "a", "Frieren", null, 100),
    item("chapter_read", "", "Old title", "Chapter 1", 50),
  ]);
  assert.deepEqual(groups.map((g) => g.latest.title), ["Frieren", "Kaguya", "Old title"]);
  assert.equal(groups[0].chapters, 2); assert.equal(groups[0].lastChapter, "Chapter 41"); assert.equal(groups[0].favorited, true);
  assert.equal(groups[1].chapters, 1); assert.equal(groups[1].favorited, false);
});
