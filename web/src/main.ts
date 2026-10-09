import { createUserWithEmailAndPassword, EmailAuthProvider, onAuthStateChanged, reauthenticateWithCredential, sendEmailVerification, signInWithEmailAndPassword, signOut, User } from "firebase/auth";
import * as api from "./api";
import * as social from "./api-social";
import { h, replace } from "./dom";
import { coverImage } from "./covers";
import { usingEmulators } from "./firebase";
import {
  ActivityGroup, allowNotification, attentionCount, BADGES, companions, gettingStarted, globalRank, groupActivity, inviteCode, inviteHandle, inviteUrl, knownRoute,
  lastDays, levelFor, levelTitle, namesLabel, notificationEvents, NotifySnapshot, rank, RankingEntry, relativeTime, Sharing, sharingPayload, tabTitle,
  validHandle, weekOf, xpForLevel, QUICK_REACTIONS, isReactionEmoji, reactionChips,
} from "./logic";
import { joinableRooms, progressLabel, Review, stars, visibleQuote, withoutBlocked } from "./logic";
import { challengeProgress, groupRecent, monthName, monthOf, seasons, XP_RULES } from "./logic";
import { initWebLibrary, leaveWebLibrary, myLibraryPage, resetWebLibrary } from "./weblibrary/views";

const ACCENTS = ["#5C6BC0", "#EF5350", "#FFA726", "#66BB6A", "#26C6DA", "#AB47BC", "#EC407A", "#8D6E63"];
const SHARING_LABELS: Record<string, string> = {
  library: "Library", favorites: "Favorite titles", activity: "Current reading activity", feed: "Recent activity history (7 days)",
  stats: "Daily reading stats (friends ranking)", presence: "Online status", lastSeen: "Last seen",
};

interface State {
  user: User | null;
  profile: api.Profile | null;
  sharing: Sharing | null;
  friends: Map<string, api.Profile>;
  status: Map<string, api.Status>;
  incoming: api.Request[];
  outgoing: api.Request[];
  ranking: RankingEntry[];
  board: RankingEntry[];
  weekly: Array<RankingEntry & { weekXp: number }>;
  allTime: Array<RankingEntry & { xp: number }>;
  rankingTab: "friends" | "global" | "week" | "alltime";
  /** A non-friend's public card from the global ranking. */
  card: RankingEntry | null;
  blocks: Set<string>;
  chats: social.ChatSummary[];
  groups: social.GroupSummary[];
  xp: social.Xp | null;
  joinRequests: social.JoinRequest[];
  rooms: social.Room[];
  message: { text: string; error: boolean } | null;
  busy: boolean;
  /** Listeners that have answered at least once this session (the notifier ignores what was there before). */
  loaded: Set<string>;
  /** How many titles my shared library has (null until known): the "app installed" step of Getting started. */
  librarySize: number | null;
  /** On an invite landing page, "Use the website" was chosen. */
  showSignIn: boolean;
}
const state: State = {
  user: null, profile: null, sharing: null, friends: new Map(), status: new Map(), incoming: [], outgoing: [],
  ranking: [], board: [], weekly: [], allTime: [], rankingTab: "friends", card: null, blocks: new Set(), chats: [], groups: [], xp: null, joinRequests: [], rooms: [],
  message: null, busy: false, loaded: new Set(), librarySize: null, showSignIn: false,
};

/** localStorage that never throws (private windows and blocked storage just forget). */
function stored(key: string): string | null {
  try { return localStorage.getItem(key); } catch { return null; }
}
function store(key: string, value: string | null): void {
  try { if (value === null) localStorage.removeItem(key); else localStorage.setItem(key, value); } catch { /* storage blocked */ }
}
const NUDGE_COOL_DOWN_MS = 24 * 60 * 60 * 1000;
/** Remembered per account and friend in this browser; the server enforces the real limit. */
function nudgedRecently(friendUid: string): boolean {
  const at = Number(stored(`nudge:${state.user?.uid ?? ""}:${friendUid}`));
  return Number.isFinite(at) && at > 0 && Date.now() - at < NUDGE_COOL_DOWN_MS;
}
let stops: Array<() => void> = [];
const root = document.getElementById("app")!;

// Inputs survive re-renders (value and identity), and renders wait while the user is typing.
const fields = new Map<string, HTMLInputElement>();
function field(key: string, attrs: Record<string, string>): HTMLInputElement {
  let input = fields.get(key);
  if (!input) {
    input = h("input", attrs) as HTMLInputElement;
    fields.set(key, input);
  }
  return input;
}
let renderPending = false;
root.addEventListener("focusout", () => { if (renderPending) queueMicrotask(render); });
let friendPage: { uid: string; element: HTMLElement } | null = null;

// ---------- routing ----------
function navigate(path: string): void {
  history.pushState({}, "", path);
  // A notice belongs to the page it was shown on.
  state.message = null;
  renderNow();
  window.scrollTo(0, 0);
}
window.addEventListener("popstate", render);
function link(path: string, label: string): HTMLElement {
  return h("a", { href: path, onclick: (event) => { event.preventDefault(); navigate(path); } }, label);
}
initWebLibrary({ navigate, link });

// ---------- actions ----------
async function run(action: () => Promise<unknown>, success?: string): Promise<void> {
  state.busy = true; state.message = null; render();
  try {
    await action();
    if (success) state.message = { text: success, error: false };
  } catch (error) {
    state.message = { text: describe(error), error: true };
  } finally {
    state.busy = false; render();
  }
}
function describe(error: unknown): string {
  const code = (error as { code?: string })?.code ?? "";
  if (code.includes("wrong-password") || code.includes("invalid-credential")) return "Wrong email or password.";
  if (code.includes("resource-exhausted")) return "Slow down a little and try again later.";
  if (code.includes("already-exists")) return "That handle is taken.";
  if (code.includes("not-found")) return "Not found.";
  return (error as Error)?.message || "Something went wrong. Check your connection and try again.";
}

function clearSubscriptions(): void {
  stops.forEach((stop) => stop());
  stops = [];
}

async function startSession(user: User): Promise<void> {
  clearSubscriptions();
  state.friends = new Map(); state.status = new Map(); state.ranking = [];
  state.loaded = new Set(); state.librarySize = null; notified = null;
  const self = await api.getSelf();
  state.profile = self.profile; state.sharing = self.sharing;
  render();
  checkLibrarySize();
  stops.push(api.watchRequests(user.uid, true, (requests) => { state.incoming = requests; state.loaded.add("requests"); render(); }));
  stops.push(social.watchBlocks(user.uid, (ids) => { state.blocks = new Set(ids); }));
  stops.push(social.watchChats(user.uid, (chats) => { state.chats = chats; state.loaded.add("chats"); chatWatchers.forEach((watch) => watch()); render(); }));
  stops.push(social.watchGroups(user.uid, (groups) => { state.groups = groups; state.loaded.add("groups"); groupWatchers.forEach((watch) => watch()); render(); }));
  stops.push(social.watchXp(user.uid, (xp) => { state.xp = xp; render(); }));
  stops.push(social.watchJoinRequests(user.uid, (requests) => { state.joinRequests = requests; render(); }));
  stops.push(social.watchRooms(user.uid, (rooms) => { state.rooms = rooms; state.loaded.add("rooms"); roomWatchers.forEach((draw) => draw()); render(); }));
  stops.push(api.watchRequests(user.uid, false, (requests) => { state.outgoing = requests; render(); }));
  const statusStops = new Map<string, () => void>();
  stops.push(() => statusStops.forEach((stop) => stop()));
  stops.push(api.watchFriendIds(user.uid, async (ids) => {
    for (const [uid, stop] of statusStops) if (!ids.includes(uid)) { stop(); statusStops.delete(uid); state.friends.delete(uid); state.status.delete(uid); }
    for (const uid of ids) {
      if (!state.friends.has(uid)) {
        const profile = await api.getProfile(uid).catch(() => null);
        if (profile) state.friends.set(uid, profile);
      }
      if (!statusStops.has(uid)) {
        statusStops.set(uid, api.watchStatus(uid, (patch) => {
          state.status.set(uid, { online: null, lastSeen: null, reading: null, ...state.status.get(uid), ...patch });
          render();
        }));
      }
    }
    state.loaded.add("friends");
    render();
    await loadRanking();
  }, () => render()));
  // Every minute while the tab is visible (a hidden tab reads nothing), and right away when it comes back.
  const timer = setInterval(() => { if (!document.hidden) loadRanking(); }, 60_000);
  const onVisible = () => { if (!document.hidden) loadRanking(); };
  document.addEventListener("visibilitychange", onVisible);
  stops.push(() => document.removeEventListener("visibilitychange", onVisible));
  stops.push(() => clearInterval(timer));
}

async function loadRanking(): Promise<void> {
  const uid = api.me();
  if (!uid) return;
  const entries: RankingEntry[] = [];
  // My own counters are always readable to me; friends see them only when stats are shared.
  const mine = await api.loadDailyStats(uid);
  if (mine) entries.push({ uid, name: state.profile?.displayName ?? "", ...mine, me: true });
  const friends = [...state.friends.values()];
  // In parallel: one friend after another made the ranking slow with many friends.
  (await Promise.all(friends.map((friend) => api.loadDailyStats(friend.uid).catch(() => null)))).forEach((stats, i) => {
    const friend = friends[i];
    if (stats) entries.push({ uid: friend.uid, name: friend.displayName, ...stats, me: false, friend: true, handle: friend.handle });
  });
  state.ranking = rank(entries);
  state.board = globalRank(await social.loadLeaderboard().catch(() => []), uid, new Set(state.friends.keys()), state.blocks);
  state.allTime = (await social.loadLeaderboard("alltime").catch(() => []))
    .filter((e) => !state.blocks.has(e.uid) && (e.xp ?? 0) > 0)
    .map((e) => ({ uid: e.uid, name: e.displayName, chapters: e.chapters, pages: 0, me: e.uid === uid, handle: e.handle, accent: e.accent,
      friend: state.friends.has(e.uid), level: e.level, xp: e.xp ?? 0, avatar: e.avatar, banner: e.banner }))
    .sort((a, b) => b.xp - a.xp || b.chapters - a.chapters || a.name.localeCompare(b.name));
  const week = weekOf();
  const weekly = [{ uid, name: state.profile?.displayName ?? "", chapters: state.xp?.week === week ? state.xp.weekChapters : 0, pages: 0, me: true, weekXp: state.xp?.week === week ? state.xp.weekXp : 0 }];
  const xps = await Promise.all(friends.map((friend) => social.loadXp(friend.uid)));
  for (const [i, friend] of friends.entries()) {
    const xp = xps[i];
    if (xp) weekly.push({ uid: friend.uid, name: friend.displayName, chapters: xp.week === week ? xp.weekChapters : 0, pages: 0, me: false, friend: true, level: levelFor(xp.total),
      weekXp: xp.week === week ? xp.weekXp : 0 } as RankingEntry & { weekXp: number });
  }
  state.weekly = weekly.filter((e) => e.weekXp > 0).sort((a, b) => b.weekXp - a.weekXp || a.name.localeCompare(b.name));
  render();
}

/** Whether my shared library has titles: the app is installed and sharing (Getting started). */
function checkLibrarySize(): void {
  const uid = api.me(), sharing = state.sharing;
  if (!uid || !sharing) return;
  if (sharing.library !== "friends") { state.librarySize = 0; render(); return; }
  social.countEntries(uid, "library", sharing.generation).then((count) => { state.librarySize = count ?? 0; render(); });
}

// ---------- tab badge and browser notifications ----------
const NOTIFY_KEY = "mt-notifications";
const notificationsSupported = () => typeof Notification !== "undefined";
const notificationsOn = () => notificationsSupported() && Notification.permission === "granted" && stored(NOTIFY_KEY) === "on";
let notified: NotifySnapshot | null = null;
const notifiedAt = new Map<string, number>();

function roomInvites(): social.Room[] {
  const me = api.me() ?? "";
  return state.rooms.filter((room) => room.members[me]?.state === "invited");
}
function unreadMessages(): number {
  return state.chats.filter((c) => state.friends.has(c.otherUid)).reduce((sum, c) => sum + c.unread, 0) + state.groups.reduce((sum, g) => sum + g.unread, 0);
}

/** What is waiting, as the notifier sees it; sections stay null until their listener has answered. */
function notifySnapshot(): NotifySnapshot {
  const ready = (key: string) => state.loaded.has(key);
  return {
    chats: ready("chats") ? Object.fromEntries(state.chats.filter((c) => !state.blocks.has(c.otherUid)).map((c) => [c.otherUid, {
      unread: c.unread, name: state.friends.get(c.otherUid)?.displayName ?? "", text: c.lastText, friend: state.friends.has(c.otherUid),
    }])) : null,
    groups: ready("groups") ? Object.fromEntries(state.groups.map((g) => [g.groupId, { unread: g.unread, name: g.name, sender: g.lastSenderName, text: g.lastText }])) : null,
    requests: ready("requests") ? Object.fromEntries(state.incoming.map((r) => [r.id, { name: r.name ?? "" }])) : null,
    invites: ready("rooms") ? Object.fromEntries(roomInvites().map((room) => [room.id, { host: room.members[room.hostUid]?.name ?? "", title: room.title }])) : null,
  };
}

/** Runs on every redraw: the tab title count, and notifications for new things while the tab is hidden. */
function updateAttention(): void {
  if (!state.user) {
    notified = null;
    document.title = tabTitle(0);
    return;
  }
  document.title = tabTitle(attentionCount({
    chatUnread: state.chats.filter((c) => state.friends.has(c.otherUid)).map((c) => c.unread), groupUnread: state.groups.map((g) => g.unread),
    requests: state.incoming.length, invites: roomInvites().length,
  }));
  const next = notifySnapshot();
  const events = notificationEvents(notified, next);
  notified = next;
  if (!document.hidden || !notificationsOn()) return;
  const now = Date.now();
  for (const event of events) {
    if (!allowNotification(notifiedAt, event.key, now)) continue;
    try {
      // Plain text only: the Notification API never renders markup.
      const notification = new Notification(event.title, { body: event.body.slice(0, 140), tag: event.key, icon: "/icon.svg" });
      notification.onclick = () => { window.focus(); navigate(event.path); notification.close(); };
    } catch { /* some browsers only allow notifications from a service worker */ }
  }
}

/** Opt-in switch for browser notifications; permission is only ever asked from this button. */
function notificationControls(compact: boolean): HTMLElement | null {
  if (!notificationsSupported()) return compact ? null : h("p", { class: "muted small" }, "This browser doesn't support notifications.");
  if (Notification.permission === "denied") {
    return compact ? null : h("p", { class: "muted small" }, "Notifications are blocked for this site. Allow them in your browser's site settings to turn them on.");
  }
  if (notificationsOn()) {
    return compact ? null : h("div", {}, h("p", { class: "small" }, "Notifications are on while this tab is in the background."),
      h("button", { class: "secondary", onclick: () => { store(NOTIFY_KEY, null); renderNow(); } }, "Turn off notifications"));
  }
  if (compact && stored(`${NOTIFY_KEY}-prompt`) === "dismissed") return null;
  const enable = h("button", { onclick: async () => {
    const permission = await Notification.requestPermission().catch(() => "denied" as NotificationPermission);
    if (permission === "granted") store(NOTIFY_KEY, "on");
    renderNow();
  } }, "Enable notifications");
  return h("div", { class: compact ? "card inset" : "" },
    h("p", { class: "small" }, "Get a browser notification for new messages, friend requests and room invites while this tab is in the background."),
    h("div", { class: "row" }, enable,
      compact ? h("button", { class: "secondary", onclick: () => { store(`${NOTIFY_KEY}-prompt`, "dismissed"); renderNow(); } }, "Not now") : null));
}

onAuthStateChanged(api.authInstance(), async (user) => {
  state.user = user; state.profile = null; state.sharing = null;
  clearSubscriptions();
  // The phone connection belongs to the account that opened it.
  resetWebLibrary();
  render();
  if (user) await startSession(user).catch((error) => { state.message = { text: describe(error), error: true }; render(); });
});

// ---------- views ----------
function avatar(profile: api.Profile, size: number): HTMLElement {
  const box = h("div", { class: "avatar", style: `width:${size}px;height:${size}px;background:${ACCENTS[profile.accent] ?? ACCENTS[0]}` },
    profile.displayName.trim().slice(0, 1).toUpperCase());
  api.mediaUrl(profile, "avatar").then((url) => { if (url) replace(box, h("img", { src: url, alt: "" })); });
  return box;
}
function header(profile: api.Profile): HTMLElement {
  const banner = h("div", { class: "banner", style: `background:${ACCENTS[profile.accent] ?? ACCENTS[0]}` });
  api.mediaUrl(profile, "banner").then((url) => { if (url) replace(banner, h("img", { src: url, alt: "" })); });
  return h("section", { class: "profile-header" }, banner, avatar(profile, 80),
    h("h2", {}, profile.displayName), h("p", { class: "muted" }, `@${profile.handle}`), profile.bio ? h("p", {}, profile.bio) : null);
}
function statusLine(uid: string): string {
  const status = state.status.get(uid);
  if (status?.reading) return `Reading now: ${status.reading}`;
  if (status?.online) return "Online";
  if (status?.lastSeen) return `Last seen ${relativeTime(status.lastSeen)}`;
  return "";
}

function signInView(): HTMLElement {
  const email = field("email", { type: "email", placeholder: "Email", autocomplete: "email" });
  const password = field("password", { type: "password", placeholder: "Password", autocomplete: "current-password" });
  const invite = inviteHandle(location.pathname);
  // Someone arriving from an invite most likely has no account yet.
  const createFirst = !!invite;
  const signIn = h("button", { class: createFirst ? "secondary" : undefined, disabled: state.busy,
    onclick: () => run(() => signInWithEmailAndPassword(api.authInstance(), email.value.trim(), password.value)) }, createFirst ? "I have an account: sign in" : "Sign in");
  const create = h("button", { class: createFirst ? undefined : "secondary", disabled: state.busy, onclick: () => run(async () => {
    const credential = await createUserWithEmailAndPassword(api.authInstance(), email.value.trim(), password.value);
    await sendEmailVerification(credential.user).catch(() => undefined);
  }) }, "Create account");
  return h("section", { class: "card narrow" },
    h("h2", {}, createFirst ? "Create your account" : "Sign in"),
    h("p", { class: "muted" }, "Read with friends. What you read is shared with your friends; you can turn anything off in Privacy."),
    invite ? h("p", {}, `Afterwards you can send a friend request to @${invite}, or `, h("a", { href: `mihontogether://add/${invite}` }, "open the invite in the app"), ".") : null,
    email, password,
    h("div", { class: "row" }, ...(createFirst ? [create, signIn] : [signIn, create])));
}

/**
 * Signed out on /add/<handle>: what this is, and three ways in. Only the handle from the link is shown. The friend
 * code survives the APK install: copied now, the app offers it once on first start (or it can be typed in Friends).
 */
function inviteLandingView(invite: string): HTMLElement {
  const note = h("p", { class: "muted small", "aria-live": "polite" },
    "Copy it before installing: the app offers to add your friend when you first open it. You can also type it in Friends.");
  return h("section", { class: "card narrow highlight" },
    h("h2", {}, `@${invite} invited you to Mihon Together`),
    h("p", {}, "Mihon Together is a manga reader for Android with friends built in."),
    h("p", { class: "muted" }, "Read chapters together, chat, and see what friends are reading. Sharing with friends is on; turn off anything in Privacy."),
    h("h3", {}, "Friend code"),
    h("div", { class: "row" }, h("code", { class: "invite-url" }, invite),
      h("button", { onclick: () => {
        const copied = navigator.clipboard?.writeText(inviteCode(invite)) ?? Promise.reject(new Error("no clipboard"));
        copied.then(() => { note.textContent = "Copied. Now install the app and open it."; },
          () => { note.textContent = `Couldn't copy. In the app, enter the code ${invite} under Friends.`; });
      } }, "Copy")),
    note,
    h("div", { class: "choices" },
      link("/download", "Get the Android app"),
      h("a", { href: `mihontogether://add/${invite}` }, "I already have the app"),
      h("button", { class: "secondary", onclick: () => { state.showSignIn = true; renderNow(); } }, "Use the website")));
}

/** My shareable invite link. */
function inviteCard(): HTMLElement | null {
  const handle = state.profile?.handle;
  if (!handle) return null;
  const url = inviteUrl(location.origin, handle);
  // The home page redraws often, so the result goes to the page notice rather than an element of this card.
  const say = (text: string, error = false) => { state.message = { text, error }; render(); };
  const shareable = typeof navigator.share === "function";
  return h("section", { class: "card" }, h("h3", {}, "Your invite link"),
    h("p", { class: "muted small" }, "Send it to a friend: it opens the app, or this website, ready to add you."),
    h("code", { class: "invite-url" }, url),
    h("div", { class: "row" },
      h("button", { onclick: () => {
        const copied = navigator.clipboard?.writeText(url) ?? Promise.reject(new Error("no clipboard"));
        copied.then(() => say("Invite link copied."), () => say("Couldn't copy. Select the link and copy it.", true));
      } }, "Copy"),
      shareable ? h("button", { class: "secondary", onclick: () => {
        navigator.share({ title: "Mihon Together", text: `Add me on Mihon Together: @${handle}`, url }).catch(() => undefined);
      } }, "Share") : null));
}

const ONBOARDING_KEY = "mt-getting-started-dismissed";
const PRIVACY_SEEN_KEY = "mt-privacy-reviewed";
/** Getting started for new users; hidden once everything is done or it was dismissed. */
function gettingStartedCard(): HTMLElement | null {
  const uid = api.me();
  if (!uid || !state.profile || !state.sharing || stored(`${ONBOARDING_KEY}:${uid}`) === "1") return null;
  const sharing = state.sharing;
  const privacy = stored(`${PRIVACY_SEEN_KEY}:${uid}`) === "1" || ["library", "favorites", "activity", "feed", "stats", "presence", "lastSeen"]
    .some((key) => (sharing as unknown as Record<string, string>)[key] === "friends");
  const profile = !!(state.profile.bio || state.profile.avatar || state.profile.banner);
  const list = gettingStarted({ profile, privacy, library: (state.librarySize ?? 0) > 0, friend: state.friends.size > 0 });
  if (list.complete) return null;
  const labels: Record<string, () => HTMLElement> = {
    profile: () => h("span", {}, "Make your profile yours: avatar, banner or bio (Account → Profile look, or in the app)"),
    privacy: () => h("span", {}, "Choose what friends can see in ", link("/privacy-settings", "Privacy")),
    library: () => h("span", {}, link("/download", "Install the app"), ", turn on Library sharing and share your library from the app"),
    friend: () => h("span", {}, "Add your first friend: send them your invite link"),
  };
  return h("section", { class: "card" },
    h("div", { class: "row between" }, h("h3", {}, `Getting started (${list.done}/${list.steps.length})`),
      h("button", { class: "link", "aria-label": "Dismiss getting started", onclick: () => { store(`${ONBOARDING_KEY}:${uid}`, "1"); renderNow(); } }, "Dismiss")),
    h("ul", { class: "checklist" }, ...list.steps.map((step) => h("li", { class: step.done ? "done" : "" },
      h("span", { class: "check", "aria-hidden": "true" }, step.done ? "✓" : ""),
      h("span", { class: "sr-only" }, step.done ? "Done: " : "To do: "),
      labels[step.id]()))));
}

function notFoundView(): HTMLElement {
  return h("section", { class: "card narrow" }, h("h2", {}, "Page not found"),
    h("p", { class: "muted" }, "There's nothing at this address."), link("/", "Go to the home page"));
}

function profileSetupView(): HTMLElement {
  const handle = field("setupHandle", { placeholder: "handle (3–24 a-z, 0-9, _)", maxlength: "24" });
  const name = field("setupName", { placeholder: "Display name", maxlength: "40" });
  return h("section", { class: "card narrow" }, h("h2", {}, "Create your profile"), handle, name,
    h("button", { disabled: state.busy, onclick: () => {
      const value = handle.value.trim().toLowerCase();
      if (!validHandle(value) || !name.value.trim()) { state.message = { text: "Pick a valid handle and a name.", error: true }; render(); return; }
      run(async () => { state.profile = await api.saveProfile(value, name.value.trim()); });
    } }, "Save profile"));
}

function homeView(): HTMLElement {
  const invite = inviteHandle(location.pathname);
  const handle = field("friendHandle", { placeholder: "Friend's exact handle", maxlength: "24" });
  if (invite && !handle.value) handle.value = invite;
  const friends = [...state.friends.values()].sort((a, b) => {
    const sa = state.status.get(a.uid), sb = state.status.get(b.uid);
    return Number(!!sb?.online) - Number(!!sa?.online) || Number(!!sb?.reading) - Number(!!sa?.reading) ||
      (sb?.lastSeen ?? 0) - (sa?.lastSeen ?? 0) || a.displayName.localeCompare(b.displayName);
  });
  return h("div", {},
    invite ? h("section", { class: "card highlight" }, h("p", {}, `Invite link for @${invite}.`),
      invite === state.profile?.handle
        ? h("p", { class: "muted small" }, "This is your own invite link.")
        : h("button", { disabled: state.busy, onclick: () => run(async () => { await api.requestFriend(invite, true); navigate("/"); }, "Friend request sent.") }, `Send friend request to @${invite}`),
      h("a", { href: `mihontogether://add/${invite}`, class: "small" }, "Open in the app")) : null,
    gettingStartedCard(),
    ...socialSections(),
    inviteCard(),
    xpCard(),
    rankingCard(),
    state.incoming.length ? h("section", { class: "card" }, h("h3", {}, "Incoming requests"), ...state.incoming.map((request) =>
      h("div", { class: "row between" }, h("span", {}, request.name ?? "Someone", request.handle ? h("span", { class: "muted" }, ` @${request.handle}`) : null),
        h("span", {}, h("button", { onclick: () => run(() => api.friendAction("accept", request.senderUid)) }, "Accept"),
          h("button", { class: "secondary", onclick: () => run(() => api.friendAction("reject", request.senderUid)) }, "Reject"))))) : null,
    h("section", { class: "card" }, h("h3", {}, "Friends"),
      friends.length === 0 ? h("p", { class: "muted" }, "Add a friend by their handle to get started.") : null,
      ...friends.map((friend) => h("a", { class: "friend", href: `/friend/${friend.uid}`, onclick: (event) => { event.preventDefault(); navigate(`/friend/${friend.uid}`); } },
        h("div", { class: "avatar-wrap" }, avatar(friend, 44), h("span", { class: state.status.get(friend.uid)?.online ? "dot on" : "dot" })),
        h("div", {}, h("strong", {}, friend.displayName), h("div", { class: "muted small" }, `@${friend.handle}`),
          h("div", { class: "small accent" }, statusLine(friend.uid))))),
      h("div", { class: "row" }, handle, h("button", { disabled: state.busy, onclick: () => {
        const value = handle.value.trim().toLowerCase();
        if (!validHandle(value)) { state.message = { text: "Enter an exact handle.", error: true }; render(); return; }
        run(() => api.requestFriend(value), "Friend request sent.");
      } }, "Send request")),
      ...state.outgoing.map((request) => h("div", { class: "row between small" }, h("span", { class: "muted" }, `Sent to ${request.name ?? "someone"}`),
        h("button", { class: "link", onclick: () => run(() => api.friendAction("cancel", request.recipientUid)) }, "Cancel")))));
}

function friendView(uid: string): HTMLElement {
  // Built once per visit: live status updates re-render the shell, not this page's reads.
  if (friendPage?.uid === uid) return friendPage.element;
  const container = h("div", {}, h("p", { class: "muted" }, "Loading…"));
  friendPage = { uid, element: container };
  api.getFriend(uid).then(async ({ profile, sharing }) => {
    const preview = (kind: "library" | "favorites") => (sharing[kind] === "friends"
      ? Promise.all([social.loadEntriesPage(uid, kind, sharing.generation, null, 12), social.countEntries(uid, kind, sharing.generation)])
        .then(([page, count]) => ({ items: page.items, count })).catch(() => ({ items: [] as social.Entry[], count: null }))
      : Promise.resolve(null));
    const [library, favorites, feed, reviews] = await Promise.all([
      preview("library"), preview("favorites"),
      sharing.feed === "friends" ? api.loadFeed(uid, sharing.generation).catch(() => []) : Promise.resolve(null),
      sharing.reviews ? social.loadReviews(uid).catch(() => [] as Review[]) : Promise.resolve(null),
    ]);
    const list = (title: string, kind: string, data: { items: social.Entry[]; count: number | null } | null) => h("section", { class: "card" },
      h("div", { class: "row between" }, h("h3", {}, data?.count != null ? `${title} (${data.count})` : title),
        data && data.items.length ? link(`/friend/${uid}/${kind}`, "See all") : null),
      data === null ? h("p", { class: "muted" }, "This information is private.") : data.items.length === 0 ? h("p", { class: "muted" }, "Nothing shared yet.") : null,
      h("div", { class: "strip" }, ...(data?.items ?? []).map((item) => entryTile(uid, item, sharing.progress))));
    replace(container,
      link("/", "← Friends"),
      header(profile),
      h("p", { class: "accent" }, statusLine(uid)),
      feed ? recentCard(uid, feed) : null,
      h("div", { class: "row" },
        h("button", { onclick: () => navigate(`/chat/${uid}`) }, "Message"),
        h("button", { class: "secondary", disabled: state.busy || nudgedRecently(uid), onclick: () => run(async () => {
          await social.nudgeFriend(uid);
          store(`nudge:${state.user?.uid ?? ""}:${uid}`, String(Date.now()));
        }, "Nudged 👋") }, nudgedRecently(uid) ? "Nudged" : "Nudge 👋"),
        state.status.get(uid)?.reading
          ? h("button", { class: "secondary", disabled: state.busy, onclick: () => run(() => social.askToReadAlong(uid), "Asked. They'll get a notification.") }, "Ask to read along")
          : null),
      list("Library", "library", library), list("Favorite titles", "favorites", favorites),
      reviewsCard(reviews),
      h("div", { class: "row" },
        h("button", { class: "danger", onclick: () => confirm(`Remove ${profile.displayName} as a friend?`) && run(async () => { await api.friendAction("remove", uid); navigate("/"); }) }, "Remove friend"),
        h("button", { class: "danger secondary", onclick: () => confirm(`Block ${profile.displayName}?`) && run(async () => { await api.friendAction("block", uid); navigate("/"); }) }, "Block")));
  }).catch((error) => replace(container, link("/", "← Friends"), h("p", { class: "error", role: "status" },describe(error))));
  return container;
}

/**
 * A friend's recent activity as a cover strip like the library: one tile per title (repeats collapsed), the latest
 * chapter on the cover, and how many chapters and how long ago underneath.
 */
function recentCard(uid: string, feed: api.FeedItem[]): HTMLElement {
  const groups = groupRecent(feed);
  return h("section", { class: "card" }, h("h3", {}, "Recent"),
    groups.length === 0 ? h("p", { class: "muted" }, "Nothing recent.") : null,
    h("div", { class: "strip" }, ...groups.map((group) => {
      const item = group.latest;
      const badge = group.lastChapter ?? (group.favorited ? "♥ Favorite" : null);
      const detail = [group.chapters > 1 ? `${group.chapters} chapters` : group.chapters === 1 ? "1 chapter" : group.favorited ? "Added to favorites" : "",
        relativeTime(item.createdAt)].filter(Boolean).join(" · ");
      return h("figure", { class: "tile", title: item.title },
        coverImage(item.title, item.coverUrl, social.entryFallbacks(uid, { ...item, progress: null })),
        badge ? h("span", { class: "progress" }, badge) : null,
        group.favorited && group.lastChapter ? h("span", { class: "fav-badge", "aria-label": "Favorite" }, "♥") : null,
        h("figcaption", {}, item.title),
        h("div", { class: "muted small" }, detail));
    })));
}

/** A cover tile; the owner's reading progress (when they share it: [progress]) sits on the cover. */
function entryTile(uid: string, item: social.Entry, progress: boolean): HTMLElement {
  return h("figure", { class: "tile", title: item.title },
    coverImage(item.title, item.coverUrl, social.entryFallbacks(uid, item)),
    progress && item.progress ? h("span", { class: item.progress.finished ? "progress done" : "progress" }, progressLabel(item.progress)) : null,
    h("figcaption", {}, item.title));
}

/** A friend's ratings and reviews (written in the app); null when they keep them private. */
function reviewsCard(reviews: Review[] | null): HTMLElement {
  return h("section", { class: "card" }, h("h3", {}, "Reviews"),
    reviews === null ? h("p", { class: "muted" }, "Their reviews are private.") : reviews.length === 0 ? h("p", { class: "muted" }, "No reviews yet.") : null,
    ...(reviews ?? []).map((review) => h("div", { class: "review" },
      coverImage(review.title, review.coverUrl, { anilistId: review.anilistId, malId: review.malId }),
      h("div", {},
        h("strong", {}, review.title),
        h("div", { class: "stars", role: "img", "aria-label": `${review.rating} of 5 stars` }, stars(review.rating)),
        review.text ? h("p", { class: "review-text" }, review.text) : null,
        h("span", { class: "muted small" }, relativeTime(review.updatedAt))))));
}

function privacyView(): HTMLElement {
  const sharing = state.sharing;
  if (!sharing) return h("p", { class: "muted" }, "Loading…");
  // Opening this page counts as reviewing privacy for Getting started.
  if (api.me()) store(`${PRIVACY_SEEN_KEY}:${api.me()}`, "1");
  const toggle = (field: string, label: string, checked: boolean, apply: (s: Sharing, on: boolean) => Sharing) =>
    h("label", { class: "toggle" }, h("span", {}, label), h("input", { type: "checkbox", "aria-label": label, checked, disabled: state.busy, onchange: (event) => {
      const on = (event.target as HTMLInputElement).checked;
      run(async () => { state.sharing = await api.updateSharing(sharing, sharingPayload(apply(sharing, on))); checkLibrarySize(); }, "Saved.");
    } }));
  return h("section", { class: "card" }, h("h3", {}, "Sharing with accepted friends"),
    h("p", { class: "muted small" }, "Each switch shares that information with accepted friends. Libraries and favorites are published from the app on your phone."),
    ...Object.entries(SHARING_LABELS).map(([field, label]) => toggle(field, label, (sharing as unknown as Record<string, string>)[field] === "friends",
      (s, on) => ({ ...s, [field]: on ? "friends" : "private" }))),
    toggle("globalRanking", "Appear on the global ranking", sharing.globalRanking, (s, on) => ({ ...s, globalRanking: on })),
    toggle("rankingMedia", "Show my picture and banner on the ranking", sharing.rankingMedia, (s, on) => ({ ...s, rankingMedia: on })),
    h("p", { class: "muted small" }, "Everyone using the app can see your name, handle, level, profile picture and banner (unless you turn that off), and how many chapters you read. Nothing else."),
    toggle("progress", "Share reading progress", sharing.progress, (s, on) => ({ ...s, progress: on })),
    h("p", { class: "muted small" }, "With your library: how many chapters you've read of each title you opened. Off: your library is shared without it."),
    toggle("reviews", "Show my ratings and reviews to friends", sharing.reviews, (s, on) => ({ ...s, reviews: on })),
    h("p", { class: "muted small" }, "Ratings and reviews are written in the app. Turning this off hides them from friends right away; they stay saved for you."),
    toggle("invisible", "Invisible mode", sharing.invisible, (s, on) => ({ ...s, invisible: on })));
}

/**
 * Profile look, like the app's: avatar and banner (PNG/JPEG/WebP/GIF within the backend's size limits), bio and accent.
 * Picked images upload first, then socialUpdateProfileStyle publishes everything at once.
 */
// The unsaved draft survives re-renders (the page redraws on every snapshot); cleared after saving.
let lookDraft: { picked: { avatar: File | null; banner: File | null }; removed: { avatar: boolean; banner: boolean }; accent: number | null } =
  { picked: { avatar: null, banner: null }, removed: { avatar: false, banner: false }, accent: null };
function profileLookSection(profile: api.Profile): HTMLElement {
  const { picked, removed } = lookDraft;
  let accent = lookDraft.accent ?? profile.accent;
  const status = h("p", { class: "muted small" });
  const bio = field("profileBio", { value: profile.bio, maxlength: "160", placeholder: "Bio (one line)" });
  const preview = (kind: "avatar" | "banner") => {
    const box = h("div", { class: kind === "avatar" ? "look-avatar" : "look-banner" });
    const media = removed[kind] ? null : profile[kind];
    if (picked[kind]) box.append(h("img", { src: URL.createObjectURL(picked[kind]!), alt: "" }));
    else if (media) api.mediaUrl(profile, kind).then((url) => { if (url && !picked[kind]) box.append(h("img", { src: url, alt: "" })); });
    return box;
  };
  const avatarBox = h("div", {}), bannerBox = h("div", {});
  const refresh = () => { replace(avatarBox, preview("avatar")); replace(bannerBox, preview("banner")); };
  const chooser = (kind: "avatar" | "banner", label: string) => {
    const input = h("input", { type: "file", accept: api.PROFILE_MEDIA_TYPES.join(","), "aria-label": label }) as HTMLInputElement;
    input.onchange = () => {
      const file = input.files?.[0];
      if (!file) return;
      if (!api.PROFILE_MEDIA_TYPES.includes(file.type)) { status.textContent = "Use a PNG, JPEG, WebP or GIF image."; input.value = ""; return; }
      if (file.size > api.PROFILE_MEDIA_MAX[kind]) {
        status.textContent = `That image is too big (the ${kind} can be up to ${api.PROFILE_MEDIA_MAX[kind] / 1048576} MB).`;
        input.value = ""; return;
      }
      picked[kind] = file; removed[kind] = false; status.textContent = ""; refresh();
    };
    return h("div", { class: "row" }, h("label", { class: "button secondary" }, label, input),
      h("button", { class: "secondary", onclick: () => { picked[kind] = null; removed[kind] = true; input.value = ""; refresh(); } }, "Remove"));
  };
  const swatches = h("div", { class: "row", role: "radiogroup", "aria-label": "Accent colour" });
  const drawSwatches = () => replace(swatches, ...ACCENTS.map((color, index) => h("button", {
    class: index === accent ? "swatch on" : "swatch", style: `background:${color}`, role: "radio", "aria-checked": String(index === accent),
    "aria-label": `Accent ${index + 1}`, onclick: () => { accent = index; lookDraft.accent = index; drawSwatches(); },
  })));
  drawSwatches(); refresh();
  const save = () => run(async () => {
    const action = (kind: "avatar" | "banner"): api.MediaAction => (picked[kind] ? "set" : removed[kind] ? "remove" : "keep");
    for (const kind of ["avatar", "banner"] as const) if (picked[kind]) await api.uploadProfileMedia(kind, picked[kind]!);
    state.profile = await api.updateProfileStyle(bio.value.replace(/[\r\n]+/g, " ").trim(), accent, action("avatar"), action("banner"));
    lookDraft = { picked: { avatar: null, banner: null }, removed: { avatar: false, banner: false }, accent: null };
  }, "Profile saved.");
  return h("section", { class: "card" }, h("h3", {}, "Profile look"),
    h("p", { class: "muted small" }, "Shown to your friends (and to everyone on the global ranking, unless you turn that off in Privacy)."),
    h("strong", {}, "Banner"), bannerBox, chooser("banner", "Choose banner"),
    h("strong", {}, "Avatar"), avatarBox, chooser("avatar", "Choose avatar"),
    h("strong", {}, "Bio"), bio,
    h("strong", {}, "Accent colour"), swatches,
    h("div", { class: "row" }, h("button", { disabled: state.busy, onclick: save }, "Save")), status);
}

function accountView(): HTMLElement {
  const password = field("deletePassword", { type: "password", placeholder: "Password", autocomplete: "current-password" });
  const confirmation = field("deleteConfirm", { placeholder: "Type DELETE to confirm" });
  return h("div", {},
    state.profile ? header(state.profile) : null,
    state.profile ? profileLookSection(state.profile) : null,
    h("section", { class: "card" }, h("h3", {}, "Account"), h("p", {}, state.user?.email ?? ""),
      h("button", { class: "secondary", onclick: () => signOut(api.authInstance()) }, "Sign out")),
    h("section", { class: "card" }, h("h3", {}, "Browser notifications"), notificationControls(false)),
    h("section", { class: "card danger-zone" }, h("h3", {}, "Delete account"),
      h("p", {}, "This permanently deletes your Mihon Together account: profile, friends, shared lists, recent activity, reading rooms and chat. Your manga on your devices is not touched. This cannot be undone."),
      password, confirmation,
      h("button", { class: "danger", disabled: state.busy, onclick: () => {
        if (confirmation.value !== "DELETE" || !password.value) { state.message = { text: "Enter your password and type DELETE.", error: true }; render(); return; }
        run(async () => {
          const user = api.authInstance().currentUser!;
          await reauthenticateWithCredential(user, EmailAuthProvider.credential(user.email ?? "", password.value));
          await user.getIdToken(true);
          await api.deleteAccount();
          await signOut(api.authInstance());
          navigate("/");
        }, "Your account was deleted.");
      } }, "Delete forever")));
}


// ---------- October features: ranking tabs, cover grids, messages, read-along requests, rooms ----------

/** Pages that hold their own listeners (chat, room, grid) are built once per path and torn down when leaving it. */
let page: { path: string; element: HTMLElement; stop: () => void } | null = null;
function cachedPage(path: string, build: (container: HTMLElement) => () => void): HTMLElement {
  if (page?.path === path) return page.element;
  page?.stop();
  const element = h("div", {});
  page = { path, element, stop: () => undefined };
  page.stop = build(element);
  return element;
}

/** My own or a friend's full profile (with pictures) when known, otherwise what the ranking board carries. */
function rankProfile(entry: RankingEntry): api.Profile {
  const known = entry.me ? state.profile : state.friends.get(entry.uid);
  return known ?? { uid: entry.uid, handle: entry.handle ?? "", displayName: entry.name, bio: "", accent: entry.accent ?? 0,
    avatar: entry.avatar ?? null, banner: entry.banner ?? null };
}
function rankBanner(profile: api.Profile): HTMLElement {
  const box = h("div", { class: "spot-banner", style: `background:${ACCENTS[profile.accent] ?? ACCENTS[0]}` });
  api.mediaUrl(profile, "banner").then((url) => { if (url) box.prepend(h("img", { src: url, alt: "" })); });
  return box;
}
/** The top three as a podium (second, first, third) with banner, avatar, name, level and score. */
function podium(top: RankingEntry[], score: (entry: RankingEntry) => string, open: (entry: RankingEntry) => void): HTMLElement {
  const medals = ["🥇", "🥈", "🥉"];
  return h("div", { class: "podium" }, ...[1, 0, 2].map((place) => {
    const entry = top[place];
    if (!entry) return h("div", { class: "spot empty" });
    const profile = rankProfile(entry), banner = rankBanner(profile);
    banner.append(h("span", { class: "medal" }, medals[place]));
    return h("div", { class: `spot place-${place + 1}${entry.me ? " me-row" : " clickable"}`, onclick: () => open(entry) },
      banner, avatar(profile, place === 0 ? 64 : 52),
      h("strong", { class: "name" }, entry.me ? "You" : entry.name),
      entry.level ? h("span", { class: "level" }, `Lv ${entry.level}`) : null,
      h("span", { class: "score" }, score(entry)));
  }));
}

function rankingCard(): HTMLElement {
  const medals = ["🥇", "🥈", "🥉"];
  const global = state.rankingTab === "global", weekly = state.rankingTab === "week", allTime = state.rankingTab === "alltime";
  const rows: Array<RankingEntry & { weekXp?: number; xp?: number }> =
    allTime ? state.allTime : weekly ? state.weekly : global ? state.board : state.ranking;
  const card = state.card as (RankingEntry & { xp?: number }) | null;
  const score = (entry: RankingEntry & { weekXp?: number; xp?: number }) =>
    weekly ? `${entry.weekXp ?? 0} XP · ${entry.chapters} ch.` : allTime ? `${entry.xp ?? 0} XP · ${entry.chapters} ch.` : `${entry.chapters} ch.`;
  const open = (entry: RankingEntry) => {
    if (entry.me) return;
    if (entry.friend || state.friends.has(entry.uid)) navigate(`/friend/${entry.uid}`);
    else { state.card = entry; render(); }
  };
  const tab = (key: "friends" | "global" | "week" | "alltime", label: string) =>
    h("button", { class: state.rankingTab === key ? "chip on" : "chip", onclick: () => { state.rankingTab = key; state.card = null; render(); } }, label);
  return h("section", { class: "card" }, h("h3", {}, weekly || allTime ? "Ranking" : "Today's ranking"),
    h("div", { class: "row" }, tab("friends", "Friends"), tab("global", "Global"), tab("week", "This week"), tab("alltime", "All time")),
    rows.length === 0 ? h("p", { class: "muted" }, "Nobody has finished a chapter today yet.") : null,
    rows.length ? podium(rows.slice(0, 3), score, open) : null,
    ...rows.slice(3).map((entry, index) => {
      const detail = [entry.handle ? `@${entry.handle}` : "", !allTime && (entry.streak ?? 1) >= 2 ? `🔥 ${entry.streak}` : ""].filter(Boolean).join("  ");
      return h("div", { class: entry.me ? "rank-item me-row" : "rank-item clickable", onclick: () => open(entry) },
        h("span", { class: "pos" }, `${index + 4}`), avatar(rankProfile(entry), 40),
        h("div", { class: "who" }, h("div", { class: "name" }, h("strong", {}, entry.me ? "You" : entry.name),
          entry.level ? h("span", { class: "level" }, `Lv ${entry.level}`) : null), detail ? h("div", { class: "muted small" }, detail) : null),
        h("span", { class: "score" }, score(entry)));
    }),
    card ? h("div", { class: "card inset" },
      card.banner ? rankBanner(rankProfile(card)) : null,
      h("div", { class: "row" }, avatar(rankProfile(card), 48), h("strong", {}, card.name)), h("div", { class: "muted small" }, card.handle ? `@${card.handle}` : "",
        card.level ? ` · Level ${card.level} ${levelTitle(card.level)}` : ""),
      card.xp !== undefined
        ? h("p", {}, `${card.chapters} chapters in total`)
        : h("p", {}, `${card.chapters} chapters today`, (card.streak ?? 1) >= 2 ? ` · 🔥 ${card.streak}-day streak` : ""),
      h("p", { class: "muted small" }, "Not your friend yet: you only see what's on the global ranking."),
      h("div", { class: "row" },
        state.outgoing.some((r) => r.recipientUid === card.uid)
          ? h("span", { class: "muted" }, "Request sent")
          : h("button", { disabled: state.busy, onclick: () => run(() => social.requestFriendUid(card.uid), "Friend request sent.") }, "Add friend"),
        h("button", { class: "secondary", onclick: () => { state.card = null; render(); } }, "Close"))) : null,
    (global || allTime) && (!state.sharing?.globalRanking || state.sharing?.invisible)
      ? h("p", { class: "muted small" }, "You're not on the global ranking. Turn it on in Privacy, and turn off invisible mode.") : null,
    weekly ? h("p", { class: "muted small" }, "XP earned since Monday 00:00 UTC, among you and friends who share stats.") : null,
    allTime ? h("p", { class: "muted small" }, "Total XP, among everyone on the global ranking. You show up after your next chapter.") : null,
    !global && !weekly && !allTime && state.sharing?.stats !== "friends" ? h("p", { class: "muted small" }, "Turn on daily reading stats in Privacy so friends see you here.") : null);
}

function socialSections(): Array<HTMLElement | null> {
  const me = api.me() ?? "";
  const rooms = state.rooms.map((room) => {
    const mine = room.members[me];
    const invited = mine?.state === "invited";
    const host = room.members[room.hostUid]?.name || "Someone";
    const people = companions(Object.values(room.members), me);
    return h("div", { class: "room" },
      h("strong", {}, invited ? `${host} invited you to read ${room.title}` : `Reading ${room.title} with ${people}`),
      h("div", { class: "muted small" }, `${room.participants.length} of ${room.maxMembers} people`,
        room.chapterLabel ? ` · host at ${room.chapterLabel}, page ${room.page}` : ""),
      invited && room.participants.length > 2
        ? h("p", { class: "muted small" }, `Joining lets everyone in this room see your name, chat and reactions, even people who aren't your friends: ${Object.values(room.members).filter((m) => m.uid !== me).map((m) => m.name).join(", ")}.`)
        : null,
      h("div", { class: "row" },
        invited
          ? h("button", { disabled: state.busy, onclick: () => run(async () => { await social.joinRoom(room.id); navigate(`/room/${room.id}`); }) }, "Join chat")
          : link(`/room/${room.id}`, "Open room chat"),
        h("button", { class: "secondary", disabled: state.busy, onclick: () => run(() => social.leaveRoom(room.id)) }, "Leave")));
  });
  const open = joinableRooms([...state.status.values()].map((s) => s.openRoom), new Set(state.rooms.map((r) => r.id))).map((card) => {
    const host = state.friends.get(card.hostUid)?.displayName || "A friend";
    return h("div", { class: "room" },
      h("strong", {}, `${host} is reading ${card.title}`),
      h("div", { class: "muted small" }, `${card.memberCount} of ${card.maxMembers} people`),
      h("p", { class: "muted small" }, "Joining lets everyone in this room see your name, chat, reactions and which page you're on, even people who aren't your friends."),
      h("div", { class: "row" },
        h("button", { disabled: state.busy, onclick: () => run(async () => { await social.joinRoom(card.roomId); navigate(`/room/${card.roomId}`); }) }, "Join chat")));
  });
  return [
    rooms.length ? h("section", { class: "card" }, h("h3", {}, "Reading together"),
      h("p", { class: "muted small" }, "On the web you chat and react; pages and following stay in the app."), ...rooms) : null,
    open.length ? h("section", { class: "card" }, h("h3", {}, "Friends you can join"),
      h("p", { class: "muted small" }, "These friends let friends drop in. Read along in the app; on the web you chat and react."), ...open) : null,
    state.joinRequests.length ? h("section", { class: "card" }, h("h3", {}, "Want to read with you"),
      ...state.joinRequests.map((request) => h("div", { class: "row between" },
        h("span", {}, `${request.name || request.handle} asks to read along`),
        h("span", {}, h("span", { class: "muted small" }, "Accept in the app while reading "),
          h("button", { class: "secondary", onclick: () => run(() => social.declineJoinRequest(request.requesterUid)) }, "Not now"))))) : null,
  ];
}

/** My level, progress to the next one and lifetime counters. */
function xpCard(): HTMLElement {
  const total = state.xp?.total ?? 0, level = levelFor(total);
  const start = xpForLevel(level), size = xpForLevel(level + 1) - start;
  return h("section", { class: "card" },
    h("h3", {}, h("span", { class: "level" }, `Lv ${level}`), ` ${levelTitle(level)}`),
    h("progress", { max: String(size), value: String(total - start) }),
    h("p", { class: "small" }, `${total - start} / ${size} XP to level ${level + 1}`),
    state.xp ? h("p", { class: "muted small" }, `${state.xp.chapters} chapters · ${state.xp.together} read together · best streak ${state.xp.bestStreak} days`) : null,
    state.xp ? h("p", { class: "muted small" }, `${state.xp.week === weekOf() ? state.xp.weekXp : 0} XP this week · streak ${state.xp.streak} · 🛡️ ${state.xp.freezes} streak freezes`) : null,
    state.xp ? badgeStrip(state.xp.badges, true) : null,
    h("p", { class: "muted small" }, "Earn XP in the app for every chapter you finish, with bonuses for reading together and daily streaks."),
    link("/stats", "See all your stats"));
}

/** Achievement icons with their name and condition as a tooltip; locked ones faded when [all]. */
function badgeStrip(badges: Record<string, number>, all: boolean): HTMLElement {
  const unlocked = BADGES.filter(([id]) => id in badges).length;
  return h("div", {}, h("p", { class: "small" }, `Achievements ${unlocked} / ${BADGES.length}`),
    h("div", { class: "badges" }, ...BADGES.filter(([id]) => all || id in badges).map(([id, icon, name, how]) =>
      h("span", { class: id in badges ? "badge-icon" : "badge-icon locked", title: `${name}: ${how}` }, icon))));
}

/** A recommended title in a message: cover and name (open it in the app to read). */
function titleCard(content: social.SharedTitle | null): HTMLElement | null {
  if (!content) return null;
  return h("div", { class: "title-card" }, coverImage(content.title, content.coverUrl),
    h("div", {}, h("div", { class: "muted small" }, "Recommended"), h("strong", {}, content.title),
      content.source ? h("div", { class: "muted small" }, content.source) : null));
}

/** New group: a name and some friends. */
function newGroupForm(): HTMLElement {
  const name = field("groupName", { placeholder: "Group name", maxlength: "40" });
  const boxes = [...state.friends.values()].sort((a, b) => a.displayName.localeCompare(b.displayName)).map((friend) => {
    const box = h("input", { type: "checkbox", value: friend.uid }) as HTMLInputElement;
    return { box, row: h("label", { class: "row small" }, box, ` ${friend.displayName}`) };
  });
  return h("details", { class: "card inset" }, h("summary", {}, "New group"),
    name, ...boxes.map((b) => b.row),
    h("button", { disabled: state.busy, onclick: () => {
      const uids = boxes.filter((b) => b.box.checked).map((b) => b.box.value).slice(0, 19);
      const title = name.value.replace(/\n/g, " ").trim();
      if (!title || !uids.length) { state.message = { text: "Name the group and pick at least one friend.", error: true }; render(); return; }
      run(async () => { const id = await social.createGroup(title, uids); name.value = ""; navigate(`/group/${id}`); });
    } }, "Create group"));
}

function messagesView(): HTMLElement {
  const chats = state.chats.filter((chat) => state.friends.has(chat.otherUid));
  return h("section", { class: "card" }, h("h3", {}, "Messages"),
    notificationControls(true),
    state.friends.size ? newGroupForm() : null,
    ...state.groups.map((group) => h("a", { class: "friend", href: `/group/${group.groupId}`, onclick: (event) => { event.preventDefault(); navigate(`/group/${group.groupId}`); } },
      h("div", { class: "avatar group" }, group.name.slice(0, 1).toUpperCase()),
      h("div", { class: "grow" }, h("strong", {}, group.name),
        h("div", { class: "muted small ellipsis" }, group.lastText ? `${group.lastSenderName ? `${group.lastSenderName}: ` : ""}${group.lastText}` : `${group.members} members`)),
      group.unread > 0 ? h("span", { class: "badge" }, String(group.unread)) : null)),
    chats.length === 0 && state.groups.length === 0 ? h("p", { class: "muted" }, "No conversations yet. Open a friend and choose Message.") : null,
    ...chats.map((chat) => {
      const friend = state.friends.get(chat.otherUid)!;
      return h("a", { class: "friend", href: `/chat/${chat.otherUid}`, onclick: (event) => { event.preventDefault(); navigate(`/chat/${chat.otherUid}`); } },
        avatar(friend, 40),
        h("div", { class: "grow" }, h("strong", {}, friend.displayName),
          h("div", { class: "muted small ellipsis" }, chat.lastSenderUid === api.me() ? `You: ${chat.lastText}` : chat.lastText)),
        chat.unread > 0 ? h("span", { class: "badge" }, String(chat.unread)) : null);
    }));
}

/** What chat replies and reactions need from a message (private or group). */
interface Reactable { seq: number; text: string; content: social.SharedTitle | null; replyTo: social.ReplyQuote | null; reactions: Record<string, string> }
/**
 * Replies and reactions for one chat page: the reply bar above the input, and per message its quote (tap to jump to
 * the original), reaction chips (tap to toggle mine) and Reply / React actions. [redraw] re-renders the list.
 */
function chatTools(list: HTMLElement, status: HTMLElement, me: string, react: (seq: number, emoji: string) => Promise<unknown>, redraw: () => void) {
  let replying: { seq: number; name: string; preview: string } | null = null;
  let picking: number | null = null;
  const bar = h("div", { class: "reply-bar", hidden: true });
  const showBar = () => {
    bar.hidden = !replying;
    if (replying) replace(bar, h("div", { class: "grow ellipsis small" }, h("strong", {}, `Replying to ${replying.name}`), ` ${replying.preview}`),
      h("button", { class: "secondary", "aria-label": "Cancel reply", onclick: () => { replying = null; showBar(); } }, "×"));
  };
  const toggle = (seq: number, emoji: string) => {
    picking = null;
    react(seq, emoji).catch((error) => { status.textContent = describe(error); });
    redraw();
  };
  const preview = (m: Reactable) => (m.content ? `📖 ${m.content.title}` : m.text).slice(0, 100);
  return {
    bar,
    replyTo: () => replying?.seq ?? null,
    clearReply: () => { replying = null; showBar(); },
    /** The quote inside a reply's bubble; [name] resolves the original sender; [visible] false shows a placeholder. */
    quote(m: Reactable, name: (quote: social.ReplyQuote) => string, visible: (quote: social.ReplyQuote) => boolean = () => true): HTMLElement | null {
      const q = m.replyTo;
      if (!q) return null;
      if (!visible(q)) return h("div", { class: "quote" }, h("strong", { class: "small" }, "Earlier message"));
      return h("div", { class: "quote", role: "button", tabindex: "0", onclick: () => {
        list.querySelector<HTMLElement>(`[data-seq="${q.seq}"]`)?.scrollIntoView({ behavior: "smooth", block: "center" });
      } }, h("strong", { class: "small" }, name(q)), h("div", { class: "small ellipsis" }, q.preview));
    },
    /** Reaction chips, then Reply / React (and, while picking, the quick emojis and "+"). */
    footer(m: Reactable, senderName: string): HTMLElement {
      const chips = reactionChips(m.reactions, me).map((chip) => h("button", { class: chip.mine ? "reaction mine" : "reaction",
        "aria-pressed": chip.mine ? "true" : "false", onclick: () => toggle(m.seq, chip.emoji) }, `${chip.emoji} ${chip.count}`));
      const pick = picking === m.seq ? h("div", { class: "reaction-picker" },
        ...QUICK_REACTIONS.map((emoji) => h("button", { class: "emoji", onclick: () => toggle(m.seq, emoji) }, emoji)),
        h("button", { class: "emoji", "aria-label": "Any emoji", onclick: () => {
          const emoji = prompt("React with any emoji")?.trim();
          if (!emoji) return;
          if (isReactionEmoji(emoji)) toggle(m.seq, emoji);
          else status.textContent = "Pick a single emoji.";
        } }, "+")) : null;
      return h("div", {}, chips.length ? h("div", { class: "reactions" }, ...chips) : null, pick,
        h("div", { class: "msg-actions" },
          h("button", { class: "link", onclick: () => { replying = { seq: m.seq, name: senderName, preview: preview(m) }; showBar(); } }, "Reply"),
          h("button", { class: "link", onclick: () => { picking = picking === m.seq ? null : m.seq; redraw(); } }, "React")));
    },
  };
}

function chatView(otherUid: string): HTMLElement {
  return cachedPage(`/chat/${otherUid}`, (container) => {
    const me = api.me() ?? "";
    let stopMessages: (() => void) | null = null;
    let chatId: string | null = null;
    let messages: social.ChatMessage[] = [];
    const list = h("div", { class: "chat" });
    const input = h("input", { placeholder: "Message", maxlength: "500" }) as HTMLInputElement;
    const status = h("p", { class: "muted small" }, "Only the two of you can read this. Messages disappear after 30 days.");
    const friend = state.friends.get(otherUid);
    const nameOf = (uid: string) => (uid === me ? "You" : friend?.displayName ?? "");
    const draw = () => replace(list, ...messages.map((m) => {
      // Server lines (read matches, recaps) take no replies or reactions.
      const tools = m.system ? null : chat;
      return h("div", { class: m.senderUid === me ? "bubble mine" : "bubble", "data-seq": String(m.seq) },
        tools?.quote(m, (q) => nameOf(q.senderUid)), titleCard(m.content), m.text || null,
        h("div", { class: "muted small" }, relativeTime(m.createdAt)), tools?.footer(m, nameOf(m.senderUid)));
    }));
    const chat = chatTools(list, status, me, (seq, emoji) => social.reactToMessage(otherUid, seq, emoji), draw);
    const send = async () => {
      const text = input.value.replace(/\n/g, " ").trim();
      if (!text) return;
      const replyTo = chat.replyTo();
      // The text and reply stay until the message went out, so a failed one can be sent again.
      try {
        await social.sendMessage(otherUid, text, replyTo);
        if (input.value.replace(/\n/g, " ").trim() === text) input.value = "";
        chat.clearReply();
      } catch (error) { status.textContent = describe(error); }
    };
    input.addEventListener("keydown", (event) => { if (event.key === "Enter") send(); });
    replace(container, link("/messages", "← Messages"), h("h2", {}, friend?.displayName ?? "Chat"), list, chat.bar,
      h("div", { class: "row" }, input, h("button", { onclick: send }, "Send")), status);
    const watch = () => {
      const summary = state.chats.find((c) => c.otherUid === otherUid);
      if (summary?.unread) social.markChatRead(otherUid).catch(() => undefined);
      if (!summary || summary.chatId === chatId) return;
      chatId = summary.chatId;
      stopMessages?.();
      stopMessages = social.watchChatMessages(chatId, (latest) => {
        const grew = latest.at(-1)?.seq !== messages.at(-1)?.seq;
        messages = latest;
        draw();
        // Only new messages scroll down; a reaction keeps the place.
        if (grew) list.scrollTop = list.scrollHeight;
      });
    };
    chatWatchers.add(watch);
    watch();
    if (!state.chats.some((c) => c.otherUid === otherUid)) replace(list, h("p", { class: "muted" }, "No messages yet. Say hi!"));
    return () => { chatWatchers.delete(watch); stopMessages?.(); };
  });
}
const chatWatchers = new Set<() => void>();

const GROUP_EVENTS: Record<string, (who: string, subject: string) => string> = {
  created: (who, subject) => `${who} created the group “${subject}”`,
  added: (who, subject) => `${who} added ${subject}`,
  removed: (who, subject) => `${who} removed ${subject}`,
  left: (_, subject) => `${subject} left`,
  owner: (_, subject) => `${subject} is now the group owner`,
  renamed: (who, subject) => `${who} renamed the group to “${subject}”`,
};
function groupView(groupId: string): HTMLElement {
  return cachedPage(`/group/${groupId}`, (container) => {
    const me = api.me() ?? "";
    let stopLines: (() => void) | null = null;
    let since: number | null = null;
    const title = h("h2", {}, state.groups.find((g) => g.groupId === groupId)?.name ?? "Group");
    const members = h("p", { class: "muted small" });
    let lines: social.GroupLine[] = [];
    const list = h("div", { class: "chat" });
    const input = h("input", { placeholder: "Message", maxlength: "500" }) as HTMLInputElement;
    const status = h("p", { class: "muted small" }, "Only the group's members can read this. Messages disappear after 30 days.");
    const draw = () => replace(list, ...lines.map((line, i) => {
      const who = line.senderUid === me ? "You" : line.senderName;
      if (line.kind === "event") return h("div", { class: "muted small center" }, GROUP_EVENTS[line.event ?? ""]?.(who, line.text) ?? "");
      const named = line.senderUid !== me && (i === 0 || lines[i - 1].senderUid !== line.senderUid || lines[i - 1].kind === "event");
      return h("div", { class: line.senderUid === me ? "bubble mine" : "bubble", "data-seq": String(line.seq) },
        named ? h("div", { class: "small sender" }, line.senderName) : null,
        chat.quote(line, (q) => (q.senderUid === me ? "You" : q.senderName ?? ""), (q) => visibleQuote(q, since ?? Number.MAX_SAFE_INTEGER, state.blocks) !== null),
        titleCard(line.content), line.text || null,
        h("div", { class: "muted small" }, relativeTime(line.createdAt)), chat.footer(line, who));
    }));
    const chat = chatTools(list, status, me, (seq, emoji) => social.reactInGroup(groupId, seq, emoji), draw);
    const send = async () => {
      const text = input.value.replace(/\n/g, " ").trim();
      if (!text) return;
      const replyTo = chat.replyTo();
      try {
        await social.sendGroupLine(groupId, text, replyTo);
        if (input.value.replace(/\n/g, " ").trim() === text) input.value = "";
        chat.clearReply();
      } catch (error) { status.textContent = describe(error); }
    };
    input.addEventListener("keydown", (event) => { if (event.key === "Enter") send(); });
    const leave = h("button", { class: "secondary", onclick: () => {
      if (confirm("Leave this group? You won't see its messages any more.")) run(async () => { await social.leaveGroup(groupId); navigate("/messages"); });
    } }, "Leave group");
    replace(container, link("/messages", "← Messages"), title, members, list, chat.bar,
      h("div", { class: "row" }, input, h("button", { onclick: send }, "Send")), status, leave);
    const stopGroup = social.watchGroup(groupId, (group) => {
      const mine = group?.members.find((m) => m.uid === me);
      if (!group || !mine) { lines = []; replace(list, h("p", { class: "error", role: "status" },"You're no longer in this group.")); stopLines?.(); since = null; return; }
      title.textContent = group.name;
      members.textContent = group.members.map((m) => (m.uid === me ? "You" : m.name) + (m.uid === group.ownerUid ? " (owner)" : "")).join(", ");
      if (since === mine.since) return;
      since = mine.since;
      stopLines?.();
      stopLines = social.watchGroupLines(groupId, mine.since, (latest) => {
        // People I blocked: their lines and reactions stay hidden (quotes of them too, see draw).
        const shown = latest.filter((line) => line.kind === "event" || !state.blocks.has(line.senderUid))
          .map((line) => ({ ...line, reactions: withoutBlocked(line.reactions, state.blocks) }));
        const grew = shown.at(-1)?.seq !== lines.at(-1)?.seq;
        lines = shown;
        draw();
        if (grew) list.scrollTop = list.scrollHeight;
      });
    });
    const watch = () => { if (state.groups.find((g) => g.groupId === groupId)?.unread) social.markGroupRead(groupId).catch(() => undefined); };
    groupWatchers.add(watch);
    watch();
    return () => { groupWatchers.delete(watch); stopGroup(); stopLines?.(); };
  });
}
const groupWatchers = new Set<() => void>();

function roomView(roomId: string): HTMLElement {
  return cachedPage(`/room/${roomId}`, (container) => {
    const me = api.me() ?? "";
    const info = h("div", {});
    const lines = h("div", { class: "chat" });
    const input = h("input", { placeholder: "Message the room", maxlength: "200" }) as HTMLInputElement;
    const status = h("p", { class: "muted small" });
    const say = async (text: string | null, emoji: string | null) => {
      try { await social.sendRoomLine(roomId, text, emoji); status.textContent = ""; } catch (error) { status.textContent = describe(error); }
    };
    const sendText = () => { const text = input.value.trim(); if (text) { input.value = ""; say(text, null); } };
    input.addEventListener("keydown", (event) => { if (event.key === "Enter") sendText(); });
    const nameOf = (uid: string) => state.rooms.find((r) => r.id === roomId)?.members[uid]?.name || "Someone";
    const eventText = (kind: string, name: string) => ({ joined: `${name} joined`, left: `${name} left`, removed: `${name} was removed by the host`, host: `${name} is now the host` } as Record<string, string>)[kind];
    replace(container, link("/", "← Friends"), info, lines,
      h("div", { class: "row" }, input, h("button", { onclick: sendText }, "Send")),
      h("div", { class: "row" }, ...social.ROOM_REACTIONS.map((emoji) => h("button", { class: "secondary emoji", onclick: () => say(null, emoji) }, emoji))),
      status);
    const drawInfo = () => {
      const room = state.rooms.find((r) => r.id === roomId);
      if (!room) { replace(info, h("p", { class: "muted" }, "This room has ended.")); return; }
      replace(info, h("h2", {}, room.title),
        h("p", { class: "muted small" }, room.chapterLabel ? `Host at ${room.chapterLabel}, page ${room.page}` : "Waiting for the host"),
        h("p", {}, `In this room (${room.participants.length} of ${room.maxMembers}): `,
          room.participants.map((uid) => `${uid === me ? "You" : room.members[uid]?.name || "?"}${uid === room.hostUid ? " (host)" : room.members[uid]?.state === "invited" ? " (invited)" : ""}`).join(", ")));
    };
    roomWatchers.add(drawInfo);
    drawInfo();
    const stop = social.watchRoomLines(roomId, (items) => {
      replace(lines, ...items.map((line) => line.kind === "ready" || line.kind === "wait"
        ? h("div", { class: "muted small" }, line.kind === "ready" ? `✅ ${nameOf(line.senderUid)} is ready for the next chapter` : `⏸ ${nameOf(line.senderUid)} asks everyone to wait`)
        : line.kind in { joined: 1, left: 1, removed: 1, host: 1 }
        ? h("div", { class: "muted small" }, eventText(line.kind, nameOf(line.senderUid)))
        : h("div", { class: line.senderUid === me ? "bubble mine" : "bubble" },
          h("div", { class: "muted small" }, line.senderUid === me ? "You" : nameOf(line.senderUid)),
          line.emoji ? h("span", { class: "big" }, line.emoji) : line.text)));
      lines.scrollTop = lines.scrollHeight;
    });
    return () => { roomWatchers.delete(drawInfo); stop(); };
  });
}
const roomWatchers = new Set<() => void>();

/** A shared library as a cover grid: pages load A–Z as you scroll; searching loads the rest first. */
function libraryGrid(path: string, uid: string, kind: "library" | "favorites", generation: number, title: string, back: HTMLElement, progress: boolean): HTMLElement {
  return cachedPage(path, (container) => {
    let items: social.Entry[] = [];
    let cursor: unknown | null = null;
    let done = false;
    let loading = false;
    let total: number | null = null;
    const search = h("input", { placeholder: "Search this list", maxlength: "100" }) as HTMLInputElement;
    const counter = h("span", { class: "muted small" });
    const grid = h("div", { class: "grid" });
    const more = h("div", { class: "muted small center" });
    const draw = () => {
      const q = search.value.trim().toLowerCase();
      const shown = q ? items.filter((i) => i.title.toLowerCase().includes(q)) : items;
      counter.textContent = `${shown.length} of ${total ?? items.length}`;
      replace(grid, ...shown.map((item) => entryTile(uid, item, progress)));
      more.textContent = done ? "" : loading ? "Loading…" : "Scroll for more";
    };
    const load = async (all: boolean) => {
      if (loading || done) return;
      loading = true; draw();
      try {
        do {
          const result = await social.loadEntriesPage(uid, kind, generation, cursor);
          items = items.concat(result.items);
          cursor = result.cursor;
          done = cursor === null;
        } while (all && !done);
      } catch (error) {
        more.textContent = describe(error);
      } finally {
        loading = false; draw();
      }
    };
    search.addEventListener("input", () => { if (search.value.trim()) load(true); draw(); });
    const observer = new IntersectionObserver((entries) => { if (entries.some((e) => e.isIntersecting)) load(false); });
    observer.observe(more);
    replace(container, back, h("h2", {}, title), h("div", { class: "row" }, search, counter), grid, more);
    social.countEntries(uid, kind, generation).then((count) => { total = count; draw(); });
    load(false);
    return () => observer.disconnect();
  });
}

function myLibraryView(): HTMLElement {
  const sharing = state.sharing!;
  const uid = api.me()!;
  if (sharing.library !== "friends") {
    return h("section", { class: "card" }, h("h3", {}, "Your library"),
      h("p", { class: "muted" }, "Your library comes from the app on your phone. Turn on Library sharing (Privacy), then tap Share current library and favorites in the app."));
  }
  return libraryGrid("/library", uid, "library", sharing.generation, "Your library", h("p", { class: "muted small" },
    "Synced from your phone when you share your library in the app. Reading happens in the app; sources are Android extensions."), sharing.progress);
}

/** A friend's whole library or favorites; needs their current sharing generation first. */
function friendGrid(path: string): HTMLElement {
  const [, , uid, kind] = path.split("/") as [string, string, string, "library" | "favorites"];
  if (page?.path === path) return page.element;
  // Cached right away: building a new holder on every redraw while loading called socialGetFriend each time.
  const holder = h("div", {}, h("p", { class: "muted" }, "Loading…"));
  page?.stop();
  page = { path, element: holder, stop: () => undefined };
  api.getFriend(uid).then(({ profile, sharing }) => {
    if (location.pathname !== path) return;
    if (sharing[kind] !== "friends") { replace(holder, link(`/friend/${uid}`, "← Back"), h("p", { class: "muted" }, "This information is private.")); return; }
    replace(holder, libraryGrid(path, uid, kind, sharing.generation, `${profile.displayName}'s ${kind === "library" ? "library" : "favorites"}`, link(`/friend/${uid}`, "← Back"), sharing.progress));
  }).catch((error) => replace(holder, link("/", "← Friends"), h("p", { class: "error", role: "status" },describe(error))));
  return holder;
}

// ---------- discovery: friends this week, your stats ----------

interface ActivityData { reading: ActivityGroup[]; favorited: ActivityGroup[]; sharing: number; total: number }
/** Loaded when the page opens and kept for 5 minutes: it's a summary, not a live view. */
let activityCache: { uid: string; at: number; data: Promise<ActivityData> } | null = null;
function loadActivity(): Promise<ActivityData> {
  const uid = api.me() ?? "";
  if (activityCache && activityCache.uid === uid && Date.now() - activityCache.at < 5 * 60_000) return activityCache.data;
  const friends = [...state.friends.values()].filter((friend) => !state.blocks.has(friend.uid));
  const weekAgo = Date.now() - 7 * 86_400_000;
  // Only friends who share their feed: the rules refuse the rest (and invisible friends), which just counts as not sharing.
  const data = Promise.all(friends.map(async (friend) => {
    try {
      const { sharing } = await api.getFriend(friend.uid);
      if (sharing.feed !== "friends") return null;
      const items = (await api.loadFeed(friend.uid, sharing.generation)).filter((item) => item.createdAt >= weekAgo);
      return { name: friend.displayName, items };
    } catch {
      return null;
    }
  })).then((feeds) => {
    const shared = feeds.filter((feed): feed is { name: string; items: api.FeedItem[] } => feed !== null);
    return { ...groupActivity(shared), sharing: shared.length, total: friends.length };
  });
  // Friends arrive shortly after sign-in; an empty list isn't worth keeping.
  if (friends.length) activityCache = { uid, at: Date.now(), data };
  return data;
}

function activityRow(group: ActivityGroup, detail: string): HTMLElement {
  return h("div", { class: "activity-row" }, coverImage(group.title, null),
    h("div", { class: "grow" }, h("strong", {}, group.title), h("div", { class: "small" }, namesLabel(group.names)),
      h("div", { class: "muted small" }, `${detail} · ${relativeTime(group.latest)}`)));
}

function activityView(): HTMLElement {
  return cachedPage("/activity", (container) => {
    let alive = true;
    replace(container, h("h2", {}, "Friends this week"), h("p", { class: "muted" }, "Loading…"));
    loadActivity().then((data) => {
      if (!alive) return;
      const empty = data.total === 0 ? "Add friends to see what they're reading."
        : data.sharing === 0 ? "None of your friends share their recent activity yet." : null;
      replace(container,
        h("h2", {}, "Friends this week"),
        h("p", { class: "muted small" }, data.total ? `From ${data.sharing} of ${data.total} friends who share their recent activity.` : ""),
        empty ? h("section", { class: "card" }, h("p", { class: "muted" }, empty)) : null,
        empty ? null : h("section", { class: "card" }, h("h3", {}, "Being read"),
          data.reading.length ? null : h("p", { class: "muted" }, "No chapters read this week."),
          ...data.reading.map((group) => activityRow(group, `${group.count} ${group.count === 1 ? "chapter" : "chapters"} read`))),
        empty ? null : h("section", { class: "card" }, h("h3", {}, "Newly favorited"),
          data.favorited.length ? null : h("p", { class: "muted" }, "No new favorites this week."),
          ...data.favorited.map((group) => activityRow(group, "Added to favorites"))));
    }).catch((error) => { if (alive) replace(container, h("h2", {}, "Friends this week"), h("p", { class: "error", role: "status" }, describe(error))); });
    return () => { alive = false; };
  });
}

/** My chapters per day, loaded once per visit to the stats page (they change at most once a minute). */
let myDays: { uid: string; at: number; days: Record<string, number> | null } | null = null;
function statsDays(): Record<string, number> | null {
  const uid = api.me() ?? "";
  if (!myDays || myDays.uid !== uid || Date.now() - myDays.at > 5 * 60_000) {
    const entry: { uid: string; at: number; days: Record<string, number> | null } = { uid, at: Date.now(), days: null };
    myDays = entry;
    api.loadMyDays(uid).then((days) => { entry.days = days; render(); }, () => { entry.days = {}; render(); });
  }
  return myDays.days;
}

function statsView(): HTMLElement {
  const xp = state.xp;
  const total = xp?.total ?? 0, level = levelFor(total);
  const start = xpForLevel(level), size = xpForLevel(level + 1) - start;
  const records = statsDays();
  const days = lastDays(records ?? {});
  const most = Math.max(1, ...days.map((d) => d.chapters));
  const counter = (value: string | number, label: string) => h("div", { class: "stat" }, h("strong", {}, String(value)), h("span", { class: "muted small" }, label));
  const unlocked = xp ? BADGES.filter(([id]) => id in xp.badges).length : 0;
  return h("div", {},
    link("/", "← Home"),
    h("h2", {}, "Your stats"),
    h("section", { class: "card" },
      h("h3", {}, h("span", { class: "level" }, `Lv ${level}`), ` ${levelTitle(level)}`),
      h("progress", { max: String(size), value: String(total - start), "aria-label": `Progress to level ${level + 1}` }),
      h("p", { class: "small" }, `${total - start} / ${size} XP to level ${level + 1}`),
      h("div", { class: "stats-grid" },
        counter(total, "total XP"), counter(xp?.chapters ?? 0, "chapters"), counter(xp?.together ?? 0, "read together"),
        counter(xp?.streak ?? 0, "day streak"), counter(xp?.bestStreak ?? 0, "best streak"), counter(xp?.freezes ?? 0, "streak freezes"),
        counter(xp && xp.week === weekOf() ? xp.weekXp : 0, "XP this week"), counter(xp?.days ?? 0, "days read"),
        counter(xp?.bestDay ?? 0, "best day (chapters)")),
      h("button", { disabled: !xp, onclick: () => shareStats().catch((error) => { state.message = { text: describe(error), error: true }; render(); }) }, "Share my stats")),
    challengesCard(xp),
    h("section", { class: "card" }, h("h3", {}, "Last 8 days"),
      records === null ? h("p", { class: "muted" }, "Loading…") : h("ul", { class: "bars", "aria-label": "Chapters read per day, last 8 days (UTC)" },
        ...days.map((d) => {
          const bar = h("span", { class: "bar" });
          bar.style.height = `${Math.round((d.chapters / most) * 100)}%`;
          return h("li", { "aria-label": `${d.label} ${d.day.slice(6)}.${d.day.slice(4, 6)}.: ${d.chapters} ${d.chapters === 1 ? "chapter" : "chapters"}` },
            h("span", { class: "small", "aria-hidden": "true" }, String(d.chapters)), h("span", { class: "bar-track", "aria-hidden": "true" }, bar),
            h("span", { class: "muted small", "aria-hidden": "true" }, d.label));
        })),
      h("p", { class: "muted small" }, "Days are counted while daily reading stats or the global ranking is on, and kept for 8 days.")),
    h("section", { class: "card" }, h("h3", {}, `Achievements ${unlocked} / ${BADGES.length}`),
      h("ul", { class: "achievements" }, ...BADGES.map(([id, icon, name, how]) => {
        const at = xp?.badges[id];
        return h("li", { class: at ? "" : "locked" },
          h("span", { class: "badge-icon", "aria-hidden": "true" }, icon),
          h("div", {}, h("strong", {}, name), h("div", { class: "small" }, how),
            h("div", { class: "muted small" }, at ? `Unlocked ${new Date(Number(at)).toLocaleDateString()}` : "Locked")));
      }),
      ...seasons(xp?.badges ?? {}).map(({ month, at }) => h("li", {},
        h("span", { class: "badge-icon", "aria-hidden": "true" }, "🏆"),
        h("div", {}, h("strong", {}, `${monthName(month)} — all challenges`),
          h("div", { class: "muted small" }, `Unlocked ${new Date(at).toLocaleDateString()}`)))))),
    howXpWorks());
}

/** This month's three challenges (UTC month) with progress bars, like the app. */
function challengesCard(xp: social.Xp | null): HTMLElement {
  const month = monthOf(), items = challengeProgress(xp, month);
  return h("section", { class: "card" }, h("h3", {}, `${monthName(month)} challenges`),
    h("ul", { class: "achievements" }, ...items.map(({ challenge, value, done }) => h("li", { class: done ? "" : "locked" },
      h("span", { class: "badge-icon", "aria-hidden": "true" }, challenge.icon),
      h("div", { class: "grow" }, h("strong", {}, challenge.label),
        h("progress", { max: String(challenge.min), value: String(Math.min(value, challenge.min)), "aria-label": `${challenge.label}: ${value} of ${challenge.min}` }),
        h("div", { class: "muted small" }, done ? "Done" : `${value} / ${challenge.min}`))))),
    h("p", { class: "muted small" }, items.every((i) => i.done) ? "🏆 All of this month's challenges done!"
      : `+${XP_RULES.challengeXp} XP for each. Finish all three for a season badge. New challenges on the 1st (UTC).`));
}

/** "How XP works": the backend's rules, from XP_RULES (kept equal to domain.ts by tests). */
function howXpWorks(): HTMLElement {
  const r = XP_RULES;
  return h("section", { class: "card" }, h("details", {}, h("summary", {}, "How XP works"),
    h("ul", {},
      h("li", {}, `Every finished chapter: ${r.chapter} XP.`),
      h("li", {}, `Reading together in a room with a friend: +${r.together} XP per chapter.`),
      h("li", {}, `First chapter of the day: +${r.streakDay} XP for each streak day, counting up to ${r.streakMaxDays} days (at most +${r.streakDay * r.streakMaxDays}).`),
      h("li", {}, `Reading XP is capped at ${r.dailyCap} a day (UTC).`),
      h("li", {}, "Double XP every Friday and Saturday (Israel time) and during events: reading XP and the daily cap double."),
      h("li", {}, `Streak freezes: you earn one every ${r.freezeEveryDays} streak days and can hold ${r.maxFreezes}. Each one covers a missed day.`),
      h("li", {}, `Monthly challenges: +${r.challengeXp} XP each, on top of the daily cap.`)),
    h("p", { class: "small" }, "Total XP for each level: " + [2, 3, 5, 10, 15, 25, 40].map((l) => `Lv ${l}: ${xpForLevel(l)} (${levelTitle(l)})`).join(", ") + "."),
    h("p", { class: "muted small" }, "XP counts outside incognito and invisible mode. Friends see your stats while you share them.")));
}

/** A shareable PNG of my stats, drawn on a canvas (text only: cross-origin covers would taint it). */
async function shareStats(): Promise<void> {
  const xp = state.xp;
  if (!xp) return;
  const level = levelFor(xp.total), unlocked = BADGES.filter(([id]) => id in xp.badges).length;
  const canvas = document.createElement("canvas");
  canvas.width = 1200; canvas.height = 630;
  const g = canvas.getContext("2d");
  if (!g) throw new Error("This browser can't draw the image.");
  const background = g.createLinearGradient(0, 0, 1200, 630);
  background.addColorStop(0, "#4a5aa8"); background.addColorStop(1, "#252c5c");
  g.fillStyle = background; g.fillRect(0, 0, 1200, 630);
  const font = (size: number, weight = 400) => `${weight} ${size}px system-ui, -apple-system, "Segoe UI", Roboto, sans-serif`;
  const fit = (text: string, size: number, weight: number, width: number) => {
    g.font = font(size, weight);
    let shown = text;
    while (shown.length > 1 && g.measureText(shown).width > width) shown = shown.slice(0, -1);
    return shown === text ? text : `${shown.trimEnd()}…`;
  };
  g.fillStyle = "rgba(255,255,255,.75)"; g.font = font(32, 600); g.fillText("Mihon Together", 72, 100);
  g.fillStyle = "#ffffff"; g.fillText(fit(state.profile?.displayName ?? "", 84, 700, 1056), 72, 210);
  g.font = font(44, 500); g.fillStyle = "#c5cdff"; g.fillText(`Level ${level} · ${levelTitle(level)}`, 72, 285);
  const blocks: Array<[string, string]> = [[`🔥 ${xp.streak}`, "day streak"], [String(xp.chapters), "chapters"], [`${unlocked}/${BADGES.length}`, "achievements"]];
  blocks.forEach(([value, label], i) => {
    const x = 72 + i * 360;
    g.fillStyle = "rgba(255,255,255,.12)"; g.fillRect(x, 360, 320, 180);
    g.fillStyle = "#ffffff"; g.font = font(72, 700); g.fillText(value, x + 28, 460);
    g.fillStyle = "rgba(255,255,255,.75)"; g.font = font(30); g.fillText(label, x + 28, 510);
  });
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/png"));
  if (!blob) throw new Error("Couldn't create the image.");
  const file = new File([blob], "mihon-together-stats.png", { type: "image/png" });
  if (typeof navigator.canShare === "function" && navigator.canShare({ files: [file] })) {
    try {
      await navigator.share({ files: [file], title: "My Mihon Together stats" });
      return;
    } catch (error) {
      if ((error as Error)?.name === "AbortError") return;
      // Not allowed (e.g. the click is too long ago): fall back to a download.
    }
  }
  const url = URL.createObjectURL(blob);
  const anchor = h("a", { href: url, download: file.name });
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

/** Public download page: the Android app, how to install it, and its checksum. */
interface PatchNotes { title: string; items: string[] }
/** Loaded once per visit: the page redraws often, and refetching made it flicker. */
const GITHUB_URL = "https://github.com/berti12423/MihonTogether";
let downloadInfo: Promise<{ file: string; mb: number; sha256: string; built: string; version?: string; url?: string } | null> | null = null;
/** The APK link: the GitHub release asset when apk.json names one (only that host), else this site's copy. */
function apkHref(info: { file: string; url?: string }): string {
  return info.url && /^https:\/\/github\.com\/[A-Za-z0-9-]+\/[A-Za-z0-9._-]+\/releases\/download\/[A-Za-z0-9._-]+\/[A-Za-z0-9._-]+\.apk$/.test(info.url)
    ? info.url : `/files/${info.file}`;
}
let patchNotes: Promise<PatchNotes[]> | null = null;
function loadDownloadInfo() {
  downloadInfo ??= fetch("/files/apk.json").then((r) => (r.ok ? r.json() : null)).catch(() => null);
  return downloadInfo;
}
function loadPatchNotes(): Promise<PatchNotes[]> {
  patchNotes ??= fetch("/files/patch-notes.json").then((r) => (r.ok ? r.json() : [])).catch(() => []);
  return patchNotes;
}
function notesSection(notes: PatchNotes, open: boolean): HTMLElement {
  return h("details", open ? { class: "notes", open: "" } : { class: "notes" },
    h("summary", {}, notes.title), h("ul", {}, ...notes.items.map((item) => h("li", {}, item))));
}

/** Public download page: the Android app, the newest patch notes, how to install it, and its checksum. */
function downloadView(): HTMLElement {
  return cachedPage("/download", (container) => {
    const box = h("div", {}, h("p", { class: "muted" }, "Loading…"));
    const latest = h("div", {});
    loadDownloadInfo().then((info) => {
      replace(box, info
        ? h("div", {},
          h("a", { class: "button", href: apkHref(info), download: info.file }, `Download for Android (${info.mb.toFixed(1)} MB)`),
          h("p", { class: "muted small" }, `${info.version ? `Version ${info.version}, built` : "Built"} ${info.built}. SHA-256: `, h("code", { class: "hash" }, info.sha256)))
        : h("p", { class: "muted" }, "The download isn't available right now. Ask the friend who invited you for the APK."));
    });
    loadPatchNotes().then((notes) => {
      if (notes.length) replace(latest, h("h3", {}, "What's new"), notesSection(notes[0], true), link("/patch-notes", "All patch notes"));
    });
    replace(container, h("section", { class: "card narrow" },
      h("h2", {}, "Get Mihon Together"),
      h("p", {}, "Read manga with friends: read together in rooms, chat, share your library, and see what friends are reading. Based on Mihon."),
      box,
      latest,
      h("h3", {}, "Installing"),
      h("ol", {},
        h("li", {}, "Open the downloaded file on your Android phone."),
        h("li", {}, "If Android asks, allow installing apps from your browser or files app."),
        h("li", {}, "Open Mihon Together → More → Friends to create your account. Sharing with friends starts on; you can turn anything off.")),
      h("h3", {}, "Read on your computer"),
      h("p", {}, "Sign in here with the same account and open ", link("/my-library", "My library"),
        ": this website connects straight to your phone and shows its library, chapters and pages. Keep the app open on the phone, best on the same Wi-Fi."),
      h("p", { class: "muted small" }, "This is a test build, installed next to Mihon (it doesn't replace it). The app tells you when a new version is out. Made by Berti.")));
    return () => undefined;
  });
}

/** Every release's notes, newest first. */
function patchNotesView(): HTMLElement {
  return cachedPage("/patch-notes", (container) => {
    replace(container, h("section", { class: "card narrow" }, h("h2", {}, "Patch notes"), h("p", { class: "muted" }, "Loading…")));
    loadPatchNotes().then((notes) => replace(container, h("section", { class: "card narrow" },
      h("h2", {}, "Patch notes"),
      notes.length ? null : h("p", { class: "muted" }, "No patch notes yet."),
      ...notes.map((section, i) => notesSection(section, i === 0)),
      link("/download", "Download the app"))));
    return () => undefined;
  });
}

/**
 * Redraws at most once per frame: friend statuses, chats and rankings arrive in bursts, and redrawing for each one
 * rebuilt the page dozens of times a second (the PC flicker).
 */
let scheduled = false;
function render(): void {
  if (scheduled) return;
  scheduled = true;
  let frame = 0;
  const run = () => { if (!scheduled) return; scheduled = false; cancelAnimationFrame(frame); renderNow(); };
  // Next frame normally; the timer covers tabs that don't paint (hidden, minimized or covered windows).
  frame = requestAnimationFrame(run);
  setTimeout(run, 100);
}

/**
 * The page frame stays in place; only what changed is swapped. A cached page (download, patch notes, chats, grids)
 * that is already showing isn't touched at all.
 */
const layout = {
  header: h("header", {}),
  // A persistent live region, so screen readers announce notices and errors as they appear.
  notice: h("div", { role: "status", "aria-live": "polite" }),
  body: h("main", { class: "body" }),
  footer: h("footer", { class: "credit" },
    h("div", {}, "Mihon Together · made by Berti · open source, based on ", h("a", { href: "https://github.com/mihonapp/mihon", rel: "noopener" }, "Mihon"),
      " (", h("a", { href: `${GITHUB_URL}/blob/main/LICENSE`, rel: "noopener" }, "Apache 2.0"), ")"),
    h("div", {}, h("a", { href: "/download" }, "Download"), " · ", link("/patch-notes", "Patch notes"), " · ", h("a", { href: "/privacy.html" }, "Privacy"),
      " · ", h("a", { href: "/terms.html" }, "Terms"), " · ", h("a", { href: GITHUB_URL, rel: "noopener" }, "GitHub"), " · ", h("a", { href: `${GITHUB_URL}/issues`, rel: "noopener" }, "Contact")),
    h("div", { class: "small" }, "Independent project, not affiliated with Mihon or Tachiyomi. Mihon Together hosts no manga or other reading content.")),
};
let headerKey = "", noticeKey = "";
function renderNow(): void {
  // Before the typing check: the tab title and notifications shouldn't wait for an input to lose focus.
  updateAttention();
  const active = document.activeElement;
  if (active instanceof HTMLInputElement && root.contains(active) && active.type !== "checkbox") {
    renderPending = true;
    return;
  }
  renderPending = false;
  const path = location.pathname;
  if (!path.startsWith("/friend/") || path.split("/").length > 3) friendPage = null;
  if (page && page.path !== path) { page.stop(); page = null; }
  const signedIn = !!state.user;
  const unread = unreadMessages();
  // The open-source repository, as an icon at the end of the menu (like mihon.app).
  const githubLink = () => h("a", { class: "gh-link", href: GITHUB_URL, target: "_blank", rel: "noopener", title: "Mihon Together on GitHub", "aria-label": "Mihon Together on GitHub" });
  // The active page's link is marked for screen readers (and styled).
  const navLink = (target: string, label: string) => {
    const element = link(target, label);
    if (path === target || (target === "/my-library" && path.startsWith("/my-library/"))) element.setAttribute("aria-current", "page");
    return element;
  };
  const nav = signedIn ? h("nav", { "aria-label": "Main" }, navLink("/", "Friends"), navLink("/activity", "Activity"), navLink("/library", "Shared library"),
    navLink("/my-library", "My library"),
    navLink("/messages", unread ? `Messages (${unread})` : "Messages"), navLink("/privacy-settings", "Privacy"), navLink("/account", "Account"),
    navLink("/patch-notes", "Patch notes"), h("a", { href: "/privacy.html" }, "Policy"), githubLink())
    : h("nav", { "aria-label": "Main" }, navLink("/download", "Download"), navLink("/patch-notes", "Patch notes"), h("a", { href: "/privacy.html" }, "Privacy policy"), githubLink());
  const invite = inviteHandle(path);
  if (!invite) state.showSignIn = false;
  const inMyLibrary = signedIn && /^\/my-library(\/|$)/.test(path);
  if (!inMyLibrary) leaveWebLibrary();
  let body: HTMLElement;
  if (!knownRoute(path)) body = notFoundView();
  else if (path === "/download") body = downloadView();
  else if (path === "/patch-notes") body = patchNotesView();
  else if (!signedIn) body = invite && !state.showSignIn ? inviteLandingView(invite) : signInView();
  else if (!state.sharing) body = h("p", { class: "muted" }, "Loading…");
  else if (!state.profile) body = profileSetupView();
  else if (/^\/friend\/[^/]+\/(library|favorites)$/.test(path)) body = friendGrid(path);
  else if (path.startsWith("/friend/")) body = friendView(path.slice("/friend/".length));
  else if (path === "/library") body = myLibraryView();
  else if (inMyLibrary) body = myLibraryPage(state.user!.uid, path);
  else if (path === "/messages") body = messagesView();
  else if (path.startsWith("/chat/")) body = chatView(path.slice("/chat/".length));
  else if (path.startsWith("/group/")) body = groupView(path.slice("/group/".length));
  else if (path.startsWith("/room/")) body = roomView(path.slice("/room/".length));
  else if (path === "/privacy-settings") body = privacyView();
  else if (path === "/account") body = accountView();
  else if (path === "/activity") body = state.loaded.has("friends") ? activityView() : h("p", { class: "muted" }, "Loading…");
  else if (path === "/stats") body = statsView();
  else body = homeView();
  if (layout.header.parentNode !== root) replace(root, layout.header, layout.notice, layout.body, layout.footer);
  const nextHeader = `${signedIn}|${unread}|${path}`;
  if (nextHeader !== headerKey) { headerKey = nextHeader; replace(layout.header, h("h1", {}, link("/", "Mihon Together")), nav); }
  const nextNotice = `${usingEmulators}|${state.message?.error}|${state.message?.text ?? ""}`;
  if (nextNotice !== noticeKey) {
    noticeKey = nextNotice;
    replace(layout.notice,
      usingEmulators ? h("p", { class: "banner-note" }, "Local emulator mode") : null,
      state.message ? h("p", { class: state.message.error ? "error" : "notice" }, state.message.text) : null);
  }
  if (layout.body.firstChild !== body || layout.body.childNodes.length !== 1) replace(layout.body, body);
}

renderNow();
