#!/usr/bin/env bash
set -euo pipefail

PRIMARY_UNREACHABLE_URL="https://127.0.0.1:9/config.json"
FALLBACK_URL="https://raw.githubusercontent.com/Velezer/listener-service/main/config.json"

output="$(bash tests/e2e_raw_github_config.sh "$PRIMARY_UNREACHABLE_URL" "$FALLBACK_URL")"

echo "$output"

if ! grep -Fq "OK: config reachable via $FALLBACK_URL" <<< "$output"; then
  echo "ERROR: expected fallback endpoint to be used after unreachable primary URL"
  exit 1
fi

if grep -Fq "OK: config reachable via $PRIMARY_UNREACHABLE_URL" <<< "$output"; then
  echo "ERROR: unreachable primary URL should not be reported as successful"
  exit 1
fi

echo "OK: fallback endpoint used after primary URL failure"
