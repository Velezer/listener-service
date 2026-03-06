#!/usr/bin/env bash
set -euo pipefail

workflows=(
  ".github/workflows/android-tests.yml"
  ".github/workflows/android-e2e.yml"
)

required_checks=(
  "tests/e2e_raw_github_config.sh"
  "tests/e2e_raw_github_config_fallback.sh"
  "tests/e2e_ws_service_background_return_guard.sh"
)

for wf in "${workflows[@]}"; do
  if [[ ! -f "$wf" ]]; then
    echo "FAIL: Missing workflow file: $wf"
    exit 1
  fi

  for check in "${required_checks[@]}"; do
    if ! rg -F -- "$check" "$wf" >/dev/null; then
      echo "FAIL: $wf must run $check"
      exit 1
    fi
  done

done

if ! rg -F -- ':app:connectedDebugAndroidTest' '.github/workflows/android-e2e.yml' >/dev/null; then
  echo "FAIL: .github/workflows/android-e2e.yml must run connectedDebugAndroidTest"
  exit 1
fi

echo "OK: Workflow live checks are wired for config validation and instrumentation E2E."
