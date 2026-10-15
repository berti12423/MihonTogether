package mihon.feature.social

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SharingEditorTest {
    private val everything = SocialSharing(
        generation = 7,
        library = "friends",
        favorites = "friends",
        activity = "friends",
        presence = "friends",
        lastSeen = "friends",
        feed = "friends",
        stats = "friends",
    )

    @Test
    fun `every switch turns exactly its own setting off and on`() {
        for (switch in SharingSwitch.entries) {
            val start = if (switch == SharingSwitch.INVISIBLE) everything else everything.copy(invisible = false)
            val flipped = SharingEditor.set(start, switch, !SharingEditor.isOn(start, switch))
            assertEquals(!SharingEditor.isOn(start, switch), SharingEditor.isOn(flipped, switch), "$switch")
            for (other in SharingSwitch.entries - switch) {
                assertEquals(
                    SharingEditor.isOn(start, other),
                    SharingEditor.isOn(flipped, other),
                    "$switch changed $other",
                )
            }
            assertEquals(start.generation, flipped.generation, "the server bumps the generation, not the app")
        }
    }

    @Test
    fun `visibility switches send friends or private`() {
        val off = SharingEditor.set(everything, SharingSwitch.LIBRARY, false)
        assertEquals("private", off.library)
        assertEquals("friends", SharingEditor.set(off, SharingSwitch.LIBRARY, true).library)
        assertEquals("private", SharingEditor.set(everything, SharingSwitch.LAST_SEEN, false).lastSeen)
    }

    @Test
    fun `reading progress can only change while the library is shared`() {
        assertTrue(SharingEditor.enabled(everything, SharingSwitch.PROGRESS, busy = false))
        assertFalse(SharingEditor.enabled(everything.copy(library = "private"), SharingSwitch.PROGRESS, busy = false))
    }

    @Test
    fun `ranking pictures need the global ranking and a visible account`() {
        assertTrue(SharingEditor.enabled(everything, SharingSwitch.RANKING_MEDIA, busy = false))
        assertFalse(
            SharingEditor.enabled(everything.copy(globalRanking = false), SharingSwitch.RANKING_MEDIA, busy = false),
        )
        assertFalse(SharingEditor.enabled(everything.copy(invisible = true), SharingSwitch.RANKING_MEDIA, busy = false))
    }

    @Test
    fun `nothing can change while a change is being sent`() {
        for (switch in SharingSwitch.entries) {
            assertFalse(
                SharingEditor.enabled(everything, switch, busy = true),
                "$switch",
            )
        }
    }

    @Test
    fun `invisible is the last switch, apart from the sharing switches`() {
        assertEquals(SharingSwitch.INVISIBLE, SharingSwitch.entries.last())
    }
}
