package mihon.feature.translate

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manual check against the real API (one free-tier request). Off by default; run with GEMINI_LIVE=1, a key in
 * tools/translate-spike/.env and a sliced page in tools/translate-spike/pages-sliced/ko/ (both git-ignored).
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_LIVE", matches = "1")
class GeminiLiveTest {
    @Test
    fun `real request returns placed blocks`() = runBlocking {
        val spike = File("../tools/translate-spike")
        val key = File(spike, ".env").readLines().first { it.startsWith("GEMINI_API_KEY=") }.substringAfter("=").trim()
        val image = File(spike, "pages-sliced/ko").listFiles()!!.filter { it.extension == "jpg" }.minBy { it.name }
        val client = OkHttpClient.Builder().readTimeout(90, TimeUnit.SECONDS).build()
        val engine = GeminiTranslationEngine(client, key, "gemini-3.1-flash-lite")

        val result = engine.translate(image.readBytes(), "image/jpeg", TranslationRequest("en", "ko"))
        result.blocks.forEach { println("${it.box} ${it.kind} | ${it.source} | ${it.translation}") }
        assertTrue(result.blocks.isNotEmpty())
        assertTrue(result.blocks.all { it.box.area > 0f })
    }
}
