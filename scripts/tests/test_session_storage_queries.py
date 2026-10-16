"""Execute production DAO SQL on the exported Room schema with synthetic data only.

This is host SQLite coverage, not Android Room instrumentation or a performance benchmark.
"""
import json
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
STORAGE = ROOT / "core/storage/src/main/kotlin/com/helix/core/storage"
SCHEMA = ROOT / "core/storage/src/androidTest/assets/com.helix.core.storage.HelixDatabase/1.json"


def query(dao, method):
    source = (STORAGE / "dao" / f"{dao}.kt").read_text()
    end = re.search(r"\bfun\s+" + method + r"\s*\(", source).start()
    start = source.rfind("@Query(", 0, end)
    assert start >= 0
    return "".join(json.loads(value) for value in re.findall(r'"(?:[^"\\]|\\.)*"', source[start:end]))


class SessionStorageQueriesTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA foreign_keys=ON")
        for table in json.loads(SCHEMA.read_text())["database"]["entities"]:
            self.db.execute(table["createSql"].replace("${TABLE_NAME}", table["tableName"]))
            for index in table.get("indices", []):
                self.db.execute(index["createSql"].replace("${TABLE_NAME}", table["tableName"]))
        self.session("old", 10)
        self.session("new", 20)

    def tearDown(self):
        self.db.close()

    def insert(self, table, **values):
        for column in self.db.execute(f"PRAGMA table_info({table})"):
            if column["notnull"] and column["dflt_value"] is None and column["name"] not in values:
                values[column["name"]] = 0 if column["type"] == "INTEGER" else ""
        columns = ",".join(values)
        self.db.execute(f"INSERT INTO {table} ({columns}) VALUES ({','.join('?' for _ in values)})", tuple(values.values()))

    def session(self, identity, activity, title="plain"):
        self.insert("sessions", id=identity, title=title, searchTitle=title.lower(), createdAt=activity, lastActivityAt=activity)

    def message(self, identity, session, sequence, body="needle", **extra):
        self.insert("messages", id=identity, sessionId=session, sequence=sequence, role="USER", kind="TEXT",
                    contentRef=f"ref-{identity}", createdAt=sequence, **extra)
        self.insert("message_search", messageId=identity, sessionId=session, contentRef=f"ref-{identity}", body=body, folded=body.lower())

    def search(self, text, limit=51):
        return self.db.execute(query("SessionSearchDao", "search"), {"query": text.lower(), "limit": limit}).fetchall()

    def test_old_session_recent_message_is_searchable_beyond_500_candidates(self):
        for number in range(600):
            self.message(f"n{number}", "new", number, "irrelevant")
        self.message("target", "old", 0, "unique old-session result")
        rows = self.search("unique")
        self.assertEqual(["old"], [row["id"] for row in rows])

    def test_activity_order_is_monotonic_and_has_stable_ties(self):
        touch = query("SessionSearchDao", "touch")
        self.db.execute(touch, {"sessionId": "old", "timestamp": 30})
        self.db.execute(touch, {"sessionId": "old", "timestamp": 1})
        self.assertEqual(["old", "new"], [row["id"] for row in self.db.execute(query("SessionDao", "list"))])
        self.db.execute(touch, {"sessionId": "new", "timestamp": 30})
        self.assertEqual(["new", "old"], [row["id"] for row in self.db.execute(query("SessionDao", "list"))])

    def test_literal_chinese_wildcards_and_original_snippet(self):
        self.message("a", "old", 0, "前缀 中文%_Needle 后缀")
        self.message("b", "new", 0, "中文ABneedle")
        rows = self.search("中文%_NEEDLE")
        self.assertEqual(["old"], [row["id"] for row in rows])
        self.assertIn("中文%_Needle", rows[0]["messageSnippet"])

    def test_long_body_and_late_match_have_bounded_snippets(self):
        self.message("large", "old", 0, "x" * 300_000 + " final-needle")
        result = self.search("final-needle")[0]
        self.assertIn("final-needle", result["messageSnippet"])
        self.assertLessEqual(len(result["messageSnippet"]), 240)

    def test_revised_and_stale_projection_rows_are_excluded(self):
        self.message("revised", "old", 0, supersededBy="replacement")
        self.message("changed", "new", 0)
        self.db.execute("UPDATE messages SET contentRef='different' WHERE id='changed'")
        self.assertEqual([], self.search("needle"))

    def test_archive_and_rename_do_not_change_activity_or_drop_body_hits(self):
        self.message("a", "old", 0)
        self.db.execute(query("SessionDao", "archive"), {"id": "old", "archivedAt": 100})
        self.db.execute(query("SessionDao", "updateDetails"), {"id": "old", "title": "Changed 中文", "searchTitle": "changed 中文", "directoryRef": None})
        row = self.search("changed")[0]
        self.assertEqual((100, 10, 1), (row["archivedAt"], row["lastActivityAt"], row["titleMatch"]))
        self.assertEqual("old", self.search("needle")[0]["id"])
        self.assertEqual([], self.search("absent"))

    def test_branch_copy_and_privacy_delete_keep_only_owned_projection(self):
        self.message("source", "old", 0)
        self.insert("messages", id="copy", sessionId="new", sequence=0, role="USER", kind="TEXT", contentRef="ref-source")
        self.db.execute(query("SessionSearchDao", "copy"), {"sourceId": "source", "targetId": "copy", "sessionId": "new"})
        self.db.execute(query("SessionDao", "deletePermanently"), {"id": "old"})
        self.assertEqual(["new"], [row["id"] for row in self.search("needle")])
        self.assertEqual(1, self.db.execute("SELECT COUNT(*) FROM message_search").fetchone()[0])
        self.assertEqual([], self.db.execute("PRAGMA foreign_key_check").fetchall())

    def test_history_window_is_bounded_and_keeps_effective_order(self):
        for number in range(305):
            self.message(f"m{number}", "old", number, supersededBy="revised" if number == 304 else None)
        rows = self.db.execute(query("MessageDao", "recentBySession"), {"sessionId": "old", "limit": 101, "through": 1000}).fetchall()
        self.assertEqual(101, len(rows))
        self.assertEqual((303, 203), (rows[0]["sequence"], rows[-1]["sequence"]))

    def test_gc_reference_queries_use_indexes(self):
        for dao in ("MessageDao", "MessageReferenceSnapshotDao", "ToolResultDao", "SessionInputDao", "DocumentSnapshotDao"):
            with self.subTest(dao=dao):
                plans = [row[3] for row in self.db.execute("EXPLAIN QUERY PLAN " + query(dao, "countByContentRef"), {"contentRef": "absent"})]
                self.assertTrue(any("SEARCH" in plan and "INDEX" in plan for plan in plans), plans)
                self.assertFalse(any(plan.startswith("SCAN ") for plan in plans), plans)

    def test_message_location_crosses_windows_without_loading_bodies(self):
        for number in (1, 150, 300):
            self.message(f"m{number}", "old", number, "needle")
        sql = query("SessionSearchDao", "locateMessage")
        args = dict(sessionId="old", query="needle", before=150, after=None, forward=False)
        self.assertEqual("m1", self.db.execute(sql, args).fetchone()["id"])
        args.update(before=None, after=150, forward=True)
        self.assertEqual("m300", self.db.execute(sql, args).fetchone()["id"])
        args.update(after=None, forward=False)
        self.assertEqual("m300", self.db.execute(sql, args).fetchone()["id"])

    def test_tool_message_binding_is_once_only_and_same_turn(self):
        for turn in ("one", "two"):
            self.insert("turns", id=turn, sessionId="old")
        self.insert("model_calls", id="model", turnId="one", ordinal=0)
        for identity, turn in (("correct", "one"), ("wrong", "two")):
            self.insert("messages", id=identity, sessionId="old", turnId=turn, sequence=0 if turn == "one" else 1,
                        role="ASSISTANT", kind="TOOL_CALLS")
        sql = query("ModelCallDao", "bindToolMessage")
        self.assertEqual(0, self.db.execute(sql, dict(id="model", messageId="wrong")).rowcount)
        self.assertEqual(1, self.db.execute(sql, dict(id="model", messageId="correct")).rowcount)
        self.assertEqual(0, self.db.execute(sql, dict(id="model", messageId="correct")).rowcount)

    def test_model_tool_source_rejects_cross_turn_and_duplicate_order(self):
        for turn in ("one", "two"):
            self.insert("turns", id=turn, sessionId="old", state="RUNNING_TOOL")
        self.insert("model_calls", id="model", turnId="one", ordinal=0, state="COMPLETED", providerSnapshot="{}")
        self.insert("tool_calls", id="tool", turnId="one", callId="wire", ordinal=0, modelCallId="model", callIndex=0)
        for values in (
            dict(id="cross", turnId="two", callId="cross", ordinal=0, modelCallId="model", callIndex=1),
            dict(id="duplicate", turnId="one", callId="other", ordinal=1, modelCallId="model", callIndex=0),
            dict(id="order", turnId="one", callId="order", ordinal=0),
        ):
            with self.subTest(values=values), self.assertRaises(sqlite3.IntegrityError):
                self.insert("tool_calls", **values)
        self.insert("tool_calls", id="manual", turnId="two", callId="manual", ordinal=0)
        self.assertEqual([], self.db.execute("PRAGMA foreign_key_check").fetchall())

    def test_model_and_tool_order_use_ordinals_not_insert_order(self):
        self.insert("turns", id="turn", sessionId="old", state="RUNNING_TOOL")
        for number in (2, 0, 1):
            self.insert("model_calls", id=f"model{number}", turnId="turn", ordinal=number)
            self.insert("tool_calls", id=f"tool{number}", turnId="turn", callId=f"wire{number}", ordinal=number)
        for dao in ("ModelCallDao", "ToolCallDao"):
            rows = self.db.execute(query(dao, "listByTurn"), {"turnId": "turn"}).fetchall()
            self.assertEqual([0, 1, 2], [row["ordinal"] for row in rows])

    def test_tool_timeline_respects_both_history_bounds_and_keeps_unmessaged_direct_calls_at_tail(self):
        for number in (0, 20, 300):
            turn = f"t{number}"
            self.insert("turns", id=turn, sessionId="old", state="COMPLETED", startedAt=number)
            self.insert("messages", id=f"m{number}", sessionId="old", turnId=turn, sequence=number,
                        kind="TOOL_CALLS", role="ASSISTANT")
            self.insert("model_calls", id=f"model{number}", turnId=turn, ordinal=0, toolMessageId=f"m{number}")
            self.insert("tool_calls", id=f"tool{number}", turnId=turn, callId=f"wire{number}", ordinal=0,
                        modelCallId=f"model{number}", callIndex=0)
        self.insert("turns", id="manual", sessionId="old", state="COMPLETED", startedAt=400)
        self.insert("tool_calls", id="direct", turnId="manual", callId="direct", ordinal=0)
        sql = query("ToolCallDao", "recentBySession")
        args = dict(sessionId="old", fromSequence=10, throughSequence=100, limit=200)
        self.assertEqual(["tool20"], [r["id"] for r in self.db.execute(sql, args)])
        args.update(fromSequence=200, throughSequence=9223372036854775807)
        self.assertEqual(["direct", "tool300"], [r["id"] for r in self.db.execute(sql, args)])


if __name__ == "__main__":
    unittest.main()
