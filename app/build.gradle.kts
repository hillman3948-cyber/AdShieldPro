plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val githubUpdateRepository = providers.gradleProperty("githubUpdateRepository")
    .orElse(providers.environmentVariable("GITHUB_UPDATE_REPOSITORY"))
    .orElse("hillman3948-cyber/AdShieldPro")
    .get()
val escapedGithubUpdateRepository = githubUpdateRepository.replace("\\", "\\\\").replace("\"", "\\\"")
// Keep intermediate build artifacts outside OneDrive to prevent Windows OneDrive cloud-filter
// file locking (AccessDeniedException on R.jar during javac), and automatically mirror outputs
// (APKs, AABs, and test reports) into $projectDir/build/outputs.
val externalBuildRoot = System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir")
val externalBuildDir = file("$externalBuildRoot/adshield_pro_build/app")
layout.buildDirectory.set(externalBuildDir)

tasks.matching {
    it.name.startsWith("assemble") || it.name.startsWith("bundle") || it.name.startsWith("test")
}.configureEach {
    doLast {
        val srcOutputs = File(externalBuildDir, "outputs")
        if (srcOutputs.exists()) {
            val dstOutputs = File(projectDir, "build/outputs")
            srcOutputs.copyRecursively(dstOutputs, overwrite = true)
        }
        val srcReports = File(externalBuildDir, "reports")
        if (srcReports.exists()) {
            val dstReports = File(projectDir, "build/reports")
            srcReports.copyRecursively(dstReports, overwrite = true)
        }
    }
}

android {
    namespace = "com.adshield.pro"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.adshield.pro"
        minSdk = 29
        targetSdk = 36
        versionCode = 20200
        versionName = "2.2.0"

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
            val ksPath = System.getenv("ADSHIELD_KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("ADSHIELD_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("ADSHIELD_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("ADSHIELD_KEY_PASSWORD") ?: ""
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

    kotlinOptions {
        jvmTarget = "17"
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
