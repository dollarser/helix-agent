package com.helix.app.ui

import com.helix.app.R
import com.helix.app.provider.ui.ProviderForm
import com.helix.app.provider.ui.ProviderFormField
import com.helix.app.provider.ui.SaveResult
import com.helix.app.provider.ui.providerErrorField
import com.helix.app.provider.ui.validateProviderForm
import com.helix.provider.catalog.ProviderTemplateCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderFormValidationTest {
    private val form =
        ProviderForm(
            null,
            ProviderTemplateCatalog.sglang,
            ProviderForm.FormFields("Server", "https://example.test/v1", "model", "", "", ""),
            false,
            null,
        )

    @Test fun missingFieldsIdentifyWhatToFill() {
        assertEquals(
            ProviderFormField.NAME,
            providerErrorField(
                validateProviderForm(form.copy(fields = form.fields.copy(name = "  "))),
            ),
        )
        assertEquals(
            ProviderFormField.ENDPOINT,
            providerErrorField(
                validateProviderForm(form.copy(fields = form.fields.copy(endpoint = ""))),
            ),
        )
        assertEquals(
            ProviderFormField.MODEL,
            providerErrorField(
                validateProviderForm(form.copy(fields = form.fields.copy(model = ""))),
            ),
        )
    }

    @Test fun apiKeyRemainsOptionalWithTypedOrChosenModel() {
        assertNull(validateProviderForm(form))
        assertNull(validateProviderForm(form.copy(fields = form.fields.copy(model = "m"))))
    }

    @Test fun partialHeadersAreNotSilentlyDiscarded() {
        assertEquals(
            ProviderFormField.HEADER_NAME,
            providerErrorField(
                validateProviderForm(form.copy(fields = form.fields.copy(headerValue = "value"))),
            ),
        )
        assertEquals(
            ProviderFormField.HEADER_VALUE,
            providerErrorField(
                validateProviderForm(form.copy(fields = form.fields.copy(headerName = "X-Feature"))),
            ),
        )
    }

    @Test fun protocolErrorsLocateTheControlWhileHttpNeedsNoExtraField() {
        assertEquals(
            ProviderFormField.ENDPOINT,
            providerErrorField(SaveResult.Rejected(R.string.provider_compose_endpoint_invalid)),
        )
        assertNull(validateProviderForm(form.copy(fields = form.fields.copy(endpoint = "http://example.test/v1"))))
        assertNull(providerErrorField(SaveResult.Rejected(R.string.provider_save_failed)))
    }

    @Test fun invalidModelNamesAreNotMisreportedAsStorageFailures() {
        listOf("bad model", "m".repeat(257), "bad\u007fmodel").forEach { model ->
            val error = validateProviderForm(form.copy(fields = form.fields.copy(model = model)))
            assertEquals(R.string.provider_model_selection_invalid, error?.res)
            assertEquals(ProviderFormField.MODEL, providerErrorField(error))
        }
    }
}
