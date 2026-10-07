package eu.kanade.presentation.more.settings.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import kotlinx.coroutines.flow.drop
import mihon.app.di.appGraph
import mihon.feature.discord.DiscordPresenceFactory
import mihon.feature.discord.DiscordStatus
import mihon.feature.discord.PresenceInfo
import mihon.feature.discord.SocialSdkDiscordPresence
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/** Discord Rich Presence: off by default, and only for sources the user picks (docs/DISCORD_PRESENCE_PLAN.md). */
object SettingsDiscordScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_discord

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val preferences = remember { context.appGraph.discordPreferences }
        val available = remember { DiscordPresenceFactory.isAvailable() }
        val enabled by preferences.enabled.collectAsState()
        val status by SocialSdkDiscordPresence.status.collectAsState()
        // Privacy: switching off or narrowing the allowlist also drops a status left over from the reader.
        LaunchedEffect(Unit) {
            preferences.enabled.changes().drop(1).collect { if (!it) DiscordPresenceFactory.create().clear() }
        }
        LaunchedEffect(Unit) {
            preferences.allowedSources.changes().drop(1).collect { DiscordPresenceFactory.create().clear() }
        }
        val testTitle = stringResource(MR.strings.pref_discord_test_title)
        val testState = stringResource(MR.strings.pref_discord_test_state)
        val sources by produceState(emptyMap<String, String>()) {
            // Same sources as Browse: enabled languages, not hidden. Already-allowed ones stay listed so they can be
            // switched off again.
            val sourcePreferences = context.appGraph.sourcePreferences
            val languages = sourcePreferences.enabledLanguages.get()
            val hidden = sourcePreferences.disabledSources.get()
            val allowed = preferences.allowedSources.get()
            value = context.appGraph.sourceManager.getAll()
                .filter { source ->
                    val id = source.id.toString()
                    id in allowed || (id !in hidden && (source.lang in languages || source.lang == "other"))
                }
                .sortedBy { it.name.lowercase() }
                .associate { it.id.toString() to "${it.name} (${it.lang.uppercase()})" }
        }
        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_discord),
                preferenceItems = buildList {
                    if (!available) {
                        add(
                            Preference.PreferenceItem.InfoPreference(
                                stringResource(MR.strings.pref_discord_unavailable),
                            ),
                        )
                    }
                    add(
                        Preference.PreferenceItem.SwitchPreference(
                            preference = preferences.enabled,
                            title = stringResource(MR.strings.pref_discord_enabled),
                            subtitle = stringResource(MR.strings.pref_discord_enabled_summary),
                            // Turning it off also removes the status already on Discord (it stays there otherwise).
                            onValueChanged = { on ->
                                if (!on) {
                                    context.findActivity()?.let {
                                        SocialSdkDiscordPresence.clearIfLingering(it, force = true)
                                    }
                                }
                                true
                            },
                        ),
                    )
                    add(
                        Preference.PreferenceItem.MultiSelectListPreference(
                            preference = preferences.allowedSources,
                            entries = sources,
                            title = stringResource(MR.strings.pref_discord_sources),
                            subtitle = stringResource(MR.strings.pref_discord_sources_summary),
                            enabled = enabled,
                        ),
                    )
                    add(
                        Preference.PreferenceItem.SwitchPreference(
                            preference = preferences.showCover,
                            title = stringResource(MR.strings.pref_discord_cover),
                            enabled = enabled,
                        ),
                    )
                    add(
                        Preference.PreferenceItem.SwitchPreference(
                            preference = preferences.showChapter,
                            title = stringResource(MR.strings.pref_discord_chapter),
                            enabled = enabled,
                        ),
                    )
                    add(
                        Preference.PreferenceItem.SwitchPreference(
                            preference = preferences.watching,
                            title = stringResource(MR.strings.pref_discord_watching),
                            subtitle = stringResource(MR.strings.pref_discord_watching_summary),
                            enabled = enabled,
                        ),
                    )
                    add(
                        Preference.PreferenceItem.SwitchPreference(
                            preference = preferences.showLevel,
                            title = stringResource(MR.strings.pref_discord_level),
                            subtitle = stringResource(MR.strings.pref_discord_level_summary),
                            enabled = enabled,
                        ),
                    )
                    add(
                        Preference.PreferenceItem.SwitchPreference(
                            preference = preferences.showButton,
                            title = stringResource(MR.strings.pref_discord_button),
                            subtitle = stringResource(MR.strings.pref_discord_button_summary),
                            enabled = enabled,
                        ),
                    )
                    if (available) {
                        add(
                            Preference.PreferenceItem.TextPreference(
                                title = stringResource(MR.strings.pref_discord_test),
                                subtitle = statusText(status),
                                onClick = {
                                    context.findActivity()?.let { activity ->
                                        SocialSdkDiscordPresence.sendTest(
                                            activity,
                                            PresenceInfo(
                                                testTitle,
                                                testState,
                                                null,
                                                System.currentTimeMillis(),
                                                logoUrl = eu.kanade.tachiyomi.BuildConfig.SOCIAL_WEB_URL.trimEnd('/')
                                                    .takeIf { it.startsWith("https://") }
                                                    ?.let { "$it/discord-logo.png" },
                                            ),
                                        )
                                    }
                                },
                            ),
                        )
                    }
                    add(Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_discord_grace)))
                    add(Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_discord_private)))
                },
            ),
        )
    }

    @Composable
    private fun statusText(status: DiscordStatus): String = when (status) {
        DiscordStatus.Idle -> stringResource(MR.strings.pref_discord_status_idle)
        DiscordStatus.Waiting -> stringResource(MR.strings.pref_discord_status_waiting)
        DiscordStatus.Shown -> stringResource(MR.strings.pref_discord_status_shown)
        DiscordStatus.Cleared -> stringResource(MR.strings.pref_discord_status_cleared)
        is DiscordStatus.Failed -> stringResource(MR.strings.pref_discord_status_failed, status.message)
        DiscordStatus.Reconnecting -> stringResource(MR.strings.pref_discord_status_reconnecting)
        DiscordStatus.NoDiscordApp -> stringResource(MR.strings.pref_discord_status_no_app)
        DiscordStatus.DiscordTooOld -> stringResource(MR.strings.pref_discord_status_too_old)
        is DiscordStatus.Unavailable -> stringResource(MR.strings.pref_discord_status_unavailable, status.message)
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
