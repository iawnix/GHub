#!/usr/bin/env python3
"""Isolated unprivileged OpenSSH fixtures. Never changes ~/.ssh or system sshd."""
import contextlib
import getpass
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
from codex_fixture import isolated_codex


@contextlib.contextmanager
def servers(model_provider="fixture_offline"):
    with isolated_codex(model_provider=model_provider) as codex, tempfile.TemporaryDirectory(prefix="remote-gpt-ssh-") as folder:
        root = Path(folder)
        key = root / "identity"
        host = root / "host"
        for path in (key, host):
            subprocess.run(["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", str(path)], check=True)
        encrypted = {}
        authorized = Path(str(key) + ".pub").read_text()
        for name, kind, fmt in (("encrypted_ed25519", "ed25519", None), ("encrypted_rsa", "rsa", "PEM")):
            identity = root / name
            command = ["ssh-keygen", "-q", "-t", kind, "-N", "fixture-only-passphrase", "-f", str(identity)]
            if fmt: command += ["-m", fmt, "-b", "2048"]
            subprocess.run(command, check=True)
            authorized += Path(str(identity) + ".pub").read_text()
            encrypted[name] = str(identity)
        authorized_file = root / "authorized_keys"
        authorized_file.write_text(authorized)
        fingerprint = subprocess.check_output(["ssh-keygen", "-lf", str(host) + ".pub", "-E", "sha256"], text=True).split()[1]
        processes, ports = [], []
        try:
            for i in range(3):
                with socket.socket() as sock:
                    sock.bind(("127.0.0.1", 0))
                    port = sock.getsockname()[1]
                ports.append(port)
                config = root / f"sshd-{i}.conf"
                config.write_text(f"""Port {port}
ListenAddress 127.0.0.1
HostKey {host}
PidFile {root}/pid-{i}
AuthorizedKeysFile {authorized_file}
StrictModes no
PubkeyAuthentication yes
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM no
AllowUsers {getpass.getuser()}
Subsystem sftp internal-sftp
LogLevel ERROR
""")
                log = root / f"sshd-{i}.log"
                process = subprocess.Popen([shutil.which("sshd"), "-D", "-f", str(config), "-E", str(log)])
                processes.append(process)
                for _ in range(100):
                    if process.poll() is not None:
                        raise RuntimeError(log.read_text())
                    try:
                        with socket.create_connection(("127.0.0.1", port), .1):
                            break
                    except OSError:
                        time.sleep(.05)
                else:
                    raise RuntimeError("OpenSSH fixture did not start")
            known = root / "known_hosts"
            public = (Path(str(host) + ".pub")).read_text().split()
            known.write_text("".join(f"[127.0.0.1]:{port} {public[0]} {public[1]}\n" for port in ports))
            config = root / "client.conf"
            config.write_text("".join(f"Host {name}\n HostName 127.0.0.1\n Port {port}\n" for name, port in zip(["jump1", "jump2", "target"], ports)) +
                              f"Host *\n User {getpass.getuser()}\n IdentityFile {key}\n IdentitiesOnly yes\n UserKnownHostsFile {known}\n StrictHostKeyChecking yes\n BatchMode yes\nHost target\n ProxyJump jump1,jump2\n")
            yield {"codex": codex["codex"], "codex_data": codex["data"], "root": str(root), "key": str(key), "ports": ports, "fingerprint": fingerprint, "config": str(config), **encrypted}
        finally:
            for process in processes:
                process.terminate()
            for process in processes:
                process.wait(timeout=5)


if __name__ == "__main__":
    import sys
    with servers() as fixture:
        if "--jvm" in sys.argv:
            env = os.environ.copy()
            env.update(SSH_FIXTURE_KEY=fixture["key"], SSH_FIXTURE_PORTS=",".join(map(str, fixture["ports"])),
                       SSH_FIXTURE_FINGERPRINT=fixture["fingerprint"], SSH_FIXTURE_USER=getpass.getuser(),
                       SSH_FIXTURE_ED25519=fixture["encrypted_ed25519"], SSH_FIXTURE_RSA=fixture["encrypted_rsa"],
                       SSH_FIXTURE_CODEX=fixture["codex"], SSH_FIXTURE_ROOT=fixture["root"])
            subprocess.run([os.environ.get("GRADLE_COMMAND", "./gradlew"), ":codex-client:test", "--tests", "*SshIntegrationTest", "--rerun-tasks"], env=env, check=True)
        else:
            subprocess.run([sys.executable, "scripts/probe.py", "--ssh-config", fixture["config"], "--codex", fixture["codex"], "--exercise"], check=True)
            subprocess.run([sys.executable, "scripts/question_probe.py", "--ssh-config", fixture["config"], "--codex", fixture["codex"]], check=True)
