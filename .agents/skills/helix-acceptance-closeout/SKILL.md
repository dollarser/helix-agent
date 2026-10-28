---
name: helix-acceptance-closeout
description: "Close out a Helix candidate or review whether its acceptance evidence supports delivery. Use for release preparation, scoped validation, artifact handoff, or acceptance gaps; not for routine edits that do not need candidate acceptance."
---

# Helix Acceptance Closeout

Use the ongoing task and its compaction summary; this skill does not choose work from the repository queue or grant device, account, deletion or publication authority. Project boundaries remain in [AGENTS.md](../../../AGENTS.md).

## Relevant acceptance evidence

- Bind successful build inputs, APK identity and installed bytes. A failed build's leftover APK is not a candidate; reuse unchanged evidence with its applicable boundary.
- Choose checks for the requested outcome and affected paths. Separate host tests, test APK compilation, device effects, durable results and actual model consumption; a wrapper or Turn completion alone is insufficient.
- Preserve user data and unrelated work. Daily-emulator replacement needs the designated instance and matching package/signature; verify current-state recovery and applicable platform compatibility. Pre-launch test data needs no App migration chain: use an external one-time transformation when useful or a targeted clean installation with concrete authorization. This does not authorize automatic data clearing; cleanup touches only task-owned resources.
- Keep failures, skips and untested conditions visible. Fix scoped defects without weakening boundary assertions. Missing authority or destructive installation blocks that action, not independent work.

Existing commands are in the [verification reference](../../../docs/development/verification-matrix.md) and [debugging reference](../../../docs/development/debugging-playbook.md): `check-all.sh`, `build-release-apks.sh`, owned emulator runners and `instrumentation_junit.py` / `summarize-owned-runs.py`. Use what fits the task, without a duplicate runner or fixed test-count convention. [Release readiness](../../../docs/development/release-readiness.md) applies to explicitly authorized distribution.

Report concise implementation/verification results, source and candidate identity, remaining conditions and cleanup. Keep raw evidence in ignored `build/<task>/`, temporary helpers in `scripts/local/debug/YYYY-MM-DD/`, and stable conclusions in the relevant task/evidence record. `not requested` and `pending` are not passes.
