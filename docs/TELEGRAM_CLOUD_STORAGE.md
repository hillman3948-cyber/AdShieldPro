# Telegram Cloud Storage (official TDLib)

AdShield Pro's **Cloud → Telegram** section is a real Telegram client feature, built on the
official [TDLib](https://github.com/tdlib/td) Java API and its native `libtdjni.so`. It signs in a
**user account** directly with Telegram, stores files in **Saved Messages** or in a **private
channel created by the app**, and reads back only that one designated chat.

There is no backend server, no bot token, no simulated transfer, no fake progress and no
"unlimited storage" claim. TDLib opens the MTProto connection to Telegram itself.

---

## 1. What you need before building

1. **Your own Telegram API credentials.** Get `api_id` (a number) and `api_hash` (a string) at
   <https://my.telegram.org> → *API development tools*. They are entered in the app at runtime,
   never hardcoded, never bundled in the APK and never sent anywhere except Telegram.
2. **A real Telegram account** with a phone number you can receive a login code on (plus your
   2-step verification password if enabled).
3. **Android SDK / JDK 21** as for the rest of the app. No NDK, no Docker, no paid service is
   required for a normal build.

### Build

```bash
./gradlew :app:fetchTdlibNative      # downloads + verifies 12 pinned lib*.so files
./gradlew :app:assembleDebug          # also runs fetchTdlibNative through preBuild
```

`fetchTdlibNative` is a dependency of `preBuild`, so **every** assemble/compile/test/lint task
verifies the native libraries first. It downloads a 40 MB GitHub archive pinned to commit
`7dede3f1a716b7398caf79acd413ad9d5331f079` of a verbatim mirror of the Telegram X TDLib
prebuilt distribution, then checks every `.so` against `tdlib/native-checksums.sha256`. A
checksum mismatch fails the build. See [`tdlib/README.md`](../tdlib/README.md) for provenance,
licenses and how to rebuild the binaries from official `tdlib/td` source instead.

The APK ships all four ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) with `libtdjni.so`,
`libsslx.so` and `libcryptox.so`, so the existing universal-build gate still passes.

---

## 2. Where the feature lives

| Layer | File |
| --- | --- |
| Official TDLib Java API | `app/src/main/java/org/drinkless/tdlib/{Client,TdApi}.java` |
| Real TDLib client (JNI, update filtering, `send`) | `data/telegram/AndroidTdlibPort.kt` |
| Port seam used by tests | `data/telegram/TelegramTdlibPort.kt` |
| Transfer/repository logic | `data/telegram/TelegramStorageRepository.kt` |
| Keystore-backed encryption + secure store | `data/telegram/AndroidTelegramSecurity.kt`, `TelegramSecureStore.kt` |
| SAF staging, hashing, wipe | `data/telegram/AndroidTelegramFileIo.kt` |
| Limits, caption envelope, SHA-256, retry policy | `data/telegram/TelegramPolicy.kt` |
| Hilt wiring | `di/TelegramModule.kt` |
| ViewModel | `ui/viewmodel/TelegramCloudViewModel.kt` |
| Compose UI (4 sections) | `ui/screens/cloud/TelegramCloudScreen.kt` |
| Entry point in existing Cloud Storage | `CloudSection.TELEGRAM` in `ui/viewmodel/CloudViewModel.kt` |

The existing Cloud dashboard, WebDAV/Nextcloud providers, AdShield Cloud sections, navigation
and theme are unchanged. Telegram is an additional section tab and an additional "Jump to a
section" card.

---

## 3. Authorization (direct user account)

`AndroidTdlibPort.start()` loads `libtdjni.so`, sets TDLib verbosity to **errors only** with no
log file, and creates `org.drinkless.tdlib.Client`. On `authorizationStateWaitTdlibParameters` it
sends a real `SetTdlibParameters` request:

* `useTestDc = false` (production Telegram DCs)
* `apiId` / `apiHash` — the values you entered
* app-private `databaseDirectory` / `filesDirectory` under `noBackupFilesDir/telegram/<uuid>/`
* a random 32-byte `databaseEncryptionKey` (TDLib encrypts its own database with it)
* `useFileDatabase`, `useChatInfoDatabase`, `useMessageDatabase = true` (needed for history/search
  and resumable transfers), `useSecretChats = false`
* honest `deviceModel`, `systemVersion`, `applicationVersion`, `systemLanguageCode`

The app then follows the states TDLib reports:

| TDLib state | App action |
| --- | --- |
| `authorizationStateWaitPhoneNumber` | `setAuthenticationPhoneNumber` with explicit settings that disable flash calls, missed calls and SMS Retriever |
| `authorizationStateWaitCode` | `checkAuthenticationCode` |
| `authorizationStateWaitPassword` | `checkAuthenticationPassword` (2-step verification) |
| `authorizationStateReady` | `getMe`, then resolve/create the storage chat |
| `waitOtherDeviceConfirmation`, `waitRegistration`, `waitEmailAddress`, `waitEmailCode`, `waitPremiumPurchase` | shown with clear guidance to finish that step in an official Telegram app; **not simulated** |

Phone number, login code and 2-step password are entered only while the corresponding TDLib state
is active, passed straight to TDLib, and are **never stored, logged or written to disk** by
AdShield Pro. Only a masked phone number (`+••••••1234`) is kept for the status card.

### Storage chat

* **Saved Messages** — `createPrivateChat(userId = getMe().id, force = false)`; TDLib uses the
  current user's id as the Saved Messages chat id.
* **Private storage channel** — `createNewSupergroupChat(..., isChannel = true, ...)` creates a
  channel only that account can read. Its chat id is stored encrypted and reused; the app never
  creates a second channel on reconnect.

Everything (`getChatHistory`, `searchChatMessages`, `deleteMessages`, `editMessageCaption`,
`sendMessage`) targets **only** this chat id. `getChats`/`loadChats` are never called and updates
carrying other conversations' content are dropped in `AndroidTdlibPort.dispatch()`.

---

## 4. Uploads, downloads and progress

### Upload

1. The SAF document is copied to an app-private staged file; while copying, the app enforces the
   size limit and computes a SHA-256 hash over the bytes.
2. If the same hash is already in the local index of files uploaded by this app, the upload is
   refused with a **Duplicate file detected** dialog (with an "Upload anyway" escape hatch).
   Telegram does not expose hashes for arbitrary uploaded documents, so deduplication covers
   files uploaded through this build — this is the "where practical" boundary.
3. `sendMessage` → `inputMessageDocument` with `InputFileLocal(stagedPath)` and
   `disableContentTypeDetection = true`. The caption carries a small, versioned JSON envelope
   (`AdShieldPro/TGCS1:{"v":1,"sha256":…,"size":…,"label":…}`) so the app recognises only its
   own documents.
4. `updateFile` events (fed into `fileStates`) drive the progress bar via
   `remote.uploadedSize / size`. Completion is `remote.isUploadingCompleted`.
5. The resulting `MessageDocument` supplies the real file id, filename, MIME type, size and
   upload date. The final message id is reconciled from chat history (temporary ids change when
   TDLib confirms a send).

### Download

1. `getFile(fileId)` → `downloadFile(fileId, priority = 16, offset = local.downloadOffset,
   limit = 0, synchronous = false)`. TDLib resumes at its recorded offset.
2. `updateFile` drives progress via `local.downloadedSize`.
3. When `local.isDownloadingCompleted`, the bytes are copied from TDLib's cache into the
   `CreateDocument` URI chosen by the user, and the copy is hashed. If a recorded hash exists and
   matches, status becomes **Downloaded · hash verified**; a mismatch is reported as a failure
   with the local copy kept for inspection rather than silently "succeeding".

### Progress, interruptions and retries

Every transfer is a persisted `TelegramTransfer` record (kind, state, bytes done/total, attempts,
last error, staged path, file id, message id, SHA-256). On process start, any non-terminal
transfer becomes **Interrupted**, so a killed app or a dropped connection never masquerades as a
running job. The UI exposes **Retry** and **Cancel**:

* interrupted upload → `retry()` checks `getFile`; if the bytes were already uploaded it re-sends
  the message with `InputFileId` (no second data transfer), otherwise it re-sends from the staged
  local copy. TDLib resumes its own upload state.
* interrupted download → `retry()` re-issues `downloadFile` from the current `downloadOffset`.
* cancel upload → `deleteMessages` on the pending message; cancel download →
  `cancelDownloadFile`.
* bounded retry attempts (max 5), persisted attempt counts, and an honest stalled-transfer
  detection after four consecutive inactive polls. `TelegramPolicy.retryDelayMs` provides a bounded
  2 s → 30 s backoff curve for scheduling; retries are initiated by the user from the persisted
  transfer list rather than silently retried in the background.

Progress is never invented: it comes from TDLib's `updateFile` values only.

### Telegram's limits (respected)

| Rule | Value used |
| --- | --- |
| Max file size | **2 GB** (`2000 MiB`) per file for non-Premium accounts. Premium's 4 GB ceiling is documented but **not** enabled, so the app never attempts an upload Telegram would reject. |
| Caption | Kept under 900 characters (Telegram's `message_caption_length_max` is read by TDLib; our envelope is ~120 bytes). |
| History page size | 100 messages (`getChatHistory` limit), max 5 pages per refresh. |
| Priority | 16 of TDLib's 1–32 range. |

Larger files must be split by the user; the app says so instead of failing obscurely.

---

## 5. Search and organization

* **Files**: name, size, upload date (`Message.date`), download status, SHA-256 prefix and label.
* **Search**: local filter by name/label plus a real `searchChatMessages` call with
  `searchMessagesFilterDocument` against the designated chat; untagged documents are ignored.
* **Organize**: labels are local virtual folders *and* are written back into the Telegram
  document caption with `editMessageCaption`, so they survive a reinstall. A failed caption edit
  keeps the label on-device and says so.
* **Storage Management**: connected-account status, count/bytes of indexed files, clearing
  TDLib's local cache and staged copies, and disconnect. Telegram publishes **no per-account
  quota**, so no capacity or "free space" number is displayed.

---

## 6. Security

| Concern | Implementation |
| --- | --- |
| Session data | `TelegramSession` (api_id, api_hash, TDLib database key, account id, target chat id) is JSON encrypted with an **Android Keystore AES-256-GCM** key and stored in `SharedPreferences`. The key never leaves Keystore. |
| TDLib database | Encrypted by TDLib with a random 32-byte key that is itself Keystore-wrapped. All TDLib files live in `noBackupFilesDir`. |
| Backups | `android:allowBackup="false"` and `res/xml/data_extraction_rules.xml` exclude all SharedPreferences/files/databases from cloud backup and device transfer. |
| Credentials in code | None. No `api_id`, `api_hash`, bot token or session token is compiled into the APK. |
| Logging | TDLib verbosity 1 (errors), no log file, no `toString()` of TdApi objects, no logging of phone numbers, codes, passwords or encrypted blobs. |
| Passwords | 2-step verification password is collected only in TDLib's `waitPassword` state and sent only to TDLib. |
| Private conversations | Only the designated chat id is queried; other update types are dropped. |
| Permissions | Only the app's existing `INTERNET` / `ACCESS_NETWORK_STATE`. File access is entirely through the Storage Access Framework (`OpenDocument`/`CreateDocument`), so no `READ/WRITE_EXTERNAL_STORAGE`, no contacts, no SMS, no notification listener. |
| Disconnect | `logOut` (terminates the server-side session) → `close` → delete the Keystore key → clear all encrypted records → recursively delete the TDLib database, file cache, staged copies and previews. If offline, the UI offers "Clear local data only" **with an explicit warning that the server session stays active** and tells the user where to terminate it. |
| Deleted key | A session whose ciphertext cannot be decrypted is wiped and the user is asked to reconnect; no fake session is invented. |

---

## 7. Honest limitations

* **Telegram does not guarantee permanent backup.** Files can be removed by Telegram, by the
  account owner, by deletion of the chat/channel, or by account termination. Keep an independent
  copy of anything irreplaceable. The UI states this on every provider screen.
* **No quota is reported by Telegram.** Storage Management shows only files this app actually
  indexed; it never displays invented capacity.
* **Real-account end-to-end tests cannot run in CI.** Authentication needs an SMS/official-app
  code and a live account. The JVM suite verifies the state machine, request objects, progress,
  retry and wipe with a scripted TDLib port; on-device validation is a manual checklist below.
* **Native binary provenance.** The `.so` files come from the Telegram X prebuilt distribution
  mirror described in `tdlib/README.md`, pinned by commit and SHA-256. For production releases,
  build them from official `tdlib/td` source with the documented Docker command and update the
  checksums.
* **Premium 4 GB uploads are not enabled**; the conservative 2 GB ceiling applies to everyone.
* **Labels and duplicate hashes are local metadata plus a caption envelope**; Telegram itself has
  no folder API for Saved Messages.
* Logout requires network. An offline disconnect clears everything locally but cannot prove the
  remote session ended.
* `editMessageCaption` may be rejected by Telegram for some channel/message states; the local
  label still works and the UI reports the difference.

---

## 8. Testing

### Automated (runs in CI, no account/device needed)

`app/src/test/java/com/adshield/pro/TelegramCloudTestSuite.kt` uses a scripted
`TelegramTdlibPort`, an in-memory key/value store, a deterministic cipher and an in-memory file
I/O double. It covers:

1. authorization state machine (parameters → phone → code → 2FA → ready), honest
   `SetTdlibParameters`, and that no password/phone is persisted;
2. unsupported authorization states are reported, never simulated;
3. real upload request (`sendMessage` with `InputFileLocal`, our caption), progress from
   `updateFile`, and stored filename/size/date/hash/status;
4. SHA-256 duplicate detection before any upload request;
5. interrupted upload → `INTERRUPTED`, then retry reusing `InputFileId` when bytes were uploaded;
6. download progress, resumption from `downloadOffset`, and SHA-256 verification/mismatch;
7. failed download retry from the recorded offset;
8. disconnect: `logOut`, closed state, encrypted store wiped, Keystore key deleted, TDLib
   directories and staged files deleted;
9. only the designated chat id is ever queried;
10. caption envelope, 2 GB limit, secure-store ciphertext-only invariant, masked phone and retry
    backoff.

Run: `./gradlew :app:testDebugUnitTest` (or `tools/gradle-ci.sh .ci/test.log :app:testDebugUnitTest`).

The Android build workflow additionally compiles Kotlin/Java, assembles both debug and
R8-minified release APKs, runs the universal-build gate, executes all JVM tests, runs Android
lint, and runs `tools/check_apk_native_libs.py` against the debug APK. That verifier confirms the
packaged bytes of `libtdjni.so`, `libsslx.so` and `libcryptox.so` for `arm64-v8a`,
`armeabi-v7a`, `x86` and `x86_64` still match `tdlib/native-checksums.sha256`. Those twelve
libraries are downloaded and SHA-256-verified by `:app:fetchTdlibNative` on every build; a
missing, swapped or altered `.so` fails the build. Run the same check locally on any APK with
`python3 tools/check_apk_native_libs.py <path-to-apk>`.

### Manual on-device checklist (real account, real network)

| # | Step | Expected |
| --- | --- | --- |
| 1 | Cloud → **Telegram** → enter own `api_id`/`api_hash` → Connect | Status changes to `WAIT_PHONE_NUMBER`; no crash about `libtdjni.so`. |
| 2 | Enter phone number | Telegram sends a code to the official app / SMS. |
| 3 | Enter code, then 2-step password (if enabled) | Status becomes **CONNECTED** with masked phone and storage-chat name. |
| 4 | Upload a small file, watch the progress bar | Progress reaches 100%, file appears in Saved Messages/private channel in the official Telegram app. |
| 5 | Upload the same file again | Duplicate dialog; "Upload anyway" creates a second copy. |
| 6 | Upload a 2+ GB file | Clear 2 GB limit error; no partial upload. |
| 7 | Start a large upload, enable airplane mode for ~30 s, restore | Transfer is reported **Interrupted**; **Retry** resumes and completes without a second full upload. |
| 8 | Kill the app mid-transfer and reopen | Transfer shows **Interrupted** (not stuck "Running"); Retry works. |
| 9 | Download a stored file to Downloads | Progress → 100%, status **Downloaded · hash verified**, file opens in an external app. |
| 10 | Interrupt a download (airplane mode) | **Interrupted**; Retry resumes from the last offset. |
| 11 | Corrupt/alter the downloaded copy and re-check hash | Mismatch is reported; the app does not claim success. |
| 12 | Search by filename and by label, filter by label | Only tagged files from the designated chat are listed. |
| 13 | Clear local cache | TDLib cache/staged copies gone; files remain in Telegram. |
| 14 | **Disconnect & clear local data** (online) | Telegram → Settings → Devices no longer shows the AdShield Pro session; encrypted prefs empty; Keystore key deleted; no TDLib directory left; status returns to disconnected. |
| 15 | Disconnect offline ("clear local data only") | Local data gone, explicit warning that the server session is still active. |

---

## 9. Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| "TDLib native runtime is unavailable for this device ABI" | `jniLibs` was not populated. Run `./gradlew :app:fetchTdlibNative` and rebuild; confirm the APK contains `lib/<abi>/libtdjni.so`. |
| `401` on `SetTdlibParameters` / `setDatabaseEncryptionKey` | The TDLib database key no longer matches its database (Keystore key replaced or a partial restore). The app wipes local session data and asks you to connect again. |
| `FLOOD_WAIT_x` | Telegram rate-limited the account. Wait for the interval; the transfer is marked interrupted and can be retried. |
| `PHONE_NUMBER_INVALID` / `PHONE_CODE_INVALID` | TDLib's real error is surfaced verbatim; re-enter the value. |
| Logout did not confirm | No network. Use the offline option and terminate the session in Telegram → Settings → Devices. |
| Build fails on native checksum | Do not bypass it. Re-download, or rebuild the libraries from official source and update `tdlib/native-checksums.sha256`. |
