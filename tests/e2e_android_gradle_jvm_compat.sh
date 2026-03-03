#!/usr/bin/env bash
set -euo pipefail

cd android

version_output="$(./gradlew --version --no-daemon)"
echo "$version_output"

jvm_line="$(printf '%s\n' "$version_output" | sed -n -E 's/^(Launcher JVM|JVM):[[:space:]]*//p' | head -n1)"
if [[ -z "$jvm_line" ]]; then
  echo "WARNING: Could not parse explicit JVM line from Gradle --version output"
else
  echo "OK: Gradle runtime JVM -> $jvm_line"
fi

./gradlew testDebugUnitTest --no-daemon --no-parallel

echo "OK: Android debug JVM unit tests passed via Gradle wrapper (no parallel configuration)"
