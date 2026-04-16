# Soundcork Stockholm App

Clean-room Java middle layer for running the Bose SoundTouch Stockholm frontend in a browser without the Android `Native` bridge.
It can be used as a frontend for the [soundcork](https://github.com/deborahgu/soundcork) project.

This project is under active development. Expect bugs and limitations.

> [!TIP]
> This project does not bundle the Stockholm frontend itself for potential legal reasons. Please download it from, e.g. the Internet Archive at https://archive.org/download/bose-soundtouch-software-and-firmware/Programs/Interface/ - choose the Stockholm zip file with version 27.0.13-4277-8963611.
> After downloading, copy the Stockholm archive next to the `config` folder of this project and mount it via Docker Compose.

## Current scope

- Serves the `stockholm` frontend on `http://127.0.0.1:8088/`
- Implements a queue-backed `Native.appSend(...)` / `Native.runQueue()` bridge
- Proxies browser cross-origin HTTP(S) requests through `/api/http-proxy`
- Persists `getData` / `setData` values under `state/native-state.json`
- Reads backend configuration from `config/backend-config.json`
- Uses backend configuration to control frontend `loggingLevel` / `showDebug`
- Implements SSDP-based speaker discovery for `getDeviceList`
- Implements SSDP-based media-server discovery for `getHrmsList`
- Implements basic callback methods used during browser startup:
  - `getLanStatus`
  - `getTimeZone`
  - `getLegalDocPath`
  - `getConstant`
  - `canPerformAutoAPSetup`

### Not implemented yet

- Android-only setup flows such as Wi-Fi provisioning (`getNetStats`, `getSSIDList`, `setSSID`)
- Native websocket shims for old Android browser constraints
- App/gui update install flows
- OAuth handoff flows that depended on mobile-native wrappers

## Run

We recommend running the backend with Docker. Customize it via `.env` file.

Example:

```env
TZ=Europe/Berlin
BACKEND_BIND_IP=0.0.0.0
BACKEND_PORT=8088
BACKEND_URL=http://soundcork:8000
AUTH_SERVICE_URL=http://soundcork:8000/marge/
```

```shell
docker compose up --build
```

You can also run it locally with Java 21 and Gradle.

```shell
./gradlew --gradle-user-home .gradle-home run
```

After starting the backend, open the frontend in a browser at:

```text
http://127.0.0.1:8088/
```

To disable frontend debug logging, set `frontendLoggingLevel` to `0` in `config/backend-config.json`.

## Known issues

- Reverse proxies do not yet work reliably (do not use TLS encrypted reverse proxies for now - see below).
- Since this app connects to the SoundTouch speakers via unencrypted Websockets, the browser may block the connection if the frontend is served over HTTPS. In that case, use HTTP for the frontend.
