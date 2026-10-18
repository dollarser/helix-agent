package com.helix.app.proot

import com.helix.runtime.proot.ipc.ProotJobCommand
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxExecutionInfoTest {
    private fun call(
        files: List<String>,
        cwd: String = "",
        output: String? = null,
    ) = LinuxRunTool.ParsedLinuxCall(
        "call",
        "turn",
        "session",
        ProotJobCommand.Script("true"),
        cwd,
        emptyMap(),
        files,
        output,
        1000,
    )

    @Test fun reportsActualSnapshotNamesCwdAndExplicitImportWithoutChangingSources() {
        val files = listOf("scope:ws:src/main.txt", "scope:other:src/main.txt")
        val info = linuxExecutionInfo(call(files, "build", "scope:ws:output/result.txt"), "job")
        assertEquals("snapshot", info["mode"]?.jsonPrimitive?.content)
        assertEquals("local-proot", info["environment"]?.jsonPrimitive?.content)
        assertEquals("/workspace/build", info["cwd"]?.jsonPrimitive?.content)
        val mappings = info.getValue("inputMapping").jsonArray
        assertEquals("/workspace/main.txt", mappings[0].jsonObject["guestPath"]?.jsonPrimitive?.content)
        assertEquals("/workspace/main.txt-2", mappings[1].jsonObject["guestPath"]?.jsonPrimitive?.content)
        assertEquals(files[1], mappings[1].jsonObject["source"]?.jsonPrimitive?.content)
        assertEquals("false", info["automaticSourceWriteback"]?.jsonPrimitive?.content)
        assertEquals("scope:ws:output/result.txt", info["outputImportTarget"]?.jsonPrimitive?.content)
        assertEquals("false", info["inputMappingTruncated"]?.jsonPrimitive?.content)
    }

    @Test fun absentImportDoesNotInventDestinationAndLargeMappingIsExplicitlyBounded() {
        val info = linuxExecutionInfo(call(List(64) { "scope:ws:${"中".repeat(100)}$it.txt" }), "job")
        assertFalse(info.containsKey("outputImportTarget"))
        assertEquals("64", info["inputCount"]?.jsonPrimitive?.content)
        assertEquals("true", info["inputMappingTruncated"]?.jsonPrimitive?.content)
        assertEquals("/workspace", info["cwd"]?.jsonPrimitive?.content)
        assertTrue(info.toString().toByteArray(Charsets.UTF_8).size < 4096)
    }
}
