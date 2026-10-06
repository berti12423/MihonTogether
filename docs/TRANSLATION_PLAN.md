# In-reader translation for raws: research and plan

Status (2026-10-02): T0 spike done ([results](translation-spike.md)). **T1–T3 built**: `mihon.feature.translate` (own Gemini key over the Interactions REST API, slicing and seam merge, local cache, baked rendering, reader menu "Translate pages" / "Translation settings", status chip). Unit-tested (19 tests, plus one live test that's off by default), and checked on the emulator in paged and long-strip mode with cached translations. Not yet tried by Berti with his key on real chapters.

Update (2026-10-03): Berti's Gemini free quota ran out right away (long strips are sliced, so one webtoon page is several requests). Translation now offers four engines in Translation settings, with **On this phone** as the default:

| Engine | How | Cost | Quality |
| --- | --- | --- | --- |
| On this phone (default) | ML Kit OCR with bundled Korean, Japanese and Chinese models, then ML Kit on-device translation (about 30 MB language pack, downloaded once). Works without Google Play services and in incognito mode. | Free, no limits, offline after the first download | Rough |
| DeepL | The same OCR, then only the text goes to DeepL with the reader's own key (free keys end in `:fx`). Earlier lines go along as untranslated `context`. | DeepL API Free: 500,000 characters a month | Good |
| Papago | The same OCR, then Naver Cloud Papago with the reader's Client ID and Secret. | **Paid per character**: Naver Cloud has had no free plan since February 2024 | Best for Korean |
| Gemini | The vision model, as before. | Small daily free quota | Best overall (bubbles, SFX, context) |

Code: `OcrTranslationEngine.kt` (grouping lines into bubbles, script checks that drop Latin watermarks, language detection that's remembered per reader session), `MlKitTranslation.kt`, `CloudTextTranslators.kt`. Verified: 9 unit tests, plus the emulator (no Play services) translating the Korean test chapter fully on-device. DeepL and Papago are tested only against their documented request and response formats, not live.

Known limits of v1: boxes the model places too tight leave strokes of the original visible (refine later with OCR or bubble detection), SFX are never drawn by default, the whole chapter isn't pre-translated (current page plus the next 2), and a pager page reloads (zoom resets) when its translation arrives.

Goal: read raw manga, manhwa and manhua (Japanese, Korean, Chinese) in your own language inside the reader. One tap translates the current page. It should feel like a screen translator, but work on the page image itself.

## What exists already (prior art)

| Project | Approach | Takeaways |
| --- | --- | --- |
| [Yakuyomi](https://github.com/joyeli/Yakuyomi) (Mihon fork, GPL-3.0) | On-device detection (DBNet), OCR (48px CTC) and inpainting (AOT-GAN) with NCNN/ONNX, about 250 MB of models. A cloud LLM translates (bring your own key, DeepSeek by default). The result is typeset back into the cleaned bubble. | Best image quality, but heavy: about 2 s/page of on-device work plus a 250 MB download. They measured about $0.001 per chapter with DeepSeek. Typesetting into the image is the right rendering model. GPL, so we can study it but can't copy code into our Apache-2.0 fork. |
| [TachiyomiAT](https://github.com/mannu691/TachiyomiAT) (Mihon fork, Apache-2.0) | Engines: ML Kit on device, Google Translate (web), Gemini, OpenRouter (user keys). Translates downloaded chapters, with optional auto-translate on download. | Several engines behind one setting works. Same license as ours. |
| [LegendZ69/mihon#1](https://github.com/LegendZ69/mihon/issues/1) | PaddleOCR (ONNX) plus provider queues and reader overlays. | Overlays have to track crop, rotate, zoom and spread transitions, which is a lot of work. Baking into the image avoids it. |
| Madomi, XianScan | Commercial app, or a local server streaming into Mihon. | Confirms the demand. A server pipeline is overkill for us. |

## Screen translators (researched 2026-10-02)

Overlay apps that translate whatever is on screen. Two families exist: screen capture plus a floating overlay (works in any app), and in-app integration (our approach).

| App | Platform, license | How it works | Worth taking |
| --- | --- | --- | --- |
| [ALST Mobile](https://github.com/navidseyedain/ALSTMobile) | Android, MIT | MediaProjection capture (throttled to 300 ms). **One Gemini call per screen frame** returns JSON blocks with `text`, `translated`, `lines_count` and `box_2d` (0–1000), at temperature 0.1. Compose overlay through WindowManager draws dark boxes over the text. Offline: ML Kit OCR and translate (Latin only). User key stored in DataStore. | Same approach as our spike, validated in a shipped app. Its frames are screen-sized, so it never meets the tall-strip box drift. `lines_count` helps size the font. It uses the deprecated `com.google.ai.client.generativeai` SDK. |
| [overlay-translator](https://github.com/ciddwd/overlay-translator) (Screen Translator) | Android, **Apache-2.0** | MediaProjection or Shizuku capture. OCR: ML Kit, PaddleOCR v5/v6, **manga-ocr ONNX (~140 MB) for vertical Japanese**, cloud OCRs. Translators: OpenAI/Claude-compatible, DeepL, ML Kit, **offline LLMs through llama.cpp** (Sakura 1.5B, Hy-MT2 1.8B GGUF). Manga mode: **bubble detection, erases the text and repairs the background, typesets along the bubble contour**, with a rectangle fallback. Vertical/RTL handling, furigana removal. Live modes: "stable-wait" (translate once motion stops), interval, duplicate-frame skip. | The richest manga feature set, and **license-compatible**, so its bubble-detection and typesetting code can be adapted with attribution. "Stable-wait" fits webtoon scrolling. |
| [Screen Translate](https://github.com/AidanPark/android-screen-translator) (screen-trans) | Android, PolyForm Noncommercial (study only, no code reuse) | MediaProjection. ML Kit for Latin/CJK, PP-OCRv5 ONNX for Arabic/Cyrillic/Thai, "Auto" picks the recognizer. A custom grouping algorithm builds words, lines, sentences and paragraphs from position, spacing and font size (RTL aware). Five engines behind one interface: Google Translate (no key), DeepL, OpenAI, Gemini, Claude (own keys). Drag a pointer; stale work is cancelled when it moves. | Pluggable engines behind one interface, like our plan. Cancel stale work when the view moves. |
| [Click'n'Translate](https://github.com/jabrailkhalil/clickntranslate) | Windows/Linux, GPL-3.0 | Four OCRs (Windows OCR, Tesseract, RapidOCR, EasyOCR). Online translators (Google, MyMemory, Lingva, LibreTranslate) or offline (Argos, Hy-MT). "Dynamic translation" re-translates a region only when its text changes. | Re-translate only on change. Hy-MT shows up again as the go-to offline model. |
| [WatchdOcr](https://github.com/h1fired/watchdocr-overlay) | Windows, Python/Qt | Captures a region, Windows OCR, an **unofficial** Google Translate endpoint, HUD overlay, one-shot or live mode. | Unofficial endpoints are fragile and against the terms of service, so we avoid them. |
| [OCR-Translator](https://github.com/tomkam1702/OCR-Translator) | Windows, proprietary | Vision LLMs as OCR (Gemini, Gemma, Qwen). A **sliding context window of the last 5 subtitles** keeps names and pronouns consistent. Two-tier cache (memory LRU plus disk). Gemma for OCR is about 3× cheaper (about $0.16/h). | Pass the previous lines as context. Two-tier cache. |
| [OverlAI](https://github.com/Labushuya/overlai) | Android, sideload-only | A general bring-your-own-key AI assistant overlay: translate, summarize, vision/OCR, search. Requests go straight to the provider. Not on Play because overlay and screen-reading conflict with Play policies. | An in-reader feature avoids the overlay permission and the policy trouble. |
| OverlayTranslate, OCR-Translator variants | Desktop | Region capture, OCR (RapidOCR/EasyOCR/PaddleOCR), translate, overlay. | Nothing new for us. |

**What this changes in our plan:**
1. **Stay in the reader** instead of screen capture. We have the full-resolution page, translations stay attached while scrolling, results can be cached per page, and no capture permission or overlay policy is involved. A system-wide screen mode could come later as a separate feature.
2. **Viewport-sized slices** for long strips (ALST shows screen-shaped images give reliable boxes, which matches our spike).
3. Add **`lines_count`** (or a font-size hint) to the response schema for typesetting. Use temperature 0.1.
4. **Context window:** send the previous slice's or page's dialogue (last 5–10 lines) as text with each request, so names, pronouns and tone stay consistent. It's cheap in tokens.
5. **Rendering roadmap:** start with covering the text with the sampled bubble colour (ALST level). Later, adapt overlay-translator's Apache-2.0 bubble detection, text erasing and contour typesetting.
6. **Long-strip UX:** "stable-wait": translate the slices around the viewport once scrolling settles, then prefetch ahead. Cancel work that scrolls out of range.
7. **Offline option (later):** Hy-MT2 1.8B or Sakura 1.5B GGUF through llama.cpp (several apps use these), or manga-ocr ONNX for Japanese. Each is a large download (about 140 MB to 1 GB), so make it optional.
8. **No unofficial endpoints** (the keyless Google Translate web API): fragile and against the terms of service.

## Building blocks

### Text detection and OCR
- **ML Kit Text Recognition v2** (on device, free, offline): Chinese, Japanese, Korean, Latin and Devanagari recognizers ([docs](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)). It does fine on horizontal manhwa text. Vertical Japanese needs orientation handling: about 40% accuracy naively, about 85% with proper vertical handling on clean scans ([report](https://dev.to/joe_wang_6a4a3e51566e8b52/how-to-handle-vertical-japanese-text-in-android-ocr-1mj9)). It needs at least 16 px per character, so tiny SFX and furigana get lost. It returns lines and blocks, not speech bubbles, so we'd need our own grouping.
- **Manga-specific models** (comic-text-detector, manga-ocr, PaddleOCR): better on manga, but they mean 100–400 MB of ONNX/NCNN models plus native runtimes. This is Yakuyomi's path.
- **Gemini vision** (cloud): one request returns every text region as `box_2d [ymin, xmin, ymax, xmax]` normalized to 0–1000 ([docs](https://ai.google.dev/gemini-api/docs/image-understanding)), plus the source text and translation as structured JSON. It reads vertical text, SFX and context (who speaks, honorifics) in one pass.

### Translation
- **ML Kit Translation** (on device): about 30 MB per language, ja/ko/zh ↔ 50+ languages, free and offline ([docs](https://developers.google.com/ml-kit/language/translation/android)). Sentence-level, literal and context-free: fine for getting the gist, poor for dialogue.
- **LLM** (Gemini, or anything OpenAI-compatible): much better dialogue, and it can take the page or chapter as context. Current models: `gemini-3.5-flash-lite` / `gemini-3.1-flash-lite` for low cost, `gemini-3.8-flash` for quality. The `gemini-api-dev` skill says the 2.x models are deprecated.

### Papago (Naver)
- Strong Korean, with good Japanese. A paid Naver Cloud Platform service: billed per 1M characters, and the Image Translation API is billed per call plus characters. There's no free tier and you need an NCP account with a card.
- The [Image Translation API](https://api.ncloud-docs.com/docs/en/ai-naver-papagoimagetranslation) has `image-to-text` (OCR plus translation) and `image-to-image` (rendered result) endpoints. Images are capped at 20 MB and **1960×1960 px**, so long strips need slicing. The docs list Korean, English and Japanese, so Chinese support is uncertain.
- The Papago brand notice and link must be shown wherever its translations appear.
- Fits as an optional own-key engine (NCP key ID and secret), or as a text translator after Gemini or ML Kit OCR.

### Cost (Gemini Developer API, [pricing](https://ai.google.dev/gemini-api/docs/pricing), checked 2026-10-02)
- Flash-Lite 3.1: $0.25 per 1M input tokens and $1.50 per 1M output tokens. A page is roughly 1–2k image tokens in and about 0.5k tokens out, so **about $0.001 per page**: around $0.02 per 20-page chapter, or about $1 per 1,000 pages.
- **Free tier:** every listed model has one with rate limits. On the free tier, Google **uses the content to improve its products**; the paid tier doesn't.
- **Our project:** it's on Blaze with a $1 budget alert, so translating on Berti's bill needs strict per-user daily quotas. Each user bringing their own free AI Studio key costs us nothing.

## Recommended design

```
ReaderPage ─► page.stream ─► TranslationService ─► cache hit? ─► translated PNG stream ─► existing viewers
                                   │ miss
                                   ▼
                     TranslationEngine (interface)
                     ├─ GeminiEngine (user key, direct; or our callable proxy)
                     └─ OnDeviceEngine (ML Kit OCR + ML Kit Translate)
                                   │ PageTranslation { blocks: [box 0..1, source, text, vertical, bg] }
                                   ▼
                     PageRenderer (Canvas): fill each block with its sampled background,
                     auto-fit the translated text (StaticLayout), keep the original for toggling
```

1. **Bake, don't overlay.** Render the translation into a copy of the page image and hand it to the viewer through the existing `page.stream` hook. The paged viewer (`PagerPageHolder.setImage`), long-strip viewer (`WebtoonPageHolder.setImage`) and WebGPU viewer all read `page.stream`, so zoom, pan, crop borders, double-page split and long-strip scrolling keep working without any coordinate tracking. Toggling back to the original just swaps the stream.
2. **The engine output is data** (`PageTranslation` JSON with normalized boxes). It's cached on disk under a key of `sha256(image bytes) + engine + model + target language + prompt version`, so re-opening a chapter costs nothing. The rendered PNG is also cached (an LRU cache with a size limit set in settings).
3. **Engines:**
   - **Gemini, bring your own key (default):** each user pastes a free AI Studio key, stored with Android Keystore-backed encryption. One structured-output request per page: the image downscaled to 1600 px on its long side (JPEG q85), plus the source and target language, returns blocks. No cost to anyone; free-tier terms apply.
   - **Gemini through our server (optional, later):** a `translatePage` callable. The key lives in Secret Manager, calls are capped by a per-user daily page limit (reusing `dailyQuota`), and App Check gets switched on first. The app stays usable without it.
   - **Papago (optional, own Naver Cloud key):** `image-to-text` for Korean and Japanese, or text-only translation after OCR. Shows the required Papago attribution.
   - **On device (offline):** ML Kit OCR (script picked per language), a heuristic that groups lines into bubbles, and ML Kit Translate. Lower quality, but no network and no key.
4. **Long-strip (webtoon) pages:** slice tall images into about 1,600 px segments with about 10% overlap, translate each, and merge blocks that overlap at the seams.
5. **Reader UX:** a **Translate** button in the reader menu, plus a per-title setting (source language auto/ja/ko/zh, target language, engine). It translates the visible page first, then prefetches the next 3 pages. A small progress dot sits on pages still being translated. Long-press a block to see the original text. An "Original" toggle. An option to translate whole chapters on download.
6. **Product contracts:**
   - Translation is local and opt-in.
   - Page images leave the device only for a cloud engine the user picked, with that stated in the setting.
   - Nothing about translations goes to Firestore.
   - Respect incognito: no cloud requests for incognito sources unless the user allows it.
   - Never upload pages to our servers without consent.
7. **Read together (later):** the room could share the host's `PageTranslation` JSON for the current page, so the guest doesn't pay or wait for a second translation. That's a separate change with its own privacy review.

## Phases

| Phase | Scope | Verification |
| --- | --- | --- |
| T0 Spike (1–2 days) | Node script in `tools/translate-spike/` runs Gemini (Flash-Lite 3.1 vs Flash 3.8) on about 20 sample raw pages (JP vertical, KR webtoon, CN). Measure box accuracy, translation quality, tokens and latency. A small Android test screen runs ML Kit OCR on the same pages. | A report in `docs/translation-spike.md` with numbers and screenshots, used to pick the default model and confirm the on-device engine is worth it. |
| T1 Core pipeline | `PageTranslation` model, cache, `PageRenderer`, `TranslationService` (owned coroutine scope, cancellable, prefetch queue), stream swap in the pager, long-strip and WebGPU holders, reader toggle and settings. A fake engine for tests. | Unit tests: cache keys, box mapping, text fitting, cancellation. Renderer snapshot tests. Device check with the fake engine. |
| T2 Gemini engine (your own key) | Structured-output schema, prompt (reading order, SFX on/off, honorifics), image downscaling, retries and backoff, error messages (bad key, quota). | Tests against a mocked HTTP server. Manual run on the spike pages. |
| T3 Long-strip slicing | Slice and merge for tall pages, plus prefetch along the scroll. | Unit tests for the seam merge. Device test on a webtoon. |
| T4 On-device engine | ML Kit OCR + Translate, line-to-bubble grouping, model download manager (Wi-Fi only). | Accuracy compared with the T0 numbers. Offline device test. |
| T5 Server engine (optional) | `translatePage` callable, Secret Manager key, per-user quota, App Check enforcement. | Emulator tests with a fake model, rules and quota tests. Needs an explicit OK from Berti before deploying (it costs money). |
| T6 Polish | Fonts, edit a block, translate on download, sharing translations in Read together. | Per feature. |

## Decisions (Berti, 2026-10-02)
1. Default engine: **own Gemini key**. Also try **Papago** (own key) as an alternative, especially for Korean.
2. Languages: **Korean, Japanese and Chinese into English**.
3. SFX: **left alone** by default (the spike still detects them as `kind: "sfx"` so we can measure).
4. **Spike first** (T0): `tools/translate-spike/`. Berti adds a free AI Studio key and 10–20 raw pages; the tool writes `results/report.html`. Papago joins the spike once an NCP key exists.

## Skills
Installed in `.claude/skills/` (see `docs/SKILLS.md`): `firebase-ai-logic-basics` (Firebase, for the optional server engine and App Check) and `gemini-api-dev` (Google, current model names and the `@google/genai` SDK). Existing: `android-compose-coroutines` for the service and reader state.
