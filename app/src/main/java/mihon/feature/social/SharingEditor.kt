package mihon.feature.social

/** The privacy switches on Friends → Me, in screen order. Invisible mode comes last, apart from the sharing switches. */
enum class SharingSwitch {
    LIBRARY,
    PROGRESS,
    FAVORITES,
    ACTIVITY,
    FEED,
    STATS,
    GLOBAL_RANKING,
    RANKING_MEDIA,
    REVIEWS,
    PRESENCE,
    LAST_SEEN,
    INVISIBLE,
}

/**
 * What each privacy switch reads and writes, and when it can be changed. The settings it returns go to
 * `socialUpdateSharing` as they are; the server bumps the generation (which hides older shared data).
 */
object SharingEditor {
    fun isOn(settings: SocialSharing, switch: SharingSwitch): Boolean = when (switch) {
        SharingSwitch.LIBRARY -> settings.library == FRIENDS
        SharingSwitch.PROGRESS -> settings.progress
        SharingSwitch.FAVORITES -> settings.favorites == FRIENDS
        SharingSwitch.ACTIVITY -> settings.activity == FRIENDS
        SharingSwitch.FEED -> settings.feed == FRIENDS
        SharingSwitch.STATS -> settings.stats == FRIENDS
        SharingSwitch.GLOBAL_RANKING -> settings.globalRanking
        SharingSwitch.RANKING_MEDIA -> settings.rankingMedia
        SharingSwitch.REVIEWS -> settings.reviews
        SharingSwitch.PRESENCE -> settings.presence == FRIENDS
        SharingSwitch.LAST_SEEN -> settings.lastSeen == FRIENDS
        SharingSwitch.INVISIBLE -> settings.invisible
    }

    fun set(settings: SocialSharing, switch: SharingSwitch, on: Boolean): SocialSharing {
        val shared = if (on) FRIENDS else PRIVATE
        return when (switch) {
            SharingSwitch.LIBRARY -> settings.copy(library = shared)
            SharingSwitch.PROGRESS -> settings.copy(progress = on)
            SharingSwitch.FAVORITES -> settings.copy(favorites = shared)
            SharingSwitch.ACTIVITY -> settings.copy(activity = shared)
            SharingSwitch.FEED -> settings.copy(feed = shared)
            SharingSwitch.STATS -> settings.copy(stats = shared)
            SharingSwitch.GLOBAL_RANKING -> settings.copy(globalRanking = on)
            SharingSwitch.RANKING_MEDIA -> settings.copy(rankingMedia = on)
            SharingSwitch.REVIEWS -> settings.copy(reviews = on)
            SharingSwitch.PRESENCE -> settings.copy(presence = shared)
            SharingSwitch.LAST_SEEN -> settings.copy(lastSeen = shared)
            SharingSwitch.INVISIBLE -> settings.copy(invisible = on)
        }
    }

    /**
     * Nothing changes while a change is being sent. Reading progress is part of the library, so it only changes (and is
     * only sent) while the library is shared; ranking pictures only matter on the ranking and while visible.
     */
    fun enabled(settings: SocialSharing, switch: SharingSwitch, busy: Boolean): Boolean = !busy && when (switch) {
        SharingSwitch.PROGRESS -> settings.library == FRIENDS
        SharingSwitch.RANKING_MEDIA -> settings.globalRanking && !settings.invisible
        else -> true
    }

    private const val FRIENDS = "friends"
    private const val PRIVATE = "private"
}
