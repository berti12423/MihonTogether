package mihon.feature.social

import tachiyomi.domain.manga.model.Manga
import java.net.URI
import java.security.MessageDigest

data class SocialAccount(val uid: String, val email: String?, val verified: Boolean)

data class SocialProfile(
    val uid: String,
    val handle: String,
    val displayName: String,
    val bio: String = "",
    /** Index into [ProfileStyle.ACCENTS]. */
    val accent: Int = 0,
    val avatar: ProfileMedia? = null,
    val banner: ProfileMedia? = null,
)

/** An uploaded avatar or banner; [version] changes on every upload, so it doubles as a cache key. */
data class ProfileMedia(val version: Long, val contentType: String)

data class SocialSharing(
    val generation: Long = 0,
    val library: String = "private",
    val favorites: String = "private",
    val activity: String = "private",
    val presence: String = "private",
    val lastSeen: String = "private",
    val invisible: Boolean = false,
    val feed: String = "private",
    val stats: String = "private",
    /** Appear on the global ranking (name, handle, chapter count). The one switch that starts on; absent means on. */
    val globalRanking: Boolean = true,
    /** While on the global ranking, everyone signed in sees my avatar and banner. Absent means on. */
    val rankingMedia: Boolean = true,
    /** Friends see my ratings and reviews (a friend's copy is already false while they're invisible). Absent means on. */
    val reviews: Boolean = true,
    /** Friends see my reading progress on my shared library entries. Absent means on (an older backend lacks it). */
    val progress: Boolean = true,
    /**
     * The server stored [progress] (a backend that knows the switch writes it with every change). Until then it's
     * only sent when turned off, so changing other settings keeps working against a backend without it.
     */
    val progressStored: Boolean = false,
) {
    fun payload(): Map<String, Any> = mapOf(
        "library" to library,
        "favorites" to favorites,
        "activity" to activity,
        "presence" to presence,
        "lastSeen" to lastSeen,
        "feed" to feed,
        "stats" to stats,
        "invisible" to invisible,
        "globalRanking" to globalRanking,
        "rankingMedia" to rankingMedia,
        "reviews" to reviews,
    ) + if (progressStored || !progress) mapOf("progress" to progress) else emptyMap()
}

data class SocialSelf(val profile: SocialProfile?, val settings: SocialSharing)

data class SocialContent(
    val contentKey: String,
    val title: String,
    val sourceKey: String,
    val sourceWorkKey: String,
    val editionKey: String,
    val canonicalProvider: String? = null,
    val canonicalId: String? = null,
    /** Public https cover, only in library/favorites projections when cover sharing is on. */
    val coverUrl: String? = null,
    /** Rooms only: the host's source, so a friend without it knows which extension to install. */
    val source: SocialSource? = null,
    /** Library/favorites projections: the owner uploaded a small cover to coverThumbs/{uid}/{contentKey}. */
    val thumb: Boolean = false,
) {
    fun payload(): Map<String, Any?> = buildMap {
        put("contentKey", contentKey)
        put("title", title)
        put("sourceKey", sourceKey)
        put("sourceWorkKey", sourceWorkKey)
        put("editionKey", editionKey)
        put("canonicalProvider", canonicalProvider)
        put("canonicalId", canonicalId)
        coverUrl?.let { put("coverUrl", it) }
        if (thumb) put("thumb", true)
        source?.let {
            put(
                "source",
                mapOf(
                    "id" to it.id.toString(),
                    "name" to it.name,
                    "lang" to it.lang,
                    "pkg" to it.pkg,
                ) + listOfNotNull(it.url?.let { url -> "url" to url }),
            )
        }
    }
}

/**
 * An extension source as rooms share it: numeric ID, display name, language, and its package (null if built in).
 * [url] is the host's title URL in that source (SManga.url), so a friend with the source can open it in one step.
 */
data class SocialSource(
    val id: Long,
    val name: String,
    val lang: String,
    val pkg: String?,
    val url: String? = null,
) {
    companion object {
        const val MAX_URL = 500

        /** A source-relative path or a public https URL; anything else is neither shared nor trusted. */
        fun validUrl(url: String?): String? = url?.takeIf {
            (it.startsWith("/") || it.startsWith("https://")) && it.length in 2..MAX_URL &&
                it.none { char -> char.isWhitespace() || char.isISOControl() }
        }
    }
}

data class SocialPosition(
    val chapterKey: String,
    val chapterLabel: String,
    val pageIndex: Int,
    val pageCount: Int,
    val pageMappingKey: String,
    /** Long-strip scroll: pages from the chapter start to the top of the screen (9.37 = 37% into page 10). */
    val stripPosition: Double? = null,
) {
    fun payload(): Map<String, Any> = buildMap {
        put("chapterKey", chapterKey)
        put("chapterLabel", chapterLabel)
        put("pageIndex", pageIndex)
        put("pageCount", pageCount)
        put("pageMappingKey", pageMappingKey)
        stripPosition?.let { put("stripPosition", it) }
    }
}

/**
 * A reading room (schema v2): the host plus up to five friends. [participants] is everyone with access; [members] is
 * the server's snapshot of names and join state, so members can name each other without reading profiles.
 */
data class SocialRoom(
    val id: String,
    val hostUid: String,
    val participants: List<String>,
    val content: SocialContent,
    val state: String,
    val epoch: Long,
    val sequence: Long,
    val position: SocialPosition?,
    val writerSessionId: String?,
    val leaseExpiresAt: Long,
    val members: Map<String, RoomMember> = emptyMap(),
    val maxMembers: Int = 6,
    /** The host lets friends drop in without an invite (server-written; off by default). */
    val open: Boolean = false,
) {
    fun isInvited(uid: String) = members[uid]?.state == RoomMember.INVITED

    fun nameOf(uid: String): String? = members[uid]?.name?.takeIf { it.isNotBlank() }

    /** Everyone but [me]: the host first, then joined members by arrival, then invited ones. */
    fun others(me: String): List<RoomMember> = participants.filter { it != me }
        .map { members[it] ?: RoomMember(it, RoomMember.INVITED, "") }
        .sortedWith(
            compareByDescending<RoomMember> { it.uid == hostUid }
                .thenByDescending { it.state == RoomMember.JOINED }
                .thenBy { it.joinedAt ?: Long.MAX_VALUE },
        )

    val joinedCount: Int get() = participants.count { members[it]?.state == RoomMember.JOINED }

    val full: Boolean get() = participants.size >= maxMembers
}

data class RoomMember(
    val uid: String,
    val state: String,
    val name: String,
    val accent: Int = 0,
    val joinedAt: Long? = null,
    /** Where this member is, as they shared it with the room (null when unknown or cleared). */
    val page: MemberPage? = null,
) {
    companion object {
        const val JOINED = "joined"
        const val INVITED = "invited"
    }
}

/** A room member's own place: the chapter and page they're on, and when they shared it. */
data class MemberPage(val chapterKey: String, val pageIndex: Int, val at: Long)

/**
 * A friend's open room as their friends see it (`openRooms/{hostUid}`): only enough to offer “Join”. The room itself,
 * its members and chat become readable after joining.
 */
data class OpenRoomCard(
    val roomId: String,
    val hostUid: String,
    val title: String,
    val memberCount: Int,
    val maxMembers: Int,
    val leaseExpiresAt: Long,
) {
    fun liveAt(now: Long): Boolean = leaseExpiresAt > now && memberCount < maxMembers
}

data class SocialRequest(
    val id: String,
    val senderUid: String,
    val recipientUid: String,
    val senderHandle: String? = null,
    val senderDisplayName: String? = null,
    val recipientHandle: String? = null,
    val recipientDisplayName: String? = null,
)

data class SocialInvite(val id: String, val state: String)

data class SocialFriendDetails(
    val profile: SocialProfile,
    val sharing: SocialSharing,
    val library: List<SocialContent> = emptyList(),
    val favorites: List<SocialContent> = emptyList(),
    val online: Boolean? = null,
    val lastSeen: Long? = null,
    val activity: SocialContent? = null,
    val activityExpiresAt: Long? = null,
    val feed: List<SocialFeedItem> = emptyList(),
    /** How many titles are shared; the lists above are only the first few (A–Z) for the profile preview. */
    val libraryCount: Int? = null,
    val favoritesCount: Int? = null,
    /** Their level, while they share stats with friends. */
    val xp: SocialXp? = null,
    /** Their ratings and reviews, newest first, while they share them. */
    val reviews: List<SocialReview> = emptyList(),
)

/** A friend's whole library or favorites, paged A–Z from the server as the grid scrolls. */
data class FriendGrid(
    val uid: String,
    val kind: String,
    val generation: Long,
    val items: List<SocialContent> = emptyList(),
    val total: Int? = null,
    val loading: Boolean = false,
    val complete: Boolean = false,
    val failed: Boolean = false,
)

object SocialIdentity {
    fun key(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    // Query-addressed works need a source-specific public locator adapter. Reject them:
    // silently dropping a meaningful query could match a different title/chapter.
    // Deliberately excludes credentials, fragments and local database IDs.
    fun publicPath(url: String): String? = try {
        val uri = URI(url)
        if (uri.rawQuery != null) null else uri.normalize().rawPath?.trim()?.takeIf { it.isNotEmpty() && it != "/" }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: java.net.URISyntaxException) {
        null
    }

    /** A cover URL that may be shared: public https only, so local files and custom covers never leave the device. */
    fun publicCover(url: String?): String? = url?.takeIf {
        it.startsWith("https://") && it.length in 12..500 &&
            it.none { char -> char.isWhitespace() || char.isISOControl() }
    }

    /** Extension source IDs are stable source descriptors, unlike local Manga.id; shared only hashed. */
    fun sourceKey(sourceId: Long): String = "source-v1:" + key(sourceId.toString())

    fun content(manga: Manga): SocialContent? {
        if (manga.source <= 0L || manga.title.isBlank()) return null
        val path = publicPath(manga.url) ?: return null
        val sourceKey = sourceKey(manga.source)
        val workKey = key("work-v1|$sourceKey|$path")
        val editionKey = key("edition-v1|$sourceKey|$workKey")
        return SocialContent(
            contentKey = key("content-v1|$sourceKey|$workKey|$editionKey"),
            title = manga.title.take(200),
            sourceKey = sourceKey,
            sourceWorkKey = workKey,
            editionKey = editionKey,
        )
    }
}

/** The room can no longer be read: its lease expired, it was closed, or the friendship ended. */
class SocialRoomUnavailable : Exception("Reading room ended")

/** One opt-in recent-history entry; [chapterLabel] is set only for [KIND_CHAPTER_READ]. */
data class SocialFeedItem(
    val kind: String,
    val content: SocialContent,
    val chapterLabel: String?,
    val createdAt: Long,
) {
    companion object {
        const val KIND_CHAPTER_READ = "chapter_read"
        const val KIND_FAVORITE_ADDED = "favorite_added"
    }
}

/** A chat line or reaction inside a live reading room; exactly one of [text]/[emoji] is set. */
data class RoomMessage(
    val seq: Long,
    val senderUid: String,
    val kind: String,
    val text: String?,
    val emoji: String?,
    val createdAt: Long,
) {
    companion object {
        const val KIND_TEXT = "text"
        const val KIND_REACTION = "reaction"

        /** Server-written room events; [RoomMessage.senderUid] is who it's about. */
        const val KIND_JOINED = "joined"
        const val KIND_LEFT = "left"
        const val KIND_REMOVED = "removed"
        const val KIND_HOST = "host"
        val EVENTS = setOf(KIND_JOINED, KIND_LEFT, KIND_REMOVED, KIND_HOST)

        /** Ready check: a member is done with the chapter, or asks the others to wait. */
        const val KIND_READY = "ready"
        const val KIND_WAIT = "wait"
        val SIGNALS = setOf(KIND_READY, KIND_WAIT)
        const val MAX_TEXT = 200

        /** Must match ROOM_REACTIONS in backend/src/domain.ts (checked by SocialCallableNamesTest). */
        val REACTIONS = listOf("👍", "😂", "😮", "😢", "😡", "❤️", "🔥", "👏")
    }
}

/** A friend asking to read along with me (joinRequests/{me}/items/{them}); pending for 10 minutes. */
data class JoinRequest(
    val requesterUid: String,
    val name: String,
    val handle: String,
    val createdAt: Long,
    val expiresAt: Long,
)

/** One conversation in my chat list (chatIndex/{me}/chats/{other}). */
data class ChatSummary(
    val otherUid: String,
    val chatId: String,
    val lastText: String,
    val lastSenderUid: String,
    val updatedAt: Long,
    val unread: Int,
)

/**
 * A private message; single line, at most 500 characters, kept 30 days. [content] is a recommended title.
 * Server-written lines carry [system]: [SYSTEM_MATCH] (we both want to read [content]) or [SYSTEM_RECAP] ([recap]).
 */
data class ChatMessage(
    val seq: Long,
    val senderUid: String,
    val text: String,
    val createdAt: Long,
    val content: SocialContent? = null,
    val system: String? = null,
    val recap: ChatRecap? = null,
    /** The message this one answers (a server-written quote). */
    val replyTo: ChatQuote? = null,
    /** One emoji per person, by uid. */
    val reactions: Map<String, String> = emptyMap(),
) {
    companion object {
        const val SYSTEM_MATCH = "match"
        const val SYSTEM_RECAP = "recap"
    }
}

/** "Read together" summary after a room: the title, the chapters read (labels) and how long. */
data class ChatRecap(val title: String, val fromChapter: String?, val toChapter: String?, val minutes: Int?) {
    /** (first, last) chapter read; last is null for a single chapter; null when neither is known. */
    fun chapterSpan(): Pair<String, String?>? {
        val first = fromChapter ?: toChapter ?: return null
        val last = toChapter?.takeIf { it != first }
        return first to last
    }
}

/** Tolerant parsing of chat documents: unknown fields are ignored, malformed messages dropped. */
object ChatMessages {
    fun parse(data: Map<String, Any?>, millis: (Any?) -> Long?): ChatMessage? {
        val seq = (data["seq"] as? Number)?.toLong() ?: return null
        val sender = data["senderUid"] as? String ?: return null
        val text = (data["text"] as? String).orEmpty()

        @Suppress("UNCHECKED_CAST")
        val content = (data["content"] as? Map<String, Any?>)?.let { runCatching { it.content() }.getOrNull() }
        val createdAt = millis(data["createdAt"]) ?: 0
        return when (data["system"] as? String) {
            ChatMessage.SYSTEM_MATCH -> content?.let {
                ChatMessage(seq, sender, "", createdAt, it, system = ChatMessage.SYSTEM_MATCH)
            }
            ChatMessage.SYSTEM_RECAP -> recap(data["recap"])?.let {
                ChatMessage(seq, sender, "", createdAt, system = ChatMessage.SYSTEM_RECAP, recap = it)
            }
            // A plain message, or a system line this version doesn't know: shown only if it has something to show.
            else -> ChatMessage(
                seq,
                sender,
                text,
                createdAt,
                content,
                replyTo = ChatReactions.quote(data["replyTo"]),
                reactions = ChatReactions.parse(data["reactions"]),
            ).takeIf { text.isNotEmpty() || content != null }
        }
    }

    private fun recap(value: Any?): ChatRecap? {
        val data = value as? Map<*, *> ?: return null
        val title = (data["title"] as? String)?.trim()?.take(200)?.takeIf { it.isNotEmpty() } ?: return null
        return ChatRecap(
            title = title,
            fromChapter = label(data["fromChapter"]),
            toChapter = label(data["toChapter"]),
            minutes = (data["minutes"] as? Number)?.toInt()?.takeIf { it in 0..MAX_RECAP_MINUTES },
        )
    }

    /** Chapter labels arrive as text ("12.5") or numbers (12, 12.5). */
    private fun label(value: Any?): String? = when (value) {
        is String -> value.trim().take(40).takeIf { it.isNotEmpty() }
        is Number -> value.toDouble().let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
        else -> null
    }

    private const val MAX_RECAP_MINUTES = 24 * 60
}

/**
 * "Let's read this": my wish to read [contentKey] with [otherUid] (readWishIndex/{me}/items/{otherUid}_{contentKey}).
 * It stays private to me until the friend wishes the same title; then [matched] is set.
 */
data class ReadWish(
    val otherUid: String,
    val contentKey: String,
    val content: SocialContent?,
    val matched: Boolean,
    val matchedAt: Long?,
    val createdAt: Long,
    val expiresAt: Long?,
) {
    companion object {
        fun parse(data: Map<String, Any?>, millis: (Any?) -> Long?, now: Long): ReadWish? {
            val other = data["otherUid"] as? String ?: return null
            val key = data["contentKey"] as? String ?: return null
            val expiresAt = millis(data["expiresAt"])
            if (expiresAt != null && expiresAt <= now) return null
            @Suppress("UNCHECKED_CAST")
            val content = (data["content"] as? Map<String, Any?>)?.let { runCatching { it.content() }.getOrNull() }
            return ReadWish(
                otherUid = other,
                contentKey = key,
                content = content?.takeIf { it.contentKey == key },
                matched = data["matched"] == true,
                matchedAt = millis(data["matchedAt"]),
                createdAt = millis(data["createdAt"]) ?: 0,
                expiresAt = expiresAt,
            )
        }

        /** Matches first (newest match first), then my open wishes, newest first. */
        fun sorted(wishes: List<ReadWish>): List<ReadWish> = wishes.sortedWith(
            compareByDescending<ReadWish> { it.matched }
                .thenByDescending { it.matchedAt ?: 0 }
                .thenByDescending { it.createdAt },
        )
    }
}

/** The conversation open on screen. */
data class OpenChat(
    val otherUid: String,
    val messages: List<ChatMessage> = emptyList(),
    val loaded: Boolean = false,
    val sending: Boolean = false,
    val failed: Boolean = false,
    /** Counts messages sent successfully: the screen clears its draft and reply only then. */
    val sent: Int = 0,
)

/** One row of the global ranking (leaderboards/{day}): only public fields. */
data class LeaderboardEntry(
    val uid: String,
    val handle: String,
    val displayName: String,
    val accent: Int,
    val chapters: Int,
    val streak: Int,
    /** Reading level (XP), on boards written since XP exists. */
    val level: Int? = null,
    /** Total XP, on the all-time board ([LeaderboardEntry.ALL_TIME]); there [chapters] is lifetime. */
    val xp: Int? = null,
    /** Pictures, when the person shows them on the ranking (readable by everyone signed in then). */
    val avatar: ProfileMedia? = null,
    val banner: ProfileMedia? = null,
) {
    companion object {
        /** The all-time board's document ID in leaderboards/ (the others are UTC days). */
        const val ALL_TIME = "alltime"
    }
}

/** One person's counters for a UTC day (see backend statsDay). */
data class DailyStats(val day: String, val chapters: Int, val pages: Int, val streak: Int = 1)
