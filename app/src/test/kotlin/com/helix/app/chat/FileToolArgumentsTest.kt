package com.helix.app.chat

import com.helix.core.workspace.FileScopePath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FileToolArgumentsTest {
    @Test fun imageToolUsesTheSameFrozenDirectoryAndModelPathHelp() {
        val descriptor =
            com.helix.tools.files.ViewImageTool
                .descriptor()
        org.junit.Assert.assertTrue(FileToolArguments.handles(descriptor))
        val schema = FileToolArguments.modelSchema(descriptor)
        org.junit.Assert.assertTrue(schema.inputSchemaJson.contains("current session working directory"))
        val resolved =
            Json
                .parseToJsonElement(
                    FileToolArguments.bindRequest("""{"path":"chart.png"}""", FileScopePath("ws", "project")),
                ).jsonObject
        assertEquals("scope:ws:project/chart.png", resolved.getValue("path").jsonPrimitive.content)
        assertEquals(resolved, FileToolArguments.normalize(resolved, FileScopePath("elsewhere", "other")))
    }

    @Test fun inFlightResponseAndStoredHistoryKeepTheRequestDirectory() {
        val firstRequest = FileToolArguments.directory("app", "scope:app:first")
        val nextRequest = FileToolArguments.directory("app", "scope:app:second")
        val arguments = """{"path":"answer.txt","content":"hello"}"""
        // The response arrives after the session has switched, but carries the old request base.
        val oldResponse = Json.parseToJsonElement(FileToolArguments.bindRequest(arguments, firstRequest)).jsonObject
        assertEquals("scope:app:first/answer.txt", oldResponse["path"]!!.jsonPrimitive.content)
        // Dispatch and provider-history replay cannot reinterpret already bound arguments.
        assertEquals(oldResponse, FileToolArguments.normalize(oldResponse, nextRequest))
        val newResponse = Json.parseToJsonElement(FileToolArguments.bindRequest(arguments, nextRequest)).jsonObject
        assertEquals("scope:app:second/answer.txt", newResponse["path"]!!.jsonPrimitive.content)
    }

    @Test fun jobInputsAndOutputFreezeButRuntimeCwdAndScriptStayUnchanged() {
        val args =
            Json
                .parseToJsonElement(
                    """{"files":["a.txt"],"output":"output/result.txt","cwd":"job","script":"cat a.txt"}""",
                ).jsonObject
        val frozen = FileToolArguments.normalize(args, FileScopePath("first", "project"))
        assertEquals("scope:first:project/output/result.txt", frozen["output"]!!.jsonPrimitive.content)
        assertEquals(args["cwd"], frozen["cwd"])
        assertEquals(args["script"], frozen["script"])
        assertEquals(frozen, FileToolArguments.normalize(frozen, FileScopePath("second", "")))
        assertEquals(
            "scope:first:project/a.txt",
            (frozen["files"] as kotlinx.serialization.json.JsonArray).single().jsonPrimitive.content,
        )
    }

    @Test fun requestBindingPreservesInvalidArgumentsForOrdinaryRejection() {
        val directory = FileScopePath("app", "old")
        for (arguments in listOf("not json", "[]", """{"path":"../escape"}""", """{"path":"/absolute"}""")) {
            assertEquals(arguments, FileToolArguments.bindRequest(arguments, directory))
        }
    }

    @Test fun toolSchemaExplainsRelativePathsWithoutChangingRequiredFields() {
        val descriptor =
            com.helix.tools.files.WriteTool
                .descriptor()
        val exposed = FileToolArguments.modelSchema(descriptor)
        val schema = Json.parseToJsonElement(exposed.inputSchemaJson).jsonObject
        assertEquals(descriptor.inputSchema["required"], schema["required"])
        val pathHelp =
            schema
                .getValue("properties")
                .jsonObject
                .getValue("path")
                .jsonObject
                .getValue("description")
                .jsonPrimitive.content
        org.junit.Assert.assertTrue(pathHelp.contains("current session working directory"))
        org.junit.Assert.assertTrue(pathHelp.contains(".helix internals are not writable"))
    }

    @Test fun relativeAndExplicitPathsUseOneResolverWithoutChangingContent() {
        val args = Json.parseToJsonElement("""{"path":"cnn.py","content":"../keep this text"}""").jsonObject
        val result = FileToolArguments.normalize(args, FileScopePath("app", "work/project"))
        assertEquals("scope:app:work/project/cnn.py", result["path"]!!.jsonPrimitive.content)
        assertEquals(args["content"], result["content"])
        val explicit = Json.parseToJsonElement("""{"path":"scope:other:output/a"}""").jsonObject
        assertEquals(explicit, FileToolArguments.normalize(explicit, FileScopePath("app", "work")))
    }

    @Test fun traversalAndAbsolutePathsAreRejectedBeforeJoining() {
        for (path in listOf("../x", "/sdcard/x", "a/../../x")) {
            val args =
                kotlinx.serialization.json.buildJsonObject {
                    put("path", kotlinx.serialization.json.JsonPrimitive(path))
                }
            assertThrows(IllegalArgumentException::class.java) {
                FileToolArguments.normalize(args, FileScopePath("app", "work/project"))
            }
        }
    }

    @Test fun copyBothPathsResolveAndListDotUsesSelectedDirectory() {
        val args = Json.parseToJsonElement("""{"source":"a","destination":"b","path":"."}""").jsonObject
        val result = FileToolArguments.normalize(args, FileScopePath("app", ""))
        assertEquals("scope:app:a", result["source"]!!.jsonPrimitive.content)
        assertEquals("scope:app:b", result["destination"]!!.jsonPrimitive.content)
        assertEquals("scope:app:.", result["path"]!!.jsonPrimitive.content)
    }

    // The session prompt (SystemPromptContext) and the file tools resolve the working directory
    // through this one helper, so a `scope:` directoryRef must land exactly where the model put
    // it for the three real requests — never re-wrapped under the default scope (the previous
    // prompt bug rendered scope:app:scope:app:work for a selected subdirectory).
    @Test fun workingDirectoryResolutionCoversTheThreeRealRequests() {
        // Default root: no directoryRef → the default scope root.
        assertEquals("scope:app:.", FileToolArguments.directory("app", null).toModelReference())
        // Selected subdirectory: a scope: ref under the default scope resolves to itself.
        val sub = FileToolArguments.directory("app", "scope:app:work/project")
        assertEquals("app", sub.scopeId)
        assertEquals("scope:app:work/project", sub.toModelReference())
        // Other authorized root: a different scope is preserved, not forced under the default.
        val other = FileToolArguments.directory("app", "scope:other:output")
        assertEquals("other", other.scopeId)
        assertEquals("scope:other:output", other.toModelReference())
    }
}
