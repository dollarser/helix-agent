"""Current version-1 host contract fixture; historical samples are not compatibility contracts."""
import importlib.util
import io
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("session_export", ROOT / "scripts/validate-session-export.py")
EXPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EXPORT)
SAMPLE = ROOT / "scripts/fixtures/session-export-current.jsonl"


class SessionExportValidatorTest(unittest.TestCase):
    def rows(self):
        return [json.loads(line) for line in SAMPLE.read_text().splitlines()]

    def reject(self, rows):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "mutated.jsonl"
            path.write_text("".join(json.dumps(row) + "\n" for row in rows))
            with self.assertRaises((ValueError, sqlite3.IntegrityError)):
                EXPORT.validate(path)

    def test_host_fixture_reconstructs_messages_and_calls_but_is_not_complete_material(self):
        messages, calls = io.StringIO(), io.StringIO()
        result = EXPORT.validate(SAMPLE, messages, calls)
        self.assertTrue(result["valid"])
        self.assertFalse(result["materialsComplete"])
        self.assertEqual([0, 1], [json.loads(line)["sequence"] for line in messages.getvalue().splitlines()])
        rows = [json.loads(line) for line in calls.getvalue().splitlines()]
        self.assertEqual({"tool_call", "tool_result", "approval"}, {row["type"] for row in rows})
        self.assertEqual("tool", next(row for row in rows if row["type"] == "tool_result")["data"]["toolCallId"])

    def test_rejects_unsupported_version_and_mixed_export(self):
        for field, value in (("formatVersion", 2), ("exportId", "other"), ("sessionId", "other"), ("sequence", 99)):
            with self.subTest(field=field):
                rows = self.rows()
                rows[2][field] = value
                self.reject(rows)

    def test_conversation_reference_keeps_snapshot_edges_without_claiming_source_session_access(self):
        rows = self.rows()
        position = next(i for i, row in enumerate(rows) if row["type"] == "usage")
        data = dict(rowId="reference", messageId="message", sourceSessionId="foreign",
                    sourceMessageIdsJson='["source-message"]', contentId=None, references=[
                        dict(field="messageId", targetRecordId="message:message", status="included"),
                        dict(field="sourceSessionId", targetRecordId="session:foreign", status="not_in_selected_snapshot"),
                        dict(field="contentId", targetRecordId=None, status="not_recorded"),
                        dict(field="sourceMessageIds[0]", targetRecordId="message:source-message", status="not_in_selected_snapshot"),
                    ])
        row = dict(rows[0], type="conversation_reference", recordId="conversation_reference:reference", data=data)
        rows.insert(position, row)
        for index, record in enumerate(rows):
            record["sequence"] = index
        rows[-1]["data"]["recordCount"] += 1
        rows[-1]["data"]["counts"]["conversation_reference"] += 1
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "references.jsonl"
            path.write_text("".join(json.dumps(row) + "\n" for row in rows))
            result = EXPORT.validate(path)
            self.assertTrue(result["valid"])
            self.assertEqual(2, result["unresolvedReferences"])
        data["references"][1]["status"] = "included"
        self.reject(rows)

    def test_rejects_incomplete_tail_and_false_counts(self):
        self.reject(self.rows()[:-1])
        rows = self.rows()
        rows[-1]["data"]["counts"]["message"] += 1
        self.reject(rows)
        rows = self.rows()
        rows[-1]["data"]["contentStatistics"]["inline"] += 1
        self.reject(rows)

    def test_rejects_unmarked_or_false_reference_closure(self):
        for mutation in ("remove", "false-status", "wrong-target"):
            with self.subTest(mutation=mutation):
                rows = self.rows()
                edges = next(row for row in rows if row["type"] == "message")["data"]["references"]
                if mutation == "remove":
                    edges.pop()
                elif mutation == "false-status":
                    edges[0]["status"] = "not_in_selected_snapshot"
                else:
                    edges[0]["targetRecordId"] = "session:other"
                self.reject(rows)

    def test_rejects_body_hash_tampering_and_unstable_identity(self):
        rows = self.rows()
        next(row for row in rows if row["type"] == "content")["data"]["text"] = "changed"
        self.reject(rows)
        rows = self.rows()
        rows[2]["recordId"] = "turn:invented"
        self.reject(rows)

    def test_explicit_missing_reference_is_valid_but_incomplete(self):
        rows = self.rows()
        message = next(row for row in rows if row["type"] == "message")["data"]
        message["turnId"] = "deleted"
        edge = next(edge for edge in message["references"] if edge["field"] == "turnId")
        edge.update(targetRecordId="turn:deleted", status="not_in_selected_snapshot")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "missing.jsonl"
            path.write_text("".join(json.dumps(row) + "\n" for row in rows))
            result = EXPORT.validate(path)
            self.assertEqual(1, result["unresolvedReferences"])
            self.assertFalse(result["materialsComplete"])

    def test_rejects_duplicate_keys_bom_and_missing_final_lf(self):
        original = SAMPLE.read_bytes()
        for raw in (b"\xef\xbb\xbf" + original, original[:-1],
                    original.replace(b'"formatVersion":1', b'"formatVersion":1,"formatVersion":1', 1)):
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "bad.jsonl"
                path.write_bytes(raw)
                with self.assertRaises(ValueError):
                    EXPORT.validate(path)


if __name__ == "__main__":
    unittest.main()
