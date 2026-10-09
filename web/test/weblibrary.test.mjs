import { test } from "node:test";
import assert from "node:assert/strict";
import { build } from "esbuild";

// Bundle the TypeScript modules on the fly (like logic.test.mjs): the protocol client and the dev fake phone.
async function load(entry) {
  const bundled = await build({ entryPoints: [entry], bundle: true, format: "esm", write: false, platform: "neutral" });
  return import(`data:text/javascript;base64,${Buffer.from(bundled.outputFiles[0].text).toString("base64")}`);
}
const p = await load("src/weblibrary/protocol.ts");
const { FakePhone } = await load("src/weblibrary/fake-phone.ts");
const logic = await load("src/logic.ts");

/** Manual timers: tests decide when time passes. */
function fakeTimers() {
  let now = 0, next = 1;
  const pending = new Map();
  return {
    now: () => now,
    setTimeout: (fn, ms) => { const id = next++; pending.set(id, { at: now + ms, fn }); return id; },
    clearTimeout: (id) => pending.delete(id),
    advance(ms) {
      now += ms;
      for (const [id, t] of [...pending].sort((a, b) => a[1].at - b[1].at)) if (t.at <= now && pending.has(id)) { pending.delete(id); t.fn(); }
    },
    get count() { return pending.size; },
  };
}
/** A client whose sent messages are recorded; `reply` answers like the phone. */
function harness(options = {}) {
  const sent = [];
  const timers = fakeTimers();
  const client = new p.ProtocolClient((text) => sent.push(JSON.parse(text)), { timers, ...options });
  return { client, sent, timers, reply: (id, body) => client.receive(JSON.stringify({ id, ...body })) };
}
const bytes = (n, seed = 1) => Uint8Array.from({ length: n }, (_, i) => (i * 31 + seed) & 255);
const settle = () => new Promise((resolve) => setImmediate(resolve));

// ---------- framing ----------

test("chunk frames carry a big-endian id and index in an 8-byte header", () => {
  const frame = p.encodeChunk(0x01020304, 0x0a0b0c0d, Uint8Array.of(9, 8, 7));
  assert.deepEqual([...frame.subarray(0, 8)], [1, 2, 3, 4, 10, 11, 12, 13]);
  const decoded = p.decodeChunk(frame.buffer);
  assert.equal(decoded.id, 0x01020304);
  assert.equal(decoded.index, 0x0a0b0c0d);
  assert.deepEqual([...decoded.payload], [9, 8, 7]);
  // Ids above 2^31 stay positive (uint32).
  assert.equal(p.decodeChunk(p.encodeChunk(0xfffffffe, 0, new Uint8Array())).id, 0xfffffffe);
});

test("frames are at most 16 384 bytes and a short frame is rejected", () => {
  const frames = p.encodeChunks(7, bytes(40_000));
  assert.equal(frames.length, 3);
  assert.ok(frames.every((f) => f.byteLength <= 16_384));
  assert.equal(frames[0].byteLength, 16_384);
  assert.throws(() => p.encodeChunk(1, 0, new Uint8Array(16_377)), RangeError);
  assert.throws(() => p.decodeChunk(new Uint8Array(5)), (e) => e.code === "bad_response");
});

test("reassembly joins chunks in any order and checks count and size", () => {
  const data = bytes(40_000);
  const frames = p.encodeChunks(3, data).map((f) => p.decodeChunk(f));
  const r = new p.Reassembly(3, 40_000);
  assert.equal(r.add(2, frames[2].payload), false);
  assert.equal(r.add(0, frames[0].payload), false);
  assert.equal(r.add(1, frames[1].payload), true);
  assert.deepEqual(r.join(), data);
  assert.throws(() => r.add(1, frames[1].payload), /twice/);
  assert.throws(() => new p.Reassembly(1, 10).add(1, new Uint8Array(1)), /Chunk 1 of 1/);
  const wrongSize = new p.Reassembly(1, 10);
  wrongSize.add(0, new Uint8Array(9));
  assert.throws(() => wrongSize.join(), (e) => e.code === "bad_response");
});

// ---------- requests ----------

test("requests get increasing ids and responses are matched by id, in any order", async () => {
  const { client, sent, reply } = harness();
  const a = client.request("hello", { protocol: 1, client: "web" });
  const b = client.request("library.list");
  assert.deepEqual(sent, [{ id: 1, method: "hello", params: { protocol: 1, client: "web" } }, { id: 2, method: "library.list", params: {} }]);
  reply(2, { result: { manga: [] } });
  reply(1, { result: { protocol: 1 } });
  assert.deepEqual(await b, { manga: [] });
  assert.deepEqual(await a, { protocol: 1 });
  // Unknown or repeated ids are ignored.
  reply(1, { result: "again" });
  reply(99, { result: "nobody" });
  client.receive("not json");
  assert.equal(client.pendingCount, 0);
});

test("phone errors pass through with their code and message", async () => {
  const { client, reply } = harness();
  const request = client.request("manga.details", { mangaId: "123" });
  reply(1, { error: { code: "not_found", message: "Manga 123 isn't in the library" } });
  await assert.rejects(request, (e) => e instanceof p.ProtocolError && e.code === "not_found" && e.message === "Manga 123 isn't in the library");
  const binary = client.requestBinary("page.image", { chapterId: "1", index: 0 });
  reply(2, { error: { code: "source_error", message: "HTTP 503" } });
  await assert.rejects(binary, (e) => e.code === "source_error" && e.message === "HTTP 503");
  assert.match(p.requestErrorText({ code: "source_error", message: "HTTP 503" }), /HTTP 503/);
});

test("requests time out after 20 s, page images after 60 s, and the slot is freed", async () => {
  const { client, sent, timers, reply } = harness();
  const details = client.request("manga.details", { mangaId: "1" });
  const page = client.requestBinary("page.image", { chapterId: "1", index: 0 });
  let detailsError, pageError;
  details.catch((e) => { detailsError = e; });
  page.catch((e) => { pageError = e; });
  timers.advance(19_999);
  await settle();
  assert.equal(detailsError, undefined);
  timers.advance(1);
  await settle();
  assert.equal(detailsError?.code, "timeout");
  assert.equal(pageError, undefined);
  timers.advance(40_000);
  await settle();
  assert.equal(pageError?.code, "timeout");
  assert.equal(client.pendingCount, 0);
  // A late answer is ignored; new requests still work.
  reply(1, { result: {} });
  const next = client.request("library.list");
  reply(sent.at(-1).id, { result: { ok: 1 } });
  assert.deepEqual(await next, { ok: 1 });
});

test("at most 4 requests are in flight; the rest wait in order", async () => {
  const { client, sent, reply } = harness();
  const results = Array.from({ length: 7 }, (_, i) => client.request("manga.details", { mangaId: String(i) }));
  assert.equal(sent.length, 4);
  assert.equal(client.pendingCount, 4);
  assert.equal(client.queuedCount, 3);
  reply(3, { result: "three" });
  assert.equal(sent.length, 5);
  assert.deepEqual(sent[4], { id: 5, method: "manga.details", params: { mangaId: "4" } });
  reply(1, { error: { code: "busy", message: "" } });
  reply(2, { result: "two" });
  assert.equal(sent.length, 7);
  assert.equal(client.pendingCount, 4);
  for (const id of [4, 5, 6, 7]) reply(id, { result: id });
  assert.deepEqual(await Promise.allSettled(results).then((r) => r.map((x) => x.value ?? x.reason.code)), ["busy", "two", "three", 4, 5, 6, 7]);
});

test("cancelling a queued request drops it; a sent one still settles", async () => {
  const { client, sent, reply } = harness({ maxInFlight: 1 });
  const first = new AbortController(), second = new AbortController();
  const a = client.request("manga.details", { mangaId: "a" }, { signal: first.signal });
  const b = client.request("manga.details", { mangaId: "b" }, { signal: second.signal });
  const c = client.request("manga.details", { mangaId: "c" });
  second.abort();
  await assert.rejects(b, (e) => e.code === "aborted");
  first.abort(); // already sent: no effect
  reply(1, { result: "a" });
  assert.equal(await a, "a");
  assert.deepEqual(sent.map((m) => m.params.mangaId), ["a", "c"]);
  reply(2, { result: "c" });
  assert.equal(await c, "c");
  await assert.rejects(client.request("x", {}, { signal: AbortSignal.abort() }), (e) => e.code === "aborted");
});

test("binary results: header then chunks, two responses interleaved", async () => {
  const { client, reply } = harness();
  const coverBytes = bytes(30_000, 5), pageBytes = bytes(50_000, 9);
  const cover = client.requestBinary("manga.cover", { mangaId: "1", maxWidth: 300 });
  const page = client.requestBinary("page.image", { chapterId: "2", index: 0 });
  const coverFrames = p.encodeChunks(1, coverBytes), pageFrames = p.encodeChunks(2, pageBytes);
  reply(1, { result: { contentType: "image/jpeg", size: coverBytes.byteLength, chunks: coverFrames.length } });
  reply(2, { result: { contentType: "image/webp", size: pageBytes.byteLength, chunks: pageFrames.length } });
  // Interleaved: page, cover, page, cover, page, page. ArrayBuffers like the data channel delivers.
  const order = [pageFrames[0], coverFrames[0], pageFrames[1], coverFrames[1], pageFrames[2], pageFrames[3]];
  for (const frame of order) client.receive(frame.slice().buffer);
  const [c, pg] = await Promise.all([cover, page]);
  assert.equal(c.contentType, "image/jpeg");
  assert.deepEqual(c.bytes, coverBytes);
  assert.equal(pg.contentType, "image/webp");
  assert.deepEqual(pg.bytes, pageBytes);
  assert.equal(client.pendingCount, 0);
});

test("binary results: zero chunks, early chunks, and a size mismatch", async () => {
  const { client, reply } = harness();
  const empty = client.requestBinary("manga.cover", { mangaId: "1" });
  reply(1, { result: { contentType: "image/png", size: 0, chunks: 0 } });
  assert.equal((await empty).bytes.byteLength, 0);
  const early = client.requestBinary("manga.cover", { mangaId: "2" });
  const data = bytes(100);
  client.receive(p.encodeChunks(2, data)[0]);
  reply(2, { result: { contentType: "image/png", size: 100, chunks: 1 } });
  assert.deepEqual((await early).bytes, data);
  const wrong = client.requestBinary("manga.cover", { mangaId: "3" });
  reply(3, { result: { contentType: "image/png", size: 99, chunks: 1 } });
  client.receive(p.encodeChunks(3, data)[0]);
  await assert.rejects(wrong, (e) => e.code === "bad_response");
});

test("closing fails waiting and queued requests, and later ones right away", async () => {
  const { client } = harness({ maxInFlight: 1 });
  const a = client.request("library.list"), b = client.request("library.list");
  client.close();
  await assert.rejects(a, (e) => e.code === "closed");
  await assert.rejects(b, (e) => e.code === "closed");
  await assert.rejects(client.request("library.list"), (e) => e.code === "closed");
  assert.equal(client.closed, true);
});

test("a send that throws fails that request without blocking the queue", async () => {
  let fail = true;
  const sent = [];
  const client = new p.ProtocolClient((text) => { if (fail) { fail = false; throw new Error("channel closed"); } sent.push(JSON.parse(text)); }, { timers: fakeTimers() });
  await assert.rejects(client.request("a"), (e) => e.code === "closed");
  client.request("b");
  assert.equal(sent[0].method, "b");
});

// ---------- against the fake phone ----------

test("the client talks to the fake phone: hello first, list, details, chunked page, progress", async () => {
  let client;
  const phone = new FakePhone((text) => client.receive(text), (frame) => client.receive(frame.slice().buffer), {
    image: () => ({ contentType: "image/jpeg", bytes: bytes(70_000, 3) }),
  });
  client = new p.ProtocolClient((text) => phone.handle(text));
  await assert.rejects(client.request("library.list"), (e) => e.code === "bad_request");
  const hello = await client.request("hello", { protocol: 1, client: "web" });
  assert.equal(hello.protocol, 1);
  const list = await client.request("library.list");
  assert.ok(list.manga.length > 0 && list.manga.every((m) => typeof m.mangaId === "string"));
  const details = await client.request("manga.details", { mangaId: list.manga[0].mangaId });
  const chapter = details.chapters[0];
  const pages = await client.request("chapter.pages", { chapterId: chapter.chapterId });
  const images = await Promise.all(Array.from({ length: 6 }, (_, index) => client.requestBinary("page.image", { chapterId: chapter.chapterId, index })));
  assert.ok(images.every((img) => img.bytes.byteLength === 70_000));
  assert.deepEqual(await client.request("chapter.progress", { chapterId: chapter.chapterId, lastPageRead: pages.pageCount - 1, read: true }), { ok: true });
  assert.deepEqual(phone.progress.at(-1), { chapterId: chapter.chapterId, lastPageRead: pages.pageCount - 1, read: true });
  await assert.rejects(client.request("manga.details", { mangaId: "999" }), (e) => e.code === "not_found");
  let other;
  const newer = new FakePhone((text) => other.receive(text), () => undefined);
  other = new p.ProtocolClient((text) => newer.handle(text));
  await assert.rejects(other.request("hello", { protocol: 2, client: "web" }), (e) => e.code === "unsupported_protocol");
});

// ---------- page helpers ----------

test("routes: list, title and reader; anything else is not My library", () => {
  assert.deepEqual(p.parseLibraryRoute("/my-library"), { view: "list" });
  assert.deepEqual(p.parseLibraryRoute("/my-library/"), { view: "list" });
  assert.deepEqual(p.parseLibraryRoute("/my-library/123"), { view: "manga", mangaId: "123" });
  assert.deepEqual(p.parseLibraryRoute("/my-library/123/9876"), { view: "reader", mangaId: "123", chapterId: "9876" });
  assert.equal(p.parseLibraryRoute("/my-library/1/2/3"), null);
  assert.equal(p.parseLibraryRoute("/my-library/<x>"), null);
  assert.equal(p.parseLibraryRoute("/library"), null);
  for (const path of ["/my-library", "/my-library/123", "/my-library/123/9876"]) assert.ok(logic.knownRoute(path), path);
  assert.equal(logic.knownRoute("/my-library/1/2/3"), false);
});

test("error texts from the protocol doc, and which side to update", () => {
  assert.equal(p.problemText("no_answer"), "Your phone didn't answer. Open Mihon Together on it and try again.");
  assert.equal(p.problemText("no_connect"), "Couldn't connect. Make sure your phone and computer are on the same Wi-Fi. If the phone uses AdGuard or a VPN, exclude Mihon Together in it.");
  assert.equal(p.protocolMismatch(1), null);
  assert.equal(p.protocolMismatch(2), "phone_newer");
  assert.equal(p.protocolMismatch(0), "phone_older");
  assert.match(p.problemText("phone_newer"), /Reload this page/);
  assert.match(p.problemText("phone_older"), /Update Mihon Together on your phone/);
});

test("image blobs only get raster image types", () => {
  assert.equal(p.safeImageType("image/webp"), "image/webp");
  assert.equal(p.safeImageType("IMAGE/PNG; charset=x"), "image/png");
  assert.equal(p.safeImageType("text/html"), "image/jpeg");
  assert.equal(p.safeImageType("image/svg+xml"), "image/jpeg");
});

test("library: category tabs like the app, filter by category and search, sorted by title", () => {
  const list = {
    categories: [{ categoryId: "3", name: "Reading", order: 1 }, { categoryId: "0", name: "Default", order: 0 }, { categoryId: "5", name: "Later", order: 2 }],
    manga: [
      { mangaId: "1", title: "frieren", author: "Yamada", categoryIds: ["3"], coverVersion: "1" },
      { mangaId: "2", title: "Blue Period", author: null, categoryIds: [], coverVersion: "1" },
      { mangaId: "3", title: "Akira", author: "Otomo", categoryIds: ["3", "5"], coverVersion: "2" },
    ],
  };
  assert.deepEqual(p.visibleCategories(list).map((c) => c.categoryId), ["0", "3", "5"]);
  assert.deepEqual(p.visibleCategories({ ...list, manga: list.manga.filter((m) => m.categoryIds.length) }).map((c) => c.categoryId), ["3", "5"]);
  assert.deepEqual(p.filterLibrary(list, null, "").map((m) => m.mangaId), ["3", "2", "1"]);
  assert.deepEqual(p.filterLibrary(list, "0", "").map((m) => m.mangaId), ["2"]);
  assert.deepEqual(p.filterLibrary(list, "3", "").map((m) => m.mangaId), ["3", "1"]);
  assert.deepEqual(p.filterLibrary(list, null, "yama").map((m) => m.mangaId), ["1"]);
  assert.equal(p.coverKey(list.manga[2]), "3:2");
});

test("chapters: continue, next chapter, start page", () => {
  const chapters = [ // newest first, like the phone sends them
    { chapterId: "c3", name: "Chapter 3", number: 3, read: false, lastPageRead: 0 },
    { chapterId: "c2", name: "Chapter 2", number: 2, read: false, lastPageRead: 5 },
    { chapterId: "c1", name: "Chapter 1", number: 1, read: true, lastPageRead: 19 },
  ];
  assert.equal(p.continueChapter(chapters).chapterId, "c2");
  assert.equal(p.continueChapter(chapters.map((c) => ({ ...c, read: true }))), null);
  assert.equal(p.nextChapter(chapters, "c1").chapterId, "c2");
  assert.equal(p.nextChapter(chapters, "c3"), null);
  assert.equal(p.nextChapter(chapters, "missing"), null);
  assert.equal(p.startPage(chapters[1], 20), 5);
  assert.equal(p.startPage(chapters[2], 20), 0);
  assert.equal(p.startPage({ read: false, lastPageRead: 50 }, 20), 19);
  assert.equal(p.chapterLabel({ name: "", number: 12 }), "Chapter 12");
});

test("reader: start layout, keys and taps (RTL mirrored), prefetch and kept pages", () => {
  assert.deepEqual(p.initialLayout("rtl"), { mode: "paged", rtl: true });
  assert.deepEqual(p.initialLayout("ltr"), { mode: "paged", rtl: false });
  assert.deepEqual(p.initialLayout("webtoon"), { mode: "strip", rtl: false });
  assert.deepEqual(p.initialLayout("vertical"), { mode: "strip", rtl: false });
  assert.equal(p.pageStep("ArrowRight", false), 1);
  assert.equal(p.pageStep("ArrowRight", true), -1);
  assert.equal(p.pageStep("ArrowLeft", true), 1);
  assert.equal(p.pageStep("a", false), 0);
  assert.equal(p.tapStep(10, 300, false), -1);
  assert.equal(p.tapStep(290, 300, false), 1);
  assert.equal(p.tapStep(10, 300, true), 1);
  assert.equal(p.tapStep(150, 300, true), 0);
  assert.deepEqual(p.prefetchPages(0, 10), [1, 2, 3]);
  assert.deepEqual(p.prefetchPages(8, 10), [9]);
  assert.deepEqual(p.keepWindow(2, 10), { from: 0, to: 8 });
  assert.deepEqual(p.keepWindow(9, 10), { from: 5, to: 9 });
});

test("progress goes out at most every 5 s, on leave, and as read at the last page", () => {
  const timers = fakeTimers();
  const sent = [];
  const throttle = new p.ProgressThrottle(10, (page, read) => sent.push([timers.now(), page, read]), timers.now, timers);
  throttle.page(0);
  throttle.page(1);
  throttle.page(2);
  assert.deepEqual(sent, [[0, 0, false]]);
  timers.advance(5_000);
  assert.deepEqual(sent, [[0, 0, false], [5_000, 2, false]]);
  throttle.page(3);
  timers.advance(1_000);
  throttle.flush(); // leaving the chapter
  assert.deepEqual(sent.at(-1), [6_000, 3, false]);
  assert.equal(timers.count, 0);
  throttle.page(4);
  throttle.page(9); // last page: right away, as read
  assert.deepEqual(sent.at(-1), [6_000, 9, true]);
  assert.equal(throttle.finished, true);
  throttle.page(5);
  throttle.flush();
  timers.advance(10_000);
  assert.equal(sent.length, 4);
});
