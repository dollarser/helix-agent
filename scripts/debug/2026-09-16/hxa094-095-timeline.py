"""HXA-094/095 su-timeline parser for run-hxa094-095-rooted.sh (2026-09-16).

Timeline format (written by the run script's host-side poller):
    CYCLE|<epoch_ms>|<appPid or empty>|<phase or empty>
    ROW|<pid>|<ppid>|<pgid>|<name>

Usage:
    hxa094-095-timeline.py check   <timeline>  # background-transition assertions
    hxa094-095-timeline.py setup   <timeline>  # prints "suPid:pgrp:remotePid[,remotePid...]"
    hxa094-095-timeline.py verify  <timeline>  # no app-owned su may appear (verify window)
"""

import sys


def load_cycles(path):
    cycles = []
    current = None
    with open(path) as handle:
        for line in handle:
            line = line.rstrip("\n")
            if line.startswith("CYCLE|"):
                parts = line.split("|", 3)
                if len(parts) < 4:
                    continue
                try:
                    ts = int(parts[1])
                except ValueError:
                    continue
                current = {
                    "ts": ts,
                    "app_pid": parts[2].strip(),
                    "phase": parts[3].split()[0] if parts[3].split() else "",
                    "remote_pid": parts[3].split()[1] if len(parts[3].split()) > 1 else "0",
                    "procs": [],
                }
                cycles.append(current)
            elif line.startswith("ROW|") and current is not None:
                # A raw `ps -A -o PID,PPID,PGID,NAME` line (whitespace separated); the
                # process name is the final field and may contain spaces.
                fields = line[4:].split()
                if len(fields) >= 4 and fields[0].isdigit():
                    current["procs"].append((fields[0], fields[1], fields[2], " ".join(fields[3:])))
    return cycles


def app_children(cycle):
    app_pid = cycle["app_pid"]
    if not app_pid:
        return []
    return [p for p in cycle["procs"] if p[1] == app_pid]


def app_sus(cycle):
    return [p for p in app_children(cycle) if p[3] == "su"]


def app_descendants(cycle):
    """Pids of the app's direct children and their children (name-agnostic)."""
    children = {p[0] for p in app_children(cycle)}
    app_pid = cycle["app_pid"]
    grandchildren = {p[0] for p in cycle["procs"] if p[1] in children and p[1] != app_pid}
    return children | grandchildren | ({cycle["remote_pid"]} if cycle["remote_pid"] != "0" else set())


def check_background(timeline):
    cycles = load_cycles(timeline)

    phases_seen = {c["phase"] for c in cycles if c["phase"]}
    missing = {"connected", "backgrounded", "foreground"} - phases_seen
    if missing:
        sys.exit(f"timeline is missing phases: {sorted(missing)}")

    connected = [c for c in cycles if c["phase"] == "connected" and app_sus(c)]
    if not connected:
        sys.exit("no sample shows the app's own su process while the phase is 'connected'")

    # Everything observed in the app's process tree while connected (the su shell and its
    # libsu-internal descendants) must be gone from the first backgrounded sample onward,
    # and no new app-owned su may appear (no automatic rebind).
    observed = set()
    for cycle in cycles:
        if cycle["phase"] == "connected":
            observed |= app_descendants(cycle)

    seen_backgrounded = False
    for cycle in cycles:
        if cycle["phase"] == "backgrounded":
            seen_backgrounded = True
        if not seen_backgrounded:
            continue
        survivors = [p for p in cycle["procs"] if p[0] in observed]
        if survivors:
            sys.exit(f"app process tree survived the background transition: {survivors[:3]}")
        if app_sus(cycle):
            sys.exit(f"new app-owned su appeared after the background transition (auto-rebind?): {app_sus(cycle)[0]}")
    if not seen_backgrounded:
        sys.exit("timeline never reached the 'backgrounded' phase")

    print(
        f"timeline OK: app su present during 'connected' ({len(connected)} samples), "
        f"observed process tree {sorted(observed)} gone from 'backgrounded' onward, no rebind"
    )


def setup_identity(timeline):
    cycles = load_cycles(timeline)
    best = None
    for cycle in cycles:
        sus = app_sus(cycle)
        if cycle["phase"] != "connected" or cycle["remote_pid"] == "0":
            continue
        if not sus:
            continue
        su = sus[0]
        # The remote process is a child of the su shell; record every observed one.
        remotes = sorted({cycle["remote_pid"]} | {p[0] for p in cycle["procs"] if p[1] == su[0]})
        best = (su[0], su[2], ",".join(sorted(remotes)))
    if best is None:
        sys.exit("setup timeline shows no su process with PPID == app pid")
    print(f"{best[0]}:{best[1]}:{best[2]}")


def check_verify(timeline):
    """Verify-run window: the fresh process never requests Root, so no app-owned su may
    appear at all (a new one would be an automatic rebind regression)."""
    cycles = load_cycles(timeline)
    if not any(c["app_pid"] and c["phase"] == "verify" and c["procs"] for c in cycles):
        sys.exit("verify timeline has no live verification sample")
    bad = [c for c in cycles if app_sus(c)]
    if bad:
        cycle = bad[0]
        sys.exit(f"app-owned su appeared during the verify run (automatic rebind?): {app_sus(cycle)[0]}")
    print(f"verify timeline OK: no app-owned su across {len(cycles)} cycles")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    command, timeline_path = sys.argv[1], sys.argv[2]
    if command == "check":
        check_background(timeline_path)
    elif command == "setup":
        setup_identity(timeline_path)
    elif command == "verify":
        check_verify(timeline_path)
    else:
        sys.exit(__doc__)
