package mihon.feature.weblibrary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.zacsweers.metro.Inject
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import mihon.feature.social.SocialFeedItem
import mihon.feature.social.SocialIdentity
import mihon.feature.social.SocialRepository
import mihon.feature.social.reader.ReaderReadingIdentity
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * [WebLibraryBackend] over the app's own library: the same interactors, caches and loaders as the library screen and
 * the reader (ChapterLoader picks the downloaded folder/CBZ, the local source or the online source with the chapter
 * cache and the 503 retry).
 */
@Inject
class WebLibraryAppBackend(
    private val context: Context,
    private val getLibraryManga: GetLibraryManga,
    private val getCategories: GetCategories,
    private val getManga: GetManga,
    private val getChapter: GetChapter,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateChapter: UpdateChapter,
    private val upsertHistory: UpsertHistory,
    private val getIncognitoState: GetIncognitoState,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val chapterCache: ChapterCache,
    private val coverCache: CoverCache,
    private val readerPreferences: ReaderPreferences,
    private val trackPreferences: TrackPreferences,
    private val trackChapter: TrackChapter,
    private val socialRepository: SocialRepository,
) : WebLibraryBackend {
    /** Last progress time per chapter, to add the reading time between the web's progress reports to history. */
    private val lastProgress = ConcurrentHashMap<Long, Long>()

    override suspend fun librarySize(): Int = getLibraryManga.await().size

    override suspend fun library(): WebLibrary {
        val library = getLibraryManga.await()
        val categories = getCategories.await().map { category ->
            val name = if (category.isSystemCategory) {
                context.stringResource(
                    MR.strings.label_default,
                )
            } else {
                category.name
            }
            WebCategory(category.id, name, category.order)
        }
        val manga = library.map { item ->
            val manga = item.manga
            WebManga(
                id = manga.id,
                title = manga.title,
                author = manga.author,
                sourceId = manga.source,
                sourceName = sourceManager.getOrStub(manga.source).name,
                unread = item.unreadCount,
                downloaded = downloadManager.getDownloadCount(manga),
                categoryIds = item.categories,
                coverVersion = manga.coverLastModified,
                lastRead = item.lastRead.takeIf { it > 0 },
                dateAdded = manga.favoriteAt ?: 0,
            )
        }
        return WebLibrary(categories, manga)
    }

    override suspend fun cover(mangaId: Long, maxWidth: Int): ByteArray? = withIOContext {
        val manga = libraryManga(mangaId) ?: return@withIOContext null
        val file = coverFile(manga) ?: return@withIOContext null
        resizeJpeg(file, maxWidth)
    }

    /** The custom cover, the cached cover, or the cover downloaded through the source (and cached like the app). */
    private suspend fun coverFile(manga: Manga): File? {
        coverCache.getCustomCoverFile(manga.id).takeIf { it.isFile }?.let { return it }
        val url = manga.thumbnailUrl ?: return null
        val cached = coverCache.getCoverFile(url) ?: return null
        if (cached.isFile && cached.length() > 0) return cached
        val source = sourceManager.get(manga.source) as? HttpSource ?: return null
        if (!url.startsWith("http")) return null
        return try {
            source.client.newCall(GET(url, source.headers)).awaitSuccess().use { response ->
                val temp = File(cached.path + ".web")
                cached.parentFile?.mkdirs()
                temp.outputStream().use { output -> response.body.byteStream().copyTo(output) }
                if (!temp.renameTo(cached)) temp.delete()
            }
            cached.takeIf { it.isFile }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Web library cover not loaded" }
            null
        }
    }

    override suspend fun details(mangaId: Long): WebMangaDetails? {
        val manga = libraryManga(mangaId) ?: return null
        val source = sourceManager.getOrStub(manga.source)
        val url = (source as? HttpSource)?.let { http ->
            try {
                http.getMangaUrl(manga.toSManga())
            } catch (_: Exception) {
                null
            }
        }
        val chapters = getChaptersByMangaId.await(manga.id).sortedBy { it.sourceOrder }.map { chapter ->
            WebChapter(
                id = chapter.id,
                name = chapter.name,
                number = chapter.chapterNumber,
                scanlator = chapter.scanlator,
                read = chapter.read,
                bookmark = chapter.bookmark,
                lastPageRead = chapter.lastPageRead,
                downloaded = downloadManager.isChapterDownloaded(
                    chapter.name,
                    chapter.scanlator,
                    chapter.url,
                    manga.title,
                    manga.source,
                ),
                dateUpload = chapter.dateUpload,
            )
        }
        return WebMangaDetails(
            id = manga.id,
            title = manga.title,
            author = manga.author,
            artist = manga.artist,
            description = manga.description,
            status = WebLibraryWire.status(manga.status),
            genres = manga.genre.orEmpty(),
            sourceName = source.name,
            url = url,
            readingMode = WebLibraryWire.readingMode(readingModeFlag(manga)),
            chapters = chapters,
        )
    }

    /** The title's reader mode, or the app default when it has none (as the reader resolves it). */
    private fun readingModeFlag(manga: Manga): Int {
        val mode = ReadingMode.fromPreference(manga.readingMode.toInt())
        return if (mode == ReadingMode.DEFAULT) readerPreferences.defaultReadingMode.get() else mode.flagValue
    }

    override suspend fun openChapter(chapterId: Long): WebOpenedChapter? {
        val chapter = getChapter.await(chapterId) ?: return null
        val manga = libraryManga(chapter.mangaId) ?: return null
        val source = sourceManager.getOrStub(manga.source)
        val readerChapter = ReaderChapter(chapter)
        val loader = ChapterLoader(context, downloadManager, downloadProvider, chapterCache, manga, source)
        try {
            loader.loadChapter(readerChapter)
        } catch (error: CancellationException) {
            readerChapter.pageLoader?.recycle()
            throw error
        } catch (error: Exception) {
            readerChapter.pageLoader?.recycle()
            throw WebLibraryException(WebLibraryException.SOURCE_ERROR, error.message ?: error.javaClass.simpleName)
        }
        val pageLoader = checkNotNull(readerChapter.pageLoader)
        return OpenedChapter(pageLoader, readerChapter.pages.orEmpty())
    }

    private inner class OpenedChapter(
        private val loader: PageLoader,
        private val pages: List<ReaderPage>,
    ) : WebOpenedChapter {
        override val pageCount: Int = pages.size
        override val downloaded: Boolean = loader.isLocal

        override suspend fun image(index: Int): ByteArray = withIOContext {
            val page = pages[index]
            if (!loader.isLocal) {
                // An online page that was evicted from the chapter cache, or failed earlier, loads again.
                val url = page.imageUrl
                if (page.status == Page.State.Ready && (url == null || !chapterCache.isImageInCache(url))) {
                    page.status = Page.State.Queue
                }
                if (page.status is Page.State.Error) page.status = Page.State.Queue
            }
            if (page.status != Page.State.Ready || page.stream == null) {
                coroutineScope {
                    // The reader's own path: the loader's queue, the source's client, the 503 retry, the cache.
                    val load = launch { loader.loadPage(page) }
                    val state = page.statusFlow.first { it == Page.State.Ready || it is Page.State.Error }
                    load.cancel()
                    if (state is Page.State.Error) {
                        throw WebLibraryException(
                            WebLibraryException.SOURCE_ERROR,
                            state.error.message ?: state.error.javaClass.simpleName,
                        )
                    }
                }
            }
            val stream = page.stream
                ?: throw WebLibraryException(WebLibraryException.SOURCE_ERROR, "Page $index has no image")
            stream().use { it.readBytes() }
        }

        override fun close() {
            loader.recycle()
        }
    }

    override suspend fun progressTarget(chapterId: Long): WebProgressTarget? {
        val chapter = getChapter.await(chapterId) ?: return null
        val manga = libraryManga(chapter.mangaId) ?: return null
        return WebProgressTarget(read = chapter.read, incognito = getIncognitoState.await(manga.source))
    }

    override suspend fun saveProgress(chapterId: Long, lastPageRead: Int, read: Boolean) {
        updateChapter.await(
            ChapterUpdate(chapterId) {
                this.read = read
                this.lastPageRead = lastPageRead.toLong()
            },
        )
        val now = System.currentTimeMillis()
        // Reading time between two reports (the web sends one every few seconds while reading); long gaps don't count.
        val duration = lastProgress.put(chapterId, now)?.let { now - it }?.takeIf { it in 0..MAX_GAP_MS } ?: 0
        upsertHistory.await(HistoryUpdate(chapterId, Date(now), duration))
    }

    override suspend fun chapterFinished(chapterId: Long, pageCount: Int?) {
        val chapter = getChapter.await(chapterId) ?: return
        val manga = libraryManga(chapter.mangaId) ?: return
        if (getIncognitoState.await(manga.source)) return
        if (trackPreferences.autoUpdateTrack.get()) {
            bestEffort("tracking") { trackChapter.await(context, manga.id, chapter.chapterNumber) }
        }
        bestEffort("social") { recordSocial(manga, chapter, pageCount) }
    }

    /** As the reader's ReadingTogetherCoordinator.publishChapterRead: feed under consent, stats and XP. */
    private suspend fun recordSocial(manga: Manga, chapter: Chapter, pageCount: Int?) {
        val uid = socialRepository.currentUid ?: return
        val content = SocialIdentity.content(manga) ?: return
        val settings = withTimeout(SOCIAL_TIMEOUT) { socialRepository.getSelf().settings }
        if (settings.invisible || socialRepository.currentUid != uid) return
        val hidden = withTimeout(SOCIAL_TIMEOUT) { socialRepository.watchHidden(uid).first() }
        val chapterKey = ReaderReadingIdentity.chapterKey(content, chapter.url, chapter.scanlator)
        if (settings.feed == "friends" && chapterKey != null && content.contentKey !in hidden) {
            bestEffort("feed") {
                socialRepository.publishFeed(
                    settings.generation,
                    SocialFeedItem.KIND_CHAPTER_READ,
                    content,
                    chapter.name.take(100),
                )
            }
        }
        val pages = (pageCount ?: (chapter.lastPageRead.toInt() + 1)).coerceIn(1, 2000)
        socialRepository.recordChapter(settings.generation, pages, null)
    }

    private suspend fun libraryManga(mangaId: Long): Manga? = getManga.await(mangaId)?.takeIf { it.favorite }

    private suspend fun bestEffort(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Web library $what not recorded" }
        }
    }

    private companion object {
        const val MAX_GAP_MS = 60_000L
        val SOCIAL_TIMEOUT = 15.seconds

        /** A JPEG no wider than [maxWidth] (downscaled by power-of-two sampling, then scaled exactly). */
        fun resizeJpeg(file: File, maxWidth: Int): ByteArray? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
            val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val scaled = if (decoded.width > maxWidth) {
                val height = (decoded.height * maxWidth.toFloat() / decoded.width).roundToInt().coerceIn(
                    1,
                    maxWidth * 4,
                )
                Bitmap.createScaledBitmap(decoded, maxWidth, height, true).also {
                    if (it !==
                        decoded
                    ) {
                        decoded.recycle()
                    }
                }
            } else {
                decoded
            }
            return try {
                ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
            } finally {
                scaled.recycle()
            }
        }
    }
}
