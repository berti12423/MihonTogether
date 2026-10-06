package mihon.feature.discord

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.core.common.preference.PreferenceStore

/** What Discord shows while reading (see docs/DISCORD_PRESENCE_PLAN.md). */
data class PresenceInfo(
    /** Title being read. */
    val details: String,
    /** "Chapter 12"; null when chapters are hidden. */
    val state: String?,
    /** https cover URL, or null for the app logo. */
    val coverUrl: String?,
    /** When the current chapter was opened (epoch ms), for Discord's elapsed timer. */
    val startedAt: Long,
    /** Read together: people in the room (shown as a party), or null. */
    val partySize: Int? = null,
    /** Same for both readers of a room, so Discord groups them; derived from the room, never the raw ID. */
    val partyId: String? = null,
    /** Room capacity, for Discord's "2 of 6". */
    val partyMax: Int = 2,
    /** "Get Mihon Together" button target (the website's download page), or null for no button. */
    val buttonUrl: String? = null,
    /** "Watching {title}" (default; sits next to games) or "Playing {title}". */
    val watching: Boolean = true,
    /** The line under the chapter: "Level 12 • Reading with X" (Discord adds the party's "(2 of 6)"), or null. */
    val extra: String? = null,
    /** The app logo (https): a small badge on the cover, or the picture itself without a cover. Carries the credit. */
    val logoUrl: String? = null,
)

/** Publishes to the Discord app on this device. Implementations must never block or throw. */
interface DiscordPresence {
    /** False when this build has no Discord SDK or no Application ID; settings explain that. */
    val available: Boolean

    fun update(info: PresenceInfo)

    fun clear()

    /** Clears after [delayMs] unless [update] comes first (the reader went to the background). */
    fun clearAfter(delayMs: Long) = clear()
}

/** What the settings screen shows about the Discord connection. */
sealed interface DiscordStatus {
    /** Nothing tried yet in this app session. */
    data object Idle : DiscordStatus

    /** The SDK couldn't load (the message is the exception's). */
    data class Unavailable(val message: String) : DiscordStatus

    /** Sent to the Discord app, no answer yet. Stays here if Discord isn't installed or signed in. */
    data object Waiting : DiscordStatus

    data object Shown : DiscordStatus

    data object Cleared : DiscordStatus

    /** Discord refused the update. */
    data class Failed(val message: String) : DiscordStatus

    /** The link to the Discord app broke; the client is being rebuilt and the status resent. */
    data object Reconnecting : DiscordStatus

    /** The Discord app isn't installed on this phone. */
    data object NoDiscordApp : DiscordStatus

    /** The installed Discord app is too old to accept status from other apps. */
    data object DiscordTooOld : DiscordStatus
}

/** Builds without the Discord Social SDK, or without an Application ID. */
object NoopDiscordPresence : DiscordPresence {
    override val available = false
    override fun update(info: PresenceInfo) = Unit
    override fun clear() = Unit
}

@Inject
@SingleIn(AppScope::class)
class DiscordPreferences(store: PreferenceStore) {
    val enabled = store.getBoolean("discord_presence", false)

    /** Source IDs whose titles may appear on Discord. New sources start excluded. */
    val allowedSources = store.getStringSet("discord_presence_sources", emptySet())
    val showCover = store.getBoolean("discord_presence_cover", true)
    val showChapter = store.getBoolean("discord_presence_chapter", true)

    /** A "Get Mihon Together" button under the status, linking to the website's download page. */
    val showButton = store.getBoolean("discord_presence_button", true)

    /** Show as "Watching" instead of "Playing", so Discord doesn't swap it with a game being played. */
    val watching = store.getBoolean("discord_presence_watching", true)

    /** My reading level (already public on the global ranking). */
    val showLevel = store.getBoolean("discord_presence_level", true)

    fun rules() = DiscordRules(
        enabled.get(),
        allowedSources.get(),
        showCover.get(),
        showChapter.get(),
        showButton.get(),
        watching.get(),
        showLevel.get(),
    )

    /** [rules], again whenever any Discord setting changes. */
    fun rulesChanges(): Flow<DiscordRules> = merge(
        enabled.changes(),
        allowedSources.changes(),
        showCover.changes(),
        showChapter.changes(),
        showButton.changes(),
        watching.changes(),
        showLevel.changes(),
    ).map { rules() }.distinctUntilChanged()
}

/** A snapshot of the user's Discord settings. */
data class DiscordRules(
    val enabled: Boolean,
    val allowedSources: Set<String>,
    val showCover: Boolean,
    val showChapter: Boolean,
    val showButton: Boolean = true,
    val watching: Boolean = true,
    val showLevel: Boolean = true,
)

/** What the reader is showing right now. */
data class ReadingNow(
    val sourceId: Long,
    val title: String,
    val coverUrl: String?,
    val chapterName: String?,
    val chapterOpenedAt: Long,
    /** Incognito source, or a title the social privacy rules keep private (hidden from friends). */
    val private: Boolean,
    /** Read together partner's display name, if in an active room. */
    val readingWith: String? = null,
    /** The active room's ID, for grouping its readers into one Discord party. */
    val roomId: String? = null,
    /** People reading in the room, me included. */
    val partySize: Int = 2,
    /** My reading level, when signed in. */
    val level: Int? = null,
)

object PresenceRules {
    /** The presence to show, or null to show nothing. Privacy wins over everything else. */
    fun presence(
        reading: ReadingNow?,
        rules: DiscordRules,
        readingWithLabel: (String) -> String,
        downloadUrl: String? = null,
        logoUrl: String? = null,
        levelLabel: (Int) -> String = { "Level $it" },
    ): PresenceInfo? {
        if (reading == null || !rules.enabled || reading.private) return null
        if (reading.sourceId.toString() !in rules.allowedSources) return null
        val chapter = reading.chapterName?.takeIf { rules.showChapter }
        val extra = listOfNotNull(
            reading.level?.takeIf { rules.showLevel && it > 0 }?.let(levelLabel),
            reading.readingWith?.let(readingWithLabel),
        ).joinToString(" • ").ifBlank { null }
        return PresenceInfo(
            details = reading.title.take(MAX_TEXT),
            state = chapter?.take(MAX_TEXT),
            coverUrl = reading.coverUrl?.takeIf {
                rules.showCover && it.startsWith("https://") && it.length <= MAX_URL
            },
            startedAt = reading.chapterOpenedAt,
            partySize = if (reading.readingWith != null) reading.partySize.coerceAtLeast(2) else null,
            partyMax = 6,
            partyId = reading.roomId?.takeIf { reading.readingWith != null }?.let(::partyIdFor),
            buttonUrl = downloadUrl?.takeIf { rules.showButton && it.startsWith("https://") && it.length <= MAX_URL },
            watching = rules.watching,
            extra = extra?.take(MAX_TEXT),
            logoUrl = logoUrl?.takeIf { it.startsWith("https://") && it.length <= MAX_URL },
        )
    }

    /** An opaque, stable party ID for a room (2-128 chars), so the room ID itself never leaves the app. */
    fun partyIdFor(roomId: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest("mihon-party|$roomId".toByteArray())
        return "mt-" + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    /** Discord's limit for activity text fields. */
    const val MAX_TEXT = 128
    const val MAX_URL = 256
}

/**
 * Sends presence changes to [backend] without flooding Discord: identical updates are skipped and updates are spaced
 * at least [minIntervalMs] apart (the latest wins). Clearing is immediate, since it's a privacy action.
 */
class PresenceThrottle(
    scope: CoroutineScope,
    private val backend: DiscordPresence,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = 15_000,
) {
    private val wanted = Channel<Wish>(Channel.CONFLATED)
    private var shown: PresenceInfo? = null
    private var lastSentAt = Long.MIN_VALUE / 2

    init {
        scope.launch {
            for (first in wanted) {
                var wish = first
                while (true) {
                    val info = wish.info
                    if (info == null) {
                        if (shown != null) backend.clear()
                        shown = null
                        break
                    }
                    if (info == shown) break
                    val wait = lastSentAt + minIntervalMs - clock()
                    if (wait <= 0) {
                        backend.update(info)
                        shown = info
                        lastSentAt = clock()
                        break
                    }
                    // Too soon after the last update: wait, but react at once to a newer wish (a clear especially).
                    val newer = withTimeoutOrNull(wait) { wanted.receive() }
                    if (newer != null) wish = newer
                }
            }
        }
    }

    private class Wish(val info: PresenceInfo?)

    fun show(info: PresenceInfo?) {
        wanted.trySend(Wish(info))
    }
}

/** Picks the backend for this build: the Social SDK when it was bundled and an Application ID is set. */
object DiscordPresenceFactory {
    fun isAvailable(): Boolean = create().available

    fun create(): DiscordPresence = SocialSdkDiscordPresence.takeIf { it.available } ?: NoopDiscordPresence
}
