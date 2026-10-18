package com.helix.app.provider

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.R
import com.helix.app.provider.ui.ProviderRow
import com.helix.app.provider.ui.ProviderRowActions
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.ProviderResidence
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Pure UI fixture: does not register, delete or connect any real provider. */
class BuiltInModelUiDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var requests = 0
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val row =
        ProviderRowUi(
            BuiltInModel.PROVIDER_ID,
            "Helix",
            BuiltInModel.connection.let {
                (it.transport as com.helix.core.model.ProviderTransport.Network).protocol
            },
            BuiltInModel.API_ROOT,
            ProviderResidence.PUBLIC_CLOUD,
            BuiltInModel.MODEL_ID,
            false,
            false,
            ConnectionTestStatus.Untested,
            BuiltInModel.capabilities,
            null,
            emptyList(),
            provisioning = ProviderProvisioningKind.BUNDLED_SERVICE,
        )

    @Test fun untestedServiceHasDisclosureAndNoEditableOrDeleteControls() {
        render(row)
        compose
            .onNodeWithTag("builtin-model-connection")
            .assertTextEquals(context.getString(R.string.builtin_model_unverified))
        compose.onNodeWithTag("provider-edit").assertDoesNotExist()
        compose.onNodeWithTag("provider-delete").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.builtin_model_disclosure)).assertExists()
        assertEquals(0, requests)
        compose.onNodeWithText(context.getString(R.string.provider_connection_test)).performScrollTo().performClick()
        assertEquals(1, requests)
    }

    @Test fun latestRateLimitFailureDoesNotShowOlderPassedConnectionOrKeyAdvice() {
        render(
            row.copy(
                status = ConnectionTestStatus.Passed(1, BuiltInModel.capabilities),
                modelGenerations =
                    mapOf(
                        BuiltInModel.MODEL_ID to ProviderModelVerification(2, failure = ModelErrorCode.RATE_LIMITED),
                    ),
            ),
        )
        compose
            .onNodeWithTag("builtin-model-connection")
            .assertTextEquals(context.getString(R.string.builtin_model_rate_limit))
        compose.onNodeWithText(context.getString(R.string.builtin_model_verified)).assertDoesNotExist()
        assertEquals(0, requests)
    }

    private fun render(value: ProviderRowUi) {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ProviderRow(
                        value,
                        false,
                        ProviderRowActions(
                            { requests++ },
                            { error("Fixed model") },
                            { error("Bundled service") },
                            { error("Declared capability") },
                            { error("No account") },
                        ),
                        false,
                    )
                }
            }
        }
    }
}
