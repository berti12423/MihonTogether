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

// Translator settings (/translator page, GET/PUT /settings on translator 0.2.0+).
const ts = await load("src/weblibrary/translator-settings.ts");
const SETTINGS = { ocr: "paddleocr-vl-1.6", inpainting: "lama", llm: "gemma4-12b-it", instructions: "", temperature: null, topP: null,
  topK: null, maxTokens: null, repeatPenalty: null, reasoning: true, vision: true, fonts: [] };
const settingsBody = (settings = SETTINGS) => ({
  settings, defaults: SETTINGS,
  options: {
    ocr: [{ id: "paddleocr-vl-1.6", name: "PaddleOCR-VL 1.6" }, { id: "manga-ocr", name: "Manga OCR" }],
    inpainting: [{ id: "lama", name: "LaMa" }],
    llm: [{ id: "gemma4-12b-it", name: "Gemma 4 12B", vision: true, reasoning: true }, { id: "qwen3.5-9b", name: "Qwen 3.5 9B", vision: false, reasoning: true }],
    maxInstructions: 2000, maxFonts: 8,
  },
});

test("settings answers are parsed and checked", () => {
  const info = ts.parseSettingsInfo(settingsBody());
  assert.equal(info.settings.llm, "gemma4-12b-it");
  assert.equal(info.options.llm[1].vision, false);
  assert.equal(info.options.maxFonts, 8);
  assert.equal(ts.parseSettingsInfo({ settings: { ocr: 1 } }), null);
  assert.equal(ts.parseSettingsInfo(null), null);
  assert.equal(ts.parseSettingsInfo({ ...settingsBody(), options: null }), null);
});

test("font lists and optional numbers come from the form as the translator expects", () => {
  assert.deepEqual(ts.parseFonts(" Anime Ace 2.0 BB, Comic Sans MS\nAnime Ace 2.0 BB,, "), ["Anime Ace 2.0 BB", "Comic Sans MS"]);
  assert.deepEqual(ts.parseFonts(""), []);
  assert.equal(ts.parseOptionalNumber(""), null);
  assert.equal(ts.parseOptionalNumber(" 0.3 "), 0.3);
  assert.equal(ts.parseOptionalNumber("abc"), undefined);
  assert.equal(ts.parseOptionalNumber("1.5", true), undefined);
  assert.equal(ts.parseOptionalNumber("40", true), 40);
});

test("saving PUTs JSON to the local translator and returns the saved settings", async () => {
  const changed = { ...SETTINGS, llm: "qwen3.5-9b", instructions: "Keep honorifics." };
  const fetcher = fakeFetch((url, init) => {
    assert.equal(url, `${t.TRANSLATOR_URL}/settings`);
    assert.equal(init.method, "PUT");
    assert.equal(init.headers["Content-Type"], "application/json");
    return json(200, settingsBody(JSON.parse(init.body)));
  });
  const info = await ts.saveSettings(changed, fetcher);
  assert.equal(info.settings.llm, "qwen3.5-9b");
  assert.equal(info.settings.instructions, "Keep honorifics.");
});

test("settings errors explain themselves", async () => {
  await assert.rejects(ts.fetchSettings(fakeFetch(() => Promise.reject(new TypeError("offline")))), /isn't running/);
  const old = await ts.fetchSettings(fakeFetch(() => json(404, { error: "not_found", message: "nope" }))).catch((e) => e);
  assert.equal(old.missing, true);
  assert.match(old.message, /no settings/);
  await assert.rejects(ts.saveSettings(SETTINGS, fakeFetch(() => json(400, { error: "bad_settings", message: "Temperature must be between 0 and 2." }))),
    /Temperature must be between 0 and 2/);
  await assert.rejects(ts.fetchSettings(fakeFetch(() => json(200, { hello: 1 }))), /unexpected answer/);
});

test("a settings change gives translated pages a new cache key", async () => {
  const bytes = page();
  const a = await t.cacheKey(bytes, "en-US", { ...MODELS, settings: "aaaa1111" });
  const b = await t.cacheKey(bytes, "en-US", { ...MODELS, settings: "bbbb2222" });
  assert.notEqual(a, b);
});

// Which models fit the GPU (sizes from the translator; estimate measured on a 16 GB RTX 5070 Ti).
const LLMS = [
  { id: "gemma4-26b-a4b-it", name: "Gemma 4 26B A4B", vision: true, reasoning: true, sizeMb: 13589, projectorMb: 1138 },
  { id: "gemma4-12b-it", name: "Gemma 4 12B", vision: true, reasoning: true, sizeMb: 6406, projectorMb: 168 },
  { id: "gemma4-e4b-it", name: "Gemma 4 E4B", vision: true, reasoning: true, sizeMb: 4021, projectorMb: 945 },
  { id: "gemma4-e2b-it", name: "Gemma 4 E2B", vision: true, reasoning: true, sizeMb: 2499, projectorMb: 940 },
  { id: "qwen3.5-2b", name: "Qwen 3.5 2B", vision: true, reasoning: true, sizeMb: 1280, projectorMb: 635 },
  { id: "mystery", name: "Mystery", vision: false, reasoning: false, sizeMb: null, projectorMb: null },
];
const GPU16 = { name: "NVIDIA GeForce RTX 5070 Ti", vramMb: 16303 };

test("the memory estimate matches the measured default model", () => {
  const need = ts.neededMb(LLMS[1], true);
  assert.ok(Math.abs(need - 10104) < 300, `estimated ${need} MB, measured 10104 MB`);
  assert.ok(ts.neededMb(LLMS[2], false) < ts.neededMb(LLMS[2], true), "the projector only counts with the page image on");
  assert.equal(ts.neededMb(LLMS[5], true), null);
});

test("models are sorted into fits, tight and too big for the GPU", () => {
  assert.equal(ts.fitFor(10_000, GPU16), "fits");
  assert.equal(ts.fitFor(15_500, GPU16), "tight");
  assert.equal(ts.fitFor(19_000, GPU16), "too_big");
  assert.equal(ts.fitFor(null, GPU16), "unknown");
  assert.equal(ts.fitFor(5_000, null), "unknown");
  assert.equal(ts.fitFor(ts.neededMb(LLMS[0], true), GPU16), "too_big", "26B doesn't fit 16 GB with the pipeline");
});

test("the recommendation is the best model that fits comfortably", () => {
  assert.equal(ts.recommendedModel(LLMS, GPU16, true).id, "gemma4-12b-it");
  assert.equal(ts.recommendedModel(LLMS, { name: "RTX 4090", vramMb: 24564 }, true).id, "gemma4-26b-a4b-it");
  // 6 GB: nothing fits with room to spare, so the best tight fit.
  assert.equal(ts.recommendedModel(LLMS, { name: "RTX 3060 Laptop", vramMb: 6144 }, true).id, "qwen3.5-2b");
  // 8 GB: a model with room to spare beats a bigger one that only just fits.
  assert.equal(ts.recommendedModel(LLMS, { name: "RTX 4060", vramMb: 8188 }, true).id, "qwen3.5-2b");
  assert.equal(ts.recommendedModel(LLMS, { name: "GTX 1050", vramMb: 2048 }, true), null);
  assert.equal(ts.recommendedModel(LLMS, null, true).id, "gemma4-e2b-it", "no NVIDIA GPU: a small model for the processor");
});

test("model labels say whether each one fits", () => {
  assert.match(ts.modelLabel(LLMS[1], GPU16, true), /^Gemma 4 12B — fits your GPU \(about 9\.9 GB\)$/);
  assert.match(ts.modelLabel(LLMS[0], GPU16, true), /too big for your GPU/);
  assert.match(ts.modelLabel(LLMS[1], null, true), /^Gemma 4 12B \(about 9\.9 GB\)$/);
  assert.equal(ts.modelLabel(LLMS[5], GPU16, true), "Mystery");
});

test("settings answers carry the GPU and model sizes", () => {
  const body = settingsBody();
  body.options.gpu = { name: "RTX", vramMb: 8192 };
  body.options.llm[0].sizeMb = 6406;
  const info = ts.parseSettingsInfo(body);
  assert.deepEqual(info.options.gpu, { name: "RTX", vramMb: 8192 });
  assert.equal(info.options.llm[0].sizeMb, 6406);
  assert.equal(info.options.llm[1].sizeMb, null);
  assert.equal(ts.parseSettingsInfo({ ...settingsBody(), options: { ...settingsBody().options, gpu: { name: "x", vramMb: -1 } } }).options.gpu, null);
});
