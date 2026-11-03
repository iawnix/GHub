#!/usr/bin/env python3
"""Exercise user-input requests against a private daemon and localhost model.

No real model, credentials, or user threads are used. Optionally pass --ssh-config
and the private --codex wrapper supplied by ssh_fixture.py.
"""
import argparse
import http.server
import json
import shlex
import threading

from codex_fixture import isolated_codex, require_fixture
from probe import Client


class QuestionModel(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_POST(self):
        raw = self.rfile.read(int(self.headers["Content-Length"]))
        if self.headers.get("Content-Encoding") == "zstd":
            import zstandard
            raw = zstandard.ZstdDecompressor().decompress(raw)
        body = json.loads(raw)
        outputs = [i for i in body.get("input", []) if i.get("type") == "function_call_output"]
        if outputs:
            expected = {"answers": {"scope": {"answers": ["Prepare structures"]}}}
            assert json.loads(outputs[-1]["output"]) == expected, outputs[-1]
            item = {"id": "msg_questions", "type": "message", "role": "assistant", "status": "completed",
                    "content": [{"type": "output_text", "text": "Answer received.", "annotations": []}]}
        else:
            item = {"id": "fc_questions", "type": "function_call", "call_id": "call_questions",
                    "name": "request_user_input", "status": "completed", "arguments": json.dumps({"questions": [{
                        "id": "scope", "header": "Scope", "question": "How far should we go?",
                        "options": [{"label": "Prepare structures", "description": "Prepare usable structures"},
                                    {"label": "Compute descriptors", "description": "Run calculations"}]}]})}
        events = [
            {"type": "response.created", "response": {"id": "resp_questions", "status": "in_progress"}},
            {"type": "response.output_item.added", "output_index": 0, "item": item},
            {"type": "response.output_item.done", "output_index": 0, "item": item},
            {"type": "response.completed", "response": {"id": "resp_questions", "status": "completed", "output": [item],
                "usage": {"input_tokens": 1, "output_tokens": 1, "total_tokens": 2}}},
        ]
        data = "".join(f"event: {e['type']}\ndata: {json.dumps(e)}\n\n" for e in events).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def exercise(binary, ssh_config=None):
    require_fixture(binary)
    command = [binary, "app-server", "proxy"]
    if ssh_config:
        command = ["ssh", "-F", ssh_config, "target", shlex.join(command)]
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), QuestionModel)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    clients = []
    try:
        a = Client(command)
        clients.append(a)
        provider = {"name": "Question fixture", "base_url": f"http://127.0.0.1:{server.server_port}/v1",
                    "wire_api": "responses", "requires_openai_auth": False, "supports_websockets": False}
        thread = a.request("thread/start", {"cwd": "/tmp", "modelProvider": "questions_fixture", "model": "gpt-5.4",
            "config": {"model_providers.questions_fixture": provider, "features.default_mode_request_user_input": True},
            "developerInstructions": "This is an isolated question protocol test."})["thread"]["id"]
        a.request("turn/start", {"threadId": thread, "input": [{"type": "text", "text": "Ask a question."}]})
        predicate = lambda m: m.get("method") == "item/tool/requestUserInput" and m.get("params", {}).get("threadId") == thread
        original = a.wait(predicate)
        assert original["params"]["questions"][0]["id"] == "scope"
        assert original["params"]["questions"][0]["isOther"] is True
        assert original["params"]["isBlocking"] is False
        a.close()
        b = Client(command)
        clients.append(b)
        b.request("thread/resume", {"threadId": thread, "excludeTurns": True})
        replay = b.wait(predicate)
        assert replay["id"] == original["id"]
        c = Client(command)
        clients.append(c)
        c.request("thread/resume", {"threadId": thread, "excludeTurns": True})
        assert c.wait(predicate)["id"] == replay["id"]
        b.send({"id": replay["id"], "result": {"answers": {"scope": {"answers": ["Prepare structures"]}}}})
        resolved = lambda m: m.get("method") == "serverRequest/resolved" and m.get("params", {}).get("requestId") == replay["id"]
        b.wait(resolved)
        c.wait(resolved)
        completed = b.wait(lambda m: m.get("method") == "turn/completed" and m.get("params", {}).get("threadId") == thread)
        assert completed["params"]["turn"]["status"] == "completed"
        history = b.request("thread/turns/list", {"threadId": thread, "itemsView": "full"})
        assert any(i.get("text") == "Answer received." for t in history["data"] for i in t["items"])
        print(json.dumps({"check": "questions_reconnect_answer_and_cross_client_resolution", "passed": True}))
        b.request("thread/archive", {"threadId": thread})

        # A pending question cleared by interruption must also resolve remotely.
        second = b.request("thread/start", {"cwd": "/tmp", "modelProvider": "questions_fixture", "model": "gpt-5.4",
            "config": {"model_providers.questions_fixture": provider, "features.default_mode_request_user_input": True}})["thread"]["id"]
        turn = b.request("turn/start", {"threadId": second, "input": [{"type": "text", "text": "Ask again."}]})["turn"]["id"]
        pending = b.wait(lambda m: m.get("method") == "item/tool/requestUserInput" and m.get("params", {}).get("threadId") == second)
        b.request("turn/interrupt", {"threadId": second, "turnId": turn})
        b.wait(lambda m: m.get("method") == "serverRequest/resolved" and m.get("params", {}).get("requestId") == pending["id"])
        print(json.dumps({"check": "interruption_clears_pending_question", "passed": True}))
    finally:
        for client in clients:
            client.close()
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--codex")
    parser.add_argument("--ssh-config")
    args = parser.parse_args()
    if args.codex:
        exercise(args.codex, args.ssh_config)
    else:
        if args.ssh_config:
            parser.error("--ssh-config requires the private --codex wrapper")
        with isolated_codex() as fixture:
            exercise(fixture["codex"])
