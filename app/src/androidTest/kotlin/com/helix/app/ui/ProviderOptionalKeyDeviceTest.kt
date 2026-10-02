package com.helix.app.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.helix.app.MainActivity
import com.helix.app.provider.ComposeOutcome
import com.helix.app.provider.ProviderComposer
import com.helix.core.model.ProviderAuth
import com.helix.provider.catalog.ProviderTemplateCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProviderOptionalKeyDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun optionalKeyCanBeAddedPreservedAndReplacedWithoutLosingModelChoices() =
        runBlocking {
            val container = compose.container()
            val service = container.providerService
            val draft =
                (
                    ProviderComposer.compose(
                        ProviderTemplateCatalog.sglang,
                        "Optional credential fixture",
                        "https://example.test/v1",
                        "model-a",
                        emptyMap(),
                    ) as ComposeOutcome.Ok
                ).draft
            val id = service.create(draft, null)
            try {
                assertEquals(ProviderAuth.None, service.storedConfig(id).auth)
                service.update(id, draft, "fixture-first")
                val alias = (service.storedConfig(id).auth as ProviderAuth.Secret).alias
                assertEquals("fixture-first", container.storage.secrets.get(alias))
                service.saveSelectedModels(id, listOf("model-a", "model-b"))
                service.update(id, draft.copy(displayName = "Renamed"), null)
                assertEquals(alias, (service.storedConfig(id).auth as ProviderAuth.Secret).alias)
                assertEquals("fixture-first", container.storage.secrets.get(alias))
                service.update(id, draft, "fixture-second")
                assertEquals("fixture-second", container.storage.secrets.get(alias))
                assertTrue(!service.chatSelectable(id))
            } finally {
                service.delete(id)
            }
        }
}
