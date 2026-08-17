#!/usr/bin/env bash
set -euo pipefail

if [ $# -lt 1 ]; then
  echo "Usage: $0 /path/to/android_offline_build_environment_v1 [gradle tasks/options...]" >&2
  exit 2
fi

ENV_ROOT="$(cd "$1" && pwd)"
shift
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

export JAVA_HOME="$ENV_ROOT/jdk17"
export ANDROID_HOME="$ENV_ROOT/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_OFFLINE_MAVEN_REPO="$ENV_ROOT/maven-repo"
export GRADLE_USER_HOME="$ENV_ROOT/.gradle-user-home"
export PATH="$JAVA_HOME/bin:$ENV_ROOT/gradle/bin:$PATH"

GRADLE="$ENV_ROOT/gradle/bin/gradle"
AAPT2="$ANDROID_HOME/build-tools/36.1.0/aapt2"

[ -x "$GRADLE" ] || { echo "Missing Gradle: $GRADLE" >&2; exit 3; }
[ -x "$AAPT2" ] || { echo "Missing aapt2: $AAPT2" >&2; exit 3; }

cd "$ROOT"
python3 tools/verify-local-build.py

if [ $# -eq 0 ]; then
  set -- :app:compileDebugKotlin :app:testDebugUnitTest
fi

exec "$GRADLE" --offline --no-daemon \
  -Pandroid.aapt2FromMavenOverride="$AAPT2" \
  "$@"
