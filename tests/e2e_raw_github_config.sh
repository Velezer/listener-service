#!/usr/bin/env bash
set -euo pipefail

DEFAULT_URLS=(
  "https://raw.githubusercontent.com/Velezer/listener-service/main/config.json"
  "https://github.com/Velezer/listener-service/raw/main/config.json"
)

if [[ $# -gt 0 ]]; then
  CONFIG_URLS=("$@")
else
  CONFIG_URLS=("${DEFAULT_URLS[@]}")
fi

fetch_config() {
  local url="$1"
  curl -fsSL --retry 3 --retry-delay 2 --retry-all-errors --connect-timeout 10 --max-time 30 \
    -H 'User-Agent: listener-service-e2e-check/1.0' \
    -H 'Accept: application/json' \
    "$url"
}

body=""
used_url=""
failures=()
for url in "${CONFIG_URLS[@]}"; do
  if body="$(fetch_config "$url" 2>/dev/null)"; then
    used_url="$url"
    break
  fi
  failures+=("$url")
done

if [[ -z "$used_url" ]]; then
  echo "ERROR: failed to fetch config payload from all endpoints: ${failures[*]}"
  exit 1
fi

json_parse_output="$({
  python3 - <<'PY' "$body"
import json
import sys

raw = sys.argv[1]
try:
    payload = json.loads(raw)
except json.JSONDecodeError as exc:
    print(f"JSON_ERROR:{exc}")
    raise SystemExit(2)

for key in ("wssFeederServiceAggTrade", "WS_FEEDER_SERVICE"):
    value = (payload.get(key) or "").strip()
    if value:
        print(f"{key}\n{value}")
        break
else:
    print("MISSING_WS_KEY")
    raise SystemExit(3)
PY
} 2>&1)" || {
  echo "ERROR: failed to parse config payload from $used_url"
  echo "$json_parse_output"
  exit 1
}

ws_key="$(sed -n '1p' <<< "$json_parse_output")"
ws_url="$(sed -n '2p' <<< "$json_parse_output")"

if [[ ! "$ws_url" =~ ^wss?:// ]]; then
  echo "ERROR: websocket URL has invalid scheme: $ws_url"
  exit 1
fi

echo "OK: config reachable via $used_url"
echo "OK: websocket key found: $ws_key"
echo "OK: websocket URL parsed: $ws_url"
