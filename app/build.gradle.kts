// The Gradle Kotlin DSL exposes `java` as the Java plugin extension, so JDK packages used by
// the TDLib verification task need explicit imports.
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 compiles Kotlin itself; org.jetbrains.kotlin.android must not be applied.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val githubUpdateRepository = providers.gradleProperty("githubUpdateRepository")
    .orElse(providers.environmentVariable("GITHUB_UPDATE_REPOSITORY"))
    .orElse("yawiyuson231-oss/AdShieldPro")
    .get()
val escapedGithubUpdateRepository = githubUpdateRepository.replace("\\", "\\\\").replace("\"", "\\\"")
val releaseKeystorePath = providers.gradleProperty("adshieldKeystorePath")
    .orElse(providers.environmentVariable("ADSHIELD_KEYSTORE_PATH"))
    .orNull
val releaseKeystorePassword = providers.gradleProperty("adshieldKeystorePassword")
    .orElse(providers.environmentVariable("ADSHIELD_KEYSTORE_PASSWORD"))
    .orNull
val releaseKeyAlias = providers.gradleProperty("adshieldKeyAlias")
    .orElse(providers.environmentVariable("ADSHIELD_KEY_ALIAS"))
    .orNull
val releaseKeyPassword = providers.gradleProperty("adshieldKeyPassword")
    .orElse(providers.environmentVariable("ADSHIELD_KEY_PASSWORD"))
    .orNull
// On Windows, keep intermediate build artifacts outside OneDrive to prevent OneDrive
// cloud-filter file locking (AccessDeniedException on R.jar), and mirror the outputs
// (APKs, AABs, test reports) back into $projectDir/build/...
// Everywhere else (Linux, macOS, CI) the standard Gradle build directory is used.
val oneDriveSafeBuildDir = System.getenv("LOCALAPPDATA")
    ?.takeIf { it.isNotBlank() }
    ?.let { localAppData -> file("$localAppData/adshield_pro_build/app") }
if (oneDriveSafeBuildDir != null) {
    layout.buildDirectory.set(oneDriveSafeBuildDir)
    tasks.matching {
        it.name.startsWith("assemble") || it.name.startsWith("bundle") || it.name.startsWith("test")
    }.configureEach {
        doLast {
            val outputs = oneDriveSafeBuildDir.resolve("outputs")
            if (outputs.exists()) {
                outputs.copyRecursively(File(projectDir, "build/outputs"), overwrite = true)
            }
            val reports = oneDriveSafeBuildDir.resolve("reports")
            if (reports.exists()) {
                reports.copyRecursively(File(projectDir, "build/reports"), overwrite = true)
            }
        }
    }
}

android {
    namespace = "com.adshield.pro"
    // compileSdk 37 is required by core-ktx 1.19, Compose 1.12 and hilt-navigation-compose 1.4.
    // targetSdk deliberately stays at 36: the app is not exercised on an Android 17 device
    // here, so opt-in runtime behaviour changes are not taken blind. Raise it once the app
    // has been smoke-tested on API 37 (see docs/UPGRADE.md).
    compileSdk = 37

    defaultConfig {
        applicationId = "com.adshield.pro"
        minSdk = 29
        targetSdk = 36
        versionCode = 21009
        versionName = "2.10.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField(
            "String",
            "GITHUB_UPDATE_REPOSITORY",
            "\"$escapedGithubUpdateRepository\""
        )

    }

    signingConfigs {
        create("releaseConfig") {
            val ksPath = releaseKeystorePath
            if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = releaseKeystorePassword ?: ""
                keyAlias = releaseKeyAlias ?: ""
                keyPassword = releaseKeyPassword ?: ""
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val releaseSign = signingConfigs.getByName("releaseConfig")
            signingConfig = releaseSign
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("wasm", "ktx2")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // libtdjni.so declares libsslx.so and libcryptox.so as DT_NEEDED. Extracting the
            // libraries keeps those dependencies resolvable on every supported Android version.
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Print every JVM test case in CI logs instead of a bare "BUILD SUCCESSFUL".
        unitTests.all { test ->
            test.testLogging {
                events("passed", "failed", "skipped")
            }
        }
    }
}

// AGP 9 built-in Kotlin: `android.kotlinOptions {}` is gone, compiler options are configured
// on the `kotlin` extension registered by AGP (see developer.android.com/build/migrate-to-built-in-kotlin).
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        val releaseSign = android.signingConfigs.getByName("releaseConfig")
        require(
            releaseSign.storeFile?.isFile == true &&
                !releaseSign.storePassword.isNullOrBlank() &&
                !releaseSign.keyAlias.isNullOrBlank() &&
                !releaseSign.keyPassword.isNullOrBlank()
        ) {
            "Release signing is not configured. Set ADSHIELD_KEYSTORE_PATH, ADSHIELD_KEYSTORE_PASSWORD, ADSHIELD_KEY_ALIAS, and ADSHIELD_KEY_PASSWORD."
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

// ---------------------------------------------------------------------------------------------
// Official TDLib native runtime.
//
// app/src/main/java/org/drinkless/tdlib/{Client,TdApi}.java are the generated official TDLib
// Java bindings (Boost 1.0). The matching libtdjni.so + OpenSSL libraries are NOT committed:
// they are downloaded at build time from a pinned commit of a verbatim mirror of the Telegram X
// TDLib prebuilt distribution, and every .so is checked against tdlib/native-checksums.sha256
// before it can be packaged. See tdlib/README.md and docs/TELEGRAM_CLOUD_STORAGE.md.
// ---------------------------------------------------------------------------------------------
val tdlibCommit = "7dede3f1a716b7398caf79acd413ad9d5331f079"
val tdlibArchiveUrl = "https://codeload.github.com/capullo-tech/lib-tdlib-android/tar.gz/$tdlibCommit"
val tdlibJniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")
val tdlibChecksumsFile = rootProject.layout.projectDirectory.file("tdlib/native-checksums.sha256")
val tdlibCacheDir = layout.buildDirectory.dir("tdlib-native")

val fetchTdlibNative = tasks.register("fetchTdlibNative") {
    group = "telegram"
    description = "Fetch and verify the pinned official TDLib Android native libraries"
    val jniLibsDir = tdlibJniLibsDir
    val checksumsFile = tdlibChecksumsFile
    val archiveUrl = tdlibArchiveUrl
    val cacheDir = tdlibCacheDir
    val pinnedCommit = tdlibCommit
    inputs.file(checksumsFile)
    outputs.dir(jniLibsDir)
    // Always re-verify: a partially deleted jniLibs directory must never pass as up-to-date.
    outputs.upToDateWhen { false }
    doLast {
        val expected = checksumsFile.asFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val parts = line.split(Regex("\\s+"), limit = 2)
                require(parts.size == 2) { "Invalid TDLib checksum line: $line" }
                parts[1].trim() to parts[0].trim().lowercase()
            }
        require(expected.size == 12) { "Expected 12 pinned TDLib libraries, found ${expected.size}" }
        val destination = jniLibsDir.asFile
        fun sha256(file: File): String? {
            if (!file.isFile) return null
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        val missing = expected.filter { (path, hash) -> sha256(File(destination, path)) != hash }
        if (missing.isEmpty()) {
            logger.lifecycle("TDLib native runtime: ${expected.size} checksum-verified libraries present")
            return@doLast
        }
        val cache = cacheDir.get().asFile.apply { mkdirs() }
        val archive = File(cache, "tdlib-$pinnedCommit.tar.gz")
        if (!archive.isFile || archive.length() < 1_000_000L) {
            logger.lifecycle("Fetching pinned TDLib native runtime ($pinnedCommit) from GitHub")
            val connection = URI(archiveUrl).toURL().openConnection()
            connection.connectTimeout = 30_000
            connection.readTimeout = 180_000
            connection.setRequestProperty("User-Agent", "AdShieldPro-gradle")
            connection.getInputStream().use { input ->
                archive.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
            }
        }
        destination.mkdirs()
        // Extract with the system tar (available on Linux/macOS/Windows build agents with Git
        // Bash) and copy the pinned paths explicitly. Avoids Gradle CopySpec/Groovy Closure
        // overloads and keeps the archive layout out of the build script's type inference.
        val extractDir = File(cache, "extracted-$pinnedCommit")
        if (!extractDir.isDirectory) extractDir.mkdirs()
        val process = ProcessBuilder(
            "tar", "-xzf", archive.absolutePath, "-C", extractDir.absolutePath
        ).redirectErrorStream(true).start()
        val tarOutput = process.inputStream.bufferedReader().readText()
        require(process.waitFor() == 0) { "Could not unpack TDLib archive: $tarOutput" }
        val sourceRoot = File(
            extractDir,
            "lib-tdlib-android-$pinnedCommit/src/main/jniLibs"
        )
        expected.keys.forEach { relative ->
            val source = File(sourceRoot, relative)
            require(source.isFile) { "TDLib archive is missing $relative" }
            val target = File(destination, relative).apply { parentFile.mkdirs() }
            source.copyTo(target, overwrite = true)
        }
        val bad = expected.filter { (path, hash) -> sha256(File(destination, path)) != hash }
        require(bad.isEmpty()) {
            "TDLib native checksum mismatch for ${bad.keys}. " +
                "See docs/TELEGRAM_CLOUD_STORAGE.md (do not ship unverified native code)."
        }
        logger.lifecycle("TDLib native runtime: ${expected.size} libraries verified and installed")
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(fetchTdlibNative) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.okhttp)
    implementation(libs.androidx.webkit)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
