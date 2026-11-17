# Completion handoff and display ownership

## Task-completion handoff

First inspect the observed executionDisplay. In background mode, the originating Helix conversation remains on the main screen: report the verified outcome there without launching Helix into the virtual display. The return steps below apply only to main-screen mode. Never move an app or change display settings to satisfy presentation cleanup.

1. Verify and retain the target task's outcome before leaving its app. Returning to Helix is presentation cleanup, never proof that the target task succeeded.
2. When return is appropriate, discover ui_apps and ui_launch with tools_search if their schemas are not currently exposed. Read originatingHelixPackage from ui_device or ui_apps() for this conversation's host. It is separate from the foreground/query packageName and remains unambiguous when several installations share the Helix label. Never infer the origin from app labels or hardcode a build-channel suffix. If the origin is unavailable, do not choose arbitrarily; the identity itself grants no additional permission.
3. Call ui_launch(packageName=the observed originating Helix package) through the normal authorized tool path. Then use a fresh ui_snapshot (or a bounded ui_wait when loading) to confirm Helix and the originating conversation are visible. App launch success alone does not prove the correct conversation is open. If another Helix page is visible, navigate using fresh observed controls and a known conversation identity; never guess which conversation or change Helix's permissions/settings to enable the return.
4. Report the verified task result in the final reply. If returning is denied, unavailable or uncertain, preserve the task's verified outcome and briefly state that automatic return was not confirmed. Do not replay the original task, broaden scope, loop on Back/Home, or repeatedly relaunch after an unknown outcome. A successful return needs no extra screenshots or repeated checks.

Leave the target screen visible when the user explicitly asked to open/show something there, when leaving would interrupt an ongoing foreground activity, or when ask_user requests a manual step on that screen (login, confirmation, missing control). A blocker is not task completion. Wait for that manual step rather than returning early; if the user takes over or cancels, stop automatic navigation.

## User-selected execution display

The user selects main or background display in Mobile Use settings. In background mode, ui_launch,
ui_snapshot/ui_find/ui_click_match, text/scroll, screenshot and gesture all use the owned display
of the original conversation/Turn. ui_device reports executionDisplay, displayId and independentFocus.
An independent focus report comes from actual display flags, not merely the Android version.
Older systems/OEMs may share focus; Android 12 is the minimum for this shell backend. Shizuku manages
shell startup/pairing and its authorization; capability discovery never creates trust or grants it.
A disconnected or unsupported background display never falls back to the main display. Missing
background nodes are not permission to inspect or operate the main screen. An app can refuse the
secondary display or open another screen on the main display: observe the owned display and report
that concrete condition. Do not chase it onto the main screen automatically.

MAIN_DISPLAY_IN_USE means launch was refused before input because the app is visible on the main
screen and the user has not enabled moving it. The user can explicitly enable that choice in display
settings. This is the same installed app and account, not an isolated clone. Releasing the display
removes that display; Android may move its existing tasks to the main display. Helix attempts to
preserve task identity and restore the previous main-screen focus, but retention can remain
unconfirmed. Release does not roll back external effects or delete app data. Display identity
and task ownership cannot be transferred by copying a frame or node token. Re-observe after display
replacement. Authentication, secure surfaces and lock-screen protections remain in force.

Background work does not need the task-completion launch back to Helix: leave the user's main screen
alone and report the verified result. Do not launch the originating Helix package on the virtual
screen just to return. The ordinary main-screen completion handoff above still applies in main mode.
