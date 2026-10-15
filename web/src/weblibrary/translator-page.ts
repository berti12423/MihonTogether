// /translator: what the Mihon Together Translator is, how to get it, and a live status line from its /health.
import { h, replace } from "../dom";
import { healthText, pageTranslator } from "./translator";
import { settingsCard } from "./translator-settings";

declare const __DEV_TOOLS__: boolean;

export const TRANSLATOR_ASSET = "MihonTogether-Translator-windows.zip";
const KOHARU_URL = "https://github.com/koharu-rs/koharu";

/** Fills [container] with the setup page; returns how to stop its status polling. */
export function translatorSetup(container: HTMLElement, options: { link: (path: string, label: string) => HTMLElement; githubUrl: string }): () => void {
  const status = h("div", { role: "status" });
  // Shown once the translator is ready; kept (not rebuilt) while it stays up, so edits aren't lost.
  const settingsBox = h("div");
  let settingsShown = false;
  let stop = () => undefined as void;
  const watch = () => {
    stop();
    const translator = pageTranslator();
    const draw = () => {
      const state = translator.state;
      if (state === "ready" && !settingsShown) {
        settingsShown = true;
        replace(settingsBox, settingsCard(() => { void translator.check(); }));
      } else if (state === "absent" && settingsShown) {
        settingsShown = false;
        replace(settingsBox);
      }
      const health = translator.health;
      const p = health?.progress;
      const dot = state === "ready" ? "dot on" : state === "absent" || state === "error" ? "dot bad" : "dot wait";
      replace(status,
        h("p", { class: "tr-status" }, h("span", { class: dot, "aria-hidden": "true" }), h("span", {}, h("strong", {}, "Status: "), healthText(state, health))),
        p && p.total > 0 ? h("progress", { class: "tr-progress", max: String(p.total), value: String(Math.min(p.done, p.total)) }) : null,
        state === "ready" && health ? h("p", { class: "muted small" },
          `Version ${health.version || "?"}${health.lastPageMs ? ` • last page took ${(health.lastPageMs / 1000).toFixed(1)} s` : ""}`,
          health.models.llm ? ` • translation model: ${health.models.llm}` : "") : null,
        state === "absent" ? h("p", { class: "muted small" }, "This page checks every few seconds; start the translator and it shows up here.") : null,
        __DEV_TOOLS__ && state === "absent" ? h("button", { class: "secondary", onclick: () => {
          import("./dev-translator").then(({ useDevTranslator }) => { useDevTranslator(); watch(); });
        } }, "Use a fake translator (development)") : null);
    };
    stop = translator.subscribe(draw);
    draw();
  };
  watch();
  const download = `${options.githubUrl}/releases/download/translator/${TRANSLATOR_ASSET}`;
  const releases = `${options.githubUrl}/releases/tag/translator`;
  replace(container, h("section", { class: "card" },
    h("h2", {}, "Translate pages on your PC"),
    h("p", {}, "The Mihon Together Translator turns raw manga pages (Japanese, Korean or Chinese) into English pages while you read ",
      options.link("/my-library", "My library"), " on this website. It finds the speech bubbles, reads the text, translates it, erases the original lettering and writes the translation in."),
    h("p", {}, "It's fully local: it runs on your own computer, needs no account or API keys, and no page is ever uploaded anywhere. Pages go from your phone to this browser to the translator on the same PC, and back."),
    status,
    h("h3", {}, "What you need"),
    h("ul", {},
      h("li", {}, "A Windows PC. An NVIDIA graphics card makes it fast; without one it works on the processor, but slowly."),
      h("li", {}, "About 12 GB of free space and download for the first start (the AI models). After that it works offline."),
      h("li", {}, "Chrome, Edge or Firefox on the same PC.")),
    h("h3", {}, "Get it"),
    h("p", {}, h("a", { class: "button", href: download, rel: "noopener" }, "Download for Windows"),
      " ", h("span", { class: "muted small" }, TRANSLATOR_ASSET, ", or see ", h("a", { href: releases, target: "_blank", rel: "noopener" }, "the release page"), ".")),
    h("ol", {},
      h("li", {}, `Unzip ${TRANSLATOR_ASSET} into a folder of its own.`),
      h("li", {}, "Start the Mihon Together Translator from that folder. If Windows SmartScreen warns about an unknown app, choose More info, then Run anyway."),
      h("li", {}, "The first start downloads the models (about 12 GB). Keep it open; the status above shows the progress and turns green when it's ready."),
      h("li", {}, "Open a chapter in ", options.link("/my-library", "My library"), " and turn on Translate in the reader's toolbar. The original page shows until its translation arrives; the Original button on a page switches back."),
      h("li", {}, "Keep the translator open while you read. Translated pages are kept in this browser, so going back to them is instant.")),
    h("p", { class: "muted small" }, "The translator only listens on this PC (127.0.0.1) and only answers this website. Sound effects and name tags stay as they are."),
    h("h3", {}, "Credits"),
    h("p", { class: "small" }, "Translation is done by ", h("a", { href: KOHARU_URL, target: "_blank", rel: "noopener" }, "Koharu"),
      ", an open-source manga translator, with its own models (detection, OCR, inpainting and a local language model). Mihon Together isn't affiliated with Koharu."),
  ), settingsBox);
  return () => stop();
}
