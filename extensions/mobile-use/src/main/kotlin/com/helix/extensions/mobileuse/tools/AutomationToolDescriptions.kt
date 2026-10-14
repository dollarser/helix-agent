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
                "Wait read-only for a screen condition: present/absent require a selector; changed/stable " +
                    "may observe the whole screen. Set timeoutMillis (maximum 60000); inspect waitStatus. " +
                    "TIMED_OUT does not prove the previous action failed. A unique intended match provides " +
                    "suggestedClickToken for ui.click. Never repeat unchanged waits indefinitely."
            }

            AutomationTools.CLICK_MATCH -> {
                "Observe and click one unambiguous target using its observed label or attributes. " +
                    "Prefer ui.click when you already have suggestedClickToken. " +
                    "The runtime selects the available backend; do not supply a backend argument. " +
                    "An authorized system Install button is within task scope; " +
                    "Android may still require manual confirmation. " +
                    "Zero or ambiguous targets do not click; unknown outcomes must not be replayed."
            }

            AutomationTools.CLICK -> {
                "Click a fresh observed token; use clickTargetToken for a non-clickable child. " +
                    "DISPATCHED does not verify touch acceptance; observe the resulting screen."
            }

            AutomationTools.LONG_CLICK -> {
                "Long-press a fresh observed node token to open its contextual actions; observe the resulting screen."
            }

            AutomationTools.SET_TEXT -> {
                "Replace text directly in a fresh editable node token; " +
                    "no preliminary click or keyboard opening is needed. " +
                    "Omit submit for ordinary filling (default false); text insertion does not need IME submission. " +
                    "Set submit=true only when canImeEnter=true " +
                    "and the task requests a keyboard action. Read the returned observation for fresh tokens; " +
                    "request another observation only if it is unavailable, incomplete or still loading."
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
        "This observation replaces earlier tokens. For scrolling choose a node with scrollable=true. " +
        "Offscreen nodes require scrolling before clicking. " +
        "Check truncated/recoveryHint before " +
        "concluding a control is absent. Observation chooses ready Root, then Shizuku, then Accessibility."

private const val FIND_DESCRIPTION =
    "Find controls in a fresh screen observation using observed labels or attributes. " +
        "Default text matching is exact; use match=contains for a substring. " +
        "An actionable unique match supplies suggestedClickToken for ui.click. " +
        "This observation replaces all earlier node tokens. Offscreen matches are not clickable: " +
        "find a fresh container with scrollable=true, scroll its token and read the returned observation. " +
        "Does not click or wait."

private const val SCROLL_DESCRIPTION =
    "Scroll a fresh scrollable node token forward or backward; " +
        "read the returned observation for new controls/tokens. " +
        "Use a token from the latest observation or ui.find(scrollable=true), not the offscreen button's " +
        "token or clickTargetToken. A later observation invalidates the earlier container token. " +
        "Query again if the attached observation is unavailable or incomplete. " +
        "For coordinate/canvas gestures use ui.device, ui.screenshot and ui.gesture instead."
