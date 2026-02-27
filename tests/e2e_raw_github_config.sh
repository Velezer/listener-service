#!/usr/bin/env bash
set -euo pipefail

CONFIG_URL="${1:-https://raw.githubusercontent.com/Velezer/listener-service/main/config.json}"

body="$(curl -fsSL --connect-timeout 10 --max-time 30 "$CONFIG_URL")"

json_parse_output="$({
  python - <<'PY' "$body"
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
  echo "ERROR: failed to parse config payload from $CONFIG_URL"
  echo "$json_parse_output"
  exit 1
}

ws_key="$(sed -n '1p' <<< "$json_parse_output")"
ws_url="$(sed -n '2p' <<< "$json_parse_output")"

if [[ ! "$ws_url" =~ ^wss?:// ]]; then
  echo "ERROR: websocket URL has invalid scheme: $ws_url"
  exit 1
fi

echo "OK: config reachable"
echo "OK: websocket key found: $ws_key"
echo "OK: websocket URL parsed: $ws_url"
