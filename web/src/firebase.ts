import { FirebaseOptions, initializeApp } from "firebase/app";
import { connectAuthEmulator, getAuth } from "firebase/auth";
import { connectFirestoreEmulator, getFirestore } from "firebase/firestore";
import { connectFunctionsEmulator, getFunctions } from "firebase/functions";
import { connectStorageEmulator, getStorage } from "firebase/storage";

declare const __FIREBASE_CONFIG__: string;

/**
 * Production builds receive their Firebase web config via MIHON_WEB_FIREBASE_CONFIG (JSON) at build time.
 * Without it the companion only talks to the local emulators, mirroring the Android debug build.
 */
const productionConfig: FirebaseOptions | null = __FIREBASE_CONFIG__ ? JSON.parse(__FIREBASE_CONFIG__) : null;
export const usingEmulators = productionConfig === null;

const app = initializeApp(productionConfig ?? {
  projectId: "demo-mihon-together",
  apiKey: "demo-web-key",
  authDomain: "demo-mihon-together.firebaseapp.com",
  storageBucket: "demo-mihon-together.appspot.com",
});

export const auth = getAuth(app);
export const db = getFirestore(app);
export const functions = getFunctions(app, "europe-west1");
export const storage = getStorage(app);

if (usingEmulators) {
  const host = location.hostname === "localhost" ? "localhost" : "127.0.0.1";
  connectAuthEmulator(auth, `http://${host}:9099`, { disableWarnings: true });
  connectFirestoreEmulator(db, host, 8080);
  connectFunctionsEmulator(functions, host, 5001);
  connectStorageEmulator(storage, host, 9199);
}
