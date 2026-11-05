#!/usr/bin/env python3
"""Private Codex state and daemon for tests; never bootstrap user services."""
import contextlib
import json
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile


@contextlib.contextmanager
def isolated_codex(binary=None, model_provider="fixture_offline"):
    executable = shutil.which(binary or "codex")
    if not executable:
        raise RuntimeError("Codex CLI is required for the isolated fixture")
    root = Path(tempfile.mkdtemp(prefix="remote-gpt-codex-"))
    data = root / "data"
    data.mkdir(mode=0o700)
    # No real credentials or user configuration are copied. All test model
    # requests must explicitly select a localhost fixture provider.
    (data / "config.toml").write_text(f'''model_provider = {json.dumps(model_provider)}
[model_providers.{json.dumps(model_provider)}]
name = "Offline test default"
base_url = "http://127.0.0.1:1/v1"
wire_api = "responses"
requires_openai_auth = false
[analytics]
enabled = false
''')
    wrapper = root / "codex-fixture"
    wrapper.write_text("#!/bin/sh\nexec env -u OPENAI_API_KEY -u CODEX_API_KEY " +
                       shlex.quote("CODEX_HOME=" + str(data)) + " " + shlex.quote(executable) + ' "$@"\n')
    wrapper.chmod(0o700)
    marker = root / "fixture.json"
    marker.write_text(json.dumps({"purpose": "remote-gpt-isolated-test", "data": str(data)}))
    try:
        subprocess.run([str(wrapper), "app-server", "daemon", "start"], check=True,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        yield {"codex": str(wrapper), "data": str(data), "root": str(root)}
    finally:
        # The wrapper always selects this fixture's state/control socket.
        # Never fall back to the system CLI when stopping a failed fixture.
        subprocess.run([str(wrapper), "app-server", "daemon", "stop"], check=True,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        # Preserve the private state for diagnosis if stop fails.
        shutil.rmtree(root)


def require_fixture(binary):
    """Fail closed before any --exercise mutation, including SSH invocations."""
    executable = Path(binary).absolute()
    marker = executable.parent / "fixture.json"
    try:
        metadata = json.loads(marker.read_text())
        if (metadata["purpose"] != "remote-gpt-isolated-test" or executable.name != "codex-fixture"
                or Path(metadata["data"]).parent != executable.parent):
            raise ValueError("Not a private fixture wrapper")
    except (OSError, ValueError, KeyError) as error:
        raise RuntimeError("Mutating probes require the private codex-fixture wrapper") from error
