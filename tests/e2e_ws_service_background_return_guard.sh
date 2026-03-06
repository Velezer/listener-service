#!/usr/bin/env bash
set -euo pipefail

target="android/app/src/main/kotlin/com/listener/WsService.kt"

if [[ ! -f "$target" ]]; then
  echo "FAIL: Missing $target"
  exit 1
fi

if rg -n 'return@Thread' "$target" >/dev/null; then
  echo "FAIL: $target contains invalid return label return@Thread (must match helper lambda label)"
  exit 1
fi

if ! rg -n 'runInBackground\s*\{' "$target" >/dev/null; then
  echo "FAIL: expected runInBackground helper usage in $target"
  exit 1
fi

if ! rg -n 'return@runInBackground' "$target" >/dev/null; then
  echo "FAIL: expected labeled return return@runInBackground in $target"
  exit 1
fi

echo "OK: WsService uses valid runInBackground labeled return semantics."
