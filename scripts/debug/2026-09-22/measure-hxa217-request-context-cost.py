#!/usr/bin/env python3
"""Measure candidate HXA-217 source-manifest encodings on the host.

This is a format-only estimate. It does not open Room, Android storage, Provider code, or any
user content. Synthetic opaque IDs are used so the result cannot contain conversation text.
"""

from __future__ import annotations

import argparse
import json
import platform
import re
import statistics
import sys
import time
from pathlib import Path
from typing import Any


ROLE_CODES = ("s", "u", "a", "t")
ITERATIONS = 100


def read_constant(path: Path, name: str) -> int:
    match = re.search(rf"const val {name}\s*=\s*(\d+)", path.read_text(encoding="utf-8"))
    if match is None:
        raise RuntimeError(f"could not find {name} in {path}")
    return int(match.group(1))


def read_input_identifier_limit(path: Path) -> int:
    match = re.search(r"value\.length <= (\d+)", path.read_text(encoding="utf-8"))
    if match is None:
        raise RuntimeError(f"could not find input identifier length in {path}")
    return int(match.group(1))


def synthetic_id(kind: str, index: int, length: int, unicode_fill: bool = False) -> str:
    prefix = f"{kind}{index:04d}"
    fill = "é" if unicode_fill else "a"
    return (prefix + (fill * length))[:length]


def ids(kind: str, count: int, length: int, unicode_fill: bool = False) -> list[str]:
    return [synthetic_id(kind, index, length, unicode_fill) for index in range(count)]


def manifest(
    encoding: str,
    message_count: int,
    message_id_length: int,
    input_id_length: int,
    input_count: int,
    input_unicode: bool,
) -> dict[str, Any]:
    message_ids = ids("msg", message_count, message_id_length)
    input_ids = ids("inp", input_count, input_id_length, input_unicode)
    checkpoint_id = synthetic_id("checkpoint", 1, message_id_length)
    if encoding == "verbose":
        messages: Any = [
            {"id": message_id, "role": ROLE_CODES[index % len(ROLE_CODES)]}
            for index, message_id in enumerate(message_ids)
        ]
        return {
            "schemaVersion": 1,
            "sessionId": synthetic_id("session", 1, message_id_length),
            "turnId": synthetic_id("turn", 1, message_id_length),
            "modelCallId": synthetic_id("call", 1, message_id_length),
            "purpose": "generation",
            "messages": messages,
            "inputIds": input_ids,
            "checkpoint": {
                "id": checkpoint_id,
                "coveredThrough": message_ids[-5],
                "preservedMessageIds": message_ids[-4:],
            },
            "status": "complete",
        }
    if encoding == "compact":
        messages = [[message_id, ROLE_CODES[index % len(ROLE_CODES)]] for index, message_id in enumerate(message_ids)]
        return {
            "v": 1,
            "s": synthetic_id("session", 1, message_id_length),
            "t": synthetic_id("turn", 1, message_id_length),
            "c": synthetic_id("call", 1, message_id_length),
            "p": "generation",
            "m": messages,
            "i": input_ids,
            "k": [checkpoint_id, message_ids[-5], message_ids[-4:]],
            "q": "complete",
        }
    raise ValueError(f"unknown encoding: {encoding}")


def json_bytes(value: dict[str, Any]) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def export_row(data: dict[str, Any], sequence: int) -> dict[str, Any]:
    call_id = data.get("modelCallId", data.get("c"))
    session_id = data.get("sessionId", data.get("s"))
    return {
        "format": "helix.session-export",
        "formatVersion": 1,
        "exportId": synthetic_id("export", 1, 32),
        "sequence": sequence,
        "type": "model_call",
        "recordId": f"model_call:{call_id}",
        "sessionId": session_id,
        "data": data,
    }


def measure_case(
    encoding: str,
    message_count: int,
    message_id_length: int,
    input_id_length: int,
    input_count: int,
    input_unicode: bool = False,
) -> dict[str, Any]:
    data = manifest(encoding, message_count, message_id_length, input_id_length, input_count, input_unicode)
    payload = json_bytes(data)
    row = json_bytes(export_row(data, 0))
    encode_samples: list[float] = []
    decode_samples: list[float] = []
    row_sizes: list[int] = []
    for sequence in range(ITERATIONS):
        started = time.perf_counter_ns()
        encoded = json_bytes(export_row(data, sequence))
        row_sizes.append(len(encoded) + 1)
        encode_samples.append((time.perf_counter_ns() - started) / 1_000.0)
        started = time.perf_counter_ns()
        json.loads(encoded)
        decode_samples.append((time.perf_counter_ns() - started) / 1_000.0)
    return {
        "messageCount": message_count,
        "inputCount": input_count,
        "messageIdLength": message_id_length,
        "inputIdLength": input_id_length,
        "inputIdUnicode": input_unicode,
        "payloadBytes": len(payload),
        "jsonlRowBytes": len(row) + 1,
        "repeated100Bytes": sum(row_sizes),
        "encodeMicros": {
            "median": round(statistics.median(encode_samples), 2),
            "p95": round(statistics.quantiles(encode_samples, n=20)[18], 2),
        },
        "decodeMicros": {
            "median": round(statistics.median(decode_samples), 2),
            "p95": round(statistics.quantiles(decode_samples, n=20)[18], 2),
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model-request", type=Path, required=True)
    parser.add_argument("--identifiers", type=Path, required=True)
    parser.add_argument("--session-input-validation", type=Path, required=True)
    parser.add_argument("--export-format", type=Path, required=True)
    args = parser.parse_args()

    max_messages = read_constant(args.model_request, "MAX_MESSAGES")
    max_identifier_length = read_constant(args.identifiers, "MAX_LENGTH")
    max_input_identifier_length = read_input_identifier_limit(args.session_input_validation)
    cases: dict[str, dict[str, dict[str, Any]]] = {}
    for encoding in ("verbose", "compact"):
        cases[encoding] = {
            "typical_24_messages": measure_case(encoding, 24, 32, 36, 4),
            "stress_512_messages_512_inputs_uuid36": measure_case(encoding, max_messages, 32, 36, max_messages),
            "stress_512_messages_512_inputs_id64": measure_case(
                encoding,
                max_messages,
                max_identifier_length,
                max_identifier_length,
                max_messages,
            ),
            "stress_512_messages_512_inputs_id256_ascii": measure_case(
                encoding,
                max_messages,
                32,
                max_input_identifier_length,
                max_messages,
            ),
            "stress_512_messages_512_inputs_id256_unicode": measure_case(
                encoding,
                max_messages,
                32,
                max_input_identifier_length,
                max_messages,
                input_unicode=True,
            ),
        }

    result = {
        "measurement": {
            "kind": "host_format_estimate_only",
            "iterations": ITERATIONS,
            "python": sys.version.split()[0],
            "platform": platform.platform(),
            "idSource": "RandomIdGenerator emits 32 lowercase hex characters; input IDs commonly use UUID strings of length 36, while SessionInputValidation permits 256 characters including non-ASCII",
            "syntheticIdsOnly": True,
            "noUserContent": True,
        },
        "sourceFacts": {
            "modelRequestMaxMessages": max_messages,
            "identifierMaxLength": max_identifier_length,
            "maxAppendedSourceMessages": max_messages,
            "observedInputIdLength": 36,
            "sessionInputIdentifierMaxLength": max_input_identifier_length,
            "exportFormat": "helix.session-export",
            "exportFormatVersion": 1,
            "exportLineLimitBytes": 256 * 1024,
            "exportFileLimitBytes": 128 * 1024 * 1024,
            "formatSource": str(args.export_format),
        },
        "cases": cases,
        "interpretation": {
            "verbose": "object messages with explicit id and role fields",
            "compact": "tuple messages [id, role-code] with short top-level keys; role codes are s/u/a/t",
            "timing": "Python host json.dumps/json.loads only; excludes Room, Android filesystems, JSONL streaming, and device cost",
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
