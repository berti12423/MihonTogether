package mihon.feature.translate

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OcrTranslationEngineTest {
    private fun line(left: Int, top: Int, right: Int, bottom: Int, text: String) =
        OcrLine(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), text)

    @Test
    fun `stacked lines of one bubble join, a bubble further down stays separate`() {
        val groups = TextGrouping.group(
            listOf(
                line(110, 102, 290, 130, "어디 가는"),
                line(100, 70, 300, 98, "잠깐만,"),
                line(120, 134, 280, 162, "거야?"),
                line(400, 600, 600, 630, "몰라."),
            ),
            "ko",
        )
        assertEquals(2, groups.size)
        assertEquals("잠깐만, 어디 가는 거야?", groups[0].text)
        assertEquals(3, groups[0].lines.size)
        assertEquals(100f, groups[0].left)
        assertEquals(162f, groups[0].bottom)
        assertEquals("몰라.", groups[1].text)
    }

    @Test
    fun `vertical japanese columns join right to left without spaces`() {
        val groups = TextGrouping.group(
            listOf(
                line(200, 100, 230, 300, "どこへ"),
                line(240, 100, 270, 260, "待って"),
                line(600, 100, 630, 260, "別の"),
            ),
            "ja",
        )
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.vertical })
        assertEquals("別の", groups[0].text) // the right-hand bubble is read first
        assertEquals("待ってどこへ", groups[1].text)
    }

    @Test
    fun `busy pages sort without breaking, row by row`() {
        // 40 scattered bubbles mixing vertical and horizontal text: the order must be a consistent sort.
        val random = java.util.Random(7)
        val lines = (0 until 40).map { i ->
            val x = random.nextInt(900)
            val y = random.nextInt(3000)
            if (i % 2 == 0) line(x, y, x + 30, y + 120, "縦書き") else line(x, y, x + 150, y + 30, "가로 쓰기")
        }
        val groups = TextGrouping.group(lines, "ja")
        assertTrue(groups.isNotEmpty())
        val rows = groups.map { it.top }
        assertTrue(rows.zipWithNext().all { (a, b) -> b >= a - 200 }) // never jumps back more than a band
    }

    @Test
    fun `script counting tells the languages apart`() {
        assertEquals(3, Scripts.count("안녕하 OK", "ko"))
        assertEquals(0, Scripts.count("www.scans.com", "ko"))
        assertEquals(4, Scripts.count("待ってね", "ja"))
        assertEquals(3, Scripts.kana("待ってね"))
        assertEquals(2, Scripts.count("你好", "zh"))
    }

    private class Finder(val results: Map<String, OcrResult>, val delayMs: Long = 0) : TextFinder {
        val calls = mutableListOf<String>()
        override suspend fun find(image: ByteArray, language: String): OcrResult {
            calls += language
            if (delayMs > 0) delay(delayMs)
            return results[language] ?: OcrResult(1000, 2000, emptyList())
        }
    }

    private class Upper : TextTranslator {
        override val id = "upper"
        val seen = mutableListOf<Pair<List<String>, String>>()
        override suspend fun translate(
            texts: List<String>,
            source: String,
            target: String,
            context: List<String>,
        ): List<String> {
            seen += texts to source
            return texts.map { "EN($it)" }
        }
    }

    @Test
    fun `detects korean once, drops watermarks, and normalizes boxes`() = runTest {
        val finder = Finder(
            mapOf(
                "ko" to OcrResult(
                    1000,
                    2000,
                    listOf(line(100, 200, 300, 240, "안녕"), line(10, 1900, 400, 1940, "www.scans.com")),
                ),
                "ja" to OcrResult(1000, 2000, listOf(line(100, 200, 300, 240, "口"))),
            ),
        )
        val translator = Upper()
        val engine = OcrTranslationEngine(finder, translator)
        val result = engine.translate(ByteArray(1), "image/jpeg", TranslationRequest("en", null))
        assertEquals("ko", result.sourceLanguage)
        assertEquals(listOf("EN(안녕)"), result.blocks.map { it.translation })
        assertEquals(NormBox(0.1f, 0.1f, 0.12f, 0.3f), result.blocks.single().box)
        assertEquals(listOf("ko", "ja"), finder.calls)

        engine.translate(ByteArray(1), "image/jpeg", TranslationRequest("en", null))
        assertEquals(listOf("ko", "ja", "ko"), finder.calls) // remembered: one recognizer per page now
    }

    @Test
    fun `parallel slices of the first page run language detection only once`() = runTest {
        val finder = Finder(
            mapOf("ko" to OcrResult(1000, 2000, listOf(line(100, 200, 300, 240, "안녕")))),
            delayMs = 10,
        )
        val engine = OcrTranslationEngine(finder, Upper())
        assertEquals(ImageFormat.OCR, engine.imageFormat)
        val results = coroutineScope {
            List(3) {
                async { engine.translate(ByteArray(1), "image/jpeg", TranslationRequest("en", null)) }
            }.map { it.await() }
        }
        assertEquals(listOf("ko", "ko", "ko"), results.map { it.sourceLanguage })
        assertEquals(listOf("ko", "ja", "ko", "ko"), finder.calls) // one detection, then one recognizer per slice
    }

    @Test
    fun `japanese-script text without kana is chinese, an explicit language skips detection`() = runTest {
        val han = OcrResult(1000, 1000, listOf(line(100, 100, 300, 140, "你好吗")))
        val finder = Finder(mapOf("ja" to han, "zh" to han))
        val translator = Upper()
        val result = OcrTranslationEngine(
            finder,
            translator,
        ).translate(ByteArray(1), "x", TranslationRequest("en", null))
        assertEquals("zh", result.sourceLanguage)
        assertEquals("zh", translator.seen.single().second)

        val explicit = Finder(mapOf("ja" to han))
        OcrTranslationEngine(explicit, Upper()).translate(ByteArray(1), "x", TranslationRequest("en", "ja"))
        assertEquals(listOf("ja"), explicit.calls)
    }

    @Test
    fun `an empty page needs no translation request`() = runTest {
        val translator = Upper()
        val result = OcrTranslationEngine(Finder(emptyMap()), translator)
            .translate(ByteArray(1), "x", TranslationRequest("en", "ko"))
        assertTrue(result.blocks.isEmpty())
        assertTrue(translator.seen.isEmpty())
    }

    @Test
    fun `deepl uses the free host for free keys and maps its errors`() {
        assertEquals("https://api-free.deepl.com/v2/translate", DeepLTranslator.endpoint("abc:fx"))
        assertEquals("https://api.deepl.com/v2/translate", DeepLTranslator.endpoint("abc"))
        val body = DeepLTranslator.body(listOf("안녕"), "ko", "en", listOf("Hi there")).toString()
        assertTrue("\"source_lang\":\"KO\"" in body && "\"target_lang\":\"EN-US\"" in body && "Hi there" in body)
        assertFalse("context" in DeepLTranslator.body(listOf("a"), "ko", "en", emptyList()).toString())
        assertEquals(
            listOf("Hello", "Bye"),
            DeepLTranslator.parse("""{"translations":[{"text":"Hello"},{"text":"Bye"}]}""", 2),
        )
        assertThrows(TranslationException.Failed::class.java) {
            DeepLTranslator.parse("""{"translations":[]}""", 1)
        }
        assertTrue(DeepLTranslator.error(456, "") is TranslationException.QuotaExceeded)
        assertTrue(DeepLTranslator.error(403, """{"message":"Wrong key"}""") is TranslationException.InvalidKey)
    }

    @Test
    fun `papago uses zh-CN and reads its reply`() {
        assertTrue("\"source\":\"zh-CN\"" in PapagoTranslator.body("你好", "zh", "en").toString())
        assertEquals(
            "Hello",
            PapagoTranslator.parse("""{"message":{"result":{"srcLangType":"ko","translatedText":"Hello"}}}"""),
        )
        assertTrue(PapagoTranslator.error(401, """{"error":{"message":"bad"}}""") is TranslationException.InvalidKey)
    }

    @Test
    fun `only engines with their key are ready`() {
        val base = TranslationSettings("", "m", null, "en", translateSfx = false)
        assertTrue(base.ready) // on-device needs nothing
        assertFalse(base.copy(engine = TranslationPreferences.ENGINE_GEMINI).ready)
        assertFalse(base.copy(engine = TranslationPreferences.ENGINE_DEEPL).ready)
        assertTrue(base.copy(engine = TranslationPreferences.ENGINE_DEEPL, deeplKey = "k:fx").ready)
        assertFalse(base.copy(engine = TranslationPreferences.ENGINE_PAPAGO, papagoId = "id").ready)
    }
}
