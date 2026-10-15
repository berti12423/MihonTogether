package mihon.feature.translate

import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Translations live only on this device. A page's [PageTranslation] JSON is keyed by its image bytes plus everything
 * that changes the engine's answer, so re-opening a chapter costs nothing. Rendered pages are keyed additionally by the
 * render settings. The oldest files are dropped past [maxBytes].
 */
class TranslationCache(
    private val dir: File,
    private val maxBytes: Long = 300L * 1024 * 1024,
    private val trimEvery: Int = 10,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var writes = 0

    fun key(image: ByteArray, engineId: String, request: TranslationRequest): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(image)
        digest.update("|$engineId|${request.targetLanguage}|${request.sourceLanguage.orEmpty()}".toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** For engines that return a finished page: only the image, the engine and the target language change it. */
    fun pageKey(image: ByteArray, engineId: String, targetLanguage: String): String =
        key(image, engineId, TranslationRequest(targetLanguage = targetLanguage, sourceLanguage = null))

    fun read(key: String): PageTranslation? {
        val file = File(dir, "$key.json").takeIf { it.isFile } ?: return null
        return try {
            json.decodeFromString<PageTranslation>(file.readText()).also {
                file.setLastModified(System.currentTimeMillis())
            }
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    fun write(key: String, translation: PageTranslation) {
        dir.mkdirs()
        atomicWrite(File(dir, "$key.json")) {
            it.writeText(json.encodeToString(PageTranslation.serializer(), translation))
        }
    }

    /** Where the page rendered with [variant] settings goes; it may not exist yet. */
    fun renderedFile(key: String, variant: String, extension: String = "jpg"): File =
        File(dir, "$key-$variant.$extension")

    /** Writes through a temporary file, so a crash never leaves a half-written page behind. */
    fun atomicWrite(target: File, write: (File) -> Unit) {
        dir.mkdirs()
        val temp = File(dir, "${target.name}.tmp")
        write(temp)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    /** Marks [file] as just used, so [trim] keeps it (rendered pages are reused without being rewritten). */
    fun touch(file: File) {
        file.setLastModified(System.currentTimeMillis())
    }

    /** Call after each rendered page: trims on the first and then every [trimEvery]th write, not on every page. */
    fun afterWrite() {
        val due = synchronized(this) { writes++ % trimEvery == 0 }
        if (due) trim()
    }

    /** Drops least recently used files until the cache fits in [maxBytes]. Files still being written are kept. */
    fun trim() {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }?.sortedBy { it.lastModified() }
            ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
        }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}
