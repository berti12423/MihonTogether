import { Auth } from "firebase-admin/auth";
import { Database } from "firebase-admin/database";
import { FieldPath, FieldValue, Firestore, Timestamp } from "firebase-admin/firestore";
import { ALL_TIME_BOARD, CHAT_TTL, pairId, ROOM_MESSAGE_SLOTS, statsDay } from "./domain.js";

/** Owner-keyed subtrees removed wholesale (every document below them belongs to the user). */
const OWNED_TREES = ["sharedLibraries", "sharedFavorites", "feeds", "hiddenTitles", "operations", "friendships", "blocks", "roomInvites",
  "dailyStats", "chatIndex", "joinRequests", "groupIndex", "readWishIndex", "reviews"];
/** Owner-keyed single documents. */
const OWNED_DOCS = ["profiles", "accounts", "sharingSettings", "activities", "presence", "lastSeen", "feedState", "quotas",
  "rateLimits", "requestLimits", "roomLimits", "devices", "joinRequestLimits", "xp", "groupLimits", "readWishLimits",
  "reactionLimits", "reviewLimits", "nudgeLimits", "invites", "openRooms"];

/**
 * Deletes everything the backend stores about [uid], then the Auth user. Other users keep their own records
 * (e.g. their block list entry for this now-meaningless uid), but nothing that identifies or describes the user.
 * Order: revoke relationships first (so friends lose access immediately), then owned data, then Auth.
 */
export async function deleteAccount(db: Firestore, rtdb: Database, auth: Auth, uid: string): Promise<void> {
  const profile = (await db.doc(`profiles/${uid}`).get()).data();

  // Reciprocal friendship edges and both sides of pending/old requests.
  const friends = await db.collection(`friendships/${uid}/accepted`).get();
  for (const friend of friends.docs) await db.doc(`friendships/${friend.id}/accepted/${uid}`).delete();
  for (const field of ["senderUid", "recipientUid"]) {
    const requests = await db.collection("friendRequests").where(field, "==", uid).get();
    for (const request of requests.docs) await request.ref.delete();
  }

  // Rooms the user took part in: remove the room, its lease, chat and the other participant's index entry.
  const invites = await db.collection(`roomInvites/${uid}/items`).get();
  for (const invite of invites.docs) {
    const roomId = invite.id, room = (await db.doc(`rooms/${roomId}`).get()).data();
    for (const participant of (room?.participants as string[] | undefined) ?? []) {
      if (participant !== uid) await db.doc(`roomInvites/${participant}/items/${roomId}`).delete();
      await db.doc(`roomChatRates/${roomId}/users/${participant}`).delete();
    }
    const batch = db.batch();
    for (let slot = 0; slot < ROOM_MESSAGE_SLOTS; slot++) {
      batch.delete(db.doc(`rooms/${roomId}/messages/${String(slot).padStart(3, "0")}`));
    }
    batch.delete(db.doc(`roomChat/${roomId}`)); batch.delete(db.doc(`roomLeases/${roomId}`)); batch.delete(db.doc(`rooms/${roomId}`));
    await batch.commit();
  }

  // Global ranking entries (today's and yesterday's boards can still be shown, and the all-time board).
  for (const day of [statsDay(Date.now()), statsDay(Date.now() - 86_400_000), ALL_TIME_BOARD]) {
    const board = db.doc(`leaderboards/${day}`);
    if ((await board.get()).exists) await board.update(new FieldPath("entries", uid), FieldValue.delete());
  }

  // Private chats: the other person's list entry and the whole conversation go too.
  const chats = await db.collection(`chatIndex/${uid}/chats`).get();
  for (const chat of chats.docs) {
    await db.doc(`chatIndex/${chat.id}/chats/${uid}`).delete();
    await db.recursiveDelete(db.doc(`chats/${pairId(uid, chat.id)}`));
    await db.recursiveDelete(db.doc(`chatRates/${pairId(uid, chat.id)}`));
  }

  // Group chats: leave each one (ownership passes on; the last member out deletes it). Old messages from this user
  // keep only their display name until they expire, like messages in any group chat.
  const groups = await db.collection(`groupIndex/${uid}/groups`).get();
  for (const entry of groups.docs) {
    const ref = db.doc(`groups/${entry.id}`);
    await db.runTransaction(async (tx) => {
      const group = (await tx.get(ref)).data();
      if (!group) return;
      const members = (group.members as string[]).filter((member) => member !== uid);
      const indexes = await Promise.all(members.map((member) => tx.get(db.doc(`groupIndex/${member}/groups/${entry.id}`))));
      if (!members.length) { tx.delete(ref); return; }
      const names = { ...(group.names as Record<string, { name?: string }>) };
      const leaving = String(names[uid]?.name ?? "");
      delete names[uid];
      const owner = group.ownerUid === uid ? members[0] : group.ownerUid;
      // Same lines the app shows when someone leaves (and, for the owner, who takes over).
      let seq = Number(group.next ?? 0);
      const now = Timestamp.now(), expiresAt = Timestamp.fromMillis(Date.now() + CHAT_TTL);
      const line = (event: string, senderUid: string, text: string) => tx.set(db.doc(`groups/${entry.id}/messages/${String(seq++).padStart(10, "0")}`),
        { schemaVersion: 1, seq: seq - 1, kind: "event", event, senderUid, senderName: String(names[senderUid]?.name ?? leaving), text, createdAt: now, expiresAt });
      line("left", uid, leaving);
      if (owner !== group.ownerUid) line("owner", owner, String(names[owner]?.name ?? ""));
      tx.update(ref, { members, names, ownerUid: owner, next: seq, updatedAt: now });
      indexes.forEach((index) => { if (index.exists) tx.update(index.ref, { members: members.length, updatedAt: now }); });
    });
    await db.doc(`groupRates/${entry.id}/users/${uid}`).delete();
  }

  // "Let's read this": the pair's flags for each title and the other person's list entry for it.
  const wishes = await db.collection(`readWishIndex/${uid}/items`).get();
  for (const wish of wishes.docs) {
    const otherUid = String(wish.data().otherUid ?? ""), contentKey = String(wish.data().contentKey ?? "");
    if (!otherUid || !contentKey) continue;
    await db.doc(`readWishes/${pairId(uid, otherUid)}/items/${contentKey}`).delete();
    await db.doc(`readWishIndex/${otherUid}/items/${uid}_${contentKey}`).delete();
  }

  for (const tree of OWNED_TREES) await db.recursiveDelete(db.doc(`${tree}/${uid}`));
  for (const collection of OWNED_DOCS) await db.doc(`${collection}/${uid}`).delete();
  if (typeof profile?.handle === "string") {
    const handle = db.doc(`handles/${profile.handle}`);
    // Only release the handle if it still points at this user.
    if ((await handle.get()).data()?.uid === uid) await handle.delete();
  }
  await rtdb.ref(`connections/${uid}`).remove();
  await auth.deleteUser(uid);
}
