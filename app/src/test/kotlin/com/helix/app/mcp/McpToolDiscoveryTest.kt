package com.helix.app.mcp

import com.helix.app.application.tools.FileToolArguments
import com.helix.core.model.CancelSignal
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class McpToolDiscoveryTest {
    @Test fun groupedSettingsAndDeviceToolsRemainDiscoverableInChinese() {
        registerFixture(
            com.helix.app.settings.HelixSettingsTool
                .descriptor(),
        )
        registerFixture(
            com.helix.tools.android.ClipboardReadTool
                .descriptor(),
        )
        assertTrue(discovery.search("s", "设置 配置", 8).any { it.name.value == "settings_inspect" })
        assertTrue(discovery.search("s", "剪贴板", 8).any { it.name.value == "device_clipboard_read" })
    }

    @Test fun standaloneQuestionToolKeepsItsShortNameAndDiscoveryCategory() {
        registerFixture(
            com.helix.app.chat.UserQuestionTool
                .descriptor(),
        )
        assertTrue(discovery.search("s", "询问 用户", 8).any { it.name.value == "ask_user" })
        assertEquals(
            "user",
            ToolDiscoveryMetadata
                .of(
                    com.helix.app.chat.UserQuestionTool
                        .descriptor(),
                ).category,
        )
    }

    @Test fun sessionHistoryCanBeDiscoveredInChineseWithoutDefaultOrCrossSessionExposure() {
        com.helix.app.chat.SessionToolDescriptors.names.forEach {
            registerFixture(
                com.helix.app.chat.SessionToolDescriptors
                    .descriptor(it),
            )
        }
        val defaults =
            com.helix.app.chat.ModelToolExposureOrder
                .defaultNames(emptySet())
        assertFalse(discovery.visible("s", registry.all(), defaults).any { it.name.value.startsWith("session_") })
        val matches = discovery.search("s", "会话 历史", 8)
        assertTrue(matches.any { it.name.value == "session_history" })
        assertTrue(discovery.visible("s", registry.all(), defaults).any { it.name.value == "session_history" })
        assertFalse(discovery.visible("other", registry.all(), defaults).any { it.name.value.startsWith("session_") })
    }

    @Test fun skillReadersAndManagementCanBeDiscoveredWithoutEagerSchemas() {
        com.helix.extensions.skills.SkillTools
            .descriptors()
            .forEach(::registerFixture)
        val all = registry.all()
        assertEquals(listOf(search), discovery.visible("s", all))
        for (name in listOf("skills_list", "skills_enable", "skills_read", "skills_read_resource")) {
            assertEquals(listOf(name), discovery.search("s", name, 1).map { it.name.value })
            assertTrue(discovery.visible("s", all).any { it.name.value == name })
        }
        assertEquals(listOf(search), discovery.visible("other", all))
    }

    @Test fun discoveringGoalMutationLoadsOnlyItsAdmittedReadPrerequisiteInThatSession() {
        com.helix.app.goal.GoalLifecycleTools
            .register(registry) { error("Discovery must not execute a tool") }
        val all = registry.all()
        for (name in listOf("goal_create", "goal_update")) {
            val session = "test-$name"
            assertEquals(listOf(search), discovery.visible(session, all))
            assertEquals(listOf(name), discovery.search(session, name, 4).map { it.name.value })
            val exposed = discovery.visible(session, all).map { it.name.value }.toSet()
            assertEquals(setOf("tools_search", name, "goal_get"), exposed)
        }
        val admitted = all.filterNot { it.name.value == "goal_get" }
        discovery.visible("restricted", admitted)
        discovery.search("restricted", "goal_update", 4)
        assertFalse(discovery.visible("restricted", admitted).any { it.name.value == "goal_get" })
        assertEquals(listOf(search), discovery.visible("other", all))
    }

    @Test fun sharedConversationLookupDiscoversTargetDirectoryWithoutDefaultExposure() {
        com.helix.app.chat.SessionToolDescriptors.names.forEach {
            registerFixture(
                com.helix.app.chat.SessionToolDescriptors
                    .descriptor(it),
            )
        }
        val matches = discovery.search("s", "another shared conversation", 4)
        assertTrue(matches.any { it.name.value == "session_list" })
        assertFalse(discovery.visible("other", registry.all(), emptySet()).any { it.name.value == "session_list" })
    }

    @Test fun fileSearchToolsKeepFileCategoryAndRespectAvailability() {
        val glob =
            com.helix.tools.files.GlobTool
                .descriptor()
        val grep =
            com.helix.tools.files.GrepTool
                .descriptor()
        listOf(glob, grep).forEach(::registerFixture)
        discovery.visible("s", registry.all())
        for (tool in listOf(glob, grep)) {
            assertEquals("files", ToolDiscoveryMetadata.of(tool).category)
            assertEquals(listOf(tool), discovery.search("s", tool.name.value, 8, category = "files"))
        }
        assertTrue(discovery.search("s", "内容 搜索", 8, category = "files").contains(grep))
        assertTrue(discovery.search("s", "文件名 通配", 8, category = "files").contains(glob))
        val filtered = McpToolDiscovery(registry) { _, tool -> tool != grep }
        assertTrue(filtered.search("s", "grep", 8, category = "files").isEmpty())
        assertTrue(discovery.search("s", "glob", 8, category = "browser").isEmpty())
    }

    private val registry = ToolRegistry()

    private val discovery = McpToolDiscovery(registry).also { it.register(registry) }
    private val search = requireNotNull(registry.resolveLatest(ToolName("tools_search")))

    private val fixtureExecutor =
        object : com.helix.tools.framework.ToolExecutor {
            override fun execute(call: ExecutableToolCall) =
                ToolExecutorResult.Completed(kotlinx.serialization.json.buildJsonObject {})
        }

    private fun registerFixture(descriptor: ToolDescriptor) = registry.register(descriptor, fixtureExecutor)

    private fun replaceCatalog(
        server: String,
        descriptors: List<ToolDescriptor>,
    ) = registry.replaceMcpServer(
        server,
        descriptors.map {
            com.helix.tools.framework
                .ToolBinding(it, fixtureExecutor)
        },
    )

    private fun remote(index: Int): ToolDescriptor =
        search.copy(
            name = ToolName("mcp_catalog_tool_$index"),
            description = "catalog operation $index",
            operationClass = ToolOperationClass.NETWORK,
            origin = ToolOrigin.McpOrigin("catalog", "2025-03-26", "a".repeat(64)),
        )

    private fun catalog(count: Int = 500) = replaceCatalog("catalog", (0 until count).map(::remote))

    @Test fun discoveredMaximumLengthDescriptionRemainsValidAcrossRequests() {
        val tool = search.copy(name = ToolName("code_linux_run"), description = "x".repeat(1024))
        registerFixture(tool)
        val defaults =
            com.helix.app.chat.ModelToolExposureOrder
                .defaultNames(emptySet())
        assertFalse(tool in discovery.visible("session", registry.all(), defaults))
        assertEquals(listOf(tool), discovery.search("session", "code_linux_run", 1))
        repeat(3) {
            val loaded = discovery.visible("session", registry.all(), defaults)
            assertTrue(tool in loaded)
            loaded.forEach { descriptor ->
                com.helix.app.application.tools.FileToolArguments
                    .modelSchema(descriptor)
            }
            assertEquals(
                tool.description,
                com.helix.app.application.tools.FileToolArguments
                    .modelSchema(tool)
                    .description,
            )
        }
        assertFalse(tool in discovery.visible("other-session", registry.all(), defaults))
    }

    @Test fun optionalBuiltInNeedsDiscoveryAndNeverLeaksAcrossSessions() {
        val time = search.copy(name = ToolName("time_now"), description = "Current time")
        registerFixture(time)
        val defaults =
            com.helix.app.chat.ModelToolExposureOrder
                .defaultNames(emptySet())
        assertFalse(time in discovery.visible("session", registry.all(), defaults))
        assertEquals(listOf(time), discovery.search("session", "time_now", 1))
        assertTrue(time in discovery.visible("session", registry.all(), defaults))
        assertFalse(time in discovery.visible("another-session", registry.all(), defaults))
    }

    @Test fun searchChecksAvailabilityOnlyForMatchingCandidatesUntilWindowIsFull() {
        catalog()
        val checked = mutableListOf<String>()
        val filtered =
            McpToolDiscovery(registry) { _, descriptor ->
                checked += descriptor.name.value
                descriptor.name.value != "mcp_catalog_tool_0"
            }
        assertEquals(listOf(remote(499)), filtered.search("session", "tool_499", 1))
        assertEquals(listOf("mcp_catalog_tool_499"), checked)
        checked.clear()
        assertEquals(listOf(remote(1)), filtered.search("session", "catalog", 1))
        assertEquals(listOf("mcp_catalog_tool_0", "mcp_catalog_tool_1", "mcp_catalog_tool_499"), checked)
    }

    @Test fun exactNameAndNameMatchesRankAheadOfDescriptionMentions() {
        val exact = search.copy(name = ToolName("files_archive"), description = "Create an archive")
        val mention = search.copy(name = ToolName("aaa_helper"), description = "Use files_archive to archive files")
        registerFixture(exact)
        registerFixture(mention)
        assertEquals(listOf(exact), discovery.search("session", "files_archive", 1))
        assertEquals(listOf(exact), discovery.search("session", "files_archive", 16))
        assertEquals(exact, discovery.search("session", "archive", 2).first())
    }

    @Test fun exactDisabledToolDoesNotDiscoverAnUnrelatedSubstitute() {
        val mkdir = search.copy(name = ToolName("files_mkdir"), description = "Create a directory")
        val other = search.copy(name = ToolName("code_helper"), description = "Alternative to files_mkdir")
        registerFixture(mkdir)
        registerFixture(other)
        val filtered = McpToolDiscovery(registry) { _, descriptor -> descriptor != mkdir }
        assertTrue(filtered.search("session", "files_mkdir", 16).isEmpty())
    }

    @Test fun workspaceDirectoryCreationIsDefaultButStillSubjectToAdmission() {
        val mkdir = search.copy(name = ToolName("files_mkdir"), description = "Create a directory")
        registerFixture(mkdir)
        val defaults =
            com.helix.app.chat.ModelToolExposureOrder
                .defaultNames(emptySet())
        assertTrue(mkdir in discovery.visible("session", registry.all(), defaults))
        assertFalse(mkdir in discovery.visible("session", listOf(search), defaults))
        val filtered = McpToolDiscovery(registry) { _, descriptor -> descriptor != mkdir }
        assertFalse(mkdir in filtered.visible("session", registry.all(), defaults))
    }

    @Test fun compositeQueryFallsBackToRankedPartialMatches() {
        val click = search.copy(name = ToolName("android_ui_click"), description = "Click Android UI controls")
        val capture = search.copy(name = ToolName("android_screen_capture"), description = "Capture the Android screen")
        val weak = search.copy(name = ToolName("misc_screen_helper"), description = "Screen helper")
        registerFixture(click)
        registerFixture(capture)
        registerFixture(weak)

        val found = discovery.search("session", "android ui click screen", 3)

        assertEquals(listOf(click, capture, weak), found)
    }

    @Test fun completeTermMatchesRankFirstWithoutHidingComplementaryPartialMatches() {
        val complete = search.copy(name = ToolName("android_ui_click"), description = "Click a button on Android UI")
        val partial = search.copy(name = ToolName("android_screen"), description = "Inspect Android UI")
        registerFixture(complete)
        registerFixture(partial)

        val found = discovery.search("session", "android ui click", 2)

        assertEquals(listOf(complete, partial), found)
        assertTrue(partial in discovery.visible("session", registry.all()))
    }

    @Test fun unavailableCompleteMatchesStillAllowAvailablePartialDiscovery() {
        val complete = search.copy(name = ToolName("android_ui_click"), description = "Click a button on Android UI")
        val partial = search.copy(name = ToolName("android_screen"), description = "Inspect Android UI")
        listOf(complete, partial).forEach(::registerFixture)
        val filtered = McpToolDiscovery(registry) { _, tool -> tool != complete }
        assertEquals(listOf(partial), filtered.search("session", "android ui click", 8))
        assertEquals(listOf(search, partial), filtered.visible("session", registry.all()))
    }

    @Test fun englishWholeWordsRankBeforeButDoNotHideSubstringMatches() {
        val execute = search.copy(name = ToolName("sample_execute"), description = "Execute a command")
        val download = search.copy(name = ToolName("browser_download"), description = "Download executable files")
        val done = search.copy(name = ToolName("plan_submit"), description = "Record previously executed steps")
        listOf(execute, download, done).forEach(::registerFixture)
        assertEquals(listOf(execute, done), discovery.search("session", "execute", 8))
        assertEquals(listOf(download), discovery.search("session", "executable", 8))
    }

    @Test fun underscoreNameComponentsRankBeforeLongerNumbersAndExactNamesRemainPrecise() {
        // These are synthetic operation tools, not copies of tools_search's parameter descriptions.
        val fixture = search.copy(inputSchema = Json.parseToJsonElement("{}").jsonObject)
        val issue = fixture.copy(name = ToolName("sample_create_issue"), description = "Create a record")
        val log = fixture.copy(name = ToolName("sample_read_log"), description = "Inspect saved output")
        val exact = fixture.copy(name = ToolName("sample_tool_49"), description = "Inspect a record")
        val longer = fixture.copy(name = ToolName("sample_tool_499"), description = "Inspect a record")
        listOf(issue, log, exact, longer).forEach(::registerFixture)
        assertEquals(listOf(issue), discovery.search("s", "issue", 8))
        assertEquals(listOf(log), discovery.search("s", "read log", 8))
        assertEquals(listOf(exact, longer), discovery.search("s", "tool_49", 8))
        assertEquals(listOf(exact), discovery.search("s", "sample_tool_49", 8))
    }

    @Test fun camelCaseSchemaFieldFragmentsRemainDiscoverableWithoutDescriptionMatches() {
        val tool =
            search.copy(
                name = ToolName("sample_inspect"),
                description = "Inspect a record",
                inputSchema =
                    Json
                        .parseToJsonElement(
                            """{"type":"object","properties":{"maxOutputTokens":{"type":"integer"}}}""",
                        ).jsonObject,
            )
        registerFixture(tool)
        assertEquals(listOf(tool), discovery.search("s", "output", 8))
        assertEquals(listOf(tool), discovery.search("s", "output tokens", 8))
        assertEquals(listOf(tool), discovery.search("s", "maxOutputTokens", 8))
    }

    @Test fun missCannotRetainReplacedOrDisabledBindings() {
        catalog()
        var enabled = true
        val filtered = McpToolDiscovery(registry) { _, _ -> enabled }
        filtered.search("session", "tool_499", 1)
        enabled = false
        assertTrue(filtered.search("session", "absent", 1).isEmpty())
        enabled = true
        assertEquals(listOf(search), filtered.visible("session", registry.all()))
        filtered.search("session", "tool_499", 1)
        replaceCatalog("catalog", listOf(remote(499).copy(description = "replacement")))
        filtered.search("session", "absent", 1)
        // Add a large directory to avoid the independent small-catalog automatic exposure.
        replaceCatalog("catalog", (0 until 500).map { remote(it).copy(description = "replacement") })
        assertEquals(listOf(search), filtered.visible("session", registry.all()))
    }

    @Test
    fun lateCatalogToolIsDiscoverableWithoutExposingAllSchemas() {
        catalog()
        assertEquals(listOf(search), discovery.visible("session", registry.all()))
        val found = discovery.search("session", "tool_499", 8)
        assertEquals(listOf("mcp_catalog_tool_499"), found.map { it.name.value })
        assertEquals(listOf(search) + found, discovery.visible("session", registry.all()))
        assertEquals(listOf(search), discovery.visible("other", registry.all()))
    }

    @Test
    fun discoveryAndLoadedWindowSurviveTheModelToolLimit() {
        catalog()
        val other = (0 until 160).map { search.copy(name = ToolName("local_tool_$it")) }
        val admitted = other + registry.all()
        assertTrue(search in discovery.visible("session", admitted))
        val found = discovery.search("session", "catalog", McpToolDiscovery.WINDOW)
        val exposed = discovery.visible("session", admitted)
        assertTrue(search in exposed)
        assertTrue(exposed.containsAll(found))
        assertEquals(exposed.size, exposed.distinctBy { it.name }.size)
    }

    @Test
    fun searchedSmallCatalogToolAlsoSurvivesTheModelToolLimit() {
        catalog(3)
        val other = (0 until 160).map { search.copy(name = ToolName("local_tool_$it")) }
        val found = discovery.search("session", "tool_2", 1).single()
        assertTrue(found in discovery.visible("session", other + registry.all()))
        assertFalse(found in discovery.visible("session", other + listOf(search)))
    }

    @Test
    fun separateSearchesRetainRelatedValidCapabilitiesWithoutSharingAcrossSessions() {
        val start = search.copy(name = ToolName("code_job_start"), description = "Start a background job")
        val await = search.copy(name = ToolName("jobs_await"), description = "Await a background job")
        registerFixture(start)
        registerFixture(await)
        assertEquals(listOf(start), discovery.search("session", "code_job_start", 8))
        assertEquals(listOf(await), discovery.search("session", "jobs_await", 8))
        assertEquals(setOf(search, start, await), discovery.visible("session", registry.all()).toSet())
        assertEquals(setOf(start, await), discovery.search("session", "job start await", 8).toSet())
        assertEquals(setOf(search, start, await), discovery.visible("session", registry.all()).toSet())
        assertEquals(listOf(search), discovery.visible("other-session", registry.all()))
    }

    @Test fun chineseActionAndSchemaFieldsAreDiscoverableWithSourceFilters() {
        val screenshot = search.copy(name = ToolName("ui_screenshot"), description = "Capture current frame")
        val click = search.copy(name = ToolName("ui_click"), description = "Press a node")
        val archive =
            search.copy(
                name = ToolName("files_archive"),
                description = "Create ZIP",
                discovery =
                    com.helix.tools.framework
                        .ToolDiscoveryHints(category = "files"),
            )
        listOf(screenshot, click, archive).forEach(::registerFixture)
        assertEquals(listOf(screenshot), discovery.search("session", "截图", 8))
        assertEquals(listOf(archive), discovery.search("session", "压缩", 8, source = "helix", category = "files"))
        assertTrue(discovery.search("session", "压缩", 8, source = "mcp:other").isEmpty())
        assertTrue(discovery.search("session", "query", 8).isNotEmpty())
    }

    @Test fun capabilityCatalogUsesCurrentAdmissionAndDoesNotRetainDisabledSources() {
        catalog(3)
        val allowed = mutableSetOf(remote(1).name)
        val filtered =
            McpToolDiscovery(registry) { _, descriptor -> descriptor == search || descriptor.name in allowed }
        filtered.visible("session", registry.all())
        assertTrue(requireNotNull(filtered.catalog("session")).entries.any { it.source == "mcp:catalog" })
        assertNull(filtered.catalog("other-session"))
        allowed.clear()
        assertFalse(requireNotNull(filtered.catalog("session")).entries.any { it.source == "mcp:catalog" })
        filtered.visible("session", emptyList())
        assertNull(filtered.catalog("session"))
    }

    @Test fun sourceAndCategoryFiltersCannotBypassAvailability() {
        catalog(3)
        val filtered = McpToolDiscovery(registry) { _, descriptor -> descriptor.name != remote(1).name }
        assertTrue(filtered.search("session", remote(1).name.value, 8, source = "mcp:catalog").isEmpty())
        assertTrue(filtered.search("session", "catalog", 8, category = "files").isEmpty())
    }

    @Test fun recentWindowUsesSchemaCostRatherThanARequestToolCount() {
        val tools = (1..24).map { search.copy(name = ToolName("files_fixture_$it"), description = "small contract") }
        tools.forEach(::registerFixture)
        tools.forEach { discovery.search("session", it.name.value, 1) }
        assertTrue(discovery.visible("session", registry.all()).containsAll(tools))
        val large =
            (1..80).map {
                search.copy(
                    name = ToolName("files_large_$it"),
                    description = "x".repeat(ToolDescriptor.MAX_DESCRIPTION_LENGTH),
                )
            }
        large.forEach(::registerFixture)
        discovery.visible("session", registry.all())
        large.forEach { discovery.search("session", it.name.value, 1) }
        val visible = discovery.visible("session", registry.all())
        assertTrue(large.last() in visible)
        assertFalse(large.first() in visible)
        val beforeMiss = visible.toSet()
        discovery.search("session", "no_matching_contract", 1)
        assertEquals(beforeMiss, discovery.visible("session", registry.all()).toSet())
    }

    @Test fun gestureDiscoveryIncludesOnlyAvailableSameOriginObservationContracts() {
        val origin = ToolOrigin.PluginOrigin("mobile-use", "1", "mobile-use")
        val gesture =
            search.copy(
                name = ToolName("ui_gesture"),
                origin = origin,
                discoveryCompanions = setOf(ToolName("ui_screenshot"), ToolName("ui_device")),
            )
        val screenshot = search.copy(name = ToolName("ui_screenshot"), origin = origin)
        val device = search.copy(name = ToolName("ui_device"), origin = origin)
        listOf(gesture, screenshot, device).forEach(::registerFixture)
        val filtered = McpToolDiscovery(registry) { _, descriptor -> descriptor != device }
        assertEquals(listOf(gesture), filtered.search("session", "ui_gesture", 1))
        val visible = filtered.visible("session", registry.all())
        assertTrue(screenshot in visible)
        assertFalse(device in visible)
        assertFalse(screenshot in filtered.visible("other", registry.all()))
    }

    @Test fun cheapMcpSourcesDoNotHaveASixteenToolCliff() {
        val tools =
            (1..20).map {
                remote(it).copy(description = "tiny", inputSchema = Json.parseToJsonElement("{}").jsonObject)
            }
        replaceCatalog("catalog", tools)
        assertTrue(discovery.visible("session", registry.all()).containsAll(tools))
        replaceCatalog(
            "catalog",
            tools.map { it.copy(description = "x".repeat(ToolDescriptor.MAX_DESCRIPTION_LENGTH)) },
        )
        assertEquals(listOf(search), discovery.visible("session", registry.all()))
        assertTrue(discovery.search("session", tools.last().name.value, 1).isNotEmpty())
    }

    @Test fun eagerSourceBudgetIncludesModelPresentationMetadataAndDefersTheWholeGroup() {
        val tools =
            (1..20).map {
                remote(it).copy(description = "x".repeat(600), inputSchema = Json.parseToJsonElement("{}").jsonObject)
            }
        val rawBytes =
            tools.sumOf {
                it.name.value
                    .toByteArray()
                    .size +
                    it.description
                        .toByteArray()
                        .size
                        .toLong() +
                    it.inputSchema
                        .toString()
                        .toByteArray()
                        .size + 128L
            }
        assertTrue(rawBytes < 16 * 1024L)
        assertTrue(tools.sumOf(ToolDiscoveryWorkset::bytes) > 16 * 1024L)
        replaceCatalog("catalog", tools)
        assertEquals(listOf(search), discovery.visible("session", registry.all()))
        assertEquals(listOf(tools.last()), discovery.search("session", tools.last().name.value, 1))
        assertEquals(listOf(search, tools.last()), discovery.visible("session", registry.all()))
    }

    @Test fun oversizedUnselectedSourceDoesNotRequireItsSchemasToBeProjectable() {
        val tools =
            (1..32).map {
                remote(it).copy(
                    description = "x".repeat(1023) + "\n",
                    inputSchema =
                        Json
                            .parseToJsonElement(
                                """{"type":"object","properties":{"__helix_intent":{"type":"string"}}}""",
                            ).jsonObject,
                )
            }
        replaceCatalog("catalog", tools)
        assertEquals(listOf(search), discovery.visible("session", registry.all()))
    }

    @Test fun rawPreflightAccountsForMaximumPortableNamesBeforeProjection() {
        val tools =
            (1..16).map {
                remote(it).copy(
                    name = ToolName("mcp_catalog_t$it".padEnd(64, 'b')),
                    description = "x".repeat(800),
                    inputSchema =
                        Json
                            .parseToJsonElement(
                                """{"type":"object","properties":{"__helix_intent":{"type":"string"}}}""",
                            ).jsonObject,
                )
            }
        val withoutNames =
            tools.sumOf {
                com.helix.core.agent.ModelInputEstimate
                    .toolBytes(it.description, it.inputSchema.toString())
            }
        assertEquals(15920L, withoutNames)
        assertEquals(16944L, withoutNames + tools.sumOf { it.name.value.length })
        replaceCatalog("catalog", tools)
        assertEquals(listOf(search), discovery.visible("session", registry.all()))
    }

    @Test
    fun newSearchRetainsValidHistoryAndMissDoesNotDiscardTheWorkset() {
        catalog()
        val found = discovery.search("session", "catalog", 16)
        assertEquals(16, found.size)
        val selected = discovery.search("session", "tool_499", 1)
        assertTrue(discovery.visible("session", registry.all()).containsAll(found + selected))
        val current = discovery.visible("session", registry.all())
        assertTrue(discovery.search("session", "nonexistent-capability-zz", 8).isEmpty())
        assertEquals(current, discovery.visible("session", registry.all()))
    }

    @Test
    @Suppress("MaxLineLength") // Exact schema fixture.
    fun schemaReplacementInvalidatesExposureAndContractsWithoutExpandingAuthority() {
        val old = catalog()
        val selected = discovery.search("session", "tool_499", 1).single()
        val changed =
            selected.copy(
                inputSchema =
                    Json
                        .parseToJsonElement(
                            """{"type":"object","properties":{"changed":{"type":"boolean"}},"additionalProperties":false}""",
                        ).jsonObject,
            )
        replaceCatalog("catalog", old.map { if (it == selected) changed else it })
        assertFalse(selected.contractHash == changed.contractHash)
        assertEquals(listOf(search), discovery.visible("session", registry.all()))
        assertEquals(listOf(changed), discovery.search("session", "tool_499", 1))
        replaceCatalog("catalog", emptyList())
        assertEquals(listOf(search), discovery.visible("session", registry.all()))
        assertTrue(discovery.search("session", "catalog", 8).isEmpty())
    }

    @Test
    fun modeAdmissionStillRemovesPreviouslyDiscoveredRemoteTool() {
        catalog()
        discovery.search("session", "tool_499", 1)
        assertEquals(listOf(search), discovery.visible("session", listOf(search)))
    }

    @Test
    fun smallCatalogRemainsAvailableAndRestartDropsLargeCatalogWindow() {
        catalog(3)
        assertEquals(4, discovery.visible("session", registry.all()).size)
        catalog()
        discovery.search("session", "tool_499", 1)
        assertEquals(listOf(search), McpToolDiscovery(registry).visible("session", registry.all()))
    }

    @Test
    fun cancelledSearchCannotLoadSchemas() {
        catalog()
        val call =
            ExecutableToolCall(
                "call",
                "tools_search",
                search.version.value.toString(),
                Json.parseToJsonElement("""{"query":"tool_499"}""").jsonObject,
                ExecutionTargetType.LOCAL_ANDROID,
                Instant.now().plusSeconds(5),
                object : CancelSignal {
                    override fun isCancelled() = true
                },
                "session",
            )
        assertEquals(ToolExecutorResult.Cancelled, registry.executor(search.name, search.version).execute(call))
        assertEquals(listOf(search), discovery.visible("session", registry.all()))
    }

    @Test
    fun successfulDiscoveryReportsNextRequestAndDoesNotMutateTheCurrentToolTable() {
        catalog()
        val currentRequest = discovery.visible("session", registry.all())
        val call =
            ExecutableToolCall(
                "search",
                search.name.value,
                search.version.value.toString(),
                Json.parseToJsonElement("""{"query":"tool_499"}""").jsonObject,
                ExecutionTargetType.LOCAL_ANDROID,
                Instant.now().plusSeconds(5),
                object : CancelSignal {
                    override fun isCancelled() = false
                },
                "session",
            )
        val result = registry.executor(search.name, search.version).execute(call) as ToolExecutorResult.Completed
        assertEquals(
            kotlinx.serialization.json.JsonPrimitive("next_request"),
            result.output.jsonObject["schemaAvailability"],
        )
        assertFalse(currentRequest.contains(remote(499)))
        assertTrue(discovery.visible("session", registry.all()).contains(remote(499)))
        assertEquals(
            com.helix.tools.framework.ToolSchemaValidation.Valid,
            com.helix.tools.framework.ToolSchemaValidator
                .validate(search.outputSchema, result.output),
        )
    }

    @Test
    fun foreignMcpNamesAreOnlySearchHintsAndCannotBypassSourceAvailability() {
        val action =
            search.copy(
                name = ToolName("mcp_catalog_create_issue"),
                description = "Create an issue",
                operationClass = ToolOperationClass.NETWORK,
                origin = ToolOrigin.McpOrigin("catalog", "2025-03-26", "a".repeat(64)),
            )
        registerFixture(action)
        assertEquals(listOf(action), discovery.search("session", "mcp__plugin_github_github__create_issue", 8))
        val denied = McpToolDiscovery(registry) { _, tool -> tool != action }
        assertTrue(denied.search("session", "mcp__github__create_issue", 8).isEmpty())
        assertTrue(registry.all().none { it.name.value == "mcp__github__create_issue" })
    }

    @Test fun optionalBuiltInIsSearchableAndOnlyLoadedForItsSession() {
        val local = search.copy(name = ToolName("files_archive"), description = "Create a file archive")
        registerFixture(local)
        assertFalse(local in discovery.visible("session", registry.all()))
        assertEquals(listOf(local), discovery.search("session", "archive", 8))
        assertTrue(local in discovery.visible("session", registry.all()))
        assertFalse(local in discovery.visible("other", registry.all()))
        assertFalse(local in discovery.visible("session", listOf(search)))
        assertFalse(local in McpToolDiscovery(registry).visible("session", registry.all()))
    }

    @Test fun builtInDisableAppliesToSearchAndLoadedSchemas() {
        val local = search.copy(name = ToolName("files_archive"), description = "Create a file archive")
        registerFixture(local)
        var enabled = true
        val filtered = McpToolDiscovery(registry) { _, descriptor -> descriptor != local || enabled }
        assertEquals(listOf(local), filtered.search("session", "archive", 8))
        enabled = false
        assertTrue(filtered.search("session", "archive", 8).isEmpty())
        assertFalse(local in filtered.visible("session", registry.all(), setOf(local.name.value)))
    }

    @Test fun defaultsAndDiscoveryShareOneBoundedSurface() {
        val locals = (0 until 100).map { search.copy(name = ToolName("local_tool_$it")) }
        locals.forEach(::registerFixture)
        val defaults = setOf("local_tool_0", "local_tool_1")
        assertEquals(3, discovery.visible("session", registry.all(), defaults).size)
        discovery.search("session", "local_tool_99", 1)
        assertEquals(4, discovery.visible("session", registry.all(), defaults).size)
        assertEquals(3, discovery.visible("other", registry.all(), defaults).size)
    }

    @Test fun currentModeAdmissionAlsoBoundsSearchResults() {
        val local = search.copy(name = ToolName("files_archive"), description = "Create a file archive")
        registerFixture(local)
        discovery.visible("session", listOf(search))
        assertTrue(discovery.search("session", "archive", 8).isEmpty())
        discovery.visible("session", listOf(search, local))
        assertEquals(listOf(local), discovery.search("session", "archive", 8))
        discovery.visible("session", listOf(search))
        assertFalse(local in discovery.visible("session", listOf(search)))
    }

    // HXA-209 B3: the shared availability predicate (the same lambda the execution entry uses)
    // also gates discovery — a disabled remote tool is neither searchable nor visible, and a
    // disable that lands after a search removes it from the loaded window.
    @Test
    fun aDisabledToolLeavesSearchAndTheVisibleWindow() {
        catalog(3)
        val disabled = mutableSetOf("mcp_catalog_tool_1")
        val filtered = McpToolDiscovery(registry) { _, descriptor -> descriptor.name.value !in disabled }
        assertEquals(
            listOf("mcp_catalog_tool_0", "mcp_catalog_tool_2"),
            filtered.search("session", "catalog", 8).map { it.name.value },
        )
        val visible = filtered.visible("session", registry.all()).map { it.name.value }
        assertFalse(visible.contains("mcp_catalog_tool_1"))
        assertTrue(visible.contains("tools_search"))
        assertTrue(visible.containsAll(listOf("mcp_catalog_tool_0", "mcp_catalog_tool_2")))
    }

    @Test
    fun aDisableLandedAfterSearchRemovesItFromTheWindow() {
        catalog(3)
        val disabled = mutableSetOf<String>()
        val filtered = McpToolDiscovery(registry) { _, descriptor -> descriptor.name.value !in disabled }
        assertEquals(listOf("mcp_catalog_tool_1"), filtered.search("session", "tool_1", 1).map { it.name.value })
        disabled += "mcp_catalog_tool_1"
        val visible = filtered.visible("session", registry.all()).map { it.name.value }
        assertFalse(
            "a disable landing after the search removes the loaded window",
            visible.contains("mcp_catalog_tool_1"),
        )
        assertTrue(visible.contains("tools_search"))
        assertTrue(filtered.search("session", "tool_1", 1).isEmpty())
    }
}
