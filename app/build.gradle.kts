import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.BuildConfigField
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import mihon.gradle.Config
import mihon.gradle.getCurrentTime
import mihon.gradle.getLatestCommitCount
import mihon.gradle.getLatestCommitSha
import mihon.gradle.getLatestCommitTime
import mihon.gradle.tasks.ReplaceShortcutsPlaceholderTask
import java.io.FileInputStream
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(mihonx.plugins.android.application)
    alias(mihonx.plugins.compose)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.metro)
    alias(libs.plugins.aboutLibraries)
    alias(libs.plugins.androidx.baselineProfile)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.apollo)
}

if (Config.includeTelemetry) {
    pluginManager.apply {
        apply(libs.plugins.google.services.get().pluginId)
        apply(libs.plugins.firebase.crashlytics.get().pluginId)
    }
}

val keystorePropertiesFile = layout.settingsDirectory.file("keystore.properties").asFile

fun socialProperty(name: String): String? = providers.gradleProperty("social-$name").orNull?.trim()
    ?.takeIf { it.isNotEmpty() }

/** Discord Application ID (public): -Pdiscord-app-id, else `mihon.discordAppId` in local.properties, else none. */
fun discordAppId(): String = (
    providers.gradleProperty("discord-app-id").orNull
        ?: providers.fileContents(layout.projectDirectory.file("../local.properties")).asText.orNull
            ?.lineSequence()?.firstOrNull { it.trim().startsWith("mihon.discordAppId=") }?.substringAfter("=")
    )?.trim()?.filter(Char::isDigit).orEmpty()

fun javaString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

fun socialEmulatorPort(name: String, default: Int): Int = socialProperty("$name-emulator-port")
    ?.let { port ->
        port.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: error("-Psocial-$name-emulator-port must be a port between 1 and 65535")
    }
    ?: default

val socialEnvironmentOverride = socialProperty("environment")?.lowercase()?.also {
    require(it in setOf("disabled", "emulator", "production")) {
        "-Psocial-environment must be disabled, emulator, or production"
    }
}

/** The proprietary Discord Social SDK, if the developer downloaded it (git-ignored, docs/DISCORD_PRESENCE_PLAN.md). */
val discordSdkAar: File? = layout.settingsDirectory.dir("third_party/discord-social-sdk").asFile
    .takeIf { it.isDirectory }
    ?.walkTopDown()
    ?.firstOrNull { it.name == "discord_partner_sdk.aar" && it.parentFile?.name == "release" }

/** That AAR unpacked once under build/, for the CMake bridge (headers and per-ABI libraries). */
val discordSdkDir: File? = discordSdkAar?.let { aar ->
    val dir = layout.buildDirectory.dir("discord-sdk").get().asFile
    val marker = File(dir, ".unpacked-${aar.length()}-${aar.lastModified()}")
    if (!marker.isFile) {
        dir.deleteRecursively()
        ZipFile(aar).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                val target = File(dir, entry.name).canonicalFile
                check(target.path.startsWith(dir.canonicalPath)) { "Bad entry ${entry.name}" }
                target.parentFile.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().use(input::copyTo) }
            }
        }
        marker.writeText("")
    }
    dir
}

android {
    namespace = "eu.kanade.tachiyomi"

    if (discordSdkDir != null) {
        ndkVersion = "27.3.13750724"
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.31.6"
            }
        }
    }

    defaultConfig {
        applicationId = "dev.mihontogether.reader"

        versionCode = 33
        versionName = "0.20.4"

        buildConfigField("boolean", "TELEMETRY_INCLUDED", "${Config.includeTelemetry}")
        buildConfigField("boolean", "UPDATER_ENABLED", "${Config.enableUpdater}")
        buildConfigField("boolean", "SOCIAL_INCLUDED", "${Config.includeSocial}")
        buildConfigField("boolean", "DISCORD_SDK", "${discordSdkDir != null}")
        if (discordSdkDir != null) {
            externalNativeBuild {
                cmake { arguments += "-DDISCORD_SDK_DIR=${discordSdkDir.invariantSeparatorsPath}" }
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (System.getenv("MIHON_GITHUB_RELEASE").toBoolean()) {
        signingConfigs {
            named("debug") {
                storeFile = file(System.getenv("storeFile"))
                storePassword = System.getenv("storePassword")
                keyAlias = System.getenv("keyAlias")
                keyPassword = System.getenv("keyPassword")
            }
        }
    } else if (keystorePropertiesFile.exists()) {
        val keystoreProperties = FileInputStream(keystorePropertiesFile).use { Properties().apply { load(it) } }

        signingConfigs {
            named("debug") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        val debug = getByName("debug") {
            applicationIdSuffix = ".dev"
            isPseudoLocalesEnabled = true
        }
        val release = getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true

            signingConfig = debug.signingConfig

            isProfileable = true

            proguardFiles("proguard-android-optimize.txt", "proguard-rules.pro")
        }

        val commonMatchingFallbacks = listOf(release.name)

        create("foss") {
            initWith(release)

            applicationIdSuffix = ".foss"

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
        create("nightly") {
            initWith(release)

            applicationIdSuffix = ".debug"

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
        // Mihon Together test builds for friends: release-sized (R8 minify, resource shrinking) but with the debug
        // build's app ID, name, icon and signing key, so they update the existing "Mihon Together Dev" install.
        create("preview") {
            initWith(release)

            applicationIdSuffix = ".dev"

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
        create("benchmark") {
            initWith(release)

            versionNameSuffix = "-benchmark"
            applicationIdSuffix = ".benchmark"

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }

        if (Config.includeTelemetry) {
            configureEach {
                configure<CrashlyticsExtension> {
                    mappingFileUploadEnabled = Config.uploadCrashlyticsMapping
                }
            }
        }
    }

    sourceSets {
        getByName("nightly").res.directories.add("src/debug/res")
        getByName("preview").res.directories.add("src/debug/res")
        getByName("benchmark").res.directories.add("src/debug/res")
    }

    splits {
        abi {
            isEnable = true
            isUniversalApk = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    packaging {
        jniLibs {
            // Compress native libraries in the APK (translation, OCR and Discord add ~40 MB uncompressed); Android
            // extracts them on install. Halves the download at the cost of install-time extraction.
            useLegacyPackaging = true
            keepDebugSymbols += listOf(
                "libandroidx.graphics.path",
                "libarchive-jni",
                "libconscrypt_jni",
                "libimagedecoder",
                "libquickjs",
                "libsqlite3x",
            )
                .map { "**/$it.so" }
        }
        resources {
            excludes += setOf(
                "kotlin-tooling-metadata.json",
                "LICENSE.txt",
                "META-INF/**/*.properties",
                "META-INF/**/LICENSE.txt",
                "META-INF/*.properties",
                "META-INF/*.version",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
                "META-INF/README.md",
            )
        }
    }

    dependenciesInfo {
        includeInApk = Config.includeDependencyInfo
        includeInBundle = Config.includeDependencyInfo
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=coil3.annotation.ExperimentalCoilApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlinx.coroutines.InternalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

baselineProfile {
    baselineProfileOutputDir = "baselineProfiles"
    mergeIntoMain = true
}

dependencies {
    baselineProfile(projects.baselineProfile)

    // Discord Rich Presence, only when the developer downloaded the SDK (see discordSdkAar above).
    discordSdkAar?.let { implementation(files(it)) }

    implementation(projects.i18n)
    implementation(projects.icons.materialSymbols)
    implementation(projects.icons.simpleIcons)
    implementation(projects.core.archive)
    implementation(projects.core.common)
    implementation(projects.core.metro)
    implementation(projects.coreMetadata)
    implementation(projects.sourceApi)
    implementation(projects.sourceLocal)
    implementation(projects.data)
    implementation(projects.domain)
    implementation(projects.presentationCore)
    implementation(projects.presentationWidget)
    implementation(projects.telemetry)

    // Social uses a named FirebaseApp with explicit options, independently of telemetry.
    // Keep these modules on the compile classpath when SOCIAL_INCLUDED is false.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.database)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.messaging)
    implementation(libs.kotlinx.coroutines.playServices)

    // Free on-device translation: bundled OCR models (work without Google Play services) and ML Kit translation
    implementation(libs.mlkit.text.korean)
    implementation(libs.mlkit.text.japanese)
    implementation(libs.mlkit.text.chinese)
    implementation(libs.mlkit.translate)

    // Library on the web (docs/WEB_LIBRARY_PROTOCOL.md): WebRTC data channel to the user's browser. It ships its own
    // keep rule for org.webrtc (JNI) and only INTERNET/ACCESS_NETWORK_STATE in its manifest.
    implementation(libs.stream.webrtc)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.animationGraphics)
    debugImplementation(libs.androidx.compose.uiTooling)
    implementation(libs.androidx.compose.uiToolingPreview)
    implementation(libs.androidx.compose.uiUtil)

    implementation(libs.androidx.interpolator)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    implementation(libs.kotlin.reflect)

    implementation(libs.bundles.kotlinx.coroutines)

    implementation(libs.kotlinx.datetime)

    // AndroidX libraries
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appCompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.constraintLayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.coreSplashScreen)
    implementation(libs.androidx.recyclerView)
    implementation(libs.androidx.viewPager)
    implementation(libs.androidx.profileInstaller)

    implementation(libs.bundles.androidx.lifecycle)

    // Job scheduling
    implementation(libs.androidx.work)

    // RxJava
    implementation(libs.rxJava)

    // Networking
    implementation(libs.bundles.okhttp)
    implementation(libs.okio)
    implementation(libs.conscrypt) // TLS 1.3 support for Android < 10

    // Data serialization (JSON, protobuf, xml)
    implementation(libs.bundles.serialization)

    // HTML parser
    implementation(libs.jsoup)

    // Disk
    implementation(libs.diskLruCache)
    implementation(libs.unifile)

    // Preferences
    implementation(libs.androidx.preference)

    // Dependency injection
    implementation(libs.injekt)
    implementation(libs.metro.runtime)
    implementation(libs.metrox.viewmodel)
    implementation(libs.metrox.viewmodel.compose)

    // Image loading
    implementation(libs.bundles.coil)
    implementation(libs.subsamplingScaleImageView) {
        exclude(module = "image-decoder")
    }
    implementation(libs.image.decoder)

    implementation(libs.webgpuviewer)

    // UI libraries
    implementation(libs.material)
    implementation(libs.flexibleAdapter)
    implementation(libs.photoView)
    implementation(libs.directionalViewPager) {
        exclude(group = "androidx.viewpager", module = "viewpager")
    }
    implementation(libs.composeRichEditor)
    implementation(libs.aboutLibraries.compose)
    implementation(libs.bundles.voyager)
    implementation(libs.composeMaterialMotion)
    implementation(libs.swipe)
    implementation(libs.composeWebview)
    implementation(libs.composeGrid)
    implementation(libs.reorderable)
    implementation(libs.bundles.markdown)
    implementation(libs.materialKolor)

    // Logging
    implementation(libs.logcat)

    // Shizuku
    implementation(libs.bundles.shizuku)

    // String similarity
    implementation(libs.stringSimilarity)

    // GraphQL generation
    implementation(libs.apollo)
    implementation(libs.apollo.adapters)

    // Tests
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    // For detecting memory leaks; see https://square.github.io/leakcanary/
    // debugImplementation(libs.leakCanary.android)
    implementation(libs.leakCanary.plumber)

    testImplementation(libs.kotlinx.coroutines.test)
}

apollo {
    val schemaBasePath = "src/main/graphql/mihon/graphql"
    service("anilist") {
        packageName.set("mihon.graphql.anilist")
        val srcDir = "$schemaBasePath/anilist"
        srcDir(file(srcDir))

        introspection {
            endpointUrl.set("https://graphql.anilist.co")
            schemaFile.set(file("$srcDir/anilist.graphqls"))
        }

        // ISO 3166-1 alpha-2 country code
        mapScalarToKotlinString("CountryCode")
    }

    service("kitsu") {
        packageName.set("mihon.graphql.kitsu")
        val srcDir = "$schemaBasePath/kitsu"
        srcDir(file(srcDir))
        android.defaultConfig.versionCode

        introspection {
            // Kitsu doesn't like requests without a UA
            headers.put(
                "User-Agent",
                "Mihon v${android.defaultConfig.versionName} (${android.defaultConfig.applicationId})",
            )
            endpointUrl.set("https://kitsu.app/api/graphql")
            schemaFile.set(file("$srcDir/kitsu.graphqls"))
        }

        // A date, expressed as an ISO8601 string
        mapScalarToKotlinString("Date")
        // An ISO 8601-encoded datetime
        mapScalar("ISO8601DateTime", "kotlin.time.Instant", "com.apollographql.adapter.core.KotlinInstantAdapter")
        // A loose key-value map in GraphQL
        mapScalar(
            "Map",
            "kotlin.collections.Map<String, Any?>",
            "mihon.graphql.kitsu.adapter.KitsuMapAdapter",
        )
    }

    service("shikimori") {
        packageName.set("mihon.graphql.shikimori")
        val srcDir = "$schemaBasePath/shikimori"
        srcDir(file(srcDir))

        introspection {
            endpointUrl.set("https://shikimori.io/api/graphql")
            schemaFile.set(file("$srcDir/shikimori.graphqls"))
        }

        // An ISO 8601-encoded date
        mapScalarToKotlinString("ISO8601Date")
    }

    service("suwayomi") {
        packageName.set("mihon.graphql.suwayomi")
        val srcDir = "$schemaBasePath/suwayomi"
        srcDir(file(srcDir))

        introspection {
            endpointUrl.set("http://localhost:4567/api/graphql")
            schemaFile.set(file("$srcDir/suwayomi.graphqls"))
        }
    }
}

val latestCommitCount = getLatestCommitCount()
val latestCommitSha = getLatestCommitSha()
val latestCommitTime = getLatestCommitTime()
val currentTime = getCurrentTime()

fun ApplicationVariant.buildConfigField(type: String, name: String, value: Provider<String>) {
    buildConfigFields?.put(name, value.map { BuildConfigField(type, it, null) })
}

androidComponents {
    onVariants { variant ->
        val environment = if (!Config.includeSocial) {
            "disabled"
        } else {
            socialEnvironmentOverride ?: if (variant.buildType == "debug") "emulator" else "disabled"
        }
        val isEmulator = environment == "emulator"
        val isProduction = environment == "production"
        val projectId = socialProperty("project-id") ?: if (isEmulator) "demo-mihon-together" else ""
        // Functions' IID dependency validates API-key syntax even when HTTP targets an emulator.
        val apiKey = socialProperty("api-key") ?: if (isEmulator) "A00000000000000000000000000000000000000" else ""
        val appId = socialProperty("app-id") ?: if (isEmulator) "1:1234567890:android:0123456789abcdef" else ""
        val databaseUrl = socialProperty("database-url")
            ?: if (isEmulator) "https://$projectId-default-rtdb.firebaseio.com" else ""
        val emulatorHost = socialProperty("emulator-host") ?: "10.0.2.2"
        val functionsRegion = socialProperty("functions-region") ?: "europe-west1"
        // Web companion base URL for invite links (blank = share the app link and handle only).
        val webUrl = socialProperty("web-url") ?: if (isEmulator) "http://10.0.2.2:5050" else ""
        val storageBucket =
            socialProperty("storage-bucket") ?: when {
                // Emulators use the legacy name; buckets created since Sept 2024 are <id>.firebasestorage.app.
                isEmulator -> "$projectId.appspot.com"
                projectId.isNotEmpty() -> "$projectId.firebasestorage.app"
                else -> ""
            }

        require(projectId != "mihonapp") { "Social must use a fork-owned Firebase project, never Mihon's project" }
        require(
            listOf(projectId, apiKey, appId, databaseUrl, emulatorHost, functionsRegion).none {
                it.any { character -> character.isISOControl() }
            },
        ) { "Social configuration cannot contain control characters" }
        if (isEmulator) {
            require(projectId.startsWith("demo-")) {
                "Emulator builds require a demo- project ID so an unavailable emulator cannot access cloud data"
            }
        }
        if (isProduction) {
            require(listOf(projectId, apiKey, appId, databaseUrl).all { it.isNotEmpty() }) {
                "Production social requires explicit social-project-id, social-api-key, social-app-id, and social-database-url"
            }
            require(!projectId.startsWith("demo-") && databaseUrl.startsWith("https://")) {
                "Production social requires a real fork-owned project and an HTTPS database URL"
            }
            require(!databaseUrl.contains("mihonapp", ignoreCase = true)) {
                "Social must never use Mihon's database"
            }
        }

        mapOf(
            "SOCIAL_ENVIRONMENT" to environment,
            "SOCIAL_PROJECT_ID" to if (environment == "disabled") "" else projectId,
            "SOCIAL_API_KEY" to if (environment == "disabled") "" else apiKey,
            "SOCIAL_APP_ID" to if (environment == "disabled") "" else appId,
            "SOCIAL_DATABASE_URL" to if (environment == "disabled") "" else databaseUrl,
            "SOCIAL_EMULATOR_HOST" to emulatorHost,
            "SOCIAL_FUNCTIONS_REGION" to functionsRegion,
            "SOCIAL_STORAGE_BUCKET" to if (environment == "disabled") "" else storageBucket,
            "SOCIAL_WEB_URL" to if (environment == "disabled") "" else webUrl,
            "DISCORD_APP_ID" to discordAppId(),
        ).forEach { (name, value) ->
            variant.buildConfigField("String", name, providers.provider { javaString(value) })
        }
        mapOf(
            "SOCIAL_AUTH_EMULATOR_PORT" to socialEmulatorPort("auth", 9099),
            "SOCIAL_FIRESTORE_EMULATOR_PORT" to socialEmulatorPort("firestore", 8080),
            "SOCIAL_DATABASE_EMULATOR_PORT" to socialEmulatorPort("database", 9000),
            "SOCIAL_FUNCTIONS_EMULATOR_PORT" to socialEmulatorPort("functions", 5001),
            "SOCIAL_STORAGE_EMULATOR_PORT" to socialEmulatorPort("storage", 9199),
        ).forEach { (name, value) ->
            variant.buildConfigField("int", name, providers.provider { value.toString() })
        }
    }

    onVariants { variant ->
        val isUnstableBuild = variant.buildType in setOf("debug", "nightly", "preview")
        val buildTime = if (isUnstableBuild) currentTime else latestCommitTime

        variant.buildConfigField("String", "COMMIT_COUNT", latestCommitCount.map { "\"$it\"" })
        variant.buildConfigField("String", "COMMIT_SHA", latestCommitSha.map { "\"$it\"" })
        variant.buildConfigField("String", "BUILD_TIME", buildTime.map { "\"$it\"" })

        if (isUnstableBuild) {
            variant.outputs.forEach { output ->
                val versionName = output.versionName.get()
                output.versionName.set(latestCommitCount.map { "$versionName-$it" })
            }
        }
    }

    onVariants { variant ->
        val resSource = variant.sources.res ?: return@onVariants

        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val replaceShortcutsPlaceholderTask = tasks.register<ReplaceShortcutsPlaceholderTask>(
            "replace${variantName}ShortcutPlaceholder",
        ) {
            applicationId.set(variant.applicationId)
            shortcutsFile.set(projectDir.resolve("src/main/shortcuts.xml"))
        }
        resSource.addGeneratedSourceDirectory(replaceShortcutsPlaceholderTask) { it.outputDir }
    }

    onVariants(selector().withFlavor("default" to "standard")) {
        // Only excluding in standard flavor because this breaks
        // Layout Inspector's Compose tree
        it.packaging.resources.excludes.add("META-INF/*.version")
    }
}
