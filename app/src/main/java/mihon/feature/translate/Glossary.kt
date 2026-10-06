package mihon.feature.translate

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One "find → replace" pair of a title's glossary. */
@Serializable
data class GlossaryEntry(val find: String, val replace: String)

/**
 * A title's own replacements (names the translator gets wrong, preferred terms), applied to translations when a page
 * is drawn. They aren't part of the translation cache key, so changing them never costs a new translation.
 */
data class Glossary(val entries: List<GlossaryEntry> = emptyList()) {
    /** Replaces every entry's text (ignoring case), in order. Blank "find" entries are ignored. */
    fun apply(text: String): String = entries
        .filter { it.find.isNotBlank() }
        .fold(text) { acc, entry -> acc.replace(entry.find.trim(), entry.replace.trim(), ignoreCase = true) }

    /** The entries worth keeping: non-blank "find", trimmed, no duplicates. */
    fun cleaned(): Glossary = Glossary(
        entries.map { GlossaryEntry(it.find.trim(), it.replace.trim()) }
            .filter { it.find.isNotEmpty() }
            .distinctBy { it.find.lowercase() },
    )

    fun encode(): String = if (entries.isEmpty()) "" else json.encodeToString(serializer, entries)

    companion object {
        val EMPTY = Glossary()
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(GlossaryEntry.serializer())

        fun decode(value: String): Glossary {
            if (value.isBlank()) return EMPTY
            return runCatching { Glossary(json.decodeFromString(serializer, value)) }.getOrDefault(EMPTY)
        }
    }
}

/** The room chat line for sharing one bubble: `❝original❞ → translation`, at most [max] characters. */
object RoomQuote {
    const val MAX_LENGTH = 200

    fun format(source: String, translation: String, max: Int = MAX_LENGTH): String {
        val original = source.oneLine()
        val translated = translation.oneLine()
        val full = "❝$original❞ → $translated"
        if (full.length <= max) return full
        // Keep the translation whole where possible; shorten the original first, then the translation.
        val frame = "❝❞ → ".length
        val roomForOriginal = max - frame - translated.length
        if (roomForOriginal >= 4) return "❝${original.shorten(roomForOriginal)}❞ → $translated"
        val roomForTranslation = max - frame - 2
        return "❝${original.shorten(2)}❞ → ${translated.shorten(roomForTranslation)}"
    }

    private fun String.oneLine() = replace(Regex("\\s+"), " ").trim()

    private fun String.shorten(length: Int): String =
        if (this.length <= length) this else take((length - 1).coerceAtLeast(0)).trimEnd() + "…"
}
