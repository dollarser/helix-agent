---
name: helix-acceptance-closeout
description: "Close out a Helix candidate or review whether its acceptance evidence supports delivery. Use for release preparation, scoped validation, artifact handoff, or acceptance gaps; not for routine edits that do not need candidate acceptance."
---

# Helix Acceptance Closeout

## Establish the acceptance scope

Work from the Helix repository root. Read its applicable [AGENTS.md](../../../AGENTS.md), the current work in [status](../../../docs/development/status.md), and the requested HXA or owner instruction. Follow the [implementation guide](../../../docs/development/implementation-guide.md) for authority and handoff; use the [verification matrix](../../../docs/development/verification-matrix.md) only for relevant gates.

Record the current branch, HEAD, existing changes and their owners. Resolve the candidate, successful build source, requested checks and current authorization from available evidence before asking for missing inputs. Preserve authorization within the same ongoing task; availability of a device, account or old result is not authorization for a new task. This skill does not authorize device use, real services, data removal, commits, pushes or releases.

## Bind evidence to the actual candidate

- Distinguish production source, later documentation commits, build inputs, APK identity and installed bytes. An APK remaining after a failed build does not validate the attempted source change. Reuse unchanged evidence only with a stated reason and boundary.
- Reuse existing scripts: `scripts/check-all.sh` for applicable host gates, `scripts/build-release-apks.sh` for an authorized build, and `scripts/instrumentation_junit.py` / `scripts/summarize-owned-runs.py` for existing evidence. Read their arguments before invocation; do not invent a parallel runner or counting scheme.
- For explicitly requested isolated emulator checks, use `scripts/run-owned-emulator.py` or the applicable existing runner. A request to update the daily emulator requires that exact instance, matching package/signature and a non-destructive database preflight; do not replace it with a disposable instance or clear its data. Follow the root keyboard preference without adding unrelated IME tests.
- Check execution, independently observed effects, durable result persistence and actual model consumption separately where the task requires them. A completed Turn, model claim or successful wrapper alone is insufficient.
- Report host checks, AndroidTest compilation, device execution, real-provider tasks and media end-to-end work separately. Preserve failures and identify adopted passing subsets; do not convert an incomplete run into a complete pass.

## Resolve or report concrete blockers

Use the [debugging playbook](../../../docs/development/debugging-playbook.md) for failed checks. Fix reproducible in-scope defects and rerun affected acceptance; do not weaken assertions or retry without a hypothesis. Stop the dependent action if the candidate cannot be identified, required authority is absent, or installation requires unapproved data loss. Continue independent authorized work and report the exact missing condition. Do not expand the task into new platforms, accounts or an unlimited stability campaign.

## Deliver and clean up

Use [release readiness](../../../docs/development/release-readiness.md) for requested distribution work. Before an authorized push, verify the destination, inspect the entire outgoing range for sensitive content and unrelated changes, recheck remote concurrency, and confirm the remote commit after pushing. A push is not a tag, Release or successful CI run.

Keep raw logs, hashes and inventories under ignored `build/<task>/`; keep temporary helpers under `scripts/local/debug/YYYY-MM-DD/`. Record the concise result in an existing relevant evidence/HXA location and update `status` only when current facts change. Include commands, candidate provenance, pass/fail/skip counts, untested boundaries and remaining inputs. Shut down only task-owned temporary resources; leave an owner-requested daily instance available. Never claim `not requested` or `pending` as a pass.
