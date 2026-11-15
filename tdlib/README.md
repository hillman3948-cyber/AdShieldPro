# Official TDLib Java API + pinned Android native runtime

This directory holds the **supply-chain pins** for the Telegram Cloud Storage provider.

## What is vendored in Git

| Path | What it is | License |
| --- | --- | --- |
| `app/src/main/java/org/drinkless/tdlib/Client.java` | Official TDLib JNI Java client | Boost 1.0 |
| `app/src/main/java/org/drinkless/tdlib/TdApi.java` | Generated official TDLib Java API | Boost 1.0 |
| `tdlib/LICENSE_1_0.txt` | Boost Software License 1.0 text | — |
| `tdlib/native-checksums.sha256` | SHA-256 for all 12 native libraries | — |

`TdApi.java` records the upstream generator commit in
`TdApi.GIT_COMMIT_HASH = "a17f87c4cff7b90b278d12b91ba0614383aaee82"` (an official
`tdlib/td` commit). It is the generated `org.drinkless.tdlib` Java API — the same API used by
Telegram's own Android clients — not a hand-written imitation.

## What is downloaded at build time

`./gradlew :app:fetchTdlibNative` (a dependency of every `preBuild`) downloads the archive

```
https://codeload.github.com/capullo-tech/lib-tdlib-android/tar.gz/7dede3f1a716b7398caf79acd413ad9d5331f079
```

and copies `src/main/jniLibs/<abi>/*.so` into `app/src/main/jniLibs/`. That repository is a
**verbatim, unmodified mirror** of the prebuilt TDLib distribution used by Telegram X
(`TGX-Android/tdlib`, pinned commit `63524bd14bebb9d7f9f04a1747675501ec41ea0a`), which is built
from official `tdlib/td` source. The mirror ships:

* `libtdjni.so` — TDLib's JNI native library (4 ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`)
* `libsslx.so`, `libcryptox.so` — OpenSSL 3.x, renamed to avoid collisions; `libtdjni.so` lists
  both as `DT_NEEDED`

**Every `.so` is verified against `native-checksums.sha256` before it can reach the APK.** A
mismatch fails the build with an explicit error. The binaries are deliberately *not* committed
(they total ~95 MB, and the repository's convention is to keep large artifacts out of Git). The
`jniLibs/` directory is git-ignored.

The task always re-hashes the installed files. If all 12 hashes match, no download happens; a
fresh clone downloads one ~40 MB archive from GitHub. No paid service, no personal backend, no
JitPack/Maven artifact of unclear provenance is required.

## Building the native library yourself from official source

If you do not want to trust the mirror, build the same libraries from upstream:

```bash
git clone https://github.com/tdlib/td.git
cd td/example/android
# Requires Docker; see example/android/README.md for prerequisites.
docker build --build-arg COMMIT_HASH=a17f87c4cff7b90b278d12b91ba0614383aaee82 --output tdlib .
```

The official Docker build produces `tdlib/tdlib.zip` containing `java/org/drinkless/tdlib/*.java`
and `libs/<abi>/libtdjni.so`. Replace the vendored Java files, place the `.so` files (and their
OpenSSL dependencies) in `app/src/main/jniLibs/<abi>/`, then update
`tdlib/native-checksums.sha256` with `sha256sum` output. The build task will then verify your own
binaries and skip the download.

## Honesty notes

* The Telegram X distribution's README says it is "not intended to be used in any projects except
  Telegram X" and may include work-in-progress changes. It is redistributable under the Boost 1.0
  license, but you should prefer the official source build above for production releases and
  re-verify with a real device.
* No API `api_id`/`api_hash` values are included in this repository. Every user supplies their
  own credentials from <https://my.telegram.org> at runtime; they are stored encrypted with the
  Android Keystore.
* Telegram is not a permanent backup service. Nothing in this integration claims a retention
  guarantee, a quota, or a capacity that Telegram did not report.
