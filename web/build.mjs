// Bundles the web companion into dist/ (mirrored to backend/hosting/) and renders the privacy policy from docs/PRIVACY_POLICY.md.
import { build } from "esbuild";
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { homedir } from "node:os";
import { join } from "node:path";
import { marked } from "marked";

rmSync("dist", { recursive: true, force: true });
mkdirSync("dist", { recursive: true });
await build({
  entryPoints: ["src/main.ts"],
  bundle: true,
  format: "esm",
  target: "es2022",
  minify: true,
  sourcemap: true,
  outfile: "dist/app.js",
  // Production config is injected at build time; local builds talk to the emulators.
  // __DEV_TOOLS__: the demo phone for "My library" (src/weblibrary/dev-loopback.ts, fake-phone.ts), local builds only.
  define: {
    __FIREBASE_CONFIG__: JSON.stringify(process.env.MIHON_WEB_FIREBASE_CONFIG ?? ""),
    __DEV_TOOLS__: JSON.stringify(!process.env.MIHON_WEB_FIREBASE_CONFIG),
  },
});
// The demo phone must never reach the production site.
if (process.env.MIHON_WEB_FIREBASE_CONFIG && readFileSync("dist/app.js", "utf8").includes("Demo phone")) {
  throw new Error("The development demo phone ended up in a production bundle");
}
cpSync("static", "dist", { recursive: true });

// Static legal pages from the docs (no app script): privacy policy and terms of use.
const legalPage = (markdown, title, path) => readFileSync("static/index.html", "utf8")
  .replace("<title>Mihon Together</title>", `<title>${title} – Mihon Together</title>`)
  .replace('<link rel="canonical" href="https://mihontogether.web.app/">', `<link rel="canonical" href="https://mihontogether.web.app${path}">`)
  .replace('<main id="app"></main>', `<main id="policy" class="policy">${marked.parse(readFileSync(markdown, "utf8"))}</main>`)
  .replace('<script type="module" src="/app.js"></script>', "");
writeFileSync("dist/privacy.html", legalPage("../docs/PRIVACY_POLICY.md", "Privacy policy", "/privacy"));
writeFileSync("dist/terms.html", legalPage("../docs/TERMS.md", "Terms of use", "/terms"));

// Patch notes for the website (and the app's update screen, through apk.json): docs/WHATS_NEW.md, one "## " section
// per release, newest first. Plain text items only, so nothing in the file is ever treated as HTML.
mkdirSync("dist/files", { recursive: true });
const patchNotes = (existsSync("../docs/WHATS_NEW.md") ? readFileSync("../docs/WHATS_NEW.md", "utf8") : "")
  .split(/^## /m).slice(1).map((section) => {
    const [title, ...lines] = section.split("\n");
    return { title: title.trim(), items: lines.map((line) => line.trim()).filter((line) => line.startsWith("- ")).map((line) => line.slice(2)) };
  });
writeFileSync("dist/files/patch-notes.json", JSON.stringify(patchNotes));

// The Android APK for /download (arm64, the common phone build), from the production build's output folder.
// Never committed: dist/ and backend/hosting/ are git-ignored. Without the file the page says it's unavailable.
const apk = process.env.MIHON_WEB_APK ?? join(homedir(), "Desktop", "MihonTogether-online", "MihonTogether-online-arm64.apk");
if (process.env.MIHON_WEB_FIREBASE_CONFIG && existsSync(apk)) {
  mkdirSync("dist/files", { recursive: true });
  const bytes = readFileSync(apk);
  // MIHON_WEB_APK_URL: the same APK as a GitHub release asset (cheaper than Hosting traffic). Newer apps and the
  // download page use it; older apps only know /files/, so the copy here stays unless MIHON_WEB_HOST_APK=0.
  const externalUrl = process.env.MIHON_WEB_APK_URL || null;
  if (process.env.MIHON_WEB_HOST_APK !== "0") writeFileSync("dist/files/MihonTogether.apk", bytes);
  // The app's update check compares `build` (the commit count at the end of versionName) with its own.
  const metadataFile = join(apk, "..", "output-metadata.json");
  const versionName = existsSync(metadataFile)
    ? JSON.parse(readFileSync(metadataFile, "utf8")).elements?.find((e) => e.filters?.some((f) => f.value === "arm64-v8a"))?.versionName ?? null
    : null;
  const build = Number(versionName?.split("-").pop()) || null;
  // "What's new" for the app's update screen: the newest patch notes.
  const whatsNew = (patchNotes[0]?.items ?? []).map((item) => `- ${item}`).join("\n");
  writeFileSync("dist/files/apk.json", JSON.stringify({
    file: "MihonTogether.apk", mb: statSync(apk).size / 1048576,
    sha256: createHash("sha256").update(bytes).digest("hex").toUpperCase(),
    built: statSync(apk).mtime.toISOString().slice(0, 10),
    version: versionName, build, notes: whatsNew.slice(0, 2000), ...(externalUrl ? { url: externalUrl } : {}),
  }));
  console.log(`bundled ${apk}`);
}

// Firebase Hosting only serves folders inside its project directory (backend/), so mirror dist/ there.
rmSync("../backend/hosting", { recursive: true, force: true });
cpSync("dist", "../backend/hosting", { recursive: true });
console.log("built dist/ (mirrored to backend/hosting/)");
