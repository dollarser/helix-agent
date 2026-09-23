#!/usr/bin/env python3
"""Measure candidate HXA-217 source-manifest Kotlin/Room storage costs on SQLite.

Evaluates:
1. Candidate compact encoding vs verbose encoding byte sizes.
2. SQLite (Room engine) physical disk allocation (page size = 4096, WAL journal).
3. 100-turn cumulative session growth, B-tree overhead, overflow pages, and checkpoint behavior.
4. Embedded column vs Dedicated relation table comparison.
5. Session CASCADE deletion space reclamation.
6. Execution latency (write transaction and read parse times).
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sqlite3
import statistics
import sys
import tempfile
import time
from pathlib import Path
from typing import Any, Dict, List, Tuple

ROLE_CODES = ("s", "u", "a", "t")
MAX_SAFE_MESSAGES = 512
MAX_SAFE_INPUT_IDS = 512
MAX_SINGLE_LINE_BYTES = 256 * 1024


def synthetic_id(kind: str, index: int, length: int, unicode_fill: bool = False) -> str:
    prefix = f"{kind}{index:04d}"
    fill = "é" if unicode_fill else "a"
    return (prefix + (fill * length))[:length]


def ids(kind: str, count: int, length: int, unicode_fill: bool = False) -> List[str]:
    return [synthetic_id(kind, index, length, unicode_fill) for index in range(count)]


def build_manifest_compact(
    call_id: str,
    timestamp: int,
    checkpoint: int | None,
    message_ids: List[str],
    input_ids: List[str],
    is_truncated: bool = False,
) -> Dict[str, Any]:
    messages = [[msg_id, ROLE_CODES[idx % len(ROLE_CODES)]] for idx, msg_id in enumerate(message_ids)]
    res: Dict[str, Any] = {
        "c": call_id,
        "t": timestamp,
        "m": messages,
        "i": input_ids,
    }
    if checkpoint is not None:
        res["cp"] = checkpoint
    if is_truncated:
        res["tr"] = True
    return res


def build_manifest_verbose(
    call_id: str,
    timestamp: int,
    checkpoint: int | None,
    message_ids: List[str],
    input_ids: List[str],
    is_truncated: bool = False,
) -> Dict[str, Any]:
    messages = [
        {"id": msg_id, "role": ROLE_CODES[idx % len(ROLE_CODES)]}
        for idx, msg_id in enumerate(message_ids)
    ]
    return {
        "callId": call_id,
        "timestamp": timestamp,
        "checkpoint": checkpoint,
        "messages": messages,
        "inputIds": input_ids,
        "isTruncated": is_truncated,
    }


def bounded_compact(
    call_id: str,
    timestamp: int,
    checkpoint: int | None,
    message_ids: List[str],
    input_ids: List[str],
) -> Tuple[str, bool]:
    safe_msgs = message_ids[:MAX_SAFE_MESSAGES]
    safe_inputs = input_ids[:MAX_SAFE_INPUT_IDS]
    truncated = len(message_ids) > len(safe_msgs) or len(input_ids) > len(safe_inputs)

    candidate = build_manifest_compact(call_id, timestamp, checkpoint, safe_msgs, safe_inputs, truncated)
    encoded = json.dumps(candidate, ensure_ascii=False, separators=(",", ":"))
    encoded_bytes = len(encoded.encode("utf-8"))

    while encoded_bytes > MAX_SINGLE_LINE_BYTES and (safe_msgs or safe_inputs):
        truncated = True
        if len(safe_inputs) > 10:
            safe_inputs = safe_inputs[: len(safe_inputs) // 2]
        elif len(safe_msgs) > 10:
            safe_msgs = safe_msgs[: len(safe_msgs) // 2]
        else:
            safe_inputs = []
            safe_msgs = []
        candidate = build_manifest_compact(call_id, timestamp, checkpoint, safe_msgs, safe_inputs, True)
        encoded = json.dumps(candidate, ensure_ascii=False, separators=(",", ":"))
        encoded_bytes = len(encoded.encode("utf-8"))

    return encoded, truncated


def measure_scenario(
    name: str,
    msg_count: int,
    msg_id_len: int,
    input_count: int,
    input_id_len: int,
    unicode_fill: bool = False,
) -> Dict[str, Any]:
    message_ids = ids("msg", msg_count, msg_id_len)
    input_ids = ids("inp", input_count, input_id_len, unicode_fill)
    call_id = "call-0001"
    timestamp = 1774300000000

    # 1. Encoding sizes
    compact_raw = json.dumps(
        build_manifest_compact(call_id, timestamp, 42, message_ids, input_ids),
        ensure_ascii=False,
        separators=(",", ":"),
    )
    compact_bytes = len(compact_raw.encode("utf-8"))

    verbose_raw = json.dumps(
        build_manifest_verbose(call_id, timestamp, 42, message_ids, input_ids),
        ensure_ascii=False,
        separators=(",", ":"),
    )
    verbose_bytes = len(verbose_raw.encode("utf-8"))

    bounded_json, was_truncated = bounded_compact(call_id, timestamp, 42, message_ids, input_ids)
    bounded_bytes = len(bounded_json.encode("utf-8"))

    # 2. SQLite / Room physical simulation
    with tempfile.TemporaryDirectory() as temp_dir:
        db_path = Path(temp_dir) / f"{name}.db"
        conn = sqlite3.connect(str(db_path))
        conn.execute("PRAGMA page_size = 4096;")
        conn.execute("PRAGMA journal_mode = WAL;")
        conn.execute("PRAGMA synchronous = NORMAL;")
        conn.execute("PRAGMA foreign_keys = ON;")

        # Create schema matching HelixDatabase
        conn.execute("""
            CREATE TABLE sessions (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            );
        """)
        conn.execute("""
            CREATE TABLE turns (
                id TEXT PRIMARY KEY NOT NULL,
                sessionId TEXT NOT NULL,
                state TEXT NOT NULL,
                turnNumber INTEGER NOT NULL,
                FOREIGN KEY (sessionId) REFERENCES sessions(id) ON DELETE CASCADE
            );
        """)
        # Strategy A: Embedded column in model_calls
        conn.execute("""
            CREATE TABLE model_calls (
                id TEXT PRIMARY KEY NOT NULL,
                turnId TEXT NOT NULL,
                providerSnapshot TEXT NOT NULL,
                state TEXT NOT NULL,
                usage TEXT,
                requestId TEXT,
                promptFingerprint TEXT,
                promptSections TEXT,
                requestManifest TEXT,
                FOREIGN KEY (turnId) REFERENCES turns(id) ON DELETE CASCADE
            );
        """)
        # Strategy B: Dedicated table
        conn.execute("""
            CREATE TABLE request_manifests (
                modelCallId TEXT PRIMARY KEY NOT NULL,
                turnId TEXT NOT NULL,
                manifestJson TEXT NOT NULL,
                isTruncated INTEGER NOT NULL,
                FOREIGN KEY (modelCallId) REFERENCES model_calls(id) ON DELETE CASCADE
            );
        """)
        conn.commit()

        initial_db_size = db_path.stat().st_size
        wal_path = Path(str(db_path) + "-wal")

        # Simulate 100 turns in one session
        session_id = "session-test"
        conn.execute("INSERT INTO sessions VALUES (?, ?, ?);", (session_id, "Test Session", 1000))
        conn.commit()

        write_times: List[float] = []
        read_times: List[float] = []

        growth_checkpoints: Dict[int, int] = {}

        for turn_idx in range(1, 101):
            turn_id = f"turn-{turn_idx:04d}"
            call_id = f"call-{turn_idx:04d}"

            # Grow message list progressively up to msg_count
            current_msgs = message_ids[: min(len(message_ids), max(4, turn_idx * 5))]
            current_inputs = input_ids[: min(len(input_ids), max(1, turn_idx * 5))]

            manifest_content, is_trunc = bounded_compact(
                call_id, timestamp + turn_idx * 1000, 42, current_msgs, current_inputs
            )

            t0 = time.perf_counter()
            conn.execute("INSERT INTO turns VALUES (?, ?, ?, ?);", (turn_id, session_id, "COMPLETED", turn_idx))
            # Insert into model_calls with embedded column
            conn.execute(
                "INSERT INTO model_calls VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);",
                (call_id, turn_id, "{}", "COMPLETED", "{}", f"req-{turn_idx}", None, None, manifest_content),
            )
            # Insert into dedicated table
            conn.execute(
                "INSERT INTO request_manifests VALUES (?, ?, ?, ?);",
                (call_id, turn_id, manifest_content, 1 if is_trunc else 0),
            )
            conn.commit()
            t1 = time.perf_counter()
            write_times.append((t1 - t0) * 1000.0)

            # Test read
            r0 = time.perf_counter()
            cur = conn.execute("SELECT requestManifest FROM model_calls WHERE id = ?;", (call_id,))
            row = cur.fetchone()
            loaded = json.loads(row[0])
            r1 = time.perf_counter()
            read_times.append((r1 - r0) * 1000.0)

            if turn_idx in (1, 10, 25, 50, 75, 100):
                total_file_size = db_path.stat().st_size
                if wal_path.exists():
                    total_file_size += wal_path.stat().st_size
                growth_checkpoints[turn_idx] = total_file_size

        # Physical size before checkpoint
        pre_checkpoint_db = db_path.stat().st_size
        pre_checkpoint_wal = wal_path.stat().st_size if wal_path.exists() else 0
        pre_checkpoint_total = pre_checkpoint_db + pre_checkpoint_wal

        # Checkpoint WAL
        conn.execute("PRAGMA wal_checkpoint(TRUNCATE);")
        post_checkpoint_db = db_path.stat().st_size
        post_checkpoint_wal = wal_path.stat().st_size if wal_path.exists() else 0
        post_checkpoint_total = post_checkpoint_db + post_checkpoint_wal

        # Test CASCADE deletion
        conn.execute("DELETE FROM sessions WHERE id = ?;", (session_id,))
        conn.commit()
        remaining_calls = conn.execute("SELECT count(*) FROM model_calls;").fetchone()[0]
        remaining_manifests = conn.execute("SELECT count(*) FROM request_manifests;").fetchone()[0]
        conn.execute("VACUUM;")
        post_vacuum_size = db_path.stat().st_size

        conn.close()

    return {
        "name": name,
        "single_row": {
            "compact_bytes": compact_bytes,
            "verbose_bytes": verbose_bytes,
            "compact_savings_pct": round((1.0 - compact_bytes / verbose_bytes) * 100.0, 2),
            "bounded_bytes": bounded_bytes,
            "is_truncated": was_truncated,
            "fits_256k_limit": bounded_bytes <= MAX_SINGLE_LINE_BYTES,
        },
        "disk_growth_100_turns": {
            "initial_db_bytes": initial_db_size,
            "growth_checkpoints": growth_checkpoints,
            "pre_checkpoint_total_bytes": pre_checkpoint_total,
            "post_checkpoint_total_bytes": post_checkpoint_total,
            "post_vacuum_bytes": post_vacuum_size,
            "avg_bytes_per_turn": round((post_checkpoint_total - initial_db_size) / 100.0, 2),
            "safe_boundary_10mb": post_checkpoint_total <= 10 * 1024 * 1024,
            "safe_boundary_64mb": post_checkpoint_total <= 64 * 1024 * 1024,
        },
        "latency_ms": {
            "write_p50": round(statistics.median(write_times), 3),
            "write_p95": round(statistics.quantiles(write_times, n=20)[18], 3),
            "read_p50": round(statistics.median(read_times), 3),
            "read_p95": round(statistics.quantiles(read_times, n=20)[18], 3),
        },
        "cascade_cleanup": {
            "calls_remaining": remaining_calls,
            "manifests_remaining": remaining_manifests,
            "clean": (remaining_calls == 0 and remaining_manifests == 0),
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Measure candidate HXA-217 source-manifest Kotlin/Room cost")
    parser.add_argument("--output", default="build/hxa217-preparation/kotlin-room-cost-report.json")
    args = parser.parse_args()

    scenarios = [
        ("typical_24m_4i", 24, 32, 4, 36, False),
        ("max_valid_512m_512i_uuid", 512, 32, 512, 36, False),
        ("stress_512m_512i_ascii64", 512, 32, 512, 64, False),
        ("stress_512m_512i_ascii256", 512, 32, 512, 256, False),
        ("extreme_512m_512i_unicode256", 512, 32, 512, 256, True),
    ]

    results = []
    for name, msg_count, msg_len, inp_count, inp_len, unicode_fill in scenarios:
        res = measure_scenario(name, msg_count, msg_len, inp_count, inp_len, unicode_fill)
        results.append(res)

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(results, indent=2, ensure_ascii=False))

    print(f"Report saved to {output_path}")
    print("\n================== SUMMARY ==================")
    print(f"{'Scenario':<30} | {'Single Bounded':<14} | {'100-Turn DB':<12} | {'Write P50':<9} | {'Safety (10MB)':<13}")
    print("-" * 88)
    for r in results:
        name = r["name"]
        bounded_b = f"{r['single_row']['bounded_bytes']} B"
        db_post = f"{r['disk_growth_100_turns']['post_checkpoint_total_bytes'] / (1024*1024):.2f} MB"
        write_lat = f"{r['latency_ms']['write_p50']} ms"
        safe = "SAFE PASS" if r["disk_growth_100_turns"]["safe_boundary_10mb"] else "EXCEEDED"
        print(f"{name:<30} | {bounded_b:<14} | {db_post:<12} | {write_lat:<9} | {safe:<13}")


if __name__ == "__main__":
    main()
