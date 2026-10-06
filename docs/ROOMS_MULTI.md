# Design: reading rooms with more than two people

Status: **implemented 2026-10-03** (roadmap F6), with these choices: up to **6** people; option A (every member is the host's friend) with a consent line on group invites that names everyone; a **v2 schema replaces v1** outright (rooms live 90 s without heartbeats, so there's nothing to migrate); when the host leaves, the **longest-present joined member becomes host**, members who aren't the new host's friends are removed, and the chat shows "X is now the host". Details below are the original design; see `docs/SOCIAL_API.md` → "Group rooms" for the shipped protocol.

## Why v1 can't just grow

- The schema is fixed to two participants. `rooms/{id}` has `hostUid`, `guestUid` and `participants: [host, guest]`. The Rules validator (`validRoom`) requires exactly two participants, and `activeRoom` checks `friends(hostUid, guestUid)`.
- **Authorization is one friendship edge.** With 3+ people, A may be friends with B and C while B and C are strangers. Letting B and C see each other's presence in a room, or chat, would leak information between non-friends.
- Revocation is pairwise. Today, removing the one edge ends the room for both. With N people, one removal must eject one member, not end the room.
- The client coordinator assumes one "other" participant: `otherParticipant`, the accepted-edge revocation check, and "host is reading" UI.

## Options for the ACL

| Option | Rule | Pros | Cons |
| --- | --- | --- | --- |
| A. Host-centric | Every member must be an accepted friend of the host. Members needn't be friends with each other. | Simple; the host invites from their own friend list. | Members learn the display names, handles and chat lines of non-friends. That needs explicit consent ("people in this room can see your name and messages"). |
| B. Clique | Every pair of members must be friends. | No exposure to strangers. | Hard to form rooms; one unfriending between two guests breaks the room. Rules cost grows as O(N²) `exists()` calls. |
| C. Invite-chain with consent | Host-centric, plus each member accepts a per-room disclosure that names everyone present. Later joiners need re-consent from existing members. | Explicit and auditable. | Most UI and protocol work. |

**Recommendation:** A with explicit consent (a light version of C). Cap rooms at 4–6 members to keep Rules `exists()` calls bounded: the rules check only the host↔member edge per read, plus a server-maintained `members` map.

## Proposed data model (v2)

- `rooms/{id}`: `schemaVersion: 2`, `hostUid`, `members: {uid: {joinedAt, consentAt}}` (server-written), `content`, `state`, `epoch`, `sequence`, `position`, `writerSessionId`, `leaseExpiresAt`, `maxMembers`.
- `roomInvites/{uid}/items/{roomId}`: unchanged index.
- Rules, `activeRoomV2(data)`: `request.auth.uid in data.members && (request.auth.uid == data.hostUid || friends(data.hostUid, request.auth.uid))`, plus the state and lease checks. Host position writes are unchanged.
- Callables: `socialRoomAction` gains `invite {roomId, otherUid}` (host only, ≤ maxMembers), `join` (requires `consent: true` and returns the member list), `kick {otherUid}` (host only) and `leave` (removes self; the host leaving closes the room or transfers it, to be decided). On a host↔member friendship removal, the backend removes that member, or the client leaves on its own revocation signal.
- Chat: messages carry `senderUid`. Member names come from a server-written `memberProfiles` snapshot in the room, because members can't read non-friend profiles.

## Client changes

- Coordinator: "other participant" becomes "host + members". Revocation becomes "my edge to the host" for members, and "per member" for the host (eject one, keep going).
- Following stays host-only (one writer). Members follow the host as today.
- UI: member list and consent screen; the chat shows names from the room snapshot.

## Migration

Keep v1 rooms as they are (`schemaVersion: 1`). New code reads both, and creates v2 only when inviting a third person. Rules accept both validators.

## Effort and risk

Medium-large: about 2–3 feature branches (backend + rules + emulator tests; coordinator refactor; UI). The main risk is privacy exposure between non-friends, so explicit consent is a hard requirement. Rules cost must be re-audited with N members.
