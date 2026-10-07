package mihon.feature.social

import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.history.interactor.GetReadingLog
import tachiyomi.domain.history.interactor.GetTotalReadDuration
import java.time.Instant
import java.time.ZoneId

data class SocialStatsState(
    /** Signed in with the social backend (otherwise only the phone's stats show). */
    val signedIn: Boolean = false,
    val xp: SocialXp? = null,
    val local: LocalReadingStats? = null,
)

/** "My stats": my server-side XP doc live, plus stats computed from this phone's history (never uploaded). */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class SocialStatsViewModel(
    private val repository: SocialRepository,
    private val getReadingLog: GetReadingLog,
    private val getTotalReadDuration: GetTotalReadDuration,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SocialStatsState())
    val state = mutableState.asStateFlow()

    // Started by the screen's lifecycle effect; cancelled when it leaves.
    suspend fun observe() = coroutineScope {
        launch { loadLocal() }
        if (!repository.enabled) return@coroutineScope
        repository.authChanges().collectLatest { account ->
            mutableState.update { it.copy(signedIn = account != null, xp = null) }
            if (account == null) return@collectLatest
            repository.watchXp(account.uid)
                .catch { error ->
                    if (error is CancellationException) throw error
                    emit(null)
                }
                .collect { xp -> mutableState.update { it.copy(xp = xp) } }
        }
    }

    private suspend fun loadLocal() {
        val local = withContext(Dispatchers.IO) {
            LocalReadingStats.compute(
                getReadingLog.await(),
                getTotalReadDuration.await(),
                Instant.now(),
                ZoneId.systemDefault(),
            )
        }
        mutableState.update { it.copy(local = local) }
    }
}
