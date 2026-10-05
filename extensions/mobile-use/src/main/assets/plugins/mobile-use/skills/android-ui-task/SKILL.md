---
name: android-ui-task
description: Operate authorized Android apps with Mobile Use.
---

[MOBILE_USE]
Use the exposed Android tools for phone UI tasks. Linux/PRoot is not an Android ADB/root shell. A tool being listed does not grant permission; obey the current mode, scope and live tool result.
Observe → act → wait when needed → verify the requested end state. Dependent UI actions belong in separate calls after the previous result; do not batch them concurrently.
Leave backend selection automatic: the host prefers ready Root, then Shizuku, then Accessibility. Node tokens bind to their observed backend and target. Use the latest token or suggestedClickToken. Every successful action invalidates ALL earlier tokens, including tokens for other nodes. When observationRequired=true, call ui.snapshot or ui.find before the next node action; never reuse another token from the earlier snapshot. Omitted snapshot flags mean false. Use checked to distinguish a switch state from its label.
A label can exist in the UI tree while outside the viewport. If offscreen=true or bounds are empty/inverted, scroll a fresh scrollable container token and observe again before clicking. Do not interpret repeated TARGET_CHANGED as a reason to guess coordinates. For an editable token, call ui.set_text directly; clicking first unnecessarily opens the keyboard and may obscure controls.
Use verified URLs rather than guessing download paths. For a prerequisite setting, prefer the originating app's native control so it receives its expected return/result; discover android.open_settings only when needed. Re-observe after returning. Normal system installation confirmation is part of an explicitly authorized installation when policy permits it. For a known web URL use android.open_uri. If missing information requires fetching an authoritative page, discover http.fetch with tools.search. Use ui.apps for a positive installed-app observation; its missing entries are not proof of absence because Android visibility, scope and truncation limit the list.
Examples (use actual observed values, not these labels as fixed targets):
- Waiting for a visible Continue control: ui.wait(text="Continue", condition="present", timeoutMillis=5000); when waitStatus=FOUND and the intended target is unique, ui.click(token=suggestedClickToken), then observe. A wait timeout is not proof that the preceding action failed.
- An action returns STALE_TOKEN or TARGET_CHANGED: obtain a fresh observation before selecting a new action. Never reuse the old token or coordinates.
- An installation screen reports completion: verify with a fresh success observation or ui.apps before reporting success. Source permission, download completion, opening the installer, and dispatching Install alone do not prove installation.
When semantics cannot expose the target, use ui.device then ui.screenshot. Only pixelsAttached=true permits deriving coordinates from the image, mapped through screenBounds; use that screenshot's frame for ui.gesture without refreshing ui.device in between. Missing pixels never justify guessing. Policy denial and protected UI are not reasons to switch backends.
For ACTION_FAILED or ACTION_OUTCOME_UNKNOWN, the effect may have happened: inspect with read-only tools and do not replay. A successful dispatch still needs result verification. If waiting has no progress, report the concrete unresolved state rather than repeating unchanged calls. If another permitted action is needed, call it instead of ending with a promise to do it.
[/MOBILE_USE]
