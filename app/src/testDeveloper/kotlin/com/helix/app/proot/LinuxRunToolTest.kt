@file:Suppress("TooManyFunctions") // the scenario list is the test class's purpose

package com.helix.app.proot

import com.helix.app.R
import com.helix.app.approval.ApprovalCardUi
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolOperationClass
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HXA-085 (app, JVM): the `code.linux.run` tool contract — the registered descriptor
 * (L2 CODE_EXECUTION / LOCAL_PROOT / offline), the argument parsing (argv/script
 * exclusivity, caps), the stable parse failures, and the approval-card code section
 * (full script / argv + offline line). The production executor's job flow is
 * device-verified (E2E) because it crosses the APK boundary.
 */
class LinuxRunToolTest {
    // ------------------------------------------------------------------ descriptor

    @Test
    fun descriptorIsL2CodeExecutionOnTheProotLaneOffline() {
        val d = LinuxRunTool.descriptor()
        assertEquals("code.linux.run", d.name.value)
        assertEquals(ToolOperationClass.CODE_EXECUTION, d.operationClass)
        assertEquals(RiskLevel.L2, d.baseRisk)
        assertEquals(ExecutionTargetType.LOCAL_PROOT, d.executionTarget)
        // Generic L2: no auto-approve path can mint approval (ADR-0012).
        assertTrue("L2 must require per-call approval", d.baseRisk.requiresApproval)
        // The model cannot raise the fixed deadline.
        assertTrue(d.timeout.inWholeMilliseconds >= LinuxRunTool.DEFAULT_DEADLINE_SECONDS * 1000)
        // No network switch in the schema (the Runtime has no INTERNET permission;
        // neither Advanced nor a LAN scope can add it).
        val props = d.inputSchema["properties"] as JsonObject
        assertFalse(props.containsKey("network"))
        assertFalse(props.containsKey("internet"))
    }

    // ------------------------------------------------------------------ parsing

    @Test
    fun exactlyOneOfArgvOrScriptIsRequired() {
        assertParseFailure(emptyArgs(), "exactly one of")
        assertParseFailure(
            args {
                put("argv", jsonArrayOf("sh", "-c", "echo hi"))
                put("script", "echo hi")
            },
            "mutually exclusive",
        )
        assertParseFailure(
            args { put("argv", jsonArrayOf()) },
            "non-blank",
        )
        assertParseFailure(
            args { put("argv", jsonArrayOf("sh", "  ")) },
            "non-blank",
        )
        assertParseFailure(
            args { put("script", "   ") },
            "must not be blank",
        )
    }

    @Test
    fun argvAndScriptParseIntoTheExecutionView() {
        val argvCall = parsedOk(args { put("argv", jsonArrayOf("git", "log", "-1")) })
        assertEquals(
            com.helix.runtime.proot.ipc.ProotJobCommand
                .Argv(listOf("git", "log", "-1")),
            argvCall.command,
        )
        // The deadline is bound to the CALL deadline (never past it) — here the call
        // deadline (+120 s) is later than the default 60 s cap, so the cap wins.
        assertTrue(
            argvCall.deadlineEpochMs in
                (System.currentTimeMillis() - 5_000L)..(System.currentTimeMillis() + 60_000L + 5_000L),
        )

        val scriptCall =
            parsedOk(
                args {
                    put("script", "ls -la && pwd")
                    put("cwd", "src")
                    put("timeoutSeconds", JsonPrimitive(15))
                    put("output", JsonPrimitive("scope:app:output/result.txt"))
                },
            )
        val s = scriptCall
        assertTrue(s.command is com.helix.runtime.proot.ipc.ProotJobCommand.Script)
        assertEquals("ls -la && pwd", (s.command as com.helix.runtime.proot.ipc.ProotJobCommand.Script).script)
        assertEquals("src", s.cwd)
        // The per-call 15 s clamps below the call deadline (+120 s).
        assertTrue(
            s.deadlineEpochMs in (System.currentTimeMillis() - 5_000L)..(System.currentTimeMillis() + 15_000L + 5_000L),
        )
        assertEquals("scope:app:output/result.txt", s.outputReference)
    }

    @Test
    fun cwdEnvironmentAndFilesCapsAreEnforced() {
        assertParseFailure(
            args {
                put("argv", jsonArrayOf("echo", "x"))
                put("cwd", "a/b")
            },
            "single directory name",
        )
        assertParseFailure(
            args {
                put("argv", jsonArrayOf("echo", "x"))
                put("cwd", "..")
            },
            "single directory name",
        )
        assertParseFailure(
            args {
                put("argv", jsonArrayOf("echo", "x"))
                put("cwd", "../x")
            },
            "single directory name",
        )

        val tooManyEnv =
            buildJsonObject {
                repeat(65) { i -> put("VAR$i", JsonPrimitive("v")) }
            }
        assertParseFailure(
            args {
                put("argv", jsonArrayOf("echo", "x"))
                put("environment", tooManyEnv)
            },
            "at most 64",
        )

        val tooManyFiles =
            buildJsonArray { repeat(65) { i -> add(JsonPrimitive("scope:app:work/f$i")) } }
        assertParseFailure(
            args {
                put("argv", jsonArrayOf("echo", "x"))
                put("files", tooManyFiles)
            },
            "at most 64",
        )

        assertParseFailure(
            args {
                put("argv", jsonArrayOf("echo", "x"))
                put("timeoutSeconds", JsonPrimitive(61))
            },
            "1..60",
        )
        assertParseFailure(
            args {
                put("argv", jsonArrayOf("echo", "x"))
                put("timeoutSeconds", JsonPrimitive(0))
            },
            "1..60",
        )
    }

    @Test
    fun environmentValuesPassThroughRawForScreening() {
        val call =
            parsedOk(
                args {
                    put("argv", jsonArrayOf("echo", "x"))
                    put(
                        "environment",
                        buildJsonObject {
                            put("LANG", JsonPrimitive("C.UTF-8"))
                            put("MY_TOKEN", JsonPrimitive("hunter2"))
                        },
                    )
                },
            )
        // The tool does NOT screen (the production executor does, with the live SecretStore
        // snapshot): the raw map is what reaches the screen call, and the screen is what
        // refuses. This test pins the tool's contract boundary.
        assertEquals(mapOf("LANG" to "C.UTF-8", "MY_TOKEN" to "hunter2"), call.environment)
    }

    // ------------------------------------------------------------------ approval card

    @Test
    fun approvalCardShowsTheFullScriptOfflineAndTheSnapshotSource() {
        val d = LinuxRunTool.descriptor()
        val args =
            buildJsonObject {
                put("script", JsonPrimitive("git log -1 && echo done"))
                put(
                    "files",
                    buildJsonArray {
                        add(JsonPrimitive("scope:app:work/a.txt"))
                        add(JsonPrimitive("scope:app:work/b.txt"))
                    },
                )
            }
        val ui =
            com.helix.app.approval.ApprovalUiMapper
                .codeExecutionUi(d, args)
        assertTrue("a CODE_EXECUTION tool with a script arg must render the code section", ui != null)
        val e = ui!!
        // The FULL script (审批 UI 必须展示完整 script), not truncated.
        assertEquals("git log -1 && echo done", e.code)
        // Offline: the Runtime has no INTERNET permission.
        assertFalse(e.online)
        // The limits line names the fixed deadline + the offline boundary.
        // (HXA-069 localization merge: the card fields are string-resource IDs +
        // args resolved by the UI; the JVM test asserts the resource wiring.)
        assertEquals(R.string.approval_limits_proot, e.limitsRes)
        assertEquals(listOf("60"), e.limitsArgs)
        // The input source names the COPIED snapshot (references, never bodies).
        assertEquals(R.string.approval_input_proot_files, e.inputSourceRes)
        assertEquals(listOf("2"), e.inputSourceArgs)
    }

    @Test
    fun approvalCardShowsTheArgvLineWhenNoScript() {
        val d = LinuxRunTool.descriptor()
        val args =
            buildJsonObject {
                put(
                    "argv",
                    buildJsonArray {
                        add(JsonPrimitive("python3"))
                        add(JsonPrimitive("-c"))
                        add(JsonPrimitive("print(1)"))
                    },
                )
            }
        val ui =
            com.helix.app.approval.ApprovalUiMapper
                .codeExecutionUi(d, args)
        assertTrue(ui != null)
        assertEquals("python3 -c print(1)", ui!!.code)
        assertFalse(ui.online)
        assertEquals(ApprovalCardUi.NO_INPUT, ui.inputSourceRes)
    }

    // ------------------------------------------------------------------ helpers

    private fun parsedOk(args: JsonObject): LinuxRunTool.ParsedLinuxCall {
        val result = LinuxRunTool.parsed(call(args))
        assertTrue("expected Ok but got $result", result is LinuxRunTool.ParsedResult.Ok)
        return (result as LinuxRunTool.ParsedResult.Ok).call
    }

    private fun assertParseFailure(
        args: JsonObject,
        messagePart: String,
    ) {
        val result = LinuxRunTool.parsed(call(args))
        assertTrue(
            "expected a ParseFailure containing '$messagePart' but got $result",
            result is LinuxRunTool.ParsedResult.ParseFailure &&
                (result as LinuxRunTool.ParsedResult.ParseFailure).detail.contains(messagePart),
        )
    }

    private fun call(args: JsonObject): ExecutableToolCall =
        ExecutableToolCall(
            toolCallId = "tc-linux-test",
            toolName = "code.linux.run",
            toolVersion = "1",
            args = args,
            executionTarget = ExecutionTargetType.LOCAL_PROOT,
            deadline =
                java.time.Instant
                    .now()
                    .plusSeconds(120),
            cancel =
                object : CancelSignal {
                    override fun isCancelled(): Boolean = false
                },
        )

    private fun emptyArgs(): JsonObject = buildJsonObject {}

    private fun args(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject(block)

    private fun jsonArrayOf(vararg items: String): JsonArray =
        buildJsonArray { items.forEach { add(JsonPrimitive(it)) } }
}
