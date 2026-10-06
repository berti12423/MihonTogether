package mihon.feature.social.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.feature.social.SocialContent
import mihon.feature.social.SocialPosition
import mihon.feature.social.SocialRoom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadingProtocolTest {
    private val content = SocialContent("work", "A title", "source", "source-work", "edition")
    private val position = SocialPosition("chapter", "Chapter 1", 2, 20, "explicit-page-map")
    private val local = LocalReading(content, position, 7L)
    private val room =
        SocialRoom(
            "room", "host",
            listOf(
                "host",
                "guest",
            ),
            content, "active", 4, 8, position, "session", Long.MAX_VALUE,
        )

    @Test
    fun `equal page counts do not allow following without consent`() {
        assertFalse(ReadingCompatibility.canFollow(local, room, null))
        assertTrue(ReadingCompatibility.canFollow(local, room, position.pageMappingKey))
    }

    @Test
    fun `source edition chapter and explicit page mapping must all match`() {
        val variants = listOf(
            room.copy(content = content.copy(sourceKey = "different-source")),
            room.copy(content = content.copy(sourceWorkKey = "different-work")),
            room.copy(content = content.copy(editionKey = "different-edition")),
            room.copy(position = position.copy(chapterKey = "different-chapter")),
            room.copy(position = position.copy(pageMappingKey = "different-order")),
            room.copy(position = position.copy(pageCount = 21)),
            room.copy(position = position.copy(pageIndex = 20)),
            room.copy(position = position.copy(pageIndex = -1)),
        )
        variants.forEach { assertFalse(ReadingCompatibility.canFollow(local, it, position.pageMappingKey)) }
    }

    @Test
    fun `stale duplicate and different epoch snapshots cannot move reader`() {
        val sequence = RoomSequence(room.epoch)
        assertTrue(sequence.accept(room))
        assertFalse(sequence.accept(room))
        assertFalse(sequence.accept(room.copy(sequence = 7)))
        assertFalse(sequence.accept(room.copy(epoch = 5, sequence = 100)))
        assertTrue(sequence.accept(room.copy(sequence = 9)))
    }

    @Test
    fun `remote page echo is suppressed exactly once`() {
        val echo = RemotePageEcho()
        echo.arm("chapter", 2)
        assertTrue(echo.consume("chapter", 2))
        assertFalse(echo.consume("chapter", 2))
        echo.arm("chapter", 2)
        assertFalse(echo.consume("other-chapter", 2))
        assertFalse(echo.consume("chapter", 2))
    }

    @Test
    fun `a spaced writer publishes at most once per interval and keeps only the latest`() = runTest {
        val writer = LatestPositionWriter(minIntervalMs = 700)
        val published = mutableListOf<Int>()
        val job = launch { writer.run(publish = { published += it.pageIndex }, onFailure = { throw it }) }
        writer.offer(position.copy(pageIndex = 1))
        runCurrent()
        writer.offer(position.copy(pageIndex = 2))
        writer.offer(position.copy(pageIndex = 3))
        advanceTimeBy(600)
        assertEquals(listOf(1), published)
        advanceTimeBy(200)
        assertEquals(listOf(1, 3), published)
        job.cancel()
    }

    @Test
    fun `one writer coalesces rapid turns to last settled position`() = runTest {
        val writer = LatestPositionWriter()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val published = mutableListOf<Int>()
        var inFlight = 0
        var maximumInFlight = 0
        val job = launch {
            writer.run(
                publish = {
                    inFlight++
                    maximumInFlight = maxOf(maximumInFlight, inFlight)
                    if (published.isEmpty()) {
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                    }
                    published += it.pageIndex
                    inFlight--
                },
                onFailure = { throw it },
            )
        }
        writer.offer(position.copy(pageIndex = 1))
        firstEntered.await()
        writer.offer(position.copy(pageIndex = 2))
        writer.offer(position.copy(pageIndex = 3))
        releaseFirst.complete(Unit)
        runCurrent()
        assertEquals(listOf(1, 3), published)
        assertEquals(1, maximumInFlight)
        job.cancel()
    }

    @Test
    fun `failed turn discards pending older offline updates`() = runTest {
        val writer = LatestPositionWriter()
        val firstEntered = CompletableDeferred<Unit>()
        val failFirst = CompletableDeferred<Unit>()
        val published = mutableListOf<Int>()
        var failures = 0
        val job = launch {
            writer.run(
                publish = {
                    if (it.pageIndex == 1) {
                        firstEntered.complete(Unit)
                        failFirst.await()
                        error("offline")
                    }
                    published += it.pageIndex
                },
                onFailure = { failures++ },
            )
        }
        writer.offer(position.copy(pageIndex = 1))
        firstEntered.await()
        writer.offer(position.copy(pageIndex = 2))
        writer.offer(position.copy(pageIndex = 3))
        failFirst.complete(Unit)
        runCurrent()
        assertEquals(1, failures)
        assertTrue(published.isEmpty())
        writer.offer(position.copy(pageIndex = 4))
        runCurrent()
        assertEquals(listOf(4), published)
        job.cancel()
    }

    @Test
    fun `chat cursor never replays history and pops only newer messages`() {
        fun message(seq: Long) = mihon.feature.social.RoomMessage(seq, "host", "reaction", null, "🔥", seq)
        val cursor = RoomChatCursor()
        assertEquals(emptyList<Long>(), cursor.fresh(listOf(message(3), message(4))).map { it.seq })
        assertEquals(listOf(5L), cursor.fresh(listOf(message(3), message(4), message(5))).map { it.seq })
        assertEquals(emptyList<Long>(), cursor.fresh(listOf(message(4), message(5))).map { it.seq })
        assertEquals(listOf(6L, 7L), cursor.fresh(listOf(message(5), message(6), message(7))).map { it.seq })
        val empty = RoomChatCursor()
        assertEquals(emptyList<Long>(), empty.fresh(emptyList()).map { it.seq })
        assertEquals(listOf(0L), empty.fresh(listOf(message(0))).map { it.seq })
    }
}
