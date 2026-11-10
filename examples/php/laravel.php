<?php
// Example queued job handler. $crash already contains the saved original report.
use Illuminate\Support\Facades\Http;

$response = Http::withToken(config('services.stacktrace.token'))
    ->connectTimeout(3)->timeout(15)
    ->post(rtrim(config('services.stacktrace.url'), '/') . '/api/v1/retrace', [
        'applicationId' => $crash->application_id,
        'versionCode' => $crash->version_code,
        'stackTrace' => $crash->stack_trace,
    ]);

if ($response->successful() && $response->json('success') === true) {
    $crash->translated_stack_trace = $response->json('translated');
    $crash->save();
}
// Keep stack_trace intact. Retry transient network/5xx failures; surface validation/auth errors.
// If debug/release share a version code, include the explicit variant from your crash metadata.
// config/services.php:
// 'stacktrace' => ['url' => env('STACKTRACE_URL'), 'token' => env('STACKTRACE_API_TOKEN')],
