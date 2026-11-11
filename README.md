# StackTrace Studio

An Android stack trace deobfuscation utility for Linux/Windows desktop,
web and an independent REST API. Original requirements are in `readme.txt`.

## Current status

The R8 engine, offline Compose desktop, independent Ktor API and Compose/Wasm
browser application are implemented. The automated engine, storage, API, client and rendered-desktop checks pass. The browser
workflow, packaged Linux runtime and plain PHP integration have also passed
end-to-end checks. A portable Linux archive is available in `build/`.

Linux DEB and Windows MSI installation/runtime, browser/PHP workflows and Docker
deployment passed [final CI](https://github.com/mahdiy2/TraceWise/actions/runs/42825228608).
See [verification evidence](docs/VERIFICATION.md), `DEVELOPMENT_PROGRESS.md` and
[installation instructions](docs/INSTALLATION.md).

## Development

Requires JDK 21. The included wrapper uses Gradle 8.13.

```sh
./scripts/gradle.sh :shared:jvmTest :shared:compileKotlinWasmJs
```

The shared module targets JVM and browser Wasm. Desktop uses local SQLite
and filesystem storage; web calls Ktor. Both JVM entry points reuse
Google's official R8 Retrace engine. Uploaded APKs are optional inert artifacts.

## Try deobfuscation

```sh
./scripts/gradle.sh :retrace:test
./scripts/gradle.sh :retrace:run --args='../examples/mapping.txt ../examples/crash.txt'
./scripts/gradle.sh :retrace:installDist
```

Gradle's run task uses the retrace module directory as its working directory.
The installed CLI in `retrace/build/install/retrace/bin/retrace` accepts a mapping
path and crash-log path. It writes translated text to stdout, errors to stderr,
and never modifies the input. Mapping files are essential; APKs are not needed.

Dependencies resolve from Maven Central and Google Maven. For networks where
Google Maven is unavailable, `STACKTRACE_GOOGLE_MAVEN_URL` can select a trusted
mirror (verification here used JetBrains’ Google Maven cache). Dependency versions
remain pinned. Java 21 must be installed;
set JAVA_HOME if the helper cannot locate your JDK.

## Desktop workspace

```sh
./scripts/gradle.sh :composeApp:run
./scripts/gradle.sh :composeApp:packageDeb  # Linux host
./scripts/gradle.sh :composeApp:packageMsi  # Windows host
```

Linux DEB and Windows MSI installation and bundled-runtime checks passed CI.
The desktop stores SQLite metadata and private artifacts in
`~/.stacktrace-studio`; set `STACKTRACE_DATA_DIR` to override. It requires no
backend. Create/select a project, add/select a build, upload mapping.txt, then
paste or load a trace and click Retrace. Batch mode separates traces with a line
containing `---`. APK upload is optional and never executes the archive.

Deleted or replaced artifacts are reclaimed after their last build reference is
removed. Active retraces hold private immutable snapshots until completion, so
concurrent replacement/deletion cannot change their mapping. Failed mapping
validation leaves the previous artifact active. APKs must be ZIP archives with
AndroidManifest.xml. Upload limits currently default to 50 MiB for mappings and
500 MiB for APKs; server limits are configurable using the environment settings below.

## Independent backend

```sh
export JAVA_HOME=/path/to/jdk-21
export STACKTRACE_API_TOKEN=your-random-secret
export STACKTRACE_DATA_DIR="$PWD/data"
./scripts/gradle.sh :server:run
# Or build an independent distribution:
./scripts/gradle.sh :server:installDist
server/build/install/server/bin/server
```

The backend listens on port 8080 by default. `PORT` changes it.
Without a token, `STACKTRACE_DEV_MODE=true` explicitly permits unauthenticated
localhost-only development. A configured token remains required even in
development mode. See `.env.example` for configurable mapping/APK limits,
port and retrace concurrency. Environment files are examples; the JVM launcher
does not automatically load `.env`.

See [API documentation](docs/API.md) for routes, error codes, registration and
upload commands. PHP examples are in `examples/php/`. Keep original crash reports
in your existing system and store the translated result alongside them.

Docker deployment files are provided, with verification status in the progress log:

```sh
cp .env.example .env
# Edit .env to set a real random token.
docker compose up --build -d
```

Compose publishes only localhost port 8080 and persists data in a named volume.
For remote access, place it behind your existing HTTPS reverse proxy. The desktop
continues to work independently of this service.

## Browser application

The browser client uses Compose/Wasm, shared Kotlin models and the shared Ktor
HTTP client. It calls the backend; R8 never runs inside the browser.

```sh
./scripts/gradle.sh :webApp:wasmJsBrowserDistribution :server:installDist
export STACKTRACE_WEB_DIR="$PWD/webApp/build/dist/wasmJs/productionExecutable"
export STACKTRACE_DATA_DIR="$PWD/data"
export STACKTRACE_API_TOKEN=your-random-secret
server/build/install/server/bin/server
```

Open `http://localhost:8080` in a browser with WasmGC support and enter the API
token. Tokens are kept in memory, not local storage. Development mode may connect
with a blank token. The UI and API share one origin, so no CORS setup is required.
Use HTTPS for a remote deployment and browser clipboard access. File downloads
use generated text blobs; APK uploads use native multipart File objects.

The browser bundle can also be served by a separate web server if `/api` is
reverse-proxied to Ktor at the same origin. Keep the public web directory separate
from private storage; the server rejects configurations that expose storage
under the web root. Docker builds include the browser bundle automatically.

Browser end-to-end verification is tracked in `DEVELOPMENT_PROGRESS.md`.

## Browser verification

After building the web bundle and server distribution:

```sh
node scripts/browser-smoke.mjs
```

Requires Node with built-in WebSocket support and Chromium (`CHROMIUM` can select
its executable). It launches temporary local server/browser processes, uses actual
UI controls, uploads the example mapping, verifies R8 single/batch results,
checks original preservation, and compares clipboard and downloaded text. It
writes screenshots and `build/browser-verification.json`, then removes temporary
server/browser data. The test does not use your normal browser profile.

The desktop dashboard includes total projects/builds, recent builds, and recent
retrace metadata. Operation history is capped at 100 entries and stores counts,
project/build labels and timestamps, without original or translated crash text.

## Portable Linux application

`createDistributable` builds a self-contained app image with a bundled Java
runtime. The packaged image passed the offline R8/Compose smoke test on Linux.

```sh
./scripts/gradle.sh :composeApp:createDistributable
tar -czf build/StackTraceStudio-linux-x64.tar.gz -C composeApp/build/compose/binaries/main/app StackTraceStudio
# After extracting the archive:
./StackTraceStudio/bin/StackTraceStudio
# Isolated packaged-runtime check (does not change your workspace data):
STACKTRACE_SMOKE_REPORT="$PWD/native-smoke.txt" ./StackTraceStudio/bin/StackTraceStudio --smoke-test
```

The smoke check opens and closes a temporary workspace, performs actual R8
translation through the ViewModel and emits `STACKTRACE_STUDIO_SMOKE_OK` on
success. A graphical display is required. Linux DEB and Windows MSI installers
use their platform's packaging tools; their separate verification status remains
in the progress log.

## PHP verification

```sh
php -l examples/php/retrace.php
php -l examples/php/laravel.php
python3 scripts/php-smoke.py
```

The smoke script requires PHP 8+ with ext-curl (`PHP_BIN` can select its path) and
a built server distribution. It creates temporary backend data, executes the
plain PHP example against actual R8, verifies auth failure and original
preservation, and stops the backend afterward. Both examples were linted here;
the Laravel snippet has not been executed inside a Laravel application.

Ctrl+Enter retraces the current input on desktop and web. Editors highlight
exception, class, method, source-file and line-number ranges. Tabs display as
spaces through an offset mapping; source text, clipboard output and downloads
retain their original characters. Editing/loading new input clears stale results.

Dependency versions are centralized in `gradle/libs.versions.toml`. Network
restricted builds can override repository endpoints with `-PgoogleMavenUrl=...`
and `-PmavenCentralUrl=...`; defaults remain the official repositories.
