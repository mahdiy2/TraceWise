<?php
// PHP 8+, ext-curl. Keep storing the original crash before calling this function.
// STACKTRACE_URL=https://retrace.example.com STACKTRACE_API_TOKEN=... php retrace.php crash.txt
function retraceCrash(string $applicationId, int $versionCode, string $original, ?string $variant = null): string
{
    $base = getenv('STACKTRACE_URL');
    $token = getenv('STACKTRACE_API_TOKEN');
    if (!$base || !$token) {
        throw new RuntimeException('Configure STACKTRACE_URL and STACKTRACE_API_TOKEN');
    }
    $handle = curl_init(rtrim($base, '/') . '/api/v1/retrace');
    curl_setopt_array($handle, [
        CURLOPT_POST => true,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_CONNECTTIMEOUT => 3,
        CURLOPT_TIMEOUT => 15,
        CURLOPT_HTTPHEADER => ['Authorization: Bearer ' . $token, 'Content-Type: application/json'],
        CURLOPT_POSTFIELDS => json_encode([
            'applicationId' => $applicationId,
            'versionCode' => $versionCode,
            'variant' => $variant,
            'stackTrace' => $original,
        ], JSON_THROW_ON_ERROR),
    ]);
    try {
        $response = curl_exec($handle);
        if ($response === false) {
            throw new RuntimeException('Retrace service unavailable');
        }
        $status = curl_getinfo($handle, CURLINFO_RESPONSE_CODE);
        $result = json_decode($response, true, 512, JSON_THROW_ON_ERROR);
        if ($status !== 200 || ($result['success'] ?? false) !== true || !is_string($result['translated'] ?? null)) {
            throw new RuntimeException('Retrace failed: ' . ($result['code'] ?? $result['errorCode'] ?? 'UNKNOWN'));
        }
        return $result['translated'];
    } finally {
        curl_close($handle);
    }
}

if (realpath($_SERVER['SCRIPT_FILENAME'] ?? '') === __FILE__) {
    if ($argc !== 2 || !is_file($argv[1]) || filesize($argv[1]) > 1048576) {
        fwrite(STDERR, "Usage: php retrace.php crash.txt (maximum 1 MiB)\n");
        exit(2);
    }
    try {
        echo retraceCrash('com.example.app', 72, file_get_contents($argv[1])) . PHP_EOL;
    } catch (Throwable $error) {
        fwrite(STDERR, $error->getMessage() . PHP_EOL);
        exit(1);
    }
}
