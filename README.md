# SoundTouch Backend

Clean-room Java middle layer for running the Stockholm frontend in a browser without the Android `Native` bridge.

Current scope:

- Serves the `stockholm` frontend on `http://127.0.0.1:8088/`
- Implements a queue-backed `Native.appSend(...)` / `Native.runQueue()` bridge
- Proxies browser cross-origin HTTP(S) requests through `/api/http-proxy`
- Persists `getData` / `setData` values under `backend/state/native-state.json`
- Reads backend configuration from `backend/config/backend-config.json`
- Uses backend configuration to control frontend `loggingLevel` / `showDebug`
- Implements SSDP-based speaker discovery for `getDeviceList`
- Implements SSDP-based media-server discovery for `getHrmsList`
- Implements basic callback methods used during browser startup:
  - `getLanStatus`
  - `getTimeZone`
  - `getLegalDocPath`
  - `getConstant`
  - `canPerformAutoAPSetup`

Not implemented yet:

- Android-only setup flows such as Wi-Fi provisioning (`getNetStats`, `getSSIDList`, `setSSID`)
- Native websocket shims for old Android browser constraints
- App/gui update install flows
- OAuth handoff flows that depended on mobile-native wrappers

Run:

```powershell
./gradlew.bat --gradle-user-home .gradle-home :backend:run
```

Then open:

```text
http://127.0.0.1:8088/
```

To disable frontend debug logging, set `frontendLoggingLevel` to `0` in `backend/config/backend-config.json`.
