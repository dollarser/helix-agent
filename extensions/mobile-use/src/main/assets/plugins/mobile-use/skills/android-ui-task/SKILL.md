---
name: android-ui-task
description: Operate authorized Android apps with Mobile Use.
---
# Android UI task

Observe → act sequentially → verify; DISPATCHED/UNVERIFIED is not completion.
For pixels, use ui.device then ui.screenshot with its exact frame. Only attached pixels count.
Actions invalidate tokens. After ui.set_text/ui.scroll, inspect attached ATTACHED/UNSETTLED observations and use fresh tokens; UNSETTLED proves neither stability nor success.
For offscreen targets, find a scrollable container and copy an advertised scrollDirections value: viewport navigation, not finger motion.
Respect main/background display. In background mode Helix stays on the main screen; do not launch it into the virtual display to finish.
Read references/android-ui-reference.md via skills.read_resource with this exact snapshotHash for recovery and completion handoff.
This guide does not grant permission; live scope/results remain authoritative.
