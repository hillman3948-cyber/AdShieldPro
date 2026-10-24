# GitHub APK update flow

The app checks the latest published release in the GitHub repository configured as githubUpdateRepository (or environment variable GITHUB_UPDATE_REPOSITORY) when launched and periodically with WorkManager. Use your own public repository; do not point the app at the upstream BlockAds project. Android may delay background checks to save battery.

Configure the repository as OWNER/REPO in your local Gradle user properties or environment. Keep signing credentials private.

For each release:
1. Increase versionCode and versionName in app/build.gradle.kts.
2. Use a vMAJOR.MINOR.PATCH tag. This app maps that tag to MAJOR * 10000 + MINOR * 100 + PATCH (v2.2.0 is 20200).
3. Build a signed release APK with the same keystore used for the installed production app.
4. Publish a GitHub Release (not draft or prerelease) in your own configured repository.
5. Attach one release APK asset and add user-facing notes in the release body. The app verifies the APK SHA-256 when GitHub provides it.
6. User confirms installation in Android's package installer. Android 8+ may ask to allow installs from AdShield Pro.

Older installed app copies must first install an updater-enabled APK manually.