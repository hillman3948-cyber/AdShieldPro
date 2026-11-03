# Toolchain & UI upgrade — AdShield Pro 2.3.0

This document records exactly what was upgraded, why each version was chosen, what was
verified by execution, and what still needs a machine with an Android SDK.

## 1. Version matrix

| Component | Before | After |
| --- | --- | --- |
| Gradle wrapper | 8.11.1 | **9.7.1** (sha256 pinned in `gradle-wrapper.properties`) |
| Android Gradle plugin | 8.9.3 | **9.4.1** |
| Kotlin (KGP, built-in Kotlin) | 2.0.21 (plugin) | **2.4.21** (KGP pinned via `buildscript` classpath) |
| KSP | 2.0.21-1.0.28 | **2.3.13** (independent versioning since 2.3.x) |
| `compileSdk` / `targetSdk` | 36 / 36 | **37 / 36** |
| Compose BOM | 2024.11.00 | **2026.09.00** (Compose UI/runtime 1.12.1, Material3 1.4.0) |
| `material-icons-extended` | via BOM | **1.7.8**, explicit (Google stopped publishing it) |
| core-ktx | 1.15.0 | **1.19.1** |
| Lifecycle | 2.8.7 | **2.11.0** |
| Activity Compose | 1.9.3 | **1.13.0** |
| Navigation Compose | 2.8.4 | **2.10.2** |
| Room | 2.6.1 | **2.8.5** |
| DataStore | 1.1.1 | **1.3.0** |
| WorkManager | 2.10.0 | **2.12.0** |
| Hilt (Dagger) | 2.52 | **2.60.1** |
| `androidx.hilt` | 1.2.0 | **1.4.0** |
| kotlinx-coroutines | 1.9.0 | **1.11.0** |
| kotlinx-serialization | 1.6.3 | **1.12.0** |
| OkHttp | 4.12.0 | **5.5.1** |
| androidx.webkit | 1.12.1 | **1.18.0** |
| androidx.test.ext:junit / espresso | 1.2.1 / 3.6.1 | **1.3.0 / 3.7.0** |
| `versionCode` / `versionName` | 20205 / 2.2.5 | **20206 / 2.3.0** |

Every version above was checked against the published repository metadata
(`dl.google.com/dl/android/maven2/.../maven-metadata.xml` and `repo1.maven.org`) before it was
written into `gradle/libs.versions.toml`, so none of them is a guess.

## 2. Why AGP 9 was unavoidable

`core-ktx 1.19.x`, `compose-ui 1.12.x`, `hilt-navigation-compose 1.4.0` and friends declare
`minCompileSdk = 37` and `minAndroidGradlePluginVersion = 9.1.0` in their AAR metadata.
`compileSdk = 37` in turn requires AGP 9.1.1+, and AGP 9.4.x requires Gradle 9.6+. There is no
combination that keeps AGP 8.x and still gets the current AndroidX generation — which is why the
whole ladder moves in one change instead of library-by-library.

`targetSdk` deliberately stays at **36**: raising it opts the app into Android 17 runtime behaviour
changes that cannot be smoke-tested in this environment. Raise it after an on-device pass
(`docs/TESTING.md`, section "On-device smoke test").

## 3. AGP 9 migration changes that were applied

1. **Built-in Kotlin.** `org.jetbrains.kotlin.android` is removed from `app/build.gradle.kts`, the
   root build file and the version catalog. AGP 9 compiles Kotlin itself and the plugin is
   incompatible with the new DSL.
2. **KGP version pin.** AGP 9 defaults to KGP 2.2.10; the Compose compiler and serialization
   plugins must match the Kotlin compiler, so `build.gradle.kts` pins
   `org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21` through a `buildscript` classpath entry.
   Keep it in sync with `kotlin` in `gradle/libs.versions.toml`.
3. **`kotlinOptions` removed.** `android.kotlinOptions { jvmTarget = "17" }` became
   `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }`, which is the only supported
   way to configure the compiler with built-in Kotlin.
4. **Portable build directory.** The OneDrive workaround that redirected *every* build into
   `$LOCALAPPDATA` (and silently used `/tmp` on Linux) now only applies when `LOCALAPPDATA` is set,
   i.e. on Windows.
5. **No OS-specific JVM flags.** `-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT` was removed from
   `gradle.properties`; `WINDOWS-ROOT` is not a KeyStore type on Linux/macOS and broke every HTTPS
   request from the Gradle daemon there.
6. **No transitional opt-outs.** `android.newDsl`, `android.builtInKotlin` and
   `android.disallowKotlinSourceSets` are intentionally *not* set; all are removed in AGP 10.

## 4. UI and branding upgrade

- Compose 1.12.1 / Material3 1.4.0 (BOM 2026.09.00).
- `Theme.kt` now defines the full Material 3 surface ladder (`surfaceContainer*`, `surfaceDim`,
  `surfaceBright`, `inverseSurface`, error containers) for both dark and light schemes, plus a
  proper `Shapes` scale and a typography set derived from the Material defaults, so styles the app
  does not override no longer fall back to mismatched metrics.
- New launcher icon: adaptive icon (`mipmap-anydpi-v26`), themed/monochrome variant
  (`mipmap-anydpi-v33`), raster fallbacks for every density, and a monochrome notification icon.
  All of it is generated from one geometry source by `tools/generate_icons.py`
  (needs Pillow only for the raster fallbacks). `@mipmap/ic_launcher` replaces the old
  `@drawable/ic_shield_logo` in the manifest; notifications now use the white-on-alpha
  `ic_notification_shield`, so the status-bar icon is no longer a coloured blob.

## 5. Code fixes shipped in the same change

- `AdShieldRepository`: persisted `RuleCategory` names are parsed case-insensitively with a safe
  fallback instead of an unguarded `valueOf` that crashed on any unknown/renamed value.
- `AdShieldRepository.rebuildActiveFilterListsLocked` is now `suspend` and awaits its
  `upsertFilterSource` write; it used to launch a detached coroutine from inside `filterMutex`,
  which could land after a later rebuild and resurrect stale counts.
- `importRulesFromText` de-duplicates the batch, skips rules that already exist and returns the
  number of rows actually inserted (it previously reported parsed lines).
- `restoreConfigurationFromJson` merges backup rules instead of `DELETE FROM custom_rules`, so a
  restore no longer destroys rules created after the backup.
- `BrowserScreen` destroys its `WebView` (and detaches it from the view hierarchy) on dispose
  instead of only calling `stopLoading()`, which leaked the Activity context.
- Deprecated `Icons.Default.ArrowBack` / `ChevronRight` replaced with their `AutoMirrored` forms.

## 6. Verification

Executed, in this order.

### 6.1 GitHub Actions (`.github/workflows/android-build.yml`) — full Gradle build

Run [41626061305](https://github.com/yawiyuson231-oss/AdShieldPro/actions/runs/41626061305) on
`ubuntu-latest`, JDK 21 (Temurin), Android SDK `platforms;android-37.0`, Gradle 9.7.1
(4m 42s, every step green):

| Step | Result |
| --- | --- |
| `./gradlew :app:compileDebugKotlin` | **BUILD SUCCESSFUL** (2m 2s) |
| `./gradlew :app:assembleDebug` | **BUILD SUCCESSFUL** — APK packaged |
| `./gradlew :app:assembleRelease` (R8 + `shrinkResources`, throwaway keystore) | **BUILD SUCCESSFUL** — minified APK packaged |
| `./gradlew :app:testDebugUnitTest` | **BUILD SUCCESSFUL** |
| `./gradlew :app:lintDebug` | **0 errors** (lint is a hard CI gate) |
| Assert 18 unit tests actually executed | **pass** (the step fails unless exactly 18 tests pass) |
| Assert the APK contains `AndroidManifest.xml`, `classes*.dex`, `resources.arsc` | **pass** |

The APK is uploaded as the `adshield-pro-2.3.0-debug-apk` workflow artifact.

Note on the SDK: API 37 is published as **minor platforms** (`platforms;android-37.0/37.1/37.2`);
the package name `platforms;android-37` does not exist. `compileSdk = 37` resolves against the
installed `android-37.x` platform.

### 6.2 Local, in the authoring sandbox (no Android SDK there)

| Check | Result |
| --- | --- |
| Repository JVM suite `AdShieldEngineTestSuite` | **18 / 18 pass** |
| Independent harness suite (extra truncation/cache/upstream cases) | **27 / 27 pass** |
| `kotlinc 2.4.21` over every `app/src/main` + `app/src/test` file | no syntax-class diagnostics |

### 6.3 Still not covered

- Installation and runtime behaviour on a physical device or emulator (no device is attached).
- Play Console upload / review.
- Release signing with the real keystore (`assembleRelease` in CI uses a throwaway key).

## 7. Rebuilding it yourself

```bash
# JDK 17+, Android SDK Platform 37.x, ANDROID_HOME set
./gradlew --version                     # expect Gradle 9.7.1
./gradlew :app:testDebugUnitTest        # JVM suite (18 tests)
./gradlew :app:assembleDebug            # debug APK
./gradlew :app:lintDebug                # must stay at 0 errors
./gradlew :app:bundleRelease            # signed AAB (see BUILD_AND_SIGNING_GUIDE.md)
```

CI runs the same commands on every push and pull request, so a regression shows up without a
local Android SDK.

If the source-set guard fires with *"Using kotlin.sourceSets DSL to add Kotlin sources is not
allowed with built-in Kotlin"* (a KSP/AGP interaction that older KSP releases hit), add
`android.disallowKotlinSourceSets=false` to `gradle.properties`. KSP 2.3.13 does not need it.

If AGP 9.4.1 misbehaves, the documented fallback pair is **AGP 9.3.3 + Gradle 9.5.0** (same
`compileSdk = 37`).

## 8. Rollback

```bash
git revert <this-commit>        # restores AGP 8.9.3 / Gradle 8.11.1 / Kotlin 2.0.21
# or, for the whole branch:
git checkout fd52d39
```
