# REST API

Base path: `/api/v1`. Every API route requires `Authorization: Bearer <token>`.
Use JSON request/response bodies except uploads. `/health` is an unauthenticated
liveness endpoint returning `ok`. Mapping/APK bytes have no download endpoint.

| Method | Path | Body / behavior |
|---|---|---|
| GET | /dashboard | Total projects/builds, recent builds and operation metadata |
| GET | /projects | List projects |
| POST | /projects | `{ "name":"Example", "applicationId":"com.example.app", "description":null }` |
| GET | /projects/{projectId} | Project metadata |
| PUT | /projects/{projectId} | Same body as creation; applicationId is immutable |
| DELETE | /projects/{projectId} | Remove project and its builds; 204 |
| GET | /projects/{projectId}/builds | List builds |
| POST | /projects/{projectId}/builds | `{ "versionName":"1.0", "versionCode":72, "variant":"release" }` |
| GET | /builds/{buildId} | Build metadata, including artifact hashes |
| DELETE | /builds/{buildId} | Remove build; 204 |
| POST | /builds/{buildId}/mapping | multipart/form-data, one `file` named *.txt |
| DELETE | /builds/{buildId}/mapping | Unlink mapping; 204 |
| POST | /builds/{buildId}/apk | multipart/form-data, one `file` named *.apk (optional) |
| POST | /retrace | Single retrace request below |
| POST | /retrace/batch | `stackTraces` array instead of `stackTrace`; ordered `results` response |

Project/build creation returns 201 and metadata with generated UUID `id` and UTC
`createdAt`. Metadata never includes storage paths. An application ID is unique;
build identity is project + versionCode + variant (`debug` or `release`).

```json
{"applicationId":"com.example.app","versionCode":72,"stackTrace":"java.lang.RuntimeException\n\tat a.b(SourceFile:1)"}
```

Select exactly one of `applicationId`, `projectId`, or `buildId`. Application or
project selection requires versionCode and optionally variant. A buildId is an
exact selector and cannot be combined with versionCode/variant. Multiple matching
variants return 409 `AMBIGUOUS_BUILD`; the service never guesses a mapping.

Successful response fields: `success`, `original`, `translated`, `status`, `build`,
`warnings`, `errorCode`, `message`. Original input is preserved. Unresolved frames
remain present. The batch response is `{ "results": [...] }` with one result per
input, including per-trace errors. Trace errors return 422 for the single endpoint;
batch returns 200 with individual result statuses.

Structured endpoint errors have `{ "code":"...", "message":"..." }`:
400 invalid JSON/request/file, 401 invalid token, 404 missing project/build,
409 duplicate or ambiguous build/missing mapping, 413 oversized body/upload,
415 unsupported content type, 500 sanitized internal/storage failure.

Trace limits: 1 MiB each, 100 traces and 10 MiB combined per batch, 12 MiB JSON body.
Mapping/APK limits are configured independently. APKs are checked as ZIP archives
containing AndroidManifest.xml and are never installed or executed.

## Register an application and upload a mapping

```sh
curl -H "Authorization: Bearer $STACKTRACE_API_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Example","applicationId":"com.example.app"}' \
  http://localhost:8080/api/v1/projects
# Use the returned project UUID:
curl -H "Authorization: Bearer $STACKTRACE_API_TOKEN" -H 'Content-Type: application/json' \
  -d '{"versionName":"1.0","versionCode":72}' \
  http://localhost:8080/api/v1/projects/PROJECT_UUID/builds
# Use the returned build UUID:
curl -H "Authorization: Bearer $STACKTRACE_API_TOKEN" \
  -F 'file=@mapping.txt;type=text/plain' \
  http://localhost:8080/api/v1/builds/BUILD_UUID/mapping
```

Existing PHP systems keep storing original crashes, call retrace using application
ID/version code, and store `translated` alongside the original. No mapping parser
or desktop project IDs are needed in PHP. See `examples/php/retrace.php` for plain
PHP and `examples/php/laravel.php` for a queued Laravel job integration. Use an
asynchronous job/retry policy when API latency must not delay crash ingestion.

Dashboard operation history is capped at 100 stored entries and returns the ten
most recent operations. It contains labels, timestamps and success counts, without
crash text or mapping contents. API responses use Cache-Control: no-store.

Artifact replacement and deletion reclaim unreferenced bytes. Retrace operations
pin an immutable snapshot of the selected mapping for their lifetime. Publication,
snapshot creation and collection coordinate through SQLite write reservations;
responses refer to the mapping metadata actually selected for that operation.

The plain PHP helper accepts an optional fourth argument for variants, for example
`retraceCrash($applicationId, $versionCode, $original, 'release')`. When both debug
and release have the same application ID/version code, an omitted variant returns
AMBIGUOUS_BUILD rather than choosing a potentially incorrect mapping.
