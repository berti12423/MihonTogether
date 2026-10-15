// /translator settings: Koharu's own choices (OCR, inpainting, translation model and options, fonts) for the
// Mihon Together Translator on this PC, read and saved through its GET/PUT /settings (translator 0.2.0 and later).
// They are stored on the PC, apply from the next translated page, and change /health's models (so translated pages
// cached in this browser for other settings aren't reused).
import { h, replace } from "../dom";
import { TRANSLATOR_URL, type FetchLike } from "./translator";

export interface TranslatorSettings {
  ocr: string;
  inpainting: string;
  llm: string;
  instructions: string;
  temperature: number | null;
  topP: number | null;
  topK: number | null;
  maxTokens: number | null;
  repeatPenalty: number | null;
  reasoning: boolean;
  vision: boolean;
  fonts: string[];
}

export interface Choice { id: string; name: string }
export interface LlmChoice extends Choice { vision: boolean; reasoning: boolean; sizeMb: number | null; projectorMb: number | null }
export interface Gpu { name: string; vramMb: number }

export interface SettingsInfo {
  settings: TranslatorSettings;
  defaults: TranslatorSettings;
  options: { ocr: Choice[]; inpainting: Choice[]; llm: LlmChoice[]; maxInstructions: number; maxFonts: number; gpu: Gpu | null };
}

const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? v : null);
const str = (v: unknown) => (typeof v === "string" ? v : "");

function parseSettings(value: unknown): TranslatorSettings | null {
  const v = value as Record<string, unknown> | null;
  if (!v || typeof v !== "object" || typeof v.ocr !== "string" || typeof v.inpainting !== "string" || typeof v.llm !== "string") return null;
  return {
    ocr: v.ocr, inpainting: v.inpainting, llm: v.llm, instructions: str(v.instructions),
    temperature: num(v.temperature), topP: num(v.topP), topK: num(v.topK), maxTokens: num(v.maxTokens), repeatPenalty: num(v.repeatPenalty),
    reasoning: v.reasoning !== false, vision: v.vision !== false,
    fonts: Array.isArray(v.fonts) ? v.fonts.filter((f): f is string => typeof f === "string") : [],
  };
}

const choices = (value: unknown): Choice[] => (Array.isArray(value) ? value : [])
  .filter((c) => c && typeof c.id === "string").map((c) => ({ id: c.id as string, name: str(c.name) || c.id }));

/** GET /settings, checked (null when the answer isn't settings). */
export function parseSettingsInfo(value: unknown): SettingsInfo | null {
  const v = value as Record<string, unknown> | null;
  if (!v || typeof v !== "object") return null;
  const settings = parseSettings(v.settings), defaults = parseSettings(v.defaults);
  const o = v.options as Record<string, unknown> | undefined;
  if (!settings || !defaults || !o || typeof o !== "object") return null;
  const llm = (Array.isArray(o.llm) ? o.llm : []).filter((c) => c && typeof c.id === "string")
    .map((c) => ({ id: c.id as string, name: str(c.name) || c.id, vision: c.vision === true, reasoning: c.reasoning === true,
      sizeMb: num(c.sizeMb), projectorMb: num(c.projectorMb) }));
  const g = o.gpu as Record<string, unknown> | null | undefined;
  const vram = g && typeof g === "object" ? num(g.vramMb) : null;
  const gpu = g && typeof g.name === "string" && vram !== null && vram > 0 ? { name: g.name, vramMb: vram } : null;
  return {
    settings, defaults,
    options: { ocr: choices(o.ocr), inpainting: choices(o.inpainting), llm, maxInstructions: num(o.maxInstructions) ?? 2000, maxFonts: num(o.maxFonts) ?? 8, gpu },
  };
}

// Graphics memory estimate, measured on an RTX 5070 Ti (2026-10-14): with Gemma 4 12B (6.4 GB of model files) the
// translator used 10.1 GB. Detection, OCR, inpainting and the runtime take about 2.9 GB; the model about 1.1x its
// files (its working memory grows with it).
export const PIPELINE_MB = 2900;
const MODEL_FACTOR = 1.1;
/** Kept free for Windows and other programs before a model counts as fitting comfortably. */
export const HEADROOM_MB = 1536;

/** Estimated graphics memory for the whole translator with [llm] (the projector only when the page is shown). */
export function neededMb(llm: LlmChoice, vision: boolean): number | null {
  if (llm.sizeMb === null) return null;
  const files = llm.sizeMb + (vision && llm.vision ? llm.projectorMb ?? 0 : 0);
  return Math.round(files * MODEL_FACTOR + PIPELINE_MB);
}

export type Fit = "fits" | "tight" | "too_big" | "unknown";

/** Fits with room to spare, fits only if little else uses the GPU, or doesn't fit (Windows then borrows slow system memory). */
export function fitFor(need: number | null, gpu: Gpu | null): Fit {
  if (need === null || !gpu) return "unknown";
  if (need <= gpu.vramMb - HEADROOM_MB) return "fits";
  return need <= gpu.vramMb ? "tight" : "too_big";
}

/** Best translation first (bigger models of the newer families translate better); uncensored variants aren't recommended. */
export const QUALITY_ORDER = [
  "gemma4-31b-it", "gemma4-26b-a4b-it", "qwen3.8-27b", "qwen3.6-27b", "qwen3.6-35b-a3b", "qwen3.5-27b", "qwen3.5-35b-a3b",
  "gemma4-12b-it", "qwen3.5-9b", "ministral-3-8b-instruct", "gemma4-e4b-it", "qwen3.5-4b", "gemma4-e2b-it", "qwen3.5-2b",
  "lfm2.5-1.2b-instruct", "qwen3.5-0.8b",
];
/** Without an NVIDIA GPU everything runs on the processor; a small model keeps pages bearable. */
const CPU_MODEL = "gemma4-e2b-it";

/** The best model that fits comfortably (else the best that fits at all), or a small one without a GPU; null if none fits. */
export function recommendedModel(llms: LlmChoice[], gpu: Gpu | null, vision: boolean): LlmChoice | null {
  const known = (id: string) => llms.find((m) => m.id === id);
  if (!gpu) return known(CPU_MODEL) ?? null;
  for (const wanted of ["fits", "tight"] as const) {
    for (const id of QUALITY_ORDER) {
      const model = known(id);
      if (model && fitFor(neededMb(model, vision), gpu) === wanted) return model;
    }
  }
  return null;
}

const gb = (mb: number) => `${(mb / 1024).toFixed(mb < 10_240 ? 1 : 0)} GB`;
const FIT_LABEL: Record<Fit, string> = { fits: "fits your GPU", tight: "tight on your GPU", too_big: "too big for your GPU", unknown: "" };

/** "Gemma 4 12B Instruct — fits your GPU (about 10 GB)" for the model list. */
export function modelLabel(llm: LlmChoice, gpu: Gpu | null, vision: boolean): string {
  const need = neededMb(llm, vision);
  if (need === null) return llm.name;
  const fit = fitFor(need, gpu);
  return fit === "unknown" ? `${llm.name} (about ${gb(need)})` : `${llm.name} — ${FIT_LABEL[fit]} (about ${gb(need)})`;
}

/** "Anime Ace, Comic Sans MS" or one per line → trimmed, de-duplicated names. */
export function parseFonts(text: string): string[] {
  const out: string[] = [];
  for (const part of text.split(/[,\n]/)) {
    const name = part.trim();
    if (name && !out.includes(name)) out.push(name);
  }
  return out;
}

/** An optional number field: "" means the model's default (null); anything else must be a number. */
export function parseOptionalNumber(text: string, integer = false): number | null | undefined {
  const trimmed = text.trim();
  if (!trimmed) return null;
  const value = Number(trimmed);
  if (!Number.isFinite(value) || (integer && !Number.isInteger(value))) return undefined;
  return value;
}

export class SettingsError extends Error {
  constructor(message: string, readonly missing = false) { super(message); this.name = "SettingsError"; }
}

async function call(fetcher: FetchLike, init?: RequestInit): Promise<SettingsInfo> {
  let response: Response;
  try {
    response = await fetcher(`${TRANSLATOR_URL}/settings`, { cache: "no-store", ...init });
  } catch {
    throw new SettingsError("The translator isn't running on this PC.");
  }
  // Translator 0.1.0 has no settings.
  if (response.status === 404) throw new SettingsError("This translator version has no settings. Download the latest one below.", true);
  const body = await response.json().catch(() => null) as { message?: unknown } | null;
  if (!response.ok) throw new SettingsError(typeof body?.message === "string" ? body.message : "The translator refused these settings.");
  const info = parseSettingsInfo(body);
  if (!info) throw new SettingsError("The translator sent an unexpected answer.");
  return info;
}

export const fetchSettings = (fetcher: FetchLike = (u, i) => fetch(u, i)) => call(fetcher);

export const saveSettings = (settings: TranslatorSettings, fetcher: FetchLike = (u, i) => fetch(u, i)) =>
  call(fetcher, { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(settings) });

/** The settings card for /translator. Loads when shown; [onSaved] runs after a save (e.g. to refresh /health). */
export function settingsCard(onSaved: () => void = () => undefined, fetcher?: FetchLike): HTMLElement {
  const card = h("section", { class: "card", "aria-labelledby": "tr-settings-title" });
  const message = h("p", { class: "small", role: "status" });
  const say = (text: string, error = false) => { message.textContent = text; message.className = error ? "small tr-error" : "small muted"; };

  const select = (id: string, list: Choice[], value: string) => h("select", { id, class: "tr-select" },
    ...list.map((c) => h("option", { value: c.id, selected: c.id === value }, c.name)));
  const field = (label: string, control: HTMLElement, hint?: string) => h("div", { class: "tr-field" },
    h("label", { for: control.id }, label), control, hint ? h("p", { class: "muted small tr-hint" }, hint) : null);
  const numberInput = (id: string, value: number | null, placeholder: string, step: string) =>
    h("input", { id, type: "number", inputmode: "decimal", step, value: value === null ? "" : String(value), placeholder }) as HTMLInputElement;

  const draw = (info: SettingsInfo) => {
    const s = info.settings, o = info.options;
    const llm = select("tr-llm", o.llm, s.llm) as HTMLSelectElement;
    const gpuNote = h("p", { class: "small tr-gpu" });
    const ocr = select("tr-ocr", o.ocr, s.ocr) as HTMLSelectElement;
    const inpainting = select("tr-inpainting", o.inpainting, s.inpainting) as HTMLSelectElement;
    const instructions = h("textarea", { id: "tr-instructions", rows: "4", maxlength: String(o.maxInstructions), class: "tr-textarea",
      placeholder: "For example: Keep Japanese honorifics like -san and -senpai. Use casual, natural English." }, s.instructions) as HTMLTextAreaElement;
    const temperature = numberInput("tr-temperature", s.temperature, "model default", "0.05");
    const topP = numberInput("tr-top-p", s.topP, "model default", "0.05");
    const topK = numberInput("tr-top-k", s.topK, "model default", "1");
    const maxTokens = numberInput("tr-max-tokens", s.maxTokens, "model default", "50");
    const repeatPenalty = numberInput("tr-repeat-penalty", s.repeatPenalty, "model default", "0.05");
    const reasoning = h("input", { id: "tr-reasoning", type: "checkbox", checked: s.reasoning }) as HTMLInputElement;
    const vision = h("input", { id: "tr-vision", type: "checkbox", checked: s.vision }) as HTMLInputElement;
    const fonts = h("input", { id: "tr-fonts", type: "text", value: s.fonts.join(", "), placeholder: "Koharu's default fonts" }) as HTMLInputElement;
    const modelNote = h("p", { class: "muted small tr-hint" });
    const describeModel = () => {
      const visionOn = vision.checked;
      for (const option of Array.from(llm.options)) {
        const model = o.llm.find((m) => m.id === option.value);
        if (model) option.textContent = modelLabel(model, o.gpu, visionOn);
      }
      const best = recommendedModel(o.llm, o.gpu, visionOn);
      replace(gpuNote,
        o.gpu ? `Your GPU: ${o.gpu.name.replace(/^NVIDIA /, "")}, ${gb(o.gpu.vramMb)}. ` : "No NVIDIA GPU found, so translation runs on the processor (slow). ",
        best ? `Recommended: ${best.name}. ` : "None of the models fits comfortably; the smallest ones may still work, slowly. ",
        best && best.id !== llm.value ? h("button", { class: "secondary tr-inline", onclick: () => { llm.value = best.id; describeModel(); } }, "Use it") : null);
      const chosen = o.llm.find((m) => m.id === llm.value);
      if (!chosen) { modelNote.textContent = ""; return; }
      const need = neededMb(chosen, visionOn);
      const fit = fitFor(need, o.gpu);
      const parts = [
        [chosen.vision ? "can see the page" : "text only", chosen.reasoning ? "can reason" : null].filter(Boolean).join(", "),
        need !== null ? `needs about ${gb(need)} of graphics memory with the rest of the translator` : null,
        chosen.sizeMb !== null ? `downloads about ${gb(chosen.sizeMb + (chosen.vision ? chosen.projectorMb ?? 0 : 0))} the first time` : null,
      ].filter(Boolean);
      modelNote.textContent = parts.join("; ") + "." + (fit === "too_big"
        ? " That's more than your GPU has: Windows then borrows system memory, which makes every page much slower."
        : fit === "tight" ? " It fits only if few other programs (games, video) use the GPU." : "");
      modelNote.className = fit === "too_big" ? "small tr-hint tr-error" : "muted small tr-hint";
    };
    llm.addEventListener("change", describeModel);
    vision.addEventListener("change", describeModel);
    describeModel();

    const read = (): TranslatorSettings | string => {
      const numbers = {
        temperature: parseOptionalNumber(temperature.value), topP: parseOptionalNumber(topP.value),
        topK: parseOptionalNumber(topK.value, true), maxTokens: parseOptionalNumber(maxTokens.value, true),
        repeatPenalty: parseOptionalNumber(repeatPenalty.value),
      };
      const bad = Object.entries(numbers).find(([, v]) => v === undefined);
      if (bad) return "Enter a number, or leave the field empty for the model's default.";
      const fontList = parseFonts(fonts.value);
      if (fontList.length > o.maxFonts) return `At most ${o.maxFonts} fonts.`;
      return {
        ocr: ocr.value, inpainting: inpainting.value, llm: llm.value, instructions: instructions.value.trim(),
        temperature: numbers.temperature ?? null, topP: numbers.topP ?? null, topK: numbers.topK ?? null,
        maxTokens: numbers.maxTokens ?? null, repeatPenalty: numbers.repeatPenalty ?? null,
        reasoning: reasoning.checked, vision: vision.checked, fonts: fontList,
      };
    };
    const save = async (settings: TranslatorSettings | string, button: HTMLButtonElement, done: string) => {
      if (typeof settings === "string") { say(settings, true); return; }
      button.disabled = true;
      say("Saving…");
      try {
        const saved = await saveSettings(settings, fetcher);
        draw(saved);
        say(done);
        onSaved();
      } catch (error) {
        say((error as Error).message, true);
      } finally {
        button.disabled = false;
      }
    };
    const saveButton = h("button", {}, "Save") as HTMLButtonElement;
    saveButton.addEventListener("click", () => save(read(), saveButton, "Saved. The next translated page uses these settings."));
    const resetButton = h("button", { class: "secondary" }, "Reset to defaults") as HTMLButtonElement;
    resetButton.addEventListener("click", () => save(info.defaults, resetButton, "Back to the default settings."));

    replace(card,
      h("h3", { id: "tr-settings-title" }, "Translator settings"),
      h("p", { class: "muted small" }, "The same choices as Koharu's own settings. They're saved on this PC and apply from the next translated page (for the website and your phone). Pages already translated in this browser are translated again with the new settings."),
      h("h4", {}, "Translation"),
      gpuNote,
      field("Translation model", llm), modelNote,
      field("Instructions for the model", instructions, `Optional, up to ${o.maxInstructions} characters: style, names to keep, tone.`),
      h("details", { class: "tr-advanced" },
        h("summary", {}, "Generation options"),
        h("p", { class: "muted small" }, "Leave a field empty to use the model's own default."),
        h("div", { class: "tr-grid" },
          field("Temperature (0–2)", temperature), field("Top P (0–1)", topP), field("Top K", topK),
          field("Max tokens", maxTokens), field("Repeat penalty", repeatPenalty)),
        h("label", { class: "tr-check" }, reasoning, " Let the model reason before answering (slower, often better)"),
        h("label", { class: "tr-check" }, vision, " Show the page to models that can see (helps with context)")),
      h("h4", {}, "Pipeline"),
      field("Text recognition (OCR)", ocr, "Manga OCR is Japanese only; PaddleOCR-VL reads Japanese, Korean and Chinese."),
      field("Text removal (inpainting)", inpainting),
      h("h4", {}, "Typesetting"),
      field("Fonts", fonts, "Font families installed on this PC, separated by commas, tried in order. Koharu's defaults stay as the fallback."),
      h("div", { class: "row" }, saveButton, resetButton),
      message);
  };

  replace(card, h("h3", { id: "tr-settings-title" }, "Translator settings"), h("p", { class: "muted small" }, "Loading…"));
  fetchSettings(fetcher).then(draw).catch((error: Error) => {
    replace(card, h("h3", { id: "tr-settings-title" }, "Translator settings"), h("p", { class: "small" }, error.message));
  });
  return card;
}
