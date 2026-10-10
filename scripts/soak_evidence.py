"""Pure parsing helpers for HXA-185. No adb, process control, or test execution."""
import re


def parse_webview_identity(raw):
    """Only the selected provider line is authoritative; never match arbitrary Version fields."""
    selected = re.search(
        r"^\s*Current WebView package \(name, version\):\s*\(([^,\s]+),\s*([0-9]+(?:\.[0-9]+)+)\)\s*$",
        raw, re.MULTILINE | re.IGNORECASE,
    )
    if selected is None:
        return {"webViewProvider": None, "webViewVersion": None,
                "webViewIdentityStatus": "unavailable", "webViewIdentitySource": "dumpsys webviewupdate"}
    return {"webViewProvider": selected[1], "webViewVersion": selected[2],
            "webViewIdentityStatus": "available", "webViewIdentitySource": "current-selected-package"}
