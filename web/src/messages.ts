// Messages, private and group chats, and read-along rooms as chat-only pages (moved out of main.ts, audit finding 6).
// main.ts owns the session state, routing and actions; it hands them over once with initMessages().
import * as api from "./api";
import * as social from "./api-social";
import { coverImage } from "./covers";
import { h, replace } from "./dom";
import { isReactionEmoji, QUICK_REACTIONS, reactionChips, relativeTime, visibleQuote, withoutBlocked } from "./logic";

/** What these pages read from the session state. */
export interface MessagesState {
  friends: Map<string, api.Profile>;
  chats: social.ChatSummary[];
  groups: social.GroupSummary[];
  rooms: social.Room[];
  blocks: Set<string>;
  busy: boolean;
  message: { text: string; error: boolean } | null;
}

/** The parts of main.ts these pages use. */
export interface MessagesContext {
  state: MessagesState;
  render(): void;
  run(action: () => Promise<unknown>, success?: string): Promise<void>;
  navigate(path: string): void;
  link(path: string, label: string): HTMLElement;
  describe(error: unknown): string;
  field(key: string, attrs: Record<string, string>): HTMLInputElement;
  notificationControls(compact: boolean): HTMLElement | null;
  avatar(profile: api.Profile, size: number): HTMLElement;
  cachedPage(path: string, build: (container: HTMLElement) => () => void): HTMLElement;
}

let ctx: MessagesContext;
export function initMessages(context: MessagesContext): void { ctx = context; }

/** A recommended title in a message: cover and name (open it in the app to read). */
function titleCard(content: social.SharedTitle | null): HTMLElement | null {
  if (!content) return null;
  return h("div", { class: "title-card" }, coverImage(content.title, content.coverUrl),
    h("div", {}, h("div", { class: "muted small" }, "Recommended"), h("strong", {}, content.title),
      content.source ? h("div", { class: "muted small" }, content.source) : null));
}

/** New group: a name and some friends. */
function newGroupForm(): HTMLElement {
  const name = ctx.field("groupName", { placeholder: "Group name", maxlength: "40" });
  const boxes = [...ctx.state.friends.values()].sort((a, b) => a.displayName.localeCompare(b.displayName)).map((friend) => {
    const box = h("input", { type: "checkbox", value: friend.uid }) as HTMLInputElement;
    return { box, row: h("label", { class: "row small" }, box, ` ${friend.displayName}`) };
  });
  return h("details", { class: "card inset" }, h("summary", {}, "New group"),
    name, ...boxes.map((b) => b.row),
    h("button", { disabled: ctx.state.busy, onclick: () => {
      const uids = boxes.filter((b) => b.box.checked).map((b) => b.box.value).slice(0, 19);
      const title = name.value.replace(/\n/g, " ").trim();
      if (!title || !uids.length) { ctx.state.message = { text: "Name the group and pick at least one friend.", error: true }; ctx.render(); return; }
      ctx.run(async () => { const id = await social.createGroup(title, uids); name.value = ""; ctx.navigate(`/group/${id}`); });
    } }, "Create group"));
}

export function messagesView(): HTMLElement {
  const chats = ctx.state.chats.filter((chat) => ctx.state.friends.has(chat.otherUid));
  return h("section", { class: "card" }, h("h3", {}, "Messages"),
    ctx.notificationControls(true),
    ctx.state.friends.size ? newGroupForm() : null,
    ...ctx.state.groups.map((group) => h("a", { class: "friend", href: `/group/${group.groupId}`, onclick: (event) => { event.preventDefault(); ctx.navigate(`/group/${group.groupId}`); } },
      h("div", { class: "avatar group" }, group.name.slice(0, 1).toUpperCase()),
      h("div", { class: "grow" }, h("strong", {}, group.name),
        h("div", { class: "muted small ellipsis" }, group.lastText ? `${group.lastSenderName ? `${group.lastSenderName}: ` : ""}${group.lastText}` : `${group.members} members`)),
      group.unread > 0 ? h("span", { class: "badge" }, String(group.unread)) : null)),
    chats.length === 0 && ctx.state.groups.length === 0 ? h("p", { class: "muted" }, "No conversations yet. Open a friend and choose Message.") : null,
    ...chats.map((chat) => {
      const friend = ctx.state.friends.get(chat.otherUid)!;
      return h("a", { class: "friend", href: `/chat/${chat.otherUid}`, onclick: (event) => { event.preventDefault(); ctx.navigate(`/chat/${chat.otherUid}`); } },
        ctx.avatar(friend, 40),
        h("div", { class: "grow" }, h("strong", {}, friend.displayName),
          h("div", { class: "muted small ellipsis" }, chat.lastSenderUid === api.me() ? `You: ${chat.lastText}` : chat.lastText)),
        chat.unread > 0 ? h("span", { class: "badge" }, String(chat.unread)) : null);
    }));
}

/** What chat replies and reactions need from a message (private or group). */
interface Reactable { seq: number; text: string; content: social.SharedTitle | null; replyTo: social.ReplyQuote | null; reactions: Record<string, string> }
/**
 * Replies and reactions for one chat page: the reply bar above the input, and per message its quote (tap to jump to
 * the original), reaction chips (tap to toggle mine) and Reply / React actions. [redraw] re-renders the list.
 */
function chatTools(list: HTMLElement, status: HTMLElement, me: string, react: (seq: number, emoji: string) => Promise<unknown>, redraw: () => void) {
  let replying: { seq: number; name: string; preview: string } | null = null;
  let picking: number | null = null;
  const bar = h("div", { class: "reply-bar", hidden: true });
  const showBar = () => {
    bar.hidden = !replying;
    if (replying) replace(bar, h("div", { class: "grow ellipsis small" }, h("strong", {}, `Replying to ${replying.name}`), ` ${replying.preview}`),
      h("button", { class: "secondary", "aria-label": "Cancel reply", onclick: () => { replying = null; showBar(); } }, "×"));
  };
  const toggle = (seq: number, emoji: string) => {
    picking = null;
    react(seq, emoji).catch((error) => { status.textContent = ctx.describe(error); });
    redraw();
  };
  const preview = (m: Reactable) => (m.content ? `📖 ${m.content.title}` : m.text).slice(0, 100);
  return {
    bar,
    replyTo: () => replying?.seq ?? null,
    clearReply: () => { replying = null; showBar(); },
    /** The quote inside a reply's bubble; [name] resolves the original sender; [visible] false shows a placeholder. */
    quote(m: Reactable, name: (quote: social.ReplyQuote) => string, visible: (quote: social.ReplyQuote) => boolean = () => true): HTMLElement | null {
      const q = m.replyTo;
      if (!q) return null;
      if (!visible(q)) return h("div", { class: "quote" }, h("strong", { class: "small" }, "Earlier message"));
      return h("div", { class: "quote", role: "button", tabindex: "0", onclick: () => {
        list.querySelector<HTMLElement>(`[data-seq="${q.seq}"]`)?.scrollIntoView({ behavior: "smooth", block: "center" });
      } }, h("strong", { class: "small" }, name(q)), h("div", { class: "small ellipsis" }, q.preview));
    },
    /** Reaction chips, then Reply / React (and, while picking, the quick emojis and "+"). */
    footer(m: Reactable, senderName: string): HTMLElement {
      const chips = reactionChips(m.reactions, me).map((chip) => h("button", { class: chip.mine ? "reaction mine" : "reaction",
        "aria-pressed": chip.mine ? "true" : "false", onclick: () => toggle(m.seq, chip.emoji) }, `${chip.emoji} ${chip.count}`));
      const pick = picking === m.seq ? h("div", { class: "reaction-picker" },
        ...QUICK_REACTIONS.map((emoji) => h("button", { class: "emoji", onclick: () => toggle(m.seq, emoji) }, emoji)),
        h("button", { class: "emoji", "aria-label": "Any emoji", onclick: () => {
          const emoji = prompt("React with any emoji")?.trim();
          if (!emoji) return;
          if (isReactionEmoji(emoji)) toggle(m.seq, emoji);
          else status.textContent = "Pick a single emoji.";
        } }, "+")) : null;
      return h("div", {}, chips.length ? h("div", { class: "reactions" }, ...chips) : null, pick,
        h("div", { class: "msg-actions" },
          h("button", { class: "link", onclick: () => { replying = { seq: m.seq, name: senderName, preview: preview(m) }; showBar(); } }, "Reply"),
          h("button", { class: "link", onclick: () => { picking = picking === m.seq ? null : m.seq; redraw(); } }, "React")));
    },
  };
}

export function chatView(otherUid: string): HTMLElement {
  return ctx.cachedPage(`/chat/${otherUid}`, (container) => {
    const me = api.me() ?? "";
    let stopMessages: (() => void) | null = null;
    let chatId: string | null = null;
    let messages: social.ChatMessage[] = [];
    const list = h("div", { class: "chat" });
    const input = h("input", { placeholder: "Message", maxlength: "500" }) as HTMLInputElement;
    const status = h("p", { class: "muted small" }, "Only the two of you can read this. Messages disappear after 30 days.");
    const friend = ctx.state.friends.get(otherUid);
    const nameOf = (uid: string) => (uid === me ? "You" : friend?.displayName ?? "");
    const draw = () => replace(list, ...messages.map((m) => {
      // Server lines (read matches, recaps) take no replies or reactions.
      const tools = m.system ? null : chat;
      return h("div", { class: m.senderUid === me ? "bubble mine" : "bubble", "data-seq": String(m.seq) },
        tools?.quote(m, (q) => nameOf(q.senderUid)), titleCard(m.content), m.text || null,
        h("div", { class: "muted small" }, relativeTime(m.createdAt)), tools?.footer(m, nameOf(m.senderUid)));
    }));
    const chat = chatTools(list, status, me, (seq, emoji) => social.reactToMessage(otherUid, seq, emoji), draw);
    const send = async () => {
      const text = input.value.replace(/\n/g, " ").trim();
      if (!text) return;
      const replyTo = chat.replyTo();
      // The text and reply stay until the message went out, so a failed one can be sent again.
      try {
        await social.sendMessage(otherUid, text, replyTo);
        if (input.value.replace(/\n/g, " ").trim() === text) input.value = "";
        chat.clearReply();
      } catch (error) { status.textContent = ctx.describe(error); }
    };
    input.addEventListener("keydown", (event) => { if (event.key === "Enter") send(); });
    replace(container, ctx.link("/messages", "← Messages"), h("h2", {}, friend?.displayName ?? "Chat"), list, chat.bar,
      h("div", { class: "row" }, input, h("button", { onclick: send }, "Send")), status);
    const watch = () => {
      const summary = ctx.state.chats.find((c) => c.otherUid === otherUid);
      if (summary?.unread) social.markChatRead(otherUid).catch(() => undefined);
      if (!summary || summary.chatId === chatId) return;
      chatId = summary.chatId;
      stopMessages?.();
      stopMessages = social.watchChatMessages(chatId, (latest) => {
        const grew = latest.at(-1)?.seq !== messages.at(-1)?.seq;
        messages = latest;
        draw();
        // Only new messages scroll down; a reaction keeps the place.
        if (grew) list.scrollTop = list.scrollHeight;
      });
    };
    chatWatchers.add(watch);
    watch();
    if (!ctx.state.chats.some((c) => c.otherUid === otherUid)) replace(list, h("p", { class: "muted" }, "No messages yet. Say hi!"));
    return () => { chatWatchers.delete(watch); stopMessages?.(); };
  });
}
export const chatWatchers = new Set<() => void>();

const GROUP_EVENTS: Record<string, (who: string, subject: string) => string> = {
  created: (who, subject) => `${who} created the group “${subject}”`,
  added: (who, subject) => `${who} added ${subject}`,
  removed: (who, subject) => `${who} removed ${subject}`,
  left: (_, subject) => `${subject} left`,
  owner: (_, subject) => `${subject} is now the group owner`,
  renamed: (who, subject) => `${who} renamed the group to “${subject}”`,
};
export function groupView(groupId: string): HTMLElement {
  return ctx.cachedPage(`/group/${groupId}`, (container) => {
    const me = api.me() ?? "";
    let stopLines: (() => void) | null = null;
    let since: number | null = null;
    const title = h("h2", {}, ctx.state.groups.find((g) => g.groupId === groupId)?.name ?? "Group");
    const members = h("p", { class: "muted small" });
    let lines: social.GroupLine[] = [];
    const list = h("div", { class: "chat" });
    const input = h("input", { placeholder: "Message", maxlength: "500" }) as HTMLInputElement;
    const status = h("p", { class: "muted small" }, "Only the group's members can read this. Messages disappear after 30 days.");
    const draw = () => replace(list, ...lines.map((line, i) => {
      const who = line.senderUid === me ? "You" : line.senderName;
      if (line.kind === "event") return h("div", { class: "muted small center" }, GROUP_EVENTS[line.event ?? ""]?.(who, line.text) ?? "");
      const named = line.senderUid !== me && (i === 0 || lines[i - 1].senderUid !== line.senderUid || lines[i - 1].kind === "event");
      return h("div", { class: line.senderUid === me ? "bubble mine" : "bubble", "data-seq": String(line.seq) },
        named ? h("div", { class: "small sender" }, line.senderName) : null,
        chat.quote(line, (q) => (q.senderUid === me ? "You" : q.senderName ?? ""), (q) => visibleQuote(q, since ?? Number.MAX_SAFE_INTEGER, ctx.state.blocks) !== null),
        titleCard(line.content), line.text || null,
        h("div", { class: "muted small" }, relativeTime(line.createdAt)), chat.footer(line, who));
    }));
    const chat = chatTools(list, status, me, (seq, emoji) => social.reactInGroup(groupId, seq, emoji), draw);
    const send = async () => {
      const text = input.value.replace(/\n/g, " ").trim();
      if (!text) return;
      const replyTo = chat.replyTo();
      try {
        await social.sendGroupLine(groupId, text, replyTo);
        if (input.value.replace(/\n/g, " ").trim() === text) input.value = "";
        chat.clearReply();
      } catch (error) { status.textContent = ctx.describe(error); }
    };
    input.addEventListener("keydown", (event) => { if (event.key === "Enter") send(); });
    const leave = h("button", { class: "secondary", onclick: () => {
      if (confirm("Leave this group? You won't see its messages any more.")) ctx.run(async () => { await social.leaveGroup(groupId); ctx.navigate("/messages"); });
    } }, "Leave group");
    replace(container, ctx.link("/messages", "← Messages"), title, members, list, chat.bar,
      h("div", { class: "row" }, input, h("button", { onclick: send }, "Send")), status, leave);
    const stopGroup = social.watchGroup(groupId, (group) => {
      const mine = group?.members.find((m) => m.uid === me);
      if (!group || !mine) { lines = []; replace(list, h("p", { class: "error", role: "status" },"You're no longer in this group.")); stopLines?.(); since = null; return; }
      title.textContent = group.name;
      members.textContent = group.members.map((m) => (m.uid === me ? "You" : m.name) + (m.uid === group.ownerUid ? " (owner)" : "")).join(", ");
      if (since === mine.since) return;
      since = mine.since;
      stopLines?.();
      stopLines = social.watchGroupLines(groupId, mine.since, (latest) => {
        // People I blocked: their lines and reactions stay hidden (quotes of them too, see draw).
        const shown = latest.filter((line) => line.kind === "event" || !ctx.state.blocks.has(line.senderUid))
          .map((line) => ({ ...line, reactions: withoutBlocked(line.reactions, ctx.state.blocks) }));
        const grew = shown.at(-1)?.seq !== lines.at(-1)?.seq;
        lines = shown;
        draw();
        if (grew) list.scrollTop = list.scrollHeight;
      });
    });
    const watch = () => { if (ctx.state.groups.find((g) => g.groupId === groupId)?.unread) social.markGroupRead(groupId).catch(() => undefined); };
    groupWatchers.add(watch);
    watch();
    return () => { groupWatchers.delete(watch); stopGroup(); stopLines?.(); };
  });
}
export const groupWatchers = new Set<() => void>();

export function roomView(roomId: string): HTMLElement {
  return ctx.cachedPage(`/room/${roomId}`, (container) => {
    const me = api.me() ?? "";
    const info = h("div", {});
    const lines = h("div", { class: "chat" });
    const input = h("input", { placeholder: "Message the room", maxlength: "200" }) as HTMLInputElement;
    const status = h("p", { class: "muted small" });
    const say = async (text: string | null, emoji: string | null) => {
      try { await social.sendRoomLine(roomId, text, emoji); status.textContent = ""; } catch (error) { status.textContent = ctx.describe(error); }
    };
    const sendText = () => { const text = input.value.trim(); if (text) { input.value = ""; say(text, null); } };
    input.addEventListener("keydown", (event) => { if (event.key === "Enter") sendText(); });
    const nameOf = (uid: string) => ctx.state.rooms.find((r) => r.id === roomId)?.members[uid]?.name || "Someone";
    const eventText = (kind: string, name: string) => ({ joined: `${name} joined`, left: `${name} left`, removed: `${name} was removed by the host`, host: `${name} is now the host` } as Record<string, string>)[kind];
    replace(container, ctx.link("/", "← Friends"), info, lines,
      h("div", { class: "row" }, input, h("button", { onclick: sendText }, "Send")),
      h("div", { class: "row" }, ...social.ROOM_REACTIONS.map((emoji) => h("button", { class: "secondary emoji", onclick: () => say(null, emoji) }, emoji))),
      status);
    const drawInfo = () => {
      const room = ctx.state.rooms.find((r) => r.id === roomId);
      if (!room) { replace(info, h("p", { class: "muted" }, "This room has ended.")); return; }
      replace(info, h("h2", {}, room.title),
        h("p", { class: "muted small" }, room.chapterLabel ? `Host at ${room.chapterLabel}, page ${room.page}` : "Waiting for the host"),
        h("p", {}, `In this room (${room.participants.length} of ${room.maxMembers}): `,
          room.participants.map((uid) => `${uid === me ? "You" : room.members[uid]?.name || "?"}${uid === room.hostUid ? " (host)" : room.members[uid]?.state === "invited" ? " (invited)" : ""}`).join(", ")));
    };
    roomWatchers.add(drawInfo);
    drawInfo();
    const stop = social.watchRoomLines(roomId, (items) => {
      replace(lines, ...items.map((line) => line.kind === "ready" || line.kind === "wait"
        ? h("div", { class: "muted small" }, line.kind === "ready" ? `✅ ${nameOf(line.senderUid)} is ready for the next chapter` : `⏸ ${nameOf(line.senderUid)} asks everyone to wait`)
        : line.kind in { joined: 1, left: 1, removed: 1, host: 1 }
        ? h("div", { class: "muted small" }, eventText(line.kind, nameOf(line.senderUid)))
        : h("div", { class: line.senderUid === me ? "bubble mine" : "bubble" },
          h("div", { class: "muted small" }, line.senderUid === me ? "You" : nameOf(line.senderUid)),
          line.emoji ? h("span", { class: "big" }, line.emoji) : line.text)));
      lines.scrollTop = lines.scrollHeight;
    });
    return () => { roomWatchers.delete(drawInfo); stop(); };
  });
}
export const roomWatchers = new Set<() => void>();
