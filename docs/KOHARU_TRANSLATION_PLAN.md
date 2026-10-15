# Plan: Koharu translation in the website reader

Status: 2026-10-11, both sides built and merged on `feat/koharu-translate`: the translator in `tools/koharu-bridge` (warm pages about 4.3 s on an RTX 5070 Ti) and the website reader's Translate plus `/translator`. Real browser test pending.

## Goal

In the website's My library reader, a **Translate** button turns raw pages (ja/ko/zh) into English pages, the way Koharu does: detect bubbles, OCR, translate with a local LLM, erase the original text (inpainting), typeset. Everything runs on the user's own PC. No keys, no cloud, nothing leaves the computer.

## Findings (Koharu, checked 2026-10-11)

- `koharu-rs/koharu` (MIT or Apache-2.0): a Rust/Tauri desktop app for Windows, macOS and Linux. **No HTTP API, server, MCP or CLI** in the app itself.
- But the workspace has `crates/koharu-pipeline` ("in-process scene-processing workflow") with a dev binary `src/bin/run.rs`: `--input`/`--output` image, plus `--detection`, `--ocr`, `--inpainting`, `--llm` and `--target-language`. Defaults:
  - **Detection:** `koharu-layout-rfdetr-seg-2xl`.
  - **OCR:** `paddleocr-vl-1.6`, or `manga-ocr`, `baberu-ocr`, `hayai-ocr`.
  - **Inpainting:** `lama`, or `aot-inpainting`, `flux2-klein`, `rorem-mixed`.
  - **Translation:** local LLM `gemma4-12b-it`, through `Provider::Local` (llama.cpp GGUF).
  - It uses libtorch (`koharu-torch-sys`) and llama.cpp (`koharu-llama-sys`).
- Build prerequisites from the README: Rust 1.97.1, Bun 1.3.14, LLVM 22.1.8. Models download from Hugging Face on first use.
- Running `run` once per page reloads every model, which is too slow for reading. We need a long-running process that keeps the models warm.

## Architecture

```
Phone ──WebRTC──► Browser (My library reader) ──HTTP 127.0.0.1:47823──► Mihon Together Translator (PC)
                     page bytes it already has      POST /translate          koharu-pipeline (models kept loaded)
                     ◄──────────── translated PNG ◄─────────────────────────
```

- **Mihon Together Translator** (`tools/koharu-bridge`, Rust): depends on `koharu-pipeline`, `koharu-scene`, `koharu-renderer` and `koharu-translator`, with a git dependency pinned to a commit. Mirrors what `bin/run.rs` does, but serves HTTP:
  - `GET /health`: `{ ready, models: {...}, device: "cuda"|"cpu", version }`, plus download progress while models are fetched on first start.
  - `POST /translate` (body: image bytes; query: `target=en-US`, `source=auto|ja|ko|zh`): returns `image/png` (the translated page). With `?format=json` it returns the text blocks (boxes, source text, translation) instead.
  - One GPU job at a time (queue). The client cancels by aborting the request.
  - Binds **127.0.0.1 only**. Accepts only `Origin: https://mihontogether.web.app` (and `http://localhost:5050` for development); other origins get 403. Answers Chrome's Private Network Access preflight with `Access-Control-Allow-Private-Network: true`.
  - A small console or tray window: status, current model, pages done.
- **Website:**
  - **Reader:** a Translate toggle. On load it detects the translator at `http://127.0.0.1:47823/health`. When on, it translates the current page first, then the next 2, and shows a small "translating" badge. It caches results in IndexedDB keyed by page-bytes hash, model and target, and keeps an Original/Translated toggle.
  - **No translator found:** a "Get the translator" page with the download and steps.
  - **CSP:** add `http://127.0.0.1:47823` to `connect-src` (https pages may call `http://127.0.0.1`: Chrome and Firefox treat loopback as trustworthy).
- Nothing about translation touches Firebase or the phone.

## Phases

1. **Test run (me, on Berti's PC):** RTX 5070 Ti 16 GB, Ryzen 7 7800X3D, 32 GB RAM. Build Koharu from source and run `koharu-pipeline`'s `run` on 5–10 raw pages (ja vertical, ko webtoon, zh). Measure seconds per page with warm and cold models, VRAM, quality and the size of the CUDA/libtorch runtime files. Then pick the defaults: Gemma 4 12B vs e4b, PaddleOCR-VL vs manga-ocr, LaMa vs AOT. Write the results to `docs/koharu-spike.md`.
2. **Two agents in parallel (worktrees, each loads the mihon-together skill):**
   - **Translator agent:** `tools/koharu-bridge`, its HTTP API, queue, origin checks, model download with progress, Windows build script.
   - **Web agent:** the reader Translate toggle, detection, prefetch, IndexedDB cache, the setup page, the CSP change, tests with a fake translator.
3. **Integration and release:** real test on Berti's PC, then a Windows zip on GitHub Releases, plus the README, the privacy policy (translation is fully local) and patch notes. macOS and Linux later.

## Limits and risks

- Needs a capable PC: an NVIDIA GPU is best, CPU works but is slow. Phones don't get it.
- The first start downloads several GB of models. The CUDA runtime files may make the download 1–3 GB (the test run will tell).
- Building against an internal Koharu crate means its API can change, so pin a commit and update deliberately. Consider proposing a headless `serve` mode upstream to Koharu later, so users could just install Koharu itself.
- Model licenses: check each Hugging Face model's license before redistributing anything. We don't redistribute weights; the translator downloads them like Koharu does.

## Translator API v1 (contract for the bridge and the website)

Base URL `http://127.0.0.1:47823`. The bridge listens on 127.0.0.1 only.

**Origins.** Allowed browser origins are `https://mihontogether.web.app`, `https://mihontogether.firebaseapp.com`, `http://localhost:5050` and `http://127.0.0.1:5050` (development).
- A request with an `Origin` header that isn't on the list gets `403`.
- A request with no `Origin` (local tools such as curl) is allowed.
- For allowed origins, responses carry `Access-Control-Allow-Origin: <origin>` and `Vary: Origin`.
- `OPTIONS` preflight answers `204` with `Access-Control-Allow-Methods: GET, POST`, `Access-Control-Allow-Headers: Content-Type`, `Access-Control-Allow-Private-Network: true` and `Access-Control-Max-Age: 600`.

**`GET /health`** returns `200` with this JSON:
```json
{ "name": "mihon-together-translator", "api": 1, "version": "0.1.0",
  "state": "starting" | "downloading" | "loading" | "ready" | "error",
  "progress": { "label": "Downloading gemma4-12b-it", "done": 123, "total": 456 } | null,
  "error": null | "message",
  "device": "cuda" | "cpu" | "...", "models": { "detection": "...", "ocr": "...", "inpainting": "...", "llm": "..." },
  "queue": 0, "lastPageMs": 8123 | null }
```

**`POST /translate?target=en-US`**
- Body: the raw page image bytes (`image/jpeg`, `png`, `webp`, `gif` or `avif`), at most 30 MB.
- `200` returns `image/png`, the translated page at the same pixel size, with header `X-Translate-Ms: <ms>`.
- Errors are JSON `{ "error": code, "message": text }`:
  - `400 bad_image`
  - `413 too_large`
  - `503 not_ready` (still starting, downloading or loading)
  - `500 failed`
- Jobs run one at a time, first in, first out. When the client aborts (closes the connection), the bridge drops the job if it hasn't started yet.
- Pages with no text come back unchanged (still a PNG).

**Pinned Koharu:** `koharu-rs/koharu` at the commit below. Models and options are Koharu's defaults (see `docs/koharu-spike.md`).
Koharu commit: 2c78c0cd6eeec4dbf607f0a22e3a7e1f932b6ff3

### Settings (2026-10-14)

`GET /settings` and `PUT /settings` on 127.0.0.1:47823 (same Host/Origin checks and CORS as the rest; the preflight allows `PUT`; not on the phone port). The body is `{ settings, defaults, options }`; `settings` holds `ocr`, `inpainting`, `llm` (a local model id from Koharu's catalog), `instructions` (≤ 2000 characters), `temperature` 0–2, `topP` 0.01–1, `topK` 1–1000, `maxTokens` 16–8192, `repeatPenalty` 0.5–2 (each `null` for the model's default), `reasoning`, `vision` and `fonts` (≤ 8 family names, tried before Koharu's defaults). `PUT` takes the full object, validates it (`400 bad_settings`), saves `settings.json` next to `config.json` and applies it before the next page. `/health`'s `models` reflect the chosen models plus `settings` (8 hex characters), which the website's page cache key includes. The website's /translator page shows the form while the translator is ready; translator 0.1.0 answers 404 and the page asks for an update. `options.gpu` is the NVIDIA GPU from `nvidia-smi` (`{ name, vramMb }`, or null) and each `options.llm` entry carries `sizeMb` and `projectorMb` (`model_sizes.rs`, read from Hugging Face at the pinned revisions); the website estimates graphics memory as 1.1 x the files plus 2.9 GB (measured: Gemma 4 12B used 10.1 GB in total on an RTX 5070 Ti), labels each model fits / tight / too big against the GPU minus 1.5 GB, and recommends the best fitting model (`QUALITY_ORDER` in `translator-settings.ts`).

## Phone uses the PC translator (API v1, phone port), planned 2026-10-11

Berti: "if someone wants to translate through their phone, can we make them sync up the pages from their PC?" The phone app sends pages straight to the translator on the same Wi-Fi.

### Translator side

- **Phone listener.** A second listener on `0.0.0.0:47824` (all interfaces). It's on by default; `--no-phone` or `"phone": false` in the config file turns it off. Config file: `%LOCALAPPDATA%\MihonTogether-Translator\config.json`, holding `{ "phone": true, "tokens": [{ "id", "name", "hash", "createdAt", "lastUsedAt" }] }`.
- **First start.** Windows asks once to allow network access. The console explains this: "Allow on private networks so your phone can connect".
- **Discovery.** Advertise mDNS/DNS-SD service `_mihontogether._tcp.local.` on port 47824, with TXT `api=1` and `name=<PC name>`.
- **Pairing code.** 6 digits, new on each start, shown in the console as "Phone pairing code: 123 456". It's only needed to add a new phone; paired phones keep working with their token.
- **Endpoints on 47824** (no CORS needed; browsers aren't involved):
  - `GET /health`: the same JSON as on 127.0.0.1, plus `"paired": true|false` (true when a valid `Authorization: Bearer <token>` was sent). Without a token it still answers, so the phone can show state, name and version.
  - `POST /pair` with body `{ "code": "123456", "device": "Galaxy A35" }`:
    - success: `200 { "token": "<random 32 bytes, base64url>", "name": "<PC name>" }`. Only a SHA-256 hash of the token is stored, and the console prints "Paired Galaxy A35".
    - wrong code: `403 { "error": "bad_code" }`. After 5 wrong codes the translator refuses all pairing for 60 s (`429 { "error": "locked" }`).
  - `POST /translate?target=en-US`, with `Authorization: Bearer <token>`. Same body, limits, queue and responses as on 127.0.0.1. A missing or unknown token gets `401 { "error": "unpaired" }`.
  - `DELETE /pair` with a token removes that phone, and the console prints it.
- **Queue.** The browser and the phone share the one-at-a-time queue.
- **Host header.** The DNS-rebinding `Host` check stays on 127.0.0.1:47823. The phone port accepts any `Host`, since phones use the LAN IP and token auth protects it.
- **Encryption.** Plain HTTP on the home network. The token is a bearer secret that only matters on the LAN. This limit is documented.
- **Built (translator 0.2.0, 2026-10-11).** Details beyond the list above:
  - `DELETE /pair` answers `204` with no body.
  - `/pair` errors also include `400 bad_device` (device not 1–60 characters after trimming), `400 bad_request` (body isn't that JSON) and `413 too_large` (body over 4 KB). `429 locked` carries `Retry-After` in seconds.
  - Wrong codes 1–5 get `403`; the lock starts after the fifth, so the sixth attempt within 60 s gets `429`, even with the right code. A right code resets the count.
  - The code may be sent as `123456`, `123 456` or `123-456`.
  - Requests with an `Origin` header (browsers) get `403 forbidden_origin` on the phone port.
  - The token scheme `Bearer` is case-insensitive.
  - Up to 32 paired phones are kept; the least recently used is dropped past that.

### Phone side

- **Engine.** A new translation engine, **"My PC (Koharu)"**, sits next to the existing engines in the reader's translation settings.
- **Pairing screen.** Finds PCs with `NsdManager` (service `_mihontogether._tcp`), with "Enter address manually" (IP or IP:port) as a fallback. The user enters the 6-digit code, and the phone stores the token like the Gemini key (private preference, excluded from backups). It shows the paired PC's name and has an "Unpair" button.
- **Reading.** The phone sends the whole page (original bytes) and gets back a finished PNG. That PNG becomes the rendered page in the existing translation cache and viewer pipeline (keyed like other engines, with engine id `pc-koharu`), so prefetch, the Original toggle and the cache work as they do now. There are no editable text blocks, because Koharu renders the page itself.
- **Errors.**
  - PC unreachable or translator closed: "Your PC translator isn't reachable. Start it on your PC (same Wi-Fi)." This doesn't halt the queue for good; it retries when the reader moves on.
  - `401`: "Pair again".
  - `503 not_ready`: "Your PC is still loading the models".
- **Networking.** Cleartext HTTP to the LAN address must be allowed (check the app's network security config and `usesCleartextTraffic`). The address is remembered, and if it changes, NSD rediscovery by PC name updates it.
- **Privacy.** Pages go only to the user's own paired PC. Incognito works like the other local engines (on-device and PC count as local).
