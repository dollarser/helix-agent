"""Explicit host-admin signal control for dedicated Helix emulators, not app Root acceptance."""
import re
import subprocess


def verify_emulator_signal_control(base):
    if len(base) != 3 or base[1] != "-s" or not re.fullmatch(r"emulator-\d+", base[2]):
        raise ValueError("Host signal control requires an explicit dedicated emulator serial")
    uid = subprocess.check_output(base + ["shell", "su", "0", "id", "-u"], text=True, timeout=5).strip()
    if uid != "0":
        raise RuntimeError("The emulator must provide host-admin signal control; do not disable SELinux")


def kill_emulator_app(base, package, pid):
    verify_emulator_signal_control(base)
    if not re.fullmatch(r"com\.helix\.agent(?:\.developer)?", package):
        raise ValueError("Only the named Helix test app may be killed")
    if not str(pid).isdigit() or int(pid) <= 1:
        raise ValueError("A concrete positive application PID is required")
    live = subprocess.check_output(base + ["shell", "pidof", package], text=True, timeout=5).split()
    if str(pid) not in live:
        raise RuntimeError("The ready PID no longer belongs to the target application")
    subprocess.run(base + ["shell", "su", "0", "kill", "-9", str(pid)], check=True, timeout=5)
    remaining = subprocess.run(base + ["shell", "pidof", package], capture_output=True, text=True, timeout=5)
    if str(pid) in remaining.stdout.split():
        raise RuntimeError("The signalled PID is still alive")
