package mihon.feature.translate

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * The reader's translation mode. While on, the selected page and the next [PREFETCH] pages are translated one at a time
 * (slices in parallel inside [PageTranslator]); the free on-device engine then goes on through the rest of the chapter.
 * The viewers show [fileFor] instead of the original when it exists. Pages that haven't loaded yet don't hold up the
 * queue: ready pages go first, and pages nobody is loading are dropped after a short wait (queued again on the next
 * page turn). Nothing is retried automatically: on free cloud tiers retries only burn the quota. A used-up quota or a
 * bad key stops the queue until translation is switched on again.
 */
class ReaderTranslation(
    private val scope: CoroutineScope,
    private val settings: () -> TranslationSettings,
    private val translator: PageTranslator,
    private val engineFor: (TranslationSettings) -> TranslationEngine,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** The open title's glossary, applied when pages are drawn. */
    private val glossary: () -> Glossary = { Glossary.EMPTY },
) {
    enum class Problem { NO_KEY, INVALID_KEY, QUOTA, NEEDS_WIFI, UNAVAILABLE, FAILED }

    data class State(
        val enabled: Boolean = false,
        val working: Boolean = false,
        val problem: Problem? = null,
        val detail: String? = null,
        /** A used-up quota or a rejected key stopped the queue: page turns queue nothing until switched on again. */
        val halted: Boolean = false,
        /** Whole-chapter progress (on-device engine): pages of the current chapter translated, out of [total]. */
        val done: Int = 0,
        /** Pages in the current chapter while the whole chapter is queued, else 0. */
        val total: Int = 0,
    )

    private data class Key(val chapterId: Long, val index: Int)

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val files = MutableStateFlow<Map<Key, File>>(emptyMap())
    private val results = mutableMapOf<Key, PageTranslator.Result>()
    private val skipped = mutableSetOf<Key>()
    private val pending = ArrayDeque<ReaderPage>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val context = ArrayDeque<String>()
    private var worker: Job? = null

    /** Bumped on every new selection, so the wait for unloaded pages starts over. */
    @Volatile
    private var generation = 0

    /** The chapter whose progress [State.done] counts, and its page count, while the whole chapter is queued. */
    @Volatile
    private var progressChapter: Pair<Long, Int>? = null

    /** The translated page to show instead of [page]'s original, if translation is on and it's ready. */
    fun fileFor(page: ReaderPage): File? =
        if (state.value.enabled) key(page)?.let(::existing) else null

    /** Emits whenever [fileFor] for [page] changes: it got translated, or translation was switched on or off. */
    fun fileFlow(page: ReaderPage): Flow<File?> {
        val key = key(page)
        return combine(state, files) { state, files ->
            if (state.enabled && key != null) files[key]?.takeIf { it.isFile } else null
        }
            .distinctUntilChanged()
    }

    /** Switches translation on or off. Turning it on without the engine's key reports [Problem.NO_KEY] instead. */
    fun setEnabled(enabled: Boolean, current: ReaderPage? = null) {
        if (!enabled) {
            worker?.cancel()
            worker = null
            synchronized(pending) { pending.clear() }
            mutableState.value = State(enabled = false)
            return
        }
        if (!settings().ready) {
            mutableState.value = State(enabled = false, problem = Problem.NO_KEY)
            return
        }
        synchronized(skipped) { skipped.clear() }
        mutableState.value = State(enabled = true) // also clears [State.halted]
        if (worker?.isActive != true) worker = scope.launch(io) { work() }
        current?.let(::onPageSelected)
    }

    /** Queues [page] and the next pages of its chapter, replacing whatever was queued for an earlier position. */
    fun onPageSelected(page: ReaderPage) {
        if (!state.value.enabled || state.value.halted) return
        val target = (page as? InsertPage)?.parent ?: page
        val chapterPages = target.chapter.pages.orEmpty()
        val next = chapterPages.drop(target.index + 1)
        val wholeChapter = settings().engine == TranslationPreferences.ENGINE_ON_DEVICE
        val queue = buildList {
            add(target)
            addAll(next.take(PREFETCH))
            // Free and unlimited: the rest of the chapter, ahead first, then the pages already passed.
            if (wholeChapter) {
                addAll(next.drop(PREFETCH))
                addAll(chapterPages.take(target.index))
            }
        }
        val wanted = queue.filter { candidate ->
            key(candidate)?.let { existing(it) == null && it !in synchronized(skipped) { skipped } } == true
        }
        progressChapter = key(target)?.chapterId?.takeIf { wholeChapter && chapterPages.isNotEmpty() }
            ?.let { it to chapterPages.size }
        updateProgress()
        synchronized(pending) {
            pending.clear()
            pending.addAll(wanted)
            generation++
        }
        wake.trySend(Unit)
    }

    private fun updateProgress() {
        val chapter = progressChapter
        val done = chapter?.let { (id, _) -> files.value.count { (key, file) -> key.chapterId == id && file.isFile } }
        mutableState.update { it.copy(done = done ?: 0, total = chapter?.second ?: 0) }
    }

    /** What was found and translated on [page], for "Edit page translation"; null until it's translated. */
    fun translationFor(page: ReaderPage): PageTranslation? =
        if (state.value.enabled) key(page)?.let { synchronized(results) { results[it] }?.translation } else null

    /**
     * Saves the reader's edited [blocks] for [page] into the cached translation and draws the page again; the viewer
     * then shows the new drawing. Returns false if the page isn't translated or its image can't be read.
     */
    suspend fun saveEdits(page: ReaderPage, blocks: List<TranslatedBlock>): Boolean = withContext(io) {
        val key = key(page) ?: return@withContext false
        val previous = synchronized(results) { results[key] } ?: return@withContext false
        val image = readImage(page) ?: return@withContext false
        val result = translator.saveEdits(
            image,
            previous.cacheKey,
            previous.translation.copy(blocks = blocks),
            settings().translateSfx,
            glossary(),
        )
        synchronized(results) { results[key] = result }
        files.update { it + (key to result.rendered) }
        true
    }

    /** Draws the pages again from their cached translations (e.g. after the glossary changed), no new requests. */
    fun redraw(current: ReaderPage?) {
        files.value = emptyMap()
        synchronized(skipped) { skipped.clear() }
        current?.let(::onPageSelected)
    }

    /** Drops cached translations, e.g. after changing the model or language. */
    fun forgetPages() {
        files.value = emptyMap()
        synchronized(results) { results.clear() }
        synchronized(skipped) { skipped.clear() }
        synchronized(context) { context.clear() }
    }

    private suspend fun work() {
        for (signal in wake) {
            var waitedMs = 0L
            var seen = generation
            while (true) {
                if (seen != generation) {
                    seen = generation
                    waitedMs = 0L
                }
                val page = takeReady()
                if (page == null) {
                    // Nothing ready: wait while someone loads a queued page, give up soon on pages nobody loads.
                    val waiting = synchronized(pending) {
                        pending.removeAll { it.status is Page.State.Error }
                        pending.toList()
                    }
                    if (waiting.isEmpty()) break
                    val loading = waiting.any {
                        it.status == Page.State.LoadPage || it.status == Page.State.DownloadImage
                    }
                    if (waitedMs >= READY_TIMEOUT_MS || (!loading && waitedMs >= QUEUE_GRACE_MS)) {
                        synchronized(pending) { if (seen == generation) pending.clear() }
                        if (seen == generation) break else continue
                    }
                    delay(POLL_MS)
                    waitedMs += POLL_MS
                    continue
                }
                waitedMs = 0L
                val key = key(page) ?: continue
                if (existing(key) != null) continue
                if (key in files.value) files.update { it - key } // trimmed: forget it, so the redraw is announced
                if (!translate(page, key)) {
                    synchronized(pending) { pending.clear() }
                    break
                }
            }
        }
    }

    /** Removes and returns the first queued page that has loaded, keeping the queue's order otherwise. */
    private fun takeReady(): ReaderPage? = synchronized(pending) {
        val index = pending.indexOfFirst { it.status == Page.State.Ready }
        if (index >= 0) pending.removeAt(index) else null
    }

    /** Returns false when the whole queue should stop (bad key or quota). */
    private suspend fun translate(page: ReaderPage, key: Key): Boolean {
        val image = readImage(page) ?: return true
        val settings = settings()
        val request = TranslationRequest(
            targetLanguage = settings.targetLanguage,
            sourceLanguage = settings.sourceLanguage,
            context = synchronized(context) { context.toList() },
        )
        mutableState.update { it.copy(working = true, problem = null, detail = null) }
        return try {
            val result = translator.translate(image, engineFor(settings), request, settings.translateSfx, glossary())
            if (result == null) {
                synchronized(skipped) { skipped += key }
            } else {
                synchronized(results) { results[key] = result }
                files.update { it + (key to result.rendered) }
                updateProgress()
                synchronized(context) {
                    result.translation.blocks.filter { !it.isSfx && it.translation.isNotBlank() }
                        .forEach { context.addLast(it.translation) }
                    while (context.size > GeminiTranslationEngine.MAX_CONTEXT_LINES) context.removeFirst()
                }
            }
            mutableState.update { it.copy(working = false) }
            true
        } catch (error: CancellationException) {
            mutableState.update { it.copy(working = false) }
            throw error
        } catch (error: TranslationException) {
            synchronized(skipped) { skipped += key }
            val problem = when (error) {
                is TranslationException.InvalidKey -> Problem.INVALID_KEY
                is TranslationException.QuotaExceeded -> Problem.QUOTA
                is TranslationException.NeedsWifi -> Problem.NEEDS_WIFI
                is TranslationException.Unavailable -> Problem.UNAVAILABLE
                is TranslationException.Failed -> Problem.FAILED
            }
            val halt = problem == Problem.INVALID_KEY || problem == Problem.QUOTA || problem == Problem.NEEDS_WIFI
            mutableState.update {
                it.copy(working = false, problem = problem, detail = error.message, halted = it.halted || halt)
            }
            !halt
        } catch (error: Exception) {
            logcat(LogPriority.ERROR, error) { "Translation failed" }
            synchronized(skipped) { skipped += key }
            mutableState.update { it.copy(working = false, problem = Problem.FAILED, detail = error.message) }
            true
        }
    }

    private fun readImage(page: ReaderPage): ByteArray? = try {
        page.stream?.invoke()?.use { it.readBytes() }
    } catch (error: Exception) {
        logcat(LogPriority.WARN, error) { "Translation: page unreadable" }
        null
    }

    /** The rendered page for [key], unless the cache trimmed it meanwhile (then it's rendered again when needed). */
    private fun existing(key: Key): File? = files.value[key]?.takeIf { it.isFile }

    private fun key(page: ReaderPage): Key? {
        val target = (page as? InsertPage)?.parent ?: page
        val chapterId = runCatching { target.chapter.chapter.id }.getOrNull() ?: return null
        return Key(chapterId, target.index)
    }

    companion object {
        const val PREFETCH = 2

        /** Longest wait for a page that is loading. */
        private const val READY_TIMEOUT_MS = 60_000L

        /** How long queued pages that nobody is loading may wait before the queue gives up on them. */
        private const val QUEUE_GRACE_MS = 5_000L
        private const val POLL_MS = 250L
    }
}

/** What the reader translates with; read fresh for every page so settings changes apply right away. */
data class TranslationSettings(
    val apiKey: String,
    val model: String,
    val sourceLanguage: String?,
    val targetLanguage: String,
    val translateSfx: Boolean,
    val engine: String = TranslationPreferences.ENGINE_ON_DEVICE,
    val deeplKey: String = "",
    val papagoId: String = "",
    val papagoSecret: String = "",
    /** The on-device language pack may download on mobile data too (otherwise only on Wi-Fi). */
    val allowMobileData: Boolean = false,
) {
    /** Whether the chosen engine has the key it needs (the on-device engine needs none). */
    val ready: Boolean
        get() = when (engine) {
            TranslationPreferences.ENGINE_GEMINI -> apiKey.isNotBlank()
            TranslationPreferences.ENGINE_DEEPL -> deeplKey.isNotBlank()
            TranslationPreferences.ENGINE_PAPAGO -> papagoId.isNotBlank() && papagoSecret.isNotBlank()
            else -> true
        }
}
