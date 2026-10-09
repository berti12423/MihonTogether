// "My library" pages: /my-library (phone, connection, cover grid), /my-library/:mangaId (details and chapters),
// /my-library/:mangaId/:chapterId (reader). One connection to the phone is kept across these pages.
// The section draws itself (it changes with the connection, not with the rest of the site's state); main.ts just
// places [myLibraryPage]'s element and tells it about sign-in changes.
import { h, replace } from "../dom";
import { ConnectError, firestoreSignaling, Phone, PhoneLink, Signaling, watchPhones } from "./connection";
import {
  Chapter, chapterLabel, continueChapter, coverKey, filterLibrary, initialLayout, keepWindow, LibraryList, LibraryManga, LibraryRoute,
  MangaDetails, nextChapter, pageStep, parseLibraryRoute, prefetchPages, problemText, ProgressThrottle, ReaderLayout, requestErrorText,
  safeImageType, startPage, tapStep, visibleCategories, ChapterPages,
} from "./protocol";

declare const __DEV_TOOLS__: boolean;

export interface Host {
  navigate(path: string): void;
  link(path: string, label: string): HTMLElement;
}

type Status = "idle" | "connecting" | "connected" | "lost" | "failed";
interface Session {
  uid: string | null;
  phones: Phone[] | null;
  phonesError: string | null;
  stopPhones: (() => void) | null;
  link: PhoneLink | null;
  /** The phone being connected or connected to (or last tried). */
  phone: { deviceId: string; name: string } | null;
  status: Status;
  problem: string | null;
  /** Auto-connect happens once per visit; not again after Disconnect or a failure. */
  autoTried: boolean;
  library: LibraryList | null;
  libraryError: string | null;
  libraryStale: boolean;
  details: Map<string, Promise<MangaDetails>>;
  covers: Map<string, Promise<string | null>>;
  category: string | null;
  search: string;
  layouts: Map<string, ReaderLayout>;
}

let host: Host;
const session: Session = {
  uid: null, phones: null, phonesError: null, stopPhones: null, link: null, phone: null, status: "idle", problem: null,
  autoTried: false, library: null, libraryError: null, libraryStale: false, details: new Map(), covers: new Map(),
  category: null, search: "", layouts: new Map(),
};

const root = h("div", { class: "wl" });
const bar = h("div", { class: "wl-bar" });
const content = h("div", {});
root.append(bar, content);

/** The current page view: its element and how to stop it (reader: flush progress, revoke images, keys). */
let view: { key: string; element: HTMLElement; stop: () => void } | null = null;
let barKey = "";

export function initWebLibrary(value: Host): void {
  host = value;
  window.addEventListener("pagehide", () => { view?.stop(); view = null; session.link?.close(); });
}

/** Another part of the site is showing: the open page stops (the reader saves progress), the connection stays. */
export function leaveWebLibrary(): void {
  view?.stop(); view = null;
}

/** Signed out or another account: everything about the previous session goes. */
export function resetWebLibrary(): void {
  view?.stop(); view = null;
  session.stopPhones?.();
  disconnect(false);
  Object.assign(session, { uid: null, phones: null, phonesError: null, stopPhones: null, phone: null, status: "idle", problem: null, autoTried: false,
    category: null, search: "" });
  session.layouts.clear();
  barKey = "";
  replace(bar);
  replace(content);
}

/** The section's element for [path] (one of the /my-library routes) while [uid] is signed in. */
export function myLibraryPage(uid: string, path: string): HTMLElement {
  if (session.uid !== uid) {
    resetWebLibrary();
    session.uid = uid;
    session.stopPhones = watchPhones(uid, (phones) => {
      session.phones = phones; session.phonesError = null;
      maybeAutoConnect();
      redraw();
    }, (error) => { session.phonesError = (error as Error)?.message || "Couldn't load your phones."; redraw(); });
  }
  draw(path);
  return root;
}

function redraw(): void {
  if (parseLibraryRoute(location.pathname) && session.uid) draw(location.pathname);
}

// ---------- connection ----------

function maybeAutoConnect(): void {
  if (session.autoTried || session.status !== "idle" || session.phones?.length !== 1) return;
  if (!parseLibraryRoute(location.pathname)) return;
  session.autoTried = true;
  connect(session.phones[0]);
}

function connect(phone: { deviceId: string; name: string; protocol?: number }, signaling?: Signaling): void {
  disconnect(false);
  session.autoTried = true;
  session.phone = { deviceId: phone.deviceId, name: phone.name };
  session.status = "connecting";
  session.problem = null;
  let link: PhoneLink;
  try {
    link = new PhoneLink(signaling ?? firestoreSignaling(session.uid!, phone.deviceId), phone.protocol ?? null);
  } catch (error) {
    fail(error);
    return;
  }
  session.link = link;
  redraw();
  link.connect().then(() => {
    if (session.link !== link) return;
    session.status = "connected";
    link.onLost = () => {
      if (session.link !== link) return;
      dropConnectionData();
      session.link = null;
      session.status = "lost";
      session.problem = problemText("lost");
      redraw();
    };
    loadLibrary();
    redraw();
  }, (error) => { if (session.link === link) { session.link = null; fail(error); } });
}

function fail(error: unknown): void {
  session.status = "failed";
  session.problem = error instanceof ConnectError ? problemText(error.problem)
    : (error as { code?: string })?.code === "permission-denied" ? "You aren't allowed to connect to that phone. Sign in with the phone's account."
    : requestErrorText(error);
  redraw();
}

/** Ends the session (Disconnect, sign-out, or before a new connection). */
function disconnect(redrawAfter = true): void {
  view?.stop(); view = null;
  const link = session.link;
  session.link = null;
  link?.close();
  dropConnectionData();
  session.status = "idle";
  session.problem = null;
  if (redrawAfter) redraw();
}

function dropConnectionData(): void {
  view?.stop(); view = null;
  session.library = null; session.libraryError = null; session.libraryStale = false;
  session.details.clear();
  for (const cover of session.covers.values()) cover.then((url) => url && URL.revokeObjectURL(url), () => undefined);
  session.covers.clear();
}

function loadLibrary(): void {
  const link = session.link;
  if (!link) return;
  session.libraryError = null;
  link.client.request<LibraryList>("library.list").then((list) => {
    if (session.link !== link) return;
    session.library = { categories: list.categories ?? [], manga: list.manga ?? [] };
    session.libraryStale = false;
    // A cached grid shows the old list; build it again.
    if (view?.key.startsWith("list|")) { view.stop(); view = null; }
    redraw();
  }, (error) => {
    if (session.link !== link) return;
    session.libraryError = requestErrorText(error);
    redraw();
  });
}

/** A blob URL for image bytes from the phone. */
function blobUrl(result: { contentType: string; bytes: Uint8Array }): string {
  return URL.createObjectURL(new Blob([result.bytes as Uint8Array<ArrayBuffer>], { type: safeImageType(result.contentType) }));
}

/** A title's cover as a blob URL (null when it has none), cached for the connection by mangaId + coverVersion. */
function cover(manga: { mangaId: string; coverVersion: string }, signal?: AbortSignal): Promise<string | null> {
  const link = session.link;
  if (!link) return Promise.resolve(null);
  const key = coverKey(manga);
  let entry = session.covers.get(key);
  if (!entry) {
    entry = link.client.requestBinary("manga.cover", { mangaId: manga.mangaId, maxWidth: 300 }, { signal })
      .then(blobUrl, (error) => {
        // Cancelled before it was sent (scrolled away): ask again next time it shows.
        if (error?.code === "aborted" || error?.code === "closed" || error?.code === "timeout" || error?.code === "busy") session.covers.delete(key);
        return null;
      });
    session.covers.set(key, entry);
  }
  return entry;
}

function details(mangaId: string): Promise<MangaDetails> {
  const link = session.link;
  if (!link) return Promise.reject(new Error(problemText("lost")));
  let entry = session.details.get(mangaId);
  if (!entry) {
    entry = link.client.request<MangaDetails>("manga.details", { mangaId });
    entry.catch(() => session.details.delete(mangaId));
    session.details.set(mangaId, entry);
  }
  return entry;
}

// ---------- drawing ----------

function draw(path: string): void {
  const route = parseLibraryRoute(path);
  if (!route) return;
  drawBar();
  const connected = session.status === "connected" && session.link;
  const key = !connected ? `none|${session.status}`
    : route.view === "list" ? `list|${session.library ? "ready" : session.libraryError ?? "loading"}`
    : route.view === "manga" ? `manga|${route.mangaId}` : `reader|${route.chapterId}`;
  if (view?.key === key) return;
  view?.stop();
  view = null;
  if (!connected) { replace(content, intro()); return; }
  const built = route.view === "list" ? listView() : route.view === "manga" ? mangaView(route.mangaId) : readerView(route);
  view = { key, ...built };
  replace(content, built.element);
}

/** What this page is, under the connection card while not connected. */
function intro(): HTMLElement {
  return h("p", { class: "muted small" },
    "Read the library on your phone here. Your phone sends covers and pages straight to this browser over an encrypted connection; nothing is uploaded. Best on the same Wi-Fi.");
}

function drawBar(): void {
  const phones = session.phones;
  const key = JSON.stringify([session.status, session.problem, session.phone, session.link?.hello?.librarySize, phones, session.phonesError]);
  if (key === barKey) return;
  barKey = key;
  const name = session.phone?.name ?? "your phone";
  const connectButtons = (list: Phone[]) => list.map((phone) => h("button", { class: list.length > 1 ? "secondary" : "", onclick: () => connect(phone) }, `Connect to ${phone.name}`));
  const demo = __DEV_TOOLS__ ? h("button", { class: "secondary", onclick: () => connectDemo() }, "Use a demo phone (development)") : null;
  switch (session.status) {
    case "connected": {
      const hello = session.link?.hello;
      replace(bar, h("div", { class: "wl-status row between" },
        h("span", {}, h("span", { class: "dot on wl-dot", "aria-hidden": "true" }), " Connected to ", h("strong", {}, hello?.deviceName || name),
          hello ? h("span", { class: "muted small" }, ` • ${hello.librarySize} ${hello.librarySize === 1 ? "title" : "titles"}`) : null),
        h("button", { class: "secondary", onclick: () => disconnect() }, "Disconnect")));
      return;
    }
    case "connecting":
      replace(bar, h("section", { class: "card" }, h("h2", {}, "My library"),
        h("p", { role: "status" }, `Connecting to ${name}…`),
        h("p", { class: "muted small" }, "Keep Mihon Together open on the phone; it answers by itself."),
        h("button", { class: "secondary", onclick: () => disconnect() }, "Cancel")));
      return;
    case "lost":
    case "failed":
      replace(bar, h("section", { class: "card" }, h("h2", {}, "My library"),
        h("p", { class: "error", role: "status" }, session.problem ?? problemText("lost")),
        h("div", { class: "row" },
          session.phone ? h("button", { onclick: () => retry() }, "Retry") : null,
          ...(phones ?? []).filter((phone) => phone.deviceId !== session.phone?.deviceId).map((phone) =>
            h("button", { class: "secondary", onclick: () => connect(phone) }, `Connect to ${phone.name}`)),
          demo)));
      return;
    default:
      replace(bar, h("section", { class: "card" }, h("h2", {}, "My library"),
        session.phonesError ? h("p", { class: "error", role: "status" }, session.phonesError)
          : phones === null ? h("p", { class: "muted" }, "Looking for your phone…")
          : phones.length === 0 ? h("div", {},
            h("p", {}, "No phone found."),
            h("p", { class: "muted" }, "Open Mihon Together on your phone with the same account; this page connects by itself."),
            h("p", { class: "muted small" }, "On the phone: Friends → Me → Library on the web must be on. Don't have the app yet? ", host.link("/download", "Download it"), "."))
          : h("div", {}, h("p", {}, phones.length === 1 ? "Your phone is ready." : "Choose a phone:"), h("div", { class: "row" }, ...connectButtons(phones))),
        demo));
  }
}

function retry(): void {
  const phone = session.phones?.find((p) => p.deviceId === session.phone?.deviceId);
  if (phone) connect(phone);
  else if (__DEV_TOOLS__ && session.phone?.deviceId === DEMO_ID) connectDemo();
  else { session.status = "failed"; session.problem = "That phone isn't online any more. Open Mihon Together on it, then try again."; redraw(); }
}

const DEMO_ID = "demo-phone-development";
/** Development only: a fake phone in this page over a real WebRTC loopback. */
function connectDemo(): void {
  if (__DEV_TOOLS__) {
    import("./dev-loopback").then(({ loopbackSignaling }) => connect({ deviceId: DEMO_ID, name: "Demo phone" }, loopbackSignaling()));
  }
}

// ---------- /my-library: cover grid ----------

function listView(): { element: HTMLElement; stop: () => void } {
  const list = session.library;
  if (!list) {
    return { element: h("div", {}, session.libraryError
      ? h("div", {}, h("p", { class: "error", role: "status" }, session.libraryError), h("button", { onclick: () => { session.libraryError = null; redraw(); loadLibrary(); } }, "Try again"))
      : h("p", { class: "muted" }, "Loading your library…")), stop: () => undefined };
  }
  if (session.libraryStale) loadLibrary();
  const categories = visibleCategories(list);
  if (session.category !== null && !categories.some((c) => c.categoryId === session.category)) session.category = null;
  const search = h("input", { type: "search", placeholder: "Search your library", maxlength: "100", "aria-label": "Search your library" }) as HTMLInputElement;
  search.value = session.search;
  const counter = h("span", { class: "muted small" });
  const tabs = h("div", { class: "row wl-tabs", role: "tablist" });
  const grid = h("div", { class: "grid" });
  const empty = h("p", { class: "muted" });
  const observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      const tile = entry.target as HTMLElement & { load?: () => void; cancel?: () => void };
      if (entry.isIntersecting) tile.load?.();
      else tile.cancel?.();
    }
  }, { rootMargin: "300px 0px" });
  const tiles = new Map<string, HTMLElement>();
  const tile = (manga: LibraryManga) => {
    let element = tiles.get(manga.mangaId);
    if (element) return element;
    const placeholder = h("div", { class: "cover empty" });
    const anchor = h("a", { class: "tile wl-tile", href: `/my-library/${manga.mangaId}`, title: manga.title,
      onclick: (event) => { event.preventDefault(); host.navigate(`/my-library/${manga.mangaId}`); } },
    placeholder,
    manga.unread > 0 ? h("span", { class: "tag", "aria-label": `${manga.unread} unread` }, String(manga.unread)) : null,
    h("span", { class: "wl-caption" }, manga.title)) as HTMLElement & { load?: () => void; cancel?: () => void };
    let controller: AbortController | null = null, done = false;
    anchor.load = () => {
      if (done || controller) return;
      controller = new AbortController();
      cover(manga, controller.signal).then((url) => {
        controller = null;
        if (!url) return;
        done = true;
        observer.unobserve(anchor);
        placeholder.replaceWith(h("img", { class: "cover", src: url, alt: "", loading: "lazy" }));
      });
    };
    anchor.cancel = () => { controller?.abort(); controller = null; };
    observer.observe(anchor);
    tiles.set(manga.mangaId, anchor);
    return anchor;
  };
  const drawGrid = () => {
    const shown = filterLibrary(list, session.category, session.search);
    counter.textContent = `${shown.length} of ${list.manga.length}`;
    replace(tabs, categories.length > 1 ? h("button", { class: session.category === null ? "chip on" : "chip", role: "tab", "aria-selected": String(session.category === null),
      onclick: () => { session.category = null; drawGrid(); } }, "All") : null,
    ...(categories.length > 1 ? categories : []).map((c) => h("button", { class: session.category === c.categoryId ? "chip on" : "chip", role: "tab",
      "aria-selected": String(session.category === c.categoryId), onclick: () => { session.category = c.categoryId; drawGrid(); } }, c.name)));
    replace(grid, ...shown.map(tile));
    empty.textContent = shown.length ? "" : list.manga.length ? "Nothing matches." : "Your library on the phone is empty. Add titles in the app.";
  };
  search.addEventListener("input", () => { session.search = search.value; drawGrid(); });
  drawGrid();
  const element = h("div", {},
    h("div", { class: "row between" }, h("h2", {}, "My library"), h("button", { class: "secondary", onclick: () => loadLibrary() }, "Refresh")),
    tabs, h("div", { class: "row" }, search, counter), empty, grid);
  return { element, stop: () => observer.disconnect() };
}

// ---------- /my-library/:mangaId: details and chapters ----------

const STATUS: Record<string, string> = {
  ongoing: "Ongoing", completed: "Completed", licensed: "Licensed", publishing_finished: "Publishing finished", cancelled: "Cancelled",
  on_hiatus: "On hiatus", unknown: "",
};
const CHAPTER_PAGE = 200;

function mangaView(mangaId: string): { element: HTMLElement; stop: () => void } {
  const element = h("div", {}, host.link("/my-library", "← My library"), h("p", { class: "muted" }, "Loading…"));
  let alive = true;
  details(mangaId).then((manga) => {
    if (!alive) return;
    const listed = session.library?.manga.find((m) => m.mangaId === mangaId);
    const coverBox = h("div", { class: "wl-cover" }, h("div", { class: "cover empty" }));
    cover({ mangaId, coverVersion: listed?.coverVersion ?? "?" }).then((url) => { if (url && alive) replace(coverBox, h("img", { class: "cover", src: url, alt: "" })); });
    const next = continueChapter(manga.chapters);
    const ordered = manga.chapters;
    const first = ordered[ordered.length - 1];
    const resume = next ?? first;
    const resumeLabel = !resume ? null : next ? `${next.lastPageRead > 0 ? "Continue" : "Start"}: ${chapterLabel(next)}` : `Read again: ${chapterLabel(first)}`;
    const rows = h("ul", { class: "wl-chapters" });
    const more = h("div", {});
    let shown = 0;
    const showMore = () => {
      rows.append(...ordered.slice(shown, shown + CHAPTER_PAGE).map((chapter) => chapterRow(manga, chapter)));
      shown = Math.min(ordered.length, shown + CHAPTER_PAGE);
      replace(more, shown < ordered.length ? h("button", { class: "secondary", onclick: showMore }, `Show all ${ordered.length} chapters`) : null);
    };
    showMore();
    const url = manga.url && /^https?:\/\//i.test(manga.url) ? manga.url : null;
    const meta = [manga.author, manga.artist && manga.artist !== manga.author ? manga.artist : null].filter(Boolean).join(", ");
    replace(element,
      host.link("/my-library", "← My library"),
      h("section", { class: "card wl-head" }, coverBox,
        h("div", { class: "grow" },
          h("h2", {}, manga.title),
          meta ? h("p", { class: "small" }, meta) : null,
          h("p", { class: "muted small" }, [STATUS[manga.status] ?? "", manga.sourceName].filter(Boolean).join(" • ")),
          manga.genres.length ? h("div", { class: "badges" }, ...manga.genres.slice(0, 12).map((g) => h("span", { class: "chip wl-genre" }, g))) : null,
          resume ? h("button", { onclick: () => host.navigate(`/my-library/${mangaId}/${resume.chapterId}`) }, resumeLabel) : null,
          url ? h("p", { class: "small" }, h("a", { href: url, target: "_blank", rel: "noopener noreferrer" }, "Open on the source's website")) : null)),
      manga.description ? h("section", { class: "card" }, h("details", {}, h("summary", {}, "Description"), h("p", { class: "review-text" }, manga.description))) : null,
      h("section", { class: "card" }, h("h3", {}, `${ordered.length} ${ordered.length === 1 ? "chapter" : "chapters"}`),
        ordered.length ? rows : h("p", { class: "muted" }, "No chapters on the phone yet. Refresh the title in the app."), more));
  }, (error) => {
    if (!alive) return;
    replace(element, host.link("/my-library", "← My library"), h("p", { class: "error", role: "status" }, requestErrorText(error)),
      h("button", { onclick: () => { if (view?.key === `manga|${mangaId}`) { view.stop(); view = null; } redraw(); } }, "Try again"));
  });
  return { element, stop: () => { alive = false; } };
}

function chapterRow(manga: MangaDetails, chapter: Chapter): HTMLElement {
  const path = `/my-library/${manga.mangaId}/${chapter.chapterId}`;
  const date = chapter.dateUpload > 0 ? new Date(chapter.dateUpload).toLocaleDateString() : null;
  const sub = [chapter.scanlator, date, !chapter.read && chapter.lastPageRead > 0 ? `Page ${chapter.lastPageRead + 1}` : null].filter(Boolean).join(" • ");
  return h("li", { class: chapter.read ? "read" : "" },
    h("a", { href: path, onclick: (event) => { event.preventDefault(); host.navigate(path); } },
      h("span", { class: "grow" }, chapter.bookmark ? h("span", { class: "accent", "aria-label": "Bookmarked" }, "★ ") : null, chapterLabel(chapter),
        sub ? h("span", { class: "muted small wl-sub" }, sub) : null),
      chapter.downloaded ? h("span", { class: "wl-flag", title: "Downloaded on the phone" }, "Downloaded") : null,
      chapter.read ? h("span", { class: "wl-flag muted" }, "Read") : null));
}

// ---------- /my-library/:mangaId/:chapterId: reader ----------

interface PageImage { promise: Promise<string>; url: string | null; controller: AbortController }

function readerView(route: Extract<LibraryRoute, { view: "reader" }>): { element: HTMLElement; stop: () => void } {
  const link = session.link!;
  const { mangaId, chapterId } = route;
  const back = host.link(`/my-library/${mangaId}`, "← Chapters");
  const title = h("strong", { class: "ellipsis grow" }, "Loading…");
  const indicator = h("span", { class: "muted small wl-indicator", "aria-live": "polite" });
  const toggles = h("span", { class: "row" });
  const body = h("div", { class: "wl-body" }, h("p", { class: "muted center" }, "Loading pages…"));
  const element = h("div", { class: "wl-reader" }, h("div", { class: "wl-toolbar row" }, back, title, indicator, toggles), body);

  let alive = true;
  let manga: MangaDetails | null = null;
  let pageCount = 0, current = 0;
  let layout: ReaderLayout = session.layouts.get(mangaId) ?? { mode: "paged", rtl: false };
  let throttle: ProgressThrottle | null = null;
  let bodyStop: () => void = () => undefined;
  const images = new Map<number, PageImage>();

  const image = (index: number): PageImage => {
    let entry = images.get(index);
    if (entry) return entry;
    const controller = new AbortController();
    const created: PageImage = { url: null, controller, promise: Promise.resolve("") };
    created.promise = link.client.requestBinary("page.image", { chapterId, index }, { signal: controller.signal }).then((result) => {
      const url = blobUrl(result);
      // Dropped while it loaded (left the chapter or scrolled far away): not needed any more.
      if (!alive || images.get(index) !== created) { URL.revokeObjectURL(url); throw new Error("dropped"); }
      created.url = url;
      return url;
    });
    created.promise.catch(() => { if (images.get(index) === created && !created.url) images.delete(index); });
    images.set(index, created);
    return created;
  };
  const release = (index: number) => {
    const entry = images.get(index);
    if (!entry) return;
    images.delete(index);
    entry.controller.abort();
    if (entry.url) URL.revokeObjectURL(entry.url);
  };
  /** Loads the current page and the next 3, and lets go of pages far from it. */
  const around = (index: number) => {
    image(index);
    prefetchPages(index, pageCount).forEach(image);
    const keep = keepWindow(index, pageCount);
    for (const i of [...images.keys()]) if (i < keep.from || i > keep.to) release(i);
  };
  const setCurrent = (index: number) => {
    current = index;
    indicator.textContent = `${index + 1} / ${pageCount}`;
    throttle?.page(index);
    if (throttle?.finished) { session.libraryStale = true; session.details.delete(mangaId); }
  };
  const nextOne = () => (manga ? nextChapter(manga.chapters, chapterId) : null);
  const endPanel = () => {
    const next = nextOne();
    return h("div", { class: "wl-end card center" },
      h("p", {}, next ? "End of the chapter." : "That's the newest chapter on your phone."),
      next ? h("button", { onclick: () => host.navigate(`/my-library/${mangaId}/${next.chapterId}`) }, `Next chapter: ${chapterLabel(next)}`) : null,
      h("button", { class: "secondary", onclick: () => host.navigate(`/my-library/${mangaId}`) }, "Back to chapters"));
  };
  const pageError = (index: number, error: unknown, retry: () => void) => h("div", { class: "wl-missing" },
    h("p", { class: "error" }, `Page ${index + 1}: ${error instanceof Error && error.message === "dropped" ? "not loaded" : requestErrorText(error)}`),
    h("button", { class: "secondary", onclick: retry }, "Try again"));

  // Paged: one page at a time; arrows, tap zones, and an end panel after the last page.
  const paged = () => {
    const frame = h("div", { class: "wl-paged" });
    let atEnd = false, token = 0;
    const show = (index: number) => {
      atEnd = false;
      setCurrent(index);
      around(index);
      const mine = ++token;
      replace(frame, h("p", { class: "muted center wl-loading" }, `Loading page ${index + 1}…`));
      images.get(index)!.promise.then((url) => {
        if (mine === token) replace(frame, h("img", { class: "wl-page", src: url, alt: `Page ${index + 1}` }));
      }, (error) => {
        if (mine === token) replace(frame, pageError(index, error, () => { release(index); show(index); }));
      });
    };
    const step = (delta: number) => {
      if (delta === 0) return;
      if (atEnd) { if (delta < 0) show(pageCount - 1); return; }
      const target = current + delta;
      if (target < 0) return;
      if (target >= pageCount) { atEnd = true; token++; replace(frame, endPanel()); return; }
      show(target);
    };
    frame.addEventListener("click", (event) => {
      if (atEnd || (event.target as HTMLElement).closest("button")) return;
      const rect = frame.getBoundingClientRect();
      step(tapStep(event.clientX - rect.left, rect.width, layout.rtl));
    });
    const onKey = (event: KeyboardEvent) => {
      if (event.ctrlKey || event.metaKey || event.altKey || (event.target as HTMLElement)?.closest?.("input, textarea, select")) return;
      const delta = pageStep(event.key, layout.rtl);
      if (delta) { event.preventDefault(); step(delta); }
    };
    document.addEventListener("keydown", onKey);
    replace(body, frame);
    show(current);
    // The toolbar at the top of the screen, so a whole page fits below it.
    requestAnimationFrame(() => element.scrollIntoView({ block: "start" }));
    return () => document.removeEventListener("keydown", onKey);
  };

  // Long strip: every page in a column; the page in the middle of the screen is the current one.
  const strip = () => {
    const slots = Array.from({ length: pageCount }, (_, i) => h("div", { class: "wl-slot", "data-page": String(i) }, h("p", { class: "muted center" }, `Page ${i + 1}`)));
    const attached = new Set<number>();
    const attach = (index: number) => {
      if (attached.has(index)) return;
      attached.add(index);
      const slot = slots[index];
      image(index).promise.then((url) => {
        if (!attached.has(index)) return;
        slot.style.height = "";
        replace(slot, h("img", { class: "wl-page", src: url, alt: `Page ${index + 1}` }));
      }, (error) => {
        if (!attached.has(index)) return;
        replace(slot, pageError(index, error, () => { attached.delete(index); release(index); attach(index); }));
      });
    };
    const detach = (index: number) => {
      if (!attached.delete(index)) return;
      const slot = slots[index];
      // Keep the height so the strip doesn't jump; the image comes back when it's near again.
      if (slot.querySelector("img")) slot.style.height = `${slot.offsetHeight}px`;
      replace(slot, h("p", { class: "muted center" }, `Page ${index + 1}`));
    };
    let frame = 0;
    const update = () => {
      frame = 0;
      const middle = window.innerHeight / 2;
      // The last page whose top is above the middle of the screen (binary search: slots are in order).
      let lo = 0, hi = pageCount - 1, found = 0;
      while (lo <= hi) {
        const mid = (lo + hi) >> 1;
        if (slots[mid].getBoundingClientRect().top <= middle) { found = mid; lo = mid + 1; } else hi = mid - 1;
      }
      const nearBottom = window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 4;
      const index = nearBottom ? pageCount - 1 : found;
      if (index !== current || !attached.size) setCurrent(index);
      around(index);
      const keep = keepWindow(index, pageCount);
      for (let i = Math.max(0, index - 1); i <= Math.min(pageCount - 1, index + 3); i++) attach(i);
      for (const i of [...attached]) if (i < keep.from || i > keep.to) detach(i);
    };
    const onScroll = () => { if (!frame) frame = requestAnimationFrame(update); };
    const container = h("div", { class: "wl-strip" }, ...slots, endPanel());
    replace(body, container);
    const start = current;
    for (let i = Math.max(0, start - 1); i <= Math.min(pageCount - 1, start + 3); i++) attach(i);
    setCurrent(start);
    around(start);
    if (start > 0) requestAnimationFrame(() => slots[start].scrollIntoView({ block: "start" }));
    // After the first scroll into place, follow the reader.
    const listen = setTimeout(() => { window.addEventListener("scroll", onScroll, { passive: true }); window.addEventListener("resize", onScroll); }, 200);
    return () => {
      clearTimeout(listen);
      if (frame) cancelAnimationFrame(frame);
      window.removeEventListener("scroll", onScroll);
      window.removeEventListener("resize", onScroll);
      attached.clear();
    };
  };

  const build = () => {
    bodyStop();
    bodyStop = layout.mode === "paged" ? paged() : strip();
    replace(toggles,
      h("button", { class: "chip", onclick: () => switchLayout({ ...layout, mode: layout.mode === "paged" ? "strip" : "paged" }) },
        layout.mode === "paged" ? "Long strip" : "Paged"),
      layout.mode === "paged" ? h("button", { class: "chip", "aria-pressed": String(layout.rtl), onclick: () => switchLayout({ ...layout, rtl: !layout.rtl }) },
        layout.rtl ? "Right to left" : "Left to right") : null);
    element.classList.toggle("rtl", layout.mode === "paged" && layout.rtl);
  };
  const switchLayout = (next: ReaderLayout) => {
    layout = next;
    session.layouts.set(mangaId, next);
    build();
  };

  Promise.all([details(mangaId), link.client.request<ChapterPages>("chapter.pages", { chapterId })]).then(([loaded, pages]) => {
    if (!alive) return;
    manga = loaded;
    const chapter = loaded.chapters.find((c) => c.chapterId === chapterId) ?? null;
    title.textContent = `${loaded.title} • ${chapter ? chapterLabel(chapter) : "Chapter"}`;
    pageCount = Math.max(0, Number(pages.pageCount) || 0);
    if (!pageCount) { replace(body, h("p", { class: "muted" }, "This chapter has no pages."), endPanel()); return; }
    if (!session.layouts.has(mangaId)) layout = initialLayout(loaded.readingMode);
    current = startPage(chapter, pageCount);
    throttle = new ProgressThrottle(pageCount, (lastPageRead, read) => {
      link.client.request("chapter.progress", { chapterId, lastPageRead, read }).catch(() => undefined);
    });
    build();
  }, (error) => {
    if (!alive) return;
    replace(body, h("p", { class: "error", role: "status" }, requestErrorText(error)),
      h("button", { onclick: () => { if (view?.key === `reader|${chapterId}`) { view.stop(); view = null; } redraw(); } }, "Try again"));
  });

  return {
    element,
    stop: () => {
      if (!alive) return;
      alive = false;
      bodyStop();
      throttle?.flush();
      if (throttle) { session.details.delete(mangaId); session.libraryStale = true; }
      for (const index of [...images.keys()]) release(index);
    },
  };
}
