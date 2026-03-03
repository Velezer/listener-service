# Listener Service

Listener Service is an Android foreground service that:
- downloads a JSON config,
- extracts the WebSocket URL (`wssFeederServiceAggTrade` or `WS_FEEDER_SERVICE`),
- connects to the WebSocket endpoint,
- surfaces status and events via Android notifications.

## Configuration

Default config source used by the app:

- `https://raw.githubusercontent.com/Velezer/listener-service/main/config.json`
- Fallback: `https://github.com/Velezer/listener-service/raw/main/config.json`

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

During `Connecting`, the notification now includes the exact WSS URL being used so you can confirm runtime config quickly.

## Tests

### End-to-end config test (no mocks)

This validates the real hosted config payload and websocket URL format.
The script automatically retries and falls back to a secondary live GitHub endpoint if the primary one is temporarily unavailable.

```bash
bash tests/e2e_raw_github_config.sh
```

Optional: test one or more custom config URLs (checked in order):

```bash
bash tests/e2e_raw_github_config.sh \
  "https://invalid.example/config.json" \
  "https://raw.githubusercontent.com/<owner>/<repo>/<branch>/config.json"
```

Fallback behavior E2E test:

```bash
bash tests/e2e_raw_github_config_fallback.sh
```

### Android runtime E2E test (live config, no mocks)

This instrumentation test runs on emulator/device and calls the live hosted config URL:

```bash
cd android
./gradlew :app:connectedDebugAndroidTest
```

This suite now includes a live WebSocket handshake test that fetches `config.json` and verifies that the configured endpoint accepts a real connection. It also uses multi-endpoint fallback resolution to reduce flaky failures caused by transient CDN or GitHub raw endpoint outages.

Gradle/JVM compatibility E2E test (verifies wrapper+Gradle can run Android JVM tests on the host JDK):

```bash
bash tests/e2e_android_gradle_jvm_compat.sh
```

This test intentionally runs `testDebugUnitTest` with `--no-parallel` to avoid Gradle 9 + Android plugin dependency-mutation races seen when parallel task graphs resolve debug/release classpaths concurrently.

Guard test for the non-parallel compatibility flag:

```bash
bash tests/e2e_android_gradle_non_parallel_guard.sh
```

### Unit tests

```bash
cd android
./gradlew test
```

> Note: requires Android SDK path (`ANDROID_HOME` or `android/local.properties`).

## Troubleshooting

### Error: `Failed to load websocket config: NetworkOnMainThreadException`

This happens when network I/O is executed on Android's main thread. The service now fetches websocket config on a background thread before opening the socket, so startup no longer crashes with `NetworkOnMainThreadException`. Error notifications now also use expanded (big text) style so long messages are readable instead of truncated.

If config loading fails for another reason, the service reports exception class + message and up to two nested causes for better troubleshooting context.

Checklist:
- Verify config URL is reachable from device network.
- Verify config payload is valid JSON.
- Verify one of these keys exists with non-empty value:
  - `wssFeederServiceAggTrade`
  - `WS_FEEDER_SERVICE`
- Verify URL scheme is `ws://` or `wss://`.

Run the E2E config script first to isolate remote-config problems quickly.

### Error: `SocketTimeoutException: timeout -> SocketException: Socket closed`

This error chain usually means the socket did not successfully complete or maintain the connection before one side closed it. Common root causes:

- The resolved WebSocket URL is wrong, stale, or blocked by DNS/network policy.
- TLS/network middleboxes terminate or delay the handshake.
- The server endpoint closes idle or invalid clients immediately.
- Device/emulator network connectivity is unstable.

Best-practice triage flow:

1. Confirm the `Connecting` notification shows the expected `WSS URL: ...` value.
2. Validate live config parsing:
   ```bash
   bash tests/e2e_raw_github_config.sh
   ```
3. Run Android runtime E2E tests on device/emulator:
   ```bash
   cd android
   ./gradlew :app:connectedDebugAndroidTest
   ```
4. If failures persist, test endpoint reachability from the same network as the device and verify backend timeout/close policies.

## CI workflow

GitHub Actions now validates the live config endpoint before running JVM or instrumentation tests.

- `android-tests.yml`: runs `tests/e2e_raw_github_config.sh`, `tests/e2e_raw_github_config_fallback.sh`, `tests/e2e_android_gradle_jvm_compat.sh` (which executes `./gradlew testDebugUnitTest --no-parallel`), and `tests/e2e_android_gradle_non_parallel_guard.sh` on pushes and pull requests.
- `android-e2e.yml`: runs both live endpoint checks (`tests/e2e_raw_github_config.sh` + `tests/e2e_raw_github_config_fallback.sh`) and then instrumentation tests (`./gradlew :app:connectedDebugAndroidTest`) on pull requests affecting Android/runtime test scope.
