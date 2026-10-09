// Development only (never in production builds): a fake phone inside this page, reached over a real WebRTC
// connection between two RTCPeerConnections. It exercises the same offer/answer (non-trickle), data channel,
// framing and chunked images as the real phone; only Firestore is replaced by a direct hand-over of the SDPs.
import { ICE_SERVERS, Signaling, waitForGathering } from "./connection";
import { FakePhone, FakeImage } from "./fake-phone";

/** A page or cover drawn on a canvas, so chunking is exercised with real, multi-chunk images. */
export async function drawImage(kind: "cover" | "page", key: string, index: number): Promise<FakeImage> {
  const [width, height] = kind === "cover" ? [300, 450] : [900, 1300];
  const canvas = new OffscreenCanvas(width, height);
  const g = canvas.getContext("2d")!;
  const hue = (Number(key.slice(-3)) * 47 + index * 23) % 360;
  g.fillStyle = `hsl(${hue} 45% ${kind === "cover" ? 40 : 92}%)`;
  g.fillRect(0, 0, width, height);
  // Noise makes the JPEG large enough to need several chunks.
  for (let i = 0; i < 400; i++) {
    g.fillStyle = `hsl(${(hue + i * 7) % 360} 50% 50% / .25)`;
    g.fillRect((i * 97) % width, (i * 61) % height, 30, 30);
  }
  g.fillStyle = kind === "cover" ? "#fff" : "#222";
  g.font = `700 ${kind === "cover" ? 42 : 120}px system-ui, sans-serif`;
  g.textAlign = "center";
  g.fillText(kind === "cover" ? `#${key}` : `Page ${index + 1}`, width / 2, height / 2);
  if (kind === "page") { g.font = "40px system-ui, sans-serif"; g.fillText(`chapter ${key}`, width / 2, height / 2 + 80); }
  const blob = await canvas.convertToBlob({ type: "image/jpeg", quality: 0.9 });
  return { contentType: "image/jpeg", bytes: new Uint8Array(await blob.arrayBuffer()) };
}

/** Signaling that answers with an in-page fake phone. */
export function loopbackSignaling(delayMs = 150): Signaling & { phone: () => FakePhone | null } {
  let phonePc: RTCPeerConnection | null = null;
  let phone: FakePhone | null = null;
  const signaling: Signaling & { phone: () => FakePhone | null } = {
    phone: () => phone,
    async exchange(offer) {
      const pc = new RTCPeerConnection({ iceServers: ICE_SERVERS });
      phonePc = pc;
      pc.ondatachannel = (event) => {
        const channel = event.channel;
        channel.binaryType = "arraybuffer";
        phone = new FakePhone((text) => channel.send(text), (frame) => channel.send(frame as Uint8Array<ArrayBuffer>), { image: drawImage, delayMs });
        channel.onmessage = (message) => { if (typeof message.data === "string") phone!.handle(message.data); };
        channel.onclose = () => signaling.onClosed?.();
      };
      await pc.setRemoteDescription({ type: "offer", sdp: offer });
      await pc.setLocalDescription(await pc.createAnswer());
      await waitForGathering(pc);
      return pc.localDescription!.sdp;
    },
    close() { phonePc?.close(); phonePc = null; },
  };
  return signaling;
}
