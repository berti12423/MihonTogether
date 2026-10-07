const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { SocialService, digest, pairId, DomainError, FEED_SLOTS, FEED_TTL, ROOM_MESSAGE_SLOTS, DAILY_LIMITS, statsDay, LEADERBOARD_SIZE, XP, xpForLevel, levelFor, MAX_FREEZES, CHALLENGE_XP, CHALLENGES, CHALLENGE_ROTATION, monthOf, challengesFor, seasonBadge, GROUP_MAX_MEMBERS, weekOf, isReactionEmoji, REACTIONS_PER_MINUTE, RECRUIT, DOUBLE_XP, xpMultiplier } = require('../lib/domain.js');

class MemoryStore {
  docs = new Map();
  tail = Promise.resolve();
  retryOnce = false;
  transact(body) {
    const operation = this.tail.then(async () => {
      const attempt = async (commit) => {
        const staged = new Map([...this.docs].map(([path, data]) => [path, structuredClone(data)]));
        const result = await body({
          get: async (path) => structuredClone(staged.get(path) ?? null),
          query: async (collection, field, value) => [...staged].filter(([path, data]) => path.startsWith(`${collection}/`) &&
            !path.slice(collection.length + 1).includes('/') && field.split('.').reduce((v, name) => v?.[name], data) === value)
            .map(([path, data]) => ({ path, data: structuredClone(data) })),
          set: (path, data) => staged.set(path, structuredClone(data)),
          delete: (path) => staged.delete(path),
        });
        if (commit) this.docs = staged;
        return result;
      };
      if (this.retryOnce) { this.retryOnce = false; await attempt(false); }
      return attempt(true);
    });
    this.tail = operation.catch(() => {});
    return operation;
  }
}
const op = () => randomUUID();
const content = (id = 1) => ({ contentKey: digest(`work-${id}`), title: `Title ${id}`, sourceKey: 'extension:en:source',
  sourceWorkKey: digest(`source-${id}`), editionKey: digest(`edition-${id}`), canonicalProvider: null, canonicalId: null });
const position = (pageIndex = 0) => ({ chapterKey: digest('chapter1'), chapterLabel: 'Chapter 1', pageIndex, pageCount: 20, pageMappingKey: digest('mapping1') });
const settings = (overrides = {}) => ({ library: 'private', favorites: 'private', activity: 'private', presence: 'private', lastSeen: 'private', feed: 'private', stats: 'private', invisible: false, ...overrides });
const roomInput = (action, fields = {}) => ({ operationId: op(), action, roomId: null, otherUid: null, sessionId: null, content: null, position: null, ...fields });
async function fixture() {
  const store = new MemoryStore(); let now = 1_900_000_000_000;
  const media = new Map();
  const pushes = [];
  const service = new SocialService(store, () => now, { inspect: async (path) => media.get(path) ?? null }, { send: async (notices) => { pushes.push(...notices); } });
  const call = (name, actor, input) => service.call(name, actor, input);
  for (const actor of ['alice', 'bob', 'charlie']) await call('socialUpdateProfile', actor, { operationId: op(), handle: actor, displayName: actor });
  return { store, service, call, media, pushes, advance: (ms) => { now += ms; }, now: () => now };
}
async function befriend(f, a = 'alice', b = 'bob') {
  await f.call('socialFriendAction', a, { operationId: op(), action: 'request', otherUid: b });
  return f.call('socialFriendAction', b, { operationId: op(), action: 'accept', otherUid: a });
}
async function share(f, overrides, actor = 'alice', expectedGeneration = 1, hideContentKey = null) {
  return f.call('socialUpdateSharing', actor, { operationId: op(), expectedGeneration, settings: settings(overrides), hideContentKey });
}
const rejects = (promise, code) => assert.rejects(promise, (error) => error instanceof DomainError && error.code === code);

test('unauthenticated, extra fields, type and ownership spoofing are rejected', async () => {
  const f = await fixture();
  await rejects(f.call('socialGetSelf', null, {}), 'unauthenticated');
  await rejects(f.call('socialGetSelf', 'alice', { uid: 'bob' }), 'invalid-argument');
  await rejects(f.call('socialUpdateProfile', 'alice', { operationId: op(), handle: 'valid', displayName: 'x'.repeat(41) }), 'invalid-argument');
  await rejects(f.call('socialFriendAction', 'alice', { operationId: op(), action: 'request', otherUid: 'alice' }), 'invalid-argument');
  await rejects(f.call('socialUpdateSharing', 'alice', { operationId: op(), expectedGeneration: 1, settings: { ...settings(), role: 'admin' }, hideContentKey: null }), 'invalid-argument');
});
test('new accounts share with friends by default; profile handle claims are independent from Auth PII', async () => {
  const f = await fixture(), own = await f.call('socialGetSelf', 'alice', {});
  assert.equal(own.settings.generation, 1); assert.equal(own.settings.library, 'friends'); assert.equal(own.settings.stats, 'friends'); assert.equal(own.settings.invisible, false); assert.equal(own.profile.email, undefined);
  await rejects(f.call('socialUpdateProfile', 'bob', { operationId: op(), handle: 'alice', displayName: 'Alice' }), 'already-exists');
  assert.equal((await f.call('socialLookupHandle', 'charlie', { handle: 'alice' })).profile.uid, 'alice');
});
test('only recipient accepts; acceptance is reciprocal and atomic', async () => {
  const f = await fixture();
  await f.call('socialFriendAction', 'alice', { operationId: op(), action: 'request', otherUid: 'bob' });
  await rejects(f.call('socialFriendAction', 'alice', { operationId: op(), action: 'accept', otherUid: 'bob' }), 'permission-denied');
  await rejects(f.call('socialGetFriend', 'bob', { otherUid: 'alice' }), 'permission-denied');
  await f.call('socialFriendAction', 'bob', { operationId: op(), action: 'accept', otherUid: 'alice' });
  assert.equal(f.store.docs.get('friendships/alice/accepted/bob').friendUid, 'bob');
  assert.equal(f.store.docs.get('friendships/bob/accepted/alice').friendUid, 'alice');
  assert.equal((await f.call('socialGetFriend', 'bob', { otherUid: 'alice' })).profile.uid, 'alice');
  await rejects(f.call('socialGetFriend', 'charlie', { otherUid: 'alice' }), 'permission-denied');
});
test('cancel and reject enforce participants; duplicate crossed requests preserve the inviter', async () => {
  const f = await fixture();
  await f.call('socialFriendAction', 'alice', { operationId: op(), action: 'request', otherUid: 'bob' });
  await f.call('socialFriendAction', 'bob', { operationId: op(), action: 'request', otherUid: 'alice' });
  assert.equal(f.store.docs.get(`friendRequests/${pairId('alice', 'bob')}`).senderUid, 'alice');
  await rejects(f.call('socialFriendAction', 'bob', { operationId: op(), action: 'cancel', otherUid: 'alice' }), 'permission-denied');
  await f.call('socialFriendAction', 'bob', { operationId: op(), action: 'reject', otherUid: 'alice' });
  await f.call('socialFriendAction', 'alice', { operationId: op(), action: 'request', otherUid: 'bob' });
  assert.equal((await f.call('socialFriendAction', 'alice', { operationId: op(), action: 'cancel', otherUid: 'bob' })).state, 'cancelled');
});
test('idempotency retries preserve state and reject reuse with different payload', async () => {
  const f = await fixture(), input = { operationId: op(), action: 'request', otherUid: 'bob' };
  assert.deepEqual(await f.call('socialFriendAction', 'alice', input), await f.call('socialFriendAction', 'alice', input));
  await rejects(f.call('socialFriendAction', 'alice', { ...input, otherUid: 'charlie' }), 'failed-precondition');
  const sharingInput = { operationId: op(), expectedGeneration: 1, settings: settings({ library: 'friends' }), hideContentKey: null };
  await f.call('socialUpdateSharing', 'alice', sharingInput); await f.call('socialUpdateSharing', 'alice', sharingInput);
  assert.equal(f.store.docs.get('sharingSettings/alice').generation, 2);
});
test('block removes both edges, blocks both lookup directions, and unblock does not restore friendship', async () => {
  const f = await fixture(); await befriend(f);
  await f.call('socialFriendAction', 'alice', { operationId: op(), action: 'block', otherUid: 'bob' });
  assert.equal(f.store.docs.has('friendships/alice/accepted/bob'), false); assert.equal(f.store.docs.has('friendships/bob/accepted/alice'), false);
  await rejects(f.call('socialGetFriend', 'bob', { otherUid: 'alice' }), 'permission-denied');
  await rejects(f.call('socialLookupHandle', 'bob', { handle: 'alice' }), 'permission-denied');
  await rejects(f.call('socialFriendAction', 'bob', { operationId: op(), action: 'request', otherUid: 'alice' }), 'permission-denied');
  await f.call('socialFriendAction', 'alice', { operationId: op(), action: 'unblock', otherUid: 'bob' });
  await rejects(f.call('socialGetFriend', 'bob', { otherUid: 'alice' }), 'permission-denied');
});
test('remove revokes reciprocal friendship without blocking future requests', async () => {
  const f = await fixture(); await befriend(f);
  await f.call('socialFriendAction', 'alice', { operationId: op(), action: 'remove', otherUid: 'bob' });
  await rejects(f.call('socialGetFriend', 'bob', { otherUid: 'alice' }), 'permission-denied');
  assert.equal((await f.call('socialFriendAction', 'bob', { operationId: op(), action: 'request', otherUid: 'alice' })).state, 'pending');
});
test('full libraries publish all pages; favorites remain independent and oversize pages fail', async () => {
  const f = await fixture(); await share(f, { library: 'friends' });
  const entries = Array.from({ length: 123 }, (_, index) => content(index));
  for (let index = 0; index < entries.length; index += 50) {
    const page = entries.slice(index, index + 50);
    assert.equal((await f.call('socialPublishEntries', 'alice', { operationId: op(), generation: 2, kind: 'library', entries: page, deleteKeys: [] })).published, page.length);
  }
  assert.equal([...f.store.docs.keys()].filter((path) => path.startsWith('sharedLibraries/alice/entries/')).length, 123);
  assert.equal([...f.store.docs.keys()].filter((path) => path.startsWith('sharedFavorites/')).length, 0);
  await rejects(f.call('socialPublishEntries', 'alice', { operationId: op(), generation: 2, kind: 'library', entries: entries.slice(0, 51), deleteKeys: [] }), 'invalid-argument');
  await rejects(f.call('socialPublishEntries', 'alice', { operationId: op(), generation: 2, kind: 'favorites', entries: [content()], deleteKeys: [] }), 'failed-precondition');
});
test('privacy generation fences stale uploads and hidden titles cannot reappear', async () => {
  const f = await fixture(); await share(f, { library: 'friends', activity: 'friends' });
  const entry = content(), upload = (gen) => f.call('socialPublishEntries', 'alice', { operationId: op(), generation: gen, kind: 'library', entries: [entry], deleteKeys: [] });
  await upload(2);
  await share(f, { library: 'friends', activity: 'friends' }, 'alice', 2, entry.contentKey);
  assert.equal(f.store.docs.has(`sharedLibraries/alice/entries/${entry.contentKey}`), false);
  await rejects(upload(2), 'failed-precondition'); await rejects(upload(3), 'failed-precondition');
  await share(f, {}, 'alice', 3);
  await rejects(f.call('socialPublishActivity', 'alice', { operationId: op(), generation: 3, activity: { content: content(2), chapterKey: digest('c'), chapterLabel: 'Chapter' } }), 'failed-precondition');
});
test('projection rejects duplicate, injected credentials and malformed canonical pairs', async () => {
  const f = await fixture(); await share(f, { library: 'friends' });
  for (const entries of [[content(), content()], [{ ...content(), cookie: 'secret' }], [{ ...content(), canonicalId: '1' }]]) {
    await rejects(f.call('socialPublishEntries', 'alice', { operationId: op(), generation: 2, kind: 'library', entries, deleteKeys: [] }), 'invalid-argument');
  }
});
test('content may carry an https cover URL and nothing else', async () => {
  const f = await fixture(); await share(f, { library: 'friends' });
  const publish = (entry) => f.call('socialPublishEntries', 'alice', { operationId: op(), generation: 2, kind: 'library', entries: [entry], deleteKeys: [] });
  await publish({ ...content(1), coverUrl: 'https://cdn.example/covers/1.jpg' });
  assert.equal(f.store.docs.get(`sharedLibraries/alice/entries/${content(1).contentKey}`).content.coverUrl, 'https://cdn.example/covers/1.jpg');
  await publish(content(2)); // still optional
  for (const coverUrl of ['http://cdn.example/a.jpg', 'file:///sdcard/a.jpg', 'https://a b', `https://${'a'.repeat(500)}`, 42, null]) {
    await rejects(publish({ ...content(3), coverUrl }), 'invalid-argument');
  }
});
test('enum validation rejects coercible arrays, numbers and objects', async () => {
  const f = await fixture();
  for (const invalid of [['friends'], 1, { toString: 'friends' }]) {
    await rejects(f.call('socialUpdateSharing', 'alice', { operationId: op(), expectedGeneration: 1, settings: settings({ library: invalid }), hideContentKey: null }), 'invalid-argument');
  }
  await share(f, { library: 'friends' });
  for (const invalid of [['anilist'], 1, { provider: 'anilist' }]) {
    await rejects(f.call('socialPublishEntries', 'alice', { operationId: op(), generation: 2, kind: 'library', entries: [{ ...content(), canonicalProvider: invalid, canonicalId: '1' }], deleteKeys: [] }), 'invalid-argument');
  }
});
test('activity expiry is bounded and clearing works after opt-out', async () => {
  const f = await fixture(); await share(f, { activity: 'friends' });
  assert.equal((await f.call('socialPublishActivity', 'alice', { operationId: op(), generation: 2, activity: { content: content(), chapterKey: digest('c'), chapterLabel: 'Chapter' } })).expiresAt, f.now() + 120_000);
  await share(f, {}, 'alice', 2);
  assert.deepEqual(await f.call('socialPublishActivity', 'alice', { operationId: op(), generation: 1, activity: null }), { expiresAt: null });
  assert.equal(f.store.docs.has('activities/alice'), false);
});
test('room invitation, explicit join, host session heartbeat and terminal close', async () => {
  const f = await fixture(); await befriend(f);
  const sessionId = op(), created = await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId, content: content(), position: position() }));
  assert.equal(created.room.state, 'invited');
  assert.equal(f.store.docs.get(`roomInvites/bob/items/${created.roomId}`).state, 'invited');
  await rejects(f.call('socialRoomAction', 'alice', roomInput('join', { roomId: created.roomId })), 'permission-denied');
  await rejects(f.call('socialRoomAction', 'charlie', roomInput('join', { roomId: created.roomId })), 'permission-denied');
  const joined = await f.call('socialRoomAction', 'bob', roomInput('join', { roomId: created.roomId })); assert.equal(joined.room.state, 'active');
  assert.equal(f.store.docs.get(`roomInvites/alice/items/${created.roomId}`).state, 'active');
  await rejects(f.call('socialRoomAction', 'alice', roomInput('heartbeat', { roomId: created.roomId, sessionId: op() })), 'permission-denied');
  f.advance(30_000);
  assert.equal((await f.call('socialRoomAction', 'alice', roomInput('heartbeat', { roomId: created.roomId, sessionId }))).room.leaseExpiresAt, f.now() + 90_000);
  assert.equal((await f.call('socialRoomAction', 'bob', roomInput('leave', { roomId: created.roomId }))).room.state, 'closed');
  assert.equal(f.store.docs.has(`roomLeases/${created.roomId}`), false);
  assert.equal(f.store.docs.get(`roomInvites/bob/items/${created.roomId}`).state, 'closed');
});
test('participants can still leave or close an expired room; outsiders cannot', async () => {
  const f = await fixture(); await befriend(f);
  const sessionId = op(), created = await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId, content: content() }));
  await f.call('socialRoomAction', 'bob', roomInput('join', { roomId: created.roomId }));
  f.advance(90_001);
  await rejects(f.call('socialRoomAction', 'charlie', roomInput('leave', { roomId: created.roomId })), 'permission-denied');
  const left = await f.call('socialRoomAction', 'alice', roomInput('leave', { roomId: created.roomId }));
  assert.equal(left.room.state, 'closed');
  assert.equal(f.store.docs.has(`roomLeases/${created.roomId}`), false);
  assert.equal(f.store.docs.get(`roomInvites/bob/items/${created.roomId}`).state, 'closed');
  // The other participant's later leave is a no-op close, not a stuck "pending" retraction.
  const again = await f.call('socialRoomAction', 'bob', roomInput('leave', { roomId: created.roomId }));
  assert.equal(again.room.state, 'closed'); assert.equal(again.room.epoch, left.room.epoch);
  await rejects(f.call('socialRoomAction', 'bob', roomInput('join', { roomId: created.roomId })), 'not-found');
});
test('room cannot resume after lease expiry, accept invalid positions, or survive friendship removal', async () => {
  const f = await fixture(); await befriend(f);
  await rejects(f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: content(), position: position(20) })), 'invalid-argument');
  for (const stripPosition of [-0.1, 20, 'x', Number.NaN]) {
    await rejects(f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: content(), position: { ...position(), stripPosition } })), 'invalid-argument');
  }
  await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: content(9), position: { ...position(9), stripPosition: 9.37 } }));
  const sessionId = op(), created = await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId, content: content() }));
  f.advance(90_001);
  await rejects(f.call('socialRoomAction', 'bob', roomInput('join', { roomId: created.roomId })), 'failed-precondition');
  await rejects(f.call('socialRoomAction', 'alice', roomInput('heartbeat', { roomId: created.roomId, sessionId })), 'failed-precondition');
  const second = await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId, content: content() }));
  await f.call('socialFriendAction', 'alice', { operationId: op(), action: 'remove', otherUid: 'bob' });
  // The host's next heartbeat drops the ex-friend; with nobody left the room ends.
  const pruned = await f.call('socialRoomAction', 'alice', roomInput('heartbeat', { roomId: second.roomId, sessionId }));
  assert.equal(pruned.room.state, 'closed');
  assert.equal(f.store.docs.get(`roomInvites/bob/items/${second.roomId}`).state, 'closed');
  await rejects(f.call('socialRoomAction', 'bob', roomInput('join', { roomId: second.roomId })), 'not-found');
});
test('presence aggregates devices and only final disconnect advances last-seen monotonically', async () => {
  const f = await fixture();
  await f.service.reconcilePresence('alice', async () => true);
  f.advance(10_000); await f.service.reconcilePresence('alice', async () => true);
  assert.equal(f.store.docs.has('lastSeen/alice'), false);
  f.advance(10_000); await f.service.reconcilePresence('alice', async () => false);
  const last = f.store.docs.get('lastSeen/alice').lastSeenAt;
  f.advance(10_000); await f.service.reconcilePresence('alice', async () => false);
  assert.equal(f.store.docs.get('lastSeen/alice').lastSeenAt, last);
  f.store.docs.set('lastSeen/alice', { schemaVersion: 1, lastSeenAt: f.now() + 1_000_000 });
  await f.service.reconcilePresence('alice', async () => true); await f.service.reconcilePresence('alice', async () => false);
  assert.equal(f.store.docs.get('lastSeen/alice').lastSeenAt, f.now() + 1_000_000);
});
test('going offline on every device clears the reading activity', async () => {
  const f = await fixture(); await f.service.reconcilePresence('alice', async () => true);
  f.store.docs.set('activities/alice', { schemaVersion: 1, generation: 1, expiresAt: f.now() + 120_000 });
  await f.service.reconcilePresence('alice', async () => true);
  assert.equal(f.store.docs.has('activities/alice'), true);
  await f.service.reconcilePresence('alice', async () => false);
  assert.equal(f.store.docs.has('activities/alice'), false);
});
test('presence retries reread current signals and discard a stale earlier attempt', async () => {
  const f = await fixture(); await f.service.reconcilePresence('alice', async () => true);
  f.store.retryOnce = true; let reads = 0;
  await f.service.reconcilePresence('alice', async () => ++reads === 1);
  assert.equal(reads, 2); assert.equal(f.store.docs.get('presence/alice').state, 'offline');
});
test('exact handle misses consume the bounded discovery window', async () => {
  const f = await fixture();
  for (let i = 0; i < 30; i++) await rejects(f.call('socialLookupHandle', 'charlie', { handle: 'absent' }), 'not-found');
  await rejects(f.call('socialLookupHandle', 'charlie', { handle: 'alice' }), 'resource-exhausted');
  f.advance(60_001); assert.equal((await f.call('socialLookupHandle', 'charlie', { handle: 'alice' })).profile.uid, 'alice');
});
test('room creation is bounded and idempotent retries do not consume extra quota', async () => {
  const f = await fixture(); await befriend(f);
  const first = roomInput('create', { otherUid: 'bob', sessionId: op(), content: content() });
  await f.call('socialRoomAction', 'alice', first); await f.call('socialRoomAction', 'alice', first);
  for (let i = 1; i < 20; i++) await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: content(i) }));
  await rejects(f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: content(20) })), 'resource-exhausted');
  assert.equal(f.store.docs.get('roomLimits/alice').count, 20);
});
const feedInput = (fields = {}) => ({ operationId: op(), generation: 2, kind: 'chapter_read', content: content(), chapterLabel: 'Chapter 1', ...fields });
test('feed publishing needs consent, current generation, visibility and a non-hidden title', async () => {
  const f = await fixture();
  assert.equal((await f.call('socialGetSelf', 'alice', {})).settings.feed, 'friends'); // new accounts share by default
  await share(f, { feed: 'friends' });
  await rejects(f.call('socialPublishFeed', 'alice', feedInput({ generation: 1 })), 'failed-precondition');
  await rejects(f.call('socialPublishFeed', 'alice', feedInput({ kind: 'library_dump' })), 'invalid-argument');
  await rejects(f.call('socialPublishFeed', 'alice', feedInput({ kind: 'favorite_added' })), 'invalid-argument');
  await rejects(f.call('socialPublishFeed', 'alice', feedInput({ chapterLabel: null })), 'invalid-argument');
  await rejects(f.call('socialPublishFeed', 'alice', feedInput({ content: { ...content(), url: 'https://x' } })), 'invalid-argument');
  const published = await f.call('socialPublishFeed', 'alice', feedInput());
  assert.equal(published.published, true); assert.equal(published.expiresAt, f.now() + FEED_TTL);
  const item = f.store.docs.get('feeds/alice/items/00');
  assert.equal(item.kind, 'chapter_read'); assert.equal(item.generation, 2); assert.equal(item.content.title, 'Title 1');
  assert.equal((await f.call('socialPublishFeed', 'alice', feedInput({ kind: 'favorite_added', content: content(2), chapterLabel: null }))).published, true);
  await share(f, { feed: 'friends', invisible: true }, 'alice', 2);
  await rejects(f.call('socialPublishFeed', 'alice', feedInput({ generation: 3 })), 'failed-precondition');
  await share(f, { feed: 'friends' }, 'alice', 3, content(3).contentKey);
  await rejects(f.call('socialPublishFeed', 'alice', feedInput({ generation: 4, content: content(3) })), 'failed-precondition');
  await share(f, { feed: 'private' }, 'alice', 4);
  await rejects(f.call('socialPublishFeed', 'alice', feedInput({ generation: 5 })), 'failed-precondition');
});
test('feed dedupes repeats for ten minutes and keeps a bounded ring of items', async () => {
  const f = await fixture(); await share(f, { feed: 'friends' });
  assert.equal((await f.call('socialPublishFeed', 'alice', feedInput())).published, true);
  f.advance(60_000);
  assert.equal((await f.call('socialPublishFeed', 'alice', feedInput())).published, false);
  assert.equal((await f.call('socialPublishFeed', 'alice', feedInput({ chapterLabel: 'Chapter 2' }))).published, true);
  f.advance(10 * 60_000);
  assert.equal((await f.call('socialPublishFeed', 'alice', feedInput({ chapterLabel: 'Chapter 2' }))).published, true);
  for (let i = 0; i < FEED_SLOTS + 5; i++) await f.call('socialPublishFeed', 'alice', feedInput({ content: content(100 + i) }));
  const items = [...f.store.docs.keys()].filter((path) => path.startsWith('feeds/alice/items/'));
  assert.equal(items.length, FEED_SLOTS);
  // 3 earlier items + 55 = 58 writes: slots 00..07 hold the newest overwrites.
  assert.equal(f.store.docs.get('feeds/alice/items/07').content.title, `Title ${100 + FEED_SLOTS + 4}`);
  assert.equal(f.store.docs.get('feedState/alice').next, 3 + FEED_SLOTS + 5);
});
async function activeRoom(f) {
  const created = await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: content() }));
  await f.call('socialRoomAction', 'bob', roomInput('join', { roomId: created.roomId }));
  return created.roomId;
}
const message = (roomId, fields = {}) => ({ operationId: op(), roomId, kind: 'text', text: 'hi', emoji: null, ...fields });
const slots = (f, roomId) => [...f.store.docs.keys()].filter((path) => path.startsWith(`rooms/${roomId}/messages/`));
test('room chat requires an active room between friends and validates text and reactions', async () => {
  const f = await fixture(); await befriend(f);
  const invited = await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: content() }));
  await rejects(f.call('socialRoomMessage', 'alice', message(invited.roomId)), 'failed-precondition');
  const roomId = await activeRoom(f);
  await rejects(f.call('socialRoomMessage', 'charlie', message(roomId)), 'permission-denied');
  for (const bad of [{ text: '' }, { text: '   ' }, { text: 'x'.repeat(201) }, { text: 'two\nlines' }, { text: 'hi', emoji: '👍' },
    { kind: 'reaction', text: null, emoji: '💩' }, { kind: 'reaction', text: 'hi', emoji: '👍' }, { kind: 'sticker' }]) {
    await rejects(f.call('socialRoomMessage', 'alice', message(roomId, bad)), 'invalid-argument');
  }
  // seq 0 is the "bob joined" line the server wrote.
  assert.deepEqual({ ...f.store.docs.get(`rooms/${roomId}/messages/000`), createdAt: 0 },
    { schemaVersion: 1, seq: 0, senderUid: 'bob', kind: 'joined', text: null, emoji: null, createdAt: 0 });
  const sent = await f.call('socialRoomMessage', 'alice', message(roomId, { text: '  page 3 is great  ' }));
  assert.equal(sent.seq, 1);
  assert.deepEqual({ ...f.store.docs.get(`rooms/${roomId}/messages/001`), createdAt: 0 },
    { schemaVersion: 1, seq: 1, senderUid: 'alice', kind: 'text', text: 'page 3 is great', emoji: null, createdAt: 0 });
  f.advance(1_000);
  assert.equal((await f.call('socialRoomMessage', 'bob', message(roomId, { kind: 'reaction', text: null, emoji: '🔥' }))).seq, 2);
  await f.call('socialFriendAction', 'alice', { operationId: op(), action: 'remove', otherUid: 'bob' });
  f.advance(1_000);
  await rejects(f.call('socialRoomMessage', 'bob', message(roomId)), 'permission-denied');
});
test('room chat is rate limited, bounded to a ring and cleared when the room closes', async () => {
  const f = await fixture(); await befriend(f);
  const roomId = await activeRoom(f);
  await f.call('socialRoomMessage', 'alice', message(roomId));
  await rejects(f.call('socialRoomMessage', 'alice', message(roomId)), 'resource-exhausted');
  await f.call('socialRoomMessage', 'bob', message(roomId)); // limits are per sender
  for (let i = 1; i < 30; i++) { f.advance(1_000); await f.call('socialRoomMessage', 'alice', message(roomId)); }
  f.advance(1_000);
  await rejects(f.call('socialRoomMessage', 'alice', message(roomId)), 'resource-exhausted');
  // Keep the host lease alive while filling the ring past its size.
  const lease = f.store.docs.get(`roomLeases/${roomId}`);
  for (let i = 0; i < ROOM_MESSAGE_SLOTS; i++) {
    f.advance(2_100);
    if (i % 20 === 0) await f.call('socialRoomAction', 'alice', roomInput('heartbeat', { roomId, sessionId: lease.sessionId }));
    await f.call('socialRoomMessage', 'bob', message(roomId));
  }
  assert.equal(slots(f, roomId).length, ROOM_MESSAGE_SLOTS);
  assert.equal(f.store.docs.get(`roomChat/${roomId}`).next, 32 + ROOM_MESSAGE_SLOTS); // + the "joined" line
  await f.call('socialRoomAction', 'bob', roomInput('leave', { roomId }));
  assert.equal(slots(f, roomId).length, 0);
  assert.equal(f.store.docs.has(`roomChat/${roomId}`), false);
  f.advance(2_000);
  await rejects(f.call('socialRoomMessage', 'alice', message(roomId)), 'failed-precondition');
});
test('unhiding clears only the caller marker, keeps the generation and allows deliberate republishing', async () => {
  const f = await fixture(); await befriend(f);
  await share(f, { library: 'friends' }, 'alice', 1, content(1).contentKey);
  const publish = (generation) => f.call('socialPublishEntries', 'alice', { operationId: op(), generation, kind: 'library', entries: [content(1)], deleteKeys: [] });
  await rejects(publish(2), 'failed-precondition');
  await rejects(f.call('socialUnhideTitle', 'alice', { operationId: op(), contentKey: 'nope' }), 'invalid-argument');
  // Bob has no marker of his own; his call must not touch Alice's.
  assert.equal((await f.call('socialUnhideTitle', 'bob', { operationId: op(), contentKey: content(1).contentKey })).restored, false);
  assert.ok(f.store.docs.has(`hiddenTitles/alice/entries/${content(1).contentKey}`));
  assert.equal((await f.call('socialUnhideTitle', 'alice', { operationId: op(), contentKey: content(1).contentKey })).restored, true);
  assert.equal(f.store.docs.has(`hiddenTitles/alice/entries/${content(1).contentKey}`), false);
  assert.equal(f.store.docs.get('sharingSettings/alice').generation, 2);
  assert.equal(f.store.docs.has(`sharedLibraries/alice/entries/${content(1).contentKey}`), false);
  assert.equal((await publish(2)).published, 1);
  assert.equal((await f.call('socialUnhideTitle', 'alice', { operationId: op(), contentKey: content(1).contentKey })).restored, false);
});
test('daily quotas cap each callable per actor, ignore idempotent replays and reset the next day', async () => {
  const f = await fixture(); await share(f, { feed: 'friends' });
  const limit = DAILY_LIMITS.socialPublishFeed;
  let replay;
  for (let i = 0; i < limit; i++) {
    const input = feedInput({ content: content(1000 + i) });
    await f.call('socialPublishFeed', 'alice', input); replay = input;
  }
  await f.call('socialPublishFeed', 'alice', replay); // a replay returns the stored result without counting
  await rejects(f.call('socialPublishFeed', 'alice', feedInput({ content: content(9999) })), 'resource-exhausted');
  // Other callables and other actors keep their own budget.
  await f.call('socialUpdateProfile', 'alice', { operationId: op(), handle: 'alice', displayName: 'Alice' });
  await share(f, { feed: 'friends' }, 'bob');
  await f.call('socialPublishFeed', 'bob', feedInput({ content: content(1) }));
  f.advance(86_400_000);
  await f.call('socialPublishFeed', 'alice', feedInput({ content: content(9999) }));
});
test('expired rooms are closed by the cleanup path; live and already closed ones are left alone', async () => {
  const f = await fixture(); await befriend(f);
  const stale = await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: content(2) }));
  await f.call('socialRoomAction', 'bob', roomInput('join', { roomId: stale.roomId }));
  f.advance(1_000);
  await f.call('socialRoomMessage', 'bob', message(stale.roomId));
  assert.equal((await f.service.expireRoom(stale.roomId)).expired, false);
  f.advance(90_001);
  assert.equal((await f.service.expireRoom(stale.roomId)).expired, true);
  const closed = f.store.docs.get(`rooms/${stale.roomId}`);
  assert.equal(closed.state, 'closed'); assert.equal(closed.epoch, 2);
  assert.equal(f.store.docs.has(`roomLeases/${stale.roomId}`), false);
  assert.equal(f.store.docs.get(`roomInvites/bob/items/${stale.roomId}`).state, 'closed');
  assert.equal(slots(f, stale.roomId).length, 0);
  assert.equal((await f.service.expireRoom(stale.roomId)).expired, false);
  await rejects(f.service.expireRoom('not-a-key'), 'invalid-argument');
});
const chapter = (fields = {}) => ({ operationId: op(), generation: 2, pages: 20, ...fields });
test('daily stats count chapters and pages per UTC day under consent, with a minimum gap', async () => {
  const f = await fixture();
  // Neither friends' stats nor the global ranking: no stats, only the reader's own XP.
  await share(f, { globalRanking: false });
  const private_ = await f.call('socialRecordChapter', 'alice', chapter({ generation: 2 }));
  assert.equal(private_.counted, false); assert.equal(private_.xp.gained, XP.chapter);
  assert.equal(f.store.docs.get(`dailyStats/alice/days/${statsDay(f.now())}`), undefined);
  await share(f, { stats: 'friends' }, 'alice', 2);
  f.advance(10_000);
  assert.equal((await f.call('socialRecordChapter', 'alice', chapter({ generation: 3 }))).chapters, 1);
  assert.equal(f.store.docs.get(`leaderboards/${statsDay(f.now())}`), undefined); // opted out stays off the board
  f.advance(86_400_000);
  await share(f, { stats: 'friends', globalRanking: true }, 'alice', 3);
  await rejects(f.call('socialRecordChapter', 'alice', chapter({ generation: 4, pages: 0 })), 'invalid-argument');
  const { xp: _xp, ...counts } = await f.call('socialRecordChapter', 'alice', chapter({ generation: 4 }));
  assert.deepEqual(counts, { day: statsDay(f.now()), chapters: 1, pages: 20, streak: 1 });
  await rejects(f.call('socialRecordChapter', 'alice', chapter({ generation: 4 })), 'resource-exhausted');
  f.advance(10_000);
  assert.equal((await f.call('socialRecordChapter', 'alice', chapter({ generation: 4, pages: 5 }))).pages, 25);
  // A privacy change restarts the public counter for the day.
  await share(f, { stats: 'friends' }, 'alice', 4);
  f.advance(10_000);
  assert.equal((await f.call('socialRecordChapter', 'alice', chapter({ generation: 5 }))).chapters, 1);
  f.advance(86_400_000);
  const next = await f.call('socialRecordChapter', 'alice', chapter({ generation: 5 }));
  assert.equal(next.chapters, 1); assert.notEqual(next.day, statsDay(f.now() - 86_400_000));
  await share(f, { stats: 'friends', invisible: true }, 'alice', 5);
  f.advance(10_000);
  await rejects(f.call('socialRecordChapter', 'alice', chapter({ generation: 6 })), 'failed-precondition');
  assert.equal(statsDay(Date.UTC(2026, 9, 2, 23, 59)), '20261002');
});
const style = (fields = {}) => ({ operationId: op(), bio: 'Reads too much', accent: 3, avatar: 'keep', banner: 'keep', ...fields });
test('profile style validates bio, accent and uploaded media, and survives profile edits', async () => {
  const f = await fixture(); await befriend(f);
  for (const bad of [{ bio: 'x'.repeat(161) }, { bio: 'two\nlines' }, { accent: 8 }, { accent: -1 }, { avatar: 'upload' }]) {
    await rejects(f.call('socialUpdateProfileStyle', 'alice', style(bad)), 'invalid-argument');
  }
  await rejects(f.call('socialUpdateProfileStyle', 'alice', style({ avatar: 'set' })), 'failed-precondition');
  f.media.set('profileMedia/alice/avatar', { size: 3 * 1024 * 1024, contentType: 'image/gif' });
  await rejects(f.call('socialUpdateProfileStyle', 'alice', style({ avatar: 'set' })), 'invalid-argument');
  f.media.set('profileMedia/alice/avatar', { size: 1000, contentType: 'image/svg+xml' });
  await rejects(f.call('socialUpdateProfileStyle', 'alice', style({ avatar: 'set' })), 'invalid-argument');
  f.media.set('profileMedia/alice/avatar', { size: 1_500_000, contentType: 'image/gif' });
  f.media.set('profileMedia/alice/banner', { size: 4_000_000, contentType: 'image/gif' });
  const set = await f.call('socialUpdateProfileStyle', 'alice', style({ avatar: 'set', banner: 'set' }));
  assert.equal(set.profile.avatar.contentType, 'image/gif'); assert.equal(set.profile.avatar.version, f.now());
  assert.equal(set.profile.bio, 'Reads too much'); assert.equal(set.profile.accent, 3);
  f.advance(1_000);
  const kept = await f.call('socialUpdateProfileStyle', 'alice', style({ bio: '  new bio ', banner: 'remove' }));
  assert.equal(kept.profile.avatar.version, set.profile.avatar.version); assert.equal(kept.profile.banner, null); assert.equal(kept.profile.bio, 'new bio');
  // Renaming keeps the style; exact-handle lookup only reveals public fields.
  await f.call('socialUpdateProfile', 'alice', { operationId: op(), handle: 'alice2', displayName: 'Alice' });
  assert.equal(f.store.docs.get('profiles/alice').avatar.contentType, 'image/gif');
  const found = await f.call('socialLookupHandle', 'charlie', { handle: 'alice2' });
  assert.deepEqual(Object.keys(found.profile).sort(), ['displayName', 'handle', 'schemaVersion', 'uid']);
  assert.equal((await f.call('socialGetFriend', 'bob', { otherUid: 'alice' })).profile.bio, 'new bio');
  await rejects(f.call('socialUpdateProfileStyle', 'nobody', style()), 'failed-precondition');
});
test('reading streaks count consecutive UTC days and reset after a gap or a privacy change', async () => {
  const f = await fixture(); await share(f, { stats: 'friends' });
  const day = 86_400_000;
  assert.equal((await f.call('socialRecordChapter', 'alice', chapter())).streak, 1);
  f.advance(20_000);
  assert.equal((await f.call('socialRecordChapter', 'alice', chapter())).streak, 1); // same day keeps it
  f.advance(day);
  assert.equal((await f.call('socialRecordChapter', 'alice', chapter())).streak, 2);
  f.advance(day);
  assert.equal((await f.call('socialRecordChapter', 'alice', chapter())).streak, 3);
  f.advance(2 * day); // missed a day
  assert.equal((await f.call('socialRecordChapter', 'alice', chapter())).streak, 1);
  f.advance(day);
  await share(f, { stats: 'friends' }, 'alice', 2);
  assert.equal((await f.call('socialRecordChapter', 'alice', chapter({ generation: 3 }))).streak, 1);
});
test('global ranking is on by default, public-fields only, and left at once on opt-out or invisible', async () => {
  const f = await fixture();
  const board = () => f.store.docs.get(`leaderboards/${statsDay(f.now())}`)?.entries ?? {};
  // Fresh accounts count toward the global ranking without sharing stats with friends.
  assert.equal((await f.call('socialGetSelf', 'alice', {})).settings.globalRanking, true);
  await f.call('socialRecordChapter', 'alice', chapter({ generation: 1 }));
  assert.deepEqual(Object.keys(board().alice).sort(), ['accent', 'avatar', 'banner', 'chapters', 'displayName', 'handle', 'level', 'streak', 'updatedAt']);
  assert.equal(board().alice.chapters, 1);
  f.advance(10_000);
  await f.call('socialRecordChapter', 'bob', chapter({ generation: 1 }));
  f.advance(10_000);
  await f.call('socialRecordChapter', 'bob', chapter({ generation: 1 }));
  assert.equal(board().bob.chapters, 2);
  // Renaming updates the board; older clients that omit the switch keep the current choice.
  await f.call('socialUpdateProfile', 'bob', { operationId: op(), handle: 'bobby', displayName: 'Bobby' });
  assert.equal(board().bob.displayName, 'Bobby'); assert.equal(board().bob.handle, 'bobby');
  await share(f, { stats: 'friends' }, 'bob');
  assert.equal(f.store.docs.get('sharingSettings/bob').globalRanking, true);
  assert.ok(board().bob);
  // Opting out or going invisible removes the entry immediately and stops counting toward it.
  await share(f, { globalRanking: false, stats: 'friends' }, 'bob', 2);
  assert.equal(board().bob, undefined);
  f.advance(10_000);
  await f.call('socialRecordChapter', 'bob', chapter({ generation: 3 }));
  assert.equal(board().bob, undefined);
  await share(f, { invisible: true }, 'alice');
  assert.equal(board().alice, undefined);
  await rejects(f.call('socialUpdateSharing', 'alice', { operationId: op(), expectedGeneration: 2, settings: { ...settings(), globalRanking: 'yes' }, hideContentKey: null }), 'invalid-argument');
});
test('the global board keeps only the top readers', async () => {
  const f = await fixture();
  const readers = Array.from({ length: LEADERBOARD_SIZE + 2 }, (_, i) => `reader${i}`);
  for (const reader of readers) await f.call('socialUpdateProfile', reader, { operationId: op(), handle: reader, displayName: reader });
  for (const [i, reader] of readers.entries()) {
    // reader0 reads most; the last arrivals tie with everyone else at one chapter.
    for (let n = 0; n < (i === 0 ? 3 : 1); n++) { f.advance(10_000); await f.call('socialRecordChapter', reader, chapter({ generation: 1 })); }
  }
  const entries = f.store.docs.get(`leaderboards/${statsDay(f.now())}`).entries;
  assert.equal(Object.keys(entries).length, LEADERBOARD_SIZE);
  assert.equal(entries.reader0.chapters, 3);
  assert.equal(entries[readers.at(-1)], undefined); // the latest arrival among equal counts drops out
});
test('the all-time board ranks by total XP, survives the day, renames and empties on opt-out', async () => {
  const f = await fixture();
  const allTime = () => f.store.docs.get('leaderboards/alltime')?.entries ?? {};
  await f.call('socialRecordChapter', 'alice', chapter({ generation: 1 }));
  f.advance(10_000);
  await f.call('socialRecordChapter', 'bob', chapter({ generation: 1 }));
  f.advance(10_000);
  await f.call('socialRecordChapter', 'bob', chapter({ generation: 1 }));
  assert.deepEqual(Object.keys(allTime().bob).sort(), ['accent', 'avatar', 'banner', 'chapters', 'displayName', 'handle', 'level', 'updatedAt', 'xp']);
  assert.equal(allTime().bob.xp, 20); assert.equal(allTime().bob.chapters, 2); assert.equal(allTime().alice.xp, 10);
  assert.equal(f.store.docs.get('leaderboards/alltime').expiresAt, undefined);
  // A new day starts a fresh daily board; the all-time one keeps counting.
  f.advance(86_400_000);
  await f.call('socialRecordChapter', 'alice', chapter({ generation: 1 }));
  assert.equal(allTime().alice.chapters, 2); assert.ok(allTime().alice.xp > 20); assert.ok(allTime().bob);
  await f.call('socialUpdateProfile', 'bob', { operationId: op(), handle: 'bobby', displayName: 'Bobby' });
  assert.equal(allTime().bob.displayName, 'Bobby');
  await share(f, { globalRanking: false }, 'bob');
  assert.equal(allTime().bob, undefined);
});
test('ranking entries carry pictures only while rankingMedia is on; the switch updates the boards at once', async () => {
  const f = await fixture();
  const day = () => f.store.docs.get(`leaderboards/${statsDay(f.now())}`)?.entries ?? {};
  const allTime = () => f.store.docs.get('leaderboards/alltime')?.entries ?? {};
  await f.call('socialRecordChapter', 'alice', chapter({ generation: 1 }));
  assert.equal(day().alice.avatar, null);
  f.media.set('profileMedia/alice/avatar', { size: 10, contentType: 'image/png' });
  const styled = await f.call('socialUpdateProfileStyle', 'alice', { operationId: op(), bio: '', accent: 1, avatar: 'set', banner: 'keep' });
  assert.deepEqual(day().alice.avatar, { version: styled.profile.avatar.version, contentType: 'image/png' });
  assert.deepEqual(allTime().alice.avatar, day().alice.avatar);
  assert.equal(day().alice.banner, null);
  // Turning the switch off hides the pictures on both boards; older clients that omit it keep the choice.
  await share(f, { rankingMedia: false });
  assert.equal(f.store.docs.get('sharingSettings/alice').rankingMedia, false);
  assert.equal(day().alice.avatar, null); assert.equal(allTime().alice.avatar, null);
  await share(f, { stats: 'friends' }, 'alice', 2);
  assert.equal(f.store.docs.get('sharingSettings/alice').rankingMedia, false);
  f.advance(10_000);
  await f.call('socialRecordChapter', 'alice', chapter({ generation: 3 }));
  assert.equal(day().alice.avatar, null);
  await share(f, { rankingMedia: true, stats: 'friends' }, 'alice', 3);
  assert.equal(day().alice.avatar.contentType, 'image/png');
  await rejects(f.call('socialUpdateSharing', 'alice', { operationId: op(), expectedGeneration: 4, settings: { ...settings(), rankingMedia: 'yes' }, hideContentKey: null }), 'invalid-argument');
});
test('XP: chapters, reading together and streak bonus, daily cap, levels, private unless stats are shared', async () => {
  const f = await fixture(); await befriend(f);
  const day = 86_400_000;
  assert.deepEqual([1, 2, 3, 4, 5].map(xpForLevel), [0, 50, 150, 300, 500]);
  assert.deepEqual([0, 49, 50, 149, 150, 10_000].map(levelFor), [1, 1, 2, 2, 3, 20]);
  const first = await f.call('socialRecordChapter', 'alice', chapter({ generation: 1 }));
  assert.deepEqual(first.xp, { gained: 10, total: 10, level: 1, leveledUp: false, unlocked: ['first_chapter'], freezeUsed: 0, challenges: [], challengeXp: 0, season: null });
  // Reading in a room with someone who joined adds the together bonus; a room id alone doesn't.
  const created = await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: content(), position: position() }));
  f.advance(10_000);
  assert.equal((await f.call('socialRecordChapter', 'alice', chapter({ generation: 1, roomId: created.roomId }))).xp.gained, XP.chapter);
  await f.call('socialRoomAction', 'bob', roomInput('join', { roomId: created.roomId }));
  f.advance(10_000);
  assert.equal((await f.call('socialRecordChapter', 'alice', chapter({ generation: 1, roomId: created.roomId }))).xp.gained, XP.chapter + XP.together);
  await rejects(f.call('socialRecordChapter', 'alice', chapter({ generation: 1, roomId: 'nope' })), 'invalid-argument');
  // Next day's first chapter carries the streak bonus (2 days).
  f.advance(day);
  const second = await f.call('socialRecordChapter', 'alice', chapter({ generation: 1 }));
  assert.equal(second.xp.gained, XP.chapter + 2 * XP.streakDay);
  assert.equal(second.xp.total, 10 + 10 + 15 + 20); assert.equal(second.xp.level, 2); assert.equal(second.xp.leveledUp, true);
  const doc = f.store.docs.get('xp/alice');
  assert.equal(doc.chapters, 4); assert.equal(doc.together, 1); assert.equal(doc.bestStreak, 2);
  // The fixture starts on a Sunday, so the next day starts a new week: the weekly counters restart.
  assert.equal(doc.weekChapters, 1); assert.equal(doc.weekXp, second.xp.gained);
  // The ranking shows the level.
  assert.equal(f.store.docs.get(`leaderboards/${statsDay(f.now())}`).entries.alice.level, 2);
  // Daily cap.
  for (let i = 0; i < 120; i++) { f.advance(10_000); await f.call('socialRecordChapter', 'alice', chapter({ generation: 1 })); }
  assert.equal(f.store.docs.get('xp/alice').today, XP.dailyCap);
  f.advance(10_000);
  assert.equal((await f.call('socialRecordChapter', 'alice', chapter({ generation: 1 }))).xp.gained, 0);
  // Invisible earns nothing.
  await share(f, { invisible: true });
  f.advance(10_000);
  await rejects(f.call('socialRecordChapter', 'alice', chapter({ generation: 2 })), 'failed-precondition');
});
test('achievements unlock once, weekly XP rolls over on Monday, streak freezes cover missed days', async () => {
  const f = await fixture(); const day = 86_400_000;
  assert.equal(weekOf(Date.UTC(2026, 9, 5, 12)), '20261005'); // Monday
  assert.equal(weekOf(Date.UTC(2026, 9, 11, 23)), '20261005'); // Sunday
  assert.equal(weekOf(Date.UTC(2026, 9, 12, 0)), '20261012');
  const read = async () => (await f.call('socialRecordChapter', 'alice', chapter({ generation: 1 }))).xp;
  assert.deepEqual((await read()).unlocked, ['first_chapter']);
  f.advance(10_000);
  assert.deepEqual((await read()).unlocked, []);
  const firstAt = f.store.docs.get('xp/alice').badges.first_chapter;
  // Seven days in a row: streak badges, and a freeze earned on day 7.
  for (let d = 1; d < 7; d++) { f.advance(day); await read(); }
  let doc = f.store.docs.get('xp/alice');
  assert.equal(doc.streak, 7); assert.equal(doc.freezes, 1); assert.equal(doc.days, 7);
  assert.ok(doc.badges.streak_3 && doc.badges.streak_7); assert.equal(doc.badges.first_chapter, firstAt);
  // One missed day is covered by the freeze; the next gap of two isn't.
  f.advance(2 * day);
  const saved = await read();
  assert.equal(saved.freezeUsed, 1);
  doc = f.store.docs.get('xp/alice'); assert.equal(doc.streak, 8); assert.equal(doc.freezes, 0);
  f.advance(3 * day);
  await read();
  assert.equal(f.store.docs.get('xp/alice').streak, 1);
  // Weekly XP: a new week starts from this chapter, last week's total is kept for the "last week" view.
  const before = f.store.docs.get('xp/alice');
  f.advance(7 * day);
  const gained = (await read()).gained;
  doc = f.store.docs.get('xp/alice');
  assert.equal(doc.weekXp, gained); assert.notEqual(doc.week, before.week);
  // Binge: 20 chapters in a day.
  for (let i = 0; i < 20; i++) { f.advance(10_000); await read(); }
  assert.ok(f.store.docs.get('xp/alice').badges.binge_20);
  assert.ok(f.store.docs.get('xp/alice').bestDay >= 20);
});
test('XP rules table: the app and web mirror exactly these values (see XpRulesTest.kt and web logic.test.mjs)', () => {
  // If this fails, update the mirrors in the app's XpRules (SocialChallenges.kt) and web logic.ts together with this list.
  assert.deepEqual({ ...XP }, { chapter: 10, together: 5, streakDay: 5, streakMaxDays: 7, dailyCap: 1000 });
  assert.equal(MAX_FREEZES, 2); assert.equal(CHALLENGE_XP, 100);
  assert.deepEqual([1, 2, 3, 4, 5, 6, 10].map(xpForLevel), [0, 50, 150, 300, 500, 750, 2250]);
  assert.deepEqual(CHALLENGES.map((c) => [c.id, c.field, c.min]), [
    ['chapters_100', 'chapters', 100], ['chapters_200', 'chapters', 200], ['days_15', 'days', 15], ['days_20', 'days', 20],
    ['together_5', 'together', 5], ['together_10', 'together', 10], ['streak_7', 'streak', 7], ['streak_14', 'streak', 14],
    ['binge_15', 'bestDay', 15], ['xp_2000', 'xp', 2000]]);
  assert.deepEqual(CHALLENGE_ROTATION.map((r) => [...r]), [
    ['chapters_100', 'days_15', 'streak_7'], ['chapters_200', 'together_5', 'binge_15'], ['days_20', 'together_10', 'xp_2000'],
    ['chapters_100', 'streak_14', 'together_5'], ['days_15', 'binge_15', 'xp_2000'], ['chapters_200', 'days_20', 'together_10'],
    ['chapters_100', 'together_5', 'streak_14'], ['days_20', 'streak_7', 'xp_2000'], ['chapters_200', 'binge_15', 'together_10'],
    ['chapters_100', 'days_20', 'together_10'], ['days_15', 'streak_14', 'xp_2000'], ['chapters_200', 'together_5', 'streak_7']]);
  // Every rotation entry exists and uses three different counters.
  for (const ids of CHALLENGE_ROTATION) {
    const fields = ids.map((id) => CHALLENGES.find((c) => c.id === id).field);
    assert.equal(new Set(fields).size, 3);
  }
  assert.equal(monthOf(Date.UTC(2026, 9, 31, 23, 59)), '2026-10'); assert.equal(monthOf(Date.UTC(2026, 10, 1)), '2026-11');
  assert.deepEqual(challengesFor('2026-10').map((c) => c.id), ['chapters_100', 'days_20', 'together_10']);
  assert.deepEqual(challengesFor('2027-01').map((c) => c.id), ['chapters_100', 'days_15', 'streak_7']);
  assert.equal(seasonBadge('2026-10'), 'season_2026_10');
});
test('monthly challenges: counters per UTC month, roll over on the 1st, older docs start fresh', async () => {
  const f = await fixture(); await befriend(f); const day = 86_400_000;
  const read = async (fields = {}) => (await f.call('socialRecordChapter', 'alice', chapter({ generation: 1, ...fields }))).xp;
  // An older xp doc without the new fields (read yesterday): the month starts from this chapter.
  f.advance(Date.UTC(2030, 9, 30, 12) - f.now());
  f.store.docs.set('xp/alice', { schemaVersion: 1, total: 400, level: 4, day: statsDay(f.now() - day), today: 30, chapters: 40, together: 2,
    streak: 3, bestStreak: 3, freezes: 0, days: 10, dayChapters: 3, bestDay: 3, week: weekOf(f.now()), weekXp: 30, badges: { first_chapter: 1 },
    createdAt: 1, updatedAt: 1 });
  await read();
  let doc = f.store.docs.get('xp/alice');
  assert.deepEqual(doc.month, { id: '2030-10', chapters: 1, days: 1, together: 0, streak: 4, bestDay: 1, xp: XP.chapter + 4 * XP.streakDay });
  assert.deepEqual(doc.challenges, {}); assert.equal(doc.chapters, 41);
  // Same day: chapters and bestDay grow, days don't.
  f.advance(10_000); await read();
  doc = f.store.docs.get('xp/alice');
  assert.equal(doc.month.chapters, 2); assert.equal(doc.month.days, 1); assert.equal(doc.month.bestDay, 2); assert.equal(doc.month.xp, 30 + 10);
  // Together: only in a room with someone who joined.
  const created = await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: content(), position: position() }));
  await f.call('socialRoomAction', 'bob', roomInput('join', { roomId: created.roomId }));
  f.advance(10_000); await read({ roomId: created.roomId });
  assert.equal(f.store.docs.get('xp/alice').month.together, 1);
  // Next day: days and the month's streak grow; bestDay keeps the best day.
  f.advance(day); await read();
  doc = f.store.docs.get('xp/alice');
  assert.equal(doc.month.days, 2); assert.equal(doc.month.streak, 5); assert.equal(doc.month.bestDay, 3); assert.equal(doc.month.chapters, 4);
  // 1 November: everything starts over, the streak carries on (the month's streak is the streak reached in it).
  f.advance(day); await read();
  doc = f.store.docs.get('xp/alice');
  assert.equal(monthOf(f.now()), '2030-11');
  // 1 November 2030 is a Friday: double XP.
  assert.deepEqual(doc.month, { id: '2030-11', chapters: 1, days: 1, together: 0, streak: 6, bestDay: 1, xp: (XP.chapter + 6 * XP.streakDay) * xpMultiplier(f.now()) });
  assert.equal(doc.chapters, 45);
});
test('monthly challenges: each completes once for 100 XP, all three add the season badge, replays award nothing', async () => {
  const f = await fixture(); await befriend(f); const day = 86_400_000;
  const read = async (fields = {}) => (await f.call('socialRecordChapter', 'alice', chapter({ generation: 1, ...fields }))).xp;
  f.advance(Date.UTC(2030, 9, 20, 12) - f.now()); // October: chapters_100, days_20, together_10
  const seed = (month, extra = {}) => f.store.docs.set('xp/alice', { schemaVersion: 1, total: 1000, level: 6, day: statsDay(f.now() - day), today: 0,
    chapters: 500, together: 50, streak: 1, bestStreak: 9, freezes: 0, days: 60, dayChapters: 1, bestDay: 9, week: weekOf(f.now()), weekXp: 0,
    badges: {}, month, createdAt: 1, updatedAt: 1, ...extra });
  // Chapters: the 100th chapter of the month completes it.
  seed({ id: '2030-10', chapters: 98, days: 5, together: 0, streak: 1, bestDay: 9, xp: 900 });
  let reward = await read();
  assert.deepEqual(reward.challenges, []);
  f.advance(10_000);
  reward = await read();
  assert.deepEqual(reward.challenges, ['chapters_100']); assert.equal(reward.challengeXp, CHALLENGE_XP);
  assert.equal(reward.gained, XP.chapter + CHALLENGE_XP); assert.equal(reward.season, null);
  let doc = f.store.docs.get('xp/alice');
  assert.deepEqual(doc.challenges, { '2030-10': ['chapters_100'] });
  // Challenge XP is outside the daily cap and the weekly XP.
  assert.equal(doc.today, 2 * XP.chapter + 2 * XP.streakDay); assert.equal(doc.weekXp, doc.today); assert.equal(doc.month.xp, 900 + doc.today);
  // Once only.
  f.advance(10_000);
  reward = await read();
  assert.deepEqual(reward.challenges, []); assert.equal(reward.challengeXp, 0);
  // Replaying an operation returns the stored result and changes nothing.
  const replay = chapter({ generation: 1 });
  f.advance(10_000);
  const firstTry = await f.call('socialRecordChapter', 'alice', replay);
  const totalAfter = f.store.docs.get('xp/alice').total;
  f.advance(10_000);
  assert.deepEqual(await f.call('socialRecordChapter', 'alice', replay), firstTry);
  assert.equal(f.store.docs.get('xp/alice').total, totalAfter);
  // Days: the 20th reading day.
  seed({ id: '2030-10', chapters: 0, days: 19, together: 0, streak: 1, bestDay: 1, xp: 0 }, { challenges: { '2030-10': ['chapters_100'] } });
  f.advance(10_000);
  assert.deepEqual((await read()).challenges, ['days_20']);
  // Together: the 10th chapter read together; with the other two done that completes the month.
  const created = await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: content(), position: position() }));
  await f.call('socialRoomAction', 'bob', roomInput('join', { roomId: created.roomId }));
  const before = f.store.docs.get('xp/alice');
  f.store.docs.set('xp/alice', { ...before, month: { ...before.month, together: 9 } });
  f.advance(10_000);
  reward = await read({ roomId: created.roomId });
  assert.deepEqual(reward.challenges, ['together_10']); assert.equal(reward.season, 'season_2030_10');
  doc = f.store.docs.get('xp/alice');
  assert.deepEqual(doc.challenges['2030-10'], ['chapters_100', 'days_20', 'together_10']);
  assert.ok(doc.badges.season_2030_10);
  f.advance(10_000);
  reward = await read({ roomId: created.roomId });
  assert.deepEqual(reward.challenges, []); assert.equal(reward.season, null);
  // All three in one chapter: 300 XP and the season badge at once; level-ups count the challenge XP.
  seed({ id: '2030-10', chapters: 99, days: 19, together: 9, streak: 1, bestDay: 1, xp: 0 }, { total: 700 });
  f.advance(10_000);
  reward = await read({ roomId: created.roomId });
  assert.deepEqual(reward.challenges, ['chapters_100', 'days_20', 'together_10']); assert.equal(reward.challengeXp, 3 * CHALLENGE_XP);
  assert.equal(reward.season, 'season_2030_10'); assert.equal(reward.total, 700 + XP.chapter + XP.together + 2 * XP.streakDay + 300);
  assert.equal(reward.level, levelFor(reward.total)); assert.equal(reward.leveledUp, true);
});
test('monthly challenges: streak, binge and monthly XP challenges count within the month', async () => {
  const f = await fixture(); const day = 86_400_000;
  const read = async () => (await f.call('socialRecordChapter', 'alice', chapter({ generation: 1 }))).xp;
  const seed = (month, extra = {}) => f.store.docs.set('xp/alice', { schemaVersion: 1, total: 5000, level: 14, day: statsDay(f.now() - day), today: 0,
    chapters: 500, together: 0, streak: 6, bestStreak: 30, freezes: 0, days: 60, dayChapters: 1, bestDay: 30, week: weekOf(f.now()), weekXp: 0,
    badges: {}, month, createdAt: 1, updatedAt: 1, ...extra });
  // August: days_20, streak_7, xp_2000. Streak: reaching a 7-day streak this month (the lifetime best doesn't count).
  f.advance(Date.UTC(2030, 7, 10, 12) - f.now());
  seed({ id: '2030-08', chapters: 9, days: 9, together: 0, streak: 6, bestDay: 3, xp: 1980 });
  assert.deepEqual((await read()).challenges, ['streak_7', 'xp_2000']); // streak 7, and 1980 + 10 + 35 XP
  // May: days_15, binge_15, xp_2000. Binge: 15 chapters in one UTC day; April's counters don't carry over.
  f.advance(Date.UTC(2031, 4, 10, 12) - f.now());
  seed({ id: '2031-04', chapters: 300, days: 30, together: 0, streak: 1, bestDay: 40, xp: 5000 }, { streak: 1, day: statsDay(f.now() - 5 * day) });
  const done = [];
  for (let i = 0; i < 15; i++) { f.advance(10_000); done.push(...(await read()).challenges); }
  assert.deepEqual(done, ['binge_15']);
  assert.equal(f.store.docs.get('xp/alice').month.bestDay, 15);
});
test('room ready checks: ready and wait carry no text, keep a fallback for older apps, and are rate limited', async () => {
  const f = await fixture(); await befriend(f);
  const created = await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: content(), position: position() }));
  await f.call('socialRoomAction', 'bob', roomInput('join', { roomId: created.roomId }));
  const signal = (kind, fields = {}) => ({ operationId: op(), roomId: created.roomId, kind, text: null, emoji: null, ...fields });
  await rejects(f.call('socialRoomMessage', 'bob', signal('wait', { text: 'hi' })), 'invalid-argument');
  await rejects(f.call('socialRoomMessage', 'bob', signal('ping')), 'invalid-argument');
  const sent = await f.call('socialRoomMessage', 'bob', signal('wait'));
  const line = [...f.store.docs].find(([path, m]) => path.startsWith(`rooms/${created.roomId}/messages/`) && m.seq === sent.seq)[1];
  assert.equal(line.kind, 'wait'); assert.equal(line.text, '⏸ Wait for me'); assert.equal(line.senderUid, 'bob');
  await rejects(f.call('socialRoomMessage', 'bob', signal('ready')), 'resource-exhausted'); // 1 s gap
  f.advance(1_000);
  await f.call('socialRoomMessage', 'bob', signal('ready'));
  await rejects(f.call('socialRoomMessage', 'charlie', signal('ready')), 'permission-denied');
});
test('messages can recommend a title: validated content, optional text, a preview with the title', async () => {
  const f = await fixture(); await befriend(f);
  const send = (fields) => f.call('socialMessage', 'alice', { operationId: op(), action: 'send', otherUid: 'bob', text: null, ...fields });
  await rejects(send({}), 'invalid-argument'); // neither text nor title
  await rejects(send({ content: { ...content(), title: '' } }), 'invalid-argument');
  await rejects(send({ content: { ...content(), coverUrl: 'http://insecure/x.jpg' } }), 'invalid-argument');
  const sent = await send({ content: { ...content(), coverUrl: 'https://cdn.example/c.jpg' } });
  const message = f.store.docs.get(`chats/${sent.chatId}/messages/${String(sent.seq).padStart(10, '0')}`);
  assert.equal(message.text, ''); assert.equal(message.content.title, 'Title 1');
  assert.equal(f.store.docs.get('chatIndex/bob/chats/alice').lastText, '📖 Title 1');
  f.advance(2_000);
  await send({ text: 'read this!', content: content(2) });
  assert.equal(f.store.docs.get('chatIndex/bob/chats/alice').lastText, '📖 Title 2 · read this!');
  assert.equal(f.pushes.at(-1).data.title, '📖 Title 2 · read this!');
  await rejects(f.call('socialMessage', 'alice', { operationId: op(), action: 'read', otherUid: 'bob', text: null, content: content() }), 'invalid-argument');
  // Groups too.
  const { groupId } = await f.call('socialGroupChat', 'alice', gc('create', { name: 'Recs', uids: ['bob'] }));
  await f.call('socialGroupChat', 'alice', gc('send', { groupId, content: content(3) }));
  assert.equal(f.store.docs.get(`groupIndex/bob/groups/${groupId}`).lastText, '📖 Title 3');
  await rejects(f.call('socialGroupChat', 'alice', gc('rename', { groupId, name: 'x', content: content(3) })), 'invalid-argument');
});
test('shared entries may flag an uploaded cover thumbnail (true only)', async () => {
  const f = await fixture(); await share(f, { library: 'friends' });
  const publish = (entry) => f.call('socialPublishEntries', 'alice', { operationId: op(), generation: 2, kind: 'library', entries: [entry], deleteKeys: [] });
  await publish({ ...content(1), thumb: true });
  assert.equal(f.store.docs.get(`sharedLibraries/alice/entries/${content(1).contentKey}`).content.thumb, true);
  await rejects(publish({ ...content(2), thumb: false }), 'invalid-argument');
  await rejects(publish({ ...content(2), thumb: 'yes' }), 'invalid-argument');
});
var gc = (action, fields = {}) => ({ operationId: op(), action, groupId: null, name: null, uids: null, text: null, ...fields });
const groupMessages = (f, groupId) => [...f.store.docs].filter(([path]) => path.startsWith(`groups/${groupId}/messages/`)).map(([, m]) => m);
test('group chats: friends only, events, unread counts, pushes, owner hand-off, last one out deletes', async () => {
  const f = await fixture(); await befriend(f); await befriend(f, 'alice', 'charlie');
  await f.call('socialUpdateProfile', 'dave', { operationId: op(), handle: 'dave', displayName: 'dave' });
  await befriend(f, 'bob', 'dave');
  // Only friends can be added, names are plain text.
  await rejects(f.call('socialGroupChat', 'alice', gc('create', { name: 'Readers', uids: ['dave'] })), 'permission-denied');
  await rejects(f.call('socialGroupChat', 'alice', gc('create', { name: 'two\nlines', uids: ['bob'] })), 'invalid-argument');
  await rejects(f.call('socialGroupChat', 'alice', gc('create', { name: 'Readers', uids: ['bob'], text: 'hi' })), 'invalid-argument');
  const { groupId, group: created } = await f.call('socialGroupChat', 'alice', gc('create', { name: 'Readers', uids: ['bob', 'charlie'] }));
  assert.deepEqual(created.members, ['alice', 'bob', 'charlie']); assert.equal(created.ownerUid, 'alice');
  assert.deepEqual(f.pushes.filter((p) => p.kind === 'group_added').map((p) => p.to).sort(), ['bob', 'charlie']);
  // Messages: unread for everyone else, pushes to everyone else, preview in the lists.
  await f.call('socialGroupChat', 'bob', gc('send', { groupId, text: '  hello all  ' }));
  assert.equal(f.store.docs.get(`groupIndex/alice/groups/${groupId}`).unread, 1);
  assert.equal(f.store.docs.get(`groupIndex/bob/groups/${groupId}`).unread, 0);
  assert.equal(f.store.docs.get(`groupIndex/charlie/groups/${groupId}`).lastText, 'hello all');
  assert.equal(f.store.docs.get(`groupIndex/charlie/groups/${groupId}`).lastSenderName, 'bob');
  assert.deepEqual(f.pushes.filter((p) => p.kind === 'group_message').map((p) => p.to).sort(), ['alice', 'charlie']);
  await rejects(f.call('socialGroupChat', 'bob', gc('send', { groupId, text: 'fast' })), 'resource-exhausted');
  await f.call('socialGroupChat', 'alice', gc('read', { groupId }));
  assert.equal(f.store.docs.get(`groupIndex/alice/groups/${groupId}`).unread, 0);
  // Outsiders can't do anything; members add their own friends, and newcomers start reading from their join.
  await rejects(f.call('socialGroupChat', 'dave', gc('send', { groupId, text: 'hi' })), 'permission-denied');
  await rejects(f.call('socialGroupChat', 'alice', gc('add', { groupId, uids: ['dave'] })), 'permission-denied');
  const before = f.store.docs.get(`groups/${groupId}`).next;
  await f.call('socialGroupChat', 'bob', gc('add', { groupId, uids: ['dave'] }));
  assert.equal(f.store.docs.get(`groups/${groupId}`).names.dave.since, before);
  await rejects(f.call('socialGroupChat', 'bob', gc('add', { groupId, uids: ['dave'] })), 'already-exists');
  // Only the owner removes people.
  await rejects(f.call('socialGroupChat', 'bob', gc('remove', { groupId, uids: ['charlie'] })), 'permission-denied');
  await f.call('socialGroupChat', 'alice', gc('remove', { groupId, uids: ['charlie'] }));
  assert.equal(f.store.docs.get(`groupIndex/charlie/groups/${groupId}`), undefined);
  await f.call('socialGroupChat', 'dave', gc('rename', { groupId, name: 'Night readers' }));
  assert.equal(f.store.docs.get(`groupIndex/alice/groups/${groupId}`).name, 'Night readers');
  // The owner leaves: the longest member takes over, and it's announced.
  await f.call('socialGroupChat', 'alice', gc('leave', { groupId }));
  assert.equal(f.store.docs.get(`groups/${groupId}`).ownerUid, 'bob');
  assert.deepEqual(groupMessages(f, groupId).filter((m) => m.kind === 'event').map((m) => m.event),
    ['created', 'added', 'removed', 'renamed', 'left', 'owner']);
  assert.ok(groupMessages(f, groupId).every((m) => m.expiresAt > f.now()));
  await f.call('socialGroupChat', 'bob', gc('leave', { groupId }));
  assert.deepEqual((await f.call('socialGroupChat', 'dave', gc('leave', { groupId }))).deleted, true);
  assert.equal(f.store.docs.get(`groups/${groupId}`), undefined);
  // Blocks: nobody is put in a group with someone they blocked (or who blocked them), and no pushes between them.
  const { groupId: g2 } = await f.call('socialGroupChat', 'bob', gc('create', { name: 'Two', uids: ['dave'] }));
  await f.call('socialFriendAction', 'charlie', { operationId: op(), action: 'request', otherUid: 'bob' });
  await f.call('socialFriendAction', 'bob', { operationId: op(), action: 'accept', otherUid: 'charlie' });
  await f.call('socialFriendAction', 'dave', { operationId: op(), action: 'block', otherUid: 'charlie' });
  await rejects(f.call('socialGroupChat', 'bob', gc('add', { groupId: g2, uids: ['charlie'] })), 'permission-denied');
  await f.call('socialFriendAction', 'dave', { operationId: op(), action: 'unblock', otherUid: 'charlie' });
  await f.call('socialGroupChat', 'bob', gc('add', { groupId: g2, uids: ['charlie'] }));
  await f.call('socialFriendAction', 'dave', { operationId: op(), action: 'block', otherUid: 'charlie' });
  f.pushes.length = 0; f.advance(2_000);
  await f.call('socialGroupChat', 'charlie', gc('send', { groupId: g2, text: 'hello' }));
  assert.deepEqual(f.pushes.map((p) => p.to), ['bob']);
  assert.ok(f.store.docs.get(`groupIndex/bob/groups/${g2}`).previewExpiresAt > f.now());
  // Size limit.
  await rejects(f.call('socialGroupChat', 'alice', gc('create', { name: 'Big', uids: Array.from({ length: GROUP_MAX_MEMBERS }, (_, i) => `u${i}`) })), 'invalid-argument');
});
const token = (n = 1) => `fcm-token-${n}:${'a'.repeat(40)}`;
test('devices register and unregister push tokens, newest ten kept, owner-only document', async () => {
  const f = await fixture();
  const device = (action, n) => f.call('socialRegisterDevice', 'alice', { operationId: op(), action, token: token(n) });
  assert.deepEqual(await device('register', 1), { devices: 1 });
  assert.deepEqual(await device('register', 1), { devices: 1 }); // same token: refreshed, not duplicated
  for (let n = 2; n <= 12; n++) { f.advance(1_000); await device('register', n); }
  const tokens = Object.values(f.store.docs.get('devices/alice').tokens).map((d) => d.token);
  assert.equal(tokens.length, 10); assert.ok(!tokens.includes(token(1)) && !tokens.includes(token(2)) && tokens.includes(token(12)));
  await device('unregister', 12);
  assert.equal(Object.keys(f.store.docs.get('devices/alice').tokens).length, 9);
  for (const bad of ['short', `bad token ${'a'.repeat(30)}`, 'x'.repeat(4097)]) {
    await rejects(f.call('socialRegisterDevice', 'alice', { operationId: op(), action: 'register', token: bad }), 'invalid-argument');
  }
  await rejects(f.call('socialRegisterDevice', 'alice', { operationId: op(), action: 'wipe', token: token(1) }), 'invalid-argument');
});
test('friend requests, acceptances and room invites push once, after commit, never on replay', async () => {
  const f = await fixture();
  const request = { operationId: op(), action: 'request', otherUid: 'bob' };
  await f.call('socialFriendAction', 'alice', request);
  await f.call('socialFriendAction', 'alice', request); // idempotent replay
  await f.call('socialFriendAction', 'alice', { operationId: op(), action: 'request', otherUid: 'bob' }); // already pending
  assert.deepEqual(f.pushes, [{ to: 'bob', kind: 'friend_request', data: { name: 'alice', handle: 'alice', uid: 'alice' } }]);
  await f.call('socialFriendAction', 'bob', { operationId: op(), action: 'accept', otherUid: 'alice' });
  assert.deepEqual(f.pushes.at(-1), { to: 'alice', kind: 'friend_accepted', data: { name: 'bob', handle: 'bob', uid: 'bob' } });
  const created = await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: randomUUID(), content: content(), position: position() }));
  assert.deepEqual(f.pushes.at(-1), { to: 'bob', kind: 'room_invite', data: { name: 'alice', title: 'Title 1', roomId: created.roomId, uid: 'alice' } });
  // A failed mutation sends nothing.
  const before = f.pushes.length;
  await rejects(f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'charlie', sessionId: randomUUID(), content: content(), position: position() })), 'permission-denied');
  assert.equal(f.pushes.length, before);
});
const dm = (action, otherUid, text = null) => ({ operationId: op(), action, otherUid, text });
test('friends exchange private messages with unread counts, pushes and a 30-day expiry', async () => {
  const f = await fixture();
  await rejects(f.call('socialMessage', 'alice', dm('send', 'bob', 'hi')), 'permission-denied'); // not friends yet
  await befriend(f);
  const sent = await f.call('socialMessage', 'alice', dm('send', 'bob', '  hi Bob  '));
  const chatId = pairId('alice', 'bob');
  assert.equal(sent.chatId, chatId);
  const stored = f.store.docs.get(`chats/${chatId}/messages/0000000000`);
  assert.equal(stored.text, 'hi Bob'); assert.equal(stored.senderUid, 'alice'); assert.equal(stored.expiresAt, f.now() + 30 * 86_400_000);
  assert.deepEqual(f.store.docs.get(`chats/${chatId}`).participants, ['alice', 'bob']);
  assert.equal(f.store.docs.get('chatIndex/bob/chats/alice').unread, 1);
  assert.equal(f.store.docs.get('chatIndex/alice/chats/bob').unread, 0);
  assert.deepEqual(f.pushes.at(-1), { to: 'bob', kind: 'message', data: { name: 'alice', title: 'hi Bob', uid: 'alice' } });
  await rejects(f.call('socialMessage', 'alice', dm('send', 'bob', 'again')), 'resource-exhausted'); // 1 s gap
  f.advance(1_000);
  await f.call('socialMessage', 'alice', dm('send', 'bob', 'x'.repeat(500)));
  assert.equal(f.store.docs.get('chatIndex/bob/chats/alice').unread, 2);
  assert.equal(f.store.docs.get('chatIndex/bob/chats/alice').lastText.length, 100);
  await f.call('socialMessage', 'bob', dm('read', 'alice'));
  assert.equal(f.store.docs.get('chatIndex/bob/chats/alice').unread, 0);
  for (const bad of ['', '   ', 'two\nlines', 'x'.repeat(501)]) await rejects(f.call('socialMessage', 'bob', dm('send', 'alice', bad)), 'invalid-argument');
  await rejects(f.call('socialMessage', 'bob', dm('read', 'alice', 'text')), 'invalid-argument');
  // Unfriending removes the chat from both lists and blocks further messages.
  await f.call('socialFriendAction', 'bob', { operationId: op(), action: 'remove', otherUid: 'alice' });
  assert.equal(f.store.docs.get('chatIndex/bob/chats/alice'), undefined);
  assert.equal(f.store.docs.get('chatIndex/alice/chats/bob'), undefined);
  f.advance(1_000);
  await rejects(f.call('socialMessage', 'alice', dm('send', 'bob', 'still there?')), 'permission-denied');
});
const ROOM_MAX = 6;
async function group(f, names) {
  for (const name of names) if (!f.store.docs.has(`profiles/${name}`)) await f.call('socialUpdateProfile', name, { operationId: op(), handle: name, displayName: name.toUpperCase() });
  for (const name of names) if (name !== 'alice') await befriend(f, 'alice', name);
}
test('group rooms: the host invites friends up to six, members join and see a name snapshot', async () => {
  const f = await fixture();
  const names = ['bob', 'charlie', 'dave', 'erin', 'frank', 'gina'];
  await group(f, names);
  const created = await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: content(), position: position() }));
  const roomId = created.roomId;
  assert.equal(created.room.schemaVersion, 2); assert.equal(created.room.maxMembers, ROOM_MAX);
  assert.deepEqual(created.room.participants, ['alice', 'bob']);
  assert.equal(created.room.members.bob.state, 'invited'); assert.equal(created.room.members.bob.name, 'bob');
  for (const name of ['charlie', 'dave', 'erin', 'frank']) await f.call('socialRoomAction', 'alice', roomInput('invite', { roomId, otherUid: name }));
  assert.equal(f.pushes.filter((p) => p.kind === 'room_invite').length, 5);
  await rejects(f.call('socialRoomAction', 'alice', roomInput('invite', { roomId, otherUid: 'gina' })), 'resource-exhausted'); // full at 6
  await rejects(f.call('socialRoomAction', 'alice', roomInput('invite', { roomId, otherUid: 'bob' })), 'already-exists');
  await rejects(f.call('socialRoomAction', 'bob', roomInput('invite', { roomId, otherUid: 'gina' })), 'permission-denied'); // host only
  for (const name of ['bob', 'charlie', 'dave']) { f.advance(1_000); await f.call('socialRoomAction', name, roomInput('join', { roomId })); }
  const room = f.store.docs.get(`rooms/${roomId}`);
  assert.equal(room.state, 'active'); assert.equal(room.members.charlie.state, 'joined');
  // Members chat; invited-only members can't yet.
  await f.call('socialRoomMessage', 'charlie', message(roomId));
  await rejects(f.call('socialRoomMessage', 'erin', message(roomId)), 'permission-denied');
  // The host removes someone; members can't.
  await rejects(f.call('socialRoomAction', 'bob', roomInput('kick', { roomId, otherUid: 'dave' })), 'permission-denied');
  await f.call('socialRoomAction', 'alice', roomInput('kick', { roomId, otherUid: 'frank' }));
  assert.ok(!f.store.docs.get(`rooms/${roomId}`).participants.includes('frank'));
  assert.equal(f.store.docs.get(`roomInvites/frank/items/${roomId}`).state, 'closed');
  // A member leaving keeps the room going.
  await f.call('socialRoomAction', 'erin', roomInput('leave', { roomId }));
  assert.equal(f.store.docs.get(`rooms/${roomId}`).state, 'active');
  assert.deepEqual(f.store.docs.get(`rooms/${roomId}`).participants, ['alice', 'bob', 'charlie', 'dave']);
});
test('when the host leaves, the longest-present member takes over, claims the lease and the chat says so', async () => {
  const f = await fixture();
  await group(f, ['bob', 'charlie', 'dave']);
  await befriend(f, 'bob', 'charlie'); // dave is not bob's friend
  const created = await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: content(), position: position() }));
  const roomId = created.roomId;
  for (const name of ['charlie', 'dave']) await f.call('socialRoomAction', 'alice', roomInput('invite', { roomId, otherUid: name }));
  for (const name of ['bob', 'charlie', 'dave']) { f.advance(1_000); await f.call('socialRoomAction', name, roomInput('join', { roomId })); }
  const before = f.store.docs.get(`rooms/${roomId}`);
  const handed = await f.call('socialRoomAction', 'alice', roomInput('leave', { roomId }));
  assert.equal(handed.room.hostUid, 'bob'); assert.equal(handed.room.state, 'active');
  assert.equal(handed.room.epoch, before.epoch + 1);
  assert.deepEqual(handed.room.participants, ['bob', 'charlie']); // dave isn't bob's friend
  assert.equal(f.store.docs.get(`roomInvites/dave/items/${roomId}`).state, 'closed');
  assert.equal(f.store.docs.get(`roomInvites/alice/items/${roomId}`).state, 'closed');
  const events = [...f.store.docs.entries()].filter(([p]) => p.startsWith(`rooms/${roomId}/messages/`)).map(([, m]) => `${m.kind}:${m.senderUid}`);
  assert.ok(events.includes('host:bob'));
  // Bob's device claims the lease with its own session, then only that session holds it.
  const lease = f.store.docs.get(`roomLeases/${roomId}`);
  assert.equal(lease.hostUid, 'bob'); assert.equal(lease.sessionId, null);
  const bobSession = op();
  await rejects(f.call('socialRoomAction', 'charlie', roomInput('heartbeat', { roomId, sessionId: op() })), 'permission-denied');
  await f.call('socialRoomAction', 'bob', roomInput('heartbeat', { roomId, sessionId: bobSession }));
  assert.equal(f.store.docs.get(`roomLeases/${roomId}`).sessionId, bobSession);
  await rejects(f.call('socialRoomAction', 'bob', roomInput('heartbeat', { roomId, sessionId: op() })), 'permission-denied');
  // Alice is out: she can't read along any more.
  await rejects(f.call('socialRoomMessage', 'alice', message(roomId)), 'permission-denied');
  // The last member leaving the new host alone ends the room.
  await f.call('socialRoomAction', 'charlie', roomInput('leave', { roomId }));
  assert.equal(f.store.docs.get(`rooms/${roomId}`).state, 'closed');
});
test('a host leaving with nobody joined, or after the lease ran out, closes the room', async () => {
  const f = await fixture(); await befriend(f);
  const invited = await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: content() }));
  assert.equal((await f.call('socialRoomAction', 'alice', roomInput('leave', { roomId: invited.roomId }))).room.state, 'closed');
  const roomId = await activeRoom(f);
  f.advance(90_001);
  assert.equal((await f.call('socialRoomAction', 'alice', roomInput('leave', { roomId }))).room.state, 'closed');
});
const ask = (action, otherUid) => ({ operationId: op(), action, otherUid });
test('asking to read along needs a friend who shares their reading, is pushed once and answered by an invite', async () => {
  const f = await fixture();
  await rejects(f.call('socialJoinRequest', 'alice', ask('ask', 'bob')), 'permission-denied'); // not friends
  await befriend(f);
  await rejects(f.call('socialJoinRequest', 'alice', ask('ask', 'bob')), 'failed-precondition'); // bob shares nothing
  await share(f, { activity: 'friends' }, 'bob');
  await rejects(f.call('socialJoinRequest', 'alice', ask('ask', 'bob')), 'failed-precondition'); // not reading now
  await f.call('socialPublishActivity', 'bob', { operationId: op(), generation: 2, activity: { content: content(), chapterKey: digest('c'), chapterLabel: 'Chapter 3' } });
  const asked = await f.call('socialJoinRequest', 'alice', ask('ask', 'bob'));
  assert.equal(asked.state, 'pending');
  const request = f.store.docs.get('joinRequests/bob/items/alice');
  assert.equal(request.name, 'alice'); assert.equal(request.expiresAt, f.now() + 600_000);
  assert.deepEqual(f.pushes.at(-1), { to: 'bob', kind: 'join_request', data: { name: 'alice', handle: 'alice', uid: 'alice' } });
  const pushes = f.pushes.length;
  await f.call('socialJoinRequest', 'alice', ask('ask', 'bob')); // already pending: no second push
  assert.equal(f.pushes.length, pushes);
  // Bob answers by inviting her: the request is gone.
  await f.call('socialRoomAction', 'bob', roomInput('create', { otherUid: 'alice', sessionId: op(), content: content(), position: position() }));
  assert.equal(f.store.docs.has('joinRequests/bob/items/alice'), false);
  // Or declines a later one.
  f.advance(1_000);
  await f.call('socialJoinRequest', 'alice', ask('ask', 'bob'));
  await f.call('socialJoinRequest', 'bob', ask('decline', 'alice'));
  assert.equal(f.store.docs.has('joinRequests/bob/items/alice'), false);
});
test('read-along requests are rate limited and vanish when the friendship ends', async () => {
  const f = await fixture(); await befriend(f);
  await share(f, { activity: 'friends' }, 'bob');
  const publish = () => f.call('socialPublishActivity', 'bob', { operationId: op(), generation: 2, activity: { content: content(), chapterKey: digest('c'), chapterLabel: 'Chapter 3' } });
  await publish();
  for (let i = 0; i < 10; i++) {
    await f.call('socialJoinRequest', 'alice', ask('ask', 'bob'));
    await f.call('socialJoinRequest', 'bob', ask('decline', 'alice'));
  }
  await rejects(f.call('socialJoinRequest', 'alice', ask('ask', 'bob')), 'resource-exhausted');
  f.advance(3_600_001); await publish();
  await f.call('socialJoinRequest', 'alice', ask('ask', 'bob'));
  await f.call('socialFriendAction', 'bob', { operationId: op(), action: 'remove', otherUid: 'alice' });
  assert.equal(f.store.docs.has('joinRequests/bob/items/alice'), false);
});
test('a room can name the host\'s source so friends know which extension to install', async () => {
  const f = await fixture(); await befriend(f);
  const source = { id: '2499283573021220255', name: 'MangaDex', lang: 'en', pkg: 'eu.kanade.tachiyomi.extension.all.mangadex' };
  const created = await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: { ...content(), source }, position: position() }));
  assert.deepEqual(created.room.content.source, source);
  await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: { ...content(2), source: { ...source, pkg: null } } }));
  for (const bad of [{ ...source, id: 'abc' }, { ...source, pkg: 'evil/../x' }, { ...source, extra: 1 }, { ...source, name: '' }, 'MangaDex']) {
    await rejects(f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: { ...content(3), source: bad } })), 'invalid-argument');
  }
});
test('pushes carry the sender uid for routing (message, group, friend, join and room notices)', async () => {
  const f = await fixture();
  await befriend(f); await befriend(f, 'alice', 'charlie');
  assert.equal(f.pushes.find((p) => p.kind === 'friend_request').data.uid, 'alice');
  assert.equal(f.pushes.find((p) => p.kind === 'friend_accepted').data.uid, 'bob');
  await f.call('socialMessage', 'bob', dm('send', 'alice', 'hey'));
  assert.equal(f.pushes.at(-1).kind, 'message'); assert.equal(f.pushes.at(-1).data.uid, 'bob');
  const created = await f.call('socialGroupChat', 'alice', { operationId: op(), action: 'create', groupId: null, name: 'Club', uids: ['bob'], text: null });
  assert.deepEqual(f.pushes.at(-1), { to: 'bob', kind: 'group_added', data: { name: 'alice', title: 'Club', groupId: created.groupId, uid: 'alice' } });
  await f.call('socialGroupChat', 'alice', { operationId: op(), action: 'add', groupId: created.groupId, name: null, uids: ['charlie'], text: null });
  assert.equal(f.pushes.at(-1).kind, 'group_added'); assert.equal(f.pushes.at(-1).data.uid, 'alice');
  await f.call('socialGroupChat', 'bob', { operationId: op(), action: 'send', groupId: created.groupId, name: null, uids: null, text: 'hi all' });
  const groupPush = f.pushes.filter((p) => p.kind === 'group_message').at(-1);
  assert.equal(groupPush.data.uid, 'bob'); assert.equal(groupPush.data.groupId, created.groupId);
  const room = await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: content(), position: position() }));
  assert.deepEqual(f.pushes.at(-1).data, { name: 'alice', title: 'Title 1', roomId: room.roomId, uid: 'alice' });
  await f.call('socialRoomAction', 'alice', roomInput('invite', { roomId: room.roomId, otherUid: 'charlie' }));
  assert.deepEqual(f.pushes.at(-1), { to: 'charlie', kind: 'room_invite', data: { name: 'alice', title: 'Title 1', roomId: room.roomId, uid: 'alice' } });
  await share(f, { activity: 'friends' }, 'bob');
  await f.call('socialPublishActivity', 'bob', { operationId: op(), generation: 2, activity: { content: content(), chapterKey: digest('c'), chapterLabel: 'Chapter 1' } });
  await f.call('socialJoinRequest', 'alice', { operationId: op(), action: 'ask', otherUid: 'bob' });
  assert.deepEqual(f.pushes.at(-1), { to: 'bob', kind: 'join_request', data: { name: 'alice', handle: 'alice', uid: 'alice' } });
});
test('a room source may carry the host\'s manga URL: relative or https, bounded, no whitespace', async () => {
  const f = await fixture(); await befriend(f);
  const source = { id: '2499283573021220255', name: 'MangaDex', lang: 'en', pkg: 'eu.kanade.tachiyomi.extension.all.mangadex' };
  const create = (n, src) => f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: { ...content(n), source: src } }));
  for (const [n, url] of [[1, '/title/abc-123'], [2, 'https://example.org/manga/1'], [3, '/'], [4, '/' + 'x'.repeat(499)]]) {
    assert.equal((await create(n, { ...source, url })).room.content.source.url, url);
  }
  for (const url of ['', 'title/abc', 'http://example.org/x', 'https://', 'https://exa mple.org', '/a\tb', '/a\nb', '/a\u0000', '/a b', '/' + 'x'.repeat(500), 42, null, 'javascript:alert(1)']) {
    await rejects(create(9, { ...source, url }), 'invalid-argument');
  }
});
const wish = (action, otherUid, c = content()) => ({ operationId: op(), action, otherUid, content: c });
test('read wishes stay hidden until both flag a title; a match tells both, chats and pushes once', async () => {
  const f = await fixture(); await befriend(f);
  const c = content(), pair = `readWishes/${pairId('alice', 'bob')}/items/${c.contentKey}`;
  assert.deepEqual(await f.call('socialReadWish', 'alice', wish('add', 'bob', c)), { state: 'wished' });
  assert.equal(f.store.docs.get(pair).flags.alice, f.now());
  assert.equal(f.store.docs.get(pair).expiresAt, f.now() + 60 * 86_400_000);
  const mine = f.store.docs.get(`readWishIndex/alice/items/bob_${c.contentKey}`);
  assert.equal(mine.matched, false); assert.equal(mine.matchedAt, null); assert.equal(mine.otherUid, 'bob'); assert.deepEqual(mine.content, c);
  // One-sided: nothing for bob, no push, no chat line.
  assert.equal(f.store.docs.has(`readWishIndex/bob/items/alice_${c.contentKey}`), false);
  assert.equal(f.pushes.some((p) => p.kind === 'read_match'), false);
  assert.equal(f.store.docs.has(`chats/${pairId('alice', 'bob')}`), false);
  // Re-adding is a no-op.
  assert.deepEqual(await f.call('socialReadWish', 'alice', wish('add', 'bob', c)), { state: 'wished' });
  f.advance(1_000);
  assert.deepEqual(await f.call('socialReadWish', 'bob', wish('add', 'alice', c)), { state: 'matched' });
  for (const path of [`readWishIndex/alice/items/bob_${c.contentKey}`, `readWishIndex/bob/items/alice_${c.contentKey}`]) {
    assert.equal(f.store.docs.get(path).matched, true); assert.equal(f.store.docs.get(path).matchedAt, f.now());
  }
  const chatId = pairId('alice', 'bob'), line = f.store.docs.get(`chats/${chatId}/messages/0000000000`);
  assert.equal(line.system, 'match'); assert.equal(line.text, ''); assert.equal(line.senderUid, 'bob');
  assert.deepEqual(line.content, c); assert.equal(line.expiresAt, f.now() + 30 * 86_400_000);
  assert.equal(f.store.docs.get('chatIndex/alice/chats/bob').lastText, '🤝 You both want to read Title 1');
  assert.equal(f.store.docs.get('chatIndex/bob/chats/alice').lastText, '🤝 You both want to read Title 1');
  assert.equal(f.store.docs.get('chatIndex/alice/chats/bob').unread, 1);
  assert.equal(f.store.docs.get('chatIndex/bob/chats/alice').unread, 0);
  assert.deepEqual(f.pushes.filter((p) => p.kind === 'read_match'), [{ to: 'alice', kind: 'read_match', data: { name: 'bob', title: 'Title 1', uid: 'bob' } }]);
  assert.deepEqual(await f.call('socialReadWish', 'bob', wish('add', 'alice', c)), { state: 'matched' });
  assert.equal(f.pushes.filter((p) => p.kind === 'read_match').length, 1);
  // Removing un-matches the other side and keeps their flag; the last flag out deletes the pair document.
  assert.deepEqual(await f.call('socialReadWish', 'bob', wish('remove', 'alice', c)), { state: 'removed' });
  assert.equal(f.store.docs.has(`readWishIndex/bob/items/alice_${c.contentKey}`), false);
  const alices = f.store.docs.get(`readWishIndex/alice/items/bob_${c.contentKey}`);
  assert.equal(alices.matched, false); assert.equal(alices.matchedAt, null);
  assert.deepEqual(Object.keys(f.store.docs.get(pair).flags), ['alice']);
  await f.call('socialReadWish', 'alice', wish('remove', 'bob', c));
  assert.equal(f.store.docs.has(pair), false);
  assert.equal(f.store.docs.has(`readWishIndex/alice/items/bob_${c.contentKey}`), false);
});
test('read wishes need a friend, valid input and stop after 50 new wishes a day', async () => {
  const f = await fixture(); await befriend(f);
  await rejects(f.call('socialReadWish', 'alice', wish('add', 'charlie')), 'permission-denied');
  await rejects(f.call('socialReadWish', 'alice', wish('add', 'alice')), 'invalid-argument');
  await rejects(f.call('socialReadWish', 'alice', wish('like', 'bob')), 'invalid-argument');
  await rejects(f.call('socialReadWish', 'alice', wish('add', 'bob', { ...content(), cookie: 'x' })), 'invalid-argument');
  await rejects(f.call('socialReadWish', 'alice', { ...wish('add', 'bob'), extra: 1 }), 'invalid-argument');
  for (let i = 0; i < 50; i++) await f.call('socialReadWish', 'alice', wish('add', 'bob', content(100 + i)));
  await rejects(f.call('socialReadWish', 'alice', wish('add', 'bob', content(200))), 'resource-exhausted');
  f.advance(86_400_000);
  assert.equal((await f.call('socialReadWish', 'alice', wish('add', 'bob', content(200)))).state, 'wished');
  // Blocking ends wishing (adds), while taking one back still works.
  await f.call('socialFriendAction', 'bob', { operationId: op(), action: 'block', otherUid: 'alice' });
  await rejects(f.call('socialReadWish', 'alice', wish('add', 'bob', content(201))), 'permission-denied');
  assert.equal((await f.call('socialReadWish', 'alice', wish('remove', 'bob', content(200)))).state, 'removed');
});
async function readTogether(f, guests = ['bob'], fields = {}) {
  const sessionId = op();
  const created = await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: guests[0], sessionId, content: content(), position: position(), ...fields }));
  for (const guest of guests.slice(1)) await f.call('socialRoomAction', 'alice', roomInput('invite', { roomId: created.roomId, otherUid: guest }));
  for (const guest of guests) await f.call('socialRoomAction', guest, roomInput('join', { roomId: created.roomId }));
  return { roomId: created.roomId, sessionId, created };
}
async function heartbeatFor(f, room, minutes) {
  for (let i = 0; i < minutes * 2; i++) {
    f.advance(30_000);
    await f.call('socialRoomAction', 'alice', roomInput('heartbeat', { roomId: room.roomId, sessionId: room.sessionId }));
  }
}
test('a two-person room of five minutes or more leaves a recap line in the chat when it closes, without unread or push', async () => {
  const f = await fixture(); await befriend(f);
  const room = await readTogether(f);
  assert.equal(room.created.room.startChapterLabel, 'Chapter 1');
  // The host moves on to chapter 3 (hosts write position fields directly through the rules; simulated here).
  const doc = f.store.docs.get(`rooms/${room.roomId}`);
  f.store.docs.set(`rooms/${room.roomId}`, { ...doc, position: { ...position(), chapterLabel: 'Chapter 3' } });
  await heartbeatFor(f, room, 6);
  const pushes = f.pushes.length;
  await f.call('socialRoomAction', 'alice', roomInput('close', { roomId: room.roomId }));
  const chatId = pairId('alice', 'bob'), line = f.store.docs.get(`chats/${chatId}/messages/0000000000`);
  assert.equal(line.system, 'recap'); assert.equal(line.text, ''); assert.equal(line.senderUid, 'alice');
  assert.deepEqual(line.recap, { title: 'Title 1', fromChapter: 'Chapter 1', toChapter: 'Chapter 3', minutes: 6 });
  assert.equal(line.expiresAt, f.now() + 30 * 86_400_000);
  for (const path of ['chatIndex/alice/chats/bob', 'chatIndex/bob/chats/alice']) {
    assert.equal(f.store.docs.get(path).lastText, '📖 Read together: Title 1'); assert.equal(f.store.docs.get(path).unread, 0);
  }
  assert.equal(f.pushes.length, pushes);
  // A second close (no-op) adds nothing.
  await f.call('socialRoomAction', 'bob', roomInput('leave', { roomId: room.roomId }));
  assert.equal(f.store.docs.get(`chats/${chatId}`).next, 1);
});
test('recaps: on a guest leave, a host hand-off and expiry; none for short or 3-person rooms or ex-friends; old rooms close fine', async () => {
  const f = await fixture(); await group(f, ['bob', 'charlie']);
  const chat = (a, b) => f.store.docs.get(`chats/${pairId(a, b)}`);
  // Guest leaves after 5 minutes: the room closes and is recapped; unread counts stay as they were.
  await f.call('socialMessage', 'alice', dm('send', 'bob', 'ready?'));
  let room = await readTogether(f);
  await heartbeatFor(f, room, 5);
  await f.call('socialRoomAction', 'bob', roomInput('leave', { roomId: room.roomId }));
  assert.equal(chat('alice', 'bob').next, 2);
  assert.equal(f.store.docs.get('chatIndex/bob/chats/alice').unread, 1);
  // Expired lease: closed by the cleanup, minutes counted up to the lease end.
  room = await readTogether(f, ['bob'], { position: null });
  await heartbeatFor(f, room, 5);
  f.advance(10 * 60_000);
  assert.equal((await f.service.expireRoom(room.roomId)).expired, true);
  assert.deepEqual(f.store.docs.get(`chats/${pairId('alice', 'bob')}/messages/0000000002`).recap,
    { title: 'Title 1', fromChapter: null, toChapter: null, minutes: 6 });
  // Too short.
  room = await readTogether(f);
  await heartbeatFor(f, room, 4);
  await f.call('socialRoomAction', 'alice', roomInput('close', { roomId: room.roomId }));
  assert.equal(chat('alice', 'bob').next, 3);
  // Three people: no recap in v1.
  room = await readTogether(f, ['bob', 'charlie']);
  await heartbeatFor(f, room, 6);
  await f.call('socialRoomAction', 'alice', roomInput('close', { roomId: room.roomId }));
  assert.equal(chat('alice', 'bob').next, 3); assert.equal(chat('alice', 'charlie'), undefined);
  // An old room without startChapterLabel still closes, with fromChapter null.
  room = await readTogether(f, ['charlie']);
  const { startChapterLabel: _gone, ...old } = f.store.docs.get(`rooms/${room.roomId}`);
  f.store.docs.set(`rooms/${room.roomId}`, old);
  await heartbeatFor(f, room, 5);
  assert.equal((await f.call('socialRoomAction', 'alice', roomInput('close', { roomId: room.roomId }))).room.state, 'closed');
  assert.equal(chat('alice', 'charlie').next, 1);
  assert.equal(f.store.docs.get(`chats/${pairId('alice', 'charlie')}/messages/0000000000`).recap.fromChapter, null);
  // The host leaves a two-person room: the session is recapped at the hand-off.
  room = await readTogether(f, ['charlie']);
  await heartbeatFor(f, room, 5);
  await f.call('socialRoomAction', 'alice', roomInput('leave', { roomId: room.roomId }));
  assert.equal(chat('alice', 'charlie').next, 2);
  assert.equal(f.store.docs.get(`chats/${pairId('alice', 'charlie')}/messages/0000000001`).senderUid, 'alice');
  // No longer friends: expiry closes without a recap.
  room = await readTogether(f);
  await heartbeatFor(f, room, 5);
  await f.call('socialFriendAction', 'bob', { operationId: op(), action: 'remove', otherUid: 'alice' });
  f.advance(120_000);
  assert.equal((await f.service.expireRoom(room.roomId)).expired, true);
  assert.equal(chat('alice', 'bob').next, 3);
});
const msgPath = (chatId, seq) => `chats/${chatId}/messages/${String(seq).padStart(10, '0')}`;
const react = (otherUid, seq, emoji) => ({ operationId: op(), action: 'react', otherUid, text: null, seq, emoji });
test('reaction emojis: one emoji of at most 16 bytes, never text, digits, whitespace or control characters', () => {
  for (const good of ['❤️', '❤', '😂', '👍🏽', '🏳️‍🌈', '❤️‍🔥', '🧑🏽‍💻', '1️⃣', '#️⃣', '🇩🇪', '🫠']) assert.ok(isReactionEmoji(good), good);
  for (const bad of ['', 'a', '1', 'ok', ' ', '\n', '😂😂', '🇩🇪🇫🇷', '👨‍👩‍👧', '‍', '️', '🏽', ' ❤', '❤ ', 'x❤', '😂\u0000', 7, null]) {
    assert.equal(isReactionEmoji(bad), false, JSON.stringify(bad));
  }
});
test('private replies store a short quote of a live message; gone or system lines cannot be quoted', async () => {
  const f = await fixture(); await befriend(f);
  const chatId = pairId('alice', 'bob');
  await f.call('socialMessage', 'alice', dm('send', 'bob', `long ${'x'.repeat(200)}`)); f.advance(1_000);
  await f.call('socialMessage', 'alice', { ...dm('send', 'bob', null), content: content(4) }); f.advance(1_000);
  const sent = await f.call('socialMessage', 'bob', { ...dm('send', 'alice', 'agreed'), replyTo: 0 });
  const reply = f.store.docs.get(msgPath(chatId, sent.seq));
  assert.deepEqual(reply.replyTo, { seq: 0, senderUid: 'alice', preview: `long ${'x'.repeat(95)}` });
  assert.equal(f.store.docs.get('chatIndex/alice/chats/bob').lastText, 'agreed'); // the quote isn't in the preview
  assert.equal(f.pushes.at(-1).data.title, 'agreed');
  f.advance(1_000);
  const second = await f.call('socialMessage', 'bob', { ...dm('send', 'alice', 'on it'), replyTo: 1 });
  assert.equal(f.store.docs.get(msgPath(chatId, second.seq)).replyTo.preview, '📖 Title 4');
  // No reply: absent or null; older apps send neither.
  f.advance(1_000);
  const plain = await f.call('socialMessage', 'alice', { ...dm('send', 'bob', 'plain'), replyTo: null });
  assert.equal(f.store.docs.get(msgPath(chatId, plain.seq)).replyTo, undefined);
  f.advance(1_000);
  for (const replyTo of [99, -1, 1.5, '0']) await rejects(f.call('socialMessage', 'alice', { ...dm('send', 'bob', 'hi'), replyTo }), 'invalid-argument');
  await rejects(f.call('socialMessage', 'alice', { ...dm('read', 'bob'), replyTo: 0 }), 'invalid-argument');
  // A server line (read match / recap) can't be quoted, and nor can an expired message.
  const line = f.store.docs.get(msgPath(chatId, 0));
  f.store.docs.set(msgPath(chatId, 0), { ...line, system: 'recap' });
  await rejects(f.call('socialMessage', 'alice', { ...dm('send', 'bob', 'hi'), replyTo: 0 }), 'invalid-argument');
  f.store.docs.set(msgPath(chatId, 0), line);
  f.advance(30 * 86_400_000);
  await rejects(f.call('socialMessage', 'alice', { ...dm('send', 'bob', 'hi'), replyTo: 1 }), 'invalid-argument');
});
test('private reactions: one per person, replaced or removed, friends only, no unread, preview or push', async () => {
  const f = await fixture(); await befriend(f);
  const chatId = pairId('alice', 'bob');
  await f.call('socialMessage', 'alice', dm('send', 'bob', 'hello'));
  await f.call('socialMessage', 'bob', dm('read', 'alice'));
  const pushes = f.pushes.length, index = structuredClone(f.store.docs.get('chatIndex/bob/chats/alice'));
  const expiresAt = f.store.docs.get(msgPath(chatId, 0)).expiresAt;
  assert.deepEqual(await f.call('socialMessage', 'bob', react('alice', 0, '❤️')), { chatId, seq: 0, emoji: '❤️' });
  await f.call('socialMessage', 'alice', react('bob', 0, '😂'));
  assert.deepEqual(f.store.docs.get(msgPath(chatId, 0)).reactions, { bob: '❤️', alice: '😂' });
  await f.call('socialMessage', 'bob', react('alice', 0, '👍🏽')); // a different emoji replaces mine
  assert.deepEqual(f.store.docs.get(msgPath(chatId, 0)).reactions, { bob: '👍🏽', alice: '😂' });
  assert.equal((await f.call('socialMessage', 'bob', react('alice', 0, '👍🏽'))).emoji, null); // the same again removes it
  await f.call('socialMessage', 'alice', react('bob', 0, '😂'));
  const message = f.store.docs.get(msgPath(chatId, 0));
  assert.equal(message.reactions, undefined); assert.equal(message.text, 'hello'); assert.equal(message.expiresAt, expiresAt);
  assert.equal(f.pushes.length, pushes);
  assert.deepEqual(f.store.docs.get('chatIndex/bob/chats/alice'), index);
  assert.equal(f.store.docs.get('chatIndex/alice/chats/bob').unread, 0);
  assert.equal(f.store.docs.get(`chats/${chatId}`).next, 1);
  // Idempotent: a retried operation doesn't toggle twice.
  const once = react('alice', 0, '🔥');
  await f.call('socialMessage', 'bob', once); await f.call('socialMessage', 'bob', once);
  assert.deepEqual(f.store.docs.get(msgPath(chatId, 0)).reactions, { bob: '🔥' });
  // Validation: one emoji, react carries no text, other actions carry no seq or emoji, the message must exist.
  for (const emoji of ['a', 'ok', '😂😂', ' ', '', null]) await rejects(f.call('socialMessage', 'bob', react('alice', 0, emoji)), 'invalid-argument');
  await rejects(f.call('socialMessage', 'bob', { ...react('alice', 0, '❤️'), text: 'hi' }), 'invalid-argument');
  await rejects(f.call('socialMessage', 'bob', { ...react('alice', 0, '❤️'), replyTo: 0 }), 'invalid-argument');
  await rejects(f.call('socialMessage', 'bob', { operationId: op(), action: 'react', otherUid: 'alice', text: null, emoji: '❤️' }), 'invalid-argument');
  await rejects(f.call('socialMessage', 'bob', react('alice', 5, '❤️')), 'invalid-argument');
  await rejects(f.call('socialMessage', 'bob', { ...dm('send', 'alice', 'hi'), seq: 0, emoji: '❤️' }), 'invalid-argument');
  // Friends only: strangers and ex-friends can't react.
  await rejects(f.call('socialMessage', 'charlie', react('alice', 0, '❤️')), 'permission-denied');
  await f.call('socialFriendAction', 'bob', { operationId: op(), action: 'remove', otherUid: 'alice' });
  await rejects(f.call('socialMessage', 'bob', react('alice', 0, '❤️')), 'permission-denied');
});
test('reactions have their own burst and daily limits and leave the message quota alone', async () => {
  const f = await fixture(); await befriend(f);
  await f.call('socialMessage', 'alice', dm('send', 'bob', 'hello'));
  for (let i = 0; i < REACTIONS_PER_MINUTE; i++) await f.call('socialMessage', 'bob', react('alice', 0, i % 2 ? '😂' : '❤️'));
  await rejects(f.call('socialMessage', 'bob', react('alice', 0, '❤️')), 'resource-exhausted');
  f.advance(60_000);
  await f.call('socialMessage', 'bob', react('alice', 0, '❤️'));
  const quota = f.store.docs.get('quotas/bob');
  assert.equal(quota.counts.socialReaction, REACTIONS_PER_MINUTE + 1);
  assert.equal(quota.counts.socialMessage, undefined);
});
test('group replies and reactions: members only, from when they joined, no unread or push on react', async () => {
  const f = await fixture(); await befriend(f); await befriend(f, 'alice', 'charlie');
  const { groupId } = await f.call('socialGroupChat', 'alice', gc('create', { name: 'Club', uids: ['bob'] }));
  const sent = await f.call('socialGroupChat', 'bob', gc('send', { groupId, text: 'first!' }));
  const at = (seq) => f.store.docs.get(`groups/${groupId}/messages/${String(seq).padStart(10, '0')}`);
  const reply = await f.call('socialGroupChat', 'alice', gc('send', { groupId, text: 'welcome', replyTo: sent.seq }));
  assert.deepEqual(at(reply.seq).replyTo, { seq: sent.seq, senderUid: 'bob', preview: 'first!', senderName: 'bob' });
  f.advance(1_000);
  await rejects(f.call('socialGroupChat', 'alice', gc('send', { groupId, text: 'x', replyTo: 0 })), 'invalid-argument'); // the "created" event
  // Reactions.
  const unread = f.store.docs.get(`groupIndex/bob/groups/${groupId}`).unread, pushes = f.pushes.length;
  const gr = (fields) => gc('react', { groupId, ...fields });
  assert.deepEqual(await f.call('socialGroupChat', 'bob', gr({ seq: reply.seq, emoji: '😮' })), { groupId, seq: reply.seq, emoji: '😮' });
  assert.deepEqual(at(reply.seq).reactions, { bob: '😮' });
  assert.equal(f.store.docs.get(`groupIndex/alice/groups/${groupId}`).lastText, 'welcome');
  assert.equal(f.store.docs.get(`groupIndex/bob/groups/${groupId}`).unread, unread);
  assert.equal(f.pushes.length, pushes);
  await rejects(f.call('socialGroupChat', 'bob', gr({ seq: 0, emoji: '😮' })), 'invalid-argument'); // events take none
  await rejects(f.call('socialGroupChat', 'bob', gr({ seq: reply.seq, emoji: 'lol' })), 'invalid-argument');
  await rejects(f.call('socialGroupChat', 'bob', gr({ seq: reply.seq, emoji: '😮', text: 'hi' })), 'invalid-argument');
  await rejects(f.call('socialGroupChat', 'charlie', gr({ seq: reply.seq, emoji: '😮' })), 'permission-denied');
  // A newcomer can't react to or quote what was said before they joined.
  await f.call('socialGroupChat', 'alice', gc('add', { groupId, uids: ['charlie'] }));
  await rejects(f.call('socialGroupChat', 'charlie', gr({ seq: reply.seq, emoji: '😮' })), 'invalid-argument');
  await rejects(f.call('socialGroupChat', 'charlie', gc('send', { groupId, text: 'hi', replyTo: reply.seq })), 'invalid-argument');
  const later = await f.call('socialGroupChat', 'charlie', gc('send', { groupId, text: 'hi all' }));
  await f.call('socialGroupChat', 'charlie', gr({ seq: later.seq, emoji: '👍' }));
  assert.deepEqual(at(later.seq).reactions, { charlie: '👍' });
  await rejects(f.call('socialGroupChat', 'alice', gc('rename', { groupId, name: 'x', replyTo: later.seq })), 'invalid-argument');
});

// ---------- friends who read this: progress; ratings and reviews (2026-10-06) ----------
const { workKey, REVIEWS_PER_DAY } = require('../lib/domain.js');
const review = (action, fields = {}) => ({ operationId: op(), action, content: content(1), rating: action === 'set' ? 4 : null, text: null, ...fields });
test('library entries may carry reading progress; favorites may not; progress is validated', async () => {
  const f = await fixture(); await share(f, { library: 'friends', favorites: 'friends' });
  const publish = (kind, entry) => f.call('socialPublishEntries', 'alice', { operationId: op(), generation: 2, kind, entries: [entry], deleteKeys: [] });
  await publish('library', { ...content(1), progress: { read: 40, total: 120, finished: false } });
  const stored = f.store.docs.get(`sharedLibraries/alice/entries/${content(1).contentKey}`);
  assert.deepEqual(stored.progress, { read: 40, total: 120, finished: false });
  assert.deepEqual(stored.content, content(1)); // progress is not part of the content projection
  await publish('library', { ...content(2), progress: { read: 12, total: 12, finished: true } });
  await publish('library', { ...content(3), progress: { read: 0, total: 0, finished: false } }); // opened, nothing read yet
  await publish('library', content(4)); // older clients: no progress at all
  assert.equal(f.store.docs.get(`sharedLibraries/alice/entries/${content(4).contentKey}`).progress, undefined);
  await rejects(publish('favorites', { ...content(5), progress: { read: 1, total: 2, finished: false } }), 'invalid-argument');
  for (const progress of [{ read: 3, total: 2, finished: false }, { read: 1, total: 2, finished: true }, { read: 0, total: 0, finished: true },
    { read: -1, total: 2, finished: false }, { read: 1.5, total: 2, finished: false }, { read: 1, total: 2 }, { read: 1, total: 2, finished: 'no' },
    { read: 1, total: 2, finished: false, percent: 50 }, null, 'half', { read: 1, total: 100_001, finished: false }]) {
    await rejects(publish('library', { ...content(6), progress }), 'invalid-argument');
  }
});
test('workKey: the tracker entry when tracked, otherwise the exact source work (same vectors as Android TitleMatch)', () => {
  const tracked = { ...content(1), canonicalProvider: 'anilist', canonicalId: '151807' };
  assert.equal(workKey(tracked), 'e692c2b5b1c74c0c5dce34809370bd00a973919c76c39d14fdc0e4f54b2ab22e');
  const sha = (value) => require('node:crypto').createHash('sha256').update(value).digest('hex');
  assert.equal(workKey({ ...content(1), sourceWorkKey: sha('x') }), '10b597de832c7a163ec706b24ea2750a8c68ec7804926aa1d419a69a4881def0');
  // The same tracked work read on another source is the same work; another tracker ID is not.
  assert.equal(workKey({ ...content(2), canonicalProvider: 'anilist', canonicalId: '151807' }), workKey(tracked));
  assert.notEqual(workKey({ ...tracked, canonicalId: '151808' }), workKey(tracked));
  assert.notEqual(workKey({ ...tracked, canonicalProvider: 'mal' }), workKey(tracked));
  assert.notEqual(workKey(content(1)), workKey(content(2)));
});
test('reviews: set, edit keeping createdAt, delete; one per work across sources; hidden titles refused', async () => {
  const f = await fixture();
  const first = await f.call('socialReview', 'alice', review('set', { rating: 5, text: '  Loved it.\nWould read again.  ' }));
  const path = `reviews/alice/items/${workKey(content(1))}`;
  assert.equal(first.workKey, workKey(content(1)));
  assert.deepEqual(f.store.docs.get(path), { schemaVersion: 1, workKey: workKey(content(1)), content: content(1), rating: 5,
    text: 'Loved it.\nWould read again.', createdAt: f.now(), updatedAt: f.now() });
  const created = f.now(); f.advance(60_000);
  await f.call('socialReview', 'alice', review('set', { rating: 3, text: '   ' })); // blank text means none
  assert.equal(f.store.docs.get(path).rating, 3); assert.equal(f.store.docs.get(path).text, null);
  assert.equal(f.store.docs.get(path).createdAt, created); assert.equal(f.store.docs.get(path).updatedAt, f.now());
  // Tracked: the same AniList entry on two sources is one review.
  const onA = { ...content(2), canonicalProvider: 'anilist', canonicalId: '7' }, onB = { ...content(3), canonicalProvider: 'anilist', canonicalId: '7' };
  await f.call('socialReview', 'alice', review('set', { content: onA, rating: 2 }));
  await f.call('socialReview', 'alice', review('set', { content: onB, rating: 4 }));
  const reviews = () => [...f.store.docs.keys()].filter((key) => key.startsWith('reviews/alice/items/'));
  assert.equal(reviews().length, 2);
  assert.equal(f.store.docs.get(`reviews/alice/items/${workKey(onA)}`).content.contentKey, onB.contentKey);
  assert.deepEqual(await f.call('socialReview', 'alice', review('delete')), { workKey: workKey(content(1)), deleted: true });
  assert.equal(f.store.docs.has(path), false);
  assert.equal((await f.call('socialReview', 'alice', review('delete'))).deleted, false);
  // A hidden title can't be reviewed.
  await share(f, {}, 'alice', 1, content(4).contentKey);
  await rejects(f.call('socialReview', 'alice', review('set', { content: content(4) })), 'failed-precondition');
});
test('reviews validate rating, text and content, and stop after 30 a day', async () => {
  const f = await fixture();
  for (const rating of [0, 6, 2.5, '5', null]) await rejects(f.call('socialReview', 'alice', review('set', { rating })), 'invalid-argument');
  for (const text of ['x'.repeat(1001), 'bad\u0007bell', 'tab\there', 'a\n'.repeat(31), 42, '']) {
    await rejects(f.call('socialReview', 'alice', review('set', { text })), 'invalid-argument');
  }
  assert.equal((await f.call('socialReview', 'alice', review('set', { text: 'x'.repeat(1000) }))).review.text.length, 1000);
  await rejects(f.call('socialReview', 'alice', review('delete', { rating: 3 })), 'invalid-argument');
  await rejects(f.call('socialReview', 'alice', review('delete', { text: 'gone' })), 'invalid-argument');
  await rejects(f.call('socialReview', 'alice', review('rate')), 'invalid-argument');
  await rejects(f.call('socialReview', 'alice', { ...review('set'), uid: 'bob' }), 'invalid-argument');
  await rejects(f.call('socialReview', 'alice', review('set', { content: { ...content(1), cookie: 'x' } })), 'invalid-argument');
  const source = { id: '123', name: 'Source', lang: 'en', pkg: null, url: '/manga/1' };
  await rejects(f.call('socialReview', 'alice', review('set', { content: { ...content(1), source } })), 'invalid-argument');
  await rejects(f.call('socialReview', null, review('set')), 'unauthenticated');
  for (let i = 2; i <= REVIEWS_PER_DAY; i++) await f.call('socialReview', 'alice', review('set', { content: content(i) }));
  await rejects(f.call('socialReview', 'alice', review('set', { content: content(99) })), 'resource-exhausted');
  await f.call('socialReview', 'alice', review('delete')); // deleting isn't a new review
  f.advance(86_400_000);
  await f.call('socialReview', 'alice', review('set', { content: content(99) }));
});
test('the reviews switch starts on, is optional for older clients and is reported to friends (off while invisible)', async () => {
  const f = await fixture(); await befriend(f);
  assert.equal((await f.call('socialGetSelf', 'alice', {})).settings.reviews, true);
  assert.equal((await f.call('socialGetFriend', 'bob', { otherUid: 'alice' })).sharing.reviews, true);
  await share(f, { reviews: false });
  assert.equal(f.store.docs.get('sharingSettings/alice').reviews, false);
  assert.equal((await f.call('socialGetFriend', 'bob', { otherUid: 'alice' })).sharing.reviews, false);
  await share(f, { library: 'friends' }, 'alice', 2); // an older client leaves it out: the choice stays
  assert.equal(f.store.docs.get('sharingSettings/alice').reviews, false);
  await share(f, { reviews: true, invisible: true }, 'alice', 3);
  assert.equal((await f.call('socialGetFriend', 'bob', { otherUid: 'alice' })).sharing.reviews, false);
  await share(f, { reviews: true }, 'alice', 4);
  assert.equal((await f.call('socialGetFriend', 'bob', { otherUid: 'alice' })).sharing.reviews, true);
  // Settings written before the switch existed lack it: absent means on.
  const stored = f.store.docs.get('sharingSettings/alice'); delete stored.reviews;
  assert.equal((await f.call('socialGetFriend', 'bob', { otherUid: 'alice' })).sharing.reviews, true);
  await rejects(f.call('socialUpdateSharing', 'alice', { operationId: op(), expectedGeneration: 5, settings: { ...settings(), reviews: 'on' }, hideContentKey: null }), 'invalid-argument');
});

// ---------- review fixes (2026-10-06) ----------
test('hiding a title deletes my reviews written for it, and only those', async () => {
  const f = await fixture(); await befriend(f);
  await f.call('socialReview', 'alice', review('set', { rating: 5 }));
  await f.call('socialReview', 'alice', review('set', { content: content(2), rating: 2 }));
  await f.call('socialReview', 'bob', review('set', { rating: 1 })); // someone else's review of the same title stays
  await share(f, {}, 'alice', 1, content(1).contentKey);
  assert.equal(f.store.docs.has(`reviews/alice/items/${workKey(content(1))}`), false);
  assert.equal(f.store.docs.has(`reviews/alice/items/${workKey(content(2))}`), true);
  assert.equal(f.store.docs.has(`reviews/bob/items/${workKey(content(1))}`), true);
  // A tracked work reviewed from another source keeps that review: it was written for the other title.
  const onA = { ...content(3), canonicalProvider: 'anilist', canonicalId: '9' }, onB = { ...content(4), canonicalProvider: 'anilist', canonicalId: '9' };
  await f.call('socialReview', 'alice', review('set', { content: onB, rating: 3 }));
  await share(f, {}, 'alice', 2, onA.contentKey);
  assert.equal(f.store.docs.has(`reviews/alice/items/${workKey(onB)}`), true);
  await share(f, {}, 'alice', 3, onB.contentKey);
  assert.equal(f.store.docs.has(`reviews/alice/items/${workKey(onB)}`), false);
});
test('the progress switch starts on, is optional, reaches friends and strips progress from new entries while off', async () => {
  const f = await fixture(); await befriend(f);
  assert.equal((await f.call('socialGetSelf', 'alice', {})).settings.progress, true);
  assert.equal((await f.call('socialGetFriend', 'bob', { otherUid: 'alice' })).sharing.progress, true);
  await share(f, { library: 'friends', progress: false });
  assert.equal(f.store.docs.get('sharingSettings/alice').progress, false);
  assert.equal((await f.call('socialGetFriend', 'bob', { otherUid: 'alice' })).sharing.progress, false);
  const publish = (generation, entry) => f.call('socialPublishEntries', 'alice', { operationId: op(), generation, kind: 'library', entries: [entry], deleteKeys: [] });
  // An older app still sends progress: it's dropped.
  await publish(2, { ...content(1), progress: { read: 3, total: 9, finished: false } });
  const path = `sharedLibraries/alice/entries/${content(1).contentKey}`;
  assert.equal(f.store.docs.get(path).progress, undefined);
  await share(f, { library: 'friends' }, 'alice', 2); // left out: the choice stays
  assert.equal(f.store.docs.get('sharingSettings/alice').progress, false);
  await share(f, { library: 'friends', progress: true }, 'alice', 3);
  await publish(4, { ...content(1), progress: { read: 3, total: 9, finished: false } });
  assert.deepEqual(f.store.docs.get(path).progress, { read: 3, total: 9, finished: false });
  const stored = f.store.docs.get('sharingSettings/alice'); delete stored.progress; // settings from before the switch
  assert.equal((await f.call('socialGetFriend', 'bob', { otherUid: 'alice' })).sharing.progress, true);
  await rejects(f.call('socialUpdateSharing', 'alice', { operationId: op(), expectedGeneration: 4, settings: { ...settings(), progress: 'off' }, hideContentKey: null }), 'invalid-argument');
});
const nudge = (otherUid) => ({ operationId: op(), otherUid });
test('nudges: friends only, no blocks, one per pair per 24 h, push payload with an at-risk streak only when shared', async () => {
  const f = await fixture(), day = 86_400_000;
  await rejects(f.call('socialNudge', 'alice', nudge('bob')), 'permission-denied'); // not friends yet
  await befriend(f);
  await rejects(f.call('socialNudge', 'alice', nudge('alice')), 'invalid-argument');
  await rejects(f.call('socialNudge', 'alice', { ...nudge('bob'), extra: 1 }), 'invalid-argument');
  assert.deepEqual(await f.call('socialNudge', 'alice', nudge('bob')), { nudged: true });
  assert.deepEqual(f.pushes.at(-1), { to: 'bob', kind: 'nudge', data: { name: 'alice', uid: 'alice' } }); // no streak yet
  assert.equal([...f.store.docs.keys()].filter((k) => k.startsWith('nudgeLimits/')).length, 1); // server-only bookkeeping
  // Same pair within 24 h is refused, in both directions it is a separate pair.
  f.advance(day - 1000);
  await rejects(f.call('socialNudge', 'alice', nudge('bob')), 'resource-exhausted');
  assert.deepEqual(await f.call('socialNudge', 'bob', nudge('alice')), { nudged: true });
  f.advance(2000);
  // Bob builds a 2-day streak and then misses today: at risk.
  await f.call('socialRecordChapter', 'bob', chapter({ generation: 1 }));
  f.advance(day);
  await f.call('socialRecordChapter', 'bob', chapter({ generation: 1 }));
  f.advance(day);
  await f.call('socialNudge', 'alice', nudge('bob'));
  assert.deepEqual(f.pushes.at(-1), { to: 'bob', kind: 'nudge', data: { name: 'alice', title: '2', uid: 'alice' } });
  // Reading again and missing the next day: at risk with 3; without stats sharing no streak is told.
  await f.call('socialRecordChapter', 'bob', chapter({ generation: 1 }));
  f.advance(day);
  await f.call('socialNudge', 'alice', nudge('bob'));
  assert.deepEqual(f.pushes.at(-1).data, { name: 'alice', title: '3', uid: 'alice' }); // read yesterday again: at risk
  await share(f, { stats: 'private' }, 'bob');
  f.advance(day);
  await f.call('socialNudge', 'alice', nudge('bob'));
  assert.deepEqual(f.pushes.at(-1).data, { name: 'alice', uid: 'alice' });
  // Blocks, either way.
  f.advance(day);
  await f.call('socialFriendAction', 'bob', { operationId: op(), action: 'block', otherUid: 'alice' });
  await rejects(f.call('socialNudge', 'alice', nudge('bob')), 'permission-denied');
  await rejects(f.call('socialNudge', 'bob', nudge('alice')), 'permission-denied');
});
test('nudges: replays send one push and at most 20 people a day', async () => {
  const f = await fixture(), day = 86_400_000;
  const replay = nudge('bob');
  await befriend(f);
  await f.call('socialNudge', 'alice', replay); await f.call('socialNudge', 'alice', replay);
  assert.equal(f.pushes.filter((p) => p.kind === 'nudge').length, 1);
  for (let i = 0; i < 19; i++) {
    const other = `friend${i}`;
    await f.call('socialUpdateProfile', other, { operationId: op(), handle: other, displayName: other });
    await befriend(f, 'alice', other);
    await f.call('socialNudge', 'alice', nudge(other));
  }
  await f.call('socialUpdateProfile', 'last', { operationId: op(), handle: 'last', displayName: 'last' });
  await befriend(f, 'alice', 'last');
  await rejects(f.call('socialNudge', 'alice', nudge('last')), 'resource-exhausted');
  f.advance(day);
  assert.deepEqual(await f.call('socialNudge', 'alice', nudge('last')), { nudged: true });
});

const invited = (otherUid) => ({ operationId: op(), action: 'request', otherUid, via: 'invite' });
const readChapters = async (f, actor, n) => {
  for (let i = 0; i < n; i++) { f.advance(10_000); await f.call('socialRecordChapter', actor, chapter({ generation: 1 })); }
};
test('invite requests are marked, filed once on accept and validated', async () => {
  const f = await fixture();
  await rejects(f.call('socialFriendAction', 'bob', { ...invited('alice'), via: 'link' }), 'invalid-argument');
  await rejects(f.call('socialFriendAction', 'alice', { operationId: op(), action: 'accept', otherUid: 'bob', via: 'invite' }), 'invalid-argument');
  await f.call('socialFriendAction', 'bob', invited('alice'));
  assert.equal(f.store.docs.get(`friendRequests/${pairId('alice', 'bob')}`).via, 'invite');
  await f.call('socialFriendAction', 'alice', { operationId: op(), action: 'accept', otherUid: 'bob' });
  const invite = f.store.docs.get('invites/bob');
  assert.equal(invite.inviterUid, 'alice'); assert.equal(invite.state, 'pending'); assert.equal(invite.baseChapters, 0);
  // A second inviter never replaces the first; a plain request (older clients) carries no flag.
  await f.call('socialFriendAction', 'bob', invited('charlie'));
  await f.call('socialFriendAction', 'charlie', { operationId: op(), action: 'accept', otherUid: 'bob' });
  assert.equal(f.store.docs.get('invites/bob').inviterUid, 'alice');
  await f.call('socialFriendAction', 'alice', { operationId: op(), action: 'request', otherUid: 'charlie', via: null });
  assert.equal(f.store.docs.get(`friendRequests/${pairId('alice', 'charlie')}`).via, undefined);
  // Friends before: removing and re-adding through an invite files nothing.
  await f.call('socialUpdateProfile', 'dave', { operationId: op(), handle: 'dave', displayName: 'dave' });
  await befriend(f, 'dave', 'charlie');
  await f.call('socialFriendAction', 'dave', { operationId: op(), action: 'remove', otherUid: 'charlie' });
  await f.call('socialFriendAction', 'dave', { operationId: op(), action: 'request', otherUid: 'charlie' });
  await f.call('socialFriendAction', 'charlie', { operationId: op(), action: 'reject', otherUid: 'dave' });
  await f.call('socialFriendAction', 'dave', invited('charlie'));
  assert.equal(f.store.docs.get(`friendRequests/${pairId('charlie', 'dave')}`).via, undefined);
  await f.call('socialFriendAction', 'charlie', { operationId: op(), action: 'accept', otherUid: 'dave' });
  assert.equal(f.store.docs.get('invites/dave'), undefined);
});
test('the inviter is credited once, after the newcomer read five chapters', async () => {
  const f = await fixture();
  await readChapters(f, 'bob', 2); // read before the invite: not counted
  await f.call('socialFriendAction', 'bob', invited('alice'));
  await f.call('socialFriendAction', 'alice', { operationId: op(), action: 'accept', otherUid: 'bob' });
  assert.equal(f.store.docs.get('invites/bob').baseChapters, 2);
  assert.equal(f.store.docs.get('xp/bob').invitePending, true);
  await readChapters(f, 'bob', RECRUIT.chapters - 1);
  assert.equal(f.store.docs.get('xp/alice'), undefined);
  assert.equal(f.store.docs.get('xp/bob').invitePending, true);
  await readChapters(f, 'bob', 1);
  let alice = f.store.docs.get('xp/alice');
  assert.equal(alice.recruits, 1); assert.equal(alice.total, RECRUIT.xp); assert.ok(alice.badges.recruit_1);
  assert.equal(f.store.docs.get('invites/bob').state, 'credited');
  assert.equal(f.store.docs.get('xp/bob').invitePending, undefined);
  // No double credit, and the inviter's own reading keeps the counters (and isn't slowed down by the credit).
  await readChapters(f, 'bob', 6);
  await f.call('socialRecordChapter', 'alice', chapter({ generation: 1 }));
  alice = f.store.docs.get('xp/alice');
  assert.equal(alice.recruits, 1); assert.equal(alice.total, RECRUIT.xp + XP.chapter); assert.ok(alice.badges.recruit_1);
  // An invite whose friendship ended before the fifth chapter is dropped.
  await f.call('socialFriendAction', 'charlie', invited('alice'));
  await f.call('socialFriendAction', 'alice', { operationId: op(), action: 'accept', otherUid: 'charlie' });
  await f.call('socialFriendAction', 'alice', { operationId: op(), action: 'remove', otherUid: 'charlie' });
  await readChapters(f, 'charlie', RECRUIT.chapters);
  assert.equal(f.store.docs.get('invites/charlie').state, 'dropped');
  assert.equal(f.store.docs.get('xp/alice').recruits, 1);
});
test('recruit credits stop at the monthly cap and unlock the recruiter badges', async () => {
  const f = await fixture();
  const newcomers = Array.from({ length: RECRUIT.monthlyCap + 1 }, (_, i) => `reader${i}`);
  for (const name of newcomers) {
    await f.call('socialUpdateProfile', name, { operationId: op(), handle: name, displayName: name });
    await f.call('socialFriendAction', name, invited('alice'));
    await f.call('socialFriendAction', 'alice', { operationId: op(), action: 'accept', otherUid: name });
    await readChapters(f, name, RECRUIT.chapters);
    if (name === 'reader2') assert.ok(f.store.docs.get('xp/alice').badges.recruit_3);
  }
  let alice = f.store.docs.get('xp/alice');
  assert.equal(alice.recruits, RECRUIT.monthlyCap); assert.equal(alice.total, RECRUIT.monthlyCap * RECRUIT.xp);
  assert.ok(alice.badges.recruit_1 && alice.badges.recruit_3 && alice.badges.recruit_10);
  const last = newcomers.at(-1);
  assert.equal(f.store.docs.get(`invites/${last}`).state, 'pending');
  assert.equal(f.store.docs.get(`xp/${last}`).invitePending, true);
  // Next month the pending credit goes through on the newcomer's next chapter.
  f.advance(32 * 86_400_000);
  await readChapters(f, last, 1);
  alice = f.store.docs.get('xp/alice');
  assert.equal(alice.recruits, RECRUIT.monthlyCap + 1); assert.equal(alice.recruitMonthCount, 1);
  assert.equal(f.store.docs.get(`invites/${last}`).state, 'credited');
});

// Open rooms + room positions (2026-10-06)
const page = (pageIndex = 0, chapter = 'chapter1') => ({ chapterKey: digest(chapter), pageIndex });
async function openRoom(f, extra = {}) {
  const created = await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: content(), position: position(), open: true, ...extra }));
  await f.call('socialRoomAction', 'bob', roomInput('join', { roomId: created.roomId }));
  return created.roomId;
}
test('open rooms: off by default; the host opens one and friends see a minimal card', async () => {
  const f = await fixture(); await group(f, ['bob', 'charlie']);
  const closed = await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: content() }));
  assert.equal(closed.room.open, false); assert.equal(f.store.docs.has('openRooms/alice'), false);
  await rejects(f.call('socialRoomAction', 'charlie', roomInput('join', { roomId: closed.roomId })), 'permission-denied');
  // Only the host opens it, only with create/open.
  await rejects(f.call('socialRoomAction', 'bob', roomInput('open', { roomId: closed.roomId, open: true })), 'permission-denied');
  await rejects(f.call('socialRoomAction', 'alice', roomInput('heartbeat', { roomId: closed.roomId, sessionId: op(), open: true })), 'invalid-argument');
  await rejects(f.call('socialRoomAction', 'alice', roomInput('open', { roomId: closed.roomId })), 'invalid-argument');
  await rejects(f.call('socialRoomAction', 'alice', roomInput('open', { roomId: closed.roomId, open: 'yes' })), 'invalid-argument');
  const opened = await f.call('socialRoomAction', 'alice', roomInput('open', { roomId: closed.roomId, open: true }));
  assert.equal(opened.room.open, true);
  const card = f.store.docs.get('openRooms/alice');
  assert.deepEqual(Object.keys(card).sort(), ['generation', 'hostUid', 'leaseExpiresAt', 'maxMembers', 'memberCount', 'roomId', 'schemaVersion', 'title', 'updatedAt']);
  assert.equal(card.roomId, closed.roomId); assert.equal(card.title, 'Title 1'); assert.equal(card.memberCount, 1);
  assert.equal(card.generation, f.store.docs.get('sharingSettings/alice').generation);
  await f.call('socialRoomAction', 'alice', roomInput('open', { roomId: closed.roomId, open: false }));
  assert.equal(f.store.docs.has('openRooms/alice'), false);
});
test('open rooms need the host activity shared and visible', async () => {
  const f = await fixture(); await befriend(f);
  await share(f, { library: 'friends', activity: 'private' });
  await rejects(f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: content(), open: true })), 'failed-precondition');
  await share(f, { activity: 'friends', invisible: true }, 'alice', 2);
  await rejects(f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: content(), open: true })), 'failed-precondition');
});
test('open rooms: friends of the host drop in; non-friends, blocked, full and closed rooms refuse', async () => {
  const f = await fixture();
  await group(f, ['bob', 'charlie', 'dave', 'erin', 'frank', 'gina']);
  await f.call('socialUpdateProfile', 'mallory', { operationId: op(), handle: 'mallory', displayName: 'mallory' });
  const roomId = await openRoom(f);
  assert.equal(f.store.docs.get('openRooms/alice').memberCount, 2);
  // Not the host's friend.
  await rejects(f.call('socialRoomAction', 'mallory', roomInput('join', { roomId })), 'permission-denied');
  // Blocked by a member (not the host): refused like any unavailable room.
  await f.call('socialFriendAction', 'bob', { operationId: op(), action: 'block', otherUid: 'charlie' });
  await rejects(f.call('socialRoomAction', 'charlie', roomInput('join', { roomId })), 'permission-denied');
  // A friend drops in: joined at once, in the participants, the chat says so, the card counts them.
  const joined = await f.call('socialRoomAction', 'dave', roomInput('join', { roomId }));
  assert.ok(joined.room.participants.includes('dave')); assert.equal(joined.room.members.dave.state, 'joined');
  assert.equal(f.store.docs.get(`roomInvites/dave/items/${roomId}`).state, 'active');
  assert.equal(f.store.docs.get('openRooms/alice').memberCount, 3);
  const events = [...f.store.docs.entries()].filter(([p]) => p.startsWith(`rooms/${roomId}/messages/`)).map(([, m]) => `${m.kind}:${m.senderUid}`);
  assert.ok(events.includes('joined:dave'));
  for (const name of ['erin', 'frank']) await f.call('socialRoomAction', name, roomInput('join', { roomId }));
  // Six people would fit; charlie's block keeps them out, so gina is the 6th.
  await f.call('socialRoomAction', 'gina', roomInput('join', { roomId }));
  await f.call('socialUpdateProfile', 'hank', { operationId: op(), handle: 'hank', displayName: 'hank' });
  await befriend(f, 'alice', 'hank');
  await rejects(f.call('socialRoomAction', 'hank', roomInput('join', { roomId })), 'resource-exhausted');
  await f.call('socialRoomAction', 'frank', roomInput('leave', { roomId }));
  assert.equal(f.store.docs.get('openRooms/alice').memberCount, 5);
  // Closed: the card goes, and nobody drops in any more.
  await f.call('socialRoomAction', 'alice', roomInput('close', { roomId }));
  assert.equal(f.store.docs.has('openRooms/alice'), false);
  assert.equal(f.store.docs.get(`rooms/${roomId}`).open, false);
  await rejects(f.call('socialRoomAction', 'hank', roomInput('join', { roomId })), 'not-found');
});
test('open rooms close to drop-ins on any host privacy change and on a host hand-off', async () => {
  const f = await fixture(); await group(f, ['bob', 'charlie', 'dave']); await befriend(f, 'bob', 'charlie');
  const roomId = await openRoom(f);
  // A privacy change (the app bumps the generation on incognito too) closes it; the room goes on.
  await share(f, { activity: 'friends', library: 'friends' });
  assert.equal(f.store.docs.has('openRooms/alice'), false);
  assert.equal(f.store.docs.get(`rooms/${roomId}`).open, false); assert.equal(f.store.docs.get(`rooms/${roomId}`).state, 'active');
  await rejects(f.call('socialRoomAction', 'charlie', roomInput('join', { roomId })), 'permission-denied');
  // Reopened, then a stale card (older generation) refuses drop-ins too.
  await f.call('socialRoomAction', 'alice', roomInput('open', { roomId, open: true }));
  const card = f.store.docs.get('openRooms/alice');
  f.store.docs.set('openRooms/alice', { ...card, generation: card.generation - 1 });
  await rejects(f.call('socialRoomAction', 'charlie', roomInput('join', { roomId })), 'permission-denied');
  f.store.docs.set('openRooms/alice', card);
  await f.call('socialRoomAction', 'charlie', roomInput('join', { roomId }));
  // The host leaves: bob takes over, the room is no longer open and alice's card is gone.
  const handed = await f.call('socialRoomAction', 'alice', roomInput('leave', { roomId }));
  assert.equal(handed.room.hostUid, 'bob'); assert.equal(handed.room.open, false);
  assert.equal(f.store.docs.has('openRooms/alice'), false); assert.equal(f.store.docs.has('openRooms/bob'), false);
  await rejects(f.call('socialRoomAction', 'dave', roomInput('join', { roomId })), 'permission-denied');
});
test('open room cards follow the lease with heartbeats and go with expiry cleanup', async () => {
  const f = await fixture(); await befriend(f);
  const sessionId = op();
  const created = await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId, content: content(), open: true }));
  f.advance(30_000);
  await f.call('socialRoomAction', 'alice', roomInput('heartbeat', { roomId: created.roomId, sessionId }));
  assert.equal(f.store.docs.get('openRooms/alice').leaseExpiresAt, f.now() + 90_000);
  f.advance(90_001);
  assert.equal((await f.service.expireRoom(created.roomId)).expired, true);
  assert.equal(f.store.docs.has('openRooms/alice'), false);
});
test('room positions: joined members share chapter and page, rate limited, validated, cleared', async () => {
  const f = await fixture(); await group(f, ['bob', 'charlie']);
  const created = await f.call('socialRoomAction', 'alice', roomInput('create', { otherUid: 'bob', sessionId: op(), content: content(), position: position() }));
  const roomId = created.roomId;
  await f.call('socialRoomAction', 'alice', roomInput('invite', { roomId, otherUid: 'charlie' }));
  // Invited but not joined, and outsiders: refused.
  await rejects(f.call('socialRoomAction', 'bob', roomInput('position', { roomId, position: page(3) })), 'permission-denied');
  await f.call('socialRoomAction', 'bob', roomInput('join', { roomId }));
  await rejects(f.call('socialRoomAction', 'charlie', roomInput('position', { roomId, position: page(3) })), 'permission-denied');
  await f.call('socialUpdateProfile', 'mallory', { operationId: op(), handle: 'mallory', displayName: 'mallory' });
  await rejects(f.call('socialRoomAction', 'mallory', roomInput('position', { roomId, position: page(3) })), 'permission-denied');
  // Only chapter key + page index.
  await rejects(f.call('socialRoomAction', 'bob', roomInput('position', { roomId, position: position(3) })), 'invalid-argument');
  await rejects(f.call('socialRoomAction', 'bob', roomInput('position', { roomId, position: { chapterKey: 'chapter1', pageIndex: 3 } })), 'invalid-argument');
  await rejects(f.call('socialRoomAction', 'bob', roomInput('position', { roomId, position: { ...page(3), pageIndex: -1 } })), 'invalid-argument');
  await rejects(f.call('socialRoomAction', 'bob', roomInput('position', { roomId, position: { ...page(3), pageIndex: 2000 } })), 'invalid-argument');
  await rejects(f.call('socialRoomAction', 'bob', roomInput('position', { roomId, position: { ...page(3), label: 'x' } })), 'invalid-argument');
  const before = f.store.docs.get(`rooms/${roomId}`);
  const result = await f.call('socialRoomAction', 'bob', roomInput('position', { roomId, position: page(3) }));
  assert.deepEqual(result.room, { hostUid: 'alice', state: 'active' });
  const after = f.store.docs.get(`rooms/${roomId}`);
  assert.deepEqual(after.members.bob.page, { ...page(3), at: f.now() });
  // Host position, sequence and updatedAt are untouched.
  assert.deepEqual(after.position, before.position); assert.equal(after.sequence, before.sequence); assert.equal(after.updatedAt, before.updatedAt);
  // At most once every 3 s; clearing is always allowed.
  f.advance(2_999);
  await rejects(f.call('socialRoomAction', 'bob', roomInput('position', { roomId, position: page(4) })), 'resource-exhausted');
  f.advance(1);
  await f.call('socialRoomAction', 'bob', roomInput('position', { roomId, position: page(4, 'chapter2') }));
  assert.equal(f.store.docs.get(`rooms/${roomId}`).members.bob.page.chapterKey, digest('chapter2'));
  await f.call('socialRoomAction', 'bob', roomInput('position', { roomId, position: null }));
  assert.equal(f.store.docs.get(`rooms/${roomId}`).members.bob.page, null);
  // Counted apart from other room actions.
  assert.equal(f.store.docs.get('quotas/bob').counts.roomPosition, 3);
  // A member who lost the host's friendship can't publish.
  await f.call('socialRoomAction', 'charlie', roomInput('join', { roomId }));
  await f.call('socialFriendAction', 'alice', { operationId: op(), action: 'remove', otherUid: 'charlie' });
  await rejects(f.call('socialRoomAction', 'charlie', roomInput('position', { roomId, position: page(1) })), 'permission-denied');
});
test('room positions stop with the lease and at the daily cap', async () => {
  const f = await fixture(); await befriend(f);
  const roomId = await openRoom(f, { open: false });
  f.advance(90_001);
  await rejects(f.call('socialRoomAction', 'bob', roomInput('position', { roomId, position: page(1) })), 'failed-precondition');
  const f2 = await fixture(); await befriend(f2);
  const live = await openRoom(f2, { open: false });
  const day = Math.floor(f2.now() / 86_400_000);
  f2.store.docs.set('quotas/bob', { schemaVersion: 1, day, counts: { roomPosition: 6_000 }, total: 6_000, updatedAt: f2.now() });
  await rejects(f2.call('socialRoomAction', 'bob', roomInput('position', { roomId: live, position: page(1) })), 'resource-exhausted');
});

test('double XP on Fridays and Saturdays in Israel time and during the 1.0 launch week, with a doubled daily cap', async () => {
  const at = (iso) => Date.parse(iso);
  assert.equal(DOUBLE_XP.timeZone, 'Asia/Jerusalem'); assert.deepEqual([...DOUBLE_XP.weekdays], ['Fri', 'Sat']);
  assert.equal(xpMultiplier(at('2030-03-14T22:30:00Z')), 2); // Friday 00:30 in Israel, UTC+2 in March (still Thursday in UTC)
  assert.equal(xpMultiplier(at('2030-03-16T22:30:00Z')), 1); // Sunday 00:30 in Israel (still Saturday in UTC)
  assert.equal(xpMultiplier(at('2030-03-16T20:00:00Z')), 2); // Saturday 22:00 in Israel
  assert.equal(xpMultiplier(at('2030-03-18T12:00:00Z')), 1); // Monday
  assert.equal(xpMultiplier(at('2026-10-06T21:00:00Z')), 2); // launch week: 7 Oct 00:00 Israel (a Wednesday)
  assert.equal(xpMultiplier(at('2026-10-06T20:59:00Z')), 1); // just before
  assert.equal(xpMultiplier(at('2026-10-13T20:59:00Z')), 2); // 13 Oct 23:59 Israel (a Tuesday)
  assert.equal(xpMultiplier(at('2026-10-13T21:00:00Z')), 1); // over
  // The fixture's clock starts on a Sunday afternoon; five days later is a Friday.
  const f = await fixture();
  assert.equal(xpMultiplier(f.now()), 1);
  assert.equal((await f.call('socialRecordChapter', 'alice', chapter({ generation: 1 }))).xp.gained, XP.chapter);
  f.advance(5 * 86_400_000);
  assert.equal(xpMultiplier(f.now()), 2);
  const friday = await f.call('socialRecordChapter', 'alice', chapter({ generation: 1 }));
  assert.equal(friday.xp.gained, XP.chapter * 2); // no streak bonus: the streak broke
  // The daily cap doubles too.
  for (let i = 0; i < 250; i++) { f.advance(10_000); await f.call('socialRecordChapter', 'alice', chapter({ generation: 1 })); }
  assert.equal(f.store.docs.get('xp/alice').today, XP.dailyCap * 2);
});