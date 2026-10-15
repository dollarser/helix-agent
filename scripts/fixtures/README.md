# Acceptance fixtures

These are helpers of the existing acceptance runners, not standalone device grants.
Only run them inside the current task's explicitly authorized owned-device lifecycle.
The existing owner, serial, process, permission and evidence checks remain in each
fixture; promotion does not broaden their device or account scope.

| Directory | Responsibility | Caller |
| --- | --- | --- |
| `conversation/` | Composer/revision/input process recovery; synthetic input server | `accept-conversation-interaction.py`; historical bounded follow-up |
| `extensions/` | Durable extension scope after process restart | `run-acceptance-matrix.py`; HXA-207 owned runner |
| `product/` | Collect named synthetic theme/export artifacts before shutdown | `run-acceptance-matrix.py` |
| `performance/` | Validate and collect fixed projection/cold-start samples | Explicit owned-runner after-script |

`conversation/history.py` owns bounded exact-title list lookup. Both former Composer
variants use the single `composer-recovery.py` fixture. Domain-specific assertions
remain in the fixture; protocol parsing and process control remain in the existing
`scripts/instrumentation_junit.py` and `scripts/android_process_control.py` modules.
Repository lookup starts from the actual source file and finds project markers;
helper imports no longer assume a dated directory's fixed parent count.

Do not import executable after-scripts as libraries: they intentionally require the
runner's arguments and environment. Import-safe helper functions have pure host
coverage under `scripts/tests/`.
