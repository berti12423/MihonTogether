package mihon.feature.social

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class SocialCallableNamesTest {
    @Test
    fun `emulator callable allow-list matches the backend callables`() {
        // A callable missing here fails silently on emulator builds (found with socialPublishFeed).
        val domain = listOf(File("../backend/src/domain.ts"), File("backend/src/domain.ts")).first { it.exists() }
        val backend = listOf("CALLABLES", "ACCOUNT_CALLABLES").flatMap { name ->
            val declaration = Regex("""export const $name = \[(.*?)] as const""", RegexOption.DOT_MATCHES_ALL)
                .find(domain.readText())!!.groupValues[1]
            Regex(""""(social\w+)"""").findAll(declaration).map { it.groupValues[1] }.toList()
        }.toSet()
        assertEquals(backend, SOCIAL_CALLABLE_NAMES)
    }

    @Test
    fun `reaction allow-list matches the backend`() {
        val domain = listOf(File("../backend/src/domain.ts"), File("backend/src/domain.ts")).first { it.exists() }
        val declaration = Regex("""ROOM_REACTIONS = \[(.*?)]""").find(domain.readText())!!.groupValues[1]
        val backend = Regex(""""([^"]+)"""").findAll(declaration).map { it.groupValues[1] }.toList()
        assertEquals(backend, RoomMessage.REACTIONS)
    }
}
