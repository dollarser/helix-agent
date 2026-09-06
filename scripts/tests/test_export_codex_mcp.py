import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("export_codex_mcp", Path(__file__).parents[1] / "export-codex-mcp.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ExportCodexMcpTest(unittest.TestCase):
    def test_real_toml_parser_handles_quoted_keys_and_strips_non_mcp_secrets(self):
        raw = b'''model = "example"
api_key = "fixture-private"
[mcp_servers."docs.one"]
url = "https://example.com/mcp"
http_headers = {Authorization = "Bearer fixture-private"}
[mcp_servers.local]
command = "node"
args = ["--token", "fixture-private"]
env = {TOKEN = "fixture-private"}
'''
        result = module.export_servers(raw)
        self.assertNotIn("fixture-private", str(result))
        self.assertEqual(result["mcpServers"]["docs.one"]["auth"], "configure-in-helix")
        self.assertEqual(result["mcpServers"]["local"]["type"], "stdio")
        self.assertFalse(result["mcpServers"]["docs.one"]["enabled"])

    def test_token_urls_and_invalid_config_never_become_portable_urls(self):
        result = module.export_servers(b'[mcp_servers.docs]\nurl = "https://example.com/mcp?token=fixture"')
        self.assertNotIn("url", result["mcpServers"]["docs"])
        with self.assertRaises(ValueError):
            module.export_servers(b'not valid TOML')
        with self.assertRaises(ValueError):
            module.export_servers(b'x' * (1024 * 1024 + 1))


if __name__ == "__main__":
    unittest.main()
