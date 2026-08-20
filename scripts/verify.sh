#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

echo "JAVA_HOME=$JAVA_HOME"
java -version
echo "ANDROID_HOME=$ANDROID_HOME"

./gradlew --no-daemon clean test lint assembleDebug

if command -v deno >/dev/null 2>&1; then
  deno test --allow-env supabase/functions
else
  echo "Deno ausente; instale para os testes das Edge Functions."
  exit 1
fi

echo "OK"
