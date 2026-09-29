package com.helix.tools.files

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.VisualArtifact
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolVisualPreparation
import com.helix.tools.framework.VisualPreparationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ViewImageToolTest {
    private fun call(path: String = "scope:ws:chart.png") =
        ExecutableToolCall(
            "call",
            "view_image",
            "1",
            JsonObject(mapOf("path" to JsonPrimitive(path))),
            ExecutionTargetType.LOCAL_ANDROID,
            Instant.now().plusSeconds(30),
            NoCancellation,
            "s",
            "t",
        )

    private val image = VisualArtifact("artifact", "a".repeat(64), "image/png", 128, 16, 16)

    @Test fun returnsTypedPixelsReferenceNotBase64Text() {
        var received: String? = null
        val result =
            ViewImageTool
                .executor(
                    ToolVisualPreparation {
                        _,
                        path,
                        _,
                        ->
                        received = path
                        image
                    },
                ).execute(call())
        assertTrue(result is ToolExecutorResult.Completed)
        result as ToolExecutorResult.Completed
        assertEquals(image, result.visualArtifact)
        assertEquals("scope:ws:chart.png", received)
        assertFalse(result.output.toString().contains("base64"))
    }

    @Test fun invalidPathNeverCallsImageLoader() {
        var calls = 0
        val executor =
            ViewImageTool.executor(
                ToolVisualPreparation { _, _, _ ->
                    calls++
                    image
                },
            )
        assertTrue(executor.execute(call("/sdcard/private.png")) is ToolExecutorResult.Failed)
        assertTrue(executor.execute(call("scope:ws:../private.png")) is ToolExecutorResult.Failed)
        assertEquals(0, calls)
    }

    @Test fun unconfirmedVisionAndBadImagesAreExplicitFailures() {
        for (reason in listOf("VISION_UNAVAILABLE", "INPUT_TOO_LARGE", "DECODE_FAILED", "SCOPE_UNAVAILABLE")) {
            val result =
                ViewImageTool
                    .executor(
                        ToolVisualPreparation {
                            _,
                            _,
                            _,
                            ->
                            throw VisualPreparationException(reason)
                        },
                    ).execute(call())
            assertTrue(result is ToolExecutorResult.Failed)
            assertTrue((result as ToolExecutorResult.Failed).detail.contains(reason))
        }
    }

    @Test fun cancelledCallDoesNotReadPixels() {
        val executor = ViewImageTool.executor(ToolVisualPreparation { _, _, _ -> error("must not read") })
        assertEquals(
            ToolExecutorResult.Cancelled,
            executor.execute(
                call().copy(
                    cancel =
                        object : com.helix.tools.framework.CancelSignal {
                            override fun isCancelled() = true
                        },
                ),
            ),
        )
    }
}
