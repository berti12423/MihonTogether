package mihon.feature.translate

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File

/**
 * Manual check against a running Mihon Together Translator (phone port). Off by default; run with PC_TRANSLATOR_LIVE=1
 * and optionally PC_TRANSLATOR_ADDRESS (default 127.0.0.1:47824). With PC_TRANSLATOR_CODE (the console's pairing
 * code) and PC_TRANSLATOR_PAGE (an image file) it also pairs, translates the page and unpairs again.
 */
@EnabledIfEnvironmentVariable(named = "PC_TRANSLATOR_LIVE", matches = "1")
class PcTranslatorLiveTest {
    private val address = PcAddress.parse(System.getenv("PC_TRANSLATOR_ADDRESS") ?: "127.0.0.1")!!
    private val translator = PcTranslator(PcTranslator.lanClient(OkHttpClient()))

    @Test
    fun `health answers and an unknown token is told to pair again`() = runBlocking {
        val health = translator.health(address, "not-a-real-token")
        println("Translator: $health")
        assertEquals(false, health.paired)
        assertThrows<TranslationException.InvalidKey> {
            translator.translate(address, "not-a-real-token", byteArrayOf(1, 2, 3), "image/png", "en")
        }
        Unit
    }

    @Test
    fun `pairs, translates a page and unpairs`() = runBlocking {
        val code = System.getenv("PC_TRANSLATOR_CODE") ?: return@runBlocking
        val page = File(System.getenv("PC_TRANSLATOR_PAGE") ?: return@runBlocking)
        val paired = translator.pair(address, code, "PcTranslatorLiveTest") as PcPairResult.Paired
        try {
            assertEquals(true, translator.health(address, paired.token).paired)
            val bytes = page.readBytes()
            val started = System.currentTimeMillis()
            val png = translator.translate(address, paired.token, bytes, PcTranslator.contentType(bytes), "en")
            println("Translated ${page.name} on ${paired.name} in ${System.currentTimeMillis() - started} ms")
            File(page.parentFile, page.nameWithoutExtension + ".translated.png").writeBytes(png)
            assertEquals("image/png", PcTranslator.contentType(png))
        } finally {
            assertTrue(translator.unpair(address, paired.token))
        }
    }
}
