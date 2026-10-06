# Backend verification checkpoint — 2026-10-01

Implemented nine authenticated callable operations, private account/settings boundaries, accepted reciprocal friendships and blocking, generation-fenced library/favorites/activity projections, private RTDB connection signals with a Firestore status bridge, two-person invitation/join/heartbeat/close, and strictly validated host position transactions. Exact Android/wire contract is in `../docs/SOCIAL_API.md`.

## Executed checks

| Check | Result |
| --- | --- |
| TypeScript `npm run build` | Passed |
| Pure domain `node --test test/domain.test.cjs` | 18 passed, 0 failed |
| Auth + Functions + Standard Firestore + RTDB emulator suite | 11 scenarios passed, 0 failed; process exit 0 |
| Locked runtime dependency `npm audit --omit=dev` | 0 advisories after scoped patches |

Latest local logs are `.cache/domain-test.log`, `.cache/emulator-test-persistent.log`, `.cache/runtime-audit.json`. The complete CLI-owned run `.cache/emulator-test.log` passed the original ten scenarios; the latest direct Node run against all four already-running demo emulators passed eleven, including missing → published → cleared → republished activity in one generation. These logs are intentionally ignored, may contain emulator-only test identities, and are not production telemetry. The test runner requires emulator environment variables and refuses a production connection.

The emulator run explicitly selected **Firestore Standard edition**, Firestore emulator 1.22.0 and RTDB emulator 4.11.2. Java 21.0.10 and host Node 24.18.0 were used. Deployment runtime is configured as Node 22; the CLI reported that it used host Node 24 locally, so production Node 22 execution remains unverified.

`.github/workflows/social-backend.yml` adds dependency-locked domain and emulator checks on backend changes using Node 22/Java 21, a demo project and no cloud secrets. Existing checkout/Java/artifact action revisions were reused; setup-node v7.0.0 was verified against its official immutable release commit. Workflow syntax was checked locally; the workflow has not run on GitHub.

The suite covered: unauthenticated/stranger/pending/one-sided/accepted/blocked access; owner-only PII/settings/leases; participant request queries; exact paginated library queries; complete 123-entry publication over three pages; separate favorite consent; stale generations and hidden-title reupload denial; expired activity; invitation discovery; two-person room transactions; guest and competing-session host impersonation; sequence skips; mutable membership/epoch/expiry; invalid/oversize/extra/missing fields; removal/block revocation; raw RTDB privacy/type validation; multiple-device presence and final-disconnect last-seen; real Firestore concurrent presence transaction serialization. Pure-domain retry injection proves the signal source is reread after an aborted destination transaction attempt.

Permission-denied messages in emulator logs are expected assertions, not test failures. First runs exposed test timestamp-constructor/cache mistakes; the latest complete suite passed after using the client Timestamp for client writes and fresh server connections for revocation assertions.

## Security and practical limits

I've set up prototype Security Rules to keep the data in Firestore safe. They are designed to be secure for owner-private account/settings data, currently accepted friends with independent sharing consent, revoked generations, and authenticated room hosts with a current session lease. However, you should review and verify them before broadly sharing your app. If you'd like, I can help you harden these rules.

All projection and ACL writes are server-managed, so Admin SDK validation is covered separately from Rules. The only permitted client write is a bounded host position transaction, whose identity, lease, live ACL, immutable fields and full resulting schema are checked. RTDB connection data never grants friendship or room permission. The presence trigger explicitly enables background-event retries for transient failures; replay derives current signals inside its transaction instead of trusting the retried payload.

An existing authorized listener target can retain its previously delivered snapshot after an ACL change. An independent fresh connection denied immediately; the existing profile listener denied on a subsequent private document update. Native clients must cancel and discard friend-held data when their own accepted edges change and expire activity/rooms locally; `Source.SERVER` does not erase data already received by an active target.

Room creation is bounded to 20 per actor per hour, friend requests to 100 per day and exact-handle discovery to 30 per minute. Projection pages are limited to 50 combined changes and reject larger input without truncation. Library size is not limited to one page.

The latest pinned Firebase CLI still brings three **moderate development-only** OpenTelemetry/pubsub advisories. Runtime dependencies have none. Scoped grpc, UUID and FTP patches were verified by the complete emulator suite; no force/downgrade audit fix was applied.

No cloud project was provisioned or deployed. Production calls enforce App Check and require an Android provider configuration. Retention/account deletion, production Node 22 validation, rate-limit tuning, actual two-device reader mapping and real-device lifecycle/presence measurements remain release work. These backend checks do not claim native APK or instrumented-test success.
