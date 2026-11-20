package com.helix.app.eval

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.provider.BuiltInModel
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ReasoningEffort
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Owner-opted real schema admission and argument generation. Never executes returned calls. */
class GestureSchemaAdmissionDeviceTest {
    @Test
    fun configuredModelAcceptsGestureSchemaThroughBothProtocols() =
        runBlocking<Unit>(Dispatchers.IO) {
            assumeTrue(InstrumentationRegistry.getArguments().getString("helixSchemaAdmission") == "true")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            app.runtimeRecoveryReady.await()
            val c = app.appContainer
            val descriptors =
                c.toolPipeline.registry
                    .snapshot()
                    .map { it.descriptor }
                    .filter { it.name.value.startsWith("ui_") }
            val gesture = descriptors.single { it.name.value == "ui_gesture" }
            val schemas = descriptors.map { ModelToolSchema(it.name, it.description, it.inputSchema.toString()) }
            val provider = c.providerService.modelProviderFor(BuiltInModel.PROVIDER_ID, BuiltInModel.MODEL_ID)
            val prompt =
                "Synthetic schema test. Call ui_gesture exactly once with frame=test_frame, " +
                    "pattern kind=tap and center x=100,y=100. Do not request a screenshot. " +
                    "The host will inspect arguments only and will NOT execute the call."
            // Built-in Luna routes NONE to Chat Completions and MEDIUM to Responses.
            for (effort in listOf("none", "medium")) {
                val events =
                    withTimeout(90_000) {
                        provider
                            .stream(
                                ModelRequest(
                                    BuiltInModel.MODEL_ID,
                                    listOf(ModelMessage(ModelRole.USER, prompt)),
                                    tools = schemas,
                                    maxOutputTokens = 1024,
                                    reasoning = ReasoningEffort.fromWire(effort),
                                ),
                            ).toList()
                    }
                val errors = events.filterIsInstance<ModelEvent.Error>()
                val completed = events.any { it is ModelEvent.Completed }
                println("SCHEMA_ADMISSION effort=$effort completed=$completed errors=$errors")
                assertTrue("$effort schema admission failed: $errors", errors.isEmpty())
                assertTrue("$effort stream incomplete", events.any { it is ModelEvent.Completed })
                val call = events.filterIsInstance<ModelEvent.ToolCallStarted>().single()
                assertEquals("ui_gesture", call.name)
                val arguments =
                    events
                        .filterIsInstance<ModelEvent.ToolArgumentsDelta>()
                        .filter { it.index == call.index }
                        .joinToString("") { it.jsonFragment }
                assertEquals(
                    ToolSchemaValidation.Valid,
                    ToolSchemaValidator.validate(gesture.inputSchema, Json.parseToJsonElement(arguments)),
                )
            }
        }
}
