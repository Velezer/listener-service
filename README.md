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

### Android runtime E2E test (live config, no mocks)

This instrumentation test runs on emulator/device and calls the live hosted config URL:

```bash
cd android
./gradlew :app:connectedDebugAndroidTest
```

### Unit tests

```bash
cd android
./gradlew test
```

> Note: requires Android SDK path (`ANDROID_HOME` or `android/local.properties`).

## Troubleshooting

### GitHub Actions emulator failure (`adb` exit code 224 / `stop: Not implemented`)

If Android E2E CI fails during emulator shutdown with logs like:

- `The process '/usr/local/lib/android/sdk/platform-tools/adb' failed with exit code 224`
- `ERROR | stop: Not implemented`

use emulator options that disable snapshot save/restore in CI.

This repository's `android-e2e` workflow is configured to:

- always create a fresh AVD,
- disable snapshots (`-no-snapshot -no-snapshot-save`),
- keep runtime E2E instrumentation tests enabled (`:app:connectedDebugAndroidTest`).

This avoids shutdown snapshot calls that can fail on some hosted runners.

### App blocked by Google Play Protect (`app is harmful`)

When installing debug APKs outside Play Store, Play Protect can warn or block installs because the app is sideloaded and signed with a non-Play key.

Recommended approach:

1. Use internal testing distribution (Play Console Internal testing), or
2. Sign release builds with your own upload/release key and install that artifact.

For local QA on a trusted test device:

- open the Play Protect warning screen,
- choose **More details**,
- allow install only if you trust the APK origin and signature.

Also verify:

- APK came from your own CI artifact/release,
- package name and signing certificate are expected,
- device date/time and Play Services are healthy.

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
