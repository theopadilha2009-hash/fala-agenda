#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:${DENO_DIR:-$HOME/.deno/bin}:$PATH"

echo "JAVA_HOME=$JAVA_HOME"
java -version
echo "ANDROID_HOME=$ANDROID_HOME"

./gradlew --no-daemon --max-workers=2 :domain:test
./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest
./gradlew --no-daemon --max-workers=2 :app:lintDebug
./gradlew --no-daemon --max-workers=2 :app:assembleDebug
./gradlew --no-daemon --max-workers=2 :app:assembleRelease
./gradlew --no-daemon --max-workers=2 :app:compileDebugAndroidTestKotlin

if command -v deno >/dev/null 2>&1; then
  deno test --allow-env supabase/functions
else
  echo "Deno ausente; instale para os testes das Edge Functions."
  exit 1
fi

echo "OK"
