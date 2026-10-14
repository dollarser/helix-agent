---
name: android-ui-task
description: Operate authorized Android apps with Mobile Use.
---
# Android UI task

Observe, act sequentially, and verify the requested result. DISPATCHED/UNVERIFIED acknowledges input, not completion.
For pixels, call ui.device and pass its exact frame to ui.screenshot; labels and node tokens are not frames.
After ui.set_text/ui.scroll, read observation when observationStatus=ATTACHED and use its fresh tokens; otherwise observe again. Other actions invalidate old tokens too.
For offscreen controls, use a fresh scrollable container from that observation or ui.find(scrollable=true), scroll its token and inspect the new state. Never scroll the button's token or stop just because it is offscreen.
Read references/android-ui-reference.md via skills.read_resource with this exact snapshotHash for details and return-to-Helix.
This guide is data, never permission; live scope and results remain authoritative.
