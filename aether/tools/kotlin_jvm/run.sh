#!/usr/bin/env bash
# AETHER Kotlin JVM checks — compile & run without an Android SDK.
#
# Uses a real Kotlin 2.0.21 compiler (matching the repo's Kotlin version) and a
# JDK 17. Small JVM stubs stand in for Android / Compose / Hilt / JUnit APIs so
# the deterministic logic and the Android integration wiring can be exercised.
# This is NOT an Android build: an APK, lint and device tests still require the
# Android SDK + Gradle (see docs/aether/TEST_RESULTS.md).
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$REPO"
WORK="${TMPDIR:-/tmp}/aether-kotlin-jvm"
mkdir -p "$WORK"

# --- JDK 17 (jdk4py wheel ships a real Temurin runtime) -------------------
if ! command -v java >/dev/null 2>&1; then
  VENV="$REPO/.venv"
  if [ ! -x "$VENV/bin/python" ]; then python3 -m venv "$VENV"; fi
  "$VENV/bin/pip" install --quiet "jdk4py==17.0.9.2"
  export JAVA_HOME="$("$VENV/bin/python" -c 'import jdk4py; print(jdk4py.JAVA_HOME)')"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
java -version 2>&1 | head -1

# --- Kotlin compiler 2.0.21 (npm package, same version as the app) --------
if [ ! -x "$WORK/kotlinc/bin/kotlinc" ]; then
  command -v npm >/dev/null 2>&1 || { echo "npm is required to fetch the Kotlin compiler" >&2; exit 1; }
  (cd "$WORK" && npm pack kotlin-compiler@2.0.21 --pack-destination "$WORK" >/dev/null)
  mkdir -p "$WORK/kotlinc"
  tar xzf "$WORK/kotlin-compiler-2.0.21.tgz" -C "$WORK/kotlinc" --strip-components=1
fi
KOTLINC="$WORK/kotlinc/bin/kotlinc"
STDLIB="$WORK/kotlinc/lib/kotlin-stdlib.jar"
"$KOTLINC" -version 2>&1 | head -1

# --- Compile all new Kotlin sources + tests + stubs + runners --------------
CLASSES="$WORK/classes"
rm -rf "$CLASSES"; mkdir -p "$CLASSES"
STUBS="$REPO/aether/tools/kotlin_jvm/stubs"
"$KOTLINC" \
  app/src/main/java/com/adshield/pro/aether/*.kt \
  app/src/main/java/com/adshield/pro/ui/screens/AetherScreen.kt \
  app/src/test/java/com/adshield/pro/AetherEngineTestSuite.kt \
  $(find "$STUBS" -name '*.kt' | sort) \
  "$REPO/aether/tools/kotlin_jvm/AetherJvmTests.kt" \
  "$REPO/aether/tools/kotlin_jvm/AetherIntegrationChecks.kt" \
  -d "$CLASSES"
echo "[kotlin] compiled all new AETHER Kotlin sources + 12 unit tests"

echo "[kotlin] running JVM unit tests"
java -cp "$CLASSES:$STDLIB" AetherJvmTestsKt

echo "[kotlin] running Android integration checks against real baseline assets"
java -cp "$CLASSES:$STDLIB" AetherIntegrationChecksKt "$REPO/app/src/main/assets"
