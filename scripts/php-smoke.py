#!/usr/bin/env python3
"""Lint both PHP examples and test the cURL CLI against an isolated real backend."""
import datetime
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

root = Path(__file__).resolve().parent.parent
php = os.environ.get('PHP_BIN') or shutil.which('php')
if not php:
    raise SystemExit('Set PHP_BIN or install PHP 8+ with ext-curl')
for example in ['retrace.php', 'laravel.php']:
    subprocess.run([php, '-l', str(root / 'examples/php' / example)], check=True)
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    port = sock.getsockname()[1]
base = f'http://127.0.0.1:{port}'
with tempfile.TemporaryDirectory(prefix='studio-php-') as directory:
    env = os.environ.copy()
    env.update(JAVA_HOME=env.get('JAVA_HOME', str(Path.home() / '.jdks/jbr-21.0.11')),
               STACKTRACE_DATA_DIR=directory, STACKTRACE_API_TOKEN='php-test-token', PORT=str(port))
    process = subprocess.Popen([str(root / 'server/build/install/server/bin/server')], env=env,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    def send(path, value, content_type='application/json'):
        body = json.dumps(value).encode() if content_type == 'application/json' else value
        request = urllib.request.Request(base + path, data=body,
            headers={'Authorization': 'Bearer php-test-token', 'Content-Type': content_type})
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.load(response)
    try:
        for attempt in range(100):
            if process.poll() is not None:
                raise RuntimeError('Backend startup failed: ' + process.stdout.read().decode())
            try:
                with urllib.request.urlopen(base + '/health', timeout=1) as response:
                    if response.read() == b'ok':
                        break
            except (urllib.error.URLError, TimeoutError):
                time.sleep(0.1)
        else:
            raise RuntimeError('Backend did not become healthy')
        project = send('/api/v1/projects', {'name':'PHP smoke', 'applicationId':'com.example.app'})
        build = send(f'/api/v1/projects/{project["id"]}/builds', {'versionName':'1','versionCode':72})
        boundary = 'studio-php-boundary'
        multipart = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="mapping.txt"\r\nContent-Type: text/plain\r\n\r\n').encode()
        multipart += (root / 'examples/mapping.txt').read_bytes() + f'\r\n--{boundary}--\r\n'.encode()
        send(f'/api/v1/builds/{build["id"]}/mapping', multipart, 'multipart/form-data; boundary='+boundary)
        env['STACKTRACE_URL'] = base
        crash = root / 'examples/crash.txt'
        original = crash.read_bytes()
        result = subprocess.run([php, str(root / 'examples/php/retrace.php'), str(crash)], env=env,
                                capture_output=True, text=True, check=True)
        assert 'com.example.Crash.explode(Crash.kt:42)' in result.stdout, result.stdout
        assert crash.read_bytes() == original, 'PHP example modified the original log'
        send(f'/api/v1/projects/{project["id"]}/builds', {'versionName':'1','versionCode':72,'variant':'debug'})
        ambiguous = subprocess.run([php, str(root / 'examples/php/retrace.php'), str(crash)], env=env,
                                   capture_output=True, text=True)
        assert ambiguous.returncode == 1 and 'AMBIGUOUS_BUILD' in ambiguous.stderr
        explicit = subprocess.run([php, '-r', 'require $argv[1]; echo retraceCrash("com.example.app",72,file_get_contents($argv[2]),"release");',
                                   str(root / 'examples/php/retrace.php'), str(crash)], env=env,
                                  capture_output=True, text=True, check=True)
        assert 'com.example.Crash.explode(Crash.kt:42)' in explicit.stdout
        env['STACKTRACE_API_TOKEN'] = 'invalid-token' 
        rejected = subprocess.run([php, str(root / 'examples/php/retrace.php'), str(crash)], env=env,
                                  capture_output=True, text=True)
        assert rejected.returncode == 1 and 'UNAUTHORIZED' in rejected.stderr
        report = {'passed':True, 'checkedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  'checks':['PHP syntax (both examples)', 'cURL HTTP integration with real R8', 'original preservation', 'explicit variant disambiguation', 'authentication failure']}
        (root / 'build/php-verification.json').write_text(json.dumps(report, indent=2))
        print('PHP integration smoke test passed')
    finally:
        process.terminate()
        try:
            process.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.communicate()
