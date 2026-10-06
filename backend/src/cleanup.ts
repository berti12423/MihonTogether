import { Firestore, Timestamp } from "firebase-admin/firestore";
import { SocialService } from "./domain.js";

/** Closes rooms whose lease expired without a close; returns how many were closed. Scheduled by `socialCleanup`. */
export async function closeExpiredRooms(db: Firestore, service: SocialService, now = Date.now(), limit = 200): Promise<number> {
  const stale = await db.collection("rooms")
    .where("state", "in", ["invited", "active"])
    .where("leaseExpiresAt", "<", Timestamp.fromMillis(now))
    .limit(limit)
    .get();
  let closed = 0;
  for (const room of stale.docs) if ((await service.expireRoom(room.id)).expired) closed++;
  return closed;
}

/** Blanks group-list previews whose message is older than 30 days (the message itself expires by TTL). */
export async function clearExpiredGroupPreviews(db: Firestore, now = Date.now(), limit = 500): Promise<number> {
  const stale = await db.collectionGroup("groups")
    .where("previewExpiresAt", "<", Timestamp.fromMillis(now))
    .limit(limit)
    .get();
  for (const entry of stale.docs) await entry.ref.update({ lastText: "", lastSenderName: "", previewExpiresAt: null });
  return stale.size;
}