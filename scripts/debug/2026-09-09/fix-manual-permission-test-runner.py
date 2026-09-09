from pathlib import Path
p=Path('scripts/debug/2026-09-09/run-owned-emulator.py');s=p.read_text().replace('            extras = []', '''            if args.grant_shared_storage:
                app_package = args.runner.split("/", 1)[0].removesuffix(".test")
                api = int(device("shell", "getprop", "ro.build.version.sdk").strip())
                if api >= 30:
                    device("shell", "appops", "set", app_package, "MANAGE_EXTERNAL_STORAGE", "allow")
                else:
                    for permission in ("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE"):
                        device("shell", "pm", "grant", app_package, "android.permission." + permission)
            extras = []''').replace('    parser.add_argument("--reverse-port", type=int)', '    parser.add_argument("--reverse-port", type=int)\n    parser.add_argument("--grant-shared-storage", action="store_true")');p.write_text(s)
p=Path('scripts/debug/2026-09-09/run-manual-files-regression.sh');s=p.read_text().replace(' --classes "$classes" --output "$output"',' --classes "$classes" --grant-shared-storage --output "$output"');p.write_text(s)
p=Path('app/src/androidTest/kotlin/com/helix/app/ui/ManualSharedFileDeviceTest.kt');s=p.read_text();a=s.index('        val packageName');b=s.index('        assertTrue(SharedStorageAccess',a);s=s[:a]+s[b:];s=s.replace('            if (Build.VERSION.SDK_INT >= 30) shell("appops set $packageName MANAGE_EXTERNAL_STORAGE default")\n','');a=s.index('    private fun shell(');s=s[:a]+'}\n';s=s.replace('            compose.waitUntil { folder.resolve("renamed.txt").exists() }', '            compose.waitUntil { compose.onAllNodesWithTag("files-entry-renamed.txt").fetchSemanticsNodes().isNotEmpty() }');s=s.replace('            val scope = SharedStorageAccess.SCOPE_ID', '''            val scope = SharedStorageAccess.SCOPE_ID
            val pipeline = compose.container().toolPipeline
            val descriptor = pipeline.registry.resolve(com.helix.core.model.ToolName("read"), com.helix.core.model.ToolVersion(1))
            val result = pipeline.implementations.resolve(descriptor.name, descriptor.version).execute(
                com.helix.tools.framework.ExecutableToolCall(
                    toolCallId = "manual-boundary", toolName = "read", toolVersion = "1",
                    args = kotlinx.serialization.json.buildJsonObject {
                        put("path", kotlinx.serialization.json.JsonPrimitive("scope:$scope:$name/original.txt"))
                    },
                    executionTarget = com.helix.core.model.ExecutionTargetType.LOCAL_ANDROID,
                    deadline = java.time.Instant.now().plusSeconds(10),
                    cancel = com.helix.tools.framework.NoCancellation,
                ),
            )
            assertTrue(result is com.helix.tools.framework.ToolExecutorResult.Failed)''');p.write_text(s)
