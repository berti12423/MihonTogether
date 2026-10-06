package mihon.feature.social.reader

import mihon.feature.social.RoomMember
import mihon.feature.social.SocialRoom

/**
 * Client spacing of my own room position updates. The backend accepts one every 3 s; the extra half second keeps
 * network jitter from turning a well-spaced pair into a rejected one.
 */
const val ROOM_POSITION_GAP_MS = 3_500L

/** One room member's marker on the reader's page slider. */
data class RoomMarker(
    val uid: String,
    val name: String,
    val accent: Int,
    val pageIndex: Int,
    val isHost: Boolean,
) {
    /** Where the marker sits along the slider, 0 (first page) to 1 (last page). */
    fun fraction(pageCount: Int): Float =
        if (pageCount <= 1) 0f else pageIndex.coerceIn(0, pageCount - 1).toFloat() / (pageCount - 1)
}

/**
 * Where everyone else in the room is in my chapter: the host by the room's position, other members by the place they
 * shared. Only shown while I'm not following (the host's position already drives my view then), only for joined
 * members on exactly my chapter, and only for places shared within [MAX_AGE_MS].
 */
object RoomMarkers {
    const val MAX_AGE_MS = 10 * 60_000L

    fun place(
        room: SocialRoom?,
        selfUid: String?,
        chapterKey: String?,
        pageCount: Int,
        following: Boolean,
        now: Long,
        maxAgeMs: Long = MAX_AGE_MS,
    ): List<RoomMarker> {
        if (room == null || selfUid == null || chapterKey == null || following || pageCount < 1) return emptyList()
        if (room.state != "active") return emptyList()
        return room.participants.asSequence()
            .filter { it != selfUid }
            .mapNotNull { uid ->
                val member = room.members[uid]?.takeIf { it.state == RoomMember.JOINED } ?: return@mapNotNull null
                val isHost = uid == room.hostUid
                val index = if (isHost) {
                    room.position?.takeIf { it.chapterKey == chapterKey }?.pageIndex
                } else {
                    member.page?.takeIf { it.chapterKey == chapterKey && now - it.at <= maxAgeMs }?.pageIndex
                }
                index?.takeIf { it in 0..<pageCount }?.let { RoomMarker(uid, member.name, member.accent, it, isHost) }
            }
            .sortedWith(compareBy<RoomMarker> { it.pageIndex }.thenByDescending { it.isHost })
            .toList()
    }
}

/**
 * What I share as my room position: my chapter and page while I'm a joined member reading the room's title, and
 * nothing (a clear) while I read anything else, so another title's chapter never reaches the room.
 */
internal data class MemberPlace(val chapterKey: String?, val pageIndex: Int) {
    companion object {
        val CLEARED = MemberPlace(null, 0)

        fun of(reading: LocalReading?, room: SocialRoom?, selfUid: String): MemberPlace? {
            if (room == null || room.state != "active" || room.members[selfUid]?.state != RoomMember.JOINED) {
                return null
            }
            if (reading == null || !ReadingCompatibility.sameEdition(reading.content, room.content)) return CLEARED
            return MemberPlace(reading.position.chapterKey, reading.position.pageIndex)
        }
    }
}
