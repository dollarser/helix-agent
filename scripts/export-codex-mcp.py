#!/usr/bin/env python3
"""Export only MCP configuration from a user-selected Codex TOML, without credentials.

Python 3.11+. No network, environment expansion, command execution, or config mutation.
"""
import argparse
import json
import sys
import tomllib
from pathlib import Path
from urllib.parse import urlsplit

AUTH_FIELDS = {"http_headers", "env_http_headers", "bearer_token_env_var", "headers", "oauth", "auth"}


def export_servers(raw: bytes) -> dict:
    if len(raw) > 1024 * 1024:
        raise ValueError("configuration exceeds 1 MiB")
    document = tomllib.loads(raw.decode("utf-8"))
    servers = document.get("mcp_servers", {})
    if not isinstance(servers, dict) or not 0 < len(servers) <= 32:
        raise ValueError("expected 1..32 MCP servers")
    result = {}
    for name, server in servers.items():
        if not isinstance(server, dict):
            raise ValueError("invalid server table")
        portable = {"enabled": False}
        if "command" in server:
            # Retain only the transport diagnostic, never argv or env values.
            portable["type"] = "stdio"
        elif isinstance(server.get("url"), str):
            url = server["url"]
            parts = urlsplit(url)
            if (parts.scheme == "https" and parts.hostname and not parts.username
                    and not parts.password and not parts.query and not parts.fragment
                    and "$" not in url and "{" not in url):
                portable["url"] = url
            portable["type"] = "http"
        if AUTH_FIELDS.intersection(server):
            portable["auth"] = "configure-in-helix"
        result[name] = portable
    return {"mcpServers": result}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        with args.config.open("rb") as source:
            payload = export_servers(source.read(1024 * 1024 + 1))
        # Refuse overwrite: exports should not destroy an existing user file.
        with args.output.open("x", encoding="utf-8") as target:
            json.dump(payload, target, ensure_ascii=False, indent=2)
            target.write("\n")
    except (OSError, ValueError, TypeError):
        print("Export failed: check the TOML, input size, and unused output path.", file=sys.stderr)
        return 1
    print("Exported MCP configuration. Configure credentials independently in Helix.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
