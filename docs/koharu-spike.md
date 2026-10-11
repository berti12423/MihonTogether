# Koharu test run (2026-10-11)

Machine: Berti's PC, Windows 11, RTX 5070 Ti 16 GB, Ryzen 7 7800X3D, 32 GB RAM.

## Build

- Koharu source at `C:\Users\Berti\Desktop\koharu-src`, commit `2c78c0c`.
- Tools installed with winget: Rustup (Rust 1.99.0, MSVC), LLVM 23.1.3 (`LIBCLANG_PATH=C:\Program Files\LLVM\bin`), Visual Studio 2022 Build Tools with VC tools. Bun 1.3.14 was already installed.
- `cargo build --release -p koharu-pipeline --bin run` took 8 min 46 s and produced `target\release\run.exe` (52 MB).
- No CUDA toolkit was needed. Koharu's `koharu-runtime` downloads libtorch, llama.cpp and the CUDA libraries on first use (from `github.com/koharu-rs/{torch,llama,diffusion}` releases and PyPI wheels), along with the models from Hugging Face.

## Run (defaults)

Defaults: RF-DETR layout detection, PaddleOCR-VL 1.6, LaMa inpainting, Gemma 4 12B IT (local), en-US.

| Run | Detection | OCR | Inpainting | Translation | Pipeline | Render | Total |
|---|---|---|---|---|---|---|---|
| 1st (downloading everything) | 17.0 s | 26.2 s | 32.3 s | 82.5 s | 125.8 s | 6.1 s | 215 s |
| 2nd (on disk; every model loaded fresh) | 1.1 s | 5.4 s | 5.8 s | 13.1 s | 19.9 s | 0.7 s | 23 s |

- The first run downloaded **12 GB** into `%LOCALAPPDATA%\koharu`.
- The per-stage times include loading each model. With the models kept in memory (the bridge), a page should take well under the 23 s.
- Test page: `koharu-ml/benches/fixtures/object_detection/1.jpg`, a Japanese 4-koma with vertical bubbles. Output: `C:\Users\Berti\Desktop\koharu-test\ja-1-en.png`.

## Quality

- All speech bubbles were erased cleanly (LaMa) and re-lettered in a comic font. Text auto-fits, including small bubbles.
- The translation is natural ("Robbing the bank", "As expected of Shiroko-senpai!"). The title was translated too.
- Sound effects (ゴゴゴ, アハハ) and name tags were left as they were, which matches Berti's earlier "leave SFX" decision.

## Not yet tested

- Korean webtoon (tall strip) and Chinese pages.
- Smaller LLM (Gemma 4 e4b) for speed.
- Warm per-page time inside a long-running process.
- CPU-only machines.

## Decision

Keep Koharu's defaults for v1. The bridge keeps the models loaded and runs one page at a time. Measure warm time in the bridge, and offer a "faster" option (smaller LLM) if pages take more than about 10 s.
