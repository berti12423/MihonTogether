package mihon.feature.translate

import android.graphics.BitmapFactory
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** On-device OCR with ML Kit's bundled Korean, Japanese and Chinese models: free, offline, no limits. */
class MlKitTextFinder : TextFinder {
    private val recognizers = mutableMapOf<String, TextRecognizer>()

    private fun recognizer(language: String): TextRecognizer = synchronized(recognizers) {
        recognizers.getOrPut(language) {
            TextRecognition.getClient(
                when (language) {
                    "ja" -> JapaneseTextRecognizerOptions.Builder().build()
                    "zh" -> ChineseTextRecognizerOptions.Builder().build()
                    else -> KoreanTextRecognizerOptions.Builder().build()
                },
            )
        }
    }

    override suspend fun find(image: ByteArray, language: String): OcrResult {
        val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size)
            ?: throw TranslationException.Failed("Page image couldn't be decoded")
        // Not recycled by hand: a cancelled caller returns while ML Kit may still be reading the bitmap.
        val text = try {
            recognizer(language).process(InputImage.fromBitmap(bitmap, 0)).await()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw TranslationException.Failed("Text recognition failed: ${error.message}", error)
        }
        val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            OcrLine(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(), line.text)
        }
        return OcrResult(bitmap.width, bitmap.height, lines)
    }

    fun close() = synchronized(recognizers) {
        recognizers.values.forEach { it.close() }
        recognizers.clear()
    }
}

/**
 * ML Kit's on-device translator. Each language pair needs a one-time download of about 30 MB from Google; after that
 * it works offline. Quality is below the cloud services, but there are no keys and no limits. While
 * [downloadAllowed] is false (mobile data, not allowed in settings) a missing pack fails fast with
 * [TranslationException.NeedsWifi] instead of waiting for Wi-Fi.
 */
class MlKitTextTranslator(private val downloadAllowed: () -> Boolean = { true }) : TextTranslator {
    override val id = "mlkit"
    private val clients = mutableMapOf<Pair<String, String>, Translator>()
    private val ready = mutableSetOf<Pair<String, String>>()

    private fun client(source: String, target: String): Translator = synchronized(clients) {
        clients.getOrPut(source to target) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.fromLanguageTag(source) ?: TranslateLanguage.KOREAN)
                    .setTargetLanguage(TranslateLanguage.fromLanguageTag(target) ?: TranslateLanguage.ENGLISH)
                    .build(),
            )
        }
    }

    override suspend fun translate(
        texts: List<String>,
        source: String,
        target: String,
        context: List<String>,
    ): List<String> {
        val client = client(source, target)
        if ((source to target) !in synchronized(ready) { ready }) {
            if (!downloadAllowed() && !(LanguagePacks.isDownloaded(source) && LanguagePacks.isDownloaded(target))) {
                throw TranslationException.NeedsWifi(
                    "The ${GeminiTranslationEngine.languageName(source)} pack needs Wi-Fi",
                )
            }
            try {
                client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw TranslationException.Unavailable(
                    "Couldn't download the ${GeminiTranslationEngine.languageName(
                        source,
                    )} language pack: ${error.message}",
                )
            }
            synchronized(ready) { ready += source to target }
        }
        return texts.map { text ->
            try {
                client.translate(text).await()
            } catch (error: CancellationException) {
                throw error
            } catch (error: MlKitException) {
                throw TranslationException.Failed("Translation failed: ${error.message}", error)
            }
        }
    }

    override fun close() = synchronized(clients) {
        clients.values.forEach { it.close() }
        clients.clear()
    }
}

/** ML Kit translation models on this phone: checked before translating on mobile data, downloaded at first run. */
object LanguagePacks {
    /** The raw languages the reader translates from. */
    val SOURCES = listOf("ko", "ja", "zh")

    private fun model(language: String): TranslateRemoteModel? =
        TranslateLanguage.fromLanguageTag(language)?.let { TranslateRemoteModel.Builder(it).build() }

    suspend fun isDownloaded(language: String): Boolean {
        val model = model(language) ?: return false
        return try {
            RemoteModelManager.getInstance().isModelDownloaded(model).await()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Downloads the packs for [SOURCES] and [target] on any network (the reader asked for it), reporting
     * (finished, total) before each pack and at the end. Throws if a download fails.
     */
    suspend fun download(target: String = "en", onProgress: (Int, Int) -> Unit) {
        val models = (SOURCES + target).distinct().mapNotNull(::model)
        val manager = RemoteModelManager.getInstance()
        models.forEachIndexed { index, model ->
            onProgress(index, models.size)
            manager.download(model, DownloadConditions.Builder().build()).await()
        }
        onProgress(models.size, models.size)
    }
}
