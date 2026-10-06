package mihon.feature.social

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class StringsDuplicatesTest {
    @Test
    fun `base strings have no duplicate names`() {
        // Parallel branches each added strings; a duplicate name silently shows one text in both places.
        val path = "i18n/src/commonMain/moko-resources/base/strings.xml"
        val file = listOf(File("../$path"), File(path)).first { it.exists() }
        val names = Regex("""<string name="([^"]+)"""").findAll(file.readText()).map { it.groupValues[1] }.toList()
        assertEquals(emptyList<String>(), names.groupBy { it }.filterValues { it.size > 1 }.keys.toList())
    }
}
