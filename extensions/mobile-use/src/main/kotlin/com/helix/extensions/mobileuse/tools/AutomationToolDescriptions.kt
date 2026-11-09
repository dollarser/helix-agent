package com.helix.extensions.mobileuse.tools

/** Task-specific tool guidance; execution and permission remain in AutomationTools. */
internal object AutomationToolDescriptions {
    fun description(name: String): String =
        when (name) {
            AutomationTools.SNAPSHOT -> {
                SNAPSHOT_DESCRIPTION
            }

            AutomationTools.FIND -> {
                FIND_DESCRIPTION
            }

            AutomationTools.WAIT -> {
                WAIT_DESCRIPTION
            }

            AutomationTools.CLICK_MATCH -> {
                CLICK_MATCH_DESCRIPTION
            }

            AutomationTools.CLICK -> {
                CLICK_DESCRIPTION
            }

            AutomationTools.LONG_CLICK -> {
                "Invoke the standard long-click action on a fresh node with longClickable=true; " +
                    "prefer this to a coordinate hold for selection or contextual actions. " +
                    "Use the observed longClick.token, which may identify the label's actionable ancestor. " +
                    "Observe the resulting screen."
            }

            AutomationTools.SET_TEXT -> {
                "Replace text directly in a fresh editable node token; " +
                    "no preliminary click or keyboard opening is needed. " +
                    "Omit submit for ordinary filling (default false); text insertion does not need IME submission. " +
                    "Set submit=true only when canImeEnter=true " +
                    "and the task requests a keyboard action. Read the returned observation for fresh tokens; " +
                    "The host briefly checks stability; UNSETTLED may include fresh state that is still changing. " +
                    "Inspect it before deciding what to do."
            }

            AutomationTools.IME_ENTER -> {
                "Submit the keyboard action for a fresh node token with canImeEnter=true; does not insert text."
            }

            AutomationTools.SET_PROGRESS -> {
                "Set a fresh node token's numeric range value; use the observed range and canSetProgress capability."
            }

            AutomationTools.SCROLL -> {
                SCROLL_DESCRIPTION
            }

            AutomationTools.BACK -> {
                "Navigate back once in the authorized phone session using the host-selected ready backend. " +
                    "Observe where navigation ends."
            }

            AutomationTools.HOME -> {
                "Go to Android Home in the authorized session using the host-selected ready backend. " +
                    "This does not finish the user's task."
            }

            else -> {
                error("Unknown automation tool: $name")
            }
        }
}

private const val SNAPSHOT_DESCRIPTION =
    "Observe the current authorized Android screen without acting. Returns fresh node tokens, " +
        "labels, bounds and state; omitted flags mean false. " +
        "A node's longClick supplies ui.long_click and its fresh target token for selection/context actions. " +
        "Compare ancestorContexts for overlapping same-label controls; visibleToUser does not prove topmost. " +
        "Each new semantic observation replaces earlier tokens, even if the screen looks unchanged. " +
        "Inspect this result before another read; do not collect tokens across " +
        "observations. For scrolling choose a node with scrollable=true. " +
        "Offscreen nodes require scrolling before clicking. " +
        "Check truncated/recoveryHint before " +
        "concluding a control is absent. Observation chooses ready Root, then a computer ADB helper, " +
        "then Shizuku, then Accessibility."

private const val FIND_DESCRIPTION =
    "Find controls in a fresh screen observation using observed labels or attributes. " +
        "Default text matching is exact; use match=contains for a substring. " +
        "Compare ancestorContexts for same-label overlapping controls; never pick the first arbitrarily. " +
        "A node's longClick gives the tool and token for selection/context actions. " +
        "For simply clicking a known label/viewId, prefer ui.click_match instead of find then click. " +
        "An actionable unique match supplies suggestedClickToken: if intended, call ui.click immediately. " +
        "This observation replaces all earlier node tokens. Offscreen matches are not clickable: " +
        "find a fresh container with scrollable=true, scroll its token and read the returned observation. " +
        "Does not click or wait."

private const val SCROLL_DESCRIPTION =
    "Scroll a fresh scrollable node token using its advertised scrollDirections. " +
        "Copy one of that node's scrollDirections exactly; orientation alone does not make a direction available. " +
        "Directions navigate the viewport through content, not the finger: a " +
        "leftward swipe may require right or forward. " +
        "At an edge the opposite direction may be absent. Forward/backward follow the widget's axis. " +
        "read the returned observation for new controls/tokens. " +
        "Use a token from the latest observation or ui.find(scrollable=true), not the offscreen button's " +
        "token or clickTargetToken. A later observation invalidates the earlier container token. " +
        "UNSETTLED may include fresh state that is still changing; query again if needed. " +
        "For coordinate/canvas gestures use ui.device, ui.screenshot and ui.gesture instead."

private const val WAIT_DESCRIPTION =
    "Wait read-only for a screen condition: present/absent require a selector; changed/stable " +
        "may observe the whole screen. After an action, prefer present with the expected label/state or " +
        "absent with the dismissed target. Use a selector for stable on dynamic pages; " +
        "changed compares against this wait call's first read, not the pre-action screen. " +
        "Set timeoutMillis (maximum 60000); normally omit " +
        "pollMillis (default 100, range 50..1000). Inspect waitStatus. " +
        "TIMED_OUT does not prove the previous action failed. A unique intended match provides " +
        "suggestedClickToken for ui.click. Never repeat unchanged waits indefinitely."

private const val CLICK_MATCH_DESCRIPTION =
    "Prefer this tool for finding and clicking a known label or viewId in one call; " +
        "no intermediate token is needed. " +
        "Use observed selectors, preferably packageName and a unique viewId or exact text. " +
        "If the latest observation already has suggestedClickToken, ui.click avoids another read. " +
        "Optional timeoutMillis waits up to 60000; normally omit pollMillis (default " +
        "100, range 50..1000). " +
        "The runtime selects the available backend; do not supply a backend argument. " +
        "An authorized system Install button is within task scope; " +
        "Android may still require manual confirmation. " +
        "Zero or ambiguous targets do not click; unknown outcomes must not be replayed."

private const val CLICK_DESCRIPTION =
    "Click the exact token from the latest ui.snapshot/find/wait or returned post-action observation. " +
        "Do not insert another observation between finding and clicking: it replaces old tokens. " +
        "Use clickTargetToken for a non-clickable child. For a known selector prefer ui.click_match. " +
        "DISPATCHED does not verify touch acceptance; observe the resulting screen."
