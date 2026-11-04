#!/usr/bin/env python3
"""Run real Android SSH checks against disposable host keys and private keys."""
import getpass
import json
import http.server
import threading
from probe import Client, ModelFixture
import os
from pathlib import Path
import shutil
import shlex
import subprocess
from ssh_fixture import servers

adb = os.environ.get("ADB", "adb")
serial = os.environ.get("ANDROID_SERIAL", "emulator-5556")
if not serial.startswith("emulator-"):
    raise SystemExit("Use a disposable emulator; this check clears the app data.")
base = [adb, "-s", serial]
package = json.loads(Path("app/build/outputs/apk/debug/output-metadata.json").read_text())["applicationId"]
subprocess.run(base + ["install", "-r", "app/build/outputs/apk/debug/app-debug.apk"], check=True)
subprocess.run(base + ["install", "-r", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"], check=True)
subprocess.run(base + ["shell", "pm", "clear", package], check=True)
api = int(subprocess.check_output(base + ["shell", "getprop", "ro.build.version.sdk"], text=True).strip())
if api >= 33:
    subprocess.run(base + ["shell", "cmd", "locale", "set-app-locales", package, "--user", "0", "--locales", "zh"], check=True)
with servers() as fixture:
    for port in fixture["ports"]:
        subprocess.run(base + ["reverse", f"tcp:{port}", f"tcp:{port}"], check=True)
    Path(fixture["root"], "device-note.txt").write_text("device edit fixture")
    model_handler = ModelFixture
    if os.environ.get("COMMAND_OUTPUT_FIXTURE") == "1":
        program = "import sys,time\nfor i in range(180):\n print(f'GHUB_ROW_{i:03d} 中文', flush=True)\n time.sleep(0.02)\nsys.exit(7)"
        class CommandModelFixture(ModelFixture):
            probe_command = "python3 -c " + shlex.quote(program)
        model_handler = CommandModelFixture
    model = http.server.ThreadingHTTPServer(("127.0.0.1", 0), model_handler)
    threading.Thread(target=model.serve_forever, daemon=True).start()
    client = Client([fixture["codex"], "app-server", "proxy"])
    thread = client.request("thread/start", {
        "cwd": fixture["root"], "ephemeral": False, "modelProvider": "remote_gpt_device_fixture", "model": "gpt-5.4",
        "approvalPolicy": "on-request", "approvalsReviewer": "user", "sandbox": "read-only",
        "config": {"model_providers.remote_gpt_device_fixture": {
            "name": "Remote GPT device fixture", "base_url": f"http://127.0.0.1:{model.server_port}/v1",
            "wire_api": "responses", "requires_openai_auth": False, "supports_websockets": False}, "features.unified_exec": True}
    })["thread"]["id"]
    client.request("thread/name/set", {"threadId": thread, "name": "Android 设备验证"})
    client.request("turn/start", {"threadId": thread, "input": [{"type": "text", "text": "Run the isolated approval probe."}]})
    approval = client.wait(lambda m: m.get("method") == "item/commandExecution/requestApproval" and m.get("params", {}).get("threadId") == thread, 45)
    subprocess.run(base + ["shell", "run-as", package, "mkdir", "-p", "files"], check=True)
    subprocess.run(base + ["shell", "run-as", package, "sh", "-c", "'cat > files/device-test-key'"],
                   input=Path(fixture["encrypted_ed25519"]).read_bytes(), check=True)
    try:
        selection = ["-e", "class", os.environ["DEVICE_TEST_CLASS"]] if os.environ.get("DEVICE_TEST_CLASS") else []
        result = subprocess.run(base + ["shell", "am", "instrument", "-w", "-r"] + selection + [
            "-e", "commandOutputFixture", os.environ.get("COMMAND_OUTPUT_FIXTURE", "0"),
            "-e", "jumpPorts", ",".join(map(str, fixture["ports"][:-1])), "-e", "keyPassphrase", "fixture-only-passphrase",
            "-e", "threadId", thread, "-e", "fixtureRoot", fixture["root"],
            "-e", "sshHost", "127.0.0.1", "-e", "sshPort", str(fixture["ports"][-1]), "-e", "sshUser", getpass.getuser(),
            "-e", "fingerprint", fixture["fingerprint"], "-e", "codexPath", fixture["codex"],
            package + ".test/androidx.test.runner.AndroidJUnitRunner"], text=True, capture_output=True, timeout=300)
        print(result.stdout)
        artifact = Path("dist/device-workbench-" + serial + ".png")
        artifact.parent.mkdir(exist_ok=True)
        screenshot = subprocess.run(base + ["exec-out", "run-as", package, "cat", "files/device-workbench.png"], capture_output=True)
        if screenshot.returncode == 0 and screenshot.stdout.startswith(b"\x89PNG"): artifact.write_bytes(screenshot.stdout)
        if result.returncode or "FAILURES!!!" in result.stdout or "INSTRUMENTATION_FAILED" in result.stdout or "OK (" not in result.stdout:
            raise SystemExit("Device verification failed")
    finally:
        for port in fixture["ports"]:
            subprocess.run(base + ["reverse", "--remove", f"tcp:{port}"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(["tmux", "-L", "remote-gpt-device-" + str(fixture["ports"][-1]), "kill-server"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            try:
                client.send({"id": approval["id"], "result": {"decision": "cancel"}})
            except Exception: pass
            client.request("thread/archive", {"threadId": thread})
        finally:
            client.close()
            model.shutdown()
        subprocess.run(base + ["shell", "run-as", package, "rm", "-f", "files/device-test-key"], check=False)
