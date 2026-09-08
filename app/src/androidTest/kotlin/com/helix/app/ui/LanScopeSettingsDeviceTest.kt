package com.helix.app.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.helix.app.internal.PrefsLineStore
import com.helix.app.network.LanScopeStore
import com.helix.core.policy.NetworkOriginScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LanScopeSettingsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun userCanPersistAndRevokeOneExactHostPort() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefsName = "lan-ui-${System.nanoTime()}"
        val backend = PrefsLineStore(context, prefsName, synchronous = true)
        val store = LanScopeStore(backend) { true }
        try {
            compose.setContent { MaterialTheme { LanScopeSettingsSection(store) } }
            compose.onNodeWithTag("settings-lan-origin").performTextInput("http://127.0.0.1:31415")
            compose.onNodeWithTag("settings-lan-add").performClick()
            compose.waitUntil(5000) { store.origins.value.isNotEmpty() }
            assertEquals(setOf(NetworkOriginScope("127.0.0.1", 31415)), LanScopeStore(backend) { true }.current())
            compose.waitUntil(5000) {
                !compose
                    .onNodeWithTag("settings-lan-add")
                    .fetchSemanticsNode()
                    .config
                    .contains(SemanticsProperties.Disabled)
            }
            compose.onNodeWithTag("settings-lan-remove").performClick()
            compose.waitUntil(5000) {
                store.origins.value.isEmpty() &&
                    !compose
                        .onNodeWithTag("settings-lan-add")
                        .fetchSemanticsNode()
                        .config
                        .contains(SemanticsProperties.Disabled)
            }
            assertTrue(LanScopeStore(backend) { true }.current().isEmpty())
        } finally {
            check(
                context
                    .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit(),
            )
        }
    }
}
