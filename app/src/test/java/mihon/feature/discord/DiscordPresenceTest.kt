package mihon.feature.discord

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiscordPresenceTest {
    private val rules = DiscordRules(enabled = true, allowedSources = setOf("42"), showCover = true, showChapter = true)
    private val reading = ReadingNow(
        sourceId = 42,
        title = "Omniscient Reader",
        coverUrl = "https://cdn.example/cover.jpg",
        chapterName = "Chapter 12",
        chapterOpenedAt = 1_000,
        private = false,
    )
    private val with: (String) -> String = { "reading with $it" }

    @Test
    fun `allowlisted titles show title, chapter, cover and start time`() {
        val info = PresenceRules.presence(reading, rules, with)!!
        assertEquals("Omniscient Reader", info.details)
        assertEquals("Chapter 12", info.state)
        assertEquals("https://cdn.example/cover.jpg", info.coverUrl)
        assertEquals(1_000, info.startedAt)
        assertNull(info.partySize)
    }

    @Test
    fun `privacy wins - off, not allowlisted, incognito or hidden shows nothing`() {
        assertNull(PresenceRules.presence(reading, rules.copy(enabled = false), with))
        assertNull(PresenceRules.presence(reading.copy(sourceId = 7), rules, with))
        assertNull(PresenceRules.presence(reading.copy(private = true), rules, with))
        assertNull(PresenceRules.presence(null, rules, with))
    }

    @Test
    fun `cover and chapter toggles, local covers and read together`() {
        val noExtras = PresenceRules.presence(reading, rules.copy(showCover = false, showChapter = false), with)!!
        assertNull(noExtras.coverUrl)
        assertNull(noExtras.state)
        assertNull(PresenceRules.presence(reading.copy(coverUrl = "file:///sdcard/cover.jpg"), rules, with)!!.coverUrl)
        val together = PresenceRules.presence(
            reading.copy(readingWith = "Shikori1", roomId = "room-abc"),
            rules,
            with,
        )!!
        assertEquals("Chapter 12", together.state)
        assertEquals("reading with Shikori1", together.extra)
        assertEquals(2, together.partySize)
        assertEquals(PresenceRules.partyIdFor("room-abc"), together.partyId) // same for both readers
        assertTrue(together.partyId!!.startsWith("mt-") && "room-abc" !in together.partyId!!)
    }

    @Test
    fun `the download button links to the website only when wanted and https`() {
        val url = "https://mihontogether.web.app/download"
        assertEquals(url, PresenceRules.presence(reading, rules, with, url)!!.buttonUrl)
        assertNull(PresenceRules.presence(reading, rules.copy(showButton = false), with, url)!!.buttonUrl)
        assertNull(PresenceRules.presence(reading, rules, with, "http://insecure.example/download")!!.buttonUrl)
        assertNull(PresenceRules.presence(reading, rules, with)!!.buttonUrl)
    }

    @Test
    fun `level and room share the second line, the level only when wanted`() {
        val leveled = reading.copy(level = 12)
        assertEquals("Level 12", PresenceRules.presence(leveled, rules, with)!!.extra)
        assertEquals(
            "Level 12 • reading with Alex",
            PresenceRules.presence(leveled.copy(readingWith = "Alex", roomId = "r"), rules, with)!!.extra,
        )
        assertNull(PresenceRules.presence(leveled, rules.copy(showLevel = false), with)!!.extra)
        assertNull(PresenceRules.presence(reading, rules, with)!!.extra)
    }

    @Test
    fun `the logo needs https`() {
        val logo = "https://mihontogether.web.app/discord-logo.png"
        assertEquals(logo, PresenceRules.presence(reading, rules, with, logoUrl = logo)!!.logoUrl)
        assertNull(PresenceRules.presence(reading, rules, with, logoUrl = "http://x/logo.png")!!.logoUrl)
    }

    @Test
    fun `shows as watching by default, playing when chosen`() {
        assertTrue(PresenceRules.presence(reading, rules, with)!!.watching)
        assertEquals(false, PresenceRules.presence(reading, rules.copy(watching = false), with)!!.watching)
    }

    private class Recorder : DiscordPresence {
        override val available = true
        val calls = mutableListOf<String>()
        override fun update(info: PresenceInfo) {
            calls += "update ${info.state}"
        }
        override fun clear() {
            calls += "clear"
        }
        override fun clearAfter(delayMs: Long) {
            calls += "clearAfter $delayMs"
        }
    }

    private fun info(state: String) = PresenceInfo("T", state, null, 0)

    @Test
    fun `updates are deduplicated and spaced, the latest wins, clearing is immediate`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val backend = Recorder()
        val throttle = PresenceThrottle(scope, backend, clock = { testScheduler.currentTime }, minIntervalMs = 15_000)

        throttle.show(info("Chapter 1"))
        scope.runCurrent()
        throttle.show(info("Chapter 1")) // same: skipped
        scope.runCurrent()
        assertEquals(listOf("update Chapter 1"), backend.calls)

        throttle.show(info("Chapter 2"))
        scope.runCurrent()
        throttle.show(info("Chapter 3")) // within 15 s: only the latest is sent, when allowed
        scope.runCurrent()
        assertEquals(listOf("update Chapter 1"), backend.calls)
        scope.advanceTimeBy(15_001)
        scope.runCurrent()
        assertEquals(listOf("update Chapter 1", "update Chapter 3"), backend.calls)

        throttle.show(info("Chapter 4"))
        scope.runCurrent()
        throttle.show(null) // privacy: no waiting
        scope.runCurrent()
        assertEquals(listOf("update Chapter 1", "update Chapter 3", "clear"), backend.calls)
        scope.cancel()
    }

    @Test
    fun `leaving the reader clears right away, coming back shows the status again`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val backend = Recorder()
        val throttle = PresenceThrottle(scope, backend, clock = { testScheduler.currentTime }, minIntervalMs = 15_000)

        throttle.show(null) // nothing shown: nothing to clear
        scope.runCurrent()
        assertEquals(emptyList<String>(), backend.calls)

        throttle.show(info("Chapter 1"))
        scope.runCurrent()
        throttle.show(null) // the reader went to the background
        scope.runCurrent()
        assertEquals(listOf("update Chapter 1", "clear"), backend.calls)

        scope.advanceTimeBy(20_000)
        throttle.show(info("Chapter 1")) // back in the reader
        scope.runCurrent()
        assertEquals(listOf("update Chapter 1", "clear", "update Chapter 1"), backend.calls)
        scope.cancel()
    }
}

private fun TestScope.cancel() = coroutineContext[kotlinx.coroutines.Job]?.cancel()
