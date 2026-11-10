# Development progress

## Implemented
- Kotlin 2.1.0 shared JVM/Wasm models and JSON contracts, shared Ktor HTTP client, Gradle 8.13 wrapper.
- Official R8 9.5.29 single/batch engine, inline/source/line restoration, input limits and sanitized diagnostics.
- SQLite projects/builds and private filesystem artifacts, streamed hashes, official mapping validation, optional APK validation and failed replacement preservation.
- Offline Compose Desktop/Koin/ViewModel: project edit/delete, build create/delete, mapping management, single/batch retrace, TXT input, copy/export, themes, responsive layouts, Ctrl+Enter and exception/class/method/source/line highlighting.
- Dashboard totals, recent builds and metadata-only retrace history (100 stored entries, ten returned); no crash text is retained.
- Ktor 3.0.3 API: CRUD, multipart uploads, mapping removal, retrace, application ID/version/variant selection, bearer auth, structured errors, payload-free logging, no-store responses, bounded retrace concurrency, configurable storage/port/limits.
- Compose/Wasm browser UI with shared models/API client, in-memory token, project/build management, native streamed file uploads, single/batch results, clipboard and download. Ktor can serve it at the same origin and rejects a web root containing private storage.
- CLI, PHP cURL/Laravel examples, API/setup/platform documentation, environment example, Docker/Compose files and platform CI workflow.
- Native self-test flag (--smoke-test) uses isolated temporary data and real R8, renders the desktop, emits a completion report and exits.

## Verified evidence
- 40 automated tests pass with zero failures, errors or skips: ten R8, ten storage, eight API, one cancellation, four HTTP client, three display-offset, three desktop ViewModel and one rendered desktop test.
- Rendered desktop test is not skipped: it enters a trace, clicks Retrace and verifies the actual local R8 result without a backend.
- Dashboard persistence test verifies totals across projects and that crash text is absent from SQLite bytes.
- Production Wasm bundle built successfully. Chromium UI E2E passed sign-in, project/build creation, mapping upload, TXT loading, actual R8 single/batch results, unchanged original, clipboard and TXT export. Report: build/browser-verification.json; screenshots: build/browser-*.png.
- Standalone server distribution built and real Netty HTTP workflow passed.
- Packaged Linux application image built through Compose createDistributable. Its bundled runtime passed --smoke-test and wrote build/native-smoke.txt.
- Portable Linux archive: build/StackTraceStudio-linux-x64.tar.gz (about 102 MiB compressed; includes Java and R8).
- PHP 8.4.27 was built only under build/tools after checking its source SHA-256 against PHP's official release metadata. Both examples lint successfully. The plain cURL example passed real-backend retrace, original preservation and rejected bad auth. Report: build/php-verification.json.
- CI YAML syntax/job structure parsed successfully. CI jobs themselves have NOT been executed against a connected repository.

## Remaining requirements / delivery gates
1. Linux DEB installation and Windows MSI/runtime verification remain pending native CI runs. The Linux portable bundled runtime has passed; this executor lacks DEB packaging tools and cannot run Windows.
2. Docker deployment verification remains pending CI: this executor has no Docker daemon socket.
3. GitHub repository supplied: https://github.com/mahdiy2/TraceWise.git. A separate checkout under ignored .delivery/TraceWise preserves this workspace; authentication is required to publish and run CI.
4. Laravel example has syntax verification only; no Laravel application is present. The complete plain PHP cURL integration is verified, including ambiguous variant handling.

## Completed final implementation checks
- Unreferenced mapping/APK bytes are reclaimed under SQLite write coordination. Active retraces pin immutable snapshots, and replacement/deletion/concurrent access are covered by tests.
- Coroutine/logcat frames, generated real R8 fixture, concurrent engine calls and cancellation cleanup are tested. Fixture regeneration and provenance are included.
- Latest browser production bundle passed real Chromium workflows, Ctrl+Enter, light/dark screenshots and 390-pixel viewport checks. Original text, clipboard and download equality were verified.
- Portable archive was extracted and launched from a directory containing spaces without JAVA_HOME; its bundled runtime passed the isolated smoke test.
- Requirement audit covers the requested core workflows, modular stack, API/PHP integration, offline desktop, browser HTTP client, limits/authentication, privacy, persistence and documentation. Native installer and Docker execution remain explicit delivery gates.
- Dependency versions are centralized in the Gradle catalog; official R8 is JVM-only. No custom mapping parser or critical placeholder implementation remains.

## Build environment notes
- Google/Maven endpoints were intermittently unavailable (403/404/TLS errors). Versions were not changed to work around this.
- Dependency repositories use Maven Central and Google Maven. STACKTRACE_GOOGLE_MAVEN_URL optionally selects a trusted mirror; verified desktop builds here used JetBrains' Google Maven cache.
- A workspace-local ignored .gradle-home retains build dependencies for this executor. In-process Kotlin compilation avoids restricted global daemon-cache writes.
- Root .git is a placeholder; delivery commits are prepared in the separate .delivery/TraceWise checkout.

The full readme.txt objective remains active until the native installer and Docker delivery gates pass.
