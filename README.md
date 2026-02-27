# Listener Service

Listener Service is an Android foreground service that:
- downloads a JSON config,
- extracts the WebSocket URL (`wssFeederServiceAggTrade` or `WS_FEEDER_SERVICE`),
- connects to the WebSocket endpoint,
- surfaces status and events via Android notifications.

## Configuration

Default config source used by the app:

- `https://raw.githubusercontent.com/Velezer/listener-service/main/config.json`

Expected config JSON:

```json
{
  "wssFeederServiceAggTrade": "wss://feeder-service-production.up.railway.app/aggTrade"
}
```

Legacy key is still supported:

```json
{
  "WS_FEEDER_SERVICE": "wss://feeder-service-production.up.railway.app/aggTrade"
}
```

## How to run the app

1. Open project in Android Studio (`android/` folder).
2. Ensure Android SDK is installed and configured.
3. Build/install app on a device or emulator.
4. Launch app once:
   - It requests notification permission on Android 13+.
   - Then it starts a foreground service that manages WebSocket connection.
5. Observe connection lifecycle notifications (`Starting`, `Connecting`, `Connected`, `Message`, `Disconnected`, `Error`).

## Tests

### End-to-end config test (no mocks)

This validates the real hosted config payload and websocket URL format.

```bash
bash tests/e2e_raw_github_config.sh
```

Optional: test a different config URL:

```bash
bash tests/e2e_raw_github_config.sh "https://raw.githubusercontent.com/<owner>/<repo>/<branch>/config.json"
```

### Unit tests

```bash
cd android
./gradlew test
```

> Note: requires Android SDK path (`ANDROID_HOME` or `android/local.properties`).

## Troubleshooting

### Error: `Failed to load websocket config: null`

This usually means config parsing/loading threw an exception that had no message. The service now reports a safer fallback detail (cause message or exception type), and the config parser now emits explicit details for invalid JSON payloads (including a short response preview).

Checklist:
- Verify config URL is reachable from device network.
- Verify config payload is valid JSON.
- Verify one of these keys exists with non-empty value:
  - `wssFeederServiceAggTrade`
  - `WS_FEEDER_SERVICE`
- Verify URL scheme is `ws://` or `wss://`.

Run the E2E config script first to isolate remote-config problems quickly.
