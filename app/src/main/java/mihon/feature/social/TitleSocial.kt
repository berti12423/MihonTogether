package mihon.feature.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.manga.components.MangaCover
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** "Friends who read this" and my rating, for one title page. Everything stays empty while signed out. */
@Immutable
data class TitleSocialState(
    val signedIn: Boolean = false,
    val readers: List<FriendReading> = emptyList(),
    val myReview: SocialReview? = null,
    /** The title can be shared and rated (an online source, not incognito, not hidden from friends). */
    val rateable: Boolean = false,
    val saving: Boolean = false,
    val failed: Boolean = false,
)

@AssistedInject
class TitleSocialViewModel(
    @Assisted private val mangaId: Long,
    private val repository: SocialRepository,
    private val friendReaders: FriendReaders,
    private val getManga: GetManga,
    private val getTracks: GetTracks,
    private val incognito: GetIncognitoState,
    private val preferences: PreferenceStore,
) : ViewModel() {
    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(mangaId: Long): TitleSocialViewModel
    }

    private val mutableState = MutableStateFlow(TitleSocialState())
    val state: StateFlow<TitleSocialState> = mutableState.asStateFlow()

    /** The title as reviews share it, with the same tracker ID and cover rules as the shared library. */
    private var content: SocialContent? = null

    /** My side of the title comparison (see [workOf]). */
    private var work: TitleMatch.Work? = null

    init {
        viewModelScope.launch {
            combine(
                repository.authChanges().map { it?.uid }.distinctUntilChanged(),
                getManga.subscribe(mangaId).distinctUntilChanged { a, b ->
                    a.title == b.title && a.source == b.source && a.url == b.url && a.thumbnailUrl == b.thumbnailUrl
                },
                getTracks.subscribe(mangaId).distinctUntilChanged(),
            ) { uid, manga, tracks -> Triple(uid, manga, tracks) }
                .collectLatest { (uid, manga, tracks) ->
                    if (uid == null) {
                        mutableState.value = TitleSocialState()
                        return@collectLatest
                    }
                    val shared = SocialIdentity.content(manga)?.let { base ->
                        val tracked = canonical(tracks)
                        val cover = if (preferences.getBoolean("social_share_covers", true).get()) {
                            SocialIdentity.publicCover(manga.thumbnailUrl)
                        } else {
                            null
                        }
                        base.copy(canonicalProvider = tracked?.first, canonicalId = tracked?.second, coverUrl = cover)
                    }
                    content = shared
                    val mine = workOf(manga, shared, tracks)
                    work = mine
                    val sourceIncognito = incognito.await(manga.source)
                    mutableState.update { it.copy(signedIn = true) }
                    // Loaded once per session for all title pages (FriendReaders), then matched here; revocations
                    // and my own rating changes arrive through the cache.
                    try {
                        friendReaders.snapshot()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        logcat(LogPriority.WARN, error) { "Friends who read this not loaded" }
                    }
                    // Hidden titles can't be rated (the server refuses them too, and hiding deletes the review).
                    // A failed listener only loses that check: the server refuses hidden titles anyway.
                    val hiddenKeys = repository.watchHiddenKeys().catch { emit(emptySet()) }
                    combine(friendReaders.cache, hiddenKeys, ::Pair)
                        .collect { (snapshot, hidden) ->
                            val held = snapshot?.takeIf { it.uid == uid }
                            mutableState.update {
                                it.copy(
                                    rateable = shared != null && !sourceIncognito && shared.contentKey !in hidden,
                                    readers = held?.let { s -> FriendReadings.collect(mine, s.friends) }.orEmpty(),
                                    myReview = held?.let { s -> FriendReadings.mine(mine, s.mine) },
                                )
                            }
                        }
                }
        }
    }

    fun rate(rating: Int, text: String) = save { uid, shared ->
        val old = mutableState.value.myReview
        val saved = checkNotNull(repository.review("set", shared, rating.coerceIn(1, 5), SocialReview.cleanText(text)))
        // The work key moved (e.g. the title got tracked since): the older review goes, so there's one per work, but
        // only when it's surely the same work (same source entry or tracker ID); a title-only match keeps both.
        val replaced = old != null && old.workKey != saved.workKey &&
            work?.let { FriendReadings.replaces(it, old) } == true
        if (old != null && replaced) {
            try {
                repository.review("delete", old.content, null, null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logcat(LogPriority.WARN, error) { "Old review not deleted" }
            }
        }
        friendReaders.updateMine(uid) { list ->
            list.filterNot { (replaced && it.workKey == old?.workKey) || it.workKey == saved.workKey } + saved
        }
        // Also when nothing is cached (the friends' load failed): the page shows what was saved.
        mutableState.update { it.copy(myReview = saved) }
    }

    fun deleteReview() = save { uid, _ ->
        val old = mutableState.value.myReview ?: return@save
        repository.review("delete", old.content, null, null)
        friendReaders.updateMine(uid) { list -> list.filterNot { it.workKey == old.workKey } }
        mutableState.update { it.copy(myReview = null) }
    }

    fun dismissFailure() = mutableState.update { it.copy(failed = false) }

    suspend fun loadMedia(uid: String, kind: String, version: Long): ByteArray? =
        repository.loadProfileMedia(uid, kind, version)

    private fun save(block: suspend (uid: String, content: SocialContent) -> Unit) {
        val uid = repository.currentUid ?: return
        val shared = content ?: return
        if (mutableState.value.saving) return
        viewModelScope.launch {
            mutableState.update { it.copy(saving = true, failed = false) }
            try {
                block(uid, shared)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logcat(LogPriority.WARN, error) { "Review not saved" }
                mutableState.update { it.copy(failed = true) }
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        }
    }

    private fun canonical(tracks: List<Track>): Pair<String, String>? {
        val tracked = tracks.filter { it.remoteId > 0 }
        // Same order as the shared library: AniList, then MyAnimeList.
        tracked.firstOrNull { it.trackerId == ANILIST }?.let { return "anilist" to it.remoteId.toString() }
        tracked.firstOrNull { it.trackerId == MAL }?.let { return "mal" to it.remoteId.toString() }
        return null
    }

    companion object {
        private const val MAL = 1L
        private const val ANILIST = 2L

        /**
         * My side of the comparison: every tracker ID I have for the title, and the trackers' titles as alternative
         * names. A title that can't be shared (local source) still matches by tracker or title.
         */
        fun workOf(manga: Manga, shared: SocialContent?, tracks: List<Track>): TitleMatch.Work {
            val ids = tracks.filter { it.remoteId > 0 }.mapNotNull { track ->
                TitleMatch.TRACKERS[track.trackerId]?.let { it to track.remoteId.toString() }
            }.toMap()
            return TitleMatch.Work(
                sourceKey = shared?.sourceKey ?: "local",
                sourceWorkKey = shared?.sourceWorkKey ?: "local:${manga.id}",
                trackers = ids,
                titles = listOf(manga.title) + tracks.map { it.title }.filter { it.isNotBlank() },
            )
        }
    }
}

/**
 * Under a title's description: friends who read it (avatars and a short label; tap for the list) and my rating.
 * [canRate] is whether I opened at least one chapter. Shows nothing while signed out or with nothing to show.
 */
@Composable
fun TitleFriendsSection(viewModel: TitleSocialViewModel, canRate: Boolean, onOpenProfile: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rating = state.rateable && canRate
    if (!state.signedIn || (state.readers.isEmpty() && !rating && state.myReview == null)) return
    var showReaders by rememberSaveable { mutableStateOf(false) }
    var showRating by rememberSaveable { mutableStateOf(false) }
    TitleFriendsRow(
        readers = state.readers,
        myReview = state.myReview,
        canRate = rating,
        loadMedia = viewModel::loadMedia,
        onOpenReaders = { showReaders = true },
        onRate = { showRating = true },
    )
    if (showReaders && state.readers.isNotEmpty()) {
        FriendReadersSheet(state.readers, viewModel::loadMedia, onOpenProfile = {
            showReaders = false
            onOpenProfile(it)
        }) { showReaders = false }
    }
    if (showRating) {
        RatingDialog(
            existing = state.myReview,
            saving = state.saving,
            onSave = { stars, text -> viewModel.rate(stars, text) },
            onDelete = viewModel::deleteReview,
        ) { showRating = false }
    }
    if (state.failed) {
        AlertDialog(
            onDismissRequest = viewModel::dismissFailure,
            text = { Text(stringResource(MR.strings.social_review_failed)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissFailure) { Text(stringResource(MR.strings.action_close)) }
            },
        )
    }
}

@Composable
private fun TitleFriendsRow(
    readers: List<FriendReading>,
    myReview: SocialReview?,
    canRate: Boolean,
    loadMedia: ProfileMediaLoader,
    onOpenReaders: () -> Unit,
    onRate: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clickable(enabled = readers.isNotEmpty(), onClick = onOpenReaders)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (readers.isNotEmpty()) {
                val shown = readers.take(MAX_AVATARS)
                // Overlapping avatars, the first on top.
                Box(Modifier.width((AVATAR_STEP * (shown.size - 1) + AVATAR_SIZE).dp)) {
                    shown.asReversed().forEachIndexed { index, reader ->
                        ProfileAvatar(
                            reader.profile,
                            loadMedia,
                            AVATAR_SIZE.dp,
                            Modifier.padding(start = (AVATAR_STEP * (shown.size - 1 - index)).dp),
                        )
                    }
                }
                Text(
                    readersLabel(readers),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        when {
            myReview != null -> TextButton(onClick = onRate) {
                Text(SocialReview.stars(myReview.rating), color = MaterialTheme.colorScheme.primary)
            }
            canRate -> OutlinedButton(onClick = onRate) { Text(stringResource(MR.strings.social_rate)) }
        }
    }
}

@Composable
private fun readersLabel(readers: List<FriendReading>): String {
    val names = readers.map { it.profile.displayName }
    return when (names.size) {
        1 -> stringResource(MR.strings.social_title_friends_one, names[0])
        2 -> stringResource(MR.strings.social_title_friends_two, names[0], names[1])
        else -> stringResource(MR.strings.social_title_friends_many, names[0], names[1], names.size - 2)
    }
}

@Composable
fun progressLabel(progress: SocialProgress?): String = when {
    progress == null -> stringResource(MR.strings.social_progress_none)
    progress.finished -> stringResource(MR.strings.social_progress_finished)
    progress.read > 0 && progress.total > 0 -> stringResource(
        MR.strings.social_progress_chapter,
        progress.read,
        progress.total,
    )
    else -> stringResource(MR.strings.social_progress_started)
}

/** Stars as text; [onRate] makes each star tappable (1–5). */
@Composable
fun StarRating(rating: Int, modifier: Modifier = Modifier, size: Int = 16, onRate: ((Int) -> Unit)? = null) {
    val description = stringResource(MR.strings.social_rating_stars, rating)
    Row(modifier.semantics { if (onRate == null) contentDescription = description }) {
        for (star in 1..5) {
            Text(
                if (star <= rating) "★" else "☆",
                fontSize = size.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = if (onRate != null) {
                    Modifier
                        .clickable(role = Role.Button) { onRate(star) }
                        .semantics { contentDescription = "$star" }
                        .padding(4.dp)
                } else {
                    Modifier
                },
            )
        }
    }
}

@Composable
private fun FriendReadersSheet(
    readers: List<FriendReading>,
    loadMedia: ProfileMediaLoader,
    onOpenProfile: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(16.dp).heightIn(max = 640.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(MR.strings.social_title_friends_sheet), style = MaterialTheme.typography.titleMedium)
            readers.forEachIndexed { index, reader ->
                if (index > 0) HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenProfile(reader.profile.uid) }.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProfileAvatar(reader.profile, loadMedia, 40.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(reader.profile.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            progressLabel(reader.progress),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        reader.review?.let { review ->
                            StarRating(review.rating)
                            review.text?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
        }
    }
}

/** One of a friend's reviews on their profile: cover, title, stars and text. Tapping opens the title's actions. */
@Composable
fun FriendReviewRow(review: SocialReview, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MangaCover.Book(data = review.content.cover(), modifier = Modifier.width(56.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                review.content.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            StarRating(review.rating)
            review.text?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** Stars plus optional text; edits or deletes my existing review. Only friends see it (and only while shared). */
@Composable
private fun RatingDialog(
    existing: SocialReview?,
    saving: Boolean,
    onSave: (Int, String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var stars by rememberSaveable { mutableIntStateOf(existing?.rating ?: 0) }
    var text by rememberSaveable { mutableStateOf(existing?.text.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.social_rate_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StarRating(stars, size = 32, onRate = { stars = it })
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(SocialReview.MAX_TEXT) },
                    placeholder = { Text(stringResource(MR.strings.social_review_hint)) },
                    supportingText = { Text("${text.length}/${SocialReview.MAX_TEXT}") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(MR.strings.social_review_friends_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (existing != null) {
                    TextButton(enabled = !saving, onClick = {
                        onDelete()
                        onDismiss()
                    }) { Text(stringResource(MR.strings.social_review_delete)) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = stars in 1..5 && !saving, onClick = {
                onSave(stars, text)
                onDismiss()
            }) { Text(stringResource(MR.strings.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}

private const val MAX_AVATARS = 4
private const val AVATAR_SIZE = 28
private const val AVATAR_STEP = 18
