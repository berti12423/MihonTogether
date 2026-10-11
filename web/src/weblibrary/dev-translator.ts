// Development only (never in production builds; loaded behind __DEV_TOOLS__): a fake Mihon Together Translator inside
// this page. It answers the same Translator API v1 as the real one through a fake fetch, so the real client, queue
// and cache run unchanged. "Translated" pages are the original with a visible TRANSLATED banner.
import { setPageTranslator, TRANSLATOR_API, TRANSLATOR_NAME, TranslatorClient } from "./translator";

const DELAY_MS = 1500;
const LOADING_MS = 4000;

function fakeFetch(): (url: string, init?: RequestInit) => Promise<Response> {
  const started = Date.now();
  let busy: Promise<unknown> = Promise.resolve();
  return async (url, init = {}) => {
    const path = new URL(url).pathname;
    if (path === "/health") {
      const loading = Date.now() - started < LOADING_MS;
      return new Response(JSON.stringify({
        name: TRANSLATOR_NAME, api: TRANSLATOR_API, version: "0.0.0-dev", state: loading ? "loading" : "ready",
        progress: loading ? { label: "Loading fake models", done: Date.now() - started, total: LOADING_MS } : null, error: null, device: "fake",
        models: { detection: "fake", ocr: "fake", inpainting: "fake", llm: "fake-dev-1" }, queue: 0, lastPageMs: DELAY_MS,
      }), { headers: { "Content-Type": "application/json" } });
    }
    if (path !== "/translate" || init.method !== "POST") return new Response("{}", { status: 404 });
    const signal = init.signal ?? undefined;
    // One page at a time, like the real bridge.
    const job = busy.then(async () => {
      if (signal?.aborted) throw new DOMException("aborted", "AbortError");
      await new Promise<void>((resolve, reject) => {
        const timer = setTimeout(resolve, DELAY_MS);
        signal?.addEventListener("abort", () => { clearTimeout(timer); reject(new DOMException("aborted", "AbortError")); });
      });
      return banner(init.body as Blob);
    });
    busy = job.catch(() => undefined);
    const png = await job;
    return new Response(png, { headers: { "Content-Type": "image/png", "X-Translate-Ms": String(DELAY_MS) } });
  };
}

async function banner(page: Blob): Promise<Blob> {
  const bitmap = await createImageBitmap(page);
  const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
  const g = canvas.getContext("2d")!;
  g.drawImage(bitmap, 0, 0);
  const band = Math.max(40, Math.round(bitmap.height * 0.08));
  g.fillStyle = "rgba(200, 30, 60, .85)";
  g.fillRect(0, Math.round(bitmap.height * 0.1), bitmap.width, band);
  g.fillStyle = "#fff";
  g.font = `700 ${Math.round(band * 0.6)}px system-ui, sans-serif`;
  g.textAlign = "center";
  g.textBaseline = "middle";
  g.fillText("TRANSLATED", bitmap.width / 2, Math.round(bitmap.height * 0.1) + band / 2);
  return canvas.convertToBlob({ type: "image/png" });
}

/** This tab uses the fake translator from now on. */
export function useDevTranslator(): void {
  setPageTranslator(new TranslatorClient({ fetch: fakeFetch() }));
}
