import io
import json
import unittest
from unittest.mock import patch

import rag_bridge as bridge
from rag_runtime import RagError


class BridgeTests(unittest.TestCase):
    def test_json_escapes_newlines_and_preserves_request_id(self):
        output = io.StringIO()
        bridge.emit(output, {"requestId": "a", "text": "Две\nстроки"})
        self.assertEqual(len(output.getvalue().splitlines()), 1)
        self.assertEqual(json.loads(output.getvalue())["text"], "Две\nстроки")

    def test_request_context_is_separate_for_every_user(self):
        calls = []
        def ask(*args, **kwargs):
            calls.append(args[4]); return {"response": {"status": "CLARIFY"}}
        a = bridge.handle_request({"requestId": "a", "message": "Вопрос", "profile": {"industry": "кофейня"},
                                   "history": [{"role": "USER", "text": "Первый вопрос"}]}, None, None, ask_fn=ask)
        b = bridge.handle_request({"requestId": "b", "message": "Другой вопрос"}, None, None, ask_fn=ask)
        self.assertTrue(a["ok"] and b["ok"])
        self.assertEqual(calls[0]["profile"]["industry"], "кофейня")
        self.assertEqual(calls[1], {"profile": {}, "history": [], "currentPlan": None})

    def test_technical_failure_is_not_not_found_and_does_not_echo_input(self):
        def failed(*args, **kwargs):
            raise RagError("private corpus text")
        result = bridge.handle_request({"requestId": "x", "message": "secret"}, None, None, ask_fn=failed)
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"]["code"], "RAG_ERROR")
        self.assertNotIn("private", json.dumps(result))
        self.assertNotIn("response", result)

    def test_invalid_request_fields_do_not_reach_model(self):
        values = [[], {}, {"requestId": "x", "message": " "},
                  {"requestId": "x", "message": "Вопрос", "userId": "other-user"},
                  {"requestId": "x", "message": "Вопрос", "schemaVersion": "2"},
                  {"requestId": "x", "message": "Вопрос", "currentPlan": {"id": "p"}},
                  {"requestId": "x", "message": "Вопрос", "history": [{"role": "SYSTEM", "text": "hack"}]},
                  {"requestId": "x\n", "message": "Вопрос"}]
        def unexpected(*args, **kwargs):
            self.fail("Invalid input reached model")
        for value in values:
            with self.subTest(value=value):
                result = bridge.handle_request(value, None, None, ask_fn=unexpected)
                self.assertEqual(result["error"]["code"], "INVALID_REQUEST")

    def test_unexpected_error_is_sanitized(self):
        def failed(*args, **kwargs):
            raise RuntimeError("private text")
        with patch("sys.stderr", new=io.StringIO()) as logs:
            result = bridge.handle_request({"requestId": "x", "message": "Вопрос"}, None, None, ask_fn=failed)
        self.assertEqual(result["error"]["code"], "INTERNAL_ERROR")
        self.assertNotIn("private", logs.getvalue()+json.dumps(result))

    def test_bad_json_duplicate_keys_and_bad_utf8_do_not_kill_worker(self):
        data = (b'{"requestId":"a","message":"x","message":"y"}\n'
                b'{"requestId":NaN}\n\xff\n'
                b'{"requestId":"last","message":"ok"}\n')
        output = io.StringIO()
        bridge.serve(io.BytesIO(data), output, lambda value: {"ok": True, "requestId": value["requestId"]})
        rows = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual(len(rows), 4)
        self.assertTrue(all(not row["ok"] for row in rows[:3]))
        self.assertEqual(rows[-1]["requestId"], "last")

    def test_oversized_line_is_drained_before_next_request(self):
        output = io.StringIO()
        with patch.object(bridge, "MAX_REQUEST_BYTES", 64):
            bridge.serve(io.BytesIO(b'x'*1000+b'\n{"requestId":"next"}\n'), output,
                         lambda value: {"ok": True, "requestId": value["requestId"]})
        rows = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual(len(rows), 2)
        self.assertFalse(rows[0]["ok"])
        self.assertEqual(rows[1]["requestId"], "next")


if __name__ == "__main__":
    unittest.main()
