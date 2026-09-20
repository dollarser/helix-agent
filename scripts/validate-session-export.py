#!/usr/bin/env python3
"""Validate Helix v1 exports independently of Kotlin; optionally stream reconstructed messages.

Only consumes the selected file. Never follows content references or executes exported tools.
"""
import argparse
from collections import Counter
from contextlib import closing
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile

FORMAT = "helix.session-export"
LINE_BYTES = 256 * 1024
FILE_BYTES = 128 * 1024 * 1024
TYPES = ("header session turn message model_call tool_call tool_result execution approval "
         "attachment artifact compaction usage goal_run goal_binding content complete").split()
GROUPS = ("header session turn message model_call tool_call tool_result execution approval "
          "artifact attachment goal_run goal_binding usage:goal usage:audit compaction content complete").split()
AVAILABILITY = "inline reference_only missing changed redacted omitted_limit".split()
FIELDS = {
    "turn": {"sessionId": "session:"},
    "message": {"sessionId": "session:", "turnId": "turn:", "contentId": ""},
    "model_call": {"turnId": "turn:"},
    "tool_call": {"turnId": "turn:", "modelCallId": "model_call:"},
    "tool_result": {"toolCallId": "tool_call:", "contentId": ""},
    "execution": {"toolCallId": "tool_call:"},
    "approval": {"toolCallId": "tool_call:"},
    "artifact": {"sessionId": "session:", "turnId": "turn:"},
    "attachment": {"messageId": "message:", "artifactId": "artifact:"},
    "goal_run": {"goalId": "usage:goal:"},
    "goal_binding": {"turnId": "turn:", "runId": "goal_run:"},
    "compaction": {"messageId": "message:", "contentId": "", "sourceCallId": "model_call:"},
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def parse(raw):
    return json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError(f"Invalid number: {value}")))


def content_statistics(value, counts):
    if not isinstance(value, dict):
        return
    counts["omitted_limit"] += len(value.get("omittedFields", {}))
    status = value.get("availability")
    if status in AVAILABILITY:
        counts[status] += 1
    if "text" in value and "exportedSha256" in value:
        body = value["text"].encode("utf-8")
        require(len(body) <= 32 * 1024, "Inline text exceeds byte limit")
        require(len(body) == value["exportedBytes"], "Exported text size mismatch")
        require(hashlib.sha256(body).hexdigest() == value["exportedSha256"], "Exported text hash mismatch")
        if status == "inline":
            require(value["sourceBytes"] == len(body), "Original text size mismatch")
            require(value["sourceSha256"] == value["exportedSha256"], "Original text hash mismatch")
    for item in value.values():
        content_statistics(item, counts)


def expected_edges(kind, data):
    fields = dict(FIELDS.get(kind, {}))
    if kind == "usage" and "correlationId" in data:
        fields["correlationId"] = "model_call:"
    result = {field: None if data.get(field) is None else prefix + str(data[field])
              for field, prefix in fields.items()}
    if kind == "compaction":
        for index, identity in enumerate(data.get("preservedMessageIds") or []):
            require(isinstance(identity, str), "Invalid preserved message identity")
            result[f"preservedMessageIds[{index}]"] = "message:" + identity
    return result


def collect_edges(database, kind, identity, data):
    expected = expected_edges(kind, data)
    edges = data.get("references", [])
    require(isinstance(edges, list), "References must be an array")
    require(len(edges) == len(expected), f"Missing or extra references: {identity}")
    seen = set()
    for edge in edges:
        field = edge["field"]
        target = edge["targetRecordId"]
        status = edge["status"]
        require(field in expected and field not in seen, f"Invalid reference field: {identity}")
        seen.add(field)
        require(target == expected[field], f"Reference identity mismatch: {identity}.{field}")
        require(status in ("included", "not_in_selected_snapshot", "not_recorded", "omitted_limit"), "Unknown reference status")
        require((target is None) == (status in ("not_recorded", "omitted_limit")), "Reference null/status mismatch")
        original = "contentRef" if field == "contentId" else field
        omitted = original in data.get("omittedFields", {})
        require((status == "omitted_limit") == omitted, "Reference omission mismatch")
        database.execute("INSERT INTO edges VALUES (?,?,?,?)", (identity, field, target, status))


def validate(path, messages=None, calls=None):
    counts = Counter({kind: 0 for kind in TYPES})
    statistics = Counter({kind: 0 for kind in AVAILABILITY})
    total = 0
    group = -1
    session = export = tail = None
    with tempfile.TemporaryDirectory(prefix="helix-export-reader-") as directory:
        with closing(sqlite3.connect(str(Path(directory) / "records.sqlite"))) as database:
            database.execute("PRAGMA cache_size=-2048")
            database.execute("CREATE TABLE records(id TEXT PRIMARY KEY, kind TEXT, data TEXT)")
            database.execute("CREATE TABLE edges(owner TEXT, field TEXT, target TEXT, status TEXT)")
            with Path(path).open("rb") as source:
                number = 0
                while raw := source.readline(LINE_BYTES + 1):
                    total += len(raw)
                    require(total <= FILE_BYTES and len(raw) <= LINE_BYTES, "Export byte limit exceeded")
                    require(raw.endswith(b"\n") and not raw.endswith(b"\r\n"), "Missing LF or unexpected CRLF")
                    require(not raw.startswith(b"\xef\xbb\xbf"), "BOM is not allowed")
                    row = parse(raw)
                    require(isinstance(row, dict), "Record must be an object")
                    require(row["format"] == FORMAT and type(row["formatVersion"]) is int and row["formatVersion"] == 1,
                            "Unsupported format/version")
                    require(type(row["sequence"]) is int and row["sequence"] == number, "Sequence gap")
                    kind, identity, data = row["type"], row["recordId"], row["data"]
                    require(kind in TYPES and isinstance(data, dict), "Unknown record type or non-object data")
                    require(isinstance(identity, str) and identity.startswith(kind + ":"), "Invalid record identity")
                    require(tail is None, "Records after completion")
                    if number == 0:
                        require(kind == "header", "First record must be header")
                        session, export = row["sessionId"], row["exportId"]
                        require(isinstance(session, str) and session and isinstance(export, str) and export, "Missing identity")
                    require(row["sessionId"] == session and row["exportId"] == export, "Mixed export/session identities")
                    if kind in ("header", "complete"):
                        expected_id = kind + ":" + session
                    elif kind == "compaction":
                        expected_id = "compaction:" + data["messageId"]
                    elif kind == "goal_binding":
                        expected_id = "goal_binding:" + data["turnId"]
                    elif kind == "content":
                        expected_id = "content:" + data["sourceSha256"]
                    else:
                        prefix = ":".join(identity.split(":")[:2]) if kind == "usage" else kind
                        expected_id = prefix + ":" + str(data["rowId" if kind == "attachment" else "id"])
                    require(identity == expected_id, "Unstable source identity")
                    if kind == "session":
                        require(data["id"] == session, "Session record mismatch")
                    key = ":".join(identity.split(":")[:2]) if kind == "usage" else kind
                    require(key in GROUPS and GROUPS.index(key) >= group, "Invalid group order")
                    group = GROUPS.index(key)
                    database.execute("INSERT INTO records VALUES (?,?,?)", (identity, kind, json.dumps(data)))
                    counts[kind] += 1
                    number += 1
                    if kind == "complete":
                        tail = data
                    else:
                        content_statistics(data, statistics)
                        collect_edges(database, kind, identity, data)
            require(tail is not None and tail.get("complete") is True, "Missing complete tail")
            require(counts["header"] == counts["session"] == counts["complete"] == 1, "Invalid singleton count")
            require(tail["recordCount"] == number and tail["counts"] == dict(counts), "Tail record counts mismatch")
            require(tail["contentStatistics"] == dict(statistics), "Tail content statistics mismatch")
            bad = database.execute("SELECT owner,field FROM edges LEFT JOIN records ON edges.target=records.id "
                                   "WHERE (status='included' AND records.id IS NULL) OR "
                                   "(status='not_in_selected_snapshot' AND records.id IS NOT NULL) LIMIT 1").fetchone()
            require(bad is None, f"Reference closure mismatch: {bad}")
            missing = database.execute("SELECT count(*) FROM edges WHERE status='not_in_selected_snapshot'").fetchone()[0]
            unknown = database.execute("SELECT count(*) FROM edges WHERE status IN ('not_recorded','omitted_limit')").fetchone()[0]
            if messages is not None:
                reconstruct(database, messages)
            if calls is not None:
                reconstruct_calls(database, calls)
            return {"valid": True, "formatVersion": 1, "recordCount": number, "counts": dict(counts),
                    "contentStatistics": dict(statistics), "unresolvedReferences": missing,
                    "unrecordedOrOmittedAssociations": unknown,
                    "materialsComplete": missing == 0 and unknown == 0 and all(
                        statistics[name] == 0 for name in AVAILABILITY if name != "inline")}


def reconstruct(database, output):
    # SQLite sorts on disk; message bodies are only ever read from this export.
    query = "SELECT id,data FROM records WHERE kind='message' ORDER BY json_extract(data,'$.sequence'),id"
    for identity, encoded in database.execute(query):
        data = json.loads(encoded)
        content = database.execute("SELECT data FROM records WHERE id=?", (data.get("contentId"),)).fetchone()
        body = json.loads(content[0]) if content else {"availability": "unavailable"}
        output.write(json.dumps({"recordId": identity, "sequence": data["sequence"], "role": data["role"],
                                 "turnId": data.get("turnId"), "body": body}, ensure_ascii=False) + "\n")


def reconstruct_calls(database, output):
    for identity, encoded in database.execute("SELECT id,data FROM records WHERE kind='tool_call' ORDER BY id"):
        call = json.loads(encoded)
        output.write(json.dumps({"type": "tool_call", "recordId": identity, "data": call}) + "\n")
        query = ("SELECT id,kind,data FROM records WHERE kind IN ('tool_result','execution','approval') "
                 "AND json_extract(data,'$.toolCallId')=? ORDER BY kind,id")
        for child, kind, data in database.execute(query, (call["id"],)):
            output.write(json.dumps({"type": kind, "recordId": child, "data": json.loads(data)}) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", type=Path)
    parser.add_argument("--messages", type=Path, help="Create reconstructed message JSONL; never overwrite an existing file")
    parser.add_argument("--calls", type=Path, help="Create reconstructed tool/result/approval relations")
    args = parser.parse_args()
    try:
        # Validate before creating the optional derived file.
        result = validate(args.file)
        if args.messages:
            with args.messages.open("x", encoding="utf-8") as output:
                validate(args.file, output)
        if args.calls:
            with args.calls.open("x", encoding="utf-8") as output:
                validate(args.file, calls=output)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (ValueError, KeyError, TypeError, RecursionError, OSError, sqlite3.Error) as error:
        parser.exit(1, f"Invalid export: {error}\n")


if __name__ == "__main__":
    main()
