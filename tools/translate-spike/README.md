# Translation spike (T0)

Measures Gemini on real raw pages before we build the in-reader pipeline ([plan](../../docs/TRANSLATION_PLAN.md)):
box accuracy, translation quality, latency, tokens and cost per page.

1. Get a free Gemini API key at https://aistudio.google.com/apikey and put it in `tools/translate-spike/.env`:
   `GEMINI_API_KEY=your-key` (git-ignored; never commit it).
2. Put 10–20 raw pages in `tools/translate-spike/pages/`, for example `pages/ko/`, `pages/ja/`, `pages/zh/` (git-ignored).
   Mihon's downloads folder has them as image files or `.cbz` archives (unzip those).
3. Run:
   ```bash
   npm --prefix tools/translate-spike install
   node tools/translate-spike/spike.mjs
   ```
   Options: `--models gemini-3.1-flash-lite,gemini-3.8-flash` (default), `--resolution low|medium|high|ultra_high`,
   `--limit 20`, `--dry yes` (fake replies, no key needed).
4. Open `tools/translate-spike/results/report.html`. Hover a page to see the original.

Requests use `store: false`. With a free-tier key, Google may use the pages to improve its products.
