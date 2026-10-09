// The browser end of "My library": lists the signed-in user's phones, connects to one over a WebRTC data channel
// with Firestore signaling (non-trickle, 2 writes per connection), and runs the protocol client over it.
// Contract: docs/WEB_LIBRARY_PROTOCOL.md. Rules: backend/firestore.rules (libraryHosts, libraryLinks).
import { collection, deleteDoc, doc, onSnapshot, query, serverTimestamp, setDoc, Timestamp, updateDoc, where } from "firebase/firestore";
import { db } from "../firebase";
import { ConnectProblem, Hello, PROTOCOL_VERSION, ProtocolClient, ProtocolError, protocolMismatch } from "./protocol";

export const ICE_SERVERS: RTCIceServer[] = [{ urls: "stun:stun.l.google.com:19302" }];
const GATHER_MS = 3_000;
const ANSWER_MS = 20_000;
const OPEN_MS = 15_000;
const LINK_TTL_MS = 10 * 60_000;

export interface Phone { deviceId: string; name: string; appBuild: number; protocol: number; expiresAt: number }

const millis = (value: unknown): number => (value instanceof Timestamp ? value.toMillis() : Number(value ?? 0));

/** The signed-in user's phones that can serve right now (live; expired ones are dropped locally too). */
export function watchPhones(uid: string, onPhones: (phones: Phone[]) => void, onError: (error: unknown) => void): () => void {
  let latest: Phone[] = [];
  const emit = () => onPhones(latest.filter((phone) => phone.expiresAt > Date.now()));
  const stop = onSnapshot(query(collection(db, "libraryHosts", uid, "phones"), where("expiresAt", ">", Timestamp.now())), (snapshot) => {
    latest = snapshot.docs.map((d) => {
      const data = d.data();
      return { deviceId: d.id, name: String(data.name ?? "Phone").slice(0, 60), appBuild: Number(data.appBuild ?? 0),
        protocol: Number(data.protocol ?? 0), expiresAt: millis(data.expiresAt) };
    }).sort((a, b) => a.name.localeCompare(b.name));
    emit();
  }, onError);
  // A phone that stops heartbeating disappears once its expiresAt passes, without a new snapshot.
  const timer = setInterval(emit, 15_000);
  return () => { stop(); clearInterval(timer); };
}

export class ConnectError extends Error {
  constructor(readonly problem: ConnectProblem) { super(problem); }
}

/** Waits until ICE gathering is complete, or [ms] at most: the SDP then carries the candidates found so far. */
export function waitForGathering(pc: RTCPeerConnection, ms = GATHER_MS): Promise<void> {
  if (pc.iceGatheringState === "complete") return Promise.resolve();
  return new Promise((resolve) => {
    const done = () => { clearTimeout(timer); pc.removeEventListener("icegatheringstatechange", check); resolve(); };
    const check = () => { if (pc.iceGatheringState === "complete") done(); };
    const timer = setTimeout(done, ms);
    pc.addEventListener("icegatheringstatechange", check);
  });
}

/** How the offer reaches the phone and its answer comes back: Firestore in production, a loopback in development. */
export interface Signaling {
  /** Sends the complete offer SDP; resolves with the complete answer SDP. Rejects with ConnectError("no_answer"). */
  exchange(offer: string): Promise<string>;
  /** Called when the browser ends the session (best effort). */
  close(): void;
  /** Called by the signaling when the phone closes the session. */
  onClosed?: () => void;
}

/** Firestore signaling: one link document, created with the offer, answered by the phone, closed by either side. */
export function firestoreSignaling(uid: string, deviceId: string): Signaling {
  const linkId = crypto.randomUUID();
  const ref = doc(db, "libraryLinks", uid, "links", linkId);
  let stop: (() => void) | null = null;
  let created = false, closed = false;
  const signaling: Signaling = {
    async exchange(offer) {
      await setDoc(ref, { deviceId, offer, state: "offered", createdAt: serverTimestamp(), expiresAt: Timestamp.fromMillis(Date.now() + LINK_TTL_MS) });
      created = true;
      return new Promise<string>((resolve, reject) => {
        let answered = false;
        const timer = setTimeout(() => { if (!answered) reject(new ConnectError("no_answer")); }, ANSWER_MS);
        stop = onSnapshot(ref, (snapshot) => {
          const data = snapshot.data();
          if (!answered && data?.state === "answered" && typeof data.answer === "string") {
            answered = true;
            clearTimeout(timer);
            resolve(data.answer);
          } else if (answered && (!snapshot.exists() || data?.state === "closed")) {
            // The phone ended the session (or the link expired and was deleted).
            signaling.onClosed?.();
          } else if (!answered && (!snapshot.exists() || data?.state === "closed")) {
            clearTimeout(timer);
            reject(new ConnectError("no_answer"));
          }
        }, (error) => { clearTimeout(timer); if (!answered) reject(error); });
      });
    },
    close() {
      if (closed) return;
      closed = true;
      stop?.();
      if (created) updateDoc(ref, { state: "closed" }).catch(() => deleteDoc(ref).catch(() => undefined));
    },
  };
  return signaling;
}

export type LinkStatus = "connecting" | "connected" | "lost" | "closed";

/**
 * One session with one phone: the peer connection, the data channel and the protocol client. [connect] resolves
 * with the phone's `hello`; afterwards [onLost] fires once if the connection drops (not after [close]).
 */
export class PhoneLink {
  readonly client: ProtocolClient;
  status: LinkStatus = "connecting";
  hello: Hello | null = null;
  onLost: (() => void) | null = null;
  private readonly pc: RTCPeerConnection;
  private readonly channel: RTCDataChannel;

  constructor(private readonly signaling: Signaling, private readonly expectedProtocol: number | null = null) {
    if (typeof RTCPeerConnection === "undefined") throw new ConnectError("unsupported_browser");
    this.pc = new RTCPeerConnection({ iceServers: ICE_SERVERS });
    this.channel = this.pc.createDataChannel("library", { ordered: true });
    this.channel.binaryType = "arraybuffer";
    this.client = new ProtocolClient((text) => {
      if (this.channel.readyState !== "open") throw new Error("The connection is closed");
      this.channel.send(text);
    });
    this.channel.onmessage = (event) => this.client.receive(event.data as string | ArrayBuffer);
    this.signaling.onClosed = () => this.lose();
  }

  async connect(): Promise<Hello> {
    try {
      const mismatch = this.expectedProtocol === null ? null : protocolMismatch(this.expectedProtocol);
      if (mismatch) throw new ConnectError(mismatch);
      const opened = new Promise<void>((resolve, reject) => {
        this.channel.onopen = () => resolve();
        this.channel.onclose = () => reject(new ConnectError("no_connect"));
      });
      opened.catch(() => undefined); // awaited below; a close during signaling must not be an unhandled rejection
      await this.pc.setLocalDescription(await this.pc.createOffer());
      await waitForGathering(this.pc);
      const answer = await this.signaling.exchange(this.pc.localDescription!.sdp);
      if (this.status === "closed") throw new ConnectError("lost");
      await this.pc.setRemoteDescription({ type: "answer", sdp: answer });
      await withTimeout(opened, OPEN_MS, () => new ConnectError("no_connect"));
      this.channel.onclose = () => this.lose();
      this.pc.addEventListener("connectionstatechange", () => {
        if (this.pc.connectionState === "failed" || this.pc.connectionState === "closed") this.lose();
      });
      let hello: Hello;
      try {
        hello = await this.client.request<Hello>("hello", { protocol: PROTOCOL_VERSION, client: "web" });
      } catch (error) {
        if ((error as ProtocolError).code === "unsupported_protocol") throw new ConnectError(this.expectedProtocol !== null && this.expectedProtocol > PROTOCOL_VERSION ? "phone_newer" : "phone_older");
        throw error;
      }
      const mismatchAfter = protocolMismatch(Number(hello.protocol));
      if (mismatchAfter) throw new ConnectError(mismatchAfter);
      this.hello = hello;
      this.status = "connected";
      return hello;
    } catch (error) {
      this.shutdown();
      throw error;
    }
  }

  /** The user ended the session. */
  close(): void {
    if (this.status === "closed") return;
    this.shutdown();
  }

  private lose(): void {
    if (this.status !== "connected") return;
    this.shutdown();
    this.status = "lost";
    this.onLost?.();
  }

  private shutdown(): void {
    this.status = "closed";
    this.client.close(new ProtocolError("closed", "The connection to your phone was closed"));
    this.signaling.close();
    try { this.channel.close(); } catch { /* already closed */ }
    try { this.pc.close(); } catch { /* already closed */ }
  }
}

function withTimeout<T>(promise: Promise<T>, ms: number, error: () => Error): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(error()), ms);
    promise.then((value) => { clearTimeout(timer); resolve(value); }, (reason) => { clearTimeout(timer); reject(reason); });
  });
}
