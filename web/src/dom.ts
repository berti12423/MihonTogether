/**
 * Tiny element builder. Strings always become text nodes (never HTML), so user-controlled values such as
 * display names, bios and titles cannot inject markup.
 */
type Child = Node | string | number | null | undefined | false;
type Attrs = Record<string, string | boolean | ((event: Event) => void) | undefined>;

export function h(tag: string, attrs: Attrs = {}, ...children: Child[]): HTMLElement {
  const element = document.createElement(tag);
  for (const [name, value] of Object.entries(attrs)) {
    if (value === undefined || value === false) continue;
    if (typeof value === "function") element.addEventListener(name.replace(/^on/, ""), value);
    // Through CSSOM: the site's CSP (style-src 'self') blocks style attributes set as markup.
    else if (name === "style") element.style.cssText = value as string;
    else if (value === true) element.setAttribute(name, "");
    else element.setAttribute(name, value);
  }
  for (const child of children) {
    if (child === null || child === undefined || child === false) continue;
    element.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return element;
}

export function replace(target: HTMLElement, ...children: Child[]): void {
  target.replaceChildren(...children.filter((c): c is Node | string | number => c !== null && c !== undefined && c !== false)
    .map((c) => (c instanceof Node ? c : document.createTextNode(String(c)))));
}
