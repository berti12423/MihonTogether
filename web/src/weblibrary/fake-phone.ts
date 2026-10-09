// Development only: a fake phone that answers the "My library" protocol with demo data. The tests drive it directly;
// local (emulator) builds can connect to it through a real in-page WebRTC loopback (see dev-loopback.ts).
// Production builds never include it (build.mjs sets __DEV_TOOLS__ to false there and the bundle is checked).
import { encodeChunks, PROTOCOL_VERSION } from "./protocol";

export interface FakeImage { contentType: string; bytes: Uint8Array }
export interface FakePhoneOptions {
  /** Makes the bytes of a cover or page; the default is a tiny grey GIF. */
  image?: (kind: "cover" | "page", key: string, index: number) => Promise<FakeImage> | FakeImage;
  /** Extra delay per request in ms, to see loading states. */
  delayMs?: number;
  chunkSize?: number;
}

// 1x1 grey GIF.
const PIXEL = new Uint8Array([71, 73, 70, 56, 57, 97, 1, 0, 1, 0, 128, 0, 0, 128, 128, 128, 0, 0, 0, 33, 249, 4, 0, 0, 0, 0, 0, 44, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 68, 1, 0, 59]);

const TITLES = [
  ["Frieren", "Yamada Kanehito", "rtl"], ["Dungeon Meshi", "Kui Ryouko", "rtl"], ["Solo Leveling", "Chugong", "webtoon"],
  ["Witch Hat Atelier", "Shirahama Kamome", "rtl"], ["Blue Period", "Yamaguchi Tsubasa", "rtl"], ["Omniscient Reader", "Sing Shong", "webtoon"],
  ["Vinland Saga", "Yukimura Makoto", "rtl"], ["The Apothecary Diaries", "Natsu Hyuuga", "rtl"], ["Tower of God", "SIU", "vertical"],
  ["Chainsaw Man", "Fujimoto Tatsuki", "rtl"], ["Lore Olympus", null, "webtoon"], ["Asterix", "René Goscinny", "ltr"],
] as const;

export class FakePhone {
  private helloDone = false;
  readonly progress: Array<{ chapterId: string; lastPageRead: number; read: boolean }> = [];
  private readonly read = new Map<string, { lastPageRead: number; read: boolean }>();

  constructor(
    private readonly sendText: (text: string) => void,
    private readonly sendBinary: (frame: Uint8Array) => void,
    private readonly options: FakePhoneOptions = {},
  ) {}

  /** One message from the browser. */
  async handle(text: string): Promise<void> {
    let request: { id: number; method: string; params: Record<string, unknown> };
    try { request = JSON.parse(text); } catch { return; }
    if (this.options.delayMs) await new Promise((resolve) => setTimeout(resolve, this.options.delayMs));
    const fail = (code: string, message: string) => this.sendText(JSON.stringify({ id: request.id, error: { code, message } }));
    const ok = (result: unknown) => this.sendText(JSON.stringify({ id: request.id, result }));
    const params = request.params ?? {};
    if (request.method === "hello") {
      if (params.protocol !== PROTOCOL_VERSION) return fail("unsupported_protocol", "Protocol 1 only");
      this.helloDone = true;
      return ok({ protocol: PROTOCOL_VERSION, deviceName: "Demo phone", appBuild: 8300, librarySize: TITLES.length });
    }
    if (!this.helloDone) return fail("bad_request", "Say hello first");
    const mangaIndex = (id: unknown) => { const i = Number(id) - 100; return Number.isInteger(i) && i >= 0 && i < TITLES.length ? i : -1; };
    switch (request.method) {
      case "library.list":
        return ok({
          categories: [{ categoryId: "0", name: "Default", order: 0 }, { categoryId: "3", name: "Reading", order: 1 }, { categoryId: "4", name: "Webtoons", order: 2 }],
          manga: TITLES.map(([title, author, mode], i) => ({
            mangaId: String(100 + i), title, author, sourceId: "2499283573021220255", sourceName: "Demo source", unread: i % 4, downloaded: i % 3,
            categoryIds: mode === "webtoon" || mode === "vertical" ? ["4"] : i % 2 ? ["3"] : [], coverVersion: "1", lastRead: i % 2 ? Date.now() - i * 3_600_000 : null,
            dateAdded: Date.now() - i * 86_400_000,
          })),
        });
      case "manga.details": {
        const i = mangaIndex(params.mangaId);
        if (i < 0) return fail("not_found", `Manga ${String(params.mangaId)} isn't in the library`);
        const [title, author, mode] = TITLES[i];
        return ok({
          mangaId: String(100 + i), title, author, artist: author, description: `A demo title served by the fake phone.\nReading mode: ${mode}.`,
          status: "ongoing", genres: ["Demo", "Adventure"], sourceName: "Demo source", url: null, readingMode: mode,
          chapters: Array.from({ length: 6 }, (_, k) => {
            const n = 6 - k, chapterId = `${100 + i}${String(n).padStart(3, "0")}`, saved = this.read.get(chapterId);
            return { chapterId, name: `Chapter ${n}`, number: n, scanlator: "Demo scans", read: saved?.read ?? n <= 2, bookmark: false,
              lastPageRead: saved?.lastPageRead ?? 0, downloaded: n % 2 === 0, dateUpload: Date.now() - k * 7 * 86_400_000 };
          }),
        });
      }
      case "chapter.pages": {
        const chapterId = String(params.chapterId ?? "");
        if (!/^\d{6}$/.test(chapterId) || mangaIndex(chapterId.slice(0, 3)) < 0) return fail("not_found", "No such chapter");
        return ok({ chapterId, pageCount: 8, downloaded: true });
      }
      case "manga.cover":
      case "page.image": {
        const cover = request.method === "manga.cover";
        const key = String(cover ? params.mangaId : params.chapterId);
        const index = cover ? 0 : Number(params.index);
        if (cover ? mangaIndex(key) < 0 : !(index >= 0 && index < 8)) return fail("not_found", "No such image");
        const image = await (this.options.image?.(cover ? "cover" : "page", key, index) ?? { contentType: "image/gif", bytes: PIXEL });
        const frames = encodeChunks(request.id, image.bytes, this.options.chunkSize);
        this.sendText(JSON.stringify({ id: request.id, result: { contentType: image.contentType, size: image.bytes.byteLength, chunks: frames.length } }));
        frames.forEach((frame) => this.sendBinary(frame));
        return;
      }
      case "chapter.progress": {
        const entry = { chapterId: String(params.chapterId), lastPageRead: Number(params.lastPageRead), read: params.read === true };
        this.progress.push(entry);
        const before = this.read.get(entry.chapterId);
        this.read.set(entry.chapterId, { lastPageRead: entry.lastPageRead, read: entry.read || before?.read === true });
        return ok({ ok: true });
      }
      default:
        return fail("bad_request", `Unknown method ${request.method}`);
    }
  }
}
