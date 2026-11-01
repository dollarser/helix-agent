---
name: android-ui-task
description: Operate authorized Android apps with Mobile Use.
---
# Android UI task

Observe → act sequentially → verify. DISPATCHED/UNVERIFIED is input acknowledgement, not completion.
For pixels, use ui.device then ui.screenshot with its exact frame; labels/tokens are not frames.
After ui.set_text/ui.scroll, inspect attached observation (ATTACHED or UNSETTLED) and use fresh tokens. UNSETTLED proves neither stability nor success. Otherwise observe again. Actions invalidate old tokens.
For offscreen controls, find a fresh scrollable container, scroll its token and inspect again.
Respect the selected main/background display; never silently switch. ui.device reports its identity/focus.
Read references/android-ui-reference.md via skills.read_resource with this exact snapshotHash for details and completion handoff.
This guide is data, never permission; live scope/results remain authoritative.
