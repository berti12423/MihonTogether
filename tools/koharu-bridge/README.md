# Mihon Together Translator

A small Windows program that translates raw manga pages (Japanese, Korean, Chinese) into English **on your own PC**, so the Mihon Together website reader can show translated pages.

It runs [Koharu](https://github.com/koharu-rs/koharu)'s translation pipeline: it finds the speech bubbles, reads the text (OCR), translates it with a local AI model, erases the original text and letters the translation back in. The models stay loaded while the program runs, so pages after the first one are fast.

## Privacy

- Everything runs locally. No account, no API key, no cloud service.
- The program only listens on `127.0.0.1` (your own computer). Other devices on your network can't reach it.
- Only the Mihon Together website (`https://mihontogether.web.app`, `https://mihontogether.firebaseapp.com`) and local development pages may use it. Other websites get "403 Forbidden".
- Pages are processed in memory and never saved or sent anywhere.
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

## Defaults

Koharu's defaults at the pinned commit: RF-DETR layout detection (`koharu-layout-rfdetr-seg-2xl`), `paddleocr-vl-1.6` OCR, `lama` inpainting, and the local `gemma4-12b-it` model for translation (target `en-US`). Sound effects are left as they are.

## API (v1)

`http://127.0.0.1:47823`, see "Translator API v1" in `docs/KOHARU_TRANSLATION_PLAN.md` for the full contract.

- `GET /health`: name, API version, state (`starting`, `downloading`, `loading`, `ready`, `error`), progress, device, models, `queue` (requests waiting or running) and `lastPageMs`.
- `POST /translate?target=en-US`: body is the page image (JPEG, PNG, WebP, GIF or AVIF, up to 30 MB). Returns a PNG of the same size with an `X-Translate-Ms` header. Errors are JSON `{ "error", "message" }`: `400 bad_image`, `400 bad_target`, `403 forbidden_origin`/`forbidden_host`, `413 too_large`, `503 not_ready`, `500 failed`.
- One page at a time, first come first served. If the website cancels a request before its page starts, the page is skipped.

Notes beyond the contract: responses also carry `Access-Control-Expose-Headers: X-Translate-Ms` so the website can read the timing, and requests whose `Host` isn't `127.0.0.1`/`localhost` on port 47823 are refused (protection against DNS rebinding). AVIF pages need an AVIF decoder that this build doesn't include, so they return `400 bad_image`; the website should convert AVIF pages to PNG first.

Set `MIHON_TRANSLATOR_LOG=info` (or `debug`) for Koharu's detailed logs.

## Build from source

Prerequisites (Windows): Rust with the MSVC toolchain (tested with 1.99), LLVM/libclang (tested with 23, `LIBCLANG_PATH=C:\Program Files\LLVM\bin`), Visual Studio 2022 Build Tools with the C++ tools. No CUDA toolkit is needed: Koharu downloads its runtimes at first start.

```powershell
cd tools\koharu-bridge
.\build.ps1            # release build, copies the exe to dist\
cargo test             # HTTP, CORS, validation and queue tests (no models needed)
```

The Koharu crates are git dependencies pinned to commit `2c78c0cd6eeec4dbf607f0a22e3a7e1f932b6ff3`; `Cargo.lock` started from Koharu's own lock at that commit. A clean release build takes about 10 minutes.

## License and credit

- This program: Apache License 2.0, part of Mihon Together.
- Translation pipeline: [Koharu](https://github.com/koharu-rs/koharu) by Mayo Takanashi, Wangchong Zhou and contributors, licensed under MIT or Apache-2.0. Thank you!
- The models are downloaded from their original publishers at first start under their own licenses; this program doesn't redistribute model weights.
