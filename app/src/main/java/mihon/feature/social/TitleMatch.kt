package mihon.feature.social

import java.text.Normalizer
import java.util.Locale

/**
 * Whether my title and a friend's shared title are the same work, although friends often read a series on different
 * sources or extensions. Used for "friends who read this" and to line up reviews. The first rule that decides wins:
 *
 * 1. **Same source work** (`sourceWorkKey`): the very same entry in the same source → same.
 * 2. **Tracker IDs**: when both sides have an ID from the same tracker (AniList, MyAnimeList, Kitsu, MangaUpdates),
 *    equal IDs → same, different IDs → different (even if the titles are equal: two works called "Monster").
 * 3. **Same source, different entry** → different: the source itself lists them apart (e.g. a novel next to its manga).
 * 4. **Normalized titles**: any of my titles (the source title plus my trackers' titles) equals the friend's title
 *    after [normalize] → same; otherwise different.
 *
 * [normalize] is deliberately conservative: it only removes what never tells two works apart (case, accents on Latin
 * letters, width, punctuation and spacing) and edition labels such as "(Official)", "[Colored]", "(Webtoon)" or a
 * trailing "Raw". It keeps everything that can: numbers and seasons ("2", "Season 2", "II"), subtitles
 * ("Solo Leveling: Ragnarok"), format words like "Novel", "Anthology" or "Doujinshi", and leading articles ("The").
 * Titles shorter than [MIN_TITLE_KEY] characters after normalization never match by title.
 */
object TitleMatch {
    /** One side of a comparison. [trackers] maps a provider ("anilist", "mal", "kitsu", "mangaupdates") to its ID. */
    data class Work(
        val sourceKey: String,
        val sourceWorkKey: String,
        val trackers: Map<String, String> = emptyMap(),
        val titles: List<String> = emptyList(),
    ) {
        /** Normalized title keys, computed once. */
        val keys: Set<String> by lazy { titles.map(::normalize).filter { it.length >= MIN_TITLE_KEY }.toSet() }

        companion object {
            /** A shared title (a friend's library entry or review): its own tracker ID and title. */
            fun of(content: SocialContent): Work = Work(
                sourceKey = content.sourceKey,
                sourceWorkKey = content.sourceWorkKey,
                trackers = content.trackers(),
                titles = listOf(content.title),
            )
        }
    }

    /** Why two titles matched, strongest first. */
    enum class Reason { SOURCE_WORK, TRACKER, TITLE }

    const val MIN_TITLE_KEY = 3

    /** Trackers that identify a work (Mihon tracker ID → provider name used in shared content). */
    val TRACKERS = mapOf(1L to "mal", 2L to "anilist", 3L to "kitsu", 7L to "mangaupdates")

    fun match(mine: Work, theirs: Work): Reason? {
        if (mine.sourceWorkKey == theirs.sourceWorkKey) return Reason.SOURCE_WORK
        for ((provider, id) in theirs.trackers) {
            val own = mine.trackers[provider] ?: continue
            return if (own == id) Reason.TRACKER else null
        }
        if (mine.sourceKey == theirs.sourceKey) return null
        return if (mine.keys.any { it in theirs.keys }) Reason.TITLE else null
    }

    /**
     * The work key the backend stores reviews under (backend `workKey`): the tracker entry when tracked, otherwise the
     * exact source work. Friends' titles are lined up with [match], not with this key.
     */
    fun workKey(content: SocialContent): String {
        val provider = content.canonicalProvider
        val id = content.canonicalId
        val basis = if (provider != null && id != null) "canonical|$provider|$id" else "source|${content.sourceWorkKey}"
        return SocialIdentity.key("work-v1|$basis")
    }

    /** The comparable form of a title; "" when nothing comparable is left. */
    fun normalize(title: String): String {
        var text = Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        text = stripLatinAccents(text).replace("&", " and ")
        // Bracketed groups that only label the edition go; any other bracket keeps its words.
        text = BRACKETS.replace(text) { group ->
            val words = words(group.groupValues[1])
            if (words.isNotEmpty() && words.all { it in EDITION_WORDS }) " " else " ${group.groupValues[1]} "
        }
        val words = words(text).toMutableList()
        while (words.size > 1 && words.last() in TRAILING_WORDS) words.removeAt(words.lastIndex)
        // Spacing differs between sources ("One-Punch Man", "Onepunch-Man"), so the key has none.
        return words.joinToString("")
    }

    private fun words(text: String): List<String> = text
        .replace(APOSTROPHES, "")
        .split(SEPARATORS)
        .filter { it.isNotEmpty() }

    /** "Pokémon" → "pokemon"; marks on other scripts (e.g. Japanese dakuten: が ≠ か) are kept. */
    private fun stripLatinAccents(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val kept = StringBuilder(decomposed.length)
        var latinBase = false
        for (char in decomposed) {
            if (Character.getType(char) == Character.NON_SPACING_MARK.toInt()) {
                if (!latinBase) kept.append(char)
                continue
            }
            latinBase = Character.UnicodeScript.of(char.code) == Character.UnicodeScript.LATIN
            kept.append(char)
        }
        return Normalizer.normalize(kept, Normalizer.Form.NFC)
    }

    private val BRACKETS = Regex("""[(\[{【]([^()\[\]{}【】]*)[)\]}】]""")
    private val APOSTROPHES = Regex("""['’‘`´]""")
    private val SEPARATORS = Regex("""[^\p{L}\p{N}]+""")

    /** Words that only describe an edition, never a different work (allowed inside brackets). */
    private val EDITION_WORDS = setOf(
        "official", "colored", "coloured", "color", "colour", "full", "fan", "webtoon", "webcomic", "web", "manhwa",
        "manhua", "manga", "comic", "comics", "raw", "raws", "digital", "uncensored", "censored", "scan", "scans",
        "scanlation", "hd", "edition", "version", "ver", "english", "en", "eng", "korean", "kr", "ko", "japanese", "jp",
        "ja", "chinese", "cn", "zh",
    )

    /** Edition words a source may append without brackets ("Tower of God Raw"). */
    private val TRAILING_WORDS = setOf("raw", "raws", "official", "colored", "coloured", "webtoon")
}

/** The tracker IDs a shared title carries (at most one). */
fun SocialContent.trackers(): Map<String, String> {
    val provider = canonicalProvider ?: return emptyMap()
    val id = canonicalId ?: return emptyMap()
    return mapOf(provider to id)
}
