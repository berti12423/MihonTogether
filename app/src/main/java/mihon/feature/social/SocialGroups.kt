package mihon.feature.social

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/** One group in my chat list (groupIndex/{me}/groups/{groupId}). */
data class GroupSummary(
    val groupId: String,
    val name: String,
    val members: Int,
    val lastText: String,
    val lastSenderName: String,
    val unread: Int,
    val updatedAt: Long,
)

/** A member's snapshot inside a group: name card plus the message number they joined at. */
data class GroupMember(val uid: String, val name: String, val handle: String, val accent: Int, val since: Long)

/** A group chat (groups/{groupId}): members in join order, the first one has been there longest. */
data class SocialGroup(
    val groupId: String,
    val name: String,
    val ownerUid: String,
    val members: List<GroupMember>,
) {
    fun member(uid: String) = members.firstOrNull { it.uid == uid }
}

/** A text or a system line ("created", "added", "removed", "left", "owner", "renamed"; [text] holds the subject). */
data class GroupMessage(
    val seq: Long,
    val kind: String,
    val senderUid: String,
    val senderName: String,
    val text: String,
    val event: String?,
    val createdAt: Long,
    /** A recommended title, shown as a card. */
    val content: SocialContent? = null,
    /** The message this one answers (a server-written quote, with the sender's name). */
    val replyTo: ChatQuote? = null,
    /** One emoji per person, by uid. */
    val reactions: Map<String, String> = emptyMap(),
) {
    val isEvent get() = kind == "event"
}

/** The group open on screen. */
data class OpenGroup(
    val groupId: String,
    val group: SocialGroup? = null,
    val messages: List<GroupMessage> = emptyList(),
    val loaded: Boolean = false,
    val sending: Boolean = false,
    val failed: Boolean = false,
    /** Counts messages sent successfully: the screen clears its draft and reply only then. */
    val sent: Int = 0,
    /** The group is gone for me (I left, was removed, or it was deleted). */
    val gone: Boolean = false,
)

/** XP and level from `xp/{uid}`, earned by reading (see backend `XP`). */
data class SocialXp(
    val total: Long,
    val level: Int,
    val today: Int = 0,
    val chapters: Int = 0,
    val together: Int = 0,
    val streak: Int = 0,
    val bestStreak: Int = 0,
    /** Streak freezes held (0–2); each covers one missed day. */
    val freezes: Int = 0,
    /** Unlocked achievement IDs → unlock time. */
    val badges: Map<String, Long> = emptyMap(),
    /** XP this week (UTC Monday start, yyyymmdd in [week]); stale weeks count as 0. */
    val week: String = "",
    val weekXp: Int = 0,
    /** Chapters this week (same week as [weekXp]). */
    val weekChapters: Int = 0,
    /** Last reading day (UTC yyyymmdd), empty when unknown. */
    val day: String = "",
    /** Different UTC days with a chapter, ever. */
    val days: Int = 0,
    /** Most chapters in one UTC day, ever. */
    val bestDay: Int = 0,
    /** Monthly challenge counters (null on docs from before challenges, or before the first chapter). */
    val month: XpMonth? = null,
    /** Completed challenge IDs by month ("yyyy-mm"). */
    val challenges: Map<String, List<String>> = emptyMap(),
) {
    fun weekXp(currentWeek: String) = if (week == currentWeek) weekXp else 0

    fun weekChapters(currentWeek: String) = if (week == currentWeek) weekChapters else 0
}

/** What one recorded chapter earned, for the "level up", "achievement" and "challenge" moments. */
data class XpReward(
    /** All XP this chapter added, challenge XP included. */
    val gained: Int,
    val total: Long,
    val level: Int,
    val leveledUp: Boolean,
    val unlocked: List<String> = emptyList(),
    val freezeUsed: Int = 0,
    /** Monthly challenges this chapter completed. */
    val challenges: List<String> = emptyList(),
    val challengeXp: Int = 0,
    /** The `season_yyyy_mm` badge when this chapter completed all of the month's challenges. */
    val season: String? = null,
) {
    /** Worth a message in the reader. */
    val notable get() = leveledUp || unlocked.isNotEmpty() || freezeUsed > 0 || challenges.isNotEmpty()
}

/** The achievements the backend can unlock (backend `BADGES`), in display order. */
enum class Badge(val id: String, val icon: String, val title: StringResource, val description: StringResource) {
    FIRST_CHAPTER("first_chapter", "📖", MR.strings.badge_first_chapter, MR.strings.badge_first_chapter_info),
    CHAPTERS_50("chapters_50", "🪝", MR.strings.badge_chapters_50, MR.strings.badge_chapters_50_info),
    CHAPTERS_250("chapters_250", "📚", MR.strings.badge_chapters_250, MR.strings.badge_chapters_250_info),
    CHAPTERS_1000("chapters_1000", "🏛️", MR.strings.badge_chapters_1000, MR.strings.badge_chapters_1000_info),
    TOGETHER_1("together_1", "🤝", MR.strings.badge_together_1, MR.strings.badge_together_1_info),
    TOGETHER_25("together_25", "🎎", MR.strings.badge_together_25, MR.strings.badge_together_25_info),
    TOGETHER_100("together_100", "💞", MR.strings.badge_together_100, MR.strings.badge_together_100_info),
    STREAK_3("streak_3", "🔥", MR.strings.badge_streak_3, MR.strings.badge_streak_3_info),
    STREAK_7("streak_7", "⚔️", MR.strings.badge_streak_7, MR.strings.badge_streak_7_info),
    STREAK_30("streak_30", "🗓️", MR.strings.badge_streak_30, MR.strings.badge_streak_30_info),
    STREAK_100("streak_100", "💎", MR.strings.badge_streak_100, MR.strings.badge_streak_100_info),
    BINGE_20("binge_20", "🌙", MR.strings.badge_binge_20, MR.strings.badge_binge_20_info),
    DAYS_30("days_30", "☕", MR.strings.badge_days_30, MR.strings.badge_days_30_info),
    LEVEL_10("level_10", "⭐", MR.strings.badge_level_10, MR.strings.badge_level_10_info),
    LEVEL_25("level_25", "🧙", MR.strings.badge_level_25, MR.strings.badge_level_25_info),
    RECRUIT_1("recruit_1", "📨", MR.strings.badge_recruit_1, MR.strings.badge_recruit_1_info),
    RECRUIT_3("recruit_3", "🎉", MR.strings.badge_recruit_3, MR.strings.badge_recruit_3_info),
    RECRUIT_10("recruit_10", "🏰", MR.strings.badge_recruit_10, MR.strings.badge_recruit_10_info),
    ;

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id }
    }
}

/** Weekly XP resets at Monday 00:00 UTC, like the backend's `weekOf`. */
fun xpWeek(now: java.time.Instant = java.time.Instant.now()): String {
    val date = now.atZone(java.time.ZoneOffset.UTC).toLocalDate()
    return java.time.format.DateTimeFormatter.BASIC_ISO_DATE.format(date.with(java.time.DayOfWeek.MONDAY))
}

/** Mirrors the backend's level curve: level L needs 25·L·(L−1) XP in total (50, 150, 300, 500, ...). */
object XpLevels {
    fun xpFor(level: Int): Long = 25L * level * (level - 1)

    fun levelFor(total: Long): Int {
        var level = 1
        while (xpFor(level + 1) <= total) level++
        return level
    }

    /** XP into the current level and the size of the level, for a progress bar. */
    fun progress(total: Long): Pair<Long, Long> {
        val level = levelFor(total)
        val start = xpFor(level)
        return (total - start) to (xpFor(level + 1) - start)
    }

    /** A reader title for each level band. */
    fun title(level: Int): StringResource = when {
        level >= 40 -> MR.strings.social_level_title_6
        level >= 25 -> MR.strings.social_level_title_5
        level >= 15 -> MR.strings.social_level_title_4
        level >= 10 -> MR.strings.social_level_title_3
        level >= 5 -> MR.strings.social_level_title_2
        else -> MR.strings.social_level_title_1
    }
}
