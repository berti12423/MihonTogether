package mihon.gradle

import org.gradle.api.Project

interface BuildConfig {
    val includeTelemetry: Boolean
    val includeSocial: Boolean
    val uploadCrashlyticsMapping: Boolean
    val enableUpdater: Boolean
    val includeDependencyInfo: Boolean
}

private enum class Distribution {
    LOCAL,
    CI,
    GITHUB,
    FOSS,
}

val Project.Config: BuildConfig get() = object : BuildConfig {
    private val distribution: Distribution = project.providers.gradleProperty("dist").orNull
        ?.let { name ->
            Distribution.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: error("Unknown -Pdist=$name, expected one of $distributionNames")
        }
        ?: Distribution.LOCAL

    // Upstream telemetry only accepts Mihon's signing identity and project. Social Firebase
    // uses its own named FirebaseApp and must never initialize that configuration.
    override val includeTelemetry: Boolean = false.also {
        require(project.flag("include-telemetry") != true) {
            "Telemetry is disabled in this fork. Implement fork-owned telemetry before enabling it."
        }
    }

    override val includeSocial: Boolean = project.flag("include-social") ?: true

    override val uploadCrashlyticsMapping: Boolean = includeTelemetry && (distribution == Distribution.GITHUB)

    override val enableUpdater: Boolean = false.also {
        require(project.flag("enable-updater") != true) {
            "The upstream updater is disabled. Configure a fork-owned updater before enabling it."
        }
    }

    override val includeDependencyInfo: Boolean = project.flag("include-dependency-info") ?: false
}

private val distributionNames = Distribution.entries.joinToString { it.name.lowercase() }

private fun Project.flag(name: String): Boolean? = providers.gradleProperty(name).orNull
    ?.let { it.isEmpty() || it.toBoolean() }
