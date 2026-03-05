#!/usr/bin/env bash
set -euo pipefail

workflows=(
  ".github/workflows/android.yml"
  ".github/workflows/android-tests.yml"
  ".github/workflows/android-e2e.yml"
)

for wf in "${workflows[@]}"; do
  if [[ ! -f "$wf" ]]; then
    echo "FAIL: Missing workflow file: $wf"
    exit 1
  fi

  if rg -n '^\s*push\s*:' "$wf" >/dev/null; then
    echo "FAIL: $wf still contains a push trigger"
    exit 1
  fi

done

echo "OK: All checked workflows are configured without push triggers."
