import { Firestore, Timestamp } from "firebase-admin/firestore";
import { Data, Store, Transaction } from "./domain.js";

const DATE_FIELDS = new Set(["createdAt", "updatedAt", "expiresAt", "leaseExpiresAt", "lastSeenAt", "windowStartedAt", "previewExpiresAt"]);
function decode(value: unknown): unknown {
  if (value instanceof Timestamp) return value.toMillis();
  if (Array.isArray(value)) return value.map(decode);
  if (value && typeof value === "object") return Object.fromEntries(Object.entries(value).map(([field, data]) => [field, decode(data)]));
  return value;
}
function encode(value: unknown, field?: string): unknown {
  if (typeof value === "number" && field && DATE_FIELDS.has(field)) return Timestamp.fromMillis(value);
  if (Array.isArray(value)) return value.map((item) => encode(item));
  if (value && typeof value === "object") return Object.fromEntries(Object.entries(value).map(([name, data]) => [name, encode(data, name)]));
  return value;
}
function fieldValue(data: Data, field: string): unknown {
  return field.split(".").reduce<unknown>((value, name) => value && typeof value === "object" ? (value as Data)[name] : undefined, data);
}
/** The adapter buffers effects so every native transaction reads before it writes. */
export function createFirestoreStore(firestore: Firestore): Store {
  return {
    read: async (path) => {
      const snapshot = await firestore.doc(path).get();
      return snapshot.exists ? decode(snapshot.data()) as Data : null;
    },
    transact: (body) => firestore.runTransaction(async (native) => {
      const pending = new Map<string, Data | null>();
      const fetched = new Map<string, Promise<Data | null>>();
      const tx: Transaction = {
        get: (path) => {
          if (pending.has(path)) return Promise.resolve(pending.get(path)!);
          let result = fetched.get(path);
          if (!result) {
            result = native.get(firestore.doc(path)).then((snapshot) => snapshot.exists ? decode(snapshot.data()) as Data : null);
            fetched.set(path, result);
          }
          return result;
        },
        query: async (collection, field, value) => {
          const snapshot = await native.get(firestore.collection(collection).where(field, "==", value));
          return snapshot.docs.map((doc) => ({ path: doc.ref.path, data: decode(doc.data()) as Data }))
            .filter((doc) => !pending.has(doc.path))
            .concat([...pending].filter(([path, data]) => data && path.startsWith(`${collection}/`) &&
              !path.slice(collection.length + 1).includes("/") && fieldValue(data, field) === value).map(([path, data]) => ({ path, data: data! })));
        },
        set: (path, data) => { pending.set(path, data); },
        delete: (path) => { pending.set(path, null); },
      };
      const result = await body(tx);
      for (const [path, data] of pending) {
        if (data) native.set(firestore.doc(path), encode(data) as Data);
        else native.delete(firestore.doc(path));
      }
      return result;
    }),
  };
}
