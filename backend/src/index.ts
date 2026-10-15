import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getDatabase } from "firebase-admin/database";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { getMessaging } from "firebase-admin/messaging";
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onValueWritten } from "firebase-functions/v2/database";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { setGlobalOptions } from "firebase-functions/v2/options";
import { CallableName, DomainError, MediaStore, Notifier, SocialService } from "./domain.js";
import { createFirestoreStore } from "./firestore-store.js";
import { clearExpiredGroupPreviews, closeExpiredRooms } from "./cleanup.js";
import { deleteAccount } from "./account-deletion.js";

setGlobalOptions({ region: "europe-west1", maxInstances: 10 });
/**
 * App Check is on for production by default. Sideloaded test builds cannot pass Play Integrity, so a project can opt
 * out during private testing with ENFORCE_APP_CHECK=false in backend/.env.<projectId> (see docs/FIREBASE_SETUP.md).
 * Auth, per-user daily quotas and security rules still apply either way.
 */
const enforceAppCheck = process.env.FUNCTIONS_EMULATOR !== "true" && process.env.ENFORCE_APP_CHECK !== "false";
const projectId = process.env.GCLOUD_PROJECT ?? process.env.GOOGLE_CLOUD_PROJECT;
initializeApp(process.env.FUNCTIONS_EMULATOR === "true" && projectId ? {
  projectId, databaseURL: `https://${projectId}-default-rtdb.firebaseio.com`, storageBucket: `${projectId}.appspot.com`,
} : undefined);
const media: MediaStore = {
  async inspect(path) {
    const [exists] = await getStorage().bucket().file(path).exists();
    if (!exists) return null;
    const [meta] = await getStorage().bucket().file(path).getMetadata();
    return { size: Number(meta.size ?? 0), contentType: String(meta.contentType ?? "") };
  },
};
/** Tokens FCM says are gone are dropped so they aren't tried again. */
const STALE_TOKEN = new Set(["messaging/registration-token-not-registered", "messaging/invalid-registration-token"]);
const notifier: Notifier = {
  async send(notices) {
    for (const notice of notices) {
      try {
        const devices = await getFirestore().doc(`devices/${notice.to}`).get();
        const entries = Object.entries((devices.data()?.tokens ?? {}) as Record<string, { token: string }>);
        if (!entries.length) continue;
        // FCM isn't emulated: the emulator only logs what would be sent.
        if (process.env.FUNCTIONS_EMULATOR === "true") { console.log("push", notice.kind, "to", notice.to, entries.length, "device(s)"); continue; }
        const response = await getMessaging().sendEachForMulticast({
          tokens: entries.map(([, device]) => device.token),
          data: { kind: notice.kind, ...notice.data },
          android: { priority: "high", ttl: 3_600_000 },
        });
        const stale = response.responses.flatMap((result, i) => !result.success && STALE_TOKEN.has(result.error?.code ?? "") && entries[i] ? [entries[i][0]] : []);
        if (stale.length) {
          await devices.ref.update(Object.fromEntries(stale.map((id) => [`tokens.${id}`, FieldValue.delete()])));
        }
      } catch (error) {
        console.warn("push failed", notice.kind, error instanceof Error ? error.message : error);
      }
    }
  },
};
const service = new SocialService(createFirestoreStore(getFirestore()), Date.now, media, notifier);
function callable(name: CallableName) {
  return onCall({ enforceAppCheck, invoker: "public" }, async (request) => {
    try { return await service.call(name, request.auth?.uid ?? null, request.data); }
    catch (error) {
      if (error instanceof DomainError) throw new HttpsError(error.code, error.message);
      throw new HttpsError("internal", "Social operation failed.");
    }
  });
}
export const socialGetSelf = callable("socialGetSelf");
export const socialUpdateProfile = callable("socialUpdateProfile");
export const socialLookupHandle = callable("socialLookupHandle");
export const socialFriendAction = callable("socialFriendAction");
export const socialGetFriend = callable("socialGetFriend");
export const socialUpdateSharing = callable("socialUpdateSharing");
export const socialPublishEntries = callable("socialPublishEntries");
export const socialPublishActivity = callable("socialPublishActivity");
export const socialRoomAction = callable("socialRoomAction");
export const socialPublishFeed = callable("socialPublishFeed");
export const socialRoomMessage = callable("socialRoomMessage");
export const socialUnhideTitle = callable("socialUnhideTitle");
export const socialRecordChapter = callable("socialRecordChapter");
export const socialUpdateProfileStyle = callable("socialUpdateProfileStyle");
export const socialRegisterDevice = callable("socialRegisterDevice");
export const socialMessage = callable("socialMessage");
export const socialJoinRequest = callable("socialJoinRequest");
export const socialGroupChat = callable("socialGroupChat");
export const socialReadWish = callable("socialReadWish");
export const socialReview = callable("socialReview");
export const socialNudge = callable("socialNudge");
/** Re-authentication must be this recent to delete an account (seconds). */
const DELETE_REAUTH_WINDOW = 5 * 60;
export const socialDeleteAccount = onCall({ enforceAppCheck, invoker: "public" }, async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first.");
  const data = request.data as Record<string, unknown> | null;
  if (!data || typeof data !== "object" || Object.keys(data).length !== 1 || data.confirm !== "DELETE") {
    throw new HttpsError("invalid-argument", "Type DELETE to confirm.");
  }
  const authTime = Number(request.auth?.token.auth_time ?? 0);
  if (Date.now() / 1000 - authTime > DELETE_REAUTH_WINDOW) {
    throw new HttpsError("failed-precondition", "Sign in again to delete your account.");
  }
  try {
    await deleteAccount(getFirestore(), getDatabase(), getAuth(), uid, (roomId) => service.departRoom(roomId, uid));
    await getStorage().bucket().deleteFiles({ prefix: `profileMedia/${uid}/` }).catch(() => {});
    await getStorage().bucket().deleteFiles({ prefix: `coverThumbs/${uid}/` }).catch(() => {});
    await getStorage().bucket().deleteFiles({ prefix: `discordCovers/${uid}/` }).catch(() => {});
    return { deleted: true };
  } catch {
    throw new HttpsError("internal", "Account deletion failed. Try again.");
  }
});

// RTDB triggers must run in the database's region. The emulator's database is always us-central1.
const databaseRegion = process.env.FUNCTIONS_EMULATOR === "true" ? "us-central1" : "europe-west1";

export const socialPresenceChanged = onValueWritten({ ref: "connections/{uid}/{connectionId}", region: databaseRegion }, async (event) => {
  const uid = event.params.uid;
  await service.reconcilePresence(uid, async () => {
    const current = await getDatabase().ref(`connections/${uid}`).get();
    return current.exists();
  });
});
export const socialCleanup = onSchedule({ schedule: "every 30 minutes" }, async () => {
  await closeExpiredRooms(getFirestore(), service);
  await clearExpiredGroupPreviews(getFirestore());
});
