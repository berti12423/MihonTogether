package mihon.feature.social

/**
 * The quote a reply carries (server-written, see SOCIAL_API "Replies and reactions"): the original's [seq], who
 * wrote it and its first 100 characters. Group quotes also keep the sender's name ([senderName]).
 */
data class ChatQuote(val seq: Long, val senderUid: String, val senderName: String?, val preview: String)

/** One chip under a message: an emoji, how many people chose it, and whether I'm one of them. */
data class ReactionChip(val emoji: String, val count: Int, val mine: Boolean)

/** Replies and reactions in private and group chats: parsing and the pure rules the UI follows. */
object ChatReactions {
    /** The quick reactions in the long-press bar; "+" offers any other emoji. */
    val QUICK = listOf("❤️", "😂", "😮", "😢", "😡", "👍")

    /**
     * `reactions: {uid: emoji}` from a message document; anything malformed is dropped. Shown leniently (the server
     * validated it; a newer Unicode version may know emojis this table doesn't).
     */
    fun parse(value: Any?): Map<String, String> {
        val data = value as? Map<*, *> ?: return emptyMap()
        return buildMap {
            for ((uid, emoji) in data) {
                if (uid is String && emoji is String && emoji.isNotBlank() && emoji.length <= ReactionEmoji.MAX_BYTES) {
                    put(uid, emoji)
                }
            }
        }
    }

    /** `replyTo: {seq, senderUid, preview, senderName?}` from a message document, or null. */
    fun quote(value: Any?): ChatQuote? {
        val data = value as? Map<*, *> ?: return null
        return ChatQuote(
            seq = (data["seq"] as? Number)?.toLong() ?: return null,
            senderUid = data["senderUid"] as? String ?: return null,
            senderName = (data["senderName"] as? String)?.take(40),
            preview = (data["preview"] as? String)?.take(PREVIEW * 2) ?: return null,
        )
    }

    /** Chips with counts, mine marked; most chosen first, ties in a fixed order so chips don't jump around. */
    fun chips(reactions: Map<String, String>, me: String): List<ReactionChip> = reactions.entries
        .groupBy({ it.value }, { it.key })
        .map { (emoji, uids) -> ReactionChip(emoji, uids.size, me in uids) }
        .sortedWith(compareByDescending<ReactionChip> { it.count }.thenBy { it.emoji })

    /**
     * A group quote as I may see it: null (shown as "Earlier message") when it quotes a message from before I joined
     * ([since]; the server can't hide it per reader) or by someone I blocked.
     */
    fun visibleQuote(quote: ChatQuote, since: Long, blocked: Set<String>): ChatQuote? =
        quote.takeIf { it.seq >= since && it.senderUid !in blocked }

    /** Reactions without the people I blocked (left out of the chips and counts). */
    fun withoutBlocked(reactions: Map<String, String>, blocked: Set<String>): Map<String, String> =
        if (blocked.isEmpty()) reactions else reactions.filterKeys { it !in blocked }

    /** What the server does with my reaction: a new emoji replaces mine, the same one again removes it. */
    fun toggle(reactions: Map<String, String>, me: String, emoji: String): Map<String, String> =
        if (reactions[me] == emoji) reactions - me else reactions + (me to emoji)

    /** The reply bar's line for a message, like the server's quote: the text, or "📖 title" for a recommendation. */
    fun preview(text: String, content: SocialContent?): String {
        val line = if (content != null) "📖 ${content.title}" else text
        val end = if (line.codePointCount(0, line.length) >
            PREVIEW
        ) {
            line.offsetByCodePoints(0, PREVIEW)
        } else {
            line.length
        }
        return line.substring(0, end)
    }

    private const val PREVIEW = 100
}

/**
 * Mirrors the backend's `isReactionEmoji` (Berti's decision: any emoji may be a reaction): at most 16 UTF-8 bytes and
 * exactly one emoji — a pictograph with an optional U+FE0F and skin tone, ZWJ sequences of those, a keycap or a flag.
 * Letters, digits on their own, whitespace and control characters are rejected.
 */
object ReactionEmoji {
    const val MAX_BYTES = 16

    fun isValid(value: String): Boolean {
        if (value.isEmpty() || value.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return false
        val points = value.codePoints().toArray()
        return keycap(points) || flag(points) || pictographs(points)
    }

    private fun keycap(points: IntArray): Boolean {
        val base = points.firstOrNull() ?: return false
        if (base != '#'.code && base != '*'.code && base !in '0'.code..'9'.code) return false
        return when (points.size) {
            2 -> points[1] == KEYCAP
            3 -> points[1] == VARIATION && points[2] == KEYCAP
            else -> false
        }
    }

    private fun flag(points: IntArray) = points.size == 2 && points.all { it in REGIONAL_INDICATORS }

    /** pictograph [FE0F] [skin tone] [FE0F], joined by ZWJ. */
    private fun pictographs(points: IntArray): Boolean {
        var i = 0
        while (true) {
            if (i >= points.size || !pictographic(points[i])) return false
            i++
            if (i < points.size && points[i] == VARIATION) i++
            if (i < points.size && points[i] in SKIN_TONES) i++
            if (i < points.size && points[i] == VARIATION) i++
            if (i == points.size) return true
            if (points[i] != ZWJ) return false
            i++
        }
    }

    /** Unicode's Extended_Pictographic property (17.0), by binary search over [PICTOGRAPHIC] range pairs. */
    private fun pictographic(point: Int): Boolean {
        var low = 0
        var high = PICTOGRAPHIC.size / 2 - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                point < PICTOGRAPHIC[mid * 2] -> high = mid - 1
                point > PICTOGRAPHIC[mid * 2 + 1] -> low = mid + 1
                else -> return true
            }
        }
        return false
    }

    private const val VARIATION = 0xFE0F
    private const val ZWJ = 0x200D
    private const val KEYCAP = 0x20E3
    private val SKIN_TONES = 0x1F3FB..0x1F3FF
    private val REGIONAL_INDICATORS = 0x1F1E6..0x1F1FF

    // Generated from \p{Extended_Pictographic} (Unicode 17.0): first, last of each range.
    private val PICTOGRAPHIC = intArrayOf(
        0xA9, 0xA9, 0xAE, 0xAE, 0x203C, 0x203C, 0x2049, 0x2049, 0x2122, 0x2122, 0x2139, 0x2139,
        0x2194, 0x2199, 0x21A9, 0x21AA, 0x231A, 0x231B, 0x2328, 0x2328, 0x23CF, 0x23CF, 0x23E9, 0x23F3,
        0x23F8, 0x23FA, 0x24C2, 0x24C2, 0x25AA, 0x25AB, 0x25B6, 0x25B6, 0x25C0, 0x25C0, 0x25FB, 0x25FE,
        0x2600, 0x2604, 0x260E, 0x260E, 0x2611, 0x2611, 0x2614, 0x2615, 0x2618, 0x2618, 0x261D, 0x261D,
        0x2620, 0x2620, 0x2622, 0x2623, 0x2626, 0x2626, 0x262A, 0x262A, 0x262E, 0x262F, 0x2638, 0x263A,
        0x2640, 0x2640, 0x2642, 0x2642, 0x2648, 0x2653, 0x265F, 0x2660, 0x2663, 0x2663, 0x2665, 0x2666,
        0x2668, 0x2668, 0x267B, 0x267B, 0x267E, 0x267F, 0x2692, 0x2697, 0x2699, 0x2699, 0x269B, 0x269C,
        0x26A0, 0x26A1, 0x26A7, 0x26A7, 0x26AA, 0x26AB, 0x26B0, 0x26B1, 0x26BD, 0x26BE, 0x26C4, 0x26C5,
        0x26C8, 0x26C8, 0x26CE, 0x26CF, 0x26D1, 0x26D1, 0x26D3, 0x26D4, 0x26E9, 0x26EA, 0x26F0, 0x26F5,
        0x26F7, 0x26FA, 0x26FD, 0x26FD, 0x2702, 0x2702, 0x2705, 0x2705, 0x2708, 0x270D, 0x270F, 0x270F,
        0x2712, 0x2712, 0x2714, 0x2714, 0x2716, 0x2716, 0x271D, 0x271D, 0x2721, 0x2721, 0x2728, 0x2728,
        0x2733, 0x2734, 0x2744, 0x2744, 0x2747, 0x2747, 0x274C, 0x274C, 0x274E, 0x274E, 0x2753, 0x2755,
        0x2757, 0x2757, 0x2763, 0x2764, 0x2795, 0x2797, 0x27A1, 0x27A1, 0x27B0, 0x27B0, 0x27BF, 0x27BF,
        0x2934, 0x2935, 0x2B05, 0x2B07, 0x2B1B, 0x2B1C, 0x2B50, 0x2B50, 0x2B55, 0x2B55, 0x3030, 0x3030,
        0x303D, 0x303D, 0x3297, 0x3297, 0x3299, 0x3299, 0x1F004, 0x1F004, 0x1F02C, 0x1F02F, 0x1F094, 0x1F09F,
        0x1F0AF, 0x1F0B0, 0x1F0C0, 0x1F0C0, 0x1F0CF, 0x1F0D0, 0x1F0F6, 0x1F0FF, 0x1F170, 0x1F171, 0x1F17E, 0x1F17F,
        0x1F18E, 0x1F18E, 0x1F191, 0x1F19A, 0x1F1AE, 0x1F1E5, 0x1F201, 0x1F20F, 0x1F21A, 0x1F21A, 0x1F22F, 0x1F22F,
        0x1F232, 0x1F23A, 0x1F23C, 0x1F23F, 0x1F249, 0x1F25F, 0x1F266, 0x1F321, 0x1F324, 0x1F393, 0x1F396, 0x1F397,
        0x1F399, 0x1F39B, 0x1F39E, 0x1F3F0, 0x1F3F3, 0x1F3F5, 0x1F3F7, 0x1F3FA, 0x1F400, 0x1F4FD, 0x1F4FF, 0x1F53D,
        0x1F549, 0x1F54E, 0x1F550, 0x1F567, 0x1F56F, 0x1F570, 0x1F573, 0x1F57A, 0x1F587, 0x1F587, 0x1F58A, 0x1F58D,
        0x1F590, 0x1F590, 0x1F595, 0x1F596, 0x1F5A4, 0x1F5A5, 0x1F5A8, 0x1F5A8, 0x1F5B1, 0x1F5B2, 0x1F5BC, 0x1F5BC,
        0x1F5C2, 0x1F5C4, 0x1F5D1, 0x1F5D3, 0x1F5DC, 0x1F5DE, 0x1F5E1, 0x1F5E1, 0x1F5E3, 0x1F5E3, 0x1F5E8, 0x1F5E8,
        0x1F5EF, 0x1F5EF, 0x1F5F3, 0x1F5F3, 0x1F5FA, 0x1F64F, 0x1F680, 0x1F6C5, 0x1F6CB, 0x1F6D2, 0x1F6D5, 0x1F6E5,
        0x1F6E9, 0x1F6E9, 0x1F6EB, 0x1F6F0, 0x1F6F3, 0x1F6FF, 0x1F7DA, 0x1F7FF, 0x1F80C, 0x1F80F, 0x1F848, 0x1F84F,
        0x1F85A, 0x1F85F, 0x1F888, 0x1F88F, 0x1F8AE, 0x1F8AF, 0x1F8BC, 0x1F8BF, 0x1F8C2, 0x1F8CF, 0x1F8D9, 0x1F8FF,
        0x1F90C, 0x1F93A, 0x1F93C, 0x1F945, 0x1F947, 0x1F9FF, 0x1FA58, 0x1FA5F, 0x1FA6E, 0x1FAFF, 0x1FC00, 0x1FFFD,
    )
}
