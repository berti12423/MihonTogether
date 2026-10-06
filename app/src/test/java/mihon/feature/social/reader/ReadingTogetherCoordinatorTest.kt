package mihon.feature.social.reader

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mihon.feature.social.RoomMember
import mihon.feature.social.SocialAccount
import mihon.feature.social.SocialContent
import mihon.feature.social.SocialFeedItem
import mihon.feature.social.SocialPosition
import mihon.feature.social.SocialProfile
import mihon.feature.social.SocialRepository
import mihon.feature.social.SocialRoom
import mihon.feature.social.SocialRoomUnavailable
import mihon.feature.social.SocialSelf
import mihon.feature.social.SocialSharing
import mihon.feature.social.SocialSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingTogetherCoordinatorTest {
    private val content = SocialContent("work", "A title", "source", "source-work", "edition")

    private fun position(index: Int) = SocialPosition("chapter", "Chapter 1", index, 20, "map")

    private fun room(sequence: Long, index: Int) = SocialRoom(
        "room", "host", listOf("host", "guest"), content, "active", 1, sequence, position(index),
        "session", Long.MAX_VALUE,
        members = mapOf(
            "host" to RoomMember("host", RoomMember.JOINED, "Host", joinedAt = 1),
            "guest" to RoomMember("guest", RoomMember.JOINED, "Guest", joinedAt = 2),
        ),
    )

    private fun repository(
        roomFlow: Flow<SocialRoom?>,
        accepted: Flow<Set<String>?> = MutableStateFlow(setOf("host")),
        settings: Flow<SocialSharing> = MutableStateFlow(SocialSharing(generation = 1)),
        hidden: Flow<Set<String>> = MutableStateFlow(emptySet()),
    ) = mockk<SocialRepository>(relaxed = true) {
        every { currentUid } returns "guest"
        every { authChanges() } returns flowOf(SocialAccount("guest", null, true))
        coEvery { getSelf() } returns SocialSelf(null, SocialSharing(generation = 1))
        every { watchSettings("guest") } returns settings
        every { watchHidden("guest") } returns hidden
        every { watchAcceptedForRevocation("guest") } returns accepted
        every { watchFriends() } returns MutableStateFlow(emptyList())
        every { watchRoom("room") } returns roomFlow
    }

    @Test
    fun `re-enabling follow applies the host's current page`() = runTest(UnconfinedTestDispatcher()) {
        val roomFlow = MutableStateFlow(room(sequence = 8, index = 2))
        val sessions = ReadingSessionStore().apply { select("room") }
        val coordinator = ReadingTogetherCoordinator(repository(roomFlow), sessions) { _, _ -> null }
        val applied = mutableListOf<Int>()

        backgroundScope.launch { coordinator.runForeground() }
        // Mirrors ReaderActivity: the viewer only moves for moves the coordinator re-validates.
        backgroundScope.launch {
            coordinator.remoteMoves.collect { if (coordinator.consumeMove(it)) applied += it.pageIndex }
        }
        coordinator.setIncognito(false)
        coordinator.pageSelected(LocalReading(content, position(0), chapterId = 7))
        coordinator.confirmPageOrder()

        coordinator.setFollowing(true)
        assertEquals(listOf(2), applied)

        coordinator.setFollowing(false)
        roomFlow.value = room(sequence = 9, index = 5)
        coordinator.pageSelected(LocalReading(content, position(0), chapterId = 7))
        assertEquals(listOf(2), applied)

        coordinator.setFollowing(true)
        assertEquals(listOf(2, 5), applied)
    }

    @Test
    fun `a following guest receives the host's long-strip scroll position`() = runTest(UnconfinedTestDispatcher()) {
        val roomFlow = MutableStateFlow(room(sequence = 3, index = 2))
        val sessions = ReadingSessionStore().apply { select("room") }
        val coordinator = ReadingTogetherCoordinator(repository(roomFlow), sessions) { _, _ -> null }
        val applied = mutableListOf<Double?>()
        backgroundScope.launch { coordinator.runForeground() }
        backgroundScope.launch {
            coordinator.remoteMoves.collect { if (coordinator.consumeMove(it)) applied += it.stripPosition }
        }
        coordinator.setIncognito(false)
        coordinator.pageSelected(LocalReading(content, position(0), chapterId = 7))
        coordinator.confirmPageOrder()
        coordinator.setFollowing(true)

        roomFlow.value =
            room(sequence = 4, index = 2).let { it.copy(position = it.position!!.copy(stripPosition = 2.38)) }
        assertEquals(listOf(null, 2.38), applied)
    }

    @Test
    fun `long-strip scroll is quantized, survives page changes and clears outside long-strip`() =
        runTest(UnconfinedTestDispatcher()) {
            val coordinator =
                ReadingTogetherCoordinator(repository(MutableStateFlow(null)), ReadingSessionStore()) { _, _ ->
                    null
                }
            coordinator.pageSelected(LocalReading(content, position(4), chapterId = 7))
            coordinator.stripScrolled(chapterId = 7, value = 4.257)
            assertEquals(4.255, coordinator.state.value.local?.position?.stripPosition)
            coordinator.stripScrolled(chapterId = 8, value = 9.0) // another chapter's strip is ignored
            assertEquals(4.255, coordinator.state.value.local?.position?.stripPosition)

            coordinator.pageSelected(LocalReading(content, position(5), chapterId = 7))
            assertEquals(5, coordinator.state.value.local?.position?.pageIndex)
            assertEquals(4.255, coordinator.state.value.local?.position?.stripPosition)

            coordinator.stripEnded()
            assertNull(coordinator.state.value.local?.position?.stripPosition)
        }

    @Test
    fun `another title keeps the room for chat, the room's title offers one-tap follow`() =
        runTest(UnconfinedTestDispatcher()) {
            val roomFlow = MutableStateFlow(room(sequence = 3, index = 6))
            val sessions = ReadingSessionStore().apply { select("room") }
            val coordinator = ReadingTogetherCoordinator(repository(roomFlow), sessions) { _, _ -> null }
            val applied = mutableListOf<Int>()
            backgroundScope.launch { coordinator.runForeground() }
            backgroundScope.launch {
                coordinator.remoteMoves.collect { if (coordinator.consumeMove(it)) applied += it.pageIndex }
            }
            coordinator.setIncognito(false)
            val elsewhere = SocialContent("other", "Another title", "source", "other-work", "other-edition")
            coordinator.pageSelected(LocalReading(elsewhere, position(0), chapterId = 9))
            assertEquals(ReaderSocialStatus.ELSEWHERE, coordinator.state.value.status)
            assertEquals("room", coordinator.state.value.room?.id) // chat and reactions stay available
            assertFalse(coordinator.state.value.followOffer)

            coordinator.pageSelected(LocalReading(content, position(0), chapterId = 7))
            assertTrue(coordinator.state.value.followOffer)
            assertEquals(ReaderSocialStatus.ACTIVE, coordinator.state.value.status)

            coordinator.acceptFollow() // no separate page-order step
            assertTrue(coordinator.state.value.following)
            assertFalse(coordinator.state.value.followOffer)
            assertEquals(listOf(6), applied)
        }

    @Test
    fun `just chat withdraws the follow offer and page-order consent covers the whole edition`() =
        runTest(UnconfinedTestDispatcher()) {
            val roomFlow = MutableStateFlow(room(sequence = 3, index = 1))
            val sessions = ReadingSessionStore().apply { select("room") }
            val coordinator = ReadingTogetherCoordinator(repository(roomFlow), sessions) { _, _ -> null }
            backgroundScope.launch { coordinator.runForeground() }
            coordinator.setIncognito(false)
            coordinator.pageSelected(LocalReading(content, position(0), chapterId = 7))
            assertTrue(coordinator.state.value.followOffer)

            coordinator.setChatOnly(true)
            assertTrue(coordinator.state.value.chatOnly)
            assertFalse(coordinator.state.value.followOffer)

            coordinator.confirmPageOrder()
            val nextChapter = SocialPosition("chapter-2", "Chapter 2", 0, 20, "map-2")
            coordinator.pageSelected(LocalReading(content, nextChapter, chapterId = 8))
            assertTrue(coordinator.state.value.mappingConfirmed)
        }

    @Test
    fun `room that becomes unreadable ends as expired without a raw error`() = runTest(UnconfinedTestDispatcher()) {
        val revoke = CompletableDeferred<Unit>()
        val roomFlow = flow {
            emit(room(sequence = 1, index = 0))
            revoke.await()
            // What SocialRepository.watchRoom throws when rules deny the read (lease expired / access revoked).
            throw SocialRoomUnavailable()
        }
        val sessions = ReadingSessionStore().apply { select("room") }
        val coordinator = ReadingTogetherCoordinator(repository(roomFlow), sessions) { _, _ -> null }

        backgroundScope.launch { coordinator.runForeground() }
        coordinator.setIncognito(false)
        coordinator.pageSelected(LocalReading(content, position(0), chapterId = 7))
        assertEquals(ReaderSocialStatus.ACTIVE, coordinator.state.value.status)

        revoke.complete(Unit)

        assertEquals(ReaderSocialStatus.EXPIRED, coordinator.state.value.status)
        assertNull(coordinator.state.value.error)
        assertNull(sessions.selectedRoomId.value)
        assertNull(coordinator.state.value.selectedRoomId)
    }

    @Test
    fun `offline accepted edges pause the room but only a server removal ends it`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val accepted = MutableStateFlow<Set<String>?>(setOf("host"))
        val sessions = ReadingSessionStore().apply { select("room") }
        val coordinator =
            ReadingTogetherCoordinator(repository(MutableStateFlow(room(1, 0)), accepted), sessions) { _, _ -> null }

        backgroundScope.launch { coordinator.runForeground() }
        coordinator.setIncognito(false)
        coordinator.pageSelected(LocalReading(content, position(0), chapterId = 7))
        assertEquals(ReaderSocialStatus.ACTIVE, coordinator.state.value.status)

        accepted.value = null // cache-only snapshot while offline
        assertEquals(ReaderSocialStatus.WAITING, coordinator.state.value.status)
        assertEquals("room", sessions.selectedRoomId.value)

        accepted.value = setOf("host") // reconnected
        assertEquals(ReaderSocialStatus.ACTIVE, coordinator.state.value.status)

        accepted.value = emptySet() // server says the friendship is gone
        assertNull(sessions.selectedRoomId.value)
        assertEquals(ReaderSocialStatus.REVOKED, coordinator.state.value.status)
    }

    @Test
    fun `invisible mode pauses the room with its own status, incognito stays private`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = MutableStateFlow(SocialSharing(generation = 3, invisible = true))
            val repository = repository(MutableStateFlow(null), settings = settings)
            val coordinator = ReadingTogetherCoordinator(repository, ReadingSessionStore()) { _, _ -> null }
            backgroundScope.launch { coordinator.runForeground() }
            coordinator.setIncognito(false)
            coordinator.pageSelected(LocalReading(content, position(0), chapterId = 7))
            assertEquals(ReaderSocialStatus.INVISIBLE, coordinator.state.value.status)

            coordinator.setIncognito(true)
            assertEquals(ReaderSocialStatus.PRIVATE, coordinator.state.value.status)
        }

    @Test
    fun `finished chapters reach the feed only when shared, visible, not hidden and not incognito`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = MutableStateFlow(SocialSharing(generation = 3))
            val hidden = MutableStateFlow(emptySet<String>())
            val repository = repository(MutableStateFlow(null), settings = settings, hidden = hidden)
            val coordinator = ReadingTogetherCoordinator(repository, ReadingSessionStore()) { _, _ -> null }
            val reading = LocalReading(content, position(19), chapterId = 7)
            backgroundScope.launch { coordinator.runForeground() }
            coordinator.setIncognito(false)
            coordinator.pageSelected(reading)

            coordinator.publishChapterRead(reading) // feed private
            settings.value = SocialSharing(generation = 4, feed = "friends")
            coordinator.publishChapterRead(reading)
            coVerify(exactly = 1) { repository.publishFeed(4, SocialFeedItem.KIND_CHAPTER_READ, content, "Chapter 1") }

            hidden.value = setOf(content.contentKey)
            coordinator.publishChapterRead(reading)
            hidden.value = emptySet()
            settings.value = SocialSharing(generation = 5, feed = "friends", invisible = true)
            coordinator.publishChapterRead(reading)
            settings.value = SocialSharing(generation = 6, feed = "friends")
            coordinator.setIncognito(true)
            coordinator.publishChapterRead(reading)
            coVerify(exactly = 1) { repository.publishFeed(any(), any(), any(), any()) }
        }

    private fun friendsRepository(friends: List<String>) = repository(MutableStateFlow(null)).apply {
        every { watchFriends() } returns MutableStateFlow(friends.map { SocialProfile(it, it, it.uppercase()) })
        coEvery { roomAction("create", any(), any(), any(), any(), any()) } returns room(sequence = 0, index = 0)
            .copy(hostUid = "guest")
    }

    @Test
    fun `a pending read together starts the room with the first friend and invites the rest`() =
        runTest(UnconfinedTestDispatcher()) {
            val repository = friendsRepository(listOf("f1", "f2", "f3"))
            val sessions = ReadingSessionStore()
            sessions.setPending(
                PendingRoomInvite(content.contentKey, listOf("f1", "stranger", "f2"), System.currentTimeMillis()),
            )
            val coordinator = ReadingTogetherCoordinator(
                repository,
                sessions,
                describeSource = { SocialSource(it, "Src", "en", "pkg") },
            ) { _, _ -> null }
            backgroundScope.launch { coordinator.runForeground() }
            coordinator.setIncognito(false)
            coordinator.pageSelected(
                LocalReading(content, position(0), chapterId = 7, sourceId = 5, mangaUrl = "/title/1"),
            )

            coVerify(exactly = 1) {
                repository.roomAction(
                    "create",
                    null,
                    "f1",
                    sessions.writerSessionId,
                    match { it.source == SocialSource(5, "Src", "en", "pkg", url = "/title/1") },
                    position(0),
                )
            }
            coVerify(exactly = 1) { repository.inviteToRoom("room", "f2") }
            coVerify(exactly = 0) { repository.inviteToRoom("room", "stranger") }
            assertEquals("room", sessions.selectedRoomId.value)
            assertNull(sessions.pendingInvite.value)
            assertTrue(coordinator.state.value.mappingConfirmed)
        }

    @Test
    fun `a pending read together waits for its title and never starts on an incognito source`() =
        runTest(UnconfinedTestDispatcher()) {
            val repository = friendsRepository(listOf("f1"))
            val sessions = ReadingSessionStore()
            val pending = PendingRoomInvite(content.contentKey, listOf("f1"), System.currentTimeMillis())
            sessions.setPending(pending)
            val coordinator = ReadingTogetherCoordinator(repository, sessions) { _, _ -> null }
            backgroundScope.launch { coordinator.runForeground() }
            coordinator.setIncognito(false)
            val other = content.copy(contentKey = "other", editionKey = "other")
            coordinator.pageSelected(LocalReading(other, position(0), chapterId = 7))
            assertEquals(pending, sessions.pendingInvite.value)

            coordinator.setIncognito(true)
            coordinator.pageSelected(LocalReading(content, position(0), chapterId = 8))
            assertNull(sessions.pendingInvite.value)
            coVerify(exactly = 0) { repository.roomAction("create", any(), any(), any(), any(), any()) }
        }
}
