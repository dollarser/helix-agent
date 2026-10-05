package com.helix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsNavigationTest {
    @Test fun projectDetailKeepsProjectEntrySelectedWithoutMatchingUnrelatedRoutes() {
        val entry = DrawerEntry("projects", com.helix.app.R.string.nav_projects)
        assertTrue(entry.matches("projects"))
        assertTrue(entry.matches("project/project-123"))
        assertFalse(entry.matches("project-other"))
        assertFalse(entry.matches("sessions"))
    }

    @Test fun workUtilitiesAreNotDuplicatedInSettings() {
        assertEquals(listOf(SETTINGS_STORAGE_ROUTE, SETTINGS_AUDIT_ROUTE), workUtilityDrawerEntries.map { it.route })
        assertTrue(settingsDrawerEntries.none { entry -> workUtilityDrawerEntries.any { it.route == entry.route } })
    }

    @Test fun eachSettingsPageHasExactlyOneDrawerEntry() {
        val routes = settingsDrawerEntries.map { it.route }
        assertEquals(routes.size, routes.distinct().size)
        assertEquals(
            listOf(
                "settings",
                "settings/defaults",
                "settings/permissions",
                "setup/runtime",
            ),
            routes,
        )
        routes.forEach { route ->
            assertEquals(listOf(route), settingsDrawerEntries.filter { it.matches(route) }.map { it.route })
        }
    }

    @Test fun nestedSystemPermissionsHighlightsOnlyPermissionsAndNotGeneral() {
        assertEquals(
            listOf(SETTINGS_PERMISSIONS_ROUTE),
            settingsDrawerEntries.filter { it.matches(SETTINGS_SYSTEM_PERMISSIONS_ROUTE) }.map { it.route },
        )
        assertFalse(settingsDrawerEntries.first().matches("settings-other"))
    }

    @Test fun diagnosticsOwnsChecksWhileRuntimeHasItsOwnEntry() {
        listOf("setup", SETUP_READINESS_ROUTE, SETUP_CAPABILITIES_ROUTE).forEach { child ->
            assertEquals(
                listOf(SETTINGS_AUDIT_ROUTE),
                workUtilityDrawerEntries.filter { it.matches(child) }.map { it.route },
            )
        }
        assertEquals(
            listOf(SETUP_RUNTIME_ROUTE),
            settingsDrawerEntries
                .filter {
                    it.matches(SETUP_RUNTIME_ROUTE)
                }.map { it.route },
        )
        assertTrue(settingsDrawerEntries.none { it.matches("setup-other/runtime") })
    }
}
