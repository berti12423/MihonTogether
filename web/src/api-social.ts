// Web side of the October features (roadmap F2–F7 and W4): paged cover libraries, the global ranking, private
// messages, read-along requests and chat-only participation in reading rooms. Same callables and rules as the app.
import { httpsCallable } from "firebase/functions";
import {
  collection, doc, getCountFromServer, getDocFromServer, getDocs, limit, onSnapshot, orderBy, query, startAfter, Timestamp, where,
} from "firebase/firestore";
import { getBytes, ref } from "firebase/storage";
import { auth, db, functions, storage } from "./firebase";
import { parseProgress, parseReview, Progress, Review, statsDay, XpMonth } from "./logic";

const uuid = () => crypto.randomUUID();
async function call<T = Record<string, unknown>>(name: string, data: Record<string, unknown>): Promise<T> {
  return (await httpsCallable(functions, name)(data)).data as T;
}
const mutation = (fields: Record<string, unknown>) => ({ operationId: uuid(), ...fields });
const millis = (value: unknown): number => (value instanceof Timestamp ? value.toMillis() : Number(value ?? 0));

// ---------- covers and paged libraries (F3) ----------
export interface Entry { contentKey: string; title: string; coverUrl: string | null; thumb: boolean; anilistId: number | null; malId: number | null; progress: Progress | null }
export interface EntryPage { items: Entry[]; cursor: unknown | null }
const collectionFor = (kind: "library" | "favorites") => (kind === "library" ? "sharedLibraries" : "sharedFavorites");

/** One page of a shared library A–Z by title; pass the cursor back for the next page (null after the last). */
export async function loadEntriesPage(uid: string, kind: "library" | "favorites", generation: number, cursor: unknown | null, size = 60): Promise<EntryPage> {
  const constraints = [where("generation", "==", generation), orderBy("content.title"), ...(cursor ? [startAfter(cursor)] : []), limit(size)];
  const snapshot = await getDocs(query(collection(db, `${collectionFor(kind)}/${uid}/entries`), ...constraints));
  const items = snapshot.docs.map((d) => {
    const content = (d.data().content ?? {}) as Record<string, unknown>;
    const cover = typeof content.coverUrl === "string" && /^https:\/\/\S+$/.test(content.coverUrl) ? content.coverUrl : null;
    const tracked = (provider: string) => content.canonicalProvider === provider && /^[0-9]{1,12}$/.test(String(content.canonicalId)) ? Number(content.canonicalId) : null;
    return { contentKey: d.id, title: String(content.title ?? ""), coverUrl: cover, thumb: content.thumb === true,
      anilistId: tracked("anilist"), malId: tracked("mal"), progress: parseProgress(d.data().progress) };
  });
  return { items, cursor: snapshot.docs.length === size ? snapshot.docs[snapshot.docs.length - 1] : null };
}

export async function countEntries(uid: string, kind: "library" | "favorites", generation: number): Promise<number | null> {
  try {
    const counted = await getCountFromServer(query(collection(db, `${collectionFor(kind)}/${uid}/entries`), where("generation", "==", generation)));
    return counted.data().count;
  } catch {
    return null;
  }
}

/** An uploaded cover thumbnail (friends-only), as an object URL; null when missing or not allowed. */
const thumbUrls = new Map<string, Promise<string | null>>();
export function coverThumb(uid: string, contentKey: string): Promise<string | null> {
  const key = `${uid}/${contentKey}`;
  let url = thumbUrls.get(key);
  if (!url) {
    // After sign-in is ready (an anonymous read is refused); failures are retried. Needs the bucket CORS setting.
    url = auth.authStateReady()
      .then(() => getBytes(ref(storage, `coverThumbs/${uid}/${contentKey}`)))
      .then((bytes) => URL.createObjectURL(new Blob([bytes], { type: "image/jpeg" })))
      .catch(() => { thumbUrls.delete(key); return null; });
    thumbUrls.set(key, url);
  }
  return url;
}
/** Cover fallbacks for one entry of [uid]'s shared list. */
export function entryFallbacks(uid: string, entry: Entry) {
  return { thumb: entry.thumb ? () => coverThumb(uid, entry.contentKey) : undefined, anilistId: entry.anilistId, malId: entry.malId };
}

// ---------- ratings and reviews (written in the app) ----------
/** A friend's reviews, newest first (rules: friends while their reviews switch is on and they're visible). */
export async function loadReviews(uid: string): Promise<Review[]> {
  const snapshot = await getDocs(query(collection(db, `reviews/${uid}/items`), orderBy("updatedAt", "desc"), limit(200)));
  return snapshot.docs.map((d) => parseReview(d.data(), millis)).filter((review): review is Review => review !== null);
}

// ---------- global ranking (F2) ----------
type BoardMedia = { version: number; contentType: string } | null;
export interface BoardEntry {
  uid: string; handle: string; displayName: string; accent: number; chapters: number; streak: number; level?: number; xp?: number;
  avatar: BoardMedia; banner: BoardMedia;
}
function boardMedia(value: unknown): BoardMedia {
  const m = value as Record<string, unknown> | null | undefined;
  return m && typeof m.version === "number" && typeof m.contentType === "string" ? { version: m.version, contentType: m.contentType } : null;
}
/** Today's board, or the all-time one ("alltime": by total XP, chapters are lifetime). */
export async function loadLeaderboard(board: string = statsDay()): Promise<BoardEntry[]> {
  const snapshot = await getDocFromServer(doc(db, `leaderboards/${board}`)).catch(() => null);
  const entries = (snapshot?.exists() ? snapshot.data().entries : null) as Record<string, Record<string, unknown>> | null;
  return Object.entries(entries ?? {}).map(([uid, e]) => ({
    uid, handle: String(e.handle ?? ""), displayName: String(e.displayName ?? ""), accent: Number(e.accent ?? 0),
    chapters: Number(e.chapters ?? 0), streak: Number(e.streak ?? 1), level: typeof e.level === "number" ? e.level : undefined,
    xp: typeof e.xp === "number" ? e.xp : undefined, avatar: boardMedia(e.avatar), banner: boardMedia(e.banner),
  }));
}
export const requestFriendUid = (uid: string) => call("socialFriendAction", mutation({ action: "request", otherUid: uid }));
export function watchBlocks(uid: string, onIds: (ids: string[]) => void): () => void {
  return onSnapshot(collection(db, `blocks/${uid}/users`), (s) => onIds(s.docs.map((d) => d.id)), () => onIds([]));
}

// ---------- private messages (F5) ----------
export interface ChatSummary { otherUid: string; chatId: string; lastText: string; lastSenderUid: string; updatedAt: number; unread: number }
/** A recommended title inside a message. */
export interface SharedTitle { title: string; coverUrl: string | null; source: string | null }
function sharedTitle(value: unknown): SharedTitle | null {
  const c = value as Record<string, unknown> | null | undefined;
  if (!c || typeof c.title !== "string") return null;
  const cover = typeof c.coverUrl === "string" && /^https:\/\/\S+$/.test(c.coverUrl) ? c.coverUrl : null;
  const source = (c.source as Record<string, unknown> | undefined)?.name;
  return { title: c.title, coverUrl: cover, source: typeof source === "string" ? source : null };
}
/** The quote a reply carries (server-written): the original's seq, sender and first 100 characters. */
export interface ReplyQuote { seq: number; senderUid: string; senderName: string | null; preview: string }
function replyQuote(value: unknown): ReplyQuote | null {
  const q = value as Record<string, unknown> | null | undefined;
  if (!q || typeof q.seq !== "number" || typeof q.senderUid !== "string" || typeof q.preview !== "string") return null;
  return { seq: q.seq, senderUid: q.senderUid, senderName: typeof q.senderName === "string" ? q.senderName : null, preview: q.preview };
}
/** Reactions on a message: one emoji per person (`{uid: emoji}`). */
function reactionMap(value: unknown): Record<string, string> {
  if (!value || typeof value !== "object") return {};
  return Object.fromEntries(Object.entries(value as Record<string, unknown>).filter((e): e is [string, string] => typeof e[1] === "string"));
}
export interface ChatMessage { seq: number; senderUid: string; text: string; createdAt: number; content: SharedTitle | null;
  replyTo: ReplyQuote | null; reactions: Record<string, string>; system: string | null }
export function watchChats(uid: string, onChats: (chats: ChatSummary[]) => void): () => void {
  return onSnapshot(query(collection(db, `chatIndex/${uid}/chats`), orderBy("updatedAt", "desc"), limit(50)), (snapshot) => {
    onChats(snapshot.docs.map((d) => {
      const data = d.data();
      return {
        otherUid: String(data.otherUid), chatId: String(data.chatId), lastText: String(data.lastText ?? ""),
        lastSenderUid: String(data.lastSenderUid ?? ""), updatedAt: millis(data.updatedAt), unread: Number(data.unread ?? 0),
      };
    }));
  }, () => onChats([]));
}
export function watchChatMessages(chatId: string, onMessages: (messages: ChatMessage[]) => void): () => void {
  return onSnapshot(query(collection(db, `chats/${chatId}/messages`), orderBy("seq", "desc"), limit(100)), (snapshot) => {
    onMessages(snapshot.docs.map((d) => {
      const data = d.data();
      return { seq: Number(data.seq), senderUid: String(data.senderUid), text: String(data.text ?? ""), createdAt: millis(data.createdAt), content: sharedTitle(data.content),
        replyTo: replyQuote(data.replyTo), reactions: reactionMap(data.reactions), system: typeof data.system === "string" ? data.system : null };
    }).sort((a, b) => a.seq - b.seq));
  }, () => onMessages([]));
}
// ---------- XP ----------
export interface Xp { total: number; chapters: number; together: number; bestStreak: number; streak: number; freezes: number;
  badges: Record<string, number>; week: string; weekXp: number; weekChapters: number; days: number; bestDay: number;
  /** Monthly challenge counters (null on older docs) and completed challenge IDs by "yyyy-mm". */
  month: XpMonth | null; challenges: Record<string, string[]> }
/** My XP, or a friend's while they share stats; null when missing or not readable. */
export function watchXp(uid: string, onXp: (xp: Xp | null) => void): () => void {
  return onSnapshot(doc(db, `xp/${uid}`), (s) => {
    const d = s.data();
    onXp(d ? parseXp(d) : null);
  }, () => onXp(null));
}

function parseXp(d: Record<string, unknown>): Xp {
  return { total: Number(d.total ?? 0), chapters: Number(d.chapters ?? 0), together: Number(d.together ?? 0), bestStreak: Number(d.bestStreak ?? 0),
    streak: Number(d.streak ?? 0), freezes: Number(d.freezes ?? 0), badges: (d.badges ?? {}) as Record<string, number>,
    week: String(d.week ?? ""), weekXp: Number(d.weekXp ?? 0), weekChapters: Number(d.weekChapters ?? 0), days: Number(d.days ?? 0), bestDay: Number(d.bestDay ?? 0),
    month: parseMonth(d.month), challenges: parseChallenges(d.challenges) };
}
function parseMonth(raw: unknown): XpMonth | null {
  if (!raw || typeof raw !== "object" || typeof (raw as { id?: unknown }).id !== "string") return null;
  const m = raw as Record<string, unknown>, n = (key: string) => Number(m[key] ?? 0);
  return { id: String(m.id), chapters: n("chapters"), days: n("days"), together: n("together"), streak: n("streak"), bestDay: n("bestDay"), xp: n("xp") };
}
function parseChallenges(raw: unknown): Record<string, string[]> {
  if (!raw || typeof raw !== "object") return {};
  return Object.fromEntries(Object.entries(raw as Record<string, unknown>)
    .map(([month, ids]) => [month, Array.isArray(ids) ? ids.filter((id): id is string => typeof id === "string") : []]));
}
/** A friend's XP (readable while they share stats), or null. */
export async function loadXp(uid: string): Promise<Xp | null> {
  const snapshot = await getDocFromServer(doc(db, `xp/${uid}`)).catch(() => null);
  return snapshot?.exists() ? parseXp(snapshot.data()) : null;
}

// ---------- group chats ----------
export interface GroupSummary { groupId: string; name: string; members: number; lastText: string; lastSenderName: string; unread: number; updatedAt: number }
export interface GroupMember { uid: string; name: string; accent: number; since: number }
export interface Group { groupId: string; name: string; ownerUid: string; members: GroupMember[] }
export interface GroupLine { seq: number; kind: string; senderUid: string; senderName: string; text: string; event: string | null; createdAt: number; content: SharedTitle | null;
  replyTo: ReplyQuote | null; reactions: Record<string, string> }
const group = (action: string, fields: Record<string, unknown>) =>
  call("socialGroupChat", mutation({ action, groupId: null, name: null, uids: null, text: null, ...fields }));
export function watchGroups(uid: string, onGroups: (groups: GroupSummary[]) => void): () => void {
  return onSnapshot(query(collection(db, `groupIndex/${uid}/groups`), orderBy("updatedAt", "desc"), limit(50)), (snapshot) => {
    onGroups(snapshot.docs.map((d) => {
      const data = d.data();
      return { groupId: String(data.groupId), name: String(data.name ?? ""), members: Number(data.members ?? 0), lastText: String(data.lastText ?? ""),
        lastSenderName: String(data.lastSenderName ?? ""), unread: Number(data.unread ?? 0), updatedAt: millis(data.updatedAt) };
    }));
  }, () => onGroups([]));
}
export function watchGroup(groupId: string, onGroup: (group: Group | null) => void): () => void {
  return onSnapshot(doc(db, `groups/${groupId}`), (s) => {
    const d = s.data();
    if (!d) { onGroup(null); return; }
    const names = (d.names ?? {}) as Record<string, Record<string, unknown>>;
    onGroup({ groupId, name: String(d.name ?? ""), ownerUid: String(d.ownerUid ?? ""),
      members: ((d.members ?? []) as string[]).map((uid) => ({ uid, name: String(names[uid]?.name ?? ""), accent: Number(names[uid]?.accent ?? 0), since: Number(names[uid]?.since ?? 0) })) });
  }, () => onGroup(null));
}
/** Messages from when I joined ([since]); the rules allow nothing earlier. */
export function watchGroupLines(groupId: string, since: number, onLines: (lines: GroupLine[]) => void): () => void {
  return onSnapshot(query(collection(db, `groups/${groupId}/messages`), where("seq", ">=", since), orderBy("seq", "desc"), limit(100)), (snapshot) => {
    onLines(snapshot.docs.map((d) => {
      const data = d.data();
      return { seq: Number(data.seq), kind: String(data.kind), senderUid: String(data.senderUid ?? ""), senderName: String(data.senderName ?? ""),
        text: String(data.text ?? ""), event: typeof data.event === "string" ? data.event : null, createdAt: millis(data.createdAt), content: sharedTitle(data.content),
        replyTo: replyQuote(data.replyTo), reactions: reactionMap(data.reactions) };
    }).sort((a, b) => a.seq - b.seq));
  }, () => onLines([]));
}
export const createGroup = async (name: string, uids: string[]) => (await group("create", { name, uids })).groupId as string;
/** [replyTo]: the seq of the message this answers (the server stores a quote of it). */
export const sendGroupLine = (groupId: string, text: string, replyTo: number | null = null) =>
  group("send", { groupId, text, ...(replyTo === null ? {} : { replyTo }) });
/** Sets, replaces or (the same emoji again) removes my reaction to a group message. */
export const reactInGroup = (groupId: string, seq: number, emoji: string) => group("react", { groupId, seq, emoji });
export const markGroupRead = (groupId: string) => group("read", { groupId });
export const leaveGroup = (groupId: string) => group("leave", { groupId });
export const sendMessage = (otherUid: string, text: string, replyTo: number | null = null) =>
  call("socialMessage", mutation({ action: "send", otherUid, text, ...(replyTo === null ? {} : { replyTo }) }));
/** Sets, replaces or (the same emoji again) removes my reaction to a private message. */
export const reactToMessage = (otherUid: string, seq: number, emoji: string) =>
  call("socialMessage", mutation({ action: "react", otherUid, text: null, seq, emoji }));
/** A light "keep reading" push to a friend; the server allows one per friend per 24 h. */
export const nudgeFriend = (otherUid: string) => call("socialNudge", mutation({ otherUid }));
export const markChatRead = (otherUid: string) => call("socialMessage", mutation({ action: "read", otherUid, text: null }));

// ---------- read-along requests (F7) ----------
export interface JoinRequest { requesterUid: string; name: string; handle: string; expiresAt: number }
export function watchJoinRequests(uid: string, onRequests: (requests: JoinRequest[]) => void): () => void {
  return onSnapshot(collection(db, `joinRequests/${uid}/items`), (snapshot) => {
    onRequests(snapshot.docs.map((d) => {
      const data = d.data();
      return { requesterUid: String(data.requesterUid), name: String(data.name ?? ""), handle: String(data.handle ?? ""), expiresAt: millis(data.expiresAt) };
    }).filter((r) => r.expiresAt > Date.now()));
  }, () => onRequests([]));
}
export const askToReadAlong = (otherUid: string) => call("socialJoinRequest", mutation({ action: "ask", otherUid }));
export const declineJoinRequest = (otherUid: string) => call("socialJoinRequest", mutation({ action: "decline", otherUid }));

// ---------- reading rooms from the browser (W4: a chat-only member; pages never show on the web) ----------
export interface RoomMember { uid: string; state: string; name: string; accent: number; joinedAt: number | null }
export interface Room {
  id: string; hostUid: string; participants: string[]; members: Record<string, RoomMember>; maxMembers: number;
  title: string; state: string; leaseExpiresAt: number; chapterLabel: string | null; page: string | null;
}
export interface RoomLine { seq: number; senderUid: string; kind: string; text: string | null; emoji: string | null; createdAt: number }
/** Must match ROOM_REACTIONS in backend/src/domain.ts (checked by the web tests). */
export const ROOM_REACTIONS = ["👍", "😂", "😮", "😢", "😡", "❤️", "🔥", "👏"];

function toRoom(id: string, data: Record<string, unknown>): Room {
  const members: Record<string, RoomMember> = {};
  for (const [uid, raw] of Object.entries((data.members ?? {}) as Record<string, Record<string, unknown>>)) {
    members[uid] = {
      uid, state: String(raw.state ?? "invited"), name: String(raw.name ?? ""), accent: Number(raw.accent ?? 0),
      joinedAt: raw.joinedAt == null ? null : millis(raw.joinedAt),
    };
  }
  const position = data.position as Record<string, unknown> | null;
  return {
    id, hostUid: String(data.hostUid), participants: (data.participants as string[]) ?? [], members, maxMembers: Number(data.maxMembers ?? 6),
    title: String((data.content as Record<string, unknown> | undefined)?.title ?? ""), state: String(data.state),
    leaseExpiresAt: millis(data.leaseExpiresAt),
    chapterLabel: position ? String(position.chapterLabel ?? "") : null,
    page: position ? `${Number(position.pageIndex) + 1}/${Number(position.pageCount)}` : null,
  };
}

/** Rooms I'm in or invited to, live; ended rooms drop out. */
export function watchRooms(uid: string, onRooms: (rooms: Room[]) => void): () => void {
  const roomStops = new Map<string, () => void>();
  const rooms = new Map<string, Room>();
  const emit = () => onRooms([...rooms.values()].filter((r) => r.state !== "closed" && r.leaseExpiresAt > Date.now()));
  const stopIndex = onSnapshot(query(collection(db, `roomInvites/${uid}/items`), where("state", "in", ["invited", "active"]), limit(20)), (snapshot) => {
    const ids = snapshot.docs.map((d) => d.id);
    for (const [id, stop] of roomStops) if (!ids.includes(id)) { stop(); roomStops.delete(id); rooms.delete(id); }
    for (const id of ids) {
      if (roomStops.has(id)) continue;
      roomStops.set(id, onSnapshot(doc(db, `rooms/${id}`), (s) => {
        if (s.exists()) rooms.set(id, toRoom(id, s.data())); else rooms.delete(id);
        emit();
      }, () => { rooms.delete(id); emit(); }));
    }
    emit();
  }, () => onRooms([]));
  return () => { stopIndex(); roomStops.forEach((stop) => stop()); };
}
export function watchRoomLines(roomId: string, onLines: (lines: RoomLine[]) => void): () => void {
  return onSnapshot(query(collection(db, `rooms/${roomId}/messages`), orderBy("seq", "desc"), limit(30)), (snapshot) => {
    onLines(snapshot.docs.map((d) => {
      const data = d.data();
      return {
        seq: Number(data.seq), senderUid: String(data.senderUid), kind: String(data.kind),
        text: (data.text as string | null) ?? null, emoji: (data.emoji as string | null) ?? null, createdAt: millis(data.createdAt),
      };
    }).sort((a, b) => a.seq - b.seq));
  }, () => onLines([]));
}
const roomInput = (action: string, roomId: string) => mutation({ action, roomId, otherUid: null, sessionId: null, content: null, position: null });
export const joinRoom = (roomId: string) => call("socialRoomAction", roomInput("join", roomId));
export const leaveRoom = (roomId: string) => call("socialRoomAction", roomInput("leave", roomId));
export const sendRoomLine = (roomId: string, text: string | null, emoji: string | null) =>
  call("socialRoomMessage", mutation({ roomId, kind: emoji ? "reaction" : "text", text: emoji ? null : text, emoji }));
