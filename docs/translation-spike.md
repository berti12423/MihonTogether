# Translation spike results (T0)

Run 2026-10-02 with `tools/translate-spike` ([plan](TRANSLATION_PLAN.md)), on Berti's free AI Studio key.

## Sample
Two Korean webtoon strips from *마음의 소리* (The Sound of Your Heart): 500×3300 and 500×2983 px (aspect about 1:6.5). They have speech bubbles, narration captions, a title card, and lots of hand-drawn SFX. No Japanese or Chinese pages yet.

## Results: `gemini-3.1-flash-lite`

| Input | Blocks | Latency | Tokens in/out | Cost at paid rates |
| --- | --- | --- | --- | --- |
| Whole strip (1 request) | 20 / 11 | 9.8 s / 12.8 s | ~1.3k / 1.0–1.4k | $0.0025 / $0.0018 |
| Sliced into 5 chunks of 500×750 px, 10% overlap | 20 / 11 | 2.3–10.7 s per chunk (sum 21 s / 27 s) | ~6.4k / 1.1–1.6k | $0.0040 / $0.0032 |

**Translation quality is very good.** It reads naturally and keeps the tone (the deadpan punchline "Sometimes, the truth just doesn't get through."). Long run-on dialogue comes out as fluent English, and title, author and page number are recognized. The kinds are classified correctly (dialogue / narration / sfx / sign), with SFX in their own category ("Creeeak~", "Scramble!", "Crash"). One ambiguous line, "...찍고", became "...and"; this needs context the strip alone doesn't give.

**Boxes on whole tall strips are unusable.** They're consistently displaced, mostly upward and toward the left, often by a whole bubble height: the model's 0–1000 coordinates lose precision on 1:6.5 images.

**Sliced, the boxes are accurate.** Mapped back to the strip, every box sits on its text. What's left:
- Duplicates in the overlap band (the same SFX found in two slices): merge by IoU/containment at the seams.
- A few boxes are loose (larger than the text), which is fine for rendering inside them.

## Free-tier limits (important)
- `gemini-3.8-flash` on the free tier allows only **20 requests per day**. Google returned 503 "high demand" at first, the SDK retried automatically, and the day's quota was gone after two slices. With slicing that's about 4 strips a day, so it isn't usable for normal reading on a free key.
- `gemini-3.1-flash-lite` handled the ~15 requests of this spike without limits. The exact free limits aren't published; each key's limits show in AI Studio ([rate limits](https://aistudio.google.com/rate-limit)). Daily quotas reset at midnight Pacific.
- So the app must: treat 429 as "out of free translations today" (show it, stop prefetching, no retries); turn off SDK auto-retry on 503 or cap it; translate on demand plus a small prefetch window, not whole chapters; and cache every result.

## Papago, compared to Gemini (not tested yet)
Papago is a dedicated Korean↔English translator, often natural on plain sentences. But it translates each text piece without seeing the panel, and its image endpoint targets documents and product photos rather than comic bubbles. It has no free tier and requires attribution. A fair test: send the Korean lines Gemini already extracted to Papago's text API and compare side by side. If Papago wins for Korean, combine them: Gemini for detection and OCR, Papago for the translation.

## Decisions from this
- **Always slice tall pages** before sending them, into chunks near a 2:3 aspect. Request the slice under the viewport first, then neighbours, in parallel (2–3 at a time). Merge seam duplicates. This moves the T3 work into T1/T2 for webtoons.
- **Default model:** `gemini-3.1-flash-lite`. The quality is already good and it's the cheapest. `gemini-3.8-flash` is the "quality" option once its comparison run is in (it returned 503 high-demand errors at first).
- **Cost:** about $0.003–0.004 per tall strip, sliced, at paid rates. Free on a user's own free-tier key (rate limited; Google may use the content).
- **Latency:** 3–10 s per slice, so translate ahead: prefetch the next slices and pages while the user reads, and show a progress dot on untranslated parts.

## Still to test
- Japanese (vertical text, right-to-left) and Chinese pages.
- `gemini-3.8-flash` on the same slices (blocked today by the 20 requests/day free limit; retry after the reset, with `--models gemini-3.8-flash` only).
- Papago `image-to-text`, if Berti gets a Naver Cloud key.
- Free-tier rate limits under prefetch (requests per minute).
