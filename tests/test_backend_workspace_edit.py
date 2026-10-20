import json
import os
import unittest
from unittest.mock import patch
from fastapi.testclient import TestClient

from backend.app import main


class FakeResponse:
    def __init__(self, body: bytes): self.body = body
    def __enter__(self): return self
    def __exit__(self, exc_type, exc, tb): return False
    def read(self, count=-1): return self.body if count < 0 else self.body[:count]


def envelope(content: str) -> bytes:
    return json.dumps({"choices": [{"message": {"content": content}}]}).encode("utf-8")


class BackendWorkspaceEditTests(unittest.TestCase):
    def setUp(self):
        main._reset_rate_limiter()
        self.client = TestClient(main.app)
        self.env = patch.dict(os.environ, {
            "SPIDER_API_TOKEN": "client-token-edit-test-value-long",
            "PROVIDER_API_KEY": "provider-secret-edit-test",
            "PROVIDER_BASE_URL": "https://provider.example/v1",
            "PROVIDER_MODEL": "test-model",
            "PROVIDER_TIMEOUT_SECONDS": "8",
        }, clear=True)
        self.env.start()
        self.addCleanup(self.env.stop)
        self.auth = {"Authorization": "Bearer client-token-edit-test-value-long"}
        self.payload = {"task_id": "edit-task-1", "goal": "Улучши README", "step_title": "Добавь краткое описание проекта", "path": "README.md", "source_content": "# Project\\n"}

    def test_edit_route_returns_candidate_without_executing_a_write(self):
        candidate = json.dumps({"status": "ok", "summary": "Добавлено описание", "updated_content": "# Project\\n\\nShort description.\\n"}, ensure_ascii=False)
        with patch.object(main.urllib.request, "urlopen", return_value=FakeResponse(envelope(candidate))) as call:
            response = self.client.post("/api/agent/edit", headers=self.auth, json=self.payload)
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json()["updated_content"], "# Project\\n\\nShort description.\\n")
        request = call.call_args.args[0]
        self.assertEqual(request.full_url, "https://provider.example/v1/chat/completions")
        sent = json.loads(request.data.decode("utf-8"))
        self.assertEqual(sent["model"], "test-model")
        self.assertIn("updated_content", sent["messages"][0]["content"])
        task_context = json.loads(sent["messages"][1]["content"])
        self.assertEqual(task_context["step_title"], "Добавь краткое описание проекта")
        self.assertEqual(sent["messages"][2]["content"].splitlines()[-1], "# Project\\n")
        self.assertNotIn("provider-secret-edit-test", response.text)

    def test_edit_route_requires_authentication_before_provider_access(self):
        with patch.object(main.urllib.request, "urlopen") as call:
            missing = self.client.post("/api/agent/edit", json=self.payload)
            wrong = self.client.post("/api/agent/edit", headers={"Authorization": "Bearer wrong-token"}, json=self.payload)
        self.assertEqual(missing.status_code, 401)
        self.assertEqual(wrong.status_code, 401)
        call.assert_not_called()

    def test_edit_route_rejects_paths_outside_workspace_and_extra_fields(self):
        with patch.object(main.urllib.request, "urlopen") as call:
            path_traversal = self.client.post("/api/agent/edit", headers=self.auth, json={**self.payload, "path": "../outside.py"})
            absolute = self.client.post("/api/agent/edit", headers=self.auth, json={**self.payload, "path": "/tmp/private.py"})
            extra = self.client.post("/api/agent/edit", headers=self.auth, json={**self.payload, "command": "rm -rf /"})
        self.assertEqual(path_traversal.status_code, 422)
        self.assertEqual(absolute.status_code, 422)
        self.assertEqual(extra.status_code, 422)
        call.assert_not_called()

    def test_edit_route_rejects_sensitive_source_before_provider_call(self):
        sensitive = self.client.post("/api/agent/edit", headers=self.auth, json={**self.payload, "source_content": "api_key = 'this-is-a-real-secret-value'"})
        self.assertEqual(sensitive.status_code, 422)
        with patch.object(main.urllib.request, "urlopen") as call:
            oversized = self.client.post("/api/agent/edit", headers=self.auth, json={**self.payload, "source_content": "x" * (main.MAX_EDIT_SOURCE_CHARS + 1)})
        self.assertEqual(oversized.status_code, 422)
        call.assert_not_called()

    def test_edit_route_rejects_model_unsupported_and_malformed_proposals(self):
        unsupported = json.dumps({"status": "unsupported", "summary": "Не хватает контекста"}, ensure_ascii=False)
        malformed = "not json"
        for response_body, expected_status in ((unsupported, 422), (malformed, 502)):
            with patch.object(main.urllib.request, "urlopen", return_value=FakeResponse(envelope(response_body))):
                response = self.client.post("/api/agent/edit", headers=self.auth, json=self.payload)
            self.assertEqual(response.status_code, expected_status, response.text)

    def test_edit_route_rejects_control_characters_in_model_candidate(self):
        candidate = json.dumps({"status": "ok", "summary": "Not safe", "updated_content": "line\x07bell"})
        with patch.object(main.urllib.request, "urlopen", return_value=FakeResponse(envelope(candidate))):
            response = self.client.post("/api/agent/edit", headers=self.auth, json=self.payload)
        self.assertEqual(response.status_code, 502, response.text)

    def test_edit_route_rate_limit_is_independent_and_bounded(self):
        candidate = json.dumps({"status": "ok", "summary": "Done", "updated_content": "ok"})
        with patch.dict(os.environ, {"SPIDER_PROVIDER_RATE_LIMIT_PER_MINUTE": "1"}, clear=False):
            with patch.object(main.urllib.request, "urlopen", return_value=FakeResponse(envelope(candidate))) as call:
                first = self.client.post("/api/agent/edit", headers=self.auth, json=self.payload)
                second = self.client.post("/api/agent/edit", headers=self.auth, json=self.payload)
        self.assertEqual(first.status_code, 200, first.text)
        self.assertEqual(second.status_code, 429, second.text)
        self.assertEqual(call.call_count, 1)


if __name__ == "__main__":
    unittest.main()
