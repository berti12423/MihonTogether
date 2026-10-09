// "My library" protocol client (docs/WEB_LIBRARY_PROTOCOL.md, v1). Pure: no DOM, no WebRTC, no Firebase, so the
// framing, reassembly, request matching, queueing and timeouts are unit-tested in Node (test/weblibrary.test.mjs).

export const PROTOCOL_VERSION = 1;
/** Payload bytes per binary frame; with the 8-byte header a frame is at most 16 384 bytes. */
export const CHUNK_PAYLOAD = 16_376;
export const HEADER_BYTES = 8;
export const MAX_IN_FLIGHT = 4;
export const REQUEST_TIMEOUT_MS = 20_000;
export const IMAGE_TIMEOUT_MS = 60_000;

// ---------- wire types ----------

export interface Hello { protocol: number; deviceName: string; appBuild: number; librarySize: number }
export interface Category { categoryId: string; name: string; order: number }
export interface LibraryManga {
  mangaId: string; title: string; author: string | null; sourceId: string; sourceName: string; unread: number; downloaded: number;
  categoryIds: string[]; coverVersion: string; lastRead: number | null; dateAdded: number;
}
export interface LibraryList { categories: Category[]; manga: LibraryManga[] }
export type ReadingMode = "ltr" | "rtl" | "vertical" | "webtoon";
export interface Chapter {
  chapterId: string; name: string; number: number; scanlator: string | null; read: boolean; bookmark: boolean;
  lastPageRead: number; downloaded: boolean; dateUpload: number;
}
export interface MangaDetails {
  mangaId: string; title: string; author: string | null; artist: string | null; description: string | null; status: string;
  genres: string[]; sourceName: string; url: string | null; readingMode: ReadingMode; chapters: Chapter[];
}
export interface ChapterPages { chapterId: string; pageCount: number; downloaded: boolean }
export interface BinaryResult { contentType: string; bytes: Uint8Array }

/** Phone error codes plus the client's own (`timeout`, `closed`, `bad_response`, `aborted`). */
export type ErrorCode = "bad_request" | "not_found" | "source_error" | "busy" | "unsupported_protocol" | "internal"
  | "timeout" | "closed" | "bad_response" | "aborted";

export class ProtocolError extends Error {
  constructor(readonly code: ErrorCode | string, message: string) {
    super(message);
    this.name = "ProtocolError";
  }
}

// ---------- binary framing ----------

/** One binary frame: big-endian uint32 request id, uint32 chunk index, then the payload. */
export function encodeChunk(id: number, index: number, payload: Uint8Array): Uint8Array {
  if (payload.byteLength > CHUNK_PAYLOAD) throw new RangeError("chunk payload too large");
  const frame = new Uint8Array(HEADER_BYTES + payload.byteLength);
  const view = new DataView(frame.buffer);
  view.setUint32(0, id >>> 0, false);
  view.setUint32(4, index >>> 0, false);
  frame.set(payload, HEADER_BYTES);
  return frame;
}

export function decodeChunk(frame: ArrayBuffer | Uint8Array): { id: number; index: number; payload: Uint8Array } {
  const bytes = frame instanceof Uint8Array ? frame : new Uint8Array(frame);
  if (bytes.byteLength < HEADER_BYTES) throw new ProtocolError("bad_response", "Binary frame shorter than its header");
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  return { id: view.getUint32(0, false), index: view.getUint32(4, false), payload: bytes.subarray(HEADER_BYTES) };
}

/** Splits bytes into frames the way the phone sends them (used by the fake phone and the tests). */
export function encodeChunks(id: number, bytes: Uint8Array, size = CHUNK_PAYLOAD): Uint8Array[] {
  const frames: Uint8Array[] = [];
  for (let offset = 0, index = 0; offset < bytes.byteLength; offset += size, index++) {
    frames.push(encodeChunk(id, index, bytes.subarray(offset, Math.min(offset + size, bytes.byteLength))));
  }
  return frames;
}

/** Collects one response's chunks (in any order) and joins them once all `chunks` are in. */
export class Reassembly {
  private readonly parts: Array<Uint8Array | undefined>;
  private received = 0;

  constructor(readonly chunks: number, readonly size: number) {
    if (!Number.isInteger(chunks) || chunks < 0 || !Number.isInteger(size) || size < 0) {
      throw new ProtocolError("bad_response", "Invalid binary result header");
    }
    this.parts = new Array(chunks);
  }

  /** Adds a chunk; true once every chunk is in. Out-of-range or repeated chunks are protocol errors. */
  add(index: number, payload: Uint8Array): boolean {
    if (index >= this.chunks) throw new ProtocolError("bad_response", `Chunk ${index} of ${this.chunks}`);
    if (this.parts[index]) throw new ProtocolError("bad_response", `Chunk ${index} received twice`);
    // Copied: the payload is a view into a frame buffer the data channel may reuse.
    this.parts[index] = payload.slice();
    this.received++;
    return this.complete;
  }

  get complete(): boolean { return this.received === this.chunks; }

  /** The joined bytes; throws when the total isn't the announced `size`. */
  join(): Uint8Array {
    const total = this.parts.reduce((sum, part) => sum + (part?.byteLength ?? 0), 0);
    if (!this.complete || total !== this.size) throw new ProtocolError("bad_response", `Expected ${this.size} bytes, got ${total}`);
    const out = new Uint8Array(total);
    let offset = 0;
    for (const part of this.parts) { out.set(part!, offset); offset += part!.byteLength; }
    return out;
  }
}

// ---------- request client ----------

export interface Timers {
  setTimeout(callback: () => void, ms: number): unknown;
  clearTimeout(handle: unknown): void;
}
const realTimers: Timers = {
  setTimeout: (callback, ms) => setTimeout(callback, ms),
  clearTimeout: (handle) => clearTimeout(handle as ReturnType<typeof setTimeout>),
};

export interface RequestOptions { signal?: AbortSignal; timeoutMs?: number }
export interface ClientOptions { maxInFlight?: number; timeoutMs?: number; imageTimeoutMs?: number; timers?: Timers }

interface Pending {
  id: number; method: string; params: Record<string, unknown>; binary: boolean; timeoutMs: number;
  resolve: (value: unknown) => void; reject: (error: ProtocolError) => void;
  timer?: unknown; assembly?: Reassembly; contentType?: string;
  /** Chunks that arrived before the text header (not expected on an ordered channel; kept to be safe). */
  early?: Array<{ index: number; payload: Uint8Array }>;
  cleanup?: () => void;
}

/**
 * JSON requests with increasing ids, matched to responses; at most [maxInFlight] at once (the rest wait in a queue);
 * per-request timeouts (longer for `page.image`); binary results reassembled from chunk frames.
 * The transport is a `send(text)` function plus calls to [receive] for every incoming message.
 */
export class ProtocolClient {
  private nextId = 1;
  private readonly inFlight = new Map<number, Pending>();
  private readonly queue: Pending[] = [];
  private closedWith: ProtocolError | null = null;
  private readonly maxInFlight: number;
  private readonly timeoutMs: number;
  private readonly imageTimeoutMs: number;
  private readonly timers: Timers;

  constructor(private readonly send: (text: string) => void, options: ClientOptions = {}) {
    this.maxInFlight = options.maxInFlight ?? MAX_IN_FLIGHT;
    this.timeoutMs = options.timeoutMs ?? REQUEST_TIMEOUT_MS;
    this.imageTimeoutMs = options.imageTimeoutMs ?? IMAGE_TIMEOUT_MS;
    this.timers = options.timers ?? realTimers;
  }

  /** A JSON result. */
  request<T>(method: string, params: Record<string, unknown> = {}, options: RequestOptions = {}): Promise<T> {
    return this.enqueue(method, params, false, options) as Promise<T>;
  }

  /** A binary result (`manga.cover`, `page.image`). */
  requestBinary(method: string, params: Record<string, unknown> = {}, options: RequestOptions = {}): Promise<BinaryResult> {
    return this.enqueue(method, params, true, options) as Promise<BinaryResult>;
  }

  get pendingCount(): number { return this.inFlight.size; }
  get queuedCount(): number { return this.queue.length; }
  get closed(): boolean { return this.closedWith !== null; }

  private enqueue(method: string, params: Record<string, unknown>, binary: boolean, options: RequestOptions): Promise<unknown> {
    if (this.closedWith) return Promise.reject(this.closedWith);
    if (options.signal?.aborted) return Promise.reject(new ProtocolError("aborted", "Cancelled"));
    return new Promise((resolve, reject) => {
      const entry: Pending = {
        id: 0, method, params, binary, resolve, reject,
        timeoutMs: options.timeoutMs ?? (method === "page.image" ? this.imageTimeoutMs : this.timeoutMs),
      };
      if (options.signal) {
        const signal = options.signal;
        const onAbort = () => this.abort(entry);
        signal.addEventListener("abort", onAbort, { once: true });
        entry.cleanup = () => signal.removeEventListener("abort", onAbort);
      }
      this.queue.push(entry);
      this.pump();
    });
  }

  /**
   * Queued: dropped and rejected with `aborted`. Already sent: nothing changes, the response still settles the
   * promise (the phone is working on it anyway, and keeping the slot until it answers keeps it under 4 requests).
   */
  private abort(entry: Pending): void {
    const queued = this.queue.indexOf(entry);
    if (queued < 0) return;
    this.queue.splice(queued, 1);
    entry.cleanup?.();
    entry.reject(new ProtocolError("aborted", "Cancelled"));
  }

  private pump(): void {
    while (!this.closedWith && this.inFlight.size < this.maxInFlight && this.queue.length) {
      const entry = this.queue.shift()!;
      entry.id = this.nextId++;
      this.inFlight.set(entry.id, entry);
      entry.timer = this.timers.setTimeout(() => this.finish(entry, new ProtocolError("timeout", `${entry.method} timed out`)), entry.timeoutMs);
      try {
        this.send(JSON.stringify({ id: entry.id, method: entry.method, params: entry.params }));
      } catch (error) {
        this.finish(entry, new ProtocolError("closed", (error as Error)?.message || "Couldn't send"));
      }
    }
  }

  private finish(entry: Pending, error: ProtocolError | null, value?: unknown): void {
    if (this.inFlight.get(entry.id) !== entry) return;
    this.inFlight.delete(entry.id);
    this.timers.clearTimeout(entry.timer);
    entry.cleanup?.();
    if (error) entry.reject(error);
    else entry.resolve(value);
    this.pump();
  }

  /** Every message from the data channel: text (JSON) or binary (a chunk frame). Unknown ids are ignored. */
  receive(data: string | ArrayBuffer | Uint8Array): void {
    if (typeof data === "string") this.receiveText(data);
    else this.receiveChunk(data);
  }

  private receiveText(text: string): void {
    let message: { id?: unknown; result?: unknown; error?: { code?: unknown; message?: unknown } };
    try { message = JSON.parse(text); } catch { return; }
    const entry = typeof message.id === "number" ? this.inFlight.get(message.id) : undefined;
    if (!entry || entry.assembly) return;
    if (message.error && typeof message.error === "object") {
      this.finish(entry, new ProtocolError(String(message.error.code ?? "internal"), String(message.error.message ?? "")));
      return;
    }
    if (!entry.binary) { this.finish(entry, null, message.result); return; }
    const header = message.result as { contentType?: unknown; size?: unknown; chunks?: unknown } | undefined;
    try {
      if (!header || typeof header.contentType !== "string") throw new ProtocolError("bad_response", "Missing contentType");
      entry.contentType = header.contentType;
      entry.assembly = new Reassembly(Number(header.chunks), Number(header.size));
      for (const chunk of entry.early ?? []) entry.assembly.add(chunk.index, chunk.payload);
      entry.early = undefined;
      if (entry.assembly.complete) this.finish(entry, null, { contentType: entry.contentType, bytes: entry.assembly.join() });
    } catch (error) {
      this.finish(entry, error instanceof ProtocolError ? error : new ProtocolError("bad_response", String(error)));
    }
  }

  private receiveChunk(data: ArrayBuffer | Uint8Array): void {
    let chunk;
    try { chunk = decodeChunk(data); } catch { return; }
    const entry = this.inFlight.get(chunk.id);
    if (!entry || !entry.binary) return;
    if (!entry.assembly) {
      (entry.early ??= []).push({ index: chunk.index, payload: chunk.payload.slice() });
      return;
    }
    try {
      if (entry.assembly.add(chunk.index, chunk.payload)) this.finish(entry, null, { contentType: entry.contentType!, bytes: entry.assembly.join() });
    } catch (error) {
      this.finish(entry, error instanceof ProtocolError ? error : new ProtocolError("bad_response", String(error)));
    }
  }

  /** The connection ended: every waiting and queued request fails with [error]; later requests fail right away. */
  close(error = new ProtocolError("closed", "Connection closed")): void {
    if (this.closedWith) return;
    this.closedWith = error;
    const queued = this.queue.splice(0);
    for (const entry of queued) { entry.cleanup?.(); entry.reject(error); }
    for (const entry of [...this.inFlight.values()]) this.finish(entry, error);
  }
}

// ---------- helpers for the pages (pure) ----------

export type LibraryRoute = { view: "list" } | { view: "manga"; mangaId: string } | { view: "reader"; mangaId: string; chapterId: string };
const ID = "[A-Za-z0-9_-]{1,64}";
const ROUTE = new RegExp(`^/my-library(?:/(${ID})(?:/(${ID}))?)?/?$`);

/** `/my-library`, `/my-library/:mangaId`, `/my-library/:mangaId/:chapterId`; null for anything else. */
export function parseLibraryRoute(path: string): LibraryRoute | null {
  const match = ROUTE.exec(path);
  if (!match) return null;
  if (match[2]) return { view: "reader", mangaId: match[1], chapterId: match[2] };
  if (match[1]) return { view: "manga", mangaId: match[1] };
  return { view: "list" };
}

/** Plain-text error messages for the connection and requests. */
export type ConnectProblem = "no_answer" | "no_connect" | "phone_newer" | "phone_older" | "lost" | "unsupported_browser" | "signaling";
export function problemText(problem: ConnectProblem | string): string {
  switch (problem) {
    case "no_answer": return "Your phone didn't answer. Open Mihon Together on it and try again.";
    case "no_connect": return "Couldn't connect. Make sure your phone and computer are on the same Wi-Fi. If the phone uses AdGuard or a VPN, exclude Mihon Together in it.";
    case "phone_newer": return "Your phone has a newer version of Mihon Together. Reload this page.";
    case "phone_older": return "Update Mihon Together on your phone to read your library here.";
    case "lost": return "The connection to your phone was lost.";
    case "unsupported_browser": return "This browser can't connect to your phone. Try a current Chrome, Edge, Firefox or Safari.";
    default: return "Couldn't reach your phone. Check your connection and try again.";
  }
}

/** What to show for a failed request. */
export function requestErrorText(error: unknown): string {
  const code = (error as { code?: string })?.code;
  const message = (error as Error)?.message ?? "";
  switch (code) {
    case "not_found": return "Not found on your phone. It may have been removed from the library.";
    case "source_error": return message ? `The source failed: ${message}` : "The source failed. Try again.";
    case "busy": return "Your phone is busy. Try again in a moment.";
    case "timeout": return "Your phone took too long to answer.";
    case "closed": return problemText("lost");
    case "unsupported_protocol": return problemText("phone_older");
    default: return message || "Something went wrong on your phone.";
  }
}

/** Which side is out of date when the protocols differ: null when they match. */
export function protocolMismatch(phoneProtocol: number): "phone_newer" | "phone_older" | null {
  if (phoneProtocol === PROTOCOL_VERSION) return null;
  return phoneProtocol > PROTOCOL_VERSION ? "phone_newer" : "phone_older";
}

/** The cover cache key: a new `coverVersion` means a new picture. */
export const coverKey = (manga: { mangaId: string; coverVersion: string }) => `${manga.mangaId}:${manga.coverVersion}`;

/** Category tabs like the app: by `order`, the default category only when it has titles. */
export function visibleCategories(list: LibraryList): Category[] {
  const hasDefault = list.manga.some((m) => m.categoryIds.length === 0 || m.categoryIds.includes("0"));
  return [...list.categories].filter((c) => c.categoryId !== "0" || hasDefault).sort((a, b) => a.order - b.order);
}

/** Titles in a category (null = all) matching a search on title or author, sorted by title. */
export function filterLibrary(list: LibraryList, categoryId: string | null, search: string): LibraryManga[] {
  const q = search.trim().toLowerCase();
  return list.manga
    .filter((m) => categoryId === null || (categoryId === "0" ? m.categoryIds.length === 0 || m.categoryIds.includes("0") : m.categoryIds.includes(categoryId)))
    .filter((m) => !q || m.title.toLowerCase().includes(q) || (m.author ?? "").toLowerCase().includes(q))
    .sort((a, b) => a.title.localeCompare(b.title, undefined, { sensitivity: "base" }));
}

/** Chapters arrive newest first; reading order is the reverse. */
export const readingOrder = (chapters: Chapter[]): Chapter[] => [...chapters].reverse();

/** "Continue": the first unread chapter in reading order (null when everything is read). */
export function continueChapter(chapters: Chapter[]): Chapter | null {
  return readingOrder(chapters).find((c) => !c.read) ?? null;
}

/** The chapter after [chapterId] in reading order, or null at the newest. */
export function nextChapter(chapters: Chapter[], chapterId: string): Chapter | null {
  const index = chapters.findIndex((c) => c.chapterId === chapterId);
  return index > 0 ? chapters[index - 1] : null;
}

/** Where a chapter opens: its saved page unless it's read (then from the start), within range. */
export function startPage(chapter: Pick<Chapter, "read" | "lastPageRead"> | null, pageCount: number): number {
  if (!chapter || chapter.read || pageCount <= 0) return 0;
  return Math.max(0, Math.min(pageCount - 1, chapter.lastPageRead || 0));
}

export type ReaderLayout = { mode: "paged" | "strip"; rtl: boolean };
export function initialLayout(mode: ReadingMode | string | undefined): ReaderLayout {
  if (mode === "rtl") return { mode: "paged", rtl: true };
  if (mode === "vertical" || mode === "webtoon") return { mode: "strip", rtl: false };
  return { mode: "paged", rtl: false };
}

/** Pages to prefetch after [current]: the next [count], inside the chapter. */
export function prefetchPages(current: number, pageCount: number, count = 3): number[] {
  const pages: number[] = [];
  for (let i = current + 1; i <= current + count && i < pageCount; i++) pages.push(i);
  return pages;
}

/** Page images kept as blob URLs around [current]; the rest are revoked. */
export function keepWindow(current: number, pageCount: number): { from: number; to: number } {
  return { from: Math.max(0, current - 4), to: Math.min(pageCount - 1, current + 6) };
}

/** Paged-mode key handling: arrows follow the screen, so in RTL the left arrow goes forward. */
export function pageStep(key: string, rtl: boolean): -1 | 0 | 1 {
  if (key === "ArrowRight") return rtl ? -1 : 1;
  if (key === "ArrowLeft") return rtl ? 1 : -1;
  if (key === "ArrowDown" || key === "PageDown" || key === " ") return 1;
  if (key === "ArrowUp" || key === "PageUp") return -1;
  return 0;
}

/** Tap zones in paged mode: the left and right thirds turn pages (mirrored in RTL), the middle does nothing. */
export function tapStep(x: number, width: number, rtl: boolean): -1 | 0 | 1 {
  if (width <= 0) return 0;
  const side = x < width / 3 ? -1 : x > (width * 2) / 3 ? 1 : 0;
  return (rtl && side ? -side : side) as -1 | 0 | 1;
}

/**
 * `chapter.progress` at most every [interval] ms: the first change goes out now, later ones wait for the interval
 * (only the newest page is sent); reaching the last page sends `read: true` right away, once.
 */
export class ProgressThrottle {
  private lastSentAt = -Infinity;
  private sentPage = -1;
  private pendingPage: number | null = null;
  private timer: unknown = null;
  private readSent = false;

  constructor(
    private readonly pageCount: number,
    private readonly send: (lastPageRead: number, read: boolean) => void,
    private readonly now: () => number = Date.now,
    private readonly timers: Timers = realTimers,
    private readonly interval = 5_000,
  ) {}

  page(index: number): void {
    if (this.readSent) return;
    if (index >= this.pageCount - 1) { this.flushTimer(); this.readSent = true; this.emit(index, true); return; }
    this.pendingPage = index;
    const wait = this.lastSentAt + this.interval - this.now();
    if (wait <= 0) this.flush();
    else if (this.timer === null) this.timer = this.timers.setTimeout(() => { this.timer = null; this.flush(); }, wait);
  }

  /** Whether `read: true` went out. */
  get finished(): boolean { return this.readSent; }

  /** Leaving the chapter: send what's waiting now. */
  flush(): void {
    this.flushTimer();
    const page = this.pendingPage;
    this.pendingPage = null;
    if (page !== null && page !== this.sentPage && !this.readSent) this.emit(page, false);
  }

  private flushTimer(): void {
    if (this.timer !== null) { this.timers.clearTimeout(this.timer); this.timer = null; }
  }

  private emit(page: number, read: boolean): void {
    this.lastSentAt = this.now();
    this.sentPage = page;
    this.send(page, read);
  }
}

/** "Chapter 12", or the chapter's own name when it isn't just a number. */
export function chapterLabel(chapter: Pick<Chapter, "name" | "number">): string {
  return chapter.name?.trim() || (chapter.number >= 0 ? `Chapter ${chapter.number}` : "Chapter");
}

const IMAGE_TYPES = new Set(["image/jpeg", "image/png", "image/webp", "image/gif", "image/avif"]);
/**
 * The blob type for image bytes from the phone: only the raster types of the protocol. A blob URL shares this
 * site's origin, so a type like text/html or image/svg+xml must never be used (opened in a tab it could run script).
 */
export function safeImageType(contentType: string): string {
  const type = contentType.split(";")[0].trim().toLowerCase();
  return IMAGE_TYPES.has(type) ? type : "image/jpeg";
}
