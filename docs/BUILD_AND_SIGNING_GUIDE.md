# AdShield Pro v2.1 — APK & Signed AAB Release Build Instructions

## 1. Environment Prerequisites

- **JDK:** Java 17 or Java 21
- **Android SDK:** API 36 (`compileSdk = 36`, `targetSdk = 36`, `minSdk = 29`); install with `sdkmanager "platforms;android-36"` if missing.
- **Gradle:** Gradle 8.11.1 Wrapper (`.\gradlew.bat`)

---

## 2. Building Debug & Release APKs (Deliverable 15)

Run from `C:\Users\hillm\OneDrive\Desktop\PROJECT`:

### Build Debug APK
```powershell
.\gradlew.bat assembleDebug
```
**Output APK Path:**
`app\build\outputs\apk\debug\app-debug.apk`

### Build Optimized & Minified Release APK
```powershell
.\gradlew.bat assembleRelease
```
**Output APK Path:**
`app\build\outputs\apk\release\app-release.apk`

---

## 3. Building a Signed Release Android App Bundle (`.aab`) for Google Play (Deliverable 16)

### Step 1: Use the Existing Production Signing Keystore

For an update to an already installed AdShield Pro app, use the original production keystore and credentials. Do not generate a replacement key: Android will reject the update if it is signed with a different key. If this is the first ever release, create and securely back up a keystore before publishing.

### Step 2: Set Signing Environment Variables and Build `.aab`
`app/build.gradle.kts` reads release credentials from environment variables or Gradle user properties (never hardcoded in source control). On Windows, you can keep them in `%USERPROFILE%\.gradle\gradle.properties`, outside this project:

```properties
adshieldKeystorePath=C:/path/to/signing.keystore
adshieldKeystorePassword=your_keystore_password
adshieldKeyAlias=your_key_alias
adshieldKeyPassword=your_key_password
```

Keep this file private and never commit or share it. The equivalent environment variable names are shown below:

```powershell
$env:ADSHIELD_KEYSTORE_PATH = "C:\path\to\adshield-upload-key.jks"
$env:ADSHIELD_KEYSTORE_PASSWORD = "your_keystore_password"
$env:ADSHIELD_KEY_ALIAS = "adshield_upload"
$env:ADSHIELD_KEY_PASSWORD = "your_key_password"
.\gradlew.bat bundleRelease
```

**Output Signed AAB Path:**
`app\build\outputs\bundle\release\app-release.aab`

---

## 4. Running Automated Unit & Regression Tests

### Android Engine & Database Migration Unit Tests
```powershell
.\gradlew.bat testDebugUnitTest
```

