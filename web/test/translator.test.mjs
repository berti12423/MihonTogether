import { test } from "node:test";
import assert from "node:assert/strict";
import { build } from "esbuild";

async function load(entry) {
  const bundled = await build({ entryPoints: [entry], bundle: true, format: "esm", write: false, platform: "neutral" });
  return import(`data:text/javascript;base64,${Buffer.from(bundled.outputFiles[0].text).toString("base64")}`);
}
const t = await load("src/weblibrary/translator.ts");
const logic = await load("src/logic.ts");

const settle = async (n = 5) => { for (let i = 0; i < n; i++) await new Promise((resolve) => setImmediate(resolve)); };

/** Manual timers: tests decide when time passes. */
function fakeTimers() {
  let now = 0, next = 1;
  const pending = new Map();
  return {
    setTimeout: (fn, ms) => { const id = next++; pending.set(id, { at: now + ms, fn }); return id; },
    clearTimeout: (id) => pending.delete(id),
    advance(ms) {
      now += ms;
      for (const [id, x] of [...pending].sort((a, b) => a[1].at - b[1].at)) if (x.at <= now && pending.has(id)) { pending.delete(id); x.fn(); }
    },
    get count() { return pending.size; },
  };
}

const MODELS = { detection: "rfdetr", ocr: "paddleocr-vl-1.6", inpainting: "lama", llm: "gemma4-12b-it" };
const health = (state, extra = {}) => ({
  name: "mihon-together-translator", api: 1, version: "0.1.0", state, progress: null, error: null, device: "cuda",
  models: MODELS, queue: 0, lastPageMs: null, ...extra,
});
const json = (status, body) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const png = (text = "PNG") => new Response(new Blob([text], { type: "image/png" }), { status: 200, headers: { "Content-Type": "image/png" } });
const page = (n = 16, seed = 1) => Uint8Array.from({ length: n }, (_, i) => (i * 31 + seed) & 255);

/** A fetch whose answers the test sets; records calls. */
function fakeFetch(handler) {
  const calls = [];
  const fn = (url, init = {}) => {
    calls.push({ url, init });
    if (init.signal?.aborted) return Promise.reject(new DOMException("aborted", "AbortError"));
    return new Promise((resolve, reject) => {
      init.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
      Promise.resolve(handler(url, init)).then(resolve, reject);
    });
  };
  fn.calls = calls;
  return fn;
}

// ---------- detection ----------

test("health parsing keeps the contract's fields and rejects other programs", () => {
  const parsed = t.parseHealth(health("downloading", { progress: { label: "Downloading gemma4-12b-it", done: 40, total: 100 } }));
  assert.equal(parsed.state, "downloading");
  assert.deepEqual(parsed.models, MODELS);
  assert.equal(t.healthText("downloading", parsed), "Downloading gemma4-12b-it: 40%…");
  assert.equal(t.parseHealth({ ...health("ready"), name: "something-else" }), null);
  assert.equal(t.parseHealth({ ...health("ready"), api: 2 }), null);
  assert.equal(t.parseHealth({ ...health("ready"), state: "weird" }), null);
  assert.equal(t.parseHealth(null), null);
  assert.equal(t.healthText("ready", parsed), "Ready (NVIDIA GPU).");
  assert.equal(t.healthText("absent", null), "Not running on this PC.");
});

test("detection tracks the states and polls every 3 s while not ready", async () => {
  const timers = fakeTimers();
  const answers = [health("starting"), health("downloading", { progress: { label: "x", done: 1, total: 4 } }), health("loading"), health("ready")];
  const fetch = fakeFetch((url, init) => {
    assert.equal(url, "http://127.0.0.1:47823/health");
    assert.equal(init.mode, "cors");
    return json(200, answers.shift());
  });
  const client = new t.TranslatorClient({ fetch, timers });
  const seen = [];
  const stop = client.subscribe(() => seen.push(client.state));
  await settle();
  assert.deepEqual(seen, ["starting"]);
  timers.advance(2999); await settle();
  assert.equal(fetch.calls.length, 1);
  timers.advance(1); await settle();
  timers.advance(3000); await settle();
  timers.advance(3000); await settle();
  assert.deepEqual(seen, ["starting", "downloading", "loading", "ready"]);
  assert.ok(client.ready);
  // Ready: the next check is much later.
  timers.advance(3000); await settle();
  assert.equal(fetch.calls.length, 4);
  stop();
  assert.equal(timers.count, 0, "no polling without listeners");
});

test("nothing answering, a timeout or a wrong answer means absent", async () => {
  const timers = fakeTimers();
  const refused = new t.TranslatorClient({ fetch: fakeFetch(() => { throw new TypeError("Failed to fetch"); }), timers });
  await refused.check();
  assert.equal(refused.state, "absent");

  // Hangs: the short timeout aborts it.
  const hanging = new t.TranslatorClient({ fetch: fakeFetch(() => new Promise(() => undefined)), timers });
  const pending = hanging.check();
  timers.advance(2500);
  assert.equal(await pending, null);
  assert.equal(hanging.state, "absent");

  const other = new t.TranslatorClient({ fetch: fakeFetch(() => json(200, { hello: "world" })), timers });
  await other.check();
  assert.equal(other.state, "absent");

  const error = new t.TranslatorClient({ fetch: fakeFetch(() => json(200, health("error", { error: "CUDA out of memory" }))), timers });
  await error.check();
  assert.equal(error.state, "error");
  assert.equal(t.healthText(error.state, error.health), "Error: CUDA out of memory");
});

// ---------- translate ----------

test("translate posts the page bytes and returns the PNG", async () => {
  const fetch = fakeFetch(async (url, init) => {
    assert.equal(url, "http://127.0.0.1:47823/translate?target=en-US");
    assert.equal(init.method, "POST");
    assert.equal(init.mode, "cors");
    assert.equal(init.headers["Content-Type"], "image/webp");
    assert.deepEqual([...new Uint8Array(await init.body.arrayBuffer())], [...page()]);
    return png("translated");
  });
  const client = new t.TranslatorClient({ fetch, timers: fakeTimers() });
  const blob = await client.translate(page(), "image/webp");
  assert.equal(blob.type, "image/png");
  assert.equal(await blob.text(), "translated");
  // Other targets go in the query.
  const ko = new t.TranslatorClient({ fetch: fakeFetch((url) => { assert.match(url, /target=de-DE$/); return png(); }), timers: fakeTimers() });
  await ko.translate(page(), "image/jpeg", "de-DE");
});

test("translate maps the bridge's errors and a network failure", async () => {
  const cases = [
    [json(400, { error: "bad_image", message: "x" }), "bad_image"],
    [json(413, { error: "too_large", message: "x" }), "too_large"],
    [json(503, { error: "not_ready", message: "x" }), "not_ready"],
    [json(500, { error: "failed", message: "x" }), "failed"],
    [new Response("oops", { status: 503 }), "not_ready"],
    [new Response("oops", { status: 502 }), "failed"],
    [new Response("<html>", { status: 200, headers: { "Content-Type": "text/html" } }), "bad_response"],
  ];
  for (const [response, code] of cases) {
    const client = new t.TranslatorClient({ fetch: fakeFetch(() => response), timers: fakeTimers() });
    await assert.rejects(client.translate(page(), "image/jpeg"), (e) => e instanceof t.TranslatorError && e.code === code, code);
  }
  const client = new t.TranslatorClient({ fetch: fakeFetch(() => { throw new TypeError("Failed to fetch"); }), timers: fakeTimers() });
  client.state = "ready";
  await assert.rejects(client.translate(page(), "image/jpeg"), (e) => e.code === "not_running" && /isn't running/.test(e.message));
  assert.equal(client.state, "absent", "a failed request sends detection back to looking");
  // Too big pages aren't sent.
  const big = new t.TranslatorClient({ fetch: fakeFetch(() => assert.fail("sent")), timers: fakeTimers() });
  await assert.rejects(big.translate(new Uint8Array(t.MAX_PAGE_BYTES + 1), "image/png"), (e) => e.code === "too_large");
});

test("aborting a translation rejects with aborted", async () => {
  const fetch = fakeFetch(() => new Promise(() => undefined));
  const client = new t.TranslatorClient({ fetch, timers: fakeTimers() });
  const controller = new AbortController();
  const pending = client.translate(page(), "image/jpeg", "en-US", controller.signal);
  controller.abort();
  await assert.rejects(pending, (e) => e.code === "aborted");
  assert.notEqual(client.state, "absent");
  await assert.rejects(client.translate(page(), "image/jpeg", "en-US", controller.signal), (e) => e.code === "aborted");
});

// ---------- cache key ----------

test("the cache key covers the page bytes, the target and the model ids", async () => {
  const a = await t.cacheKey(page(), "en-US", MODELS);
  assert.match(a, /^[0-9a-f]{64}\|en-US\|/);
  assert.equal(a, await t.cacheKey(page(), "en-US", { llm: MODELS.llm, ocr: MODELS.ocr, inpainting: MODELS.inpainting, detection: MODELS.detection }), "model order doesn't matter");
  assert.notEqual(a, await t.cacheKey(page(16, 2), "en-US", MODELS));
  assert.notEqual(a, await t.cacheKey(page(), "de-DE", MODELS));
  assert.notEqual(a, await t.cacheKey(page(), "en-US", { ...MODELS, llm: "gemma4-e4b-it" }));
  assert.ok(a.includes("llm=gemma4-12b-it"));
});

// ---------- queue ----------

/** A queue whose translations the test finishes by hand. */
function queueHarness(options = {}) {
  const jobs = [];
  const updates = [];
  const queue = new t.TranslationQueue({
    translate: (bytes, contentType, signal) => new Promise((resolve, reject) => {
      const job = { index: bytes[0], contentType, signal, resolve, reject, aborted: false };
      signal.addEventListener("abort", () => { job.aborted = true; reject(new t.TranslatorError("aborted")); });
      jobs.push(job);
    }),
    onUpdate: (index, status) => updates.push([index, status.state]),
    ...options,
  });
  const add = (...indexes) => indexes.forEach((i) => queue.setPage(i, Uint8Array.of(i, 1, 2), "image/jpeg"));
  return { queue, jobs, updates, add };
}

test("the queue translates the current page first, then the next 2, one at a time", async () => {
  const { queue, jobs, add } = queueHarness({ maxInFlight: 1 });
  queue.focus(0);
  // Page 2 arrives first and starts; pages 3, 1 and 0 arrive while it runs.
  add(2);
  await settle();
  add(3, 1, 0);
  jobs[0].resolve(new Blob(["p2"]));
  await settle();
  assert.deepEqual(jobs.map((j) => j.index), [2, 0], "the current page goes next");
  jobs[1].resolve(new Blob(["p0"])); await settle();
  jobs[2].resolve(new Blob(["p1"])); await settle();
  assert.deepEqual(jobs.map((j) => j.index), [2, 0, 1]);
  assert.equal(jobs.length, 3, "page 3 is outside the window");
  assert.equal(queue.status(0).state, "done");
  assert.equal(await queue.status(1).blob.text(), "p1");
  queue.focus(1);
  await settle();
  assert.deepEqual(jobs.map((j) => j.index), [2, 0, 1, 3], "moving on brings page 3 in");
});

test("the queue keeps at most 2 requests in flight, in window order", async () => {
  const { queue, jobs, add } = queueHarness();
  queue.focus(5);
  add(9, 8, 7, 6, 5);
  await settle();
  assert.deepEqual(jobs.map((j) => j.index), [7, 6], "7 came first, then 6 filled the second slot; 5 waits");
  jobs[1].resolve(new Blob(["6"]));
  await settle();
  assert.deepEqual(jobs.map((j) => j.index), [7, 6, 5]);
});

test("pages the reader left are aborted and start again when it comes back", async () => {
  const { queue, jobs, updates, add } = queueHarness();
  add(0, 1, 2, 3, 4, 5);
  queue.focus(0);
  await settle();
  // 0 and 1 are in flight (both from the initial adds before focus would choose 0,1 anyway).
  assert.deepEqual(jobs.map((j) => j.index), [0, 1]);
  queue.focus(4);
  await settle();
  assert.ok(jobs[0].aborted && jobs[1].aborted);
  assert.deepEqual(jobs.slice(2).map((j) => j.index), [4, 5]);
  assert.equal(queue.status(0), null);
  assert.ok(updates.some(([i, s]) => i === 0 && s === "waiting"));
  queue.focus(0);
  await settle();
  assert.ok(jobs[2].aborted && jobs[3].aborted);
  assert.deepEqual(jobs.slice(4).map((j) => j.index), [0, 1]);
  // Forgetting a page drops its bytes: nothing to translate when the window reaches it.
  queue.forget(2);
  jobs[4].resolve(new Blob(["0"])); jobs[5].resolve(new Blob(["1"]));
  await settle();
  assert.equal(jobs.length, 6);
});

test("errors stay on the page until retried; close stops everything", async () => {
  const { queue, jobs, add } = queueHarness({ maxInFlight: 1, ahead: 0 });
  add(0);
  await settle();
  jobs[0].reject(new t.TranslatorError("not_ready"));
  await settle();
  assert.equal(queue.status(0).state, "error");
  assert.equal(queue.status(0).error.code, "not_ready");
  assert.equal(jobs.length, 1);
  queue.retryFailed();
  await settle();
  assert.equal(jobs.length, 2);
  queue.close();
  assert.ok(jobs[1].aborted);
  add(0);
  await settle();
  assert.equal(jobs.length, 2);
});

test("a cache hit skips the translator; a miss translates and stores", async () => {
  const stored = new Map();
  const cache = {
    get: async (key) => stored.get(key) ?? null,
    put: async (key, blob) => { stored.set(key, blob); },
  };
  const keys = [];
  const key = async (bytes) => { const k = await t.cacheKey(bytes, "en-US", MODELS); keys.push(k); return k; };
  const { queue, jobs, add } = queueHarness({ cache, key, ahead: 1 });
  stored.set(await t.cacheKey(Uint8Array.of(0, 1, 2), "en-US", MODELS), new Blob(["cached 0"]));
  add(0, 1);
  await settle();
  assert.equal(await queue.status(0).blob.text(), "cached 0");
  assert.deepEqual(jobs.map((j) => j.index), [1], "only the miss went to the translator");
  jobs[0].resolve(new Blob(["fresh 1"]));
  await settle();
  assert.equal(await stored.get(keys[1]).text(), "fresh 1");
  assert.equal(stored.size, 2);
});

test("a broken cache or key never stops translation", async () => {
  const cache = { get: async () => { throw new Error("quota"); }, put: async () => { throw new Error("quota"); } };
  const { queue, jobs, add } = queueHarness({ cache, key: async () => { throw new Error("no subtle"); }, ahead: 0 });
  add(0);
  await settle();
  jobs[0].resolve(new Blob(["ok"]));
  await settle();
  assert.equal(queue.status(0).state, "done");
  const cache2 = { get: async () => { throw new Error("quota"); }, put: async () => { throw new Error("quota"); } };
  const h2 = queueHarness({ cache: cache2, key: async () => "k", ahead: 0 });
  h2.add(0);
  await settle();
  h2.jobs[0].resolve(new Blob(["ok"]));
  await settle();
  assert.equal(h2.queue.status(0).state, "done");
});

test("the IndexedDB cache works as a no-op without IndexedDB", async () => {
  const cache = t.indexedDbCache(1000, undefined);
  assert.equal(await cache.get("k"), null);
  await cache.put("k", new Blob(["x"]));
  const throwing = t.indexedDbCache(1000, { open: () => { throw new Error("SecurityError"); } });
  assert.equal(await throwing.get("k"), null);
  await throwing.put("k", new Blob(["x"]));
});

test("the translate switch is remembered per title and survives missing storage", () => {
  const store = new Map();
  globalThis.localStorage = { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => store.set(k, v), removeItem: (k) => store.delete(k) };
  t.setTranslateOn("101", true);
  assert.equal(t.translateOn("101"), true);
  assert.equal(t.translateOn("102"), false);
  t.setTranslateOn("101", false);
  assert.equal(t.translateOn("101"), false);
  globalThis.localStorage = { getItem: () => { throw new Error("denied"); }, setItem: () => { throw new Error("denied"); }, removeItem: () => { throw new Error("denied"); } };
  assert.equal(t.translateOn("101"), false);
  t.setTranslateOn("101", true);
  delete globalThis.localStorage;
});

test("/translator is a known route", () => {
  assert.ok(logic.knownRoute("/translator"));
});
