#!/usr/bin/env python3
"""Read only database schema metadata; temporary database copy is removed on exit."""
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile

adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
base = [adb, "-s", sys.argv[1], "exec-out", "run-as", "com.helix.agent.developer"]
with tempfile.TemporaryDirectory(prefix="helix-schema-") as directory:
    db = Path(directory) / "helix.db"
    with db.open("wb") as output:
        subprocess.run(base + ["cat", "databases/helix.db"], stdout=output, check=True)
    connection = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    try:
        tables = [row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
        print(json.dumps({"version": connection.execute("PRAGMA user_version").fetchone()[0],
            "identityHash": connection.execute("SELECT identity_hash FROM room_master_table WHERE id=42").fetchone()[0],
            "composerDraftsExists": "composer_drafts" in tables, "tableCount": len(tables)}, indent=2))
    finally:
        connection.close()
