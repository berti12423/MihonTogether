import { httpsCallable } from "firebase/functions";
import {
  collection, doc, documentId, getDoc, getDocFromServer, getDocs, limit, onSnapshot, orderBy, query, Timestamp, where,
} from "firebase/firestore";
import { getBytes, ref, uploadBytes } from "firebase/storage";
import { auth, db, functions, storage } from "./firebase";
import { OpenRoom, parseOpenRoom, parseSharing, Sharing, statsDay } from "./logic";

export interface Media { version: number; contentType: string }
export interface Profile {
  uid: string; handle: string; displayName: string; bio: string; accent: number; avatar: Media | null; banner: Media | null;
}
export interface Content { contentKey: string; title: string }
export interface FeedItem {
  kind: string; title: string; chapterLabel: string | null; createdAt: number;
  /** For the cover: the title's key, its source cover link, whether the owner uploaded a thumbnail, tracker IDs. */
  contentKey: string; coverUrl: string | null; thumb: boolean; anilistId: number | null; malId: number | null;
}
export interface Request { id: string; senderUid: string; recipientUid: string; name: string | null; handle: string | null }

const uuid = () => crypto.randomUUID();
async function call<T = Record<string, unknown>>(name: string, data: Record<string, unknown>): Promise<T> {
  return (await httpsCallable(functions, name)(data)).data as T;
}
const mutation = (fields: Record<string, unknown>) => ({ operationId: uuid(), ...fields });
const millis = (value: unknown): number => (value instanceof Timestamp ? value.toMillis() : Number(value ?? 0));

function media(raw: unknown): Media | null {
  if (!raw || typeof raw !== "object") return null;
  const data = raw as Record<string, unknown>;
  return typeof data.contentType === "string" ? { version: millis(data.version), contentType: data.contentType } : null;
}
export function toProfile(raw: Record<string, unknown>): Profile {
  return {
    uid: String(raw.uid), handle: String(raw.handle), displayName: String(raw.displayName),
    bio: typeof raw.bio === "string" ? raw.bio : "", accent: Number(raw.accent ?? 0),
    avatar: media(raw.avatar), banner: media(raw.banner),
  };
}

export const me = () => auth.currentUser?.uid ?? null;
export const authInstance = () => auth;

export async function getSelf(): Promise<{ profile: Profile | null; sharing: Sharing }> {
  const result = await call<{ profile: Record<string, unknown> | null; settings: Record<string, unknown> }>("socialGetSelf", {});
  return { profile: result.profile ? toProfile(result.profile) : null, sharing: parseSharing(result.settings) };
}
export async function saveProfile(handle: string, displayName: string): Promise<Profile> {
  return toProfile((await call<{ profile: Record<string, unknown> }>("socialUpdateProfile", mutation({ handle, displayName }))).profile);
}
export async function updateSharing(current: Sharing, settings: Record<string, unknown>): Promise<Sharing> {
  const result = await call<{ settings: Record<string, unknown> }>("socialUpdateSharing",
    mutation({ expectedGeneration: current.generation, settings, hideContentKey: null }));
  return parseSharing(result.settings);
}
/** [viaInvite]: the handle came from that person's invite link (counts toward their recruiter badges). */
export async function requestFriend(handle: string, viaInvite = false): Promise<void> {
  const target = await call<{ profile: Record<string, unknown> }>("socialLookupHandle", { handle });
  await call("socialFriendAction", mutation({ action: "request", otherUid: String(target.profile.uid), ...(viaInvite ? { via: "invite" } : {}) }));
}
export const friendAction = (action: string, otherUid: string) => call("socialFriendAction", mutation({ action, otherUid }));
export const deleteAccount = () => call("socialDeleteAccount", { confirm: "DELETE" });

/** Profile look, as in the app: the same limits as storage.rules and the backend (GIFs allowed). */
export const PROFILE_MEDIA_TYPES = ["image/png", "image/jpeg", "image/webp", "image/gif"];
export const PROFILE_MEDIA_MAX = { avatar: 2 * 1024 * 1024, banner: 5 * 1024 * 1024 } as const;
export type MediaAction = "keep" | "set" | "remove";
/** Uploads my avatar or banner; `updateProfileStyle` with "set" then publishes it. */
export async function uploadProfileMedia(kind: "avatar" | "banner", file: File): Promise<void> {
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error("Sign in first.");
  await uploadBytes(ref(storage, `profileMedia/${uid}/${kind}`), file, { contentType: file.type });
}
export async function updateProfileStyle(bio: string, accent: number, avatar: MediaAction, banner: MediaAction): Promise<Profile> {
  const result = await call<{ profile: Record<string, unknown> }>("socialUpdateProfileStyle", mutation({ bio, accent, avatar, banner }));
  return toProfile(result.profile);
}

export async function getFriend(uid: string): Promise<{ profile: Profile; sharing: Sharing }> {
  const result = await call<{ profile: Record<string, unknown>; sharing: Record<string, unknown> }>("socialGetFriend", { otherUid: uid });
  return { profile: toProfile(result.profile), sharing: parseSharing(result.sharing) };
}

/** Accepted friend uids, live. Cache-only snapshots are skipped (unknown), like the app's revocation flow. */
export function watchFriendIds(uid: string, onIds: (ids: string[]) => void, onError: (e: Error) => void): () => void {
  return onSnapshot(collection(db, `friendships/${uid}/accepted`), { includeMetadataChanges: true }, (snapshot) => {
    if (!snapshot.metadata.fromCache) onIds(snapshot.docs.map((d) => d.id));
  }, onError);
}

export function watchRequests(uid: string, incoming: boolean, onRequests: (requests: Request[]) => void): () => void {
  const field = incoming ? "recipientUid" : "senderUid";
  return onSnapshot(query(collection(db, "friendRequests"), where(field, "==", uid), where("state", "==", "pending"), limit(50)), (snapshot) => {
    onRequests(snapshot.docs.map((d) => {
      const data = d.data();
      return {
        id: d.id, senderUid: data.senderUid, recipientUid: data.recipientUid,
        name: (incoming ? data.senderDisplayName : data.recipientDisplayName) ?? null,
        handle: (incoming ? data.senderHandle : data.recipientHandle) ?? null,
      };
    }));
  }, () => onRequests([]));
}

export async function getProfile(uid: string): Promise<Profile | null> {
  const snapshot = await getDoc(doc(db, `profiles/${uid}`));
  return snapshot.exists() ? toProfile(snapshot.data()) : null;
}

/** Live status fields a friend shares; denied reads simply report null. */
export function watchStatus(uid: string, onStatus: (patch: Partial<Status>) => void): () => void {
  const stops = [
    onSnapshot(doc(db, `presence/${uid}`), (s) => onStatus({ online: s.exists() ? s.data().state === "online" : null }), () => onStatus({ online: null })),
    onSnapshot(doc(db, `lastSeen/${uid}`), (s) => onStatus({ lastSeen: s.exists() ? millis(s.data().lastSeenAt) : null }), () => onStatus({ lastSeen: null })),
    onSnapshot(doc(db, `activities/${uid}`), (s) => {
      const data = s.exists() ? s.data() : null;
      const active = data && millis(data.expiresAt) > Date.now();
      onStatus({ reading: active ? String(data.content?.title ?? "") : null });
    }, () => onStatus({ reading: null })),
    // Open room card: readable while they share activity with me and let friends drop in.
    onSnapshot(doc(db, `openRooms/${uid}`), (s) => onStatus({ openRoom: s.exists() ? parseOpenRoom(uid, s.data(), millis) : null }),
      () => onStatus({ openRoom: null })),
  ];
  return () => stops.forEach((stop) => stop());
}
export interface Status { online: boolean | null; lastSeen: number | null; reading: string | null; openRoom?: OpenRoom | null }

export async function loadEntries(uid: string, kind: "library" | "favorites", generation: number): Promise<Content[]> {
  const collectionName = kind === "library" ? "sharedLibraries" : "sharedFavorites";
  const snapshot = await getDocs(query(collection(db, `${collectionName}/${uid}/entries`),
    where("generation", "==", generation), orderBy(documentId()), limit(100)));
  return snapshot.docs.map((d) => ({ contentKey: d.id, title: String(d.data().content?.title ?? "") }));
}

export async function loadFeed(uid: string, generation: number): Promise<FeedItem[]> {
  // Rules must prove `expiresAt > request.time`, so the bound sits an hour ahead (same as the app).
  const snapshot = await getDocs(query(collection(db, `feeds/${uid}/items`), where("generation", "==", generation),
    where("expiresAt", ">", Timestamp.fromMillis(Date.now() + 3_600_000)), orderBy("expiresAt", "desc"), limit(20)));
  return snapshot.docs.map((d) => {
    const data = d.data(), content = (data.content ?? {}) as Record<string, unknown>;
    const canonical = (provider: string) => (content.canonicalProvider === provider ? Number(content.canonicalId) || null : null);
    return { kind: String(data.kind), title: String(content.title ?? ""), chapterLabel: data.chapterLabel ?? null, createdAt: millis(data.createdAt),
      contentKey: String(content.contentKey ?? ""), coverUrl: typeof content.coverUrl === "string" ? content.coverUrl : null,
      thumb: content.thumb === true, anilistId: canonical("anilist"), malId: canonical("mal") };
  });
}

export async function loadDailyStats(uid: string): Promise<{ chapters: number; pages: number; streak: number } | null> {
  try {
    const snapshot = await getDocFromServer(doc(db, `dailyStats/${uid}/days/${statsDay()}`));
    if (!snapshot.exists()) return null;
    return { chapters: Number(snapshot.data().chapters ?? 0), pages: Number(snapshot.data().pages ?? 0), streak: Number(snapshot.data().streak ?? 1) };
  } catch {
    return null;
  }
}

/** My own chapters per UTC day for the last [days] days (the docs expire after 8), keyed yyyymmdd; owner-readable. */
export async function loadMyDays(uid: string, days = 8): Promise<Record<string, number>> {
  const ids = Array.from({ length: days }, (_, i) => statsDay(Date.now() - i * 86_400_000));
  const snapshots = await Promise.all(ids.map((day) => getDoc(doc(db, `dailyStats/${uid}/days/${day}`)).catch(() => null)));
  const result: Record<string, number> = {};
  snapshots.forEach((s, i) => { if (s?.exists()) result[ids[i]] = Number(s.data().chapters ?? 0); });
  return result;
}

const mediaUrls = new Map<string, Promise<string | null>>();
/** Friends-only media through authenticated reads (never public download URLs); GIFs animate natively. */
export function mediaUrl(profile: Profile, kind: "avatar" | "banner"): Promise<string | null> {
  const item = profile[kind];
  if (!item) return Promise.resolve(null);
  const key = `${profile.uid}/${kind}/${item.version}`;
  let url = mediaUrls.get(key);
  if (!url) {
    // Waits for sign-in so the read is authenticated; failures aren't remembered. Browsers also need the bucket's
    // CORS setting (backend/storage-cors.json) or they drop the downloaded image.
    url = auth.authStateReady()
      .then(() => getBytes(ref(storage, `profileMedia/${profile.uid}/${kind}`)))
      .then((bytes) => URL.createObjectURL(new Blob([bytes], { type: item.contentType })))
      .catch(() => { mediaUrls.delete(key); return null; });
    mediaUrls.set(key, url);
  }
  return url;
}
