package mihon.feature.social

import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.asMangaCover

/** What an accepted friend currently shares about themselves; every field is null when not shared. */
data class FriendStatus(
    val online: Boolean? = null,
    val lastSeen: Long? = null,
    val reading: SocialContent? = null,
    val readingExpiresAt: Long? = null,
    /** Their open room, while they let friends drop in. */
    val openRoom: OpenRoomCard? = null,
) {
    fun readingAt(now: Long): SocialContent? = reading?.takeIf { (readingExpiresAt ?: 0) > now }
}

/** One row of today's ranking (friends or global). */
data class RankingEntry(
    val uid: String,
    val name: String,
    val chapters: Int,
    val pages: Int,
    val me: Boolean,
    /** Consecutive days with a finished chapter, including today. */
    val streak: Int = 1,
    /** Known for global rows (and friends), for the profile card. */
    val handle: String? = null,
    val accent: Int = 0,
    val friend: Boolean = false,
    /** Reading level, when known (global board, or a friend sharing stats). */
    val level: Int? = null,
    /** XP earned this week, for the weekly tab (friends sharing stats, and me). */
    val weekXp: Int = 0,
    /** Total XP, for the all-time tab. */
    val totalXp: Int = 0,
    /** Pictures from a ranking board (friends' and mine come from their profiles instead). */
    val avatar: ProfileMedia? = null,
    val banner: ProfileMedia? = null,
)

object FriendList {
    /** Online first, then friends reading right now, then most recently seen, then by name. */
    fun sort(friends: List<SocialProfile>, statuses: Map<String, FriendStatus>, now: Long): List<SocialProfile> =
        friends.sortedWith(
            compareByDescending<SocialProfile> { statuses[it.uid]?.online == true }
                .thenByDescending { statuses[it.uid]?.readingAt(now) != null }
                .thenByDescending { statuses[it.uid]?.lastSeen ?: Long.MIN_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
                .thenBy { it.uid },
        )

    /**
     * Friends' open rooms I can drop in to: live, not full and not a room I'm already in ([myRooms]), fullest first
     * (more people reading), then by title.
     */
    fun openRooms(statuses: Map<String, FriendStatus>, myRooms: Set<String>, now: Long): List<OpenRoomCard> =
        statuses.values.mapNotNull { it.openRoom }
            .filter { it.liveAt(now) && it.roomId !in myRooms }
            .distinctBy { it.roomId }
            .sortedWith(compareByDescending<OpenRoomCard> { it.memberCount }.thenBy { it.title }.thenBy { it.roomId })

    /** My library's shared projections, [localIndex] and [covers] in one pass (each title hashed once). */
    data class LocalTables(
        val library: List<SocialContent>,
        val localIndex: Map<String, Long>,
        val covers: Map<String, MangaCover>,
    )

    /** Hashes every title (SHA-256 keys): call it off the main thread for a big library. */
    fun tables(library: List<Manga>): LocalTables {
        val contents = ArrayList<SocialContent>(library.size)
        val index = LinkedHashMap<String, Long>()
        val covers = LinkedHashMap<String, MangaCover>()
        library.forEach { manga ->
            val content = SocialIdentity.content(manga) ?: return@forEach
            contents += content
            index.putIfAbsent(content.contentKey, manga.id)
            if (content.contentKey !in covers) covers[content.contentKey] = manga.asMangaCover()
        }
        return LocalTables(contents, index, covers)
    }

    /**
     * Local library entries keyed by shared content key. Matching uses the exact source + work path identity
     * ([SocialIdentity.content]); titles are never compared.
     */
    fun localIndex(library: List<Manga>): Map<String, Long> = buildMap {
        library.forEach { manga -> SocialIdentity.content(manga)?.let { putIfAbsent(it.contentKey, manga.id) } }
    }

    /**
     * My library's covers by shared content key, for the title pickers: the library's own cover loading (cover cache,
     * custom covers, source headers), so they show fast and work for sources that refuse outside requests.
     */
    fun covers(library: List<Manga>): Map<String, MangaCover> = buildMap {
        library.forEach { manga ->
            SocialIdentity.content(manga)?.let { putIfAbsent(it.contentKey, manga.asMangaCover()) }
        }
    }

    /**
     * Who I'm in a room with, for one-line labels: "Bob", "Bob and Cat", "Bob, Cat +2". Joined members come first;
     * names come from the room's snapshot, with my friends list as a fallback.
     */
    fun companions(room: SocialRoom, me: String, friends: List<SocialProfile>, and: String = "and"): String? {
        val others = room.others(me)
        val joined = others.filter { it.state == RoomMember.JOINED }.ifEmpty { others }
        val names = joined.map { member ->
            member.name.ifBlank { friends.firstOrNull { it.uid == member.uid }?.displayName.orEmpty() }
        }.filter { it.isNotBlank() }
        return when (names.size) {
            0 -> null
            1 -> names[0]
            2 -> "${names[0]} $and ${names[1]}"
            else -> "${names[0]}, ${names[1]} +${names.size - 2}"
        }
    }

    /** This week's XP among me and friends: most XP first, then name; nobody with 0 XP. */
    fun weekly(entries: List<RankingEntry>): List<RankingEntry> = entries
        .filter { it.weekXp > 0 }
        .sortedWith(
            compareByDescending<RankingEntry> { it.weekXp }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )

    /** Most chapters first, then most pages, then name; people with nothing today are left out. */
    fun rank(entries: List<RankingEntry>): List<RankingEntry> = entries
        .filter { it.chapters > 0 }
        .sortedWith(
            compareByDescending<RankingEntry> { it.chapters }
                .thenByDescending { it.pages }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )

    /** The all-time board: most total XP first, blocked people left out. */
    fun allTime(
        board: List<LeaderboardEntry>,
        me: String,
        friends: Set<String>,
        blocked: Set<String>,
    ): List<RankingEntry> = board
        .filter { it.uid !in blocked && (it.xp ?: 0) > 0 }
        .map {
            RankingEntry(
                uid = it.uid,
                name = it.displayName,
                chapters = it.chapters,
                pages = 0,
                me = it.uid == me,
                handle = it.handle,
                accent = it.accent,
                friend = it.uid in friends,
                level = it.level,
                totalXp = it.xp ?: 0,
                avatar = it.avatar,
                banner = it.banner,
            )
        }
        .sortedWith(
            compareByDescending<RankingEntry> { it.totalXp }
                .thenByDescending { it.chapters }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.uid },
        )

    /**
     * The global board as ranking rows: blocked accounts are left out, and friends and I are marked so a tap opens
     * the right thing (a friend's profile, or a public card with "Add friend").
     */
    fun global(
        board: List<LeaderboardEntry>,
        me: String,
        friends: Set<String>,
        blocked: Set<String>,
    ): List<RankingEntry> = board
        .filter { it.uid !in blocked && it.chapters > 0 }
        .map {
            RankingEntry(
                uid = it.uid,
                name = it.displayName,
                chapters = it.chapters,
                pages = 0,
                me = it.uid == me,
                streak = it.streak,
                handle = it.handle,
                accent = it.accent,
                friend = it.uid in friends,
                level = it.level,
                avatar = it.avatar,
                banner = it.banner,
            )
        }
        .sortedWith(
            compareByDescending<RankingEntry> { it.chapters }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.uid },
        )

    /** The ranking day, matching the backend's UTC `statsDay`. */
    fun statsDay(now: java.time.Instant = java.time.Instant.now()): String =
        java.time.format.DateTimeFormatter.BASIC_ISO_DATE.format(now.atZone(java.time.ZoneOffset.UTC).toLocalDate())
}

/** My shared titles on the Friends screen: search and the All / Favorites / Hidden filter. */
object OwnTitles {
    enum class Filter { ALL, FAVORITES, HIDDEN }

    fun filter(
        titles: List<SocialContent>,
        query: String,
        filter: Filter,
        favorites: Set<String>,
        hidden: Set<String>,
    ): List<SocialContent> {
        val needle = query.trim()
        return titles.filter { content ->
            (needle.isEmpty() || content.title.contains(needle, ignoreCase = true)) &&
                when (filter) {
                    Filter.ALL -> true
                    Filter.FAVORITES -> content.contentKey in favorites
                    Filter.HIDDEN -> content.contentKey in hidden
                }
        }.sortedBy { it.title.lowercase() }
    }
}

/** Friend invite links: `mihontogether://add/<handle>` (app) and `<web>/add/<handle>` (web companion). */
object FriendInvite {
    private val HANDLE = Regex("[a-z0-9_]{3,24}")

    /** The handle from an app invite link, or null for anything else. */
    fun parse(uri: String?): String? {
        val match = Regex("""^mihontogether://add/([^/?#]+)/?$""").find(uri ?: return null) ?: return null
        return match.groupValues[1].takeIf(HANDLE::matches)
    }

    fun appLink(handle: String) = "mihontogether://add/$handle"

    /**
     * The friend code the web invite page copies (`mihontogether-invite:<handle>`, web `inviteCode`). Without the Play
     * Store there is no install referrer, so the app looks for it on the clipboard once after the install.
     */
    fun code(handle: String) = "$CODE_PREFIX$handle"

    /** The handle from a clipboard text holding a friend code or an app invite link; null for anything else. */
    fun parseCode(text: String?): String? {
        val clean = text?.trim()?.takeIf { it.length <= MAX_CLIP } ?: return null
        CODE.find(clean)?.let { return it.groupValues[1] }
        return parse(clean)
    }

    /** What someone typed into "Enter a friend code": a code, an app link or a plain handle (with or without @). */
    fun parseTyped(text: String): String? =
        parseCode(text) ?: text.trim().removePrefix("@").lowercase().takeIf(HANDLE::matches)

    private const val CODE_PREFIX = "mihontogether-invite:"
    private const val MAX_CLIP = 500
    private val CODE = Regex("""(?<![a-z0-9_-])mihontogether-invite:([a-z0-9_]{3,24})(?![a-zA-Z0-9_-])""")

    fun shareText(handle: String, webBase: String): String {
        val web = webBase.trimEnd('/').takeIf { it.startsWith("https://") || it.startsWith("http://") }
        return buildString {
            append("Add me on Mihon Together: @").append(handle)
            if (web != null) append("\n").append(web).append("/add/").append(handle)
            append("\n").append(appLink(handle))
        }
    }
}
