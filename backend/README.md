# Local multiplayer backend

This backend uses the **Standard** Firestore APIs and one local Firebase demo project. It does not provision or deploy a cloud project. Node 22+ and Java 21+ are required; dependency versions are locked in `package-lock.json`.

```powershell
npm ci
npm test
npm run test:emulators
npm run emulators
```

Android emulator endpoints: `10.0.2.2` with Auth 9099, Firestore 8080, RTDB 9000, Functions 5001, region `us-central1`. The configuration binds loopback by default. A physical device needs explicitly configured reachable host bindings and address; these defaults are intentionally local.

See [the exact client contract](../docs/SOCIAL_API.md). Callables reject extra fields, use Auth UID, perform current ACL and generation checks transactionally, and publish bounded metadata without client offline writes. Host position transactions alone may write directly. Their session fence is owner-private and checked by Rules.

Generated Firestore/RTDB Security Rules are prototypes. Emulator tests cover tested attacks and supported queries; review them before broadly distributing the app. Production App Check, deletion/retention, abuse tuning and two-device native verification remain release requirements.
