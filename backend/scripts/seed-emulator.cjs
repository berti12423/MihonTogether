// Seeds the LOCAL emulators (demo-mihon-together only) with smoke accounts for manual UI checks:
// alice_smoke and bob_smoke are friends; carol_smoke is a stranger. Bob and Carol read a few chapters today, and Bob
// shares a 130-title library with covers (to try the grid's paging).
// Usage (emulators running): node backend/scripts/seed-emulator.cjs
const { randomUUID } = require('node:crypto');
const { initializeApp, deleteApp } = require('firebase/app');
const { getAuth, connectAuthEmulator, createUserWithEmailAndPassword, signInWithEmailAndPassword } = require('firebase/auth');
const { getFunctions, connectFunctionsEmulator, httpsCallable } = require('firebase/functions');
const accounts = require('../../scripts/emulator-smoke-accounts.json').accounts;

const PROJECT = 'demo-mihon-together';
const people = [...accounts, { ...accounts[0], handle: 'carol_smoke', display: 'Carol Smoke', email: 'carol.smoke@example.test' }];
const op = () => randomUUID();

async function session(person) {
  const app = initializeApp({ projectId: PROJECT, apiKey: 'demo-key' }, person.handle);
  const auth = getAuth(app);
  connectAuthEmulator(auth, 'http://127.0.0.1:9099', { disableWarnings: true });
  const functions = getFunctions(app, 'europe-west1');
  connectFunctionsEmulator(functions, '127.0.0.1', 5001);
  const user = await createUserWithEmailAndPassword(auth, person.email, person.password)
    .catch(() => signInWithEmailAndPassword(auth, person.email, person.password));
  const call = async (name, input) => (await httpsCallable(functions, name)(input)).data;
  await call('socialUpdateProfile', { operationId: op(), handle: person.handle, displayName: person.display });
  return { app, uid: user.user.uid, call };
}

(async () => {
  const sessions = [];
  for (const person of people) sessions.push(await session(person));
  const [a, b, c] = sessions;
  await a.call('socialFriendAction', { operationId: op(), action: 'request', otherUid: b.uid });
  await b.call('socialFriendAction', { operationId: op(), action: 'accept', otherUid: a.uid });
  const settings = { library: 'private', favorites: 'private', activity: 'private', presence: 'private', lastSeen: 'private',
    feed: 'private', stats: 'friends', invisible: false };
  await b.call('socialUpdateSharing', { operationId: op(), expectedGeneration: 1, settings: { ...settings, library: 'friends' }, hideContentKey: null });
  const key = (text) => require('node:crypto').createHash('sha256').update(text).digest('hex');
  const titles = Array.from({ length: 130 }, (_, i) => ({ contentKey: key(`seed-${i}`), title: `${['Solo', 'Tower', 'Omniscient', 'Return', 'Villain'][i % 5]} Story ${i + 1}`,
    sourceKey: 'source-v1:seed', sourceWorkKey: key(`work-${i}`), editionKey: key(`edition-${i}`), canonicalProvider: null, canonicalId: null,
    coverUrl: `https://picsum.photos/seed/mihon${i}/300/450` }));
  for (let i = 0; i < titles.length; i += 50) {
    await b.call('socialPublishEntries', { operationId: op(), generation: 2, kind: 'library', entries: titles.slice(i, i + 50), deleteKeys: [] });
  }
  const reads = [[b, 2, 2], [c, 1, 4]];
  for (const [who, generation, count] of reads) {
    for (let i = 0; i < count; i++) {
      await who.call('socialRecordChapter', { operationId: op(), generation, pages: 20 });
      if (i < count - 1) await new Promise((resolve) => setTimeout(resolve, 10_500)); // server minimum gap
    }
  }
  for (const s of sessions) await deleteApp(s.app);
  console.log('seeded', people.map((p) => p.handle).join(', '));
})().catch((error) => { console.error(error); process.exit(1); });
