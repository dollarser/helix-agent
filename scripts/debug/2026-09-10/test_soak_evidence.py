"""Host regression cases for Claude to execute; writing this file does not run tests."""
import unittest
from soak_evidence import parse_webview_identity


class WebViewIdentityTest(unittest.TestCase):
    def test_selected_provider_not_minimum_sdk_or_available_package(self):
        raw = '''Minimum targetSdkVersion: 33
Current WebView package (name, version): (com.google.android.webview, 139.0.7258.138)
Valid package com.android.webview (versionName: 138.0.0.0)
'''
        self.assertEqual(parse_webview_identity(raw)["webViewVersion"], "139.0.7258.138")
        self.assertEqual(parse_webview_identity(raw)["webViewProvider"], "com.google.android.webview")

    def test_unknown_does_not_fall_back_to_default_or_generic_version(self):
        for raw in ["", "Permission Denial", "Minimum targetSdkVersion: 33",
                    "Default Webview Implementation: com.android.webview\nVersion: 33",
                    "Current WebView package is null"]:
            with self.subTest(raw=raw):
                self.assertIsNone(parse_webview_identity(raw)["webViewVersion"])
                self.assertEqual(parse_webview_identity(raw)["webViewIdentityStatus"], "unavailable")

    def test_indented_selected_line(self):
        self.assertEqual(parse_webview_identity(
            "  Current WebView package (name, version): (com.android.webview, 74.0.3729.185)\n"
        )["webViewVersion"], "74.0.3729.185")


if __name__ == "__main__":
    unittest.main()
