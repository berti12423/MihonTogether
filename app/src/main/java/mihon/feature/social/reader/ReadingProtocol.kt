package mihon.feature.social.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import mihon.feature.social.RoomMessage
import mihon.feature.social.SocialContent
import mihon.feature.social.SocialIdentity
import mihon.feature.social.SocialPosition
import mihon.feature.social.SocialRoom

data class LocalReading(
    val content: SocialContent,
    val position: SocialPosition,
    val chapterId: Long,
    /** The manga's source, so an invite can tell friends which extension they need. */
    val sourceId: Long? = null,
    /** The title's URL in that source (SManga.url), so a friend with the source can open it in one step. */
    val mangaUrl: String? = null,
)

/** Page counts/descriptors alone cannot establish an edition's page order. */
object ReadingCompatibility {
    fun manualMapping(content: SocialContent, chapterKey: String, count: Int): String =
        SocialIdentity.key("confirmed-page-order-v1|${content.sourceKey}|${content.editionKey}|$chapterKey|$count")

    fun sameEdition(local: SocialContent, remote: SocialContent): Boolean =
        local.contentKey == remote.contentKey && local.sourceKey == remote.sourceKey &&
            local.sourceWorkKey == remote.sourceWorkKey && local.editionKey == remote.editionKey

    fun canFollow(local: LocalReading, room: SocialRoom, confirmedMapping: String?): Boolean {
        val remote = room.position ?: return false
        return sameEdition(local.content, room.content) &&
            local.position.chapterKey == remote.chapterKey &&
            local.position.pageMappingKey == remote.pageMappingKey &&
            local.position.pageCount == remote.pageCount &&
            remote.pageIndex in 0 until local.position.pageCount &&
            (remote.stripPosition == null || remote.stripPosition < local.position.pageCount) &&
            confirmedMapping == remote.pageMappingKey
    }
}

/** A bound epoch cannot silently migrate; duplicates and old snapshots never drive navigation. */
class RoomSequence(private val epoch: Long) {
    private var latest: Long = -1

    fun accept(room: SocialRoom): Boolean {
        if (room.epoch != epoch || room.sequence <= latest) return false
        latest = room.sequence
        return true
    }
}

class RemotePageEcho {
    private var expected: Pair<String, Int>? = null

    fun arm(chapterKey: String, index: Int) {
        expected = chapterKey to index
    }

    fun consume(chapterKey: String, index: Int): Boolean {
        val echo = expected == (chapterKey to index)
        expected = null
        return echo
    }
}

/**
 * Exactly one suspended publisher, plus a single latest pending turn. Failures discard the queue.
 * [minIntervalMs] spaces publishes so a continuous long-strip scroll costs at most one write per interval.
 */
class LatestPositionWriter(private val minIntervalMs: Long = 0) {
    private val pending = Channel<SocialPosition>(Channel.CONFLATED)

    fun offer(position: SocialPosition) {
        pending.trySend(position)
    }

    suspend fun run(publish: suspend (SocialPosition) -> Unit, onFailure: suspend (Exception) -> Unit) {
        for (position in pending) {
            try {
                publish(position)
                if (minIntervalMs > 0) delay(minIntervalMs)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                while (pending.tryReceive().isSuccess) { /* never replay a failed offline turn */ }
                onFailure(error)
            }
        }
    }
}

/**
 * Decides which room messages are new enough to pop on screen. The first snapshot after (re)subscribing is history:
 * it is shown in the chat but never animated, so reopening the reader doesn't replay old reactions.
 */
class RoomChatCursor {
    private var latest: Long? = null

    fun fresh(messages: List<RoomMessage>): List<RoomMessage> {
        val previous = latest
        latest = maxOf(previous ?: -1, messages.maxOfOrNull { it.seq } ?: -1)
        return if (previous == null) emptyList() else messages.filter { it.seq > previous }
    }
}
