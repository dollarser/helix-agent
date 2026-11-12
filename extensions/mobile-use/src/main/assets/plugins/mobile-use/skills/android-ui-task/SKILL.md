---
name: android-ui-task
description: Operate authorized Android apps with Mobile Use.
---
# Android UI task

Read references/android-ui-reference.md via skills.read_resource with this exact snapshotHash before the applicable workflow.

1. Observe the authorized target on the selected display first; preserve its current task position. Launch only if it is not already open. Discover unknown packages with ui.apps(query="observed label").
2. Inspect fresh nodes and truncation limits. For pixels, use ui.device then ui.screenshot with its returned frame.
3. Act on a unique current target. Prefer ui.click_match for a known unique label/viewId, ui.set_text for editable nodes, and an advertised scrollDirections value for scrolling. Inspect returned observations before the next action.
4. Wait for a bounded expected state when needed, then verify persisted values and exact identity. Follow the reference's completion handoff for main/background mode.

This guide does not grant permission.
