package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.R
import com.helix.app.account.LocalUserProfile
import com.helix.app.account.LocalUserProfilePersistence
import com.helix.app.account.LocalUserProfileStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

/** Isolated fixtures: never touches the installed user's profile, login, or preferences. */
class LocalProfileEditorDeviceTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val temporary = TemporaryFolder()

    @Test fun savePersistsNameAndDismissesEditor() {
        val fixture = editor()
        compose.onNodeWithTag("profile-name").performTextReplacement("  Local name  ")
        compose.onNodeWithTag("profile-save").performScrollTo().performClick()
        compose.waitUntil(5_000) { fixture.dismissed.get() == 1 }
        assertEquals("Local name", fixture.persistence.read().displayName)
        val reopened = LocalUserProfileStore(fixture.persistence) { error("No avatar requested") }
        runBlocking { reopened.restore() }
        assertEquals("Local name", reopened.state.value.displayName)
    }

    @Test fun cancelDiscardsDraftWithoutChangingPersistedName() {
        val fixture = editor()
        compose.onNodeWithTag("profile-name").performTextReplacement("Discard this")
        compose.onNodeWithTag("profile-editor-back").performClick()
        compose.runOnIdle { assertEquals(1, fixture.dismissed.get()) }
        assertEquals("Before", fixture.store.state.value.displayName)
        assertEquals("Before", fixture.persistence.read().displayName)
    }

    @Test fun emptyNameRestoresDefault() {
        val fixture = editor()
        compose.onNodeWithTag("profile-name").performTextReplacement("")
        compose
            .onNodeWithTag("profile-save")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.waitUntil(5_000) { fixture.dismissed.get() == 1 }
        assertNull(fixture.persistence.read().displayName)
        assertNull(fixture.store.state.value.displayName)
    }

    @Test fun overlongNameDisablesSaveAndLeavesStoredProfileUntouched() {
        val fixture = editor()
        compose.onNodeWithTag("profile-name").performTextReplacement("x".repeat(41))
        compose.onNodeWithTag("profile-save").performScrollTo().assertIsNotEnabled()
        assertEquals("Before", fixture.persistence.read().displayName)
        assertEquals(0, fixture.dismissed.get())
        compose.onNodeWithTag("profile-name").performScrollTo().performTextReplacement("x".repeat(40))
        compose.onNodeWithTag("profile-save").performScrollTo().assertIsEnabled()
    }

    @Test fun headerDisplaysLocalNameAndEditAction() {
        val edited = AtomicInteger()
        compose.setContent {
            MaterialTheme { UserProfileHeader(LocalUserProfile("Local name")) { edited.incrementAndGet() } }
        }
        compose.onNodeWithTag("profile-display-name", useUnmergedTree = true).assertTextEquals("Local name")
        compose.onNodeWithTag("profile-edit").performClick()
        assertEquals(1, edited.get())
    }

    @Test fun headerUsesLocalizedDefaultWhenNameIsAbsent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MaterialTheme { UserProfileHeader(LocalUserProfile()) {} } }
        compose
            .onNodeWithTag("profile-display-name", useUnmergedTree = true)
            .assertTextEquals(context.getString(R.string.user_profile_default_name))
    }

    private fun editor(): Fixture {
        val persistence = LocalUserProfilePersistence(temporary.newFolder())
        val store = LocalUserProfileStore(persistence) { error("No avatar requested") }
        runBlocking { store.saveName("Before") }
        val dismissed = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                LocalProfileEditor(store.state.value, store) { dismissed.incrementAndGet() }
            }
        }
        return Fixture(store, persistence, dismissed)
    }

    private data class Fixture(
        val store: LocalUserProfileStore,
        val persistence: LocalUserProfilePersistence,
        val dismissed: AtomicInteger,
    )
}
