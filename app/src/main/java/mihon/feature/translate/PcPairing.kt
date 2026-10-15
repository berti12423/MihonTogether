package mihon.feature.translate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** The PC this phone is paired with. */
data class PairedPc(val name: String, val address: PcAddress)

/** What the paired PC's translator is doing, for the settings screen. */
data class PcStatus(val kind: Kind, val detail: String? = null) {
    enum class Kind { READY, LOADING, UNREACHABLE, UNPAIRED, ERROR }
}

sealed interface PcPairState {
    data object Idle : PcPairState
    data object Working : PcPairState
    data class Failed(val reason: Reason) : PcPairState {
        enum class Reason { BAD_CODE, LOCKED, UNREACHABLE, FAILED }
    }
}

/**
 * Pairing with the Mihon Together Translator on the user's PC: discovery, the 6-digit code, the paired PC's status
 * and unpairing. Owned by the reader's view model ([scope]); a pairing is stored the moment the PC grants it, since
 * the PC already holds the token then.
 */
class PcPairingController(
    private val scope: CoroutineScope,
    private val translator: PcTranslator,
    private val discovery: PcDiscovery?,
    private val preferences: TranslationPreferences,
    private val deviceName: () -> String,
) {
    private val mutablePaired = MutableStateFlow(readPaired())
    val paired: StateFlow<PairedPc?> = mutablePaired.asStateFlow()

    private val mutablePairState = MutableStateFlow<PcPairState>(PcPairState.Idle)
    val pairState: StateFlow<PcPairState> = mutablePairState.asStateFlow()

    fun discover(): Flow<List<FoundPc>> = discovery?.discover() ?: emptyFlow()

    fun pair(address: PcAddress, code: String) {
        if (mutablePairState.value == PcPairState.Working) return
        mutablePairState.value = PcPairState.Working
        scope.launch {
            mutablePairState.value = try {
                when (val result = translator.pair(address, code, deviceName())) {
                    is PcPairResult.Paired -> {
                        preferences.savePc(address, result.name, result.token)
                        mutablePaired.value = readPaired()
                        PcPairState.Idle
                    }
                    PcPairResult.BadCode -> PcPairState.Failed(PcPairState.Failed.Reason.BAD_CODE)
                    PcPairResult.Locked -> PcPairState.Failed(PcPairState.Failed.Reason.LOCKED)
                }
            } catch (error: CancellationException) {
                mutablePairState.value = PcPairState.Idle
                throw error
            } catch (error: TranslationException) {
                logcat(LogPriority.WARN, error) { "PC pairing failed" }
                val reason = if (error is TranslationException.Unavailable && error.code == PcTranslator.UNREACHABLE) {
                    PcPairState.Failed.Reason.UNREACHABLE
                } else {
                    PcPairState.Failed.Reason.FAILED
                }
                PcPairState.Failed(reason)
            }
        }
    }

    fun clearPairError() {
        if (mutablePairState.value is PcPairState.Failed) mutablePairState.value = PcPairState.Idle
    }

    /** Forgets the PC here at once and tells the PC too, when it can be reached. */
    fun unpair() {
        val settings = preferences.settings()
        val address = PcAddress.parse(settings.pcAddress)
        val token = settings.pcToken
        preferences.clearPc()
        mutablePaired.value = null
        if (address != null && token.isNotBlank()) scope.launch { translator.unpair(address, token) }
    }

    /**
     * Asks the paired PC how it is. When it can't be reached, looks for it by name on this Wi-Fi (its IP may have
     * changed) and remembers the new address. Null when not paired.
     */
    suspend fun status(): PcStatus? {
        val pc = readPaired() ?: return null
        val token = preferences.pcToken.get()
        return try {
            check(pc.address, token)
        } catch (error: CancellationException) {
            throw error
        } catch (error: TranslationException) {
            val moved = (error as? TranslationException.Unavailable)?.takeIf { it.code == PcTranslator.UNREACHABLE }
                ?.let { discovery?.find(pc.name) }
                ?.takeIf { it != pc.address }
                ?: return PcStatus(PcStatus.Kind.UNREACHABLE)
            relocated(moved)
            try {
                check(moved, token)
            } catch (_: TranslationException) {
                PcStatus(PcStatus.Kind.UNREACHABLE)
            }
        }
    }

    /** The engine found the PC at a new address. */
    fun relocated(address: PcAddress) {
        preferences.pcAddress.set(address.toString())
        mutablePaired.value = readPaired()
    }

    private suspend fun check(address: PcAddress, token: String): PcStatus {
        val health = translator.health(address, token)
        return when {
            health.paired == false -> PcStatus(PcStatus.Kind.UNPAIRED)
            health.ready -> PcStatus(PcStatus.Kind.READY)
            health.loading -> PcStatus(PcStatus.Kind.LOADING, health.progressLabel)
            else -> PcStatus(PcStatus.Kind.ERROR, health.error)
        }
    }

    private fun readPaired(): PairedPc? {
        val settings = preferences.settings()
        val address = PcAddress.parse(settings.pcAddress) ?: return null
        if (settings.pcToken.isBlank()) return null
        return PairedPc(settings.pcName.ifBlank { address.host }, address)
    }
}

/**
 * The "My PC (Koharu)" part of Translation settings, wired to [controller]: status checks while visible, discovery
 * while the pairing dialog is open. Not paired yet, the pairing dialog opens right away.
 */
@Composable
fun PcTranslatorSettings(controller: PcPairingController) {
    val paired by controller.paired.collectAsState()
    val pairState by controller.pairState.collectAsState()
    var showPairing by rememberSaveable { mutableStateOf(controller.paired.value == null) }
    val status by produceState<PcStatus?>(initialValue = null, paired) {
        value = null
        if (paired == null) return@produceState
        while (true) {
            value = controller.status()
            delay(STATUS_REFRESH_MS)
        }
    }
    // A finished pairing closes the dialog.
    LaunchedEffect(paired) { if (paired != null) showPairing = false }

    PcTranslatorSection(
        paired = paired,
        status = status,
        onPair = {
            controller.clearPairError()
            showPairing = true
        },
        onUnpair = controller::unpair,
    )
    if (showPairing) {
        val found by produceState(emptyList<FoundPc>()) { controller.discover().collect { value = it } }
        PcPairingDialog(
            found = found,
            pairState = pairState,
            onPair = controller::pair,
            onEdit = controller::clearPairError,
            onDismiss = { showPairing = false },
        )
    }
}

/** Paired PC and its status with Unpair, or a Pair button; plus how to get the translator. */
@Composable
fun PcTranslatorSection(paired: PairedPc?, status: PcStatus?, onPair: () -> Unit, onUnpair: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (paired == null) {
            OutlinedButton(onClick = onPair, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(MR.strings.translate_pc_pair))
            }
        } else {
            Text(
                stringResource(MR.strings.translate_pc_paired_with, paired.name),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                paired.address.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(statusText(status), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status?.kind == PcStatus.Kind.UNPAIRED) {
                    OutlinedButton(onClick = onPair) { Text(stringResource(MR.strings.translate_pc_pair)) }
                }
                OutlinedButton(onClick = onUnpair) { Text(stringResource(MR.strings.translate_pc_unpair)) }
            }
        }
        Text(
            stringResource(MR.strings.translate_pc_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun statusText(status: PcStatus?): String = when (status?.kind) {
    null -> stringResource(MR.strings.translate_pc_status_checking)
    PcStatus.Kind.READY -> stringResource(MR.strings.translate_pc_status_ready)
    PcStatus.Kind.LOADING -> status.detail?.let { stringResource(MR.strings.translate_pc_status_loading_detail, it) }
        ?: stringResource(MR.strings.translate_pc_status_loading)
    PcStatus.Kind.UNREACHABLE -> stringResource(MR.strings.translate_pc_status_unreachable)
    PcStatus.Kind.UNPAIRED -> stringResource(MR.strings.translate_pc_status_unpaired)
    PcStatus.Kind.ERROR -> stringResource(MR.strings.translate_pc_status_error, status.detail.orEmpty())
}

/** PCs found on this Wi-Fi or a typed address, the 6-digit code, and Pair. */
@Composable
fun PcPairingDialog(
    found: List<FoundPc>,
    pairState: PcPairState,
    onPair: (PcAddress, String) -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    // The only PC found is picked for the user.
    val autoPick = remember(found) { found.singleOrNull()?.serviceName }
    val address = if (manual || found.isEmpty()) {
        PcAddress.parse(typed)
    } else {
        found.firstOrNull { it.serviceName == (selected ?: autoPick) }?.address
    }
    val working = pairState == PcPairState.Working
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.translate_pc_pair_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(MR.strings.translate_pc_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(MR.strings.translate_pc_found), style = MaterialTheme.typography.titleSmall)
                if (found.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(MR.strings.translate_pc_searching),
                            Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                found.forEach { pc ->
                    val isSelected = !manual && (selected ?: autoPick) == pc.serviceName
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = isSelected, role = Role.RadioButton) {
                            selected = pc.serviceName
                            manual = false
                            onEdit()
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(pc.name)
                            Text(
                                pc.address.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (found.isNotEmpty() && !manual) {
                    TextButton(onClick = { manual = true }) { Text(stringResource(MR.strings.translate_pc_manual)) }
                }
                if (manual || found.isEmpty()) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = {
                            typed = it.trim()
                            onEdit()
                        },
                        label = { Text(stringResource(MR.strings.translate_pc_address)) },
                        singleLine = true,
                        isError = typed.isNotEmpty() && PcAddress.parse(typed) == null,
                        supportingText = if (typed.isNotEmpty() && PcAddress.parse(typed) == null) {
                            { Text(stringResource(MR.strings.translate_pc_address_invalid)) }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { value ->
                        code = value.filter(Char::isDigit).take(CODE_LENGTH)
                        onEdit()
                    },
                    label = { Text(stringResource(MR.strings.translate_pc_code)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                when (pairState) {
                    PcPairState.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(stringResource(MR.strings.translate_pc_pairing), Modifier.padding(start = 8.dp))
                    }
                    is PcPairState.Failed -> Text(
                        stringResource(
                            when (pairState.reason) {
                                PcPairState.Failed.Reason.BAD_CODE -> MR.strings.translate_pc_bad_code
                                PcPairState.Failed.Reason.LOCKED -> MR.strings.translate_pc_locked
                                PcPairState.Failed.Reason.UNREACHABLE -> MR.strings.translate_pc_pair_unreachable
                                PcPairState.Failed.Reason.FAILED -> MR.strings.translate_pc_pair_failed
                            },
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    PcPairState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working && address != null && code.length == CODE_LENGTH,
                onClick = { address?.let { onPair(it, code) } },
            ) { Text(stringResource(MR.strings.translate_pc_pair_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}

private const val CODE_LENGTH = 6
private const val STATUS_REFRESH_MS = 4_000L
