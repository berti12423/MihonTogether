const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { randomUUID } = require('node:crypto');
const { initializeTestEnvironment, assertFails, assertSucceeds } = require('@firebase/rules-unit-testing');
const { initializeApp, deleteApp } = require('firebase/app');
const { getAuth, connectAuthEmulator, createUserWithEmailAndPassword } = require('firebase/auth');
const { getFunctions, connectFunctionsEmulator, httpsCallable } = require('firebase/functions');
const { doc, collection, getDoc, getDocFromServer, getDocs, setDoc, updateDoc, deleteDoc, query, where, orderBy, documentId,
  limit, startAfter, getCountFromServer, serverTimestamp, runTransaction, onSnapshot, Timestamp: ClientTimestamp } = require('firebase/firestore');
const { ref, set, get, remove, serverTimestamp: rtdbTimestamp } = require('firebase/database');
const { ref: storageRef, uploadBytes, getBytes, deleteObject } = require('firebase/storage');
const { initializeApp: initializeAdmin, deleteApp: deleteAdmin } = require('firebase-admin/app');
const { getFirestore: adminFirestore, Timestamp, FieldValue } = require('firebase-admin/firestore');
const { getDatabase: adminDatabase } = require('firebase-admin/database');
const { getAuth: adminAuth } = require('firebase-admin/auth');
const { digest, pairId, SocialService, statsDay, xpMultiplier } = require('../lib/domain.js');
const { createFirestoreStore } = require('../lib/firestore-store.js');
const { closeExpiredRooms, clearExpiredGroupPreviews } = require('../lib/cleanup.js');

const PROJECT = 'demo-mihon-together';
let env, adminApp, adminDb, adminRtdb;
const apps = [];
const op = () => randomUUID();
const content = (id = 1) => ({ contentKey: digest(`work-${id}`), title: `Title ${id}`, sourceKey: 'extension:en:source',
  sourceWorkKey: digest(`source-${id}`), editionKey: digest(`edition-${id}`), canonicalProvider: null, canonicalId: null });
const position = (pageIndex = 0) => ({ chapterKey: digest('chapter1'), chapterLabel: 'Chapter 1', pageIndex, pageCount: 20, pageMappingKey: digest('mapping1') });
const sharing = (overrides = {}) => ({ library: 'private', favorites: 'private', activity: 'private', presence: 'private', lastSeen: 'private', feed: 'private', stats: 'private', invisible: false, ...overrides });
const roomInput = (action, fields = {}) => ({ operationId: op(), action, roomId: null, otherUid: null, sessionId: null, content: null, position: null, ...fields });

before(async () => {
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST && process.env.FIREBASE_AUTH_EMULATOR_HOST && process.env.FIREBASE_DATABASE_EMULATOR_HOST,
    'Run with npm run test:emulators; never connect these tests to production.');
  env = await initializeTestEnvironment({ projectId: PROJECT,
    firestore: { host: '127.0.0.1', port: 8080, rules: readFileSync('firestore.rules', 'utf8') },
    database: { host: '127.0.0.1', port: 9000, rules: readFileSync('database.rules.json', 'utf8') },
    storage: { host: '127.0.0.1', port: 9199, rules: readFileSync('storage.rules', 'utf8') },
  });
  adminApp = initializeAdmin({ projectId: PROJECT, databaseURL: `https://${PROJECT}-default-rtdb.firebaseio.com` }, 'backend-tests');
  adminDb = adminFirestore(adminApp); adminRtdb = adminDatabase(adminApp);
});
beforeEach(async () => { await env.clearFirestore(); await env.clearStorage(); await adminRtdb.ref('/').remove(); });
after(async () => { await Promise.all(apps.map(deleteApp)); await env?.cleanup(); if (adminApp) await deleteAdmin(adminApp); });

async function account(prefix) {
  const suffix = op().replaceAll('-', '').slice(0, 12);
  const handle = `${prefix}_${suffix}`;
  const app = initializeApp({ projectId: PROJECT, apiKey: 'demo-local-key', authDomain: `${PROJECT}.firebaseapp.com`, databaseURL: `https://${PROJECT}-default-rtdb.firebaseio.com` }, handle);
  apps.push(app);
  const auth = getAuth(app); connectAuthEmulator(auth, 'http://127.0.0.1:9099', { disableWarnings: true });
  const user = (await createUserWithEmailAndPassword(auth, `${handle}@example.test`, 'emulator-test-password')).user;
  const functions = getFunctions(app, 'europe-west1'); connectFunctionsEmulator(functions, '127.0.0.1', 5001);
  const call = async (name, input) => (await httpsCallable(functions, name)(input)).data;
  await call('socialUpdateProfile', { operationId: op(), handle, displayName: prefix });
  const ctx = env.authenticatedContext(user.uid, { email: user.email });
  return { uid: user.uid, handle, call, db: ctx.firestore(), rtdb: ctx.database(`https://${PROJECT}-default-rtdb.firebaseio.com`),
    storage: ctx.storage(`gs://${PROJECT}.appspot.com`) };
}
async function fixture() { return { a: await account('alice'), b: await account('bob'), c: await account('charlie') }; }
async function befriend(a, b) {
  const requested = await a.call('socialFriendAction', { operationId: op(), action: 'request', otherUid: b.uid });
  await b.call('socialFriendAction', { operationId: op(), action: 'accept', otherUid: a.uid }); return requested;
}
async function share(a, overrides, generation = 1, hideContentKey = null) {
  return a.call('socialUpdateSharing', { operationId: op(), expectedGeneration: generation, settings: sharing(overrides), hideContentKey });
}
const callableFails = (promise, code) => assert.rejects(promise, (error) => error.code === `functions/${code}`);
async function eventually(assertion, timeout = 10_000) {
  const end = Date.now() + timeout; let last;
  while (Date.now() < end) {
    try { return await assertion(); } catch (error) { last = error; }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw last ?? new Error('Timed out');
}

test('callables authenticate users, bound profiles, and enforce recipient-only friendship', async () => {
  const { a, b, c } = await fixture();
  const unsigned = initializeApp({ projectId: PROJECT, apiKey: 'demo-local-key' }, `unsigned-${op()}`); apps.push(unsigned);
  const unsignedFunctions = getFunctions(unsigned, 'europe-west1'); connectFunctionsEmulator(unsignedFunctions, '127.0.0.1', 5001);
  await callableFails(httpsCallable(unsignedFunctions, 'socialGetSelf')({}), 'unauthenticated');
  assert.equal((await a.call('socialGetSelf', {})).settings.library, 'friends'); // new accounts share by default
  await callableFails(a.call('socialUpdateProfile', { operationId: op(), handle: a.handle, displayName: 'x'.repeat(41) }), 'invalid-argument');
  await callableFails(a.call('socialGetSelf', { uid: b.uid }), 'invalid-argument');
  await a.call('socialFriendAction', { operationId: op(), action: 'request', otherUid: b.uid });
  const requestDoc = (await adminDb.doc(`friendRequests/${pairId(a.uid, b.uid)}`).get()).data();
  assert.equal(requestDoc.senderHandle, a.handle); assert.equal(requestDoc.recipientDisplayName, 'bob');
  assert.equal(requestDoc.email, undefined);
  await callableFails(a.call('socialFriendAction', { operationId: op(), action: 'accept', otherUid: b.uid }), 'permission-denied');
  await callableFails(c.call('socialGetFriend', { otherUid: a.uid }), 'permission-denied');
  await assertFails(getDoc(doc(b.db, `profiles/${a.uid}`)));
  await b.call('socialFriendAction', { operationId: op(), action: 'accept', otherUid: a.uid });
  await assertSucceeds(getDoc(doc(b.db, `profiles/${a.uid}`)));
  await assertFails(getDoc(doc(b.db, `accounts/${a.uid}`)));
  await assertFails(getDoc(doc(b.db, `sharingSettings/${a.uid}`)));
  await assertFails(getDocs(collection(c.db, 'profiles')));
  await assertFails(getDocs(collection(env.unauthenticatedContext().firestore(), 'profiles')));
  await assertFails(setDoc(doc(c.db, `friendships/${c.uid}/accepted/${a.uid}`), { friendUid: a.uid }));
  await assertFails(setDoc(doc(a.db, `profiles/${a.uid}`), { uid: a.uid, role: 'admin' }));
});
test('only request participants can query pending requests; one-sided edges grant no access', async () => {
  const { a, b, c } = await fixture();
  const request = await a.call('socialFriendAction', { operationId: op(), action: 'request', otherUid: b.uid });
  await assertSucceeds(getDocs(query(collection(b.db, 'friendRequests'), where('recipientUid', '==', b.uid), where('state', '==', 'pending'), limit(50))));
  await assertSucceeds(getDocs(query(collection(a.db, 'friendRequests'), where('senderUid', '==', a.uid), where('state', '==', 'pending'), limit(50))));
  await assertFails(getDoc(doc(c.db, `friendRequests/${request.requestId}`)));
  await assertFails(updateDoc(doc(b.db, `friendRequests/${request.requestId}`), { state: 'accepted' }));
  await adminDb.doc(`friendships/${a.uid}/accepted/${b.uid}`).set({ schemaVersion: 1, friendUid: b.uid, createdAt: Timestamp.now() });
  await assertFails(getDoc(doc(b.db, `profiles/${a.uid}`)));
  await callableFails(b.call('socialGetFriend', { otherUid: a.uid }), 'permission-denied');
});
test('shared libraries paginate completely, favorites remain private, and generations revoke stale projections', async () => {
  const { a, b, c } = await fixture(); await befriend(a, b); await share(a, { library: 'friends' });
  const entries = Array.from({ length: 123 }, (_, index) => content(index));
  for (let index = 0; index < entries.length; index += 50) await a.call('socialPublishEntries', {
    operationId: op(), generation: 2, kind: 'library', entries: entries.slice(index, index + 50), deleteKeys: [],
  });
  const path = `sharedLibraries/${a.uid}/entries`;
  const fetched = []; let cursor;
  for (;;) {
    const clauses = [where('generation', '==', 2), orderBy(documentId()), limit(50)]; if (cursor) clauses.push(startAfter(cursor));
    const page = await assertSucceeds(getDocs(query(collection(b.db, path), ...clauses)));
    fetched.push(...page.docs.map((entry) => entry.id));
    if (page.size < 50) break; cursor = page.docs.at(-1);
  }
  assert.equal(fetched.length, 123); assert.equal(new Set(fetched).size, 123);
  await assertFails(getDocs(query(collection(b.db, path), limit(50))));
  await assertFails(getDoc(doc(c.db, `${path}/${entries[0].contentKey}`)));
  await assertFails(getDocs(collection(b.db, `sharedFavorites/${a.uid}/entries`)));
  await assertFails(setDoc(doc(a.db, `${path}/${content(999).contentKey}`), { content: content(999), generation: 2, updatedAt: serverTimestamp() }));
  await callableFails(a.call('socialPublishEntries', { operationId: op(), generation: 2, kind: 'library', entries: [{ ...content(), cookie: 'credential' }], deleteKeys: [] }), 'invalid-argument');
  await share(a, { library: 'friends' }, 2, entries[0].contentKey);
  await assertFails(getDoc(doc(b.db, `${path}/${entries[1].contentKey}`)));
  await assertSucceeds(getDocs(query(collection(b.db, path), where('generation', '==', 3), orderBy(documentId()), limit(50))));
  await callableFails(a.call('socialPublishEntries', { operationId: op(), generation: 2, kind: 'library', entries: [content(999)], deleteKeys: [] }), 'failed-precondition');
  await callableFails(a.call('socialPublishEntries', { operationId: op(), generation: 3, kind: 'library', entries: [entries[0]], deleteKeys: [] }), 'failed-precondition');
});
test('separate favorite consent, activity expiry and privacy switches are enforced', async () => {
  const { a, b } = await fixture(); await befriend(a, b); await share(a, { favorites: 'friends', activity: 'friends' });
  await a.call('socialPublishEntries', { operationId: op(), generation: 2, kind: 'favorites', entries: [content()], deleteKeys: [] });
  await assertSucceeds(getDoc(doc(b.db, `sharedFavorites/${a.uid}/entries/${content().contentKey}`)));
  await assertFails(getDocs(collection(b.db, `sharedLibraries/${a.uid}/entries`)));
  await a.call('socialPublishActivity', { operationId: op(), generation: 2, activity: { content: content(), chapterKey: digest('c'), chapterLabel: 'Chapter' } });
  await assertSucceeds(getDoc(doc(b.db, `activities/${a.uid}`)));
  await adminDb.doc(`activities/${a.uid}`).update({ expiresAt: Timestamp.fromMillis(1) });
  await assertFails(getDocFromServer(doc(b.db, `activities/${a.uid}`)));
  await share(a, {}, 2);
  await assertFails(getDoc(doc(b.db, `sharedFavorites/${a.uid}/entries/${content().contentKey}`)));
  await assertFails(setDoc(doc(a.db, `activities/${a.uid}`), { uid: b.uid, chapterLabel: 'malicious' }));
});
test('two friends create/join a room and host updates use the exact transaction query and session fence', async () => {
  const { a, b, c } = await fixture(); await befriend(a, b);
  const sessionId = op(), created = await a.call('socialRoomAction', roomInput('create', { otherUid: b.uid, sessionId, content: content(), position: position() }));
  await b.call('socialRoomAction', roomInput('join', { roomId: created.roomId }));
  const path = `rooms/${created.roomId}`, roomRef = doc(a.db, path);
  await assertSucceeds(getDoc(doc(b.db, path))); await assertFails(getDoc(doc(c.db, path)));
  await assertFails(getDoc(doc(b.db, `roomLeases/${created.roomId}`)));
  await assertSucceeds(getDocs(query(collection(b.db, `roomInvites/${b.uid}/items`), where('state', 'in', ['invited', 'active']), orderBy('updatedAt', 'desc'), limit(50))));
  await assertFails(getDocs(collection(c.db, `roomInvites/${b.uid}/items`)));
  await assertFails(setDoc(doc(a.db, `rooms/${digest('forged-room')}`), created.room));
  await assertSucceeds(runTransaction(a.db, async (tx) => {
    const snapshot = await tx.get(roomRef), lease = await tx.get(doc(a.db, `roomLeases/${created.roomId}`));
    assert.equal(lease.data().sessionId, sessionId);
    tx.update(roomRef, { position: position(3), sequence: snapshot.data().sequence + 1, writerSessionId: sessionId, updatedAt: serverTimestamp() });
  }));
  const remote = (await assertSucceeds(getDoc(doc(b.db, path)))).data(); assert.equal(remote.sequence, 1); assert.equal(remote.position.pageIndex, 3);
  await assertFails(updateDoc(doc(b.db, path), { position: position(4), sequence: 2, writerSessionId: sessionId, updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(roomRef, { position: position(4), sequence: 2, writerSessionId: op(), updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(roomRef, { position: position(4), sequence: 3, writerSessionId: sessionId, updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(roomRef, { position: position(4), sequence: 2, writerSessionId: sessionId, updatedAt: ClientTimestamp.fromMillis(Date.now() + 100_000) }));
  // Long-strip scroll: a fractional page position within the chapter is accepted, out-of-range is not.
  await assertSucceeds(updateDoc(roomRef, { position: { ...position(4), stripPosition: 4.25 }, sequence: 2, writerSessionId: sessionId, updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(roomRef, { position: { ...position(4), stripPosition: 20 }, sequence: 3, writerSessionId: sessionId, updatedAt: serverTimestamp() }));
});
test('accepted activity listeners receive missing, publish, clear and republish within one generation', async () => {
  const { a, b, c } = await fixture(); await befriend(a, b); await share(a, { activity: 'friends' });
  const path = `activities/${a.uid}`;
  await assertFails(getDocFromServer(doc(c.db, path)));
  await assertFails(getDocFromServer(doc(env.unauthenticatedContext().firestore(), path)));
  const received = [], errors = [];
  const stop = onSnapshot(doc(b.db, path), { includeMetadataChanges: true }, (snapshot) => {
    if (!snapshot.metadata.fromCache) received.push(snapshot.exists() ? snapshot.data().content.title : null);
  }, (error) => errors.push(error));
  try {
    await eventually(() => assert.deepEqual(received, [null]));
    await a.call('socialPublishActivity', { operationId: op(), generation: 2,
      activity: { content: content(101), chapterKey: digest('c1'), chapterLabel: 'Chapter 1' } });
    await eventually(() => assert.ok(received.includes('Title 101')));
    await a.call('socialPublishActivity', { operationId: op(), generation: 2, activity: null });
    await eventually(() => assert.equal(received.at(-1), null));
    await a.call('socialPublishActivity', { operationId: op(), generation: 2,
      activity: { content: content(102), chapterKey: digest('c2'), chapterLabel: 'Chapter 2' } });
    await eventually(() => assert.equal(received.at(-1), 'Title 102'));
    assert.deepEqual(errors, []);
  } finally { stop(); }
  await share(a, {}, 2);
  await adminDb.doc(path).delete();
  const fresh = env.authenticatedContext(b.uid, { email: 'fresh@example.test' }).firestore();
  await assertFails(getDocFromServer(doc(fresh, path)));
});
test('host cannot change membership, epoch, expiry, schema or send invalid positions', async () => {
  const { a, b, c } = await fixture(); await befriend(a, b);
  const sessionId = op(), created = await a.call('socialRoomAction', roomInput('create', { otherUid: b.uid, sessionId, content: content(), position: position() }));
  await b.call('socialRoomAction', roomInput('join', { roomId: created.roomId }));
  const target = doc(a.db, `rooms/${created.roomId}`), base = { position: position(1), sequence: 1, writerSessionId: sessionId, updatedAt: serverTimestamp() };
  for (const extra of [{ guestUid: c.uid }, { participants: [a.uid, c.uid] }, { epoch: 2 }, { leaseExpiresAt: ClientTimestamp.fromMillis(Date.now() + 999_999) },
    { createdAt: ClientTimestamp.now() }, { state: 'invited' }, { role: 'admin' }, { position: { ...position(1), pageIndex: 20 } },
    { position: { ...position(1), pageCount: 0 } }, { position: { ...position(1), chapterLabel: 'x'.repeat(101) } },
    { position: { ...position(1), pageIndex: '1' } }, { position: { ...position(1), extraData: 'pollution' } }, { position: null }]) {
    await assertFails(updateDoc(target, { ...base, ...extra }));
  }
  const missing = { ...(await getDoc(target)).data() }; delete missing.content;
  await assertFails(setDoc(target, missing)); await assertFails(deleteDoc(target));
  await adminDb.doc(`rooms/${created.roomId}`).update({ leaseExpiresAt: Timestamp.fromMillis(1) });
  await assertFails(getDocFromServer(doc(b.db, `rooms/${created.roomId}`))); await assertFails(updateDoc(target, base));
  await callableFails(a.call('socialRoomAction', roomInput('heartbeat', { roomId: created.roomId, sessionId })), 'failed-precondition');
});
test('block/remove immediately revoke room/library/profile reads and callables cannot return protected room after revoke', async () => {
  const { a, b } = await fixture(); await befriend(a, b); await share(a, { library: 'friends' });
  await a.call('socialPublishEntries', { operationId: op(), generation: 2, kind: 'library', entries: [content()], deleteKeys: [] });
  const sessionId = op(), created = await a.call('socialRoomAction', roomInput('create', { otherUid: b.uid, sessionId, content: content(), position: position() }));
  await b.call('socialRoomAction', roomInput('join', { roomId: created.roomId }));
  await a.call('socialFriendAction', { operationId: op(), action: 'block', otherUid: b.uid });
  for (const path of [`profiles/${a.uid}`, `rooms/${created.roomId}`, `sharedLibraries/${a.uid}/entries/${content().contentKey}`]) await assertFails(getDoc(doc(b.db, path)));
  await callableFails(b.call('socialGetFriend', { otherUid: a.uid }), 'permission-denied');
  await callableFails(b.call('socialRoomAction', roomInput('close', { roomId: created.roomId })), 'permission-denied');
  // Group rooms: the host's own room goes on (their writes still pass); only the blocked member lost access above.
  await assertSucceeds(updateDoc(doc(a.db, `rooms/${created.roomId}`), { position: position(1), sequence: 1, writerSessionId: sessionId, updatedAt: serverTimestamp() }));
  await a.call('socialFriendAction', { operationId: op(), action: 'unblock', otherUid: b.uid }); await befriend(a, b);
  await a.call('socialFriendAction', { operationId: op(), action: 'remove', otherUid: b.uid });
  await assertFails(getDoc(doc(b.db, `profiles/${a.uid}`)));
});
test('removed friend subscription cannot receive subsequent private profile changes', async () => {
  const { a, b } = await fixture(); await befriend(a, b);
  let stop;
  let firstResolve, deniedResolve;
  const first = new Promise((resolve) => { firstResolve = resolve; });
  const denied = new Promise((resolve) => { deniedResolve = resolve; });
  stop = onSnapshot(doc(b.db, `profiles/${a.uid}`), firstResolve, deniedResolve);
  try {
    await Promise.race([first, new Promise((_, reject) => setTimeout(() => reject(new Error('initial listener timed out')), 10_000))]);
    await a.call('socialFriendAction', { operationId: op(), action: 'remove', otherUid: b.uid });
    // An existing watch target can serve its previously authorized snapshot even for a server source.
    // A fresh connection proves new authorization, while the next protected update tests the listener.
    const fresh = env.authenticatedContext(b.uid).firestore();
    await assertFails(getDocFromServer(doc(fresh, `profiles/${a.uid}`)));
    await a.call('socialUpdateProfile', { operationId: op(), handle: a.handle, displayName: 'Private new name' });
    const error = await Promise.race([denied, new Promise((_, reject) => setTimeout(() => reject(new Error('listener revocation timed out')), 10_000))]);
    assert.equal(error.code, 'permission-denied');
  } finally { stop(); }
});
test('RTDB raw signals are owner-private, strict, and aggregate multiple devices through the Function', async () => {
  const { a, b, c } = await fixture(); await befriend(a, b); await share(a, { presence: 'friends', lastSeen: 'friends' });
  const first = op(), second = op();
  const own = ref(a.rtdb, `connections/${a.uid}/${first}`);
  await assertSucceeds(set(own, { connectedAt: rtdbTimestamp() }));
  await assertFails(get(ref(b.rtdb, `connections/${a.uid}`)));
  await assertFails(set(ref(b.rtdb, `connections/${a.uid}/${op()}`), { connectedAt: rtdbTimestamp() }));
  await assertFails(set(ref(a.rtdb, `connections/${a.uid}/${op()}`), { connectedAt: 'bad' }));
  await assertFails(set(ref(a.rtdb, `connections/${a.uid}/${op()}`), { connectedAt: rtdbTimestamp(), email: 'private@example.test' }));
  await assertFails(set(ref(a.rtdb, `connections/${a.uid}/invalid`), { connectedAt: rtdbTimestamp() }));
  await eventually(async () => assert.equal((await adminDb.doc(`presence/${a.uid}`).get()).data()?.state, 'online'));
  await assertSucceeds(getDoc(doc(b.db, `presence/${a.uid}`))); await assertFails(getDoc(doc(c.db, `presence/${a.uid}`)));
  await assertSucceeds(set(ref(a.rtdb, `connections/${a.uid}/${second}`), { connectedAt: rtdbTimestamp() }));
  await remove(own);
  await eventually(async () => assert.equal((await adminDb.doc(`presence/${a.uid}`).get()).data()?.state, 'online'));
  assert.equal((await adminDb.doc(`lastSeen/${a.uid}`).get()).exists, false);
  await remove(ref(a.rtdb, `connections/${a.uid}/${second}`));
  await eventually(async () => assert.equal((await adminDb.doc(`presence/${a.uid}`).get()).data()?.state, 'offline'));
  await eventually(async () => assert.equal((await adminDb.doc(`lastSeen/${a.uid}`).get()).exists, true));
  await assertSucceeds(getDoc(doc(b.db, `lastSeen/${a.uid}`)));
  await share(a, { presence: 'friends', lastSeen: 'friends', invisible: true }, 2);
  await assertFails(getDoc(doc(b.db, `presence/${a.uid}`))); await assertFails(getDoc(doc(b.db, `lastSeen/${a.uid}`)));
});
test('concurrent presence transactions serialize stale signal reads and preserve the final offline mirror', async () => {
  const { a } = await fixture();
  const service = new SocialService(createFirestoreStore(adminDb));
  await service.reconcilePresence(a.uid, async () => true);
  let olderReads = 0, enteredResolve, releaseResolve;
  const entered = new Promise((resolve) => { enteredResolve = resolve; });
  const release = new Promise((resolve) => { releaseResolve = resolve; });
  const older = service.reconcilePresence(a.uid, async () => {
    if (++olderReads === 1) { enteredResolve(); await release; return true; }
    return false;
  });
  await entered;
  const offlineAt = Date.now();
  const newer = service.reconcilePresence(a.uid, async () => false);
  // Standard server transactions use pessimistic locks: the later trigger starts while the old signal read is held.
  // The real store must serialize commits or retry with a fresh signal read rather than let stale work finish last.
  releaseResolve();
  await Promise.all([older, newer]);
  const final = (await adminDb.doc(`presence/${a.uid}`).get()).data();
  assert.equal(final.state, 'offline');
  assert.ok((await adminDb.doc(`lastSeen/${a.uid}`).get()).data()?.lastSeenAt.toMillis() >= offlineAt);
  assert.ok(olderReads >= 1);
});
test('feed items are readable only by friends, for the current generation, unexpired and while visible', async () => {
  const { a, b, c } = await fixture(); await befriend(a, b); await share(a, { feed: 'friends' });
  const publish = (fields) => a.call('socialPublishFeed', { operationId: op(), generation: 2, kind: 'chapter_read', content: content(), chapterLabel: 'Chapter 1', ...fields });
  await publish({}); await publish({ kind: 'favorite_added', content: content(2), chapterLabel: null });
  const path = `feeds/${a.uid}/items`;
  // Clients bound expiry an hour ahead so the rule can prove `expiresAt > request.time` despite clock lag.
  const feed = (db, generation, bound = Date.now() + 3_600_000) => getDocs(query(collection(db, path),
    where('generation', '==', generation), where('expiresAt', '>', ClientTimestamp.fromMillis(bound)), orderBy('expiresAt', 'desc'), limit(20)));
  const items = await assertSucceeds(feed(b.db, 2));
  assert.deepEqual(items.docs.map((item) => item.data().content.title), ['Title 2', 'Title 1']);
  await assertFails(feed(c.db, 2));
  await assertFails(feed(b.db, 1));
  await assertFails(feed(b.db, 2, Date.now() - 3_600_000));
  await assertFails(getDocs(query(collection(b.db, path), where('generation', '==', 2), limit(20))));
  await assertSucceeds(getDocs(collection(a.db, path)));
  await assertFails(getDoc(doc(a.db, `feedState/${a.uid}`)));
  await assertFails(getDoc(doc(b.db, `feedState/${a.uid}`)));
  await assertFails(setDoc(doc(a.db, `${path}/99`), { kind: 'chapter_read', content: content(), chapterLabel: 'x', generation: 2 }));
  await adminDb.doc(`${path}/00`).update({ expiresAt: Timestamp.fromMillis(1) });
  await assertFails(getDocFromServer(doc(b.db, `${path}/00`)));
  await assertSucceeds(getDocFromServer(doc(b.db, `${path}/01`)));
  await share(a, { feed: 'friends', invisible: true }, 2);
  await assertFails(feed(b.db, 3));
  await assertFails(getDocFromServer(doc(b.db, `${path}/01`)));
  await callableFails(a.call('socialPublishFeed', { operationId: op(), generation: 3, kind: 'chapter_read', content: content(), chapterLabel: 'Chapter 1' }), 'failed-precondition');
  await share(a, { feed: 'private' }, 3);
  await assertFails(feed(b.db, 4));
});
test('room messages are readable only by participants of a live room and never client-writable', async () => {
  const { a, b, c } = await fixture(); await befriend(a, b);
  const created = await a.call('socialRoomAction', roomInput('create', { otherUid: b.uid, sessionId: op(), content: content() }));
  await b.call('socialRoomAction', roomInput('join', { roomId: created.roomId }));
  await a.call('socialRoomMessage', { operationId: op(), roomId: created.roomId, kind: 'text', text: 'hello', emoji: null });
  await new Promise((resolve) => setTimeout(resolve, 1_100));
  await b.call('socialRoomMessage', { operationId: op(), roomId: created.roomId, kind: 'reaction', text: null, emoji: '👏' });
  const path = `rooms/${created.roomId}/messages`;
  const recent = (db) => getDocs(query(collection(db, path), orderBy('seq', 'desc'), limit(30)));
  const seen = await assertSucceeds(recent(b.db));
  assert.deepEqual(seen.docs.map((m) => m.data().kind), ['reaction', 'text', 'joined']); // + the server's "joined" line
  await assertSucceeds(recent(a.db));
  await assertFails(recent(c.db));
  await assertFails(setDoc(doc(a.db, `${path}/099`), { seq: 99, senderUid: a.uid, kind: 'text', text: 'forged', emoji: null }));
  await assertFails(getDoc(doc(a.db, `roomChat/${created.roomId}`)));
  await assertFails(getDoc(doc(a.db, `roomChatRates/${created.roomId}/users/${a.uid}`)));
  await callableFails(c.call('socialRoomMessage', { operationId: op(), roomId: created.roomId, kind: 'text', text: 'x', emoji: null }), 'permission-denied');
  await a.call('socialRoomAction', roomInput('close', { roomId: created.roomId }));
  await assertFails(recent(b.db));
  assert.equal((await adminDb.collection(path).get()).size, 0);
});
test('hidden markers are owner-private and restorable only through the callable', async () => {
  const { a, b } = await fixture(); await befriend(a, b);
  await share(a, { library: 'friends' }, 1, content(7).contentKey);
  const hidden = `hiddenTitles/${a.uid}/entries`;
  assert.equal((await assertSucceeds(getDocs(collection(a.db, hidden)))).size, 1);
  await assertFails(getDocs(collection(b.db, hidden)));
  await assertFails(deleteDoc(doc(a.db, `${hidden}/${content(7).contentKey}`)));
  assert.equal((await a.call('socialUnhideTitle', { operationId: op(), contentKey: content(7).contentKey })).restored, true);
  assert.equal((await assertSucceeds(getDocs(collection(a.db, hidden)))).size, 0);
  await a.call('socialPublishEntries', { operationId: op(), generation: 2, kind: 'library', entries: [content(7)], deleteKeys: [] });
  await assertSucceeds(getDoc(doc(b.db, `sharedLibraries/${a.uid}/entries/${content(7).contentKey}`)));
});
test('scheduled cleanup closes only rooms whose lease expired and clears their chat', async () => {
  const { a, b } = await fixture(); await befriend(a, b);
  const stale = await a.call('socialRoomAction', roomInput('create', { otherUid: b.uid, sessionId: op(), content: content(1) }));
  const live = await a.call('socialRoomAction', roomInput('create', { otherUid: b.uid, sessionId: op(), content: content(2) }));
  await b.call('socialRoomAction', roomInput('join', { roomId: stale.roomId }));
  await a.call('socialRoomMessage', { operationId: op(), roomId: stale.roomId, kind: 'text', text: 'bye', emoji: null });
  await adminDb.doc(`rooms/${stale.roomId}`).update({ leaseExpiresAt: Timestamp.fromMillis(Date.now() - 1_000) });
  const service = new SocialService(createFirestoreStore(adminDb));
  assert.equal(await closeExpiredRooms(adminDb, service), 1);
  assert.equal((await adminDb.doc(`rooms/${stale.roomId}`).get()).data().state, 'closed');
  assert.equal((await adminDb.doc(`roomInvites/${b.uid}/items/${stale.roomId}`).get()).data().state, 'closed');
  assert.equal((await adminDb.collection(`rooms/${stale.roomId}/messages`).get()).size, 0);
  assert.equal((await adminDb.doc(`rooms/${live.roomId}`).get()).data().state, 'invited');
  assert.equal(await closeExpiredRooms(adminDb, service), 0);
});
test('account deletion removes the user, their projections, relationships, rooms and handle', async () => {
  const { a, b, c } = await fixture(); await befriend(a, b);
  await a.call('socialFriendAction', { operationId: op(), action: 'request', otherUid: c.uid });
  await share(a, { library: 'friends', feed: 'friends' });
  await a.call('socialPublishEntries', { operationId: op(), generation: 2, kind: 'library', entries: [content(1)], deleteKeys: [] });
  await a.call('socialPublishFeed', { operationId: op(), generation: 2, kind: 'chapter_read', content: content(1), chapterLabel: 'Ch.1' });
  const room = await a.call('socialRoomAction', roomInput('create', { otherUid: b.uid, sessionId: op(), content: content(1) }));
  await b.call('socialRoomAction', roomInput('join', { roomId: room.roomId }));
  await b.call('socialRoomMessage', { operationId: op(), roomId: room.roomId, kind: 'text', text: 'hi', emoji: null });
  // A daily board from three days ago is still readable by everyone, so deletion must clear it too.
  const olderBoard = `leaderboards/${statsDay(Date.now() - 3 * 86_400_000)}`;
  await adminDb.doc(olderBoard).set({ schemaVersion: 1, entries: { [a.uid]: { handle: a.handle, chapters: 1 }, [b.uid]: { handle: b.handle, chapters: 2 } } });
  await callableFails(a.call('socialDeleteAccount', {}), 'invalid-argument');
  await callableFails(a.call('socialDeleteAccount', { confirm: 'delete' }), 'invalid-argument');
  assert.deepEqual(await a.call('socialDeleteAccount', { confirm: 'DELETE' }), { deleted: true });
  const gone = async (path) => assert.equal((await adminDb.doc(path).get()).exists, false, path);
  for (const path of [`profiles/${a.uid}`, `sharingSettings/${a.uid}`, `accounts/${a.uid}`, `handles/${a.handle}`, `quotas/${a.uid}`,
    `sharedLibraries/${a.uid}/entries/${content(1).contentKey}`, `feeds/${a.uid}/items/00`, `feedState/${a.uid}`,
    `friendships/${b.uid}/accepted/${a.uid}`, `friendships/${a.uid}/accepted/${b.uid}`, `friendRequests/${pairId(a.uid, c.uid)}`,
    `rooms/${room.roomId}`, `roomInvites/${b.uid}/items/${room.roomId}`, `rooms/${room.roomId}/messages/000`, `roomChat/${room.roomId}`]) await gone(path);
  assert.equal((await adminDb.collection(`operations/${a.uid}/items`).get()).size, 0);
  assert.deepEqual(Object.keys((await adminDb.doc(olderBoard).get()).data().entries), [b.uid]);
  await assert.rejects(adminAuth(adminApp).getUser(a.uid), (error) => error.code === 'auth/user-not-found');
  // The handle is free again and the friend keeps a working account.
  await assertSucceeds(getDoc(doc(b.db, `profiles/${b.uid}`)));
  assert.equal((await b.call('socialLookupHandle', { handle: b.handle })).profile.uid, b.uid);
});
test('daily stats are readable by friends for the current generation while the owner shares and is visible', async () => {
  const { a, b, c } = await fixture(); await befriend(a, b); await share(a, { stats: 'friends' });
  const result = await a.call('socialRecordChapter', { operationId: op(), generation: 2, pages: 24 });
  const path = `dailyStats/${a.uid}/days/${result.day}`;
  assert.equal(result.day, statsDay(Date.now()));
  assert.equal((await assertSucceeds(getDocFromServer(doc(b.db, path)))).data().chapters, 1);
  await assertFails(getDocFromServer(doc(c.db, path)));
  await assertFails(setDoc(doc(a.db, path), { chapters: 999 }));
  await share(a, { stats: 'friends', invisible: true }, 2);
  await assertFails(getDocFromServer(doc(b.db, path)));
  await share(a, { stats: 'friends' }, 3);
  await assertFails(getDocFromServer(doc(b.db, path))); // old generation stays unreadable
  await assertSucceeds(getDocFromServer(doc(a.db, path)));
});
test('friends page a shared library by title with covers and can count it', async () => {
  const { a, b } = await fixture(); await befriend(a, b); await share(a, { library: 'friends' });
  const entries = ['Zeta', 'alpha', 'Beta'].map((title, i) => ({ ...content(i + 1), title, coverUrl: `https://cdn.example/${i}.jpg` }));
  await a.call('socialPublishEntries', { operationId: op(), generation: 2, kind: 'library', entries, deleteKeys: [] });
  const base = query(collection(b.db, `sharedLibraries/${a.uid}/entries`), where('generation', '==', 2), orderBy('content.title'));
  const first = await assertSucceeds(getDocs(query(base, limit(2))));
  assert.deepEqual(first.docs.map((d) => d.data().content.title), ['Beta', 'Zeta']);
  const rest = await getDocs(query(base, startAfter(first.docs.at(-1)), limit(2)));
  assert.deepEqual(rest.docs.map((d) => d.data().content.title), ['alpha']);
  assert.equal(first.docs[0].data().content.coverUrl, 'https://cdn.example/2.jpg');
  const counted = await getCountFromServer(query(collection(b.db, `sharedLibraries/${a.uid}/entries`), where('generation', '==', 2)));
  assert.equal(counted.data().count, 3);
});
test('private chats are readable only by the two friends and vanish from reads after unfriending', async () => {
  const { a, b, c } = await fixture(); await befriend(a, b);
  const sent = await a.call('socialMessage', { operationId: op(), action: 'send', otherUid: b.uid, text: 'hello' });
  const messages = (db) => getDocs(query(collection(db, `chats/${sent.chatId}/messages`), orderBy('seq', 'desc'), limit(50)));
  assert.equal((await assertSucceeds(messages(b.db))).docs[0].data().text, 'hello');
  await assertSucceeds(messages(a.db));
  await assertFails(messages(c.db));
  const index = await assertSucceeds(getDocs(query(collection(b.db, `chatIndex/${b.uid}/chats`), orderBy('updatedAt', 'desc'))));
  assert.equal(index.docs[0].data().unread, 1);
  await assertFails(getDocs(collection(c.db, `chatIndex/${b.uid}/chats`)));
  await assertFails(setDoc(doc(a.db, `chats/${sent.chatId}/messages/x`), { text: 'forged' }));
  await b.call('socialFriendAction', { operationId: op(), action: 'remove', otherUid: a.uid });
  await assertFails(messages(a.db));
});
test('group room members read while they are the host\'s friends; removed members and outsiders cannot', async () => {
  const { a, b, c } = await fixture(); await befriend(a, b); await befriend(a, c);
  const sessionId = op(), created = await a.call('socialRoomAction', roomInput('create', { otherUid: b.uid, sessionId, content: content(), position: position() }));
  const roomId = created.roomId;
  await assertFails(getDocFromServer(doc(c.db, `rooms/${roomId}`))); // not invited yet
  await a.call('socialRoomAction', roomInput('invite', { roomId, otherUid: c.uid }));
  for (const member of [b, c]) await member.call('socialRoomAction', roomInput('join', { roomId }));
  const room = (await assertSucceeds(getDocFromServer(doc(c.db, `rooms/${roomId}`)))).data();
  assert.equal(room.schemaVersion, 2); assert.equal(room.members[b.uid].state, 'joined');
  await c.call('socialRoomMessage', { operationId: op(), roomId, kind: 'text', text: 'hi all', emoji: null });
  const lines = await assertSucceeds(getDocs(query(collection(b.db, `rooms/${roomId}/messages`), orderBy('seq', 'desc'), limit(30))));
  assert.ok(lines.docs.some((d) => d.data().text === 'hi all'));
  // Host position writes still work with three people.
  await assertSucceeds(updateDoc(doc(a.db, `rooms/${roomId}`), { position: position(1), sequence: 1, writerSessionId: sessionId, updatedAt: serverTimestamp() }));
  await a.call('socialRoomAction', roomInput('kick', { roomId, otherUid: c.uid }));
  await assertFails(getDocFromServer(doc(c.db, `rooms/${roomId}`)));
  await assertFails(getDocs(query(collection(c.db, `rooms/${roomId}/messages`), limit(5))));
  await assertSucceeds(getDocFromServer(doc(b.db, `rooms/${roomId}`)));
});
const gc = (action, fields = {}) => ({ operationId: op(), action, groupId: null, name: null, uids: null, text: null, ...fields });
test('group chats are readable by current members from when they joined, never client-writable', async () => {
  const { a, b, c } = await fixture(); await befriend(a, b); await befriend(b, c);
  const { groupId } = await a.call('socialGroupChat', gc('create', { name: 'Readers', uids: [b.uid] }));
  await a.call('socialGroupChat', gc('send', { groupId, text: 'before charlie' }));
  const since = (await adminDb.doc(`groups/${groupId}`).get()).data().next;
  await b.call('socialGroupChat', gc('add', { groupId, uids: [c.uid] }));
  await b.call('socialGroupChat', gc('send', { groupId, text: 'hi charlie' }));
  const messages = (db, from = 0) => getDocs(query(collection(db, `groups/${groupId}/messages`), where('seq', '>=', from), orderBy('seq', 'desc'), limit(50)));
  assert.ok((await assertSucceeds(messages(a.db))).docs.some((d) => d.data().text === 'before charlie'));
  // Charlie (not Alice's friend) reads from his join on, but not the history before it.
  const seen = await assertSucceeds(messages(c.db, since));
  assert.deepEqual(seen.docs.filter((d) => d.data().kind === 'text').map((d) => d.data().text), ['hi charlie']);
  await assertFails(messages(c.db, 0));
  await assertSucceeds(getDoc(doc(c.db, `groups/${groupId}`)));
  const index = await assertSucceeds(getDocs(query(collection(c.db, `groupIndex/${c.uid}/groups`), orderBy('updatedAt', 'desc'))));
  assert.equal(index.docs[0].data().unread, 1);
  await assertFails(getDocs(collection(a.db, `groupIndex/${c.uid}/groups`)));
  await assertFails(setDoc(doc(c.db, `groups/${groupId}/messages/x`), { seq: 99, text: 'forged' }));
  await assertFails(updateDoc(doc(c.db, `groups/${groupId}`), { name: 'Hijacked' }));
  // Leaving revokes reads at once.
  await c.call('socialGroupChat', gc('leave', { groupId }));
  await assertFails(getDoc(doc(c.db, `groups/${groupId}`)));
  await assertFails(messages(c.db, since));
});
test('XP is the owner\'s, visible to friends only with stats sharing, and leaves with the account', async () => {
  const { a, b, c } = await fixture(); await befriend(a, b); await befriend(a, c);
  await share(a, { globalRanking: false });
  const result = await a.call('socialRecordChapter', { operationId: op(), generation: 2, pages: 10 });
  assert.equal(result.counted, false); assert.equal(result.xp.total, 10 * xpMultiplier(Date.now())); // double XP on Fri/Sat and event weeks
  await assertSucceeds(getDocFromServer(doc(a.db, `xp/${a.uid}`)));
  await assertFails(getDocFromServer(doc(b.db, `xp/${a.uid}`)));
  await share(a, { stats: 'friends' }, 2);
  assert.equal((await assertSucceeds(getDocFromServer(doc(b.db, `xp/${a.uid}`)))).data().level, 1);
  await assertFails(setDoc(doc(a.db, `xp/${a.uid}`), { total: 99999 }));
  // Account deletion: XP goes, groups lose the member and pass ownership on.
  const { groupId } = await a.call('socialGroupChat', gc('create', { name: 'Readers', uids: [b.uid, c.uid] }));
  assert.deepEqual(await a.call('socialDeleteAccount', { confirm: 'DELETE' }), { deleted: true });
  assert.equal((await adminDb.doc(`xp/${a.uid}`).get()).exists, false);
  assert.equal((await adminDb.doc(`groupIndex/${a.uid}/groups/${groupId}`).get()).exists, false);
  const group = (await adminDb.doc(`groups/${groupId}`).get()).data();
  assert.deepEqual(group.members, [b.uid, c.uid]); assert.equal(group.ownerUid, b.uid); assert.equal(group.names[a.uid], undefined);
  const events = (await adminDb.collection(`groups/${groupId}/messages`).orderBy('seq').get()).docs.map((d) => d.data().event);
  assert.deepEqual(events.slice(-2), ['left', 'owner']);
  assert.equal((await adminDb.doc(`groupIndex/${b.uid}/groups/${groupId}`).get()).data().members, 2);
  // Previews are blanked 30 days after their message, by the scheduled cleanup.
  await b.call('socialGroupChat', gc('send', { groupId, text: 'still here' }));
  assert.equal((await adminDb.doc(`groupIndex/${c.uid}/groups/${groupId}`).get()).data().lastText, 'still here');
  assert.equal(await clearExpiredGroupPreviews(adminDb, Date.now()), 0);
  assert.ok(await clearExpiredGroupPreviews(adminDb, Date.now() + 31 * 86_400_000) >= 2);
  assert.equal((await adminDb.doc(`groupIndex/${c.uid}/groups/${groupId}`).get()).data().lastText, '');
});
test('read-along requests are readable only by the friend they were sent to', async () => {
  const { a, b, c } = await fixture(); await befriend(a, b);
  await share(b, { activity: 'friends' });
  await b.call('socialPublishActivity', { operationId: op(), generation: 2, activity: { content: content(), chapterKey: digest('c'), chapterLabel: 'Chapter 3' } });
  await a.call('socialJoinRequest', { operationId: op(), action: 'ask', otherUid: b.uid });
  const mine = await assertSucceeds(getDocs(collection(b.db, `joinRequests/${b.uid}/items`)));
  assert.equal(mine.docs[0].data().requesterUid, a.uid);
  await assertFails(getDocs(collection(a.db, `joinRequests/${b.uid}/items`)));
  await assertFails(getDocs(collection(c.db, `joinRequests/${b.uid}/items`)));
  await assertFails(setDoc(doc(a.db, `joinRequests/${b.uid}/items/${a.uid}`), { requesterUid: a.uid }));
});
test('the global ranking is readable by any signed-in user and never client-writable', async () => {
  const { a, b, c } = await fixture();
  const result = await a.call('socialRecordChapter', { operationId: op(), generation: 1, pages: 12 });
  const path = `leaderboards/${result.day}`;
  const board = (await assertSucceeds(getDocFromServer(doc(c.db, path)))).data(); // not a friend
  assert.equal(board.entries[a.uid].chapters, 1);
  assert.equal(board.entries[a.uid].pages, undefined);
  await assertFails(setDoc(doc(c.db, path), { entries: {} }));
  await share(a, { globalRanking: false }, 1);
  assert.equal((await getDocFromServer(doc(b.db, path))).data().entries[a.uid], undefined);
});
test('cover thumbnails: owner writes small images, friends read only while titles are shared', async () => {
  const { a, b, c } = await fixture(); await befriend(a, b);
  const key = content(1).contentKey, jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3]);
  const thumb = (who, uid = a.uid, k = key) => storageRef(who.storage, `coverThumbs/${uid}/${k}`);
  await assertSucceeds(uploadBytes(thumb(a), jpeg, { contentType: 'image/jpeg' }));
  await assertFails(uploadBytes(thumb(a, a.uid, 'not-a-key'), jpeg, { contentType: 'image/jpeg' }));
  await assertFails(uploadBytes(thumb(a), jpeg, { contentType: 'image/gif' }));
  await assertFails(uploadBytes(thumb(a), new Uint8Array(101 * 1024), { contentType: 'image/jpeg' }));
  await assertFails(uploadBytes(thumb(b, a.uid), jpeg, { contentType: 'image/jpeg' }));
  await assertSucceeds(getBytes(thumb(a)));
  await assertSucceeds(getBytes(thumb(b))); // new accounts share their library with friends by default
  await share(a, {});
  await assertFails(getBytes(thumb(b))); // nothing shared
  await share(a, { library: 'friends' }, 2);
  await assertSucceeds(getBytes(thumb(b)));
  await assertFails(getBytes(thumb(c))); // not a friend
  await share(a, {}, 3);
  await assertFails(getBytes(thumb(b))); // sharing stopped
});
test('profile media is owner-written within limits, friends-only readable, and wired through the style callable', async () => {
  const { a, b, c } = await fixture(); await befriend(a, b);
  // A tiny but valid GIF header is enough: rules check declared type and size, the callable checks stored metadata.
  const gif = Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7', 'base64');
  const avatar = (who, uid = a.uid) => storageRef(who.storage, `profileMedia/${uid}/avatar`);
  await assertSucceeds(uploadBytes(avatar(a), gif, { contentType: 'image/gif' }));
  await assertFails(uploadBytes(avatar(c, a.uid), gif, { contentType: 'image/gif' }));
  await assertFails(uploadBytes(storageRef(a.storage, `profileMedia/${a.uid}/wallpaper`), gif, { contentType: 'image/gif' }));
  await assertFails(uploadBytes(storageRef(a.storage, `profileMedia/${a.uid}/banner`), Buffer.from('<svg/>'), { contentType: 'image/svg+xml' }));
  await assertFails(uploadBytes(storageRef(a.storage, `profileMedia/${a.uid}/avatar`), Buffer.alloc(2 * 1024 * 1024 + 1), { contentType: 'image/png' }));
  assert.equal((await assertSucceeds(getBytes(avatar(b)))).byteLength, gif.length);
  // Not on the ranking yet (no chapter read): friends only.
  await assertFails(getBytes(avatar(c)));
  // On the all-time ranking (the default, once a chapter is recorded) the picture is visible to everyone signed in
  // except people the owner blocked, until turned off.
  await a.call('socialRecordChapter', { operationId: op(), generation: 1, pages: 10 });
  await assertSucceeds(getBytes(avatar(c)));
  await a.call('socialFriendAction', { operationId: op(), action: 'block', otherUid: c.uid });
  await assertFails(getBytes(avatar(c)));
  await a.call('socialFriendAction', { operationId: op(), action: 'unblock', otherUid: c.uid });
  await assertSucceeds(getBytes(avatar(c)));
  await share(a, { rankingMedia: false });
  await assertFails(getBytes(avatar(c)));
  await assertSucceeds(getBytes(avatar(b))); // friends still see it
  const styled = await a.call('socialUpdateProfileStyle', { operationId: op(), bio: 'hi', accent: 2, avatar: 'set', banner: 'keep' });
  assert.equal(styled.profile.avatar.contentType, 'image/gif');
  assert.equal((await b.call('socialGetFriend', { otherUid: a.uid })).profile.avatar.size, gif.length);
  assert.equal((await c.call('socialLookupHandle', { handle: a.handle })).profile.avatar, undefined);
  await callableFails(a.call('socialUpdateProfileStyle', { operationId: op(), bio: '', accent: 0, avatar: 'keep', banner: 'set' }), 'failed-precondition');
  await a.call('socialFriendAction', { operationId: op(), action: 'remove', otherUid: b.uid });
  await assertFails(getBytes(avatar(b)));
  await share(a, { rankingMedia: true }, 2);
  await assertSucceeds(getBytes(avatar(b))); // public again through the ranking
  await share(a, { invisible: true }, 3);
  await assertFails(getBytes(avatar(c))); // invisible: off the ranking, so friends-only again
  await assertFails(deleteObject(avatar(b, a.uid)));
  await assertSucceeds(deleteObject(avatar(a)));
});
test('read wishes: own list is owner-only, pair documents and limits are unreadable, matches reveal both and leave with the account', async () => {
  const { a, b, c } = await fixture(); await befriend(a, b);
  const title = content(7), wish = (action) => ({ operationId: op(), action, content: title });
  assert.deepEqual(await a.call('socialReadWish', { ...wish('add'), otherUid: b.uid }), { state: 'wished' });
  await callableFails(a.call('socialReadWish', { ...wish('add'), otherUid: c.uid }), 'permission-denied');
  const mine = `readWishIndex/${a.uid}/items/${b.uid}_${title.contentKey}`, theirs = `readWishIndex/${b.uid}/items/${a.uid}_${title.contentKey}`;
  const pair = `readWishes/${pairId(a.uid, b.uid)}/items/${title.contentKey}`;
  assert.equal((await assertSucceeds(getDoc(doc(a.db, mine)))).data().matched, false);
  await assertFails(getDoc(doc(b.db, mine))); await assertFails(getDoc(doc(c.db, mine)));
  await assertFails(getDocs(collection(b.db, `readWishIndex/${a.uid}/items`)));
  // A one-sided wish is invisible to the other person: nothing in their list, the pair document is server-only.
  assert.equal((await assertSucceeds(getDocs(collection(b.db, `readWishIndex/${b.uid}/items`)))).size, 0);
  for (const user of [a, b, c]) await assertFails(getDoc(doc(user.db, pair)));
  await assertFails(getDoc(doc(a.db, `readWishLimits/${a.uid}`)));
  await assertFails(setDoc(doc(a.db, mine), { matched: true }));
  await assertFails(setDoc(doc(b.db, theirs), { otherUid: a.uid, matched: true }));
  await assertFails(setDoc(doc(a.db, pair), { flags: {} }));
  assert.deepEqual(await b.call('socialReadWish', { ...wish('add'), otherUid: a.uid }), { state: 'matched' });
  assert.equal((await assertSucceeds(getDoc(doc(a.db, mine)))).data().matched, true);
  assert.equal((await assertSucceeds(getDoc(doc(b.db, theirs)))).data().matched, true);
  const chat = await assertSucceeds(getDocs(query(collection(a.db, `chats/${pairId(a.uid, b.uid)}/messages`), orderBy('seq', 'desc'), limit(50))));
  assert.equal(chat.docs[0].data().system, 'match'); assert.equal(chat.docs[0].data().content.title, 'Title 7');
  assert.equal((await assertSucceeds(getDoc(doc(a.db, `chatIndex/${a.uid}/chats/${b.uid}`)))).data().unread, 1);
  // Deleting the account removes the pair's flags and the friend's entry for that title.
  assert.deepEqual(await a.call('socialDeleteAccount', { confirm: 'DELETE' }), { deleted: true });
  for (const path of [pair, mine, theirs, `readWishLimits/${a.uid}`]) assert.equal((await adminDb.doc(path).get()).exists, false, path);
});
test('a room source may carry a manga URL; startChapterLabel is stored but not host-writable', async () => {
  const { a, b } = await fixture(); await befriend(a, b);
  const source = { id: '2499283573021220255', name: 'MangaDex', lang: 'en', pkg: null, url: '/title/abc-123' };
  await callableFails(a.call('socialRoomAction', roomInput('create', { otherUid: b.uid, sessionId: op(), content: { ...content(), source: { ...source, url: 'title with spaces' } } })), 'invalid-argument');
  const sessionId = op(), created = await a.call('socialRoomAction', roomInput('create', { otherUid: b.uid, sessionId, content: { ...content(), source }, position: position() }));
  await b.call('socialRoomAction', roomInput('join', { roomId: created.roomId }));
  const path = `rooms/${created.roomId}`, roomRef = doc(a.db, path);
  const remote = (await assertSucceeds(getDoc(doc(b.db, path)))).data();
  assert.equal(remote.content.source.url, '/title/abc-123'); assert.equal(remote.startChapterLabel, 'Chapter 1');
  const move = (sequence, extra = {}) => updateDoc(roomRef, { position: position(2), sequence, writerSessionId: sessionId, updatedAt: serverTimestamp(), ...extra });
  await assertSucceeds(move(1));
  await assertFails(move(2, { startChapterLabel: 'Chapter 9' }));
  await assertFails(move(2, { 'content.source.url': 'https://evil.example/x' }));
  // Rooms created before the field existed keep accepting host positions.
  await adminDb.doc(path).update({ startChapterLabel: FieldValue.delete() });
  await assertSucceeds(move(2));
  // And close with a recap once two friends read together for five minutes (cleanup path, through the Firestore adapter).
  const members = (await adminDb.doc(path).get()).data().members;
  await adminDb.doc(path).update({ [`members.${a.uid}.joinedAt`]: Date.now() - 10 * 60_000, [`members.${b.uid}.joinedAt`]: Date.now() - 10 * 60_000 });
  assert.ok(members[b.uid].joinedAt);
  const service = new SocialService(createFirestoreStore(adminDb), () => Date.now() + 120_000);
  assert.equal((await service.expireRoom(created.roomId)).expired, true);
  const messages = await assertSucceeds(getDocs(query(collection(b.db, `chats/${pairId(a.uid, b.uid)}/messages`), orderBy('seq', 'desc'), limit(50))));
  const recap = messages.docs[0].data();
  assert.equal(recap.system, 'recap'); assert.equal(recap.senderUid, a.uid);
  assert.equal(recap.recap.fromChapter, null); assert.equal(recap.recap.toChapter, 'Chapter 1'); assert.ok(recap.recap.minutes >= 10);
  const index = (await assertSucceeds(getDoc(doc(b.db, `chatIndex/${b.uid}/chats/${a.uid}`)))).data();
  assert.equal(index.lastText, '📖 Read together: Title 1'); assert.equal(index.unread, 0);
});
test('friends read library progress with the projection; strangers and stale generations stay denied', async () => {
  const { a, b, c } = await fixture(); await befriend(a, b); await share(a, { library: 'friends' });
  await a.call('socialPublishEntries', { operationId: op(), generation: 2, kind: 'library', deleteKeys: [],
    entries: [{ ...content(1), progress: { read: 40, total: 120, finished: false } }, content(2)] });
  const page = (db, generation = 2) => getDocs(query(collection(db, `sharedLibraries/${a.uid}/entries`), where('generation', '==', generation), orderBy('content.title'), limit(50)));
  const docs = (await assertSucceeds(page(b.db))).docs.map((d) => d.data());
  assert.deepEqual(docs[0].progress, { read: 40, total: 120, finished: false }); assert.equal(docs[1].progress, undefined);
  await assertFails(page(c.db));
  // "Friends who read this" reads only started entries (Android loadStartedEntries).
  const started = (db) => getDocs(query(collection(db, `sharedLibraries/${a.uid}/entries`), where('generation', '==', 2),
    where('progress.read', '>=', 0), orderBy('progress.read'), limit(100)));
  assert.deepEqual((await assertSucceeds(started(b.db))).docs.map((d) => d.data().content.title), ['Title 1']);
  await assertFails(started(c.db));
  await callableFails(a.call('socialPublishEntries', { operationId: op(), generation: 2, kind: 'favorites', deleteKeys: [],
    entries: [{ ...content(3), progress: { read: 1, total: 2, finished: false } }] }), 'invalid-argument');
  await share(a, { library: 'friends' }, 2);
  await assertFails(page(b.db, 2));
  // Progress sharing off: the server stores entries without progress, whatever the app sends.
  await share(a, { library: 'friends', progress: false }, 3);
  assert.equal((await b.call('socialGetFriend', { otherUid: a.uid })).sharing.progress, false);
  await a.call('socialPublishEntries', { operationId: op(), generation: 4, kind: 'library', deleteKeys: [],
    entries: [{ ...content(1), progress: { read: 41, total: 120, finished: false } }] });
  assert.equal((await adminDb.doc(`sharedLibraries/${a.uid}/entries/${content(1).contentKey}`).get()).data().progress, undefined);
});
test('reviews: owner and friends read while the switch is on and the owner is visible; never client-writable; gone with the account', async () => {
  const { a, b, c } = await fixture(); await befriend(a, b);
  const set = await a.call('socialReview', { operationId: op(), action: 'set', content: content(1), rating: 4, text: 'Great art.' });
  assert.equal(set.workKey, set.review.workKey);
  const path = `reviews/${a.uid}/items/${set.workKey}`;
  const list = (db) => getDocs(query(collection(db, `reviews/${a.uid}/items`), orderBy('updatedAt', 'desc'), limit(50)));
  // On by default: friends see the stars and text; strangers don't.
  const seen = (await assertSucceeds(list(b.db))).docs.map((d) => d.data());
  assert.equal(seen.length, 1); assert.equal(seen[0].rating, 4); assert.equal(seen[0].text, 'Great art.'); assert.equal(seen[0].content.title, 'Title 1');
  await assertSucceeds(getDocFromServer(doc(a.db, path)));
  await assertFails(getDocFromServer(doc(c.db, path))); await assertFails(list(c.db));
  for (const db of [a.db, b.db]) {
    await assertFails(setDoc(doc(db, path), { rating: 5 })); await assertFails(updateDoc(doc(db, path), { rating: 1 })); await assertFails(deleteDoc(doc(db, path)));
  }
  await assertFails(setDoc(doc(a.db, `reviews/${a.uid}/items/${digest('mine')}`), { rating: 5 }));
  await assertFails(getDocFromServer(doc(a.db, `reviewLimits/${a.uid}`)));
  // Switch off, or invisible: friends lose access at once; the owner keeps it.
  await share(a, { reviews: false });
  await assertFails(list(b.db)); await assertSucceeds(list(a.db));
  await share(a, { reviews: true, invisible: true }, 2);
  await assertFails(getDocFromServer(doc(b.db, path)));
  await share(a, { reviews: true }, 3);
  await assertSucceeds(getDocFromServer(doc(b.db, path)));
  // Settings written before the switch existed: absent means on.
  await adminDb.doc(`sharingSettings/${a.uid}`).update({ reviews: FieldValue.delete() });
  await assertSucceeds(list(b.db));
  // Hiding a title deletes my review of it (found by a query inside the sharing transaction); others stay.
  const other = await a.call('socialReview', { operationId: op(), action: 'set', content: content(2), rating: 2, text: null });
  await share(a, {}, 4, content(2).contentKey);
  assert.equal((await adminDb.doc(`reviews/${a.uid}/items/${other.workKey}`).get()).exists, false);
  assert.equal((await adminDb.doc(path).get()).exists, true);
  // Unfriending or blocking revokes; account deletion removes the reviews and the counter.
  await b.call('socialFriendAction', { operationId: op(), action: 'block', otherUid: a.uid });
  await assertFails(list(b.db));
  assert.deepEqual(await a.call('socialDeleteAccount', { confirm: 'DELETE' }), { deleted: true });
  for (const gone of [path, `reviewLimits/${a.uid}`]) assert.equal((await adminDb.doc(gone).get()).exists, false, gone);
});
test('open room cards: friends who see the activity read title and count only; members see each other positions', async () => {
  const { a, b, c } = await fixture(); const d = await account('dave');
  await befriend(a, b); await befriend(a, c);
  const sessionId = op();
  const created = await a.call('socialRoomAction', roomInput('create', { otherUid: b.uid, sessionId, content: content(), position: position(), open: true }));
  const roomId = created.roomId;
  await b.call('socialRoomAction', roomInput('join', { roomId }));
  const card = (await assertSucceeds(getDocFromServer(doc(c.db, `openRooms/${a.uid}`)))).data();
  assert.deepEqual(Object.keys(card).sort(), ['generation', 'hostUid', 'leaseExpiresAt', 'maxMembers', 'memberCount', 'roomId', 'schemaVersion', 'title', 'updatedAt']);
  assert.equal(card.roomId, roomId); assert.equal(card.memberCount, 2);
  await assertFails(getDocFromServer(doc(d.db, `openRooms/${a.uid}`)));
  await assertFails(getDocFromServer(doc(c.db, `rooms/${roomId}`))); // the card is not the room
  await assertFails(setDoc(doc(a.db, `openRooms/${a.uid}`), card));
  // Dropping in opens the room to c; a stranger can't.
  await callableFails(d.call('socialRoomAction', roomInput('join', { roomId })), 'permission-denied');
  await c.call('socialRoomAction', roomInput('join', { roomId }));
  await assertSucceeds(getDocFromServer(doc(c.db, `rooms/${roomId}`)));
  // Positions land in the server-written member snapshot; clients can't write members or open.
  await c.call('socialRoomAction', roomInput('position', { roomId, position: { chapterKey: digest('chapter1'), pageIndex: 7 } }));
  const seen = (await assertSucceeds(getDocFromServer(doc(b.db, `rooms/${roomId}`)))).data();
  assert.equal(seen.members[c.uid].page.pageIndex, 7); assert.equal(seen.open, true);
  await assertFails(updateDoc(doc(c.db, `rooms/${roomId}`), { [`members.${c.uid}.page.pageIndex`]: 9 }));
  await assertFails(updateDoc(doc(a.db, `rooms/${roomId}`), { open: false }));
  // Host position writes still pass with the open flag on the room.
  await assertSucceeds(updateDoc(doc(a.db, `rooms/${roomId}`), { position: position(2), sequence: 1, writerSessionId: sessionId, updatedAt: serverTimestamp() }));
  // A stale generation hides the card; a privacy change removes it (an absent card reads as no open room).
  await adminDb.doc(`openRooms/${a.uid}`).update({ generation: card.generation - 1 });
  await assertFails(getDocFromServer(doc(c.db, `openRooms/${a.uid}`)));
  await share(a, { activity: 'friends' }, card.generation);
  assert.equal((await assertSucceeds(getDocFromServer(doc(c.db, `openRooms/${a.uid}`)))).exists(), false);
  assert.equal((await adminDb.doc(`rooms/${roomId}`).get()).data().open, false);
  // Activity not shared: nothing is readable, not even absence.
  await share(a, { activity: 'private' }, card.generation + 1);
  await assertFails(getDocFromServer(doc(c.db, `openRooms/${a.uid}`)));
});
test('web library signaling is owner-only, shape-checked, short-lived and leaves with the account', async () => {
  const { a, b } = await fixture();
  const deviceId = 'phone_abcdefghij0123', inMinutes = (m) => ClientTimestamp.fromMillis(Date.now() + m * 60_000);
  const phone = (db, uid = a.uid, fields = {}) => setDoc(doc(db, `libraryHosts/${uid}/phones/${deviceId}`),
    { name: 'Galaxy A35', appBuild: 8300, protocol: 1, updatedAt: serverTimestamp(), expiresAt: inMinutes(3), ...fields });
  await assertSucceeds(phone(a.db));
  await assertSucceeds(phone(a.db));
  await assertSucceeds(getDocs(query(collection(a.db, `libraryHosts/${a.uid}/phones`), where('expiresAt', '>', ClientTimestamp.now()))));
  await assertFails(getDocs(collection(b.db, `libraryHosts/${a.uid}/phones`)));
  await assertFails(phone(b.db));
  await assertFails(phone(a.db, a.uid, { expiresAt: inMinutes(60) }));
  await assertFails(phone(a.db, a.uid, { name: 'x'.repeat(61) }));
  await assertFails(phone(a.db, a.uid, { library: ['nope'] }));
  await assertFails(setDoc(doc(a.db, `libraryHosts/${a.uid}/phones/short`), { name: 'P', appBuild: 1, protocol: 1, updatedAt: serverTimestamp(), expiresAt: inMinutes(3) }));

  const linkId = randomUUID(), linkPath = `libraryLinks/${a.uid}/links/${linkId}`;
  const offer = (fields = {}) => ({ deviceId, offer: 'v=0 offer', state: 'offered', createdAt: serverTimestamp(), expiresAt: inMinutes(10), ...fields });
  await assertFails(setDoc(doc(b.db, `libraryLinks/${a.uid}/links/${randomUUID()}`), offer()));
  await assertFails(setDoc(doc(a.db, `libraryLinks/${a.uid}/links/${randomUUID()}`), offer({ offer: 'x'.repeat(20_001) })));
  await assertFails(setDoc(doc(a.db, `libraryLinks/${a.uid}/links/${randomUUID()}`), offer({ state: 'answered', answer: 'v=0' })));
  await assertFails(setDoc(doc(a.db, `libraryLinks/${a.uid}/links/${randomUUID()}`), offer({ expiresAt: inMinutes(60) })));
  await assertFails(setDoc(doc(a.db, `libraryLinks/${a.uid}/links/not-a-uuid`), offer()));
  await assertSucceeds(setDoc(doc(a.db, linkPath), offer()));
  // The phone (same account) listens for offers and answers; nobody else sees or touches them.
  await assertSucceeds(getDocs(query(collection(a.db, `libraryLinks/${a.uid}/links`), where('deviceId', '==', deviceId), where('state', '==', 'offered'))));
  await assertFails(getDoc(doc(b.db, linkPath)));
  await assertFails(updateDoc(doc(b.db, linkPath), { answer: 'v=0 evil', state: 'answered' }));
  await assertFails(updateDoc(doc(a.db, linkPath), { offer: 'v=0 changed' }));
  await assertFails(updateDoc(doc(a.db, linkPath), { answer: 'x'.repeat(20_001), state: 'answered' }));
  await assertSucceeds(updateDoc(doc(a.db, linkPath), { answer: 'v=0 answer', state: 'answered' }));
  await assertFails(updateDoc(doc(a.db, linkPath), { state: 'offered' }));
  await assertSucceeds(updateDoc(doc(a.db, linkPath), { state: 'closed' }));
  await assertFails(deleteDoc(doc(b.db, linkPath)));

  assert.deepEqual(await a.call('socialDeleteAccount', { confirm: 'DELETE' }), { deleted: true });
  for (const path of [`libraryHosts/${a.uid}/phones/${deviceId}`, linkPath]) assert.equal((await adminDb.doc(path).get()).exists, false, path);
});
test('invisible hides a library published while invisible, and an activity written for it', async () => {
  const { a, b } = await fixture(); await befriend(a, b);
  await share(a, { library: 'friends', activity: 'friends', invisible: true });
  // The app's Publish button still works while invisible; friends must not see the result.
  await a.call('socialPublishEntries', { operationId: op(), generation: 2, kind: 'library', entries: [content(1)], deleteKeys: [] });
  await assertSucceeds(getDocFromServer(doc(a.db, `sharedLibraries/${a.uid}/entries/${content(1).contentKey}`)));
  await assertFails(getDocFromServer(doc(b.db, `sharedLibraries/${a.uid}/entries/${content(1).contentKey}`)));
  // Even an activity written directly (older server, race) stays hidden while invisible.
  await adminDb.doc(`activities/${a.uid}`).set({ schemaVersion: 1, content: content(1), chapterKey: content(2).contentKey, chapterLabel: 'Ch.1',
    generation: 2, updatedAt: Date.now(), expiresAt: Timestamp.fromMillis(Date.now() + 120_000) });
  await assertFails(getDocFromServer(doc(b.db, `activities/${a.uid}`)));
  // Back to visible: a deliberate republish is readable again.
  await share(a, { library: 'friends', activity: 'friends' }, 2);
  await a.call('socialPublishEntries', { operationId: op(), generation: 3, kind: 'library', entries: [content(1)], deleteKeys: [] });
  await assertSucceeds(getDocFromServer(doc(b.db, `sharedLibraries/${a.uid}/entries/${content(1).contentKey}`)));
});
test('deleting an account leaves a room of three going for the other two', async () => {
  const { a, b, c } = await fixture(); await befriend(a, b); await befriend(a, c);
  const room = await a.call('socialRoomAction', roomInput('create', { otherUid: b.uid, sessionId: op(), content: content(1) }));
  await a.call('socialRoomAction', roomInput('invite', { roomId: room.roomId, otherUid: c.uid }));
  await b.call('socialRoomAction', roomInput('join', { roomId: room.roomId }));
  await c.call('socialRoomAction', roomInput('join', { roomId: room.roomId }));
  assert.deepEqual(await c.call('socialDeleteAccount', { confirm: 'DELETE' }), { deleted: true });
  const after = (await adminDb.doc(`rooms/${room.roomId}`).get()).data();
  assert.equal(after.state, 'active');
  assert.deepEqual(after.participants, [a.uid, b.uid]);
  assert.equal(after.members[c.uid], undefined);
  assert.equal((await adminDb.doc(`roomInvites/${b.uid}/items/${room.roomId}`).get()).data().state, 'active');
  assert.equal((await adminDb.doc(`roomInvites/${c.uid}/items/${room.roomId}`).get()).exists, false);
  // The two who stay can still read the room and chat in it.
  await assertSucceeds(getDocFromServer(doc(b.db, `rooms/${room.roomId}`)));
  await b.call('socialRoomMessage', { operationId: op(), roomId: room.roomId, kind: 'text', text: 'still here', emoji: null });
});
