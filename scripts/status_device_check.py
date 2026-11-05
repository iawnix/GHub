#!/usr/bin/env python3
"""Verify unopened-session status sync on a disposable emulator with local SSH/model fixtures."""
import getpass
import http.server
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import threading
import time
from probe import ModelFixture
from ssh_fixture import servers


class DelayedAnswer(ModelFixture):
    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        if self.headers.get('Content-Encoding') == 'zstd':
            import zstandard
            raw = zstandard.ZstdDecompressor().decompress(raw)
        body = json.loads(raw)
        user = next((i for i in reversed(body.get('input', [])) if i.get('role') == 'user'), {})
        if any('Stream sync fixture' in part.get('text', '') for part in user.get('content', []) if isinstance(part, dict)):
            self.stream_answer()
            return
        time.sleep(20)
        rid = 'resp_status_' + secrets.token_hex(4)
        item = {'id': 'msg_' + rid, 'type': 'message', 'role': 'assistant', 'status': 'completed',
                'content': [{'type': 'output_text', 'text': 'Local status sync fixture completed.', 'annotations': []}]}
        events = [
            {'type': 'response.created', 'response': {'id': rid, 'status': 'in_progress'}},
            {'type': 'response.output_item.added', 'output_index': 0, 'item': item},
            {'type': 'response.output_item.done', 'output_index': 0, 'item': item},
            {'type': 'response.completed', 'response': {'id': rid, 'status': 'completed', 'output': [item],
                'usage': {'input_tokens': 1, 'output_tokens': 1, 'total_tokens': 2}}},
        ]
        data = ''.join(f"event: {e['type']}\ndata: {json.dumps(e)}\n\n" for e in events).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def stream_answer(self):
        rid = 'resp_stream_' + secrets.token_hex(4)
        item = {'id': 'msg_' + rid, 'type': 'message', 'role': 'assistant', 'status': 'in_progress', 'content': []}
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Connection', 'close')
        self.end_headers()
        self.close_connection = True

        def emit(event):
            self.wfile.write(f"event: {event['type']}\ndata: {json.dumps(event)}\n\n".encode())
            self.wfile.flush()

        emit({'type': 'response.created', 'response': {'id': rid, 'status': 'in_progress'}})
        emit({'type': 'response.output_item.added', 'output_index': 0, 'item': item})
        emit({'type': 'response.content_part.added', 'output_index': 0, 'item_id': item['id'], 'content_index': 0,
              'part': {'type': 'output_text', 'text': '', 'annotations': []}})
        text = ''
        for i in range(640):
            delta = f'SYNC_{i:03d}\n'
            text += delta
            emit({'type': 'response.output_text.delta', 'output_index': 0, 'item_id': item['id'], 'content_index': 0, 'delta': delta})
            time.sleep(.025)
        item.update(status='completed', content=[{'type': 'output_text', 'text': text, 'annotations': []}])
        emit({'type': 'response.output_item.done', 'output_index': 0, 'item': item})
        emit({'type': 'response.completed', 'response': {'id': rid, 'status': 'completed', 'output': [item],
              'usage': {'input_tokens': 1, 'output_tokens': 640, 'total_tokens': 641}}})


def main():
    serial = os.environ.get('ANDROID_SERIAL', 'emulator-5570')
    if not serial.startswith('emulator-'):
        raise SystemExit('Use a disposable emulator; this resets the preview app.')
    adb = [os.environ.get('ADB', 'adb'), '-s', serial]
    package = 'dev.remotegpt.preview'
    for apk in ['app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
        subprocess.run(adb + ['install', '-r', apk], check=True)
    subprocess.run(adb + ['shell', 'pm', 'clear', package], check=True)
    with servers(model_provider="remote_gpt_status_fixture") as fixture:
        model = http.server.ThreadingHTTPServer(('127.0.0.1', 0), DelayedAnswer)
        threading.Thread(target=model.serve_forever, daemon=True).start()
        subprocess.run(adb + ['shell', 'run-as', package, 'mkdir', '-p', 'files'], check=True)
        subprocess.run(adb + ['shell', 'run-as', package, 'sh', '-c', "'cat > files/device-test-key'"],
                       input=Path(fixture['encrypted_ed25519']).read_bytes(), check=True)
        try:
            subprocess.run(adb + ['reverse', f'tcp:{fixture["ports"][-1]}', f'tcp:{fixture["ports"][-1]}'], check=True)
            result = subprocess.run(adb + ['shell', 'am', 'instrument', '-w', '-r',
                '-e', 'class', os.environ.get('DEVICE_TEST_CLASS', 'dev.remotegpt.app.SessionStatusDeviceTest'),
                '-e', 'statusFixturePort', str(model.server_port),
                '-e', 'fixtureRoot', fixture['root'], '-e', 'sshPort', str(fixture['ports'][-1]), '-e', 'sshHost', '127.0.0.1',
                '-e', 'sshUser', getpass.getuser(), '-e', 'fingerprint', fixture['fingerprint'],
                '-e', 'codexPath', fixture["codex"], package + '.test/androidx.test.runner.AndroidJUnitRunner'],
                text=True, capture_output=True, timeout=180)
            print(result.stdout, flush=True)
            if result.returncode or 'FAILURES!!!' in result.stdout or 'OK (' not in result.stdout:
                raise SystemExit('Status synchronization verification failed')
        finally:
            subprocess.run(adb + ['reverse', '--remove', f'tcp:{fixture["ports"][-1]}'], check=False)
            subprocess.run(adb + ['shell', 'run-as', package, 'rm', '-f', 'files/device-test-key'], check=False)
            model.shutdown()


if __name__ == '__main__':
    main()
