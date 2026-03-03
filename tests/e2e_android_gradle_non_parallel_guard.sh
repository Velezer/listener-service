#!/usr/bin/env bash
set -euo pipefail

script="tests/e2e_android_gradle_jvm_compat.sh"

if [[ ! -f "$script" ]]; then
  echo "FAIL: Missing $script"
  exit 1
fi

if rg -n -- '--no-parallel' "$script" >/dev/null; then
  echo "OK: $script enforces --no-parallel for Gradle 9/AGP compatibility"
else
  echo "FAIL: $script must pass --no-parallel to Gradle"
  exit 1
fi
