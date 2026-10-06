package eu.kanade.domain.base

import android.content.Context
import eu.kanade.domain.base.BasePreferences.ExtensionInstaller
import eu.kanade.tachiyomi.util.system.hasMiuiPackageInstaller
import eu.kanade.tachiyomi.util.system.isShizukuInstalled
import kotlinx.coroutines.CoroutineScope
import mihon.feature.update.TogetherUpdates
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

class ExtensionInstallerPreference(
    private val context: Context,
    preferenceStore: PreferenceStore,
) : Preference<ExtensionInstaller> {

    /**
     * Without the install permission (the online build) only Shizuku and private installs work. Declared before
     * [basePref], whose initializer reads it through [defaultValue] (the other order crashed build 8229 on start).
     */
    private val canInstall = TogetherUpdates.canInstallApks(context)

    private val basePref = preferenceStore.getEnum(key(), defaultValue())

    override fun key() = "extension_installer"

    val entries get() = ExtensionInstaller.entries.run {
        when {
            !canInstall -> filter { !it.requiresSystemPermission }
            context.hasMiuiPackageInstaller -> filter { it != ExtensionInstaller.PACKAGEINSTALLER }
            else -> toList()
        }
    }

    override fun defaultValue() = when {
        !canInstall -> ExtensionInstaller.PRIVATE
        context.hasMiuiPackageInstaller -> ExtensionInstaller.LEGACY
        else -> ExtensionInstaller.PACKAGEINSTALLER
    }

    private fun check(value: ExtensionInstaller): ExtensionInstaller {
        if (!canInstall && value.requiresSystemPermission) return ExtensionInstaller.PRIVATE
        when (value) {
            ExtensionInstaller.PACKAGEINSTALLER -> {
                if (context.hasMiuiPackageInstaller) return ExtensionInstaller.LEGACY
            }
            ExtensionInstaller.SHIZUKU -> {
                if (!context.isShizukuInstalled) return defaultValue()
            }
            else -> {}
        }
        return value
    }

    override fun get(): ExtensionInstaller {
        val value = basePref.get()
        val checkedValue = check(value)
        if (value != checkedValue) {
            basePref.set(checkedValue)
        }
        return checkedValue
    }

    override fun set(value: ExtensionInstaller) {
        basePref.set(check(value))
    }

    override fun isSet() = basePref.isSet()

    override fun delete() = basePref.delete()

    override fun changes() = basePref.changes()

    override fun stateIn(scope: CoroutineScope) = basePref.stateIn(scope)
}
