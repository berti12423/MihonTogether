// Cover images for the web without storing any: a shared title's own cover link when the source allows other
// websites to load it, otherwise the AniList cover found by title (AniList's API and images are open to browsers).
// Results are cached in this browser for 30 days, so each title is looked up once.
import { h } from "./dom";

const ANILIST = "https://graphql.anilist.co";
// v2: v1 cached "no cover" for whole batches that AniList had rejected because one title was missing.
const CACHE_PREFIX = "anilist-cover3:";
const TTL = 30 * 86_400_000;
const BATCH = 10;

/** What to look up on AniList: a title search, or an exact AniList / MyAnimeList ID from the owner's tracking. */
export interface AniListLookup { search?: string; anilistId?: number; malId?: number }
const lookupKey = (l: AniListLookup) => (l.anilistId ? `al:${l.anilistId}` : l.malId ? `mal:${l.malId}` : (l.search ?? "").toLowerCase());

const pending = new Map<string, { lookup: AniListLookup; waiters: Array<(url: string | null) => void> }>();
let timer: ReturnType<typeof setTimeout> | null = null;

function cached(key: string): string | null | undefined {
  try {
    const raw = localStorage.getItem(CACHE_PREFIX + key);
    if (!raw) return undefined;
    const { url, at } = JSON.parse(raw) as { url: string | null; at: number };
    return Date.now() - at < TTL ? url : undefined;
  } catch {
    return undefined;
  }
}
function remember(key: string, url: string | null): void {
  try { localStorage.setItem(CACHE_PREFIX + key, JSON.stringify({ url, at: Date.now() })); } catch { /* storage full or blocked */ }
}

/**
 * The GraphQL request for up to [BATCH] lookups at once, one aliased query each. `Page { media }` rather than
 * `Media`: an unknown title or ID comes back as an empty list, while `Media` fails the whole batch with 404.
 */
export function aniListQuery(lookups: Array<AniListLookup | string>): { query: string; variables: Record<string, string | number> } {
  const variables: Record<string, string | number> = {};
  const declarations: string[] = [];
  const parts = lookups.map((raw, i) => {
    const lookup = typeof raw === "string" ? { search: raw } : raw;
    const [argument, type, value] = lookup.anilistId ? ["id", "Int", lookup.anilistId]
      : lookup.malId ? ["idMal", "Int", lookup.malId] : ["search", "String", lookup.search ?? ""];
    variables[`t${i}`] = value;
    declarations.push(`$t${i}: ${type}`);
    return `a${i}: Page(perPage: 1) { media(${argument}: $t${i}, type: MANGA) { coverImage { large } } }`;
  });
  return { query: `query (${declarations.join(", ")}) { ${parts.join(" ")} }`, variables };
}

async function flush(): Promise<void> {
  timer = null;
  const batch = [...pending.entries()].slice(0, BATCH);
  batch.forEach(([key]) => pending.delete(key));
  if (pending.size) timer = setTimeout(flush, 700); // stay well under AniList's rate limit
  let data: Record<string, { media?: Array<{ coverImage?: { large?: string } }> } | null> = {};
  let answered = false;
  try {
    const response = await fetch(ANILIST, {
      method: "POST", headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify(aniListQuery(batch.map(([, item]) => item.lookup))),
    });
    data = ((await response.json()) as { data?: typeof data }).data ?? {};
    answered = response.ok;
  } catch { /* offline or rate limited: show placeholders, try again next visit */ }
  batch.forEach(([key, item], i) => {
    const found = data[`a${i}`]?.media?.[0]?.coverImage?.large ?? null;
    const url = found && /^https:\/\/\S+$/.test(found) ? found : null;
    // Only a real answer is remembered; errors and rate limits are retried on the next visit.
    if (url) remember(key, url); else if (answered && data[`a${i}`]) remember(key, null);
    item.waiters.forEach((done) => done(url));
  });
}

export function aniListCover(lookup: AniListLookup | string): Promise<string | null> {
  const resolved = typeof lookup === "string" ? { search: lookup } : lookup;
  const key = lookupKey(resolved);
  const known = cached(key);
  if (known !== undefined) return Promise.resolve(known);
  return new Promise((resolve) => {
    const item = pending.get(key) ?? { lookup: resolved, waiters: [] };
    item.waiters.push(resolve);
    pending.set(key, item);
    if (!timer) timer = setTimeout(flush, 120);
  });
}

/** Extra ways to find a cover when the source's own link is refused. */
export interface CoverFallbacks {
  /** The owner's uploaded thumbnail (friends-only Storage read). */
  thumb?: () => Promise<string | null>;
  anilistId?: number | null;
  malId?: number | null;
}

/**
 * A cover image element. Order: the source's own cover, the owner's uploaded thumbnail, AniList by the tracked ID,
 * then AniList by title. Each step runs only when the previous one is missing or refused.
 */
export function coverImage(title: string, coverUrl: string | null, fallbacks: CoverFallbacks = {}): HTMLElement {
  const img = h("img", { class: "cover", alt: "", loading: "lazy", referrerpolicy: "no-referrer" }) as HTMLImageElement;
  const steps: Array<() => Promise<string | null>> = [];
  if (fallbacks.thumb) steps.push(fallbacks.thumb);
  if (fallbacks.anilistId || fallbacks.malId) steps.push(() => aniListCover({ anilistId: fallbacks.anilistId ?? undefined, malId: fallbacks.malId ?? undefined }));
  steps.push(() => aniListCover({ search: title }));
  const next = (): void => {
    const step = steps.shift();
    if (!step) { img.classList.add("empty"); img.removeAttribute("src"); return; }
    step().then((url) => { if (url) img.src = url; else next(); }, next);
  };
  img.addEventListener("error", next);
  if (coverUrl) img.src = coverUrl; else next();
  return img;
}
