package com.helix.extensions.mobileuse.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPermissionControlsTest {
    private val controller = "com.android.permissioncontroller"

    @Test fun onlyPublicNonSecretControlsFromPlatformIdentityAreExempt() {
        val id = "$controller:id/permission_deny_button"
        assertTrue(publicPermissionControl(controller, controller, id, false, false))
        assertFalse(publicPermissionControl(null, controller, id, false, false))
        assertFalse(publicPermissionControl(controller, "com.fake.controller", id, false, false))
        assertFalse(publicPermissionControl(controller, controller, "permission_deny_button", false, false))
        assertFalse(publicPermissionControl(controller, controller, "$controller:id/secret", false, false))
        assertFalse(publicPermissionControl(controller, controller, id, true, false))
        assertFalse(publicPermissionControl(controller, controller, id, false, true))
    }

    @Test fun googleControllerKeepsAospResourceNamespace() {
        val google = "com.google.android.permissioncontroller"
        val id = "$controller:id/permission_allow_button"
        assertTrue(publicPermissionControl(google, google, id, false, false))
        assertFalse(publicPermissionControl(google, "com.fake.controller", id, false, false))
        assertFalse(
            publicPermissionControl(google, google, "com.fake.controller:id/permission_allow_button", false, false),
        )
    }

    @Test fun installerControlsRequireTrustedIdentityAndNonSecretButton() {
        val installer = "com.google.android.packageinstaller"

        fun allowed(
            trusted: String? = installer,
            pkg: String = installer,
            id: String = "android:id/button1",
            password: Boolean = false,
            editable: Boolean = false,
            type: String = "android.widget.Button",
        ) = publicInstallerControl(trusted, pkg, id, password, editable, type)
        assertTrue(allowed())
        assertTrue(allowed(id = "com.android.packageinstaller:id/button1"))
        assertTrue(allowed(id = "$installer:id/button2"))
        assertFalse(allowed(trusted = null))
        assertFalse(allowed(pkg = "com.fake.installer"))
        assertFalse(allowed(id = "$installer:id/secret"))
        assertFalse(allowed(password = true))
        assertFalse(allowed(editable = true))
        assertFalse(allowed(type = "android.widget.EditText"))
    }

    @Test fun missingPackageRequiresFullVisibilityForAbsenceClaim() {
        org.junit.Assert.assertEquals("NOT_INSTALLED", missingApplicationStatus(true))
        org.junit.Assert.assertEquals("UNKNOWN", missingApplicationStatus(false))
    }
}
