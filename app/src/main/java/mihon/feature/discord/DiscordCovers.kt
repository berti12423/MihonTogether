package mihon.feature.discord

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/**
 * Discord downloads the status picture itself, and many sources refuse Discord's servers (Comix shows nothing). So
 * the cover the app already loaded is uploaded once (see SocialRepository.publishDiscordCover) and Discord gets that
 * link. Remembers the links for this app session.
 */
object DiscordCovers {
    private val links = mutableMapOf<String, String>()
    private val lock = Mutex()

    /**
     * The Storage object name for a cover: 32 hex characters of its link's SHA-256, so each cover gets its own address
     * and the download link stays under Discord's 256 characters.
     */
    fun name(coverUrl: String): String = MessageDigest.getInstance("SHA-256").digest(coverUrl.toByteArray())
        .take(16).joinToString("") { "%02x".format(it) }

    /** The hosted link for [coverUrl], uploading through [upload] the first time; null when that fails. */
    suspend fun link(coverUrl: String, upload: suspend (name: String) -> String?): String? = lock.withLock {
        links[coverUrl] ?: upload(name(coverUrl))?.takeIf { it.length <= PresenceRules.MAX_URL }?.also {
            links[coverUrl] = it
        }
    }
}
