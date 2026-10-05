package com.helix.extensions.mobileuse.automation

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationApplicationCatalogTest {
    private val user = AutomationApplication("com.example.Chat", "聊天工具", false, true, true)
    private val system = AutomationApplication("com.android.systemui", "System UI", true, false, true)
    private val disabled = AutomationApplication("com.vendor.service", "Vendor Service", true, false, false)
    private val catalog = listOf(system, user, disabled)

    @Test fun updatedSystemAppsStayInTheSystemGroup() {
        assertTrue(AutomationApplicationCatalog.isSystemApplication(ApplicationInfo.FLAG_SYSTEM))
        assertTrue(AutomationApplicationCatalog.isSystemApplication(ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
        assertFalse(AutomationApplicationCatalog.isSystemApplication(ApplicationInfo.FLAG_INSTALLED))
    }

    @Test fun applicationsWithoutLaunchersAndDisabledSystemComponentsAreNotHidden() {
        val visible = visible(filter = AutomationApplicationFilter.SYSTEM)
        assertEquals(setOf(system, disabled), visible.toSet())
        assertEquals(listOf(user), visible(filter = AutomationApplicationFilter.USER))
    }

    @Test fun searchMatchesLabelsAndPackagesWithoutChangingCaseSensitiveIdentity() {
        assertEquals(listOf(user), visible(query = "聊天"))
        assertEquals(listOf(user), visible(query = "COM.EXAMPLE.CHAT"))
        assertEquals(listOf(system), visible(query = "system UI"))
        assertTrue(visible(query = "no match").isEmpty())
        assertEquals("com.example.Chat", user.packageName)
    }

    @Test fun filtersAndSearchPreserveSelectionOutsideTheVisibleList() {
        val selected = AutomationApplicationChoices.toggle(setOf(user.packageName), system)
        assertEquals(listOf(system), visible(selected, "system", AutomationApplicationFilter.SELECTED))
        assertEquals(setOf(user.packageName, system.packageName), selected)
        assertEquals(setOf(user.packageName), AutomationApplicationChoices.toggle(selected, system))
    }

    @Test fun refreshKeepsUnavailableSelectionsVisibleUntilTheUserRemovesThem() {
        val selected = setOf("com.removed.app", user.packageName)
        val rows = visible(selected, filter = AutomationApplicationFilter.SELECTED)
        val missing = rows.single { !it.available }
        assertEquals("com.removed.app", missing.packageName)
        assertEquals(setOf(user.packageName), AutomationApplicationChoices.toggle(selected, missing))
        assertTrue(AutomationApplicationChoices.toggle(emptySet(), missing).isEmpty())
    }

    @Test fun recoveryCanSelectOnlyAnAlreadyAuthorizedApplication() {
        val allowed = setOf(user.packageName)
        val rows =
            AutomationApplicationChoices.visible(
                catalog,
                emptySet(),
                "",
                AutomationApplicationFilter.ALL,
                allowed,
            )
        assertEquals(listOf(user), rows)
        assertEquals(
            setOf(user.packageName),
            AutomationApplicationChoices.toggle(setOf(system.packageName), user, true),
        )
    }

    @Test fun installedPackageIdentityIsUniqueAndNoPackagesAreImplicitlyAdded() {
        val rows =
            AutomationApplicationChoices.visible(
                catalog + user,
                emptySet(),
                "",
                AutomationApplicationFilter.ALL,
            )
        assertEquals(3, rows.size)
        assertEquals(setOf(system.packageName), AutomationApplicationChoices.toggle(emptySet(), system))
        assertEquals(emptyList<AutomationApplication>(), visible(filter = AutomationApplicationFilter.SELECTED))
    }

    private fun visible(
        selected: Set<String> = emptySet(),
        query: String = "",
        filter: AutomationApplicationFilter = AutomationApplicationFilter.ALL,
    ) = AutomationApplicationChoices.visible(catalog, selected, query, filter)
}
