#!/usr/bin/env bash
# Runs a Gradle build and retries once when the failure is a transient dependency-resolution
# problem (Maven Central / Google Maven occasionally answer 404/503 for a single artifact, which
# Gradle surfaces as ModuleVersionNotFoundException). Real compile/test failures are never retried.
#
# Usage: tools/gradle-ci.sh .ci/release.log :app:assembleRelease
set -uo pipefail

log="${1:?log path required}"
shift

TRANSIENT_PATTERN='Could not (resolve|find|download)|ModuleVersionNotFoundException|Read timed out|Connection reset|Remote host terminated|Received status code 5'

run() {
    ./gradlew "$@" --no-daemon --stacktrace 2>&1 | tee "$log"
    return "${PIPESTATUS[0]}"
}

if run "$@"; then
    exit 0
fi

if grep -qE "$TRANSIENT_PATTERN" "$log"; then
    echo "::warning::transient dependency-resolution failure; retrying $* once in 30s"
    sleep 30
    run "$@"
    exit $?
fi

exit 1
