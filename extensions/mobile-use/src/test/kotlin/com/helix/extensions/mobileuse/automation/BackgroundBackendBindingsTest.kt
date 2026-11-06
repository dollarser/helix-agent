package com.helix.extensions.mobileuse.automation

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BackgroundBackendBindingsTest {
    @Test fun rootIsPreferredButExistingDisplaysNeverSwitchProvidersOnDisconnect() {
        val root = Backend()
        val shell = Backend()
        val bindings = BackgroundBackendBindings(root, shell)
        assertSame(root, bindings.forKey("session"))
        root.ready = false
        assertSame(root, bindings.forKey("session"))
        assertSame(shell, bindings.forKey("other"))
        bindings.forget("session")
        assertSame(shell, bindings.forKey("session"))
    }

    @Test fun noProviderDoesNotCreateOrPinAConnection() {
        val root = Backend().apply { ready = false }
        val bindings = BackgroundBackendBindings(root, null)
        assertNull(bindings.forKey("session"))
        root.ready = true
        assertSame(root, bindings.forKey("session"))
    }

    @Test fun replacingAdbDoesNotRetargetAnExistingDisplay() {
        val old = Backend()
        var adb: AutomationPrivilegedBackend? = old
        val shell = Backend()
        val bindings = BackgroundBackendBindings(null, shell) { adb }
        assertSame(old, bindings.forKey("session"))
        old.ready = false
        val replacement = Backend()
        adb = replacement
        assertSame(old, bindings.forKey("session"))
        assertSame(replacement, bindings.forKey("new-session"))
        adb = null
        assertSame(old, bindings.forKey("session"))
        assertSame(shell, bindings.forKey("other-session"))
    }

    private class Backend : AutomationPrivilegedBackend {
        var ready = true

        override fun state() = if (ready) AutomationBackendState.READY else AutomationBackendState.LOST

        override fun click(
            selector: AutomationPrivilegedSelector,
            allowed: (Int, Int, Int) -> Boolean,
            mayFinish: () -> Boolean,
        ): AutomationActionResult = error("Selection must not create a connection or inject input")
    }
}
