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
        versionCode = 20206
        versionName = "2.3.0"

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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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

dependencies {
    implementation(libs.androidx.core.ktx)
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
