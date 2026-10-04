"""Persistent host-keyboard defaults for owner-authorized emulator launches."""
import os
from pathlib import Path
import re


def enable_hardware_keyboard(avd_name, avd_home=None):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", avd_name) or avd_name in (".", ".."):
        raise ValueError("Invalid AVD name")
    home = Path(avd_home or os.environ.get("ANDROID_AVD_HOME", Path.home() / ".android/avd"))
    # avdmanager can place an AVD outside the default directory; its .ini is authoritative.
    metadata = home / (avd_name + ".ini")
    directory = home / (avd_name + ".avd")
    if metadata.is_file():
        for line in metadata.read_text().splitlines():
            if line.startswith("path="):
                directory = Path(line.split("=", 1)[1])
                break
    config = directory / "config.ini"
    original = config.read_text()
    replacement = "hw.keyboard=yes"
    if re.search(r"^hw\.keyboard\s*=.*$", original, flags=re.M):
        updated = re.sub(r"^hw\.keyboard\s*=.*$", replacement, original, flags=re.M)
    else:
        updated = original.rstrip("\n") + "\n" + replacement + "\n"
    if updated != original:
        config.write_text(updated)
    return config


def enable_onscreen_keyboard(device):
    # A physical keyboard must not hide the pinyin candidate/soft-keyboard UI.
    device("shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
