// Relay (TURN) credentials for the web library, so a phone and a browser on different networks can connect when a
// direct connection fails. The long-term Cloudflare key stays here; clients only get credentials that expire.
// API: https://developers.cloudflare.com/realtime/turn/generate-credentials/

export interface IceServer { urls: string[]; username: string; credential: string }
/** Longer than any reading session: the relay stops forwarding when the credentials expire. */
export const TURN_TTL_SECONDS = 12 * 3600;
/** Calls per account per day (each connection uses two: the browser and the phone). */
export const ICE_SERVERS_PER_DAY = 200;
const KEY_ID = /^[0-9a-f]{32}$/;
// UDP for speed, TLS on 443 for networks that block everything else. The rest only slow down the (non-trickle)
// candidate gathering, and browsers block port 53.
const KEEP = /^(turn:[a-z0-9.-]+:3478\?transport=udp|turns:[a-z0-9.-]+:443\?transport=tcp)$/;
type Fetcher = (url: string, init: { method: string; headers: Record<string, string>; body: string; signal?: AbortSignal }) =>
  Promise<{ ok: boolean; status: number; json(): Promise<unknown> }>;

/** Short-lived relay servers, or none when the relay isn't set up or Cloudflare fails (clients then connect directly). */
export async function turnServers(keyId: string, token: string, fetcher: Fetcher = fetch): Promise<IceServer[]> {
  if (!KEY_ID.test(keyId) || !token) return [];
  try {
    const response = await fetcher(`https://rtc.live.cloudflare.com/v1/turn/keys/${keyId}/credentials/generate-ice-servers`, {
      method: "POST", headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ ttl: TURN_TTL_SECONDS }), signal: AbortSignal.timeout(5_000),
    });
    if (!response.ok) { console.warn("relay credentials refused", response.status); return []; }
    const list = (await response.json() as { iceServers?: unknown })?.iceServers;
    if (!Array.isArray(list)) return [];
    return list.flatMap((server: { urls?: unknown; username?: unknown; credential?: unknown }) => {
      const urls = (Array.isArray(server?.urls) ? server.urls : []).filter((url): url is string => typeof url === "string" && KEEP.test(url));
      return urls.length && typeof server.username === "string" && typeof server.credential === "string"
        ? [{ urls, username: server.username, credential: server.credential }] : [];
    });
  } catch (error) {
    console.warn("relay credentials failed", error instanceof Error ? error.message : error);
    return [];
  }
}
