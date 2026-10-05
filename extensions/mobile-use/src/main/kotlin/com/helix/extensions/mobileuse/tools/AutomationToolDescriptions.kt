package com.helix.extensions.mobileuse.tools

/** Task-specific tool guidance; execution and permission remain in AutomationTools. */
internal object AutomationToolDescriptions {
    fun description(name: String): String =
        when (name) {
            AutomationTools.SNAPSHOT -> {
                "Observe the current authorized Android screen without acting. Returns fresh node tokens, " +
                    "labels, bounds and state; omitted flags mean false. Check truncated/recoveryHint before " +
                    "concluding a control is absent. Observation chooses ready Root, then Shizuku, then Accessibility."
            }

            AutomationTools.FIND -> {
                "Find controls in a fresh screen observation using observed labels or attributes. " +
                    "Default text matching is exact; use match=contains for a substring. " +
                    "A unique intended match supplies suggestedClickToken for ui.click. Does not click or wait."
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
                    "An authorized system Install button needs no separate human click. " +
                    "Zero or ambiguous targets do not click; unknown outcomes must not be replayed."
            }

            AutomationTools.CLICK -> {
                "Click a fresh observed token; use clickTargetToken for a non-clickable child. " +
                    "Verify the resulting screen."
            }

            AutomationTools.LONG_CLICK -> {
                "Long-press a fresh observed node token to open its contextual actions; observe the resulting screen."
            }

            AutomationTools.SET_TEXT -> {
                "Replace text in a fresh editable node token. Set submit=true only when canImeEnter=true " +
                    "to also submit through the keyboard action. Observe the result before the next action."
            }

            AutomationTools.IME_ENTER -> {
                "Submit the keyboard action for a fresh node token with canImeEnter=true; does not insert text."
            }

            AutomationTools.SET_PROGRESS -> {
                "Set a fresh node token's numeric range value; use the observed range and canSetProgress capability."
            }

            AutomationTools.SCROLL -> {
                "Scroll a fresh scrollable node token forward or backward, then observe newly visible controls. " +
                    "For coordinate/canvas gestures use ui.device, ui.screenshot and ui.gesture instead."
            }

            AutomationTools.BACK -> {
                "Navigate back once in the authorized Android session using Accessibility. " +
                    "Observe where navigation ends."
            }

            AutomationTools.HOME -> {
                "Go to Android Home using Accessibility within the authorized session. " +
                    "This does not finish the user's task."
            }

            else -> {
                error("Unknown automation tool: $name")
            }
        }
}
