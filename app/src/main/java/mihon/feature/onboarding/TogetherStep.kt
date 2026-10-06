package mihon.feature.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import eu.kanade.presentation.more.onboarding.OnboardingStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import mihon.feature.translate.LanguagePacks
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Check
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * First-run "Mihon Together" step (skippable, nothing is required): where friends and reading rooms live, the
 * notification permission, the on-device translation language packs, and where to switch on the Discord status.
 */
internal class TogetherStep : OnboardingStep {
    override val isComplete: Boolean = true

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        // Not tied to this step: the download keeps going when the reader moves on to the next step.
        val scope = ProcessLifecycleOwner.get().lifecycleScope
        var notificationsGranted by rememberSaveable { mutableStateOf(false) }
        var downloading by rememberSaveable { mutableStateOf(false) }
        var packReady by rememberSaveable { mutableStateOf(false) }
        var finished by rememberSaveable { mutableIntStateOf(0) }
        var total by rememberSaveable { mutableIntStateOf(0) }
        var failure by rememberSaveable { mutableStateOf<String?>(null) }

        DisposableEffect(lifecycleOwner.lifecycle) {
            val observer = object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) {
                    notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        // The onboarding screen scrolls the step itself; a scrolling column here crashed (infinite height).
        Column {
            Item(
                title = stringResource(MR.strings.onboarding_together_title),
                subtitle = null,
            )
            Item(
                title = stringResource(MR.strings.onboarding_together_friends),
                subtitle = stringResource(MR.strings.onboarding_together_friends_info),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val requester = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    notificationsGranted = it
                }
                Item(
                    title = stringResource(MR.strings.onboarding_together_notifications),
                    subtitle = stringResource(MR.strings.onboarding_together_notifications_info),
                    action = stringResource(MR.strings.onboarding_permission_action_grant),
                    done = notificationsGranted,
                    onAction = { requester.launch(Manifest.permission.POST_NOTIFICATIONS) },
                )
            }
            val packStatus = when {
                packReady -> stringResource(MR.strings.onboarding_together_pack_done)
                failure != null -> stringResource(MR.strings.onboarding_together_pack_failed, failure.orEmpty())
                downloading && total > 0 ->
                    stringResource(MR.strings.onboarding_together_pack_progress, minOf(finished + 1, total), total)
                else -> null
            }
            Item(
                title = stringResource(MR.strings.onboarding_together_pack),
                subtitle = listOfNotNull(stringResource(MR.strings.onboarding_together_pack_info), packStatus)
                    .joinToString("\n"),
                action = stringResource(MR.strings.onboarding_together_pack_download),
                done = packReady,
                enabled = !downloading,
                onAction = {
                    downloading = true
                    failure = null
                    scope.launch {
                        try {
                            LanguagePacks.download { done, count ->
                                finished = done
                                total = count
                            }
                            packReady = true
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            failure = error.message ?: error.javaClass.simpleName
                        } finally {
                            downloading = false
                        }
                    }
                },
            )
            Item(
                title = stringResource(MR.strings.onboarding_together_discord),
                subtitle = stringResource(MR.strings.onboarding_together_discord_info),
            )
        }
    }

    @Composable
    private fun Item(
        title: String,
        subtitle: String?,
        action: String? = null,
        done: Boolean = false,
        enabled: Boolean = true,
        onAction: () -> Unit = {},
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = subtitle?.let { { Text(it) } },
            trailingContent = action?.let {
                {
                    OutlinedButton(enabled = enabled && !done, onClick = onAction) {
                        if (done) {
                            Icon(
                                imageVector = MaterialSymbols.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text(it)
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}
