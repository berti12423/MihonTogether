// The Mihon Together Translator: a local program on the user's PC (built on Koharu) that turns raw manga pages into
// translated ones. The website talks to it at http://127.0.0.1:47823 (contract: docs/KOHARU_TRANSLATION_PLAN.md,
// "Translator API v1"). Nothing here touches Firebase or the phone; page bytes only go to the user's own PC.
// Pure and injectable (fetch, timers, storage, digest) so the tests drive it without a browser.

export const TRANSLATOR_URL = "http://127.0.0.1:47823";
export const TRANSLATOR_NAME = "mihon-together-translator";
export const TRANSLATOR_API = 1;
/** The bridge's own limit; bigger pages aren't sent at all. */
export const MAX_PAGE_BYTES = 30 * 1024 * 1024;
const HEALTH_TIMEOUT_MS = 2500;
const POLL_MS = 3000;
/** While ready, a slower check notices when the translator is closed. */
const READY_POLL_MS = 30_000;

/** "absent": nothing answers (not installed or not running). "unknown": not checked yet. */
export type TranslatorState = "unknown" | "absent" | "starting" | "downloading" | "loading" | "ready" | "error";

export interface TranslatorHealth {
  name: string;
  api: number;
  version: string;
  state: "starting" | "downloading" | "loading" | "ready" | "error";
  progress: { label: string; done: number; total: number } | null;
  error: string | null;
  device: string;
  models: Record<string, string>;
  queue: number;
  lastPageMs: number | null;
}

export type TranslatorErrorCode = "not_running" | "not_ready" | "bad_image" | "too_large" | "failed" | "aborted" | "bad_response";

export class TranslatorError extends Error {
  constructor(readonly code: TranslatorErrorCode, message?: string) {
    super(message || translatorErrorText(code));
    this.name = "TranslatorError";
  }
}

/** Plain-text messages for the reader. */
export function translatorErrorText(code: TranslatorErrorCode | string): string {
  switch (code) {
    case "not_running": return "The translator isn't running on this PC.";
    case "not_ready": return "The translator is still starting.";
    case "bad_image": return "The translator couldn't read this page.";
    case "too_large": return "This page is too large to translate.";
    case "aborted": return "Cancelled.";
    case "bad_response": return "The translator sent an unexpected answer.";
    default: return "The translator couldn't translate this page.";
  }
}

const isAbort = (error: unknown) => (error as { name?: string })?.name === "AbortError";

/** What /health says, checked against the contract (null when it's something else on that port). */
export function parseHealth(value: unknown): TranslatorHealth | null {
  const v = value as Partial<TranslatorHealth> | null;
  if (!v || typeof v !== "object" || v.name !== TRANSLATOR_NAME || v.api !== TRANSLATOR_API) return null;
  const state = (["starting", "downloading", "loading", "ready", "error"] as const).find((s) => s === v.state);
  if (!state) return null;
  const p = v.progress as TranslatorHealth["progress"] | undefined;
  const models: Record<string, string> = {};
  if (v.models && typeof v.models === "object") for (const [k, m] of Object.entries(v.models)) if (typeof m === "string") models[k] = m;
  return {
    name: v.name, api: v.api, version: typeof v.version === "string" ? v.version : "", state,
    progress: p && typeof p === "object" && Number.isFinite(p.done) && Number.isFinite(p.total)
      ? { label: typeof p.label === "string" ? p.label : "", done: Number(p.done), total: Number(p.total) } : null,
    error: typeof v.error === "string" ? v.error : null,
    device: typeof v.device === "string" ? v.device : "",
    models,
    queue: Number(v.queue) || 0,
    lastPageMs: Number.isFinite(v.lastPageMs) ? Number(v.lastPageMs) : null,
  };
}

/** One line for a status: "Downloading gemma4-12b-it: 40%". */
export function healthText(state: TranslatorState, health: TranslatorHealth | null): string {
  switch (state) {
    case "unknown": return "Looking for the translator…";
    case "absent": return "Not running on this PC.";
    case "ready": return `Ready${health?.device ? ` (${health.device === "cuda" ? "NVIDIA GPU" : health.device === "cpu" ? "CPU, slow" : health.device})` : ""}.`;
    case "error": return `Error: ${health?.error || "the translator stopped."}`;
    default: {
      const label = health?.progress?.label || (state === "downloading" ? "Downloading models" : state === "loading" ? "Loading models" : "Starting");
      const p = health?.progress;
      const percent = p && p.total > 0 ? `: ${Math.min(100, Math.floor((p.done / p.total) * 100))}%` : "";
      return `${label}${percent}…`;
    }
  }
}

export interface Timers {
  setTimeout(fn: () => void, ms: number): unknown;
  clearTimeout(id: unknown): void;
}
const realTimers: Timers = { setTimeout: (fn, ms) => setTimeout(fn, ms), clearTimeout: (id) => clearTimeout(id as ReturnType<typeof setTimeout>) };

export type FetchLike = (url: string, init?: RequestInit) => Promise<Response>;

/** Detection (polled /health) and single-page translation. */
export class TranslatorClient {
  state: TranslatorState = "unknown";
  health: TranslatorHealth | null = null;
  private readonly listeners = new Set<() => void>();
  private poll: unknown = null;
  private absentChecks = 0;
  private checking: Promise<TranslatorHealth | null> | null = null;
  private readonly fetch: FetchLike;
  private readonly timers: Timers;
  private readonly base: string;

  constructor(options: { fetch: FetchLike; timers?: Timers; base?: string }) {
    this.fetch = options.fetch;
    this.timers = options.timers ?? realTimers;
    this.base = options.base ?? TRANSLATOR_URL;
  }

  get ready(): boolean { return this.state === "ready"; }

  /** Calls [listener] on every state or progress change; polling runs while anyone listens. */
  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    if (this.listeners.size === 1) void this.check();
    return () => {
      this.listeners.delete(listener);
      if (!this.listeners.size && this.poll !== null) { this.timers.clearTimeout(this.poll); this.poll = null; }
    };
  }

  /** One /health check now (joins one in progress). */
  check(): Promise<TranslatorHealth | null> {
    this.checking ??= this.fetchHealth().then((health) => {
      this.checking = null;
      this.set(health ? health.state : "absent", health);
      return health;
    });
    return this.checking;
  }

  private async fetchHealth(): Promise<TranslatorHealth | null> {
    const controller = new AbortController();
    const timer = this.timers.setTimeout(() => controller.abort(), HEALTH_TIMEOUT_MS);
    try {
      const response = await this.fetch(`${this.base}/health`, { mode: "cors", cache: "no-store", signal: controller.signal });
      if (!response.ok) return null;
      return parseHealth(await response.json());
    } catch {
      return null;
    } finally {
      this.timers.clearTimeout(timer);
    }
  }

  private set(state: TranslatorState, health: TranslatorHealth | null): void {
    const changed = state !== this.state || JSON.stringify(health) !== JSON.stringify(this.health);
    this.state = state;
    this.health = health;
    this.schedule();
    if (changed) for (const listener of [...this.listeners]) { try { listener(); } catch { /* a listener's problem */ } }
  }

  private schedule(): void {
    if (this.poll !== null) this.timers.clearTimeout(this.poll);
    this.poll = null;
    if (!this.listeners.size) return;
    // Not running: back off from 3 s to 30 s, so a closed translator doesn't fill the console with refused connections.
    this.absentChecks = this.state === "absent" ? this.absentChecks + 1 : 0;
    const delay = this.state === "ready" ? READY_POLL_MS
      : this.state === "absent" ? Math.min(POLL_MS * 2 ** (this.absentChecks - 1), READY_POLL_MS) : POLL_MS;
    this.poll = this.timers.setTimeout(() => { this.poll = null; void this.check(); }, delay);
  }

  /** The translated page as a PNG. Fails with a [TranslatorError]. */
  async translate(bytes: Uint8Array, contentType: string, target = "en-US", signal?: AbortSignal): Promise<Blob> {
    if (signal?.aborted) throw new TranslatorError("aborted");
    if (bytes.byteLength > MAX_PAGE_BYTES) throw new TranslatorError("too_large");
    // The translator has no AVIF decoder: hand it a PNG of the page instead.
    if (/avif/i.test(contentType)) {
      try {
        ({ bytes, contentType } = await toPng(bytes, contentType));
      } catch {
        throw new TranslatorError("bad_image");
      }
    }
    let response: Response;
    try {
      response = await this.fetch(`${this.base}/translate?target=${encodeURIComponent(target)}`, {
        method: "POST", mode: "cors", cache: "no-store", signal,
        headers: { "Content-Type": contentType },
        body: new Blob([bytes as Uint8Array<ArrayBuffer>], { type: contentType }),
      });
    } catch (error) {
      if (isAbort(error) || signal?.aborted) throw new TranslatorError("aborted");
      // Closed since the last check: detection takes over again.
      this.set("absent", null);
      throw new TranslatorError("not_running");
    }
    try {
      if (response.ok) {
        const blob = await response.blob();
        if (!/^image\/png\b/i.test(response.headers.get("Content-Type") ?? blob.type)) throw new TranslatorError("bad_response");
        return blob.type === "image/png" ? blob : new Blob([blob], { type: "image/png" });
      }
      let body: { error?: string; message?: string } | null = null;
      try { body = await response.json(); } catch { body = null; }
      const known: TranslatorErrorCode[] = ["not_ready", "bad_image", "too_large", "failed"];
      const code = known.find((c) => c === body?.error)
        ?? (response.status === 503 ? "not_ready" : response.status === 413 ? "too_large" : response.status === 400 ? "bad_image" : "failed");
      if (code === "not_ready" && this.state === "ready") void this.check();
      throw new TranslatorError(code);
    } catch (error) {
      if (error instanceof TranslatorError) throw error;
      if (isAbort(error) || signal?.aborted) throw new TranslatorError("aborted");
      throw new TranslatorError("failed");
    }
  }
}

// ---------- cache ----------

/** Translated pages by key; every method may fail silently (no storage in private windows etc.). */
export interface TranslationCache {
  get(key: string): Promise<Blob | null>;
  put(key: string, blob: Blob): Promise<void>;
}

/** SHA-256 of the page bytes, the target language and the models from /health (a model change gives new pages). */
export async function cacheKey(bytes: Uint8Array, target: string, models: Record<string, string>,
  digest: (data: Uint8Array) => Promise<ArrayBuffer> = (data) => crypto.subtle.digest("SHA-256", data as Uint8Array<ArrayBuffer>)): Promise<string> {
  const hash = [...new Uint8Array(await digest(bytes))].map((b) => b.toString(16).padStart(2, "0")).join("");
  const modelPart = Object.keys(models).sort().map((k) => `${k}=${models[k]}`).join(",");
  return `${hash}|${target}|${modelPart}`;
}

const DB_NAME = "mihon-translator";
const STORE = "pages";

/** IndexedDB cache, capped at [maxBytes]; the least recently used pages go first. Never throws. */
export function indexedDbCache(maxBytes = 300 * 1024 * 1024, factory: IDBFactory | undefined = globalThis.indexedDB): TranslationCache {
  let db: Promise<IDBDatabase | null> | null = null;
  const open = () => {
    db ??= new Promise<IDBDatabase | null>((resolve) => {
      try {
        if (!factory) { resolve(null); return; }
        const request = factory.open(DB_NAME, 1);
        request.onupgradeneeded = () => {
          const store = request.result.createObjectStore(STORE, { keyPath: "key" });
          store.createIndex("used", "used");
        };
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => resolve(null);
        request.onblocked = () => resolve(null);
      } catch { resolve(null); }
    });
    return db;
  };
  const done = (tx: IDBTransaction) => new Promise<void>((resolve) => { tx.oncomplete = tx.onerror = tx.onabort = () => resolve(); });
  const trim = async (database: IDBDatabase) => {
    const tx = database.transaction(STORE, "readwrite");
    const store = tx.objectStore(STORE);
    const rows: Array<{ key: string; size: number }> = [];
    // Newest first, so everything after the cap is the oldest.
    const cursor = store.index("used").openCursor(null, "prev");
    cursor.onsuccess = () => {
      const c = cursor.result;
      if (!c) {
        let total = 0;
        for (const row of rows) { total += row.size; if (total > maxBytes) store.delete(row.key); }
        return;
      }
      rows.push({ key: c.value.key, size: Number(c.value.size) || 0 });
      c.continue();
    };
    await done(tx);
  };
  return {
    async get(key) {
      try {
        const database = await open();
        if (!database) return null;
        const tx = database.transaction(STORE, "readwrite");
        const store = tx.objectStore(STORE);
        const request = store.get(key);
        let found: Blob | null = null;
        request.onsuccess = () => {
          const row = request.result;
          if (row?.blob instanceof Blob) { found = row.blob; store.put({ ...row, used: Date.now() }); }
        };
        await done(tx);
        return found;
      } catch { return null; }
    },
    async put(key, blob) {
      try {
        const database = await open();
        if (!database || blob.size > maxBytes) return;
        const tx = database.transaction(STORE, "readwrite");
        tx.objectStore(STORE).put({ key, blob, size: blob.size, used: Date.now() });
        await done(tx);
        await trim(database);
      } catch { /* no storage: just no cache */ }
    },
  };
}

// ---------- queue ----------

export type PageTranslationState = "waiting" | "translating" | "done" | "error";
export interface PageTranslation { state: PageTranslationState; blob?: Blob; error?: TranslatorError }

export interface QueueOptions {
  translate: (bytes: Uint8Array, contentType: string, signal: AbortSignal) => Promise<Blob>;
  /** The cache key for a page (null: no caching, e.g. models unknown). */
  key?: (bytes: Uint8Array) => Promise<string | null>;
  cache?: TranslationCache | null;
  onUpdate?: (index: number, status: PageTranslation) => void;
  /** Requests to the translator at once (it runs one page at a time; a second keeps it busy). */
  maxInFlight?: number;
  /** Pages after the current one to translate ahead. */
  ahead?: number;
}

interface Job { index: number; controller: AbortController }

/**
 * Which pages get translated: the current page first, then the next [ahead]. Pages outside that window aren't
 * started, and running ones for pages the reader left are aborted. Finished pages stay until [forget].
 */
export class TranslationQueue {
  private readonly sources = new Map<number, { bytes: Uint8Array; contentType: string }>();
  private readonly statuses = new Map<number, PageTranslation>();
  private readonly running = new Map<number, Job>();
  private current = 0;
  private closed = false;
  private readonly maxInFlight: number;
  private readonly ahead: number;

  constructor(private readonly options: QueueOptions) {
    this.maxInFlight = Math.max(1, options.maxInFlight ?? 2);
    this.ahead = Math.max(0, options.ahead ?? 2);
  }

  status(index: number): PageTranslation | null { return this.statuses.get(index) ?? null; }

  /** The pages it would work on now, in order. */
  window(): number[] {
    return Array.from({ length: this.ahead + 1 }, (_, i) => this.current + i);
  }

  /** A page's original bytes are known (loaded from the phone). */
  setPage(index: number, bytes: Uint8Array, contentType: string): void {
    if (this.closed) return;
    this.sources.set(index, { bytes, contentType });
    this.pump();
  }

  /** The reader is on [index]: work moves there, and jobs for pages outside the window are aborted. */
  focus(index: number): void {
    if (this.closed) return;
    this.current = index;
    const wanted = new Set(this.window());
    for (const [i, job] of [...this.running]) {
      if (wanted.has(i)) continue;
      this.running.delete(i);
      job.controller.abort();
      // Not translated: it starts again when the reader comes back.
      this.statuses.delete(i);
      try { this.options.onUpdate?.(i, { state: "waiting" }); } catch { /* the view's problem */ }
    }
    this.pump();
  }

  /** The reader let go of a page (far away): its bytes and result go. */
  forget(index: number): void {
    this.running.get(index)?.controller.abort();
    this.running.delete(index);
    this.sources.delete(index);
    this.statuses.delete(index);
  }

  /** Failed pages try again (for example once the translator is ready). */
  retryFailed(): void {
    for (const [i, s] of [...this.statuses]) if (s.state === "error") this.statuses.delete(i);
    this.pump();
  }

  /** Stops everything (translation turned off or the reader closed). */
  close(): void {
    this.closed = true;
    for (const job of this.running.values()) job.controller.abort();
    this.running.clear();
    this.sources.clear();
    this.statuses.clear();
  }

  private update(index: number, status: PageTranslation): void {
    this.statuses.set(index, status);
    try { this.options.onUpdate?.(index, status); } catch { /* the view's problem */ }
  }

  private pump(): void {
    if (this.closed) return;
    for (const index of this.window()) {
      if (this.running.size >= this.maxInFlight) return;
      if (this.running.has(index) || this.statuses.has(index)) continue;
      const source = this.sources.get(index);
      if (!source) continue;
      const job: Job = { index, controller: new AbortController() };
      this.running.set(index, job);
      this.update(index, { state: "translating" });
      void this.run(job, source);
    }
  }

  private async run(job: Job, source: { bytes: Uint8Array; contentType: string }): Promise<void> {
    const { index, controller } = job;
    const mine = () => this.running.get(index) === job && !controller.signal.aborted;
    try {
      let key: string | null = null;
      try { key = (await this.options.key?.(source.bytes)) ?? null; } catch { key = null; }
      let blob = key && this.options.cache ? await this.options.cache.get(key).catch(() => null) : null;
      if (!mine()) return;
      if (!blob) {
        blob = await this.options.translate(source.bytes, source.contentType, controller.signal);
        if (key && this.options.cache) void this.options.cache.put(key, blob).catch(() => undefined);
      }
      if (!mine()) return;
      this.running.delete(index);
      this.update(index, { state: "done", blob });
    } catch (error) {
      if (!mine()) return;
      this.running.delete(index);
      const failure = error instanceof TranslatorError ? error : new TranslatorError("failed");
      if (failure.code === "aborted") this.statuses.delete(index);
      else this.update(index, { state: "error", error: failure });
    }
    this.pump();
  }
}

// ---------- the page's translator ----------

let shared: TranslatorClient | null = null;
/** The one translator client for this tab (the reader and the setup page share it). */
export function pageTranslator(): TranslatorClient {
  shared ??= new TranslatorClient({ fetch: (url, init) => fetch(url, init) });
  return shared;
}
/** Development only: swaps the page's translator (the fake one in dev-translator.ts). */
export function setPageTranslator(client: TranslatorClient): void {
  shared = client;
}

/** "Translate" remembered per title; works without storage. */
export function translateOn(mangaId: string): boolean {
  try { return localStorage.getItem(`mt.translate.${mangaId}`) === "1"; } catch { return false; }
}
export function setTranslateOn(mangaId: string, on: boolean): void {
  try {
    if (on) localStorage.setItem(`mt.translate.${mangaId}`, "1");
    else localStorage.removeItem(`mt.translate.${mangaId}`);
  } catch { /* not remembered */ }
}

/** Re-encodes an image the browser can decode (e.g. AVIF) as PNG. */
async function toPng(bytes: Uint8Array, contentType: string): Promise<{ bytes: Uint8Array; contentType: string }> {
  const bitmap = await createImageBitmap(new Blob([bytes as Uint8Array<ArrayBuffer>], { type: contentType }));
  const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
  canvas.getContext("2d")!.drawImage(bitmap, 0, 0);
  bitmap.close();
  const png = await canvas.convertToBlob({ type: "image/png" });
  return { bytes: new Uint8Array(await png.arrayBuffer()), contentType: "image/png" };
}
