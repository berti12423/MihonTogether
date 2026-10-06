package mihon.feature.social

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR

class SocialGroupsTest {
    @Test
    fun `the level curve matches the backend`() {
        assertEquals(listOf(0L, 50L, 150L, 300L, 500L), (1..5).map(XpLevels::xpFor))
        assertEquals(listOf(1, 1, 2, 2, 3, 20), listOf(0L, 49L, 50L, 149L, 150L, 10_000L).map(XpLevels::levelFor))
        assertEquals(25L to 100L, XpLevels.progress(75))
        assertEquals(0L to 50L, XpLevels.progress(0))
    }

    @Test
    fun `badges match the backend`() {
        val domain = listOf(java.io.File("../backend/src/domain.ts"), java.io.File("backend/src/domain.ts")).first {
            it.exists()
        }
        val table = domain.readText().substringAfter("export const BADGES").substringBefore("];")
        val backend = Regex("""id: "(\w+)"""").findAll(table).map { it.groupValues[1] }.toList()
        assertEquals(backend, Badge.entries.map { it.id })
    }

    @Test
    fun `weeks start on Monday UTC`() {
        assertEquals("20261005", xpWeek(java.time.Instant.parse("2026-10-05T12:00:00Z")))
        assertEquals("20261005", xpWeek(java.time.Instant.parse("2026-10-11T23:59:00Z")))
        assertEquals("20261012", xpWeek(java.time.Instant.parse("2026-10-12T00:00:00Z")))
        val xp = SocialXp(total = 100, level = 2, week = "20261005", weekXp = 40)
        assertEquals(40, xp.weekXp("20261005"))
        assertEquals(0, xp.weekXp("20261012"))
    }

    @Test
    fun `level titles by band`() {
        assertEquals(MR.strings.social_level_title_1, XpLevels.title(1))
        assertEquals(MR.strings.social_level_title_2, XpLevels.title(5))
        assertEquals(MR.strings.social_level_title_3, XpLevels.title(14))
        assertEquals(MR.strings.social_level_title_6, XpLevels.title(99))
    }

    @Test
    fun `group pushes parse their group and stack per group`() {
        val id = "b".repeat(64)
        val push = SocialPushMessage.parse(
            mapOf(
                "kind" to "group_message",
                "name" to "Bob",
                "title" to "hi",
                "group" to " Night readers ",
                "groupId" to id,
            ),
        )!!
        assertEquals("Night readers", push.group)
        assertEquals(id, push.groupId)
        assertNull(SocialPushMessage.parse(mapOf("kind" to "group_added", "name" to "Bob", "groupId" to "x"))!!.groupId)
        val other = push.copy(groupId = "c".repeat(64))
        assertNotEquals(push.notificationId(), other.notificationId())
        assertEquals(push.notificationId(), push.copy(title = "again").notificationId())
    }
}

class RoomSignalsTest {
    private fun message(seq: Long, uid: String, kind: String, at: Long = 1_000) =
        RoomMessage(seq, uid, kind, null, null, at)

    @org.junit.jupiter.api.Test
    fun `latest signal per member since the chapter change and within the time window`() {
        val messages = listOf(
            message(1, "ana", RoomMessage.KIND_WAIT),
            message(2, "bob", RoomMessage.KIND_READY),
            message(3, "ana", RoomMessage.KIND_TEXT),
            message(4, "ana", RoomMessage.KIND_READY),
            message(5, "cat", RoomMessage.KIND_WAIT, at = 0),
        )
        assertEquals(
            mapOf("bob" to "ready", "ana" to "ready", "cat" to "wait"),
            mihon.feature.social.reader.RoomSignals.current(messages, floorSeq = 0, now = 2_000),
        )
        // After a chapter change at seq 3 only newer signals count, and none after 15 minutes.
        assertEquals(
            mapOf("ana" to "ready", "cat" to "wait"),
            mihon.feature.social.reader.RoomSignals.current(messages, floorSeq = 3, now = 2_000),
        )
        assertEquals(
            emptyMap<String, String>(),
            mihon.feature.social.reader.RoomSignals.current(messages, floorSeq = 3, now = 16 * 60_000L + 1_000),
        )
    }
}
