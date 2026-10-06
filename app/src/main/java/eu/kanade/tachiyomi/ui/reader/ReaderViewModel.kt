package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import android.net.Uri
import androidx.annotation.IntRange
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactoryKey
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.domain.manga.interactor.SetMangaViewerFlags
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.util.chapter.filterDownloaded
import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.feature.discord.DiscordCovers
import mihon.feature.discord.DiscordPreferences
import mihon.feature.discord.DiscordPresenceFactory
import mihon.feature.discord.PresenceRules
import mihon.feature.discord.PresenceThrottle
import mihon.feature.discord.ReadingNow
import mihon.feature.social.CoverThumbnails
import mihon.feature.social.FriendList
import mihon.feature.social.SocialContent
import mihon.feature.social.SocialRepository
import mihon.feature.social.reader.LocalReading
import mihon.feature.social.reader.ReaderReadingIdentity
import mihon.feature.social.reader.ReaderSocialStatus
import mihon.feature.social.reader.ReadingCompatibility
import mihon.feature.social.reader.ReadingSessionStore
import mihon.feature.social.reader.ReadingTogetherCoordinator
import mihon.feature.translate.BitmapPageImages
import mihon.feature.translate.DeepLTranslator
import mihon.feature.translate.GeminiTranslationEngine
import mihon.feature.translate.MlKitTextFinder
import mihon.feature.translate.MlKitTextTranslator
import mihon.feature.translate.OcrTranslationEngine
import mihon.feature.translate.PageTranslator
import mihon.feature.translate.PapagoTranslator
import mihon.feature.translate.ReaderTranslation
import mihon.feature.translate.TranslationCache
import mihon.feature.translate.TranslationEngine
import mihon.feature.translate.TranslationPreferences
import mihon.feature.translate.TranslationSettings
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.toggle
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.isLocal
import java.io.File
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.time.Clock

/**
 * Presenter used by the activity to perform background operations.
 */
@AssistedInject
class ReaderViewModel(
    @Assisted private val savedState: SavedStateHandle,
    private val context: Context,
    private val sourceManager: SourceManager,
    private val extensionManager: eu.kanade.tachiyomi.extension.ExtensionManager,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val imageSaver: ImageSaver,
    val readerPreferences: ReaderPreferences,
    private val basePreferences: BasePreferences,
    private val downloadPreferences: DownloadPreferences,
    private val trackPreferences: TrackPreferences,
    private val trackChapter: TrackChapter,
    private val getManga: GetManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getFavorites: GetFavorites,
    private val getNextChapters: GetNextChapters,
    private val upsertHistory: UpsertHistory,
    private val updateChapter: UpdateChapter,
    private val setMangaViewerFlags: SetMangaViewerFlags,
    private val getIncognitoState: GetIncognitoState,
    private val libraryPreferences: LibraryPreferences,
    private val coverManager: LocalCoverManager,
    private val updateManga: UpdateManga,
    private val coverCache: CoverCache,
    private val chapterCache: ChapterCache,
    private val downloadCache: DownloadCache,
    private val socialRepository: SocialRepository,
    readingSessionStore: ReadingSessionStore,
    preferenceStore: PreferenceStore,
    private val networkHelper: NetworkHelper,
    private val discordPreferences: DiscordPreferences,
    private val roomTitleImporter: mihon.feature.social.reader.RoomTitleImporter,
) : ViewModel() {

    @AssistedFactory
    @ViewModelAssistedFactoryKey(ReaderViewModel::class)
    @ContributesIntoMap(AppScope::class)
    fun interface Factory : ViewModelAssistedFactory {
        override fun create(extras: CreationExtras): ReaderViewModel {
            return create(extras.createSavedStateHandle())
        }

        fun create(@Assisted savedState: SavedStateHandle): ReaderViewModel
    }

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    val readingTogether = ReadingTogetherCoordinator(
        socialRepository,
        readingSessionStore,
        describeSource = ::describeSocialSource,
        loadMatchedChapter = ::loadMatchedSocialChapter,
    )

    /** The website's download page for the Discord button; none when this build has no website. */
    private fun discordLogoUrl(): String? = eu.kanade.tachiyomi.BuildConfig.SOCIAL_WEB_URL.trimEnd('/')
        .takeIf { it.isNotEmpty() }?.let { "$it/discord-logo.png" }

    private fun discordDownloadUrl(): String? = eu.kanade.tachiyomi.BuildConfig.SOCIAL_WEB_URL.trimEnd('/')
        .takeIf { it.startsWith("https://") }?.let { "$it/download" }

    /** Source name, language and extension package for room invites (roadmap F9); local sources share nothing. */
    private suspend fun describeSocialSource(sourceId: Long): mihon.feature.social.SocialSource? {
        if (sourceId <= 0L) return null
        val source = sourceManager.get(sourceId) ?: return null
        val pkg = extensionManager.loadedExtensionsFlow.first()
            .firstOrNull { extension -> extension.sources.any { it.id == sourceId } }?.pkgName
        return mihon.feature.social.SocialSource(sourceId, source.name.take(80), source.lang.take(12), pkg)
    }
    private var socialPageStatusJob: Job? = null

    private val translationPreferences = TranslationPreferences(preferenceStore)
    private val translationClient by lazy {
        networkHelper.client.newBuilder().readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()
    }
    val translation = ReaderTranslation(
        scope = viewModelScope,
        settings = translationPreferences::settings,
        translator = PageTranslator(TranslationCache(File(context.cacheDir, "translations")), BitmapPageImages()),
        engineFor = ::translationEngine,
        glossary = {
            state.value.manga?.id?.let(translationPreferences::glossary)
                ?: mihon.feature.translate.Glossary.EMPTY
        },
    )
    private val textFinder = lazy { MlKitTextFinder() }
    private val onDeviceTranslator = lazy {
        // Language packs download on Wi-Fi only, unless the reader allowed mobile data in Translation settings.
        MlKitTextTranslator {
            translationPreferences.allowMobileData.get() ||
                context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered == false
        }
    }
    private var cachedEngine: Pair<TranslationSettings, TranslationEngine>? = null

    /** One engine per settings, so the OCR engine remembers the language it detected on earlier pages. */
    private fun translationEngine(settings: TranslationSettings): TranslationEngine = synchronized(this) {
        val reusable = settings.copy(translateSfx = false)
        cachedEngine?.takeIf { it.first == reusable }?.second ?: when (settings.engine) {
            TranslationPreferences.ENGINE_GEMINI ->
                GeminiTranslationEngine(translationClient, settings.apiKey, settings.model)
            TranslationPreferences.ENGINE_DEEPL ->
                OcrTranslationEngine(textFinder.value, DeepLTranslator(translationClient, settings.deeplKey))
            TranslationPreferences.ENGINE_PAPAGO ->
                OcrTranslationEngine(
                    textFinder.value,
                    PapagoTranslator(translationClient, settings.papagoId, settings.papagoSecret),
                )
            else -> OcrTranslationEngine(textFinder.value, onDeviceTranslator.value)
        }.also { cachedEngine = reusable to it }
    }
    private var lastSelectedPage: ReaderPage? = null

    private val incognitoFlow = MutableStateFlow(false)
    private val discordThrottle by lazy { PresenceThrottle(viewModelScope, DiscordPresenceFactory.create()) }

    /** Uploads a small copy of [manga]'s cover for Discord; null when there's no cover or it fails. */
    private suspend fun uploadDiscordCover(manga: Manga, name: String): String? = try {
        val jpeg = withIOContext {
            val custom = coverCache.getCustomCoverFile(manga.id).takeIf { it.isFile }
            val cached = custom ?: coverCache.getCoverFile(manga.thumbnailUrl)?.takeIf { it.isFile }
            if (cached != null) {
                CoverThumbnails.encode(cached)
            } else {
                // Not in the library: fetch it like the cover view does, through the source (its headers, cookies).
                val url = manga.thumbnailUrl ?: return@withIOContext null
                val source = sourceManager.get(manga.source) as? HttpSource ?: return@withIOContext null
                val temp = java.io.File.createTempFile("discord-cover", null, context.cacheDir)
                try {
                    source.client.newCall(GET(url, source.headers)).awaitSuccess().use { response ->
                        temp.outputStream().use { response.body.byteStream().copyTo(it) }
                    }
                    CoverThumbnails.encode(temp)
                } finally {
                    temp.delete()
                }
            }
        }
        jpeg?.let { socialRepository.publishDiscordCover(name, it) }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        logcat(LogPriority.WARN, error) { "Discord cover not uploaded" }
        null
    }

    /**
     * Discord Rich Presence while the reader is visible (the activity runs this while STARTED). Shows only what the
     * user's Discord settings allow, never for incognito or privately hidden titles. When the reader goes to the
     * background the status stays for a grace period (so it can be checked in Discord); leaving the reader clears it.
     */
    suspend fun runDiscordPresence() {
        val opened = mutableMapOf<Long, Long>()
        // My level, while signed in (the XP document is mine to read).
        val level = readingTogether.state.map { it.selfUid }.distinctUntilChanged().flatMapLatest { uid ->
            if (uid == null) {
                flowOf(null)
            } else {
                flow { emitAll(socialRepository.watchXp(uid).map { it?.level }) }.catch { emit(null) }
            }
        }.onStart { emit(null) }.distinctUntilChanged()
        try {
            combine(
                state.map { it.manga to it.currentChapter?.chapter }.distinctUntilChanged(),
                readingTogether.state,
                incognitoFlow,
                discordPreferences.rulesChanges(),
                level,
            ) { (manga, chapter), social, incognito, rules, myLevel ->
                val reading = if (manga != null && chapter != null) {
                    val chapterId = chapter.id ?: -1L
                    ReadingNow(
                        sourceId = manga.source,
                        title = manga.title,
                        coverUrl = manga.thumbnailUrl,
                        chapterName = chapter.name,
                        chapterOpenedAt = opened.getOrPut(chapterId) { System.currentTimeMillis() },
                        private = incognito || social.status == ReaderSocialStatus.PRIVATE,
                        readingWith = social.room?.takeIf { it.state == "active" }?.let { room ->
                            social.selfUid?.let { FriendList.companions(room, it, social.friends) }
                        },
                        roomId = social.room?.takeIf { it.state == "active" }?.id,
                        partySize = social.room?.joinedCount ?: 2,
                        level = myLevel,
                    )
                } else {
                    null
                }
                PresenceRules.presence(
                    reading,
                    rules,
                    readingWithLabel = { context.stringResource(MR.strings.discord_reading_with, it) },
                    downloadUrl = discordDownloadUrl(),
                    logoUrl = discordLogoUrl(),
                    levelLabel = { context.stringResource(MR.strings.discord_level, it) },
                ) to manga
            }.transformLatest { (info, manga) ->
                // Discord fetches covers itself and many sources refuse it: use our uploaded copy when we can. Only
                // for a status that is shown (PresenceRules already applied every privacy rule).
                val original = manga?.thumbnailUrl
                if (info == null || manga == null || original == null || !discordPreferences.showCover.get() ||
                    socialRepository.currentUid == null
                ) {
                    emit(info)
                    return@transformLatest
                }
                val hosted = viewModelScope.async { DiscordCovers.link(original) { uploadDiscordCover(manga, it) } }
                val quick = withTimeoutOrNull(DISCORD_COVER_WAIT_MS) { hosted.await() }
                emit(info.copy(coverUrl = quick ?: info.coverUrl))
                if (quick == null) hosted.await()?.let { emit(info.copy(coverUrl = it)) }
            }.collect(discordThrottle::show)
        } finally {
            // Leaving the reader (app switched, screen off, closed) clears the status at once. A delayed clear
            // can't be trusted: Android freezes background apps, so the timer may never run (beta report).
            discordThrottle.show(null)
        }
    }
    private var enableAfterSettings = false

    /** Reader menu: switches page translation on or off (asks for a key first if there is none). */
    fun toggleTranslation() {
        val enable = !translation.state.value.enabled
        // The on-device engine keeps everything on the phone, so it may run in incognito mode.
        val online = translationPreferences.settings().engine != TranslationPreferences.ENGINE_ON_DEVICE
        if (enable && incognitoMode && online) {
            eventChannel.trySend(Event.TranslationIncognito)
            return
        }
        translation.setEnabled(enable, lastSelectedPage)
        if (enable && translation.state.value.problem == ReaderTranslation.Problem.NO_KEY) {
            enableAfterSettings = true
            openTranslationSettings()
        }
    }

    /** Reader menu: the current page's blocks to edit or hide, with the title's glossary. */
    fun openTranslationEditor() {
        val page = lastSelectedPage ?: return
        val pageTranslation = translation.translationFor(page)
        if (pageTranslation == null) {
            eventChannel.trySend(Event.TranslationNotReady)
            return
        }
        mutableState.update { it.copy(dialog = Dialog.EditTranslation(page, pageTranslation)) }
    }

    fun translationGlossary(): mihon.feature.translate.Glossary =
        state.value.manga?.id?.let(translationPreferences::glossary) ?: mihon.feature.translate.Glossary.EMPTY

    fun saveTranslationEdits(
        page: ReaderPage,
        blocks: List<mihon.feature.translate.TranslatedBlock>,
        glossary: mihon.feature.translate.Glossary,
    ) {
        closeDialog()
        val mangaId = state.value.manga?.id ?: return
        val glossaryChanged = glossary != translationPreferences.glossary(mangaId)
        if (glossaryChanged) translationPreferences.setGlossary(mangaId, glossary)
        viewModelScope.launch {
            translation.saveEdits(page, blocks)
            if (glossaryChanged) translation.redraw(lastSelectedPage)
        }
    }

    /** "Send to room" in the translation editor: the bubble as a quote in the room chat. */
    fun sendTranslationToRoom(block: mihon.feature.translate.TranslatedBlock) {
        val text = mihon.feature.translate.RoomQuote.format(
            block.source,
            translationGlossary().apply(block.translation),
        )
        sendRoomChat(text)
        eventChannel.trySend(Event.TranslationSentToRoom)
    }

    fun openTranslationSettings() {
        mutableState.update { it.copy(dialog = Dialog.TranslationSettings) }
    }

    fun translationSettings(): TranslationSettings = translationPreferences.settings()

    fun saveTranslationSettings(settings: TranslationSettings) {
        val changed = settings != translationPreferences.settings()
        translationPreferences.save(settings)
        closeDialog()
        if (changed) translation.forgetPages()
        if (translation.state.value.enabled || enableAfterSettings) {
            enableAfterSettings = false
            val online = settings.engine != TranslationPreferences.ENGINE_ON_DEVICE
            if (!incognitoMode || !online) translation.setEnabled(true, lastSelectedPage)
        }
        stopOnlineTranslationInIncognito()
    }

    /** Incognito allows only the on-device engine: an online one (switched to, or already on) stops right away. */
    private fun stopOnlineTranslationInIncognito() {
        val online = translationPreferences.settings().engine != TranslationPreferences.ENGINE_ON_DEVICE
        if (incognitoMode && online && translation.state.value.enabled) {
            translation.setEnabled(false)
            eventChannel.trySend(Event.TranslationIncognito)
        }
    }

    fun openReadingTogether() {
        mutableState.update { it.copy(dialog = Dialog.ReadTogether) }
    }

    fun inviteReadingFriend(uid: String) {
        viewModelScope.launch { readingTogether.invite(uid) }
    }

    fun acceptJoinRequest(uid: String) {
        viewModelScope.launch { readingTogether.acceptJoinRequest(uid) }
    }

    fun declineJoinRequest(uid: String) {
        viewModelScope.launch { readingTogether.declineJoinRequest(uid) }
    }

    /** Host only: remove someone from the room. */
    fun kickReadingMember(uid: String) {
        viewModelScope.launch { readingTogether.kick(uid) }
    }

    fun leaveReadingRoom() {
        viewModelScope.launch { readingTogether.leave() }
    }

    /** “Let friends drop in” for the room I host, or the next one I start. */
    fun setReadingDropIn(value: Boolean) {
        viewModelScope.launch { readingTogether.setDropIn(value) }
    }

    /** "Go there" from the room prompt: reopen the reader on the room's title and the host's chapter. */
    fun goToRoomTitle() {
        val room = readingTogether.state.value.room ?: return
        viewModelScope.launch {
            val destination = withIOContext {
                mihon.feature.social.reader.RoomDestinations.resolve(
                    room.content,
                    room.position?.chapterKey,
                    getFavorites.await(),
                ) { getChaptersByMangaId.await(it) }
                    // Not in the library: open it in one step from the host's source, when installed here.
                    ?: roomTitleImporter.import(room.content, room.position?.chapterKey)
            }
            eventChannel.send(
                if (destination == null) {
                    Event.RoomTitleMissing(room.content.title)
                } else {
                    Event.OpenRoomTitle(destination.mangaId, destination.chapterId)
                },
            )
        }
    }

    fun sendRoomChat(text: String) {
        viewModelScope.launch { readingTogether.sendChat(text, null) }
    }

    fun sendRoomReaction(emoji: String) {
        viewModelScope.launch { readingTogether.sendChat(null, emoji) }
    }

    fun sendRoomSignal(kind: String) {
        viewModelScope.launch { readingTogether.sendSignal(kind) }
    }

    /**
     * Ids of the manga and chapter the reader was launched with, taken from the activity intent.
     */
    val mangaId = savedState.get<Long>("manga") ?: -1L
    private val initialChapterId = savedState.get<Long>("chapter") ?: -1L

    val hasValidArgs = mangaId != -1L && initialChapterId != -1L

    private val eventChannel = Channel<Event>()
    val eventFlow = eventChannel.receiveAsFlow()

    /**
     * The manga loaded in the reader. It can be null when instantiated for a short time.
     */
    val manga: Manga?
        get() = state.value.manga

    /**
     * The source of the manga loaded in the reader. Null until it has been resolved.
     */
    val source: Source?
        get() = state.value.source

    /**
     * The chapter id of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterId = savedState.get<Long>("chapter_id") ?: -1L
        set(value) {
            savedState["chapter_id"] = value
            field = value
        }

    /**
     * The visible page index of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterPageIndex = savedState.get<Int>("page_index") ?: -1
        set(value) {
            savedState["page_index"] = value
            field = value
        }

    /**
     * The chapter loader for the loaded manga. It'll be null until [manga] is set.
     */
    private var loader: ChapterLoader? = null

    /**
     * The time the chapter was started reading
     */
    private var chapterReadStartTime: Long? = null

    private var chapterToDownload: Download? = null

    private val unfilteredChapterList by lazy {
        val manga = manga!!
        runBlocking { getChaptersByMangaId.await(manga.id, applyScanlatorFilter = false) }
    }

    /**
     * Chapter list for the active manga. It's retrieved lazily and should be accessed for the first
     * time in a background thread to avoid blocking the UI.
     */
    private val chapterList by lazy {
        val manga = manga!!
        val chapters = runBlocking { getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true) }
            .let { filtered ->
                if (filtered.any { it.id == chapterId }) {
                    filtered
                } else {
                    filtered + unfilteredChapterList.filter { it.id == chapterId }
                }
            }

        val selectedChapter = chapters.find { it.id == chapterId }
            ?: error("Requested chapter of id $chapterId not found in chapter list")

        val chaptersForReader = when {
            (readerPreferences.skipRead.get() || readerPreferences.skipFiltered.get()) -> {
                val filteredChapters = chapters.filterNot {
                    when {
                        readerPreferences.skipRead.get() && it.read -> true
                        readerPreferences.skipFiltered.get() -> {
                            (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_READ && !it.read) ||
                                (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_UNREAD && it.read) ||
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_DOWNLOADED &&
                                        !downloadManager.isChapterDownloaded(
                                            it.name,
                                            it.scanlator,
                                            it.url,
                                            manga.title,
                                            manga.source,
                                        )
                                    ) ||
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_NOT_DOWNLOADED &&
                                        downloadManager.isChapterDownloaded(
                                            it.name,
                                            it.scanlator,
                                            it.url,
                                            manga.title,
                                            manga.source,
                                        )
                                    ) ||
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_BOOKMARKED && !it.bookmark) ||
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_NOT_BOOKMARKED && it.bookmark)
                        }
                        else -> false
                    }
                }

                if (filteredChapters.any { it.id == chapterId }) {
                    filteredChapters
                } else {
                    filteredChapters + listOf(selectedChapter)
                }
            }
            else -> chapters
        }

        chaptersForReader
            .sortedWith(getChapterSort(manga, sortDescending = false))
            .run {
                if (readerPreferences.skipDupe.get()) {
                    removeDuplicates(selectedChapter)
                } else {
                    this
                }
            }
            .run {
                if (basePreferences.downloadedOnly.get()) {
                    filterDownloaded(manga, downloadCache)
                } else {
                    this
                }
            }
            .map { it.toDbChapter() }
            .map(::ReaderChapter)
    }

    private var incognitoMode: Boolean = false
    private val downloadAheadAmount = downloadPreferences.autoDownloadWhileReading.get()

    init {
        // To save state
        state.map { it.viewerChapters?.currChapter }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { currentChapter ->
                if (chapterPageIndex >= 0) {
                    // Restore from SavedState
                    currentChapter.requestedPage = chapterPageIndex
                } else if (!currentChapter.chapter.read) {
                    currentChapter.requestedPage = currentChapter.chapter.last_page_read
                }
                chapterId = currentChapter.chapter.id!!
            }
            .launchIn(viewModelScope)

        if (hasValidArgs) {
            viewModelScope.launch { init() }
        }
    }

    override fun onCleared() {
        if (onDeviceTranslator.isInitialized()) onDeviceTranslator.value.close()
        if (textFinder.isInitialized()) textFinder.value.close()
        // The throttle's coroutine dies with viewModelScope, so clear Discord directly.
        DiscordPresenceFactory.create().clear()
        val currentChapters = state.value.viewerChapters
        if (currentChapters != null) {
            currentChapters.unref()
            chapterToDownload?.let {
                downloadManager.addDownloadsToStartOfQueue(listOf(it))
            }
        }
    }

    /**
     * Called when the user pressed the back button and is going to leave the reader. Used to
     * trigger deletion of the downloaded chapters.
     */
    fun onActivityFinish() {
        socialPageStatusJob?.cancel()
        viewModelScope.launchNonCancellable { readingTogether.finish() }
        deletePendingChapters()
    }

    /**
     * Initializes this presenter with the [mangaId] and [initialChapterId] the reader was launched
     * with. This method will fetch the manga from the database and initialize the initial chapter.
     * Failures are reported through [State.initError].
     */
    private suspend fun init() {
        withIOContext {
            try {
                val manga = getManga.await(mangaId) ?: error("Requested manga of id $mangaId not found")
                val source = sourceManager.getOrStub(manga.source)
                incognitoMode = getIncognitoState.await(manga.source)
                incognitoFlow.value = incognitoMode
                readingTogether.setIncognito(incognitoMode)
                getIncognitoState.subscribe(manga.source)
                    .onEach {
                        incognitoMode = it
                        incognitoFlow.value = it
                        readingTogether.setIncognito(it)
                        stopOnlineTranslationInIncognito()
                    }
                    .launchIn(viewModelScope)
                mutableState.update { it.copy(manga = manga, source = source) }
                if (chapterId == -1L) chapterId = initialChapterId

                loader = ChapterLoader(context, downloadManager, downloadProvider, chapterCache, manga, source)

                loadChapter(loader!!, chapterList.first { chapterId == it.chapter.id })
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                mutableState.update { it.copy(initError = e) }
            }
        }
    }

    /**
     * Loads the given [chapter] with this [loader] and updates the currently active chapters.
     * Callers must handle errors.
     */
    private suspend fun loadChapter(
        loader: ChapterLoader,
        chapter: ReaderChapter,
    ): ViewerChapters {
        loader.loadChapter(chapter)

        val chapterPos = chapterList.indexOf(chapter)
        val newChapters = ViewerChapters(
            chapter,
            chapterList.getOrNull(chapterPos - 1),
            chapterList.getOrNull(chapterPos + 1),
        )

        withUIContext {
            mutableState.update {
                // Add new references first to avoid unnecessary recycling
                newChapters.ref()
                it.viewerChapters?.unref()

                chapterToDownload = cancelQueuedDownloads(newChapters.currChapter)
                it.copy(
                    viewerChapters = newChapters,
                    bookmarked = newChapters.currChapter.chapter.bookmark,
                )
            }
        }
        return newChapters
    }

    /**
     * Called when the user changed to the given [chapter] when changing pages from the viewer.
     * It's used only to set this chapter as active.
     */
    private fun loadNewChapter(chapter: ReaderChapter) {
        val loader = loader ?: return

        viewModelScope.launchIO {
            logcat { "Loading ${chapter.chapter.url}" }

            updateHistory()
            restartReadTimer()

            try {
                loadChapter(loader, chapter)
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    /**
     * Called when the user is going to load the prev/next chapter through the toolbar buttons.
     */
    private suspend fun loadAdjacent(chapter: ReaderChapter) {
        val loader = loader ?: return

        logcat { "Loading adjacent ${chapter.chapter.url}" }

        mutableState.update { it.copy(isLoadingAdjacentChapter = true) }
        try {
            withIOContext {
                loadChapter(loader, chapter)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            logcat(LogPriority.ERROR, e)
        } finally {
            mutableState.update { it.copy(isLoadingAdjacentChapter = false) }
        }
    }

    /**
     * Called when the viewers decide it's a good time to preload a [chapter] and improve the UX so
     * that the user doesn't have to wait too long to continue reading.
     */
    suspend fun preload(chapter: ReaderChapter) {
        if (chapter.state is ReaderChapter.State.Loaded || chapter.state == ReaderChapter.State.Loading) {
            return
        }

        if (chapter.pageLoader?.isLocal == false) {
            val manga = manga ?: return
            val dbChapter = chapter.chapter
            val source = state.value.source ?: return
            val isDownloaded = downloadManager.isChapterDownloadedOnDisk(
                dbChapter.name,
                dbChapter.scanlator,
                dbChapter.url,
                manga.title,
                source,
            )
            if (isDownloaded) {
                chapter.state = ReaderChapter.State.Wait
            }
        }

        if (chapter.state != ReaderChapter.State.Wait && chapter.state !is ReaderChapter.State.Error) {
            return
        }

        val loader = loader ?: return
        try {
            logcat { "Preloading ${chapter.chapter.url}" }
            loader.loadChapter(chapter)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            return
        }
        eventChannel.trySend(Event.ReloadViewerChapters)
    }

    fun onViewerLoaded(viewer: Viewer?) {
        mutableState.update {
            it.copy(viewer = viewer)
        }
    }

    /**
     * Called every time a page changes on the reader. Used to mark the flag of chapters being
     * read, update tracking services, enqueue downloaded chapter deletion, and updating the active chapter if this
     * [page]'s chapter is different from the currently active.
     */
    fun onPageSelected(page: ReaderPage) {
        // InsertPage doesn't change page progress
        if (page is InsertPage) {
            return
        }

        val selectedChapter = page.chapter
        val pages = selectedChapter.pages ?: return
        lastSelectedPage = page
        translation.onPageSelected(page)

        // A settled real page is a lightweight state update; the lifecycle writer owns network I/O.
        socialPageStatusJob?.cancel()
        if (manga?.let { ReaderReadingIdentity.reading(it, page) } == null) {
            readingTogether.pageUnavailable()
        } else {
            socialPageStatusJob = viewModelScope.launch {
                page.statusFlow.collect { status ->
                    if (status == Page.State.Ready) {
                        manga?.let { ReaderReadingIdentity.reading(it, page) }?.let(readingTogether::pageSelected)
                    }
                }
            }
        }

        // Save last page read and mark as read if needed
        viewModelScope.launchNonCancellable {
            updateChapterProgress(selectedChapter, page)
        }

        if (selectedChapter != getCurrentChapter()) {
            logcat { "Setting ${selectedChapter.chapter.url} as active" }
            loadNewChapter(selectedChapter)
        }

        val inDownloadRange = page.number.toDouble() / pages.size > 0.25
        if (inDownloadRange) {
            downloadNextChapters()
        }

        eventChannel.trySend(Event.PageChanged)
    }

    /** Resolve only the already-installed exact source/edition/chapter; never select by title/count. */
    private suspend fun loadMatchedSocialChapter(
        content: SocialContent,
        chapterKey: String,
    ): LocalReading? = withIOContext {
        val manga = manga ?: return@withIOContext null
        val current = mihon.feature.social.SocialIdentity.content(manga) ?: return@withIOContext null
        if (getIncognitoState.await(manga.source) || !ReadingCompatibility.sameEdition(current, content)) {
            return@withIOContext null
        }
        val chapter = chapterList.firstOrNull { ReaderReadingIdentity.chapterKey(current, it) == chapterKey }
            ?: return@withIOContext null
        val chapterLoader = loader ?: return@withIOContext null
        if (getCurrentChapter() != chapter) {
            updateHistory()
            restartReadTimer()
            chapterPageIndex = 0
            chapter.requestedPage = 0
            loadChapter(chapterLoader, chapter)
            // The pager only honours requestedPage on first layout; otherwise it keeps the old item index.
            chapter.requestedPage = 0
            eventChannel.send(Event.MoveToPage(0))
        }
        ReaderReadingIdentity.reading(manga, chapter, chapter.requestedPage.coerceIn(0, (chapter.pages?.size ?: 1) - 1))
    }

    private fun downloadNextChapters() {
        if (downloadAheadAmount == 0) return
        val manga = manga ?: return

        // Only download ahead if current + next chapter is already downloaded too to avoid jank
        if (getCurrentChapter()?.pageLoader !is DownloadPageLoader) return
        val nextChapter = state.value.viewerChapters?.nextChapter?.chapter ?: return

        viewModelScope.launchIO {
            val isNextChapterDownloaded = downloadManager.isChapterDownloaded(
                nextChapter.name,
                nextChapter.scanlator,
                nextChapter.url,
                manga.title,
                manga.source,
            )
            if (!isNextChapterDownloaded) return@launchIO

            val chaptersToDownload = getNextChapters.await(manga.id, nextChapter.id!!).run {
                if (readerPreferences.skipDupe.get()) {
                    removeDuplicates(nextChapter.toDomainChapter()!!)
                } else {
                    this
                }
            }.take(downloadAheadAmount)

            downloadManager.downloadChapters(
                manga,
                chaptersToDownload,
            )
        }
    }

    /**
     * Removes [currentChapter] from download queue
     * if setting is enabled and [currentChapter] is queued for download
     */
    private fun cancelQueuedDownloads(currentChapter: ReaderChapter): Download? {
        return downloadManager.getQueuedDownloadOrNull(currentChapter.chapter.id!!)?.also {
            downloadManager.cancelQueuedDownloads(listOf(it))
        }
    }

    /**
     * Determines if deleting option is enabled and nth to last chapter actually exists.
     * If both conditions are satisfied enqueues chapter for delete
     * @param currentChapter current chapter, which is going to be marked as read.
     */
    private fun deleteChapterIfNeeded(currentChapter: ReaderChapter) {
        val removeAfterReadSlots = downloadPreferences.removeAfterReadSlots.get()
        if (removeAfterReadSlots == -1) return

        // Determine which chapter should be deleted and enqueue
        val currentChapterPosition = chapterList.indexOf(currentChapter)
        val chapterToDelete = chapterList.getOrNull(currentChapterPosition - removeAfterReadSlots)

        // If chapter is completely read, no need to download it
        chapterToDownload = null

        if (chapterToDelete != null) {
            enqueueDeleteReadChapters(chapterToDelete)
        }
    }

    /**
     * Saves the chapter progress (last read page and whether it's read)
     * if incognito mode isn't on.
     */
    private suspend fun updateChapterProgress(readerChapter: ReaderChapter, page: Page) {
        val pageIndex = page.index

        mutableState.update {
            it.copy(currentPage = pageIndex + 1)
        }
        readerChapter.requestedPage = pageIndex
        chapterPageIndex = pageIndex

        if (!incognitoMode && page.status !is Page.State.Error) {
            readerChapter.chapter.last_page_read = pageIndex

            if (readerChapter.pages?.lastIndex == pageIndex) {
                val firstCompletion = !readerChapter.chapter.read
                logcat { "Social feed chapter completed first=$firstCompletion" }
                updateChapterProgressOnComplete(readerChapter)
                if (firstCompletion) {
                    manga?.let { ReaderReadingIdentity.reading(it, readerChapter, pageIndex) }?.let { reading ->
                        viewModelScope.launch { readingTogether.publishChapterRead(reading) }
                    }
                }
            }

            updateChapter.await(
                ChapterUpdate(readerChapter.chapter.id!!) {
                    read = readerChapter.chapter.read
                    lastPageRead = readerChapter.chapter.last_page_read.toLong()
                },
            )
        }
    }

    private suspend fun updateChapterProgressOnComplete(readerChapter: ReaderChapter) {
        readerChapter.chapter.read = true
        updateTrackChapterRead(readerChapter)
        deleteChapterIfNeeded(readerChapter)

        val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead.get()
            .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING)
        if (!markDuplicateAsRead) return

        val duplicateUnreadChapters = unfilteredChapterList
            .mapNotNull { chapter ->
                if (
                    !chapter.read &&
                    chapter.isRecognizedNumber &&
                    chapter.chapterNumber.toFloat() == readerChapter.chapter.chapter_number
                ) {
                    ChapterUpdate(chapter.id) { read = true }
                } else {
                    null
                }
            }
        updateChapter.awaitAll(duplicateUnreadChapters)
    }

    fun restartReadTimer() {
        chapterReadStartTime = Clock.System.now().toEpochMilliseconds()
    }

    /**
     * Saves the chapter last read history if incognito mode isn't on.
     */
    suspend fun updateHistory() {
        getCurrentChapter()?.let { readerChapter ->
            if (incognitoMode) return@let

            val chapterId = readerChapter.chapter.id!!
            val endTime = Date()
            val sessionReadDuration = chapterReadStartTime?.let { endTime.time - it } ?: 0
            chapterReadStartTime = null

            upsertHistory.await(HistoryUpdate(chapterId, endTime, sessionReadDuration))
        }
    }

    /**
     * Called from the activity to load and set the next chapter as active.
     */
    suspend fun loadNextChapter() {
        val nextChapter = state.value.viewerChapters?.nextChapter ?: return
        loadAdjacent(nextChapter)
    }

    /**
     * Called from the activity to load and set the previous chapter as active.
     */
    suspend fun loadPreviousChapter() {
        val prevChapter = state.value.viewerChapters?.prevChapter ?: return
        loadAdjacent(prevChapter)
    }

    /**
     * Returns the currently active chapter.
     */
    private fun getCurrentChapter(): ReaderChapter? {
        return state.value.currentChapter
    }

    fun getSource() = state.value.source as? HttpSource

    fun getChapterUrl(): String? {
        val sChapter = getCurrentChapter()?.chapter ?: return null
        val source = getSource() ?: return null

        return try {
            source.getChapterUrl(sChapter)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    /**
     * Bookmarks the currently active chapter.
     */
    fun toggleChapterBookmark() {
        val chapter = getCurrentChapter()?.chapter ?: return
        val bookmarked = !chapter.bookmark
        chapter.bookmark = bookmarked

        viewModelScope.launchNonCancellable {
            updateChapter.await(
                ChapterUpdate(chapter.id!!) {
                    bookmark = bookmarked
                },
            )
        }

        mutableState.update {
            it.copy(
                bookmarked = bookmarked,
            )
        }
    }

    /**
     * Returns the viewer position used by this manga or the default one.
     */
    fun getMangaReadingMode(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultReadingMode.get()
        val readingMode = ReadingMode.fromPreference(manga?.readingMode?.toInt())
        return when {
            resolveDefault && readingMode == ReadingMode.DEFAULT -> default
            else -> manga?.readingMode?.toInt() ?: default
        }
    }

    /**
     * Updates the viewer position for the open manga.
     */
    fun setMangaReadingMode(readingMode: ReadingMode) {
        val manga = manga ?: return
        runBlocking(Dispatchers.IO) {
            setMangaViewerFlags.awaitSetReadingMode(manga.id, readingMode.flagValue.toLong())
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = currChapter.chapter.last_page_read

                mutableState.update {
                    it.copy(
                        manga = getManga.await(manga.id),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    /**
     * Returns the orientation type used by this manga or the default one.
     */
    fun getMangaOrientation(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultOrientationType.get()
        val orientation = ReaderOrientation.fromPreference(manga?.readerOrientation?.toInt())
        return when {
            resolveDefault && orientation == ReaderOrientation.DEFAULT -> default
            else -> manga?.readerOrientation?.toInt() ?: default
        }
    }

    /**
     * Updates the orientation type for the open manga.
     */
    fun setMangaOrientationType(orientation: ReaderOrientation) {
        val manga = manga ?: return
        viewModelScope.launchIO {
            setMangaViewerFlags.awaitSetOrientation(manga.id, orientation.flagValue.toLong())
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = currChapter.chapter.last_page_read

                mutableState.update {
                    it.copy(
                        manga = getManga.await(manga.id),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.SetOrientation(getMangaOrientation()))
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    fun toggleCropBorders(): Boolean {
        val isPagerType = ReadingMode.isPagerType(getMangaReadingMode())
        return if (isPagerType) {
            readerPreferences.cropBorders.toggle()
        } else {
            readerPreferences.cropBordersWebtoon.toggle()
        }
    }

    /**
     * Generate a filename for the given [manga] and [page]
     */
    private fun generateFilename(
        manga: Manga,
        page: ReaderPage,
    ): String {
        val chapter = page.chapter.chapter
        val filenameSuffix = " - ${page.number}"
        return DiskUtil.buildValidFilename(
            "${manga.title} - ${chapter.name}",
            DiskUtil.MAX_FILE_NAME_BYTES - filenameSuffix.byteSize(),
        ) + filenameSuffix
    }

    fun showMenus(visible: Boolean) {
        mutableState.update { it.copy(menuVisible = visible) }
    }

    fun showLoadingDialog() {
        mutableState.update { it.copy(dialog = Dialog.Loading) }
    }

    fun openReadingModeSelectDialog() {
        mutableState.update { it.copy(dialog = Dialog.ReadingModeSelect) }
    }

    fun openOrientationModeSelectDialog() {
        mutableState.update { it.copy(dialog = Dialog.OrientationModeSelect) }
    }

    fun openPageDialog(page: ReaderPage) {
        mutableState.update { it.copy(dialog = Dialog.PageActions(page)) }
    }

    fun openSettingsDialog() {
        mutableState.update { it.copy(dialog = Dialog.Settings) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun setBrightnessOverlayValue(value: Int) {
        mutableState.update { it.copy(brightnessOverlayValue = value) }
    }

    /**
     * Saves the image of the selected page on the pictures directory and notifies the UI of the result.
     * There's also a notification to allow sharing the image somewhere else or deleting it.
     */
    fun saveImage() {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return

        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        val filename = generateFilename(manga, page)

        // Pictures directory.
        val relativePath = if (readerPreferences.folderPerManga.get()) {
            DiskUtil.buildValidFilename(
                manga.title,
            )
        } else {
            ""
        }

        // Copy file in background.
        viewModelScope.launchNonCancellable {
            try {
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = page.stream!!,
                        name = filename,
                        location = Location.Pictures.create(relativePath),
                    ),
                )
                withUIContext {
                    notifier.onComplete(uri)
                    eventChannel.send(Event.SavedImage(SaveImageResult.Success(uri)))
                }
            } catch (e: Throwable) {
                notifier.onError(e.message)
                eventChannel.send(Event.SavedImage(SaveImageResult.Error(e)))
            }
        }
    }

    /**
     * Shares the image of the selected page and notifies the UI with the path of the file to share.
     * The image must be first copied to the internal partition because there are many possible
     * formats it can come from, like a zipped chapter, in which case it's not possible to directly
     * get a path to the file and it has to be decompressed somewhere first. Only the last shared
     * image will be kept so it won't be taking lots of internal disk space.
     */
    fun shareImage(copyToClipboard: Boolean) {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return

        val destDir = context.cacheImageDir

        val filename = generateFilename(manga, page)

        try {
            viewModelScope.launchNonCancellable {
                destDir.deleteRecursively()
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = page.stream!!,
                        name = filename,
                        location = Location.Cache,
                    ),
                )
                eventChannel.send(if (copyToClipboard) Event.CopyImage(uri) else Event.ShareImage(uri, page))
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
        }
    }

    /**
     * Sets the image of the selected page as cover and notifies the UI of the result.
     */
    fun setAsCover() {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return
        val stream = page.stream ?: return

        viewModelScope.launchNonCancellable {
            val result = try {
                manga.editCover(coverManager, stream(), updateManga, coverCache)
                if (manga.isLocal() || manga.favorite) {
                    SetAsCoverResult.Success
                } else {
                    SetAsCoverResult.AddToLibraryFirst
                }
            } catch (e: Exception) {
                SetAsCoverResult.Error
            }
            eventChannel.send(Event.SetCoverResult(result))
        }
    }

    enum class SetAsCoverResult {
        Success,
        AddToLibraryFirst,
        Error,
    }

    sealed interface SaveImageResult {
        class Success(val uri: Uri) : SaveImageResult
        class Error(val error: Throwable) : SaveImageResult
    }

    /**
     * Starts the service that updates the last chapter read in sync services. This operation
     * will run in a background thread and errors are ignored.
     */
    private fun updateTrackChapterRead(readerChapter: ReaderChapter) {
        if (incognitoMode) return
        if (!trackPreferences.autoUpdateTrack.get()) return

        val manga = manga ?: return

        viewModelScope.launchNonCancellable {
            trackChapter.await(context, manga.id, readerChapter.chapter.chapter_number.toDouble())
        }
    }

    /**
     * Enqueues this [chapter] to be deleted when [deletePendingChapters] is called. The download
     * manager handles persisting it across process deaths.
     */
    private fun enqueueDeleteReadChapters(chapter: ReaderChapter) {
        if (!chapter.chapter.read) return
        val manga = manga ?: return

        viewModelScope.launchNonCancellable {
            downloadManager.enqueueChaptersToDelete(listOf(chapter.chapter.toDomainChapter()!!), manga)
        }
    }

    /**
     * Deletes all the pending chapters. This operation will run in a background thread and errors
     * are ignored.
     */
    private fun deletePendingChapters() {
        viewModelScope.launchNonCancellable {
            downloadManager.deletePendingChapters()
        }
    }

    @Immutable
    data class State(
        val manga: Manga? = null,
        val source: Source? = null,
        val initError: Throwable? = null,
        val viewerChapters: ViewerChapters? = null,
        val bookmarked: Boolean = false,
        val isLoadingAdjacentChapter: Boolean = false,
        val currentPage: Int = -1,

        /**
         * Viewer used to display the pages (pager, webtoon, ...).
         */
        val viewer: Viewer? = null,
        val dialog: Dialog? = null,
        val menuVisible: Boolean = false,
        @IntRange(from = -100, to = 100) val brightnessOverlayValue: Int = 0,
    ) {
        val currentChapter: ReaderChapter?
            get() = viewerChapters?.currChapter

        val totalPages: Int
            get() = currentChapter?.pages?.size ?: -1
    }

    sealed interface Dialog {
        data object Loading : Dialog
        data object Settings : Dialog
        data object ReadTogether : Dialog
        data object TranslationSettings : Dialog
        data class EditTranslation(
            val page: ReaderPage,
            val translation: mihon.feature.translate.PageTranslation,
        ) : Dialog
        data object ReadingModeSelect : Dialog
        data object OrientationModeSelect : Dialog
        data class PageActions(val page: ReaderPage) : Dialog
    }

    sealed interface Event {
        data object ReloadViewerChapters : Event
        data object PageChanged : Event
        data class SetOrientation(val orientation: Int) : Event
        data class MoveToPage(val pageIndex: Int) : Event
        data class SetCoverResult(val result: SetAsCoverResult) : Event

        data class SavedImage(val result: SaveImageResult) : Event
        data class ShareImage(val uri: Uri, val page: ReaderPage) : Event
        data class CopyImage(val uri: Uri) : Event
        data class OpenRoomTitle(val mangaId: Long, val chapterId: Long?) : Event
        data class RoomTitleMissing(val title: String) : Event
        data object TranslationIncognito : Event
        data object TranslationNotReady : Event
        data object TranslationSentToRoom : Event
    }
}

/** How long Discord waits for our cover copy before showing the source's own link (replaced once uploaded). */
private const val DISCORD_COVER_WAIT_MS = 8_000L
