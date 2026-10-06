package mihon.feature.social

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.interactor.GetIncognitoState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.manga.repository.MangaRepository

/** The foreground process owns privacy validation, even when the Friends screen is closed. */
@Inject
@SingleIn(AppScope::class)
class SocialProjectionGuard(
    private val repository: SocialRepository,
    private val mangaRepository: MangaRepository,
    private val incognito: GetIncognitoState,
    private val preferences: PreferenceStore,
) {
    suspend fun observe() {
        repository.authChanges().collectLatest { account ->
            if (account == null) return@collectLatest
            repository.permitProjection(account.uid, false)
            val marker = preferences.getStringSet(
                Preference.appStateKey("social_incognito_fence_${SocialIdentity.key(account.uid)}"),
            )
            val fence = SourcePrivacyFence(marker.get())
            val observedSources = preferences.getStringSet(
                Preference.appStateKey("social_observed_sources_${SocialIdentity.key(account.uid)}"),
            )
            val history = ProjectionSourceHistory(observedSources.get())
            val privateSources = mangaRepository.getLibraryMangaAsFlow()
                .map { library ->
                    val sources = history.include(library.map { it.manga.source })
                    // Persist before permitting publication, including sources later removed locally.
                    if (observedSources.get() != history.retained) observedSources.set(history.retained)
                    sources.sorted()
                }
                .distinctUntilChanged()
                .flatMapLatest { sources ->
                    if (sources.isEmpty()) {
                        flowOf(emptySet())
                    } else {
                        combine(
                            sources.map { source ->
                                incognito.subscribe(source).map { enabled -> source.toString() to enabled }
                            },
                        ) { states -> states.filter { it.second }.map { it.first }.toSet() }
                    }
                }
            try {
                // Record a successful fence before consuming its generation update to avoid loops.
                combine(repository.watchSettings(account.uid), privateSources) { sharing, sources ->
                    sharing to sources
                }.collect { (sharing, sources) ->
                    repository.permitProjection(account.uid, false)
                    val requiresFence = fence.reconcile(sources)
                    marker.set(fence.handled)
                    if (requiresFence &&
                        (sharing.library == "friends" || sharing.favorites == "friends")
                    ) {
                        val current = repository.getSelf().settings
                        repository.updateSharing(current)
                        fence.acknowledge(sources)
                        marker.set(fence.handled)
                    }
                    repository.permitProjection(account.uid, true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A failed revocation stays blocked until a foreground revalidation succeeds.
                repository.permitProjection(account.uid, false)
            } finally {
                repository.permitProjection(account.uid, false)
            }
        }
    }
}
