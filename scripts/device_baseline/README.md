# Device baseline tools

These tools run and classify explicitly selected Android test classes. They do not
launch an emulator or grant device authorization. Use the existing owned runner for
process ownership and pass the designated serial explicitly.

- `manifest.py`: discover classes from current source; generated defaults go to ignored `build/device-baseline/`.
- `run.py CLASSES --serial SERIAL --out-dir FRESH_PATH`: execute classes, preserving locks, atomic results and recovery evidence.
- `summary.py OUT_DIR --manifest MANIFEST`: validate and classify collected results.
- `expectations.py`: historical failure signatures and required host phases, separated from parsing and execution.

Historical classifications are not passes; current acceptance still requires all
requested cases to pass. An unrelated working directory is supported by each CLI's
`--help` and the default source discovery. Explicit relative input/output arguments
remain relative to the caller's working directory.

The package replaces the three dated Python entry points and positional shell shim
from `scripts/debug/2026-09-26/`. Recover historical invocation sources using the
[script curation inventory](../debug/archive/2026-10-15-script-curation.json).
