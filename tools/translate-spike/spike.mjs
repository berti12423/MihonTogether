// T0 spike (docs/TRANSLATION_PLAN.md): send raw pages to Gemini, get text regions + English translations as JSON,
// and write an HTML report that overlays them on the pages, with latency, tokens and estimated cost per page.
//
// Usage (from this folder):
//   npm install
//   put raw pages (jpg/png/webp) in ./pages/   (subfolders allowed, e.g. pages/ko/, pages/ja/)
//   put GEMINI_API_KEY=... in ./.env            (free key from https://aistudio.google.com/apikey)
//   node spike.mjs [--models gemini-3.1-flash-lite,gemini-3.8-flash] [--resolution high] [--limit 20]
// Then open results/report.html.
import { GoogleGenAI } from "@google/genai";
import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { basename, extname, join, relative } from "node:path";

const here = new URL(".", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1");
const args = Object.fromEntries(
  process.argv.slice(2).reduce((pairs, arg, i, all) => (arg.startsWith("--") ? [...pairs, [arg.slice(2), all[i + 1]]] : pairs), []),
);
const models = (args.models ?? "gemini-3.1-flash-lite,gemini-3.8-flash").split(",");
const resolution = args.resolution ?? "high";
const limit = Number(args.limit ?? 20);

// Paid-tier list prices per 1M tokens (USD), checked 2026-10-02 at ai.google.dev/gemini-api/docs/pricing.
const PRICES = {
  "gemini-3.1-flash-lite": { input: 0.25, output: 1.5 },
  "gemini-3.5-flash-lite": { input: 0.3, output: 2.5 },
  "gemini-3.8-flash": { input: 0.75, output: 3.75 },
};

function loadKey() {
  if (process.env.GEMINI_API_KEY) return process.env.GEMINI_API_KEY;
  const env = join(here, ".env");
  if (existsSync(env)) {
    const line = readFileSync(env, "utf8").split(/\r?\n/).find((l) => l.startsWith("GEMINI_API_KEY="));
    if (line) return line.slice("GEMINI_API_KEY=".length).trim();
  }
  throw new Error("Set GEMINI_API_KEY in tools/translate-spike/.env (see the header of spike.mjs).");
}

const MIME = { ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".webp": "image/webp" };
function findPages(dir) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir).sort().flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return findPages(path);
    return MIME[extname(name).toLowerCase()] ? [path] : [];
  });
}

/** Width/height from the file header (PNG, JPEG, WebP), so the report can flag tall webtoon strips. */
function imageSize(buf) {
  if (buf.readUInt32BE(0) === 0x89504e47) return { w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) };
  if (buf.toString("ascii", 0, 4) === "RIFF" && buf.toString("ascii", 8, 12) === "WEBP") {
    const chunk = buf.toString("ascii", 12, 16);
    if (chunk === "VP8X") return { w: 1 + buf.readUIntLE(24, 3), h: 1 + buf.readUIntLE(27, 3) };
    if (chunk === "VP8L") { const b = buf.readUInt32LE(21); return { w: 1 + (b & 0x3fff), h: 1 + ((b >> 14) & 0x3fff) }; }
    return { w: buf.readUInt16LE(26) & 0x3fff, h: buf.readUInt16LE(28) & 0x3fff };
  }
  for (let i = 2; i < buf.length - 9;) { // JPEG: walk segments to the first SOFn
    if (buf[i] !== 0xff) { i++; continue; }
    const marker = buf[i + 1], length = buf.readUInt16BE(i + 2);
    if (marker >= 0xc0 && marker <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(marker)) return { w: buf.readUInt16BE(i + 7), h: buf.readUInt16BE(i + 5) };
    i += 2 + length;
  }
  return { w: 0, h: 0 };
}

const SCHEMA = {
  type: "object",
  properties: {
    sourceLanguage: { type: "string", description: "ISO 639-1 code of the page's main language (ja, ko, zh, ...)" },
    blocks: {
      type: "array",
      description: "Every text region, in reading order.",
      items: {
        type: "object",
        properties: {
          box_2d: { type: "array", items: { type: "integer" }, description: "[ymin, xmin, ymax, xmax] normalized to 0-1000, tight around the text" },
          kind: { type: "string", enum: ["dialogue", "narration", "sfx", "sign", "other"] },
          vertical: { type: "boolean", description: "true if the source text runs top-to-bottom" },
          source: { type: "string", description: "the original text exactly as written" },
          translation: { type: "string", description: "natural English translation for a comic reader" },
        },
        required: ["box_2d", "kind", "vertical", "source", "translation"],
      },
    },
  },
  required: ["sourceLanguage", "blocks"],
};

const INSTRUCTION = [
  "You translate raw manga, manhwa and manhua pages into natural English for comic readers.",
  "Find every text region on the page: speech bubbles, thought bubbles, captions/narration boxes, signs and sound effects.",
  "Return one block per bubble or caption (merge the lines of one bubble), in the page's reading order",
  "(Japanese: right-to-left, top-to-bottom; Korean and Chinese webtoons: top-to-bottom).",
  "box_2d must tightly enclose that block's text, as [ymin, xmin, ymax, xmax] normalized to 0-1000.",
  "Mark sound effects drawn into the art as kind \"sfx\".",
  "Translate for meaning and tone, keep names consistent, keep honorifics like -san/-nim only when they carry meaning.",
  "If a region is unreadable, still return its box with source \"\" and translation \"\".",
].join(" ");

async function translatePage(client, model, path) {
  const bytes = readFileSync(path);
  if (args.dry === "yes") { // exercises the report without a key or network
    const blocks = [{ box_2d: [100, 100, 250, 600], kind: "dialogue", vertical: false, source: "안녕", translation: "Hello" },
      { box_2d: [600, 300, 700, 900], kind: "sfx", vertical: true, source: "쾅", translation: "BOOM" }];
    return { ms: 0, input: 1290, output: 210, cost: 0, size: imageSize(bytes), result: { sourceLanguage: "ko", blocks } };
  }
  const started = performance.now();
  const interaction = await client.interactions.create({
    model,
    store: false, // don't keep raw pages server-side
    system_instruction: INSTRUCTION,
    input: [
      { type: "text", text: "Translate this page." },
      { type: "image", data: bytes.toString("base64"), mime_type: MIME[extname(path).toLowerCase()], resolution },
    ],
    response_format: { type: "text", mime_type: "application/json", schema: SCHEMA },
  });
  const ms = Math.round(performance.now() - started);
  const usage = interaction.usage ?? {};
  const price = PRICES[model];
  const input = usage.total_input_tokens ?? 0, output = (usage.total_output_tokens ?? 0) + (usage.total_thought_tokens ?? 0);
  const cost = price ? (input * price.input + output * price.output) / 1e6 : null;
  return { ms, input, output, cost, size: imageSize(bytes), result: JSON.parse(interaction.output_text ?? "{}") };
}

function esc(s) {
  return String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
}

function report(runs, pagesDir, outDir) {
  const rows = runs.map((run) => {
    const img = relative(outDir, run.path).replaceAll("\\", "/");
    const blocks = (run.result.blocks ?? []).map((b, i) => {
      const [y0, x0, y1, x1] = b.box_2d ?? [0, 0, 0, 0];
      const style = `top:${y0 / 10}%;left:${x0 / 10}%;height:${(y1 - y0) / 10}%;width:${(x1 - x0) / 10}%`;
      return `<div class="b ${esc(b.kind)}" style="${style}" title="${esc(`${i + 1}. ${b.kind}${b.vertical ? " (vertical)" : ""}: ${b.source}`)}">${esc(b.translation)}</div>`;
    }).join("");
    const list = (run.result.blocks ?? []).map((b, i) => `<li><b>${i + 1}. ${esc(b.kind)}</b> ${esc(b.source)}<br>→ ${esc(b.translation)}</li>`).join("");
    const tall = run.size.h > run.size.w * 3 ? ' <span class="warn">tall strip: slice in T3</span>' : "";
    return `<section><h2>${esc(relative(pagesDir, run.path))} · ${esc(run.model)}${tall}</h2>
<p>${run.error ? `<span class="warn">${esc(run.error)}</span>` : `${run.ms} ms · ${run.input} in / ${run.output} out tokens · ${run.cost == null ? "?" : `$${run.cost.toFixed(5)}`} · ${run.size.w}×${run.size.h} · lang ${esc(run.result.sourceLanguage ?? "?")}`}</p>
<div class="pair"><div class="page"><img src="${esc(img)}">${blocks}</div><ol>${list}</ol></div></section>`;
  }).join("\n");
  const totals = Object.entries(Object.groupBy(runs.filter((r) => !r.error), (r) => r.model)).map(([model, rs]) => {
    const avg = (f) => rs.reduce((s, r) => s + (f(r) ?? 0), 0) / rs.length;
    return `<tr><td>${esc(model)}</td><td>${rs.length}</td><td>${Math.round(avg((r) => r.ms))} ms</td><td>${Math.round(avg((r) => r.input))}</td><td>${Math.round(avg((r) => r.output))}</td><td>$${avg((r) => r.cost).toFixed(5)}</td><td>$${(avg((r) => r.cost) * 1000).toFixed(2)}</td></tr>`;
  }).join("");
  return `<!doctype html><meta charset="utf-8"><title>Translation spike</title><style>
body{font:14px system-ui;margin:16px;background:#111;color:#eee}table{border-collapse:collapse}td,th{border:1px solid #444;padding:4px 8px}
.pair{display:flex;gap:16px;align-items:flex-start}.page{position:relative;width:min(560px,60vw);flex:none}.page img{width:100%;display:block}
.b{position:absolute;background:rgba(255,255,255,.92);color:#000;font:600 11px/1.15 system-ui;overflow:hidden;outline:2px solid #2a7;padding:1px;box-sizing:border-box}
.b.sfx{background:rgba(255,230,120,.85);outline-color:#c90}.page:hover .b{opacity:.15}ol{max-width:520px}.warn{color:#f88}
</style><h1>Translation spike</h1><p>Hover a page to see the original. Green: dialogue/narration, yellow: SFX.</p>
<table><tr><th>Model</th><th>Pages</th><th>Avg latency</th><th>Avg input tok</th><th>Avg output tok</th><th>Avg cost/page</th><th>Per 1,000 pages</th></tr>${totals}</table>
${rows}`;
}

const pagesDir = join(here, args.pages ?? "pages"), outDir = join(here, args.out ?? "results");
const pages = findPages(pagesDir).slice(0, limit);
if (pages.length === 0) throw new Error(`No pages found. Put raw page images in ${pagesDir}.`);
mkdirSync(outDir, { recursive: true });
const client = args.dry === "yes" ? null : new GoogleGenAI({ apiKey: loadKey() });
const runs = [];
for (const model of models) {
  for (const path of pages) {
    process.stdout.write(`${model} ${basename(path)} ... `);
    try {
      const run = { model, path, ...(await translatePage(client, model, path)) };
      runs.push(run);
      console.log(`${run.ms} ms, ${run.result.blocks?.length ?? 0} blocks`);
    } catch (error) {
      runs.push({ model, path, error: String(error?.message ?? error), result: {}, size: { w: 0, h: 0 } });
      console.log(`failed: ${error?.message ?? error}`);
    }
  }
}
writeFileSync(join(outDir, "runs.json"), JSON.stringify(runs.map(({ path, ...r }) => ({ page: relative(pagesDir, path), ...r })), null, 2));
writeFileSync(join(outDir, "report.html"), report(runs, pagesDir, outDir));
console.log(`\nReport: ${join(outDir, "report.html")}`);
