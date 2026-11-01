package com.helix.app.proot

import com.helix.core.model.ToolName
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillRepository
import com.helix.extensions.skills.SkillSource
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Same PRoot executor, workspace and permission path; imported metadata never grants execution. */
internal object SkillScriptRunTool {
    const val NAME = "skills.run"

    fun register(
        registry: ToolRegistry,
        repository: SkillRepository,
        runner: LinuxRunTool.LinuxExecutor,
        decorate: (ToolExecutor) -> ToolExecutor,
    ) {
        val base = LinuxRunTool.descriptor()
        val properties =
            base.inputSchema.getValue("properties").jsonObject.filterKeys {
                it in setOf("workspace", "cwd", "artifacts", "timeoutSeconds", "toolBridge")
            } +
                mapOf(
                    "source" to
                        Json.parseToJsonElement(
                            """
                            {"type":"string","enum":["USER_IMPORTED","PROJECT","BUILT_IN"]}
                            """.trimIndent(),
                        ),
                    "name" to stringSchema(),
                    "snapshotHash" to stringSchema(),
                    "path" to stringSchema(),
                    "args" to
                        Json.parseToJsonElement(
                            """
                            {"type":"array","maxItems":128,"items":{"type":"string","maxLength":8192}}
                            """.trimIndent(),
                        ),
                )
        val descriptor =
            base.copy(
                name = ToolName(NAME),
                description =
                    "Run an enabled, hash-pinned Skill script in Linux (.py/.js/.mjs/.sh). " +
                        "args is an argument array, never shell interpolation. Keeps selected real workspace. " +
                        "Resources stage at /tmp/skill (HELIX_SKILL_ROOT); no source writeback. " +
                        "scripts/manifest.json declares commands/python/node needs; probes precede execution. " +
                        "Missing dependencies fail without installation. " +
                        "toolBridge is user-controlled; delegated tools follow normal authorization.",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        put("properties", JsonObject(properties))
                        put(
                            "required",
                            JsonArray(listOf("source", "name", "snapshotHash", "path").map(::JsonPrimitive)),
                        )
                        put("additionalProperties", false)
                    },
            )
        registry.register(descriptor, decorate(executor(repository, runner)))
    }

    private fun executor(
        repository: SkillRepository,
        runner: LinuxRunTool.LinuxExecutor,
    ): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                val prepared =
                    try {
                        prepare(repository, call)
                    } catch (_: IllegalArgumentException) {
                        return LinuxRunTool.failed(
                            "Skill script unavailable or changed.",
                            "SKILL_SCRIPT_INVALID",
                        )
                    }
                return runner.execute(prepared, { call.cancel.isCancelled() })
            }
        }

    private fun prepare(
        repository: SkillRepository,
        call: ExecutableToolCall,
    ): LinuxRunTool.ParsedLinuxCall {
        fun text(name: String) =
            call.args
                .getValue(name)
                .jsonPrimitive.content
        val key = SkillKey(SkillSource.valueOf(text("source")), text("name"), text("snapshotHash"))
        val path = text("path")
        val scripts = repository.scripts(key, call.sessionId, call.turnId)
        require(scripts.any { it.relativePath == path })
        val interpreter =
            when (path.substringAfterLast('.')) {
                "py" -> "python3"
                "js", "mjs" -> "node"
                "sh" -> "sh"
                else -> throw IllegalArgumentException("SKILL_SCRIPT_TYPE_UNSUPPORTED")
            }
        val manifest =
            if (scripts.any { it.relativePath == "scripts/manifest.json" }) {
                val resource = repository.readResource(key, "scripts/manifest.json", call.sessionId, call.turnId)
                Json.parseToJsonElement(resource.content).jsonObject
            } else {
                JsonObject(emptyMap())
            }
        require(manifest.keys.all { it == "scripts" })
        val declaration = manifest["scripts"]?.jsonObject?.get(path)?.jsonObject ?: JsonObject(emptyMap())
        validateNeeds(declaration)
        val snapshot = repository.scriptSnapshot(key, call.sessionId, call.turnId)
        val args = (call.args["args"] as? JsonArray).orEmpty()
        val translated =
            buildJsonObject {
                call.args
                    .filterKeys {
                        it in setOf("workspace", "cwd", "artifacts", "timeoutSeconds", "toolBridge")
                    }.forEach { (k, v) -> put(k, v) }
                val launch = listOf("python3", "-c", PROBE_AND_RUN, interpreter, path, declaration.toString())
                put("argv", JsonArray(launch.map(::JsonPrimitive) + args))
            }
        val parsed = LinuxRunTool.parsed(call.copy(args = translated))
        require(parsed is LinuxRunTool.ParsedResult.Ok)
        return parsed.call.copy(scriptDirectory = snapshot.directory.toString(), scriptSnapshotHash = key.snapshotHash)
    }

    private fun validateNeeds(declaration: JsonObject) {
        require(declaration.keys.all { it in setOf("commands", "python", "node") })
        declaration.forEach { (kind, value) ->
            require(value is JsonArray && value.size <= 64)
            value.forEach { item ->
                require(item is JsonPrimitive && item.isString && item.content.length in 1..128)
                val pattern = if (kind == "python") Regex("[A-Za-z_][A-Za-z0-9_.]*") else Regex("[A-Za-z0-9_@./-]+")
                require(pattern.matches(item.content) && !item.content.startsWith('.') && ".." !in item.content)
            }
        }
    }

    private fun stringSchema() =
        buildJsonObject {
            put("type", "string")
            put("minLength", 1)
            put("maxLength", 512)
        }

    private val PROBE_AND_RUN =
        """
        import importlib.util,json,os,shutil,subprocess,sys
        interpreter,path,raw=sys.argv[1:4]
        needs=json.loads(raw); missing=[]
        for command in [interpreter]+needs.get('commands',[]):
            if shutil.which(command) is None: missing.append('command:'+command)
        for module in needs.get('python',[]):
            try: found=importlib.util.find_spec(module) is not None
            except (ImportError,ModuleNotFoundError,ValueError): found=False
            if not found: missing.append('python:'+module)
        for module in needs.get('node',[]):
            if shutil.which('node') is None: missing.append('command:node'); break
            result=subprocess.run(['node','-e','require.resolve(process.argv[1])',module],
                stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            if result.returncode: missing.append('node:'+module)
        report={'status':'missing' if missing else 'available','missing':missing}
        print(json.dumps({'skillDependencies':report}),flush=True)
        if missing: sys.exit(78)
        os.environ['HELIX_SKILL_ROOT']='/tmp/skill'
        os.execvp(interpreter,[interpreter,'/tmp/skill/'+path]+sys.argv[4:])
        """.trimIndent()
}
