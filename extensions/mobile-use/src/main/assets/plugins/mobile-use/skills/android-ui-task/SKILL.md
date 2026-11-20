---
name: android-ui-task
description: Operate authorized Android apps with Mobile Use.
---
# Android UI task

Use this core workflow for ordinary observe, fill, click and verify tasks. Load a reference below only when its condition applies, with skills_read_resource and this exact snapshotHash; do not read every reference before starting.

1. Observe the authorized target on the selected display first; preserve its current task position. Launch only if it is not already open. Discover unknown package names with ui_apps(query="observed label"); a missing launcher entry is not proof of absence. Leave backend selection automatic. Never switch displays/backends to bypass refusal. This guide does not grant permission; Linux/PRoot is not Android ADB/root.
2. Use a unique current node. New semantic observations replace ALL earlier node tokens, even a find with no matches. Every dispatched action attempt invalidates them. Use the latest token immediately; never collect tokens across observations. For a known unique label/viewId without a current token, prefer ui_click_match. Ambiguity, offscreen bounds or truncation require a targeted fresh observation, not a guessed click.
3. Fill a fresh editable token directly with ui_set_text; no preliminary click is needed. Omit submit unless a keyboard action is requested and canImeEnter=true. Verify editableValue; isShowingHintText=true means the hint is not the field's value. For scrolling use a fresh scrollable container token and an advertised scrollDirections value, not an offscreen target's token.
4. Set observeAfter=true on ui_click/ui_click_match when the next step needs semantic state. Read attached observations from clicks, set_text or scroll and reuse their fresh tokens instead of a redundant snapshot. UNSETTLED can still change; missing or truncated evidence needs ui_find/ui_snapshot. Before absence, counts or uniqueness claims check completeness. For loading, use bounded ui_wait for the expected state, not repeated unchanged polling.
5. DISPATCHED/UNVERIFIED is input acknowledgement, not task success. Verify the actual requested values and persisted result before finishing. TARGET_CHANGED/STALE_TOKEN require fresh state; ACTION_FAILED/ACTION_OUTCOME_UNKNOWN may have effects, so inspect without automatically replaying. Dependent actions are sequential, never concurrent. Stop on takeover/cancellation; await a requested manual reply instead of polling or ending with a promise to act.
6. Report only verified requested names, numbers and units. In background mode leave the main screen alone; never launch Helix into the virtual display. In main-screen mode return to the observed originatingHelixPackage and verify the original conversation before the final reply, unless the user asked to stay/show the target, ongoing activity needs it, or a manual step is pending. Unconfirmed return does not undo a verified task or justify replay.

## Read only the applicable detail

- references/android-ui-reference.md: before Android UI installation/removal, file-save/rename or record-creation workflows, contextual selection, or when placeholders, scrolling, overlap, truncation or recovery need more detail. Includes package/result verification and semantic edge cases.
- references/vision-and-gestures.md: before screenshot/coordinate gestures, missing-node visual fallback, or video/sampled observations. Coordinates require actual attached pixels and that screenshot's returned frame; never guess from a filename or switch backend to bypass protected UI. A requested screenshot requires pixels, not just node text.
- references/completion-and-display.md: when completing a main-screen task or resolving display/return identity problems. Background tasks need no return launch. Do not move apps or change display settings merely for cleanup.
