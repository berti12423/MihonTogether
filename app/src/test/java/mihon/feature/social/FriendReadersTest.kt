package mihon.feature.social

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FriendReadersTest {
    private fun content(title: String, source: String = "b", tracker: Pair<String, String>? = null) = SocialContent(
        contentKey = SocialIdentity.key("content|$source|$title"),
        title = title,
        sourceKey = "source-v1:$source",
        sourceWorkKey = SocialIdentity.key("work|$source|$title"),
        editionKey = SocialIdentity.key("edition|$source|$title"),
        canonicalProvider = tracker?.first,
        canonicalId = tracker?.second,
    )

    private fun profile(name: String) = SocialProfile(name.lowercase(), name.lowercase(), name)

    private fun review(content: SocialContent, rating: Int, updatedAt: Long = 1) =
        SocialReview(TitleMatch.workKey(content), content, rating, null, updatedAt, updatedAt)

    private val mine = TitleMatch.Work(
        sourceKey = "source-v1:a",
        sourceWorkKey = SocialIdentity.key("work|a|Solo Leveling"),
        trackers = mapOf("anilist" to "105398"),
        titles = listOf("Solo Leveling"),
    )

    @Test
    fun `progress counts as read once a chapter was opened, finished when every known chapter is read`() {
        assertNull(SocialProgress.of(readCount = 0, totalChapters = 120, lastRead = 0))
        assertEquals(SocialProgress(0, 120, false), SocialProgress.of(0, 120, lastRead = 1_700_000_000_000))
        assertEquals(SocialProgress(40, 120, false), SocialProgress.of(40, 120, 1))
        assertEquals(SocialProgress(12, 12, true), SocialProgress.of(12, 12, 1))
        // Counts that disagree (scanlator filters) never claim more read than known.
        assertEquals(SocialProgress(5, 5, true), SocialProgress.of(7, 5, 1))
        assertEquals(mapOf("read" to 40, "total" to 120, "finished" to false), SocialProgress(40, 120, false).payload())
    }

    @Test
    fun `progress parsing is tolerant and never trusts a contradictory finished flag`() {
        assertEquals(
            SocialProgress(40, 120, false),
            SocialProgress.parse(
                mapOf(
                    "read" to 40L,
                    "total" to 120L,
                    "finished" to false,
                ),
            ),
        )
        assertEquals(
            SocialProgress(3, 9, false),
            SocialProgress.parse(
                mapOf(
                    "read" to 3,
                    "total" to 9,
                    "finished" to true,
                ),
            ),
        )
        assertNull(SocialProgress.parse(mapOf("read" to 10, "total" to 9, "finished" to false)))
        assertNull(SocialProgress.parse(mapOf("read" to "1", "total" to 9)))
        assertNull(SocialProgress.parse(null))
    }

    @Test
    fun `friends who read a title on other sources are found, with progress and reviews, finished first`() {
        val alex = FriendShelf(
            profile("Alex"),
            entries = listOf(SharedEntry(content("Solo Leveling (Official)"), SocialProgress(40, 120, false))),
        )
        val shikori = FriendShelf(
            profile("Shikori1"),
            entries = listOf(
                SharedEntry(
                    content("Na Honjaman Level Up", tracker = "anilist" to "105398"),
                    SocialProgress(200, 200, true),
                ),
            ),
            reviews = listOf(review(content("Solo Leveling", "c"), 5)),
        )
        val reviewerOnly = FriendShelf(profile("Bo"), reviews = listOf(review(content("SOLO LEVELING"), 3)))
        val other = FriendShelf(
            profile("Cat"),
            entries = listOf(
                SharedEntry(content("Solo Leveling: Ragnarok"), SocialProgress(5, 30, false)),
                // In the library but never opened: not a reader.
                SharedEntry(content("Solo Leveling", "d"), null),
            ),
        )
        val readers = FriendReadings.collect(mine, listOf(other, reviewerOnly, alex, shikori))
        assertEquals(listOf("Shikori1", "Alex", "Bo"), readers.map { it.profile.displayName })
        assertEquals(SocialProgress(200, 200, true), readers[0].progress)
        assertEquals(5, readers[0].review?.rating)
        assertNull(readers[2].progress)
        assertEquals(3, readers[2].review?.rating)
    }

    @Test
    fun `a conflicting tracker ID keeps a same-named work out`() {
        val impostor = FriendShelf(
            profile("Dee"),
            entries = listOf(
                SharedEntry(content("Solo Leveling", tracker = "anilist" to "1"), SocialProgress(1, 2, false)),
            ),
        )
        assertEquals(emptyList<FriendReading>(), FriendReadings.collect(mine, listOf(impostor)))
    }

    @Test
    fun `my review is the newest of mine that matches the title`() {
        val older = review(content("Solo Leveling", "a"), 2, updatedAt = 1)
        val newer = review(content("Solo Leveling", "x", "anilist" to "105398"), 4, updatedAt = 2)
        assertEquals(newer, FriendReadings.mine(mine, listOf(older, newer, review(content("Other"), 1, 3))))
        assertNull(FriendReadings.mine(mine, listOf(review(content("Other"), 1))))
    }

    @Test
    fun `reviews parse leniently and text is cleaned like the server does`() {
        val content = content("Solo Leveling")
        val parsed = SocialReview.parse(
            mapOf(
                "workKey" to "k",
                "content" to content.payload(),
                "rating" to 4L,
                "text" to "Great",
                "createdAt" to 1L,
                "updatedAt" to 2L,
            ),
        ) { (it as? Number)?.toLong() }
        assertEquals(SocialReview("k", content, 4, "Great", 1, 2), parsed)
        assertNull(SocialReview.parse(mapOf("workKey" to "k", "content" to content.payload(), "rating" to 6)) { null })
        assertNull(SocialReview.parse(mapOf("workKey" to "k", "rating" to 3)) { null })
        assertEquals("Line one\nLine two", SocialReview.cleanText("  Line one\r\nLine two\u0007  "))
        assertNull(SocialReview.cleanText(" \n "))
        assertEquals(SocialReview.MAX_TEXT, SocialReview.cleanText("x".repeat(2_000))?.length)
        assertEquals("★★★☆☆", SocialReview.stars(3))
    }

    // ---------- review fixes (2026-10-06) ----------

    private fun sharing(
        generation: Long = 2,
        library: String = "friends",
        reviews: Boolean = true,
        progress: Boolean = true,
    ) = SocialSharing(generation = generation, library = library, reviews = reviews, progress = progress)

    @Test
    fun `a friend's narrower settings drop what they no longer share`() {
        val shelf = FriendShelf(
            profile("Alex"),
            entries = listOf(SharedEntry(content("Solo Leveling"), SocialProgress(1, 2, false))),
            reviews = listOf(review(content("Solo Leveling"), 4)),
            sharing = sharing(),
        )
        assertEquals(shelf, shelf.allowedBy(sharing()))
        assertTrue(shelf.allowedBy(sharing(reviews = false)).reviews.isEmpty())
        assertEquals(1, shelf.allowedBy(sharing(reviews = false)).entries.size)
        assertTrue(shelf.allowedBy(sharing(library = "private")).entries.isEmpty())
        assertTrue(shelf.allowedBy(sharing(progress = false)).entries.isEmpty())
        // Any privacy change bumps the generation: entries read under the old one go.
        assertTrue(shelf.allowedBy(sharing(generation = 3)).entries.isEmpty())
        assertTrue(shelf.allowedBy(sharing().copy(invisible = true)).reviews.isEmpty())
        assertTrue(shelf.allowedBy(sharing(library = "private", reviews = false)).isEmpty)
    }

    @Test
    fun `rating replaces my older review only for the same source entry or tracker ID`() {
        // Same source entry (the title got tracked since).
        assertTrue(FriendReadings.replaces(mine, review(content("Solo Leveling", "a"), 3)))
        // Same tracker ID on another source.
        assertTrue(FriendReadings.replaces(mine, review(content("Other name", "x", "anilist" to "105398"), 3)))
        // Only the title matches: maybe another work, both stay.
        assertFalse(FriendReadings.replaces(mine, review(content("Solo Leveling", "x"), 3)))
        assertFalse(FriendReadings.replaces(mine, review(content("Unrelated", "x"), 3)))
    }

    /** A fake backend: [current] is each friend's live settings; reads under other settings are denied. */
    private class FakeSource(var uid: String? = "me") : FriendShelfSource {
        val current = mutableMapOf<String, SocialSharing>()
        val entries = mutableMapOf<String, List<SharedEntry>>()
        val reviews = mutableMapOf<String, List<SocialReview>>()
        val friendCalls = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        val accepted = MutableStateFlow<Set<String>?>(null)

        override val currentUid: String? get() = uid
        override suspend fun friendIds(): List<String> {
            gate?.await()
            return current.keys.sorted()
        }
        override suspend fun friend(uid: String): SocialFriendDetails {
            friendCalls += uid
            return SocialFriendDetails(SocialProfile(uid, uid, uid.uppercase()), current.getValue(uid))
        }
        override suspend fun startedEntries(uid: String, generation: Long): List<SharedEntry> {
            val live = current[uid]
            check(live != null && live.library == "friends" && live.generation == generation) { "denied" }
            return entries[uid].orEmpty()
        }
        override suspend fun reviews(uid: String): List<SocialReview> {
            if (uid == this.uid) return emptyList()
            check(current[uid]?.reviews == true) { "denied" }
            return reviews[uid].orEmpty()
        }
        override fun acceptedForRevocation(uid: String): Flow<Set<String>?> = accepted
    }

    private fun TestScope.shelves(source: FakeSource) = FriendShelves(source, backgroundScope) {
        testScheduler.currentTime
    }

    @Test
    fun `known settings are reused, a denied read asks for the current ones once`() = runTest {
        val source = FakeSource()
        source.current["bo"] = sharing()
        source.entries["bo"] = listOf(SharedEntry(content("Solo Leveling"), SocialProgress(3, 9, false)))
        source.current["cat"] = sharing(library = "private")
        source.reviews["cat"] = listOf(review(content("Solo Leveling"), 5))
        val readers = shelves(source)

        val first = readers.snapshot()!!
        assertEquals(listOf("bo", "cat"), first.friends.map { it.profile.uid })
        assertEquals(listOf("bo", "cat"), source.friendCalls)

        // Stale: reloaded with the settings already known, no socialGetFriend.
        advanceTimeBy(FriendShelves.MAX_AGE_MS + 1)
        readers.snapshot()
        assertEquals(listOf("bo", "cat"), source.friendCalls)

        // Bo turns progress off (a new generation): the read under the old one is denied, the current settings asked.
        source.current["bo"] = sharing(generation = 3, progress = false)
        advanceTimeBy(FriendShelves.MAX_AGE_MS + 1)
        val after = readers.snapshot()!!
        assertEquals(listOf("bo", "cat", "bo"), source.friendCalls)
        assertEquals(listOf("cat"), after.friends.map { it.profile.uid })
    }

    @Test
    fun `settings seen on a profile drop data at once, and a removed friend goes`() = runTest {
        val source = FakeSource()
        source.current["bo"] = sharing()
        source.entries["bo"] = listOf(SharedEntry(content("Solo Leveling"), SocialProgress(3, 9, false)))
        source.reviews["bo"] = listOf(review(content("Solo Leveling"), 4))
        source.current["cat"] = sharing()
        source.reviews["cat"] = listOf(review(content("Solo Leveling"), 5))
        val readers = shelves(source)
        readers.snapshot()

        readers.onFriendSharing(SocialFriendDetails(SocialProfile("bo", "bo", "BO"), sharing(reviews = false)))
        val bo = readers.cache.value!!.friends.first { it.profile.uid == "bo" }
        assertTrue(bo.reviews.isEmpty())
        assertEquals(1, bo.entries.size)
        readers.onFriendSharing(SocialFriendDetails(SocialProfile("bo", "bo", "BO"), sharing(generation = 3)))
        assertEquals(listOf("cat"), readers.cache.value!!.friends.map { it.profile.uid })

        source.accepted.value = emptySet()
        runCurrent()
        assertTrue(readers.cache.value!!.friends.isEmpty())
    }

    @Test
    fun `a load keeps going when the title page that asked for it goes away`() = runTest {
        val source = FakeSource()
        source.current["cat"] = sharing()
        source.reviews["cat"] = listOf(review(content("Solo Leveling"), 5))
        source.gate = CompletableDeferred()
        val readers = shelves(source)

        val page = launch { readers.snapshot() }
        runCurrent()
        page.cancelAndJoin()
        source.gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("cat"), readers.cache.value?.friends?.map { it.profile.uid })
        // The next page gets the held snapshot without another load.
        assertNotNull(readers.snapshot())
        assertEquals(listOf("cat"), source.friendCalls)
    }

    @Test
    fun `coming back to the app reloads an old snapshot`() = runTest {
        val source = FakeSource()
        source.current["cat"] = sharing()
        source.reviews["cat"] = listOf(review(content("Solo Leveling"), 5))
        val readers = shelves(source)
        readers.snapshot()
        val watcher = launch { readers.cache.collect {} }
        runCurrent()

        source.reviews["cat"] = emptyList()
        readers.onForeground() // too soon: nothing happens
        runCurrent()
        assertEquals(1, readers.cache.value!!.friends.size)
        advanceTimeBy(FriendShelves.FOREGROUND_RELOAD_MS + 1)
        readers.onForeground()
        runCurrent()
        assertTrue(readers.cache.value!!.friends.isEmpty())
        watcher.cancel()
    }

    @Test
    fun `the progress switch is absent-means-on and only sent once the server stores it, or to turn it off`() {
        val base = mapOf<String, Any?>(
            "generation" to 2L,
            "library" to "friends",
            "favorites" to "private",
            "activity" to "private",
            "presence" to "private",
            "lastSeen" to "private",
        )
        val older = base.sharing()
        assertTrue(older.progress)
        assertFalse("progress" in older.payload())
        assertEquals(false, older.copy(progress = false).payload()["progress"])
        val stored = (base + ("progress" to true)).sharing()
        assertEquals(true, stored.payload()["progress"])
        assertFalse((base + ("progress" to false)).sharing().progress)
    }
}
