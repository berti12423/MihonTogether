package mihon.feature.translate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** Engine and its key, model, original language and SFX. Saving applies to the next page translated. */
@Composable
fun TranslationSettingsDialog(
    initial: TranslationSettings,
    onSave: (TranslationSettings) -> Unit,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Pairing and status for "My PC (Koharu)", shown while that engine is chosen. */
    pcSection: @Composable () -> Unit = {},
) {
    var engine by remember { mutableStateOf(initial.engine) }
    var key by remember { mutableStateOf(initial.apiKey) }
    var deeplKey by remember { mutableStateOf(initial.deeplKey) }
    var papagoId by remember { mutableStateOf(initial.papagoId) }
    var papagoSecret by remember { mutableStateOf(initial.papagoSecret) }
    var model by remember { mutableStateOf(initial.model) }
    var source by remember { mutableStateOf(initial.sourceLanguage.orEmpty()) }
    var sfx by remember { mutableStateOf(initial.translateSfx) }
    var mobileData by remember { mutableStateOf(initial.allowMobileData) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.translate_settings_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(MR.strings.translate_engine), style = MaterialTheme.typography.titleSmall)
                TranslationPreferences.ENGINES.zip(ENGINE_LABELS).forEach { (value, label) ->
                    Choice(stringResource(label), selected = engine == value) { engine = value }
                }
                Text(
                    stringResource(ENGINE_INFO.getValue(engine)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                when (engine) {
                    TranslationPreferences.ENGINE_ON_DEVICE -> {
                        Row(
                            Modifier.fillMaxWidth().toggleable(
                                value = mobileData,
                                role = Role.Switch,
                                onValueChange = { mobileData = it },
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(MR.strings.translate_mobile_data), Modifier.weight(1f))
                            Switch(checked = mobileData, onCheckedChange = null)
                        }
                    }
                    TranslationPreferences.ENGINE_DEEPL -> {
                        KeyField(deeplKey, { deeplKey = it }, stringResource(MR.strings.translate_deepl_key))
                        TextButton(onClick = { onOpenUrl(DEEPL_KEY_URL) }) {
                            Text(stringResource(MR.strings.translate_deepl_get))
                        }
                    }
                    TranslationPreferences.ENGINE_PAPAGO -> {
                        KeyField(papagoId, { papagoId = it }, stringResource(MR.strings.translate_papago_id))
                        KeyField(papagoSecret, {
                            papagoSecret = it
                        }, stringResource(MR.strings.translate_papago_secret))
                        TextButton(onClick = { onOpenUrl(PAPAGO_KEY_URL) }) {
                            Text(stringResource(MR.strings.translate_papago_get))
                        }
                    }
                    TranslationPreferences.ENGINE_PC -> pcSection()
                    TranslationPreferences.ENGINE_GEMINI -> {
                        KeyField(key, { key = it }, stringResource(MR.strings.translate_key_label))
                        TextButton(onClick = { onOpenUrl(GEMINI_KEY_URL) }) {
                            Text(stringResource(MR.strings.translate_key_get))
                        }
                        Text(stringResource(MR.strings.translate_model), style = MaterialTheme.typography.titleSmall)
                        TranslationPreferences.MODELS.zip(MODEL_LABELS).forEach { (value, label) ->
                            Choice(stringResource(label), selected = model == value) { model = value }
                        }
                    }
                }

                // The PC translator detects the language itself.
                if (engine != TranslationPreferences.ENGINE_PC) {
                    Text(stringResource(MR.strings.translate_source), style = MaterialTheme.typography.titleSmall)
                    TranslationPreferences.SOURCE_LANGUAGES.zip(SOURCE_LABELS).forEach { (value, label) ->
                        Choice(stringResource(label), selected = source == value) { source = value }
                    }
                }

                // Only the vision model can tell sound effects from dialogue.
                if (engine == TranslationPreferences.ENGINE_GEMINI) {
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = sfx, role = Role.Switch, onValueChange = {
                            sfx = it
                        }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(MR.strings.translate_sfx), Modifier.weight(1f))
                        Switch(checked = sfx, onCheckedChange = null)
                    }
                    Text(
                        stringResource(MR.strings.translate_privacy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        initial.copy(
                            engine = engine,
                            apiKey = key,
                            deeplKey = deeplKey,
                            papagoId = papagoId,
                            papagoSecret = papagoSecret,
                            model = model,
                            sourceLanguage = source.ifBlank { null },
                            translateSfx = sfx,
                            allowMobileData = mobileData,
                        ),
                    )
                },
            ) { Text(stringResource(MR.strings.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}

/** A hidden key field with a Show button. */
@Composable
private fun KeyField(value: String, onValue: (String) -> Unit, label: String) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.trim()) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            TextButton(onClick = { show = !show }) { Text(stringResource(MR.strings.translate_key_show)) }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

/** A small pill over the reader while a page is being translated, or when translation stopped for a reason. */
@Composable
fun TranslationStatus(state: ReaderTranslation.State, modifier: Modifier = Modifier, onRetry: () -> Unit = {}) {
    val problem = state.problem
    if (!state.working && problem == null) return
    val setupProblem = problem == ReaderTranslation.Problem.NO_KEY || problem == ReaderTranslation.Problem.PC_UNPAIRED
    if (!state.enabled && !setupProblem) return
    Surface(
        modifier = modifier.widthIn(max = 360.dp).padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (problem ==
            null
        ) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        shadowElevation = 4.dp,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (problem == null) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    if (state.total > 0) {
                        stringResource(MR.strings.translate_status_progress, state.done, state.total)
                    } else {
                        stringResource(MR.strings.translate_status_working)
                    },
                    Modifier.padding(start = 8.dp),
                )
            } else {
                Text(
                    stringResource(problemText(problem)),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (problem.retryable && state.enabled) {
                    TextButton(onClick = onRetry) { Text(stringResource(MR.strings.action_retry)) }
                }
            }
        }
    }
}

private fun problemText(problem: ReaderTranslation.Problem): StringResource = when (problem) {
    ReaderTranslation.Problem.NO_KEY -> MR.strings.translate_error_no_key
    ReaderTranslation.Problem.INVALID_KEY -> MR.strings.translate_error_invalid_key
    ReaderTranslation.Problem.QUOTA -> MR.strings.translate_error_quota
    ReaderTranslation.Problem.NEEDS_WIFI -> MR.strings.translate_error_needs_wifi
    ReaderTranslation.Problem.UNAVAILABLE -> MR.strings.translate_error_unavailable
    ReaderTranslation.Problem.FAILED -> MR.strings.translate_error_failed
    ReaderTranslation.Problem.PC_UNREACHABLE -> MR.strings.translate_pc_error_unreachable
    ReaderTranslation.Problem.PC_LOADING -> MR.strings.translate_pc_error_loading
    ReaderTranslation.Problem.PC_UNPAIRED -> MR.strings.translate_pc_error_unpaired
}

private val MODEL_LABELS = listOf(
    MR.strings.translate_model_lite,
    MR.strings.translate_model_lite_35,
    MR.strings.translate_model_flash,
)

private val ENGINE_LABELS = listOf(
    MR.strings.translate_engine_on_device,
    MR.strings.translate_engine_deepl,
    MR.strings.translate_engine_papago,
    MR.strings.translate_engine_gemini,
    MR.strings.translate_engine_pc,
)

private val ENGINE_INFO = mapOf(
    TranslationPreferences.ENGINE_ON_DEVICE to MR.strings.translate_engine_on_device_info,
    TranslationPreferences.ENGINE_DEEPL to MR.strings.translate_engine_deepl_info,
    TranslationPreferences.ENGINE_PAPAGO to MR.strings.translate_engine_papago_info,
    TranslationPreferences.ENGINE_GEMINI to MR.strings.translate_engine_gemini_info,
    TranslationPreferences.ENGINE_PC to MR.strings.translate_engine_pc_info,
)

private val SOURCE_LABELS = listOf(
    MR.strings.translate_source_auto,
    MR.strings.translate_lang_ko,
    MR.strings.translate_lang_ja,
    MR.strings.translate_lang_zh,
)

/** Where users create keys. */
const val GEMINI_KEY_URL = "https://aistudio.google.com/apikey"
const val DEEPL_KEY_URL = "https://www.deepl.com/pro-api"
const val PAPAGO_KEY_URL = "https://www.ncloud.com/product/aiService/papagoTranslation"
