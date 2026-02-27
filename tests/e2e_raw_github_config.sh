#!/usr/bin/env bash
set -euo pipefail

CONFIG_URL="${1:-https://raw.githubusercontent.com/Velezer/listener-service/main/config.json}"

body="$(curl -fsSL "$CONFIG_URL")"
ws_url="$(python -c 'import json,sys; d=json.loads(sys.stdin.read()); print((d.get("wssFeederServiceAggTrade") or d.get("WS_FEEDER_SERVICE") or "").strip())' <<< "$body")"

if [[ -z "$ws_url" ]]; then
  echo "ERROR: websocket URL not found in config"
  exit 1
fi

if [[ ! "$ws_url" =~ ^wss?:// ]]; then
  echo "ERROR: websocket URL has invalid scheme: $ws_url"
  exit 1
fi

echo "OK: config reachable and websocket URL parsed: $ws_url"
