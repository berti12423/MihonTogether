# Mihon Together Translator

A small Windows program that translates raw manga pages (Japanese, Korean, Chinese) into English **on your own PC**, so the Mihon Together website reader, and the Mihon Together app on your phone, can show translated pages.

It runs [Koharu](https://github.com/koharu-rs/koharu)'s translation pipeline: it finds the speech bubbles, reads the text (OCR), translates it with a local AI model, erases the original text and letters the translation back in. The models stay loaded while the program runs, so pages after the first one are fast.

## Privacy

- Everything runs locally. No account, no API key, no cloud service.
- The website port listens on `127.0.0.1` only (your own computer).
- The phone port (47824) is open to your home network, but it only translates for phones you paired with the code shown in the window. See "Use it from your phone" below, or turn it off.
- Only the Mihon Together website (`https://mihontogether.web.app`, `https://mihontogether.firebaseapp.com`) and local development pages may use it. Other websites get "403 Forbidden".
- Pages are processed in memory and never saved or sent anywhere (a phone's page goes back only to that phone).
- The only internet traffic is the one-time download of Koharu's runtimes and models (from GitHub, PyPI and Hugging Face), the same files Koharu itself downloads.

## Requirements

- Windows 10/11, 64-bit.
- An NVIDIA GPU is strongly recommended (tested on an RTX 5070 Ti, 16 GB). It works on the CPU, but slowly.
- About **12 GB** of free disk space for the first-run download, stored in `%LOCALAPPDATA%\koharu` (shared with the Koharu app, if you have it).

## Run

1. Double-click `MihonTogether-Translator.exe`. A console window opens and shows what it's doing.
2. The first start downloads about 12 GB (runtimes, CUDA libraries and models). This can take a while; the window shows the progress. Later starts skip the download.
3. Wait for `State: ready`. Then open the reader on the Mihon Together website and turn on **Translate**.
4. Keep the window open while you read. Close it (or press Ctrl+C) to stop.

Each translated page prints a line with its time.

## Use it from your phone

The Mihon Together app can send pages to this PC over your home Wi-Fi and get the translated pages back. The phone and the PC must be on the same network.

1. Start the translator on the PC. The window shows the phone address(es), such as `Phone address: 192.168.1.20:47824 (Wi-Fi)`, and a code: `Phone pairing code: 123 456`.
2. The first time, Windows Firewall asks whether to allow the translator. Allow it on **private networks** (your home network). Without that, the phone can't connect. Leave public networks unchecked. If you clicked it away, allow `MihonTogether-Translator.exe` under Windows Security > Firewall & network protection > Allow an app through firewall.
3. On the phone: reader translation settings > engine **My PC (Koharu)**. The app finds the PC automatically (it is announced on the network as `_mihontogether._tcp`); if it doesn't, enter the address from step 1 by hand.
4. Enter the 6-digit code. The window prints `Paired <phone name>`.

The code changes every time the translator starts, but you only need it to add a phone; paired phones keep working. After 5 wrong codes, pairing pauses for a minute. Unpairing in the app removes the phone (the window prints `Removed <phone name>`). To remove all phones, close the translator and delete the `tokens` entries from the config file below.

**Security note.** Phones talk to the PC over plain HTTP on your local network, not HTTPS. Each phone gets a random secret token that the translator checks on every page; the PC only stores a hash of it. Anyone on the same network who could read your Wi-Fi traffic could see pages and the token, so use it on networks you trust (your home), not on public Wi-Fi. The phone port refuses requests from web browsers.

**Turning it off.** Start the program with `--no-phone`, or set `"phone": false` in `%LOCALAPPDATA%\MihonTogether-Translator\config.json`:

```json
{ "phone": false, "tokens": [] }
```

The config file is created at the first start. It holds the phone switch and the paired phones (`id`, `name`, `hash`, `createdAt`, `lastUsedAt`); it never contains the tokens themselves.

## Defaults

Koharu's defaults at the pinned commit: RF-DETR layout detection (`koharu-layout-rfdetr-seg-2xl`), `paddleocr-vl-1.6` OCR, `lama` inpainting, and the local `gemma4-12b-it` model for translation (target `en-US`). Sound effects are left as they are.

## Settings

The website's **Translator settings** (mihontogether.web.app/translator, shown while the translator runs) change Koharu's choices: the translation model (any of Koharu's local models), instructions for it, generation options (temperature, top P, top K, max tokens, repeat penalty, reasoning, page image), the OCR model (`paddleocr-vl-1.6`, `manga-ocr`, `baberu-ocr`, `hayai-ocr`), inpainting (`lama`, `aot-inpainting`) and the fonts tried first. They are saved in `%LOCALAPPDATA%\MihonTogether-Translator\settings.json` and apply from the next page, for the website and paired phones alike; a model used for the first time downloads with that page. Delete the file to go back to the defaults.

**Which model fits my graphics card?** At startup the translator reads the NVIDIA GPU and its memory with `nvidia-smi` (it prints `GPU: <name> (<n> GB)`). The settings list each model with its size (from Hugging Face at Koharu's pinned revisions), and the website labels every model *fits your GPU*, *tight* or *too big* and recommends the best one that fits. The estimate is measured, not guessed: with Gemma 4 12B (6.4 GB of files) the whole translator used 10.1 GB on an RTX 5070 Ti, so it counts about 1.1 x the model files plus 2.9 GB for detection, OCR, inpainting and the runtime, and keeps 1.5 GB free for Windows. Roughly: 6-8 GB cards get the small models (Qwen 3.5 2B, Gemma 4 E2B), 12-16 GB Gemma 4 12B, 24 GB and up Gemma 4 26B or 31B. Without an NVIDIA GPU it runs on the processor, and the website suggests Gemma 4 E2B.

Colored lettering keeps its color: Koharu snaps dark-looking colors to black, which also catches saturated red or blue, so the translator measures each text region on the original page and puts such colors back.

## API (v1)

`http://127.0.0.1:47823`, see "Translator API v1" in `docs/KOHARU_TRANSLATION_PLAN.md` for the full contract. The phone port is in "Phone uses the PC translator" there.

- `GET /health`: name, API version, state (`starting`, `downloading`, `loading`, `ready`, `error`), progress, device, models, `queue` (requests waiting or running) and `lastPageMs`.
- `POST /translate?target=en-US`: body is the page image (JPEG, PNG, WebP, GIF or AVIF, up to 30 MB). Returns a PNG of the same size with an `X-Translate-Ms` header. Errors are JSON `{ "error", "message" }`: `400 bad_image`, `400 bad_target`, `403 forbidden_origin`/`forbidden_host`, `413 too_large`, `503 not_ready`, `500 failed`.
- One page at a time, first come first served. If the website cancels a request before its page starts, the page is skipped.

Notes beyond the contract: responses also carry `Access-Control-Expose-Headers: X-Translate-Ms` so the website can read the timing, and requests whose `Host` isn't `127.0.0.1`/`localhost` on port 47823 are refused (protection against DNS rebinding). AVIF pages need an AVIF decoder that this build doesn't include, so they return `400 bad_image`; the website should convert AVIF pages to PNG first.

### Phone port (`0.0.0.0:47824`)

No CORS and no `Host` check (phones use the LAN IP); requests that carry an `Origin` header (browsers) get `403 forbidden_origin`.

- `GET /health`: the same JSON plus `"paired": true|false` (true when `Authorization: Bearer <token>` is a paired phone's token). Works without a token.
- `POST /pair` with JSON `{ "code": "123456", "device": "Galaxy A35" }` (at most 4 KB; `device` is trimmed and must be 1 to 60 characters): `200 { "token", "name" }` where `token` is 32 random bytes in unpadded base64url and `name` is the PC name. Errors: `403 bad_code`, `429 locked` (with `Retry-After`, after 5 wrong codes, for 60 s), `400 bad_device`, `400 bad_request` (not that JSON), `413 too_large`.
- `POST /translate?target=en-US` with `Authorization: Bearer <token>`: exactly like the website port (same queue, limits and errors); without a paired token `401 unpaired`.
- `DELETE /pair` with `Authorization: Bearer <token>`: `204` and the phone is forgotten; `401 unpaired` for an unknown or missing token.
- mDNS/DNS-SD: `_mihontogether._tcp.local.` on port 47824, TXT `api=1` and `name=<PC name>`.

- `GET /settings` (website port only): `{ settings, defaults, options: { ocr, inpainting, llm: [{ id, name, vision, reasoning, sizeMb, projectorMb }], maxInstructions, maxFonts, gpu: { name, vramMb } | null } }`.
- `PUT /settings` (website port only, `Content-Type: application/json`, at most 16 KB): the full `settings` object (`ocr`, `inpainting`, `llm`, `instructions`, `temperature`, `topP`, `topK`, `maxTokens`, `repeatPenalty`, `reasoning`, `vision`, `fonts`; numbers may be `null` for the model's default; unknown fields are refused). Answers like `GET`, or `400 bad_settings` with a message. `/health`'s `models` gain `settings`, a short fingerprint of the settings, so caches keyed by the models notice a change.

Set `MIHON_TRANSLATOR_LOG=info` (or `debug`) for Koharu's detailed logs.

## Build from source

Prerequisites (Windows): Rust with the MSVC toolchain (tested with 1.99), LLVM/libclang (tested with 23, `LIBCLANG_PATH=C:\Program Files\LLVM\bin`), Visual Studio 2022 Build Tools with the C++ tools. No CUDA toolkit is needed: Koharu downloads its runtimes at first start.

```powershell
cd tools\koharu-bridge
.\build.ps1            # release build, copies the exe to dist\
cargo test             # HTTP, CORS, pairing, validation and queue tests (no models needed)
```

The Koharu crates are git dependencies pinned to commit `2c78c0cd6eeec4dbf607f0a22e3a7e1f932b6ff3`; `Cargo.lock` started from Koharu's own lock at that commit. A clean release build takes about 10 minutes.

## License and credit

- This program: Apache License 2.0, part of Mihon Together.
- Translation pipeline: [Koharu](https://github.com/koharu-rs/koharu) by Mayo Takanashi, Wangchong Zhou and contributors, licensed under MIT or Apache-2.0. Thank you!
- The models are downloaded from their original publishers at first start under their own licenses; this program doesn't redistribute model weights.
