buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9 compiles Kotlin itself ("built-in Kotlin") and depends on KGP 2.2.10 by
        // default. Pin the Kotlin Gradle Plugin so the Kotlin compiler matches the Compose
        // compiler and serialization plugins declared below.
        // Keep in sync with `kotlin` in gradle/libs.versions.toml.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    // No `org.jetbrains.kotlin.android`: AGP 9 has built-in Kotlin and that plugin is
    // incompatible with AGP 9's DSL. Compose/serialization compiler plugins still apply.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
