# Soundcork Stockholm App

Clean-room Java middle layer for running the Bose SoundTouch Stockholm frontend in a browser without the Android `Native` bridge.
It can be used as a frontend for the [soundcork](https://github.com/deborahgu/soundcork) project.

This project is under active development. Expect bugs and limitations.

> [!IMPORTANT]
> Bose has made SoundTouch technical material available for community tooling, and Stockholm source/archive material is publicly available. We believe using the Stockholm code with this project is permitted.
>
> This project does not bundle the Stockholm frontend itself. Please download it from, e.g. the Internet Archive at https://archive.org/download/bose-soundtouch-software-and-firmware/Programs/Interface/ - choose the Stockholm zip file with version `27.0.13-4277-8963611`.
>
> After downloading, copy the Stockholm archive into the `stockholm_zip` folder of this project. It will be expanded and patched to run with the backend.

![Stockholm in Chrome](./docs/images/stockholm.png)

## Current Scope

- Serves the `stockholm` frontend on `http://127.0.0.1:8088/`
- Implements a queue-backed `Native.appSend(...)` / `Native.runQueue()` bridge
- Proxies browser cross-origin HTTP(S) requests through `/api/http-proxy`
- Persists `getData` / `setData` values under `state/native-state.json`
- Seeds `margeAuthToken` and `margeAccountID` from environment variables when provided
- Rebuilds `stockholm/json/config.json` from `stockholm/json/backup.json` on container start so URL env changes take effect after restart
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

### Not Implemented Yet

- Android-only setup flows such as Wi-Fi provisioning (`getNetStats`, `getSSIDList`, `setSSID`)
- Native websocket shims for old Android browser constraints
- App/gui update install flows
- OAuth handoff flows that depended on mobile-native wrappers

## Docker Run

Docker is the recommended way to run the backend. Customize it via the `.env` file. Use `.env.example` as a template.

1. Copy the example environment file and edit it:

```shell
cp .env.example .env
```

2. Download the Stockholm archive and place it here:

```text
stockholm_zip/stockholm.zip
```

The compose file mounts the `stockholm_zip` directory instead of mounting `stockholm.zip` directly. This avoids a Docker Desktop/Windows pitfall where a missing file bind mount can be created as a directory.

3. Start the app from the published image:

```shell
docker compose up -d
```

4. Or build and run a local image from this checkout:

```shell
docker compose -f docker-compose.yml -f docker-compose.build.yml up --build -d
```

After starting the backend, open:

```text
http://127.0.0.1:8088/
```

## Environment

The compose setup uses `.env` for interpolation. It intentionally does not use `env_file`, because Compose gives explicit `environment` entries precedence over `env_file` values and that made local overrides confusing.

Common values:

```env
TZ=Europe/Berlin
BACKEND_BIND_IP=0.0.0.0
BACKEND_PORT=8088
BACKEND_URL=http://soundcork:8000
# Optional; defaults to BACKEND_URL/marge when omitted.
STREAMING_URL=http://soundcork:8000/marge
AUTH_SERVICE_URL=http://soundcork:8000/marge/
```

`AUTH_SERVICE_URL` points to the authentication endpoint of your backend. The `/marge/` path suffix is specific to [soundcork](https://github.com/deborahgu/soundcork). If you use a different Bose SoundTouch cloud replacement such as [Bose-SoundTouch](https://github.com/gesellix/Bose-SoundTouch), omit `/marge/` and set both variables to the same base URL.

`STREAMING_URL` controls where streaming requests are routed. It is optional. When unset, the container defaults it to `BACKEND_URL/marge`, which is the soundcork-friendly behavior. If you use a backend that should not receive the `/marge` suffix, set `STREAMING_URL` explicitly:

```env
STREAMING_URL=http://soundcork:8000/marge
```

The startup script always regenerates `stockholm/json/config.json` from `stockholm/json/backup.json`, so any URL environment changes show up after a container restart.

Optional Marge session values:

```env
MARGE_AUTH_TOKEN=
MARGE_ACCOUNT_ID=
```

`MARGE_AUTH_TOKEN` is the auth string stored by the Stockholm/SoundTouch flow. The backend also accepts `margeAuthToken` and `margeAccountID` aliases.

If these variables are set, they overwrite the same keys in `state/native-state.json` on startup and are persisted there. If they are absent, values in `state/native-state.json` will be used if existing. Treat both `.env` and `state/native-state.json` as sensitive local files.

## Networking

The default compose file uses host networking so SSDP multicast discovery works by default on Linux and other host-network-capable setups.

If you are on Windows or otherwise need bridge networking, use the override file:

```shell
docker compose -f docker-compose.yml -f docker-compose.build.yml -f docker-compose.windows.yml up --build -d
```

The override restores port publishing and bridge networking for Docker Desktop setups that do not support host networking. The build override can be omitted if you want to run the published `ghcr.io` image instead of a local rebuild.

## Custom CA Certificate

If your SoundTouch speakers use HTTPS with a certificate signed by a private or self-signed CA (e.g. a local fritz.box domain), the JVM inside the container will reject the connection with a `PKIX path building failed` error.

To trust a custom CA, place the CA certificate in PEM format at `config/custom-ca.crt` and uncomment the volume mount in `docker-compose.yml`:

```yaml
- ./config/custom-ca.crt:/app/custom-ca.crt:ro
```

The entrypoint imports the certificate into the JVM truststore automatically on startup. No rebuild is required — only a container restart.

To extract the certificate from a running speaker or local host:

```shell
openssl s_client -connect <speaker-host>:443 -showcerts 2>/dev/null </dev/null \
  | openssl x509 -outform PEM > config/custom-ca.crt
```

Use this when the server certificate is self-signed. If the certificate is issued by a local CA (e.g. from a FritzBox or internal PKI), download the root CA certificate from your router's admin interface instead and save it as `config/custom-ca.crt`.

## Local Building

The default `docker-compose.yml` runs the published image from `ghcr.io`, so `docker compose up --build` does not switch you to a local rebuild by itself. Use the build override file when you want Compose to compile the image from this checkout.

### Local Java Run

You can also run the backend locally with Java 21 and Gradle:

```shell
./gradlew run
```

> [!NOTE]
> If you run the service outside of docker then you will need to unzip and patch Stockholm on your own (or run the docker setup first).
> See `docker-entrypoint.sh` for details.

To disable frontend debug logging, set `frontendLoggingLevel` to `0` in `config/backend-config.json`.

## Logging in

If using soundcork as the backend, you can login to your account just using your account number and any domain as an email address, plus any password.  So `1234567@example.com`

After you log in for the first time, the app will try to take you through setting up your speakers.  If you have already configured all of your speakers, you should be able just to refresh `http://localhost:8088/` and access the main app.

## Known Issues

- Bose login/OAuth flows appear to be unavailable and may become impossible as the SoundTouch cloud shutdown approaches.
- HTTP reverse proxies do not yet work reliably; avoid TLS encrypted reverse proxies for now.
- Since this app connects to SoundTouch speakers via unencrypted WebSockets, the browser may block the connection if the frontend is served over HTTPS. Use HTTP for the frontend.
