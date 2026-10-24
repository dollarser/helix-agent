package com.helix.extensions.plugin

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolBinding
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class PluginRegistryTest {
    @Test fun unselectedContextDoesNotProbeNativeRuntimeAndSelectionStillRechecksReadiness() {
        val catalog = MemoryNativePluginCatalog()
        val registry = PluginRegistry(ToolRegistry(), catalog)
        val base = plugin("fixture")
        var probes = 0
        var unavailable = false
        registry.register(
            object : HelixPlugin by base {
                override fun registrationError(): String? {
                    probes++
                    return "UNAVAILABLE".takeIf { unavailable }
                }
            },
        )
        repeat(5) { assertTrue(registry.selectedRuntimes("session").isEmpty()) }
        assertEquals(0, probes)
        catalog.selections["session" to "fixture"] = "selection"
        assertEquals(1, registry.selectedRuntimes("session").size)
        unavailable = true
        assertTrue(registry.selectedRuntimes("session").isEmpty())
        assertEquals(2, probes)
        catalog.selections.remove("session" to "fixture")
        assertTrue(registry.selectedRuntimes("session").isEmpty())
        assertEquals(2, probes)
    }

    @Test fun managementPreviewKeepsRulesAndResourcesWithoutSelectionOrPublication() {
        val catalog = MemoryNativePluginCatalog()
        val tools = ToolRegistry()
        val registry = PluginRegistry(tools, catalog)
        val base = plugin("preview")
        val guide = skill("guide").copy(resources = mapOf("references/workflow.md" to "Detailed workflow"))
        registry.register(
            object : HelixPlugin by base {
                override val skills = listOf(guide)
                override val requiredInstructions = "Required rule"
            },
        )
        registry.setEnabled("preview", false)
        val before = tools.snapshot()
        val contents = registry.contents("preview")
        assertEquals("Required rule", contents.requiredInstructions)
        assertEquals("Detailed workflow", contents.skills.single().resources["references/workflow.md"])
        assertTrue(registry.selectedRuntimes("session").isEmpty())
        assertEquals(before, tools.snapshot())
        assertEquals("", registry.contents("missing").requiredInstructions)
    }

    @Test fun bodiesLoadOnlyFromExactDurableActivationsAndRetainMandatoryRules() {
        val catalog = MemoryNativePluginCatalog()
        val registry = PluginRegistry(ToolRegistry(), catalog)
        val base = plugin("fixture")
        val guide = skill("guide")
        registry.register(
            object : HelixPlugin by base {
                override val skills = listOf(guide)
                override val requiredInstructions = "Mandatory rule"
            },
        )
        catalog.selections["s" to "fixture"] = "selection"
        val root =
            java.nio.file.Files
                .createTempDirectory("progressive-content")
        var activations = emptyList<com.helix.extensions.skills.UserSkillActivation>()
        val repository =
            com.helix.extensions.skills.SkillRepository(
                root.resolve("snapshots"),
                root.resolve("state"),
                root.resolve("trash"),
                builtIns = emptyList(),
                sourceAvailable = registry::skillAvailable,
                contributedDocuments = registry::skillDocuments,
                userActivations = { _, _ -> activations },
            )
        val key = repository.list("s").single().key
        val discovered = registry.contentContributions(repository, "s", "turn")
        assertEquals(
            "Mandatory rule",
            discovered.filterIsInstance<PluginContentContribution.RequiredInstructions>().single().text,
        )
        assertEquals(key, discovered.filterIsInstance<PluginContentContribution.SkillCatalog>().single().key)
        assertTrue(discovered.none { it is PluginContentContribution.ActivatedSkill })
        val activation = repository.prepareUserActivation(key, "s", "user argument")
        activations = listOf(activation, activation)
        val activated =
            registry
                .contentContributions(repository, "s", "turn")
                .filterIsInstance<PluginContentContribution.ActivatedSkill>()
                .single()
        assertEquals(key, activated.key)
        assertEquals(guide.body, activated.body)
        assertEquals("user argument", activated.acceptedArguments)
        assertEquals(listOf("fixture"), activated.owners.map { it.pluginId })
        assertEquals(discovered, registry.contentContributions(repository, "s"))
        repository.setEnabled(key, false, com.helix.extensions.skills.SkillEnablementScope.SESSION, "s")
        assertThrows(IllegalArgumentException::class.java) { registry.contentContributions(repository, "s", "turn") }
        activations = emptyList()
        val disabled = registry.contentContributions(repository, "s", "next")
        assertEquals(1, disabled.size)
        assertEquals("Mandatory rule", (disabled.single() as PluginContentContribution.RequiredInstructions).text)
        catalog.selections.clear()
        assertTrue(registry.contentContributions(repository, "s", "next").isEmpty())
        assertThrows(IllegalArgumentException::class.java) { repository.read(key, "s") }
        activations = listOf(activation.copy(key = key.copy(snapshotHash = "missing")))
        assertThrows(IllegalArgumentException::class.java) { registry.contentContributions(repository, "s", "next") }
    }

    @Test fun contributionIdentitySurvivesEqualTextAndSelectionRevocation() {
        val catalog = MemoryNativePluginCatalog()
        val registry = PluginRegistry(ToolRegistry(), catalog)
        listOf("first", "second").forEach { id ->
            val base = plugin(id)
            registry.register(
                object : HelixPlugin by base {
                    override val requiredInstructions = "Same external instruction"
                },
            )
            catalog.selections["s" to id] = "selection-$id"
        }
        val root =
            java.nio.file.Files
                .createTempDirectory("content-identity")
        val repository =
            com.helix.extensions.skills.SkillRepository(
                root.resolve("snapshots"),
                root.resolve("state"),
                root.resolve("trash"),
                builtIns = emptyList(),
            )
        val before =
            registry
                .contentContributions(repository, "s")
                .filterIsInstance<PluginContentContribution.RequiredInstructions>()
        assertEquals(listOf("first", "second"), before.map { it.owner.pluginId })
        assertEquals(1, before.map { it.contentHash }.distinct().size)
        assertTrue(before.all { it.owner.revision > 0 && it.owner.fingerprint.isNotBlank() })
        catalog.selections.remove("s" to "first")
        assertEquals(
            listOf("second"),
            registry
                .contentContributions(repository, "s")
                .filterIsInstance<PluginContentContribution.RequiredInstructions>()
                .map { it.owner.pluginId },
        )
        assertEquals(2, before.size)
        registry.setEnabled("second", false)
        assertTrue(registry.contentContributions(repository, "s").isEmpty())
    }

    @Test fun hostCatalogMetadataIsExplicitAndCannotBeGrantedByImportedFrontmatter() {
        val catalog = MemoryNativePluginCatalog()
        val registry = PluginRegistry(ToolRegistry(), catalog)
        val trusted = skill("different-host-name").copy(hostCatalogued = true)
        val spoofed =
            skill("helix-docs").copy(
                catalogEntry =
                    skill("helix-docs").catalogEntry.copy(
                        source = com.helix.extensions.skills.SkillSource.USER_IMPORTED,
                    ),
                hostCatalogued = true,
                additionalFields = mapOf("hostCatalogued" to true),
            )
        val ordinary = skill("ordinary")
        val importedDirectory =
            java.nio.file.Files
                .createTempDirectory("import-parent")
                .resolve("helix-docs")
        java.nio.file.Files
            .createDirectories(importedDirectory)
        java.nio.file.Files.writeString(
            importedDirectory.resolve("SKILL.md"),
            "---\nname: helix-docs\ndescription: spoof\nhostCatalogued: true\n---\nUntrusted body\n",
        )
        val imported =
            com.helix.extensions.skills.SkillLoader().load(
                importedDirectory,
                com.helix.extensions.skills.SkillSource.USER_IMPORTED,
            )
        assertTrue(!imported.hostCatalogued)
        val root =
            java.nio.file.Files
                .createTempDirectory("host-discovery")
        val repository =
            com.helix.extensions.skills.SkillRepository(
                root.resolve("snapshots"),
                root.resolve("state"),
                root.resolve("trash"),
                builtIns = listOf(trusted, spoofed, ordinary),
            )
        val entries =
            registry
                .contentContributions(repository, "s")
                .filterIsInstance<PluginContentContribution.SkillCatalog>()
        assertEquals(listOf("different-host-name"), entries.map { it.key.name })
        val key = entries.single().key
        repository.setEnabled(key, false, com.helix.extensions.skills.SkillEnablementScope.SESSION, "s")
        assertTrue(registry.contentContributions(repository, "s").isEmpty())
    }

    @Test fun withdrawalPrecedesRetirementAndRepairDoesNotReopenHealthyResources() {
        val tools = ToolRegistry()
        val registry = PluginRegistry(tools, MemoryNativePluginCatalog())
        val base = plugin("fixture")
        var opened = 0
        var closed = 0
        registry.register(
            object : HelixPlugin by base {
                override fun openRegistration(): AutoCloseable {
                    opened++
                    return AutoCloseable {
                        assertEquals(0, tools.snapshot().size)
                        closed++
                    }
                }
            },
        )
        registry.repair("fixture")
        assertEquals(1, opened)
        registry.setEnabled("fixture", false)
        registry.setEnabled("fixture", false)
        assertEquals(1, closed)
        registry.setEnabled("fixture", true)
        assertEquals(2, opened)
        registry.setEnabled("fixture", false)
        assertEquals(2, closed)
    }

    @Test fun healthyRestartAndRepairKeepRevisionButCreateFreshProcessBindingIncarnation() {
        val catalog = MemoryNativePluginCatalog()
        val firstTools = ToolRegistry()
        val first = PluginRegistry(firstTools, catalog)
        first.register(plugin("stable"))
        val originalState = requireNotNull(catalog.find("stable"))
        val originalBinding = firstTools.snapshot().single()

        first.repair("stable")
        assertEquals(originalState.revision, catalog.find("stable")?.revision)
        assertEquals(originalBinding.ref, firstTools.snapshot().single().ref)
        assertEquals(1, catalog.publicationCount)

        val secondTools = ToolRegistry()
        val second = PluginRegistry(secondTools, catalog)
        second.register(plugin("stable"))
        assertEquals(originalState.revision, catalog.find("stable")?.revision)
        val restartedBinding = secondTools.snapshot().single()
        org.junit.Assert.assertNotEquals(originalBinding.ref, restartedBinding.ref)
        assertTrue(second.ready("stable"))
        second.repair("stable")
        assertEquals(originalState.revision, catalog.find("stable")?.revision)
        assertEquals(1, catalog.publicationCount)
    }

    @Test fun unchangedDisabledRestartAndRepairKeepRevisionAndNeverPublishTools() {
        val catalog = MemoryNativePluginCatalog()
        val first = PluginRegistry(ToolRegistry(), catalog)
        first.register(plugin("disabled-stable"))
        val disabled = first.setEnabled("disabled-stable", false)
        val tools = ToolRegistry()
        val restarted = PluginRegistry(tools, catalog)
        restarted.register(plugin("disabled-stable"))
        assertEquals(disabled.revision, catalog.find("disabled-stable")?.revision)
        restarted.repair("disabled-stable")
        assertEquals(disabled.revision, catalog.find("disabled-stable")?.revision)
        assertEquals(false, catalog.find("disabled-stable")?.enabled)
        assertTrue(tools.snapshot().isEmpty())
    }

    @Test fun disabledRepairUpdatesChangedContentAndRemainsIdempotentWithoutEnabling() {
        val catalog = MemoryNativePluginCatalog()
        val tools = ToolRegistry()
        val registry = PluginRegistry(tools, catalog)
        var instructions = "before"
        val base = plugin("disabled-content")
        registry.register(
            object : HelixPlugin by base {
                override val requiredInstructions get() = instructions
            },
        )
        val disabled = registry.setEnabled("disabled-content", false)
        instructions = "after"
        val repaired = registry.repair("disabled-content")
        assertEquals(disabled.revision + 1, repaired.revision)
        assertEquals(false, repaired.enabled)
        assertTrue(tools.snapshot().isEmpty())
        assertEquals(repaired, registry.repair("disabled-content"))
        assertEquals(3, catalog.publicationCount)
    }

    @Test fun contentChangeAdvancesRevisionAndInvalidatesOldBinding() {
        val catalog = MemoryNativePluginCatalog()
        val tools = ToolRegistry()
        val registry = PluginRegistry(tools, catalog)
        val base = plugin("changing")
        registry.register(base)
        val original = tools.snapshot().single().ref
        val previousRevision = requireNotNull(catalog.find("changing")).revision
        registry.repair("changing")
        assertEquals(previousRevision, catalog.find("changing")?.revision)

        registry.repair("changing") // healthy repair is idempotent
        assertEquals(previousRevision, catalog.find("changing")?.revision)
        val changed =
            object : HelixPlugin by base {
                override val requiredInstructions = "changed instructions"
            }
        // A process restart loads the changed host factory against the durable installation.
        val replacementTools = ToolRegistry()
        val replacementRegistry = PluginRegistry(replacementTools, catalog)
        replacementRegistry.register(changed)
        assertEquals(previousRevision + 1, catalog.find("changing")?.revision)
        org.junit.Assert.assertNotEquals(original, replacementTools.snapshot().single().ref)
        assertEquals(1, replacementTools.snapshot().size)
    }

    @Test fun initializationFailureWithdrawsProjectionWithoutClaimingDurableInstallationWasRolledBack() {
        val tools = ToolRegistry()
        val catalog = MemoryNativePluginCatalog()
        val registry = PluginRegistry(tools, catalog)
        val base = plugin("fixture")
        assertThrows(IllegalStateException::class.java) {
            registry.register(
                object : HelixPlugin by base {
                    override fun openRegistration(): AutoCloseable = error("synthetic initialization failure")
                },
            )
        }
        assertEquals(true, catalog.find("fixture")?.enabled)
        assertEquals(false, registry.ready("fixture"))
        assertEquals(0, tools.snapshot().size)
        registry.register(base)
        assertEquals(true, registry.ready("fixture"))
    }

    private fun skill(
        name: String,
        body: String = "Observe before acting",
    ) = com.helix.extensions.skills.SkillLoader().loadBuiltIn(
        "---\nname: $name\ndescription: Workflow guide\n---\n$body",
        name,
    )

    @Test fun zeroToolPluginContentUsesRepositoryAndRevocationCannotBeBypassed() {
        val catalog = MemoryNativePluginCatalog()
        val registry = PluginRegistry(ToolRegistry(), catalog)
        val base = plugin("workflow")
        registry.register(
            object : HelixPlugin {
                override val manifest = base.manifest
                override val skills = listOf(skill("guide"))

                override fun tools() = emptyList<ToolBinding>()
            },
        )
        val root =
            java.nio.file.Files
                .createTempDirectory("zero-tool-skills")
        val repository =
            com.helix.extensions.skills.SkillRepository(
                root.resolve("snapshots"),
                root.resolve("state"),
                root.resolve("trash"),
                contributedDocuments = registry::skillDocuments,
            )
        val key = repository.list().single { it.key.name == "guide" }.key
        assertEquals(true, registry.ready("workflow"))
        assertEquals(false, repository.list("session").single { it.key == key }.enabled)
        catalog.selections["session" to "workflow"] = "selection"
        assertEquals("Observe before acting", repository.read(key, "session").body.trim())
        catalog.selections.clear()
        repository.setEnabled(key, true, com.helix.extensions.skills.SkillEnablementScope.SESSION, "session")
        assertThrows(IllegalArgumentException::class.java) { repository.read(key, "session") }
        assertEquals("Observe before acting", repository.inspect(key).body.trim())
    }

    @Test fun losingBackendBlocksNewSelectionWithoutRevokingAnExistingSelectionOrPublishedTools() {
        val catalog = MemoryNativePluginCatalog()
        val tools = ToolRegistry()
        val registry = PluginRegistry(tools, catalog)
        val base = plugin("fixture")
        var error: String? = null
        registry.register(
            object : HelixPlugin by base {
                override fun configurationError() = error
            },
        )
        registry.validateSelection("fixture")
        catalog.selections["session" to "fixture"] = "original-selection"
        val before = tools.snapshot()
        error = "MOBILE_USE_BACKEND_UNAVAILABLE"
        assertEquals(
            error,
            assertThrows(IllegalStateException::class.java) {
                registry.validateSelection("fixture")
            }.message,
        )
        assertEquals("original-selection", catalog.selections["session" to "fixture"])
        assertEquals(before, tools.snapshot())
        assertEquals(true, registry.ready("fixture"))
        error = null
        registry.validateSelection("fixture")
        assertEquals("original-selection", catalog.selections["session" to "fixture"])
    }

    @Test fun approvalPresentationRequiresTheSelectedCurrentNativePlugin() {
        val catalog = MemoryNativePluginCatalog()
        val registry = PluginRegistry(ToolRegistry(), catalog)
        val base = plugin("fixture")
        val shown = mutableListOf<PluginTaskIdentity>()
        val native =
            object : HelixPlugin by base, PluginApprovalPresentation {
                override fun showApproval(task: PluginTaskIdentity) {
                    shown += task
                }
            }
        registry.register(native)
        val descriptor = base.tools().single().descriptor
        val original = PluginTaskIdentity("a", "turn-a")
        registry.presentApproval(descriptor, original)
        assertEquals(emptyList<PluginTaskIdentity>(), shown)
        catalog.selections["a" to "fixture"] = "selection"
        registry.presentApproval(descriptor, original)
        assertEquals(listOf(original), shown)
        registry.presentApproval(descriptor, PluginTaskIdentity("b", "turn-b"))
        registry.presentApproval(descriptor.copy(description = "stale"), original)
        registry.setEnabled("fixture", false)
        registry.presentApproval(descriptor, original)
        assertEquals(listOf(original), shown)
    }

    @Suppress("LongMethod")
    @Test
    fun contextComesOnlyFromSelectedPublishedPlugins() {
        val catalog = MemoryNativePluginCatalog()
        val registry = PluginRegistry(ToolRegistry(), catalog)
        val base = plugin("fixture")
        val scope =
            com.helix.core.policy.AutomationSessionScope(
                setOf("example.app"),
                emptySet(),
                0,
                java.time.Instant.MAX,
                false,
                "config",
            )
        val contribution =
            object : HelixPlugin by base {
                override val preferredTools = setOf("fixture.fixture")
                override val skills = listOf(skill("guide"))
                override val requiredInstructions = "Mandatory operational rule"
                override val dataOrigin = com.helix.core.policy.DataOrigin.ACCESSIBILITY

                override fun imageScope(sessionId: String) = scope

                override fun scopeFor(
                    descriptor: ToolDescriptor,
                    sessionId: String,
                ) = scope
            }
        registry.register(contribution)
        val root =
            java.nio.file.Files
                .createTempDirectory("plugin-skills")
        val repository =
            com.helix.extensions.skills.SkillRepository(
                root.resolve("snapshots"),
                root.resolve("state"),
                root.resolve("trash"),
                contributedDocuments = registry::skillDocuments,
            )
        val descriptor = base.tools().single().descriptor
        assertEquals(null, registry.executionContext(descriptor, "session"))
        assertEquals(emptySet<String>(), registry.preferredTools("session"))
        assertEquals(emptyList<com.helix.core.policy.UserScope>(), registry.imageScopes("session"))
        catalog.selections["session" to "fixture"] = "selection"
        assertEquals(scope, registry.executionContext(descriptor, "session")?.scope)
        assertEquals(setOf("fixture.fixture"), registry.preferredTools("session"))
        assertEquals(listOf(scope), registry.imageScopes("session"))
        val contributions = registry.contentContributions(repository, "session")
        assertEquals(
            "Mandatory operational rule",
            contributions.filterIsInstance<PluginContentContribution.RequiredInstructions>().single().text,
        )
        assertTrue(contributions.none { it is PluginContentContribution.ActivatedSkill })
        val help =
            contributions.filterIsInstance<PluginContentContribution.SkillCatalog>().single {
                it.key.name ==
                    "helix-docs"
            }
        assertEquals(com.helix.extensions.skills.SkillSource.BUILT_IN, help.key.source)
        assertTrue(help.key.snapshotHash.isNotBlank())
        val key = repository.list("session").single { it.key.name == "guide" }.key
        assertTrue(contributions.filterIsInstance<PluginContentContribution.SkillCatalog>().any { it.key == key })
        repository.setEnabled(key, false, com.helix.extensions.skills.SkillEnablementScope.SESSION, "session")
        val afterDisable = registry.contentContributions(repository, "session")
        assertEquals(
            "Mandatory operational rule",
            afterDisable.filterIsInstance<PluginContentContribution.RequiredInstructions>().single().text,
        )
        assertTrue(afterDisable.none { it is PluginContentContribution.ActivatedSkill })
        assertTrue(help in afterDisable)
        assertTrue(afterDisable.filterIsInstance<PluginContentContribution.SkillCatalog>().none { it.key == key })
        assertThrows(IllegalArgumentException::class.java) { repository.read(key, "session") }
        repository.setEnabled(key, true, com.helix.extensions.skills.SkillEnablementScope.SESSION, "session")
        registry.setEnabled("fixture", false)
        assertEquals(null, registry.executionContext(descriptor, "session"))
        assertEquals(listOf(help), registry.contentContributions(repository, "session"))
        assertEquals(emptySet<String>(), registry.preferredTools("session"))
        registry.setEnabled("fixture", true)
        catalog.selections.clear()
        assertEquals(listOf(help), registry.contentContributions(repository, "session"))
        assertEquals(null, registry.executionContext(descriptor, "session"))
    }

    @Test fun selectionValidationDoesNotMutateRuntimeOrCatalog() {
        val catalog = MemoryNativePluginCatalog()
        val registry = PluginRegistry(ToolRegistry(), catalog)
        val base = plugin("fixture")
        registry.register(
            object : HelixPlugin by base {
                override fun configurationError() = "NOT_CONFIGURED"
            },
        )
        val before = catalog.states.toMap()
        assertEquals("NOT_CONFIGURED", registry.selectionError("fixture"))
        assertEquals("PLUGIN_HOST_COMPONENT_UNAVAILABLE", registry.selectionError("missing"))
        assertEquals(
            "NOT_CONFIGURED",
            assertThrows(IllegalStateException::class.java) {
                registry.validateSelection("fixture")
            }.message,
        )
        assertEquals(before, catalog.states)
        assertEquals(emptyMap<Pair<String, String>, String>(), catalog.selections)
    }

    @Test fun disabledPluginContentsRemainInspectableWithoutPublishingTools() {
        val tools = ToolRegistry()
        val registry = PluginRegistry(tools, MemoryNativePluginCatalog())
        val original = plugin("mobile-use")
        val skill = skill("android-ui-task")
        registry.register(
            object : HelixPlugin {
                override val manifest = original.manifest
                override val skills = listOf(skill)

                override fun tools() = original.tools()
            },
        )
        registry.setEnabled("mobile-use", false)
        assertEquals(listOf(skill), registry.contents("mobile-use").skills)
        assertEquals(
            original.tools().map {
                it.descriptor.name.value
            },
            registry.contents("mobile-use").tools.map { it.first },
        )
        assertEquals(0, tools.all().size)
        assertEquals(false, registry.hasPublishedTools("mobile-use"))
    }

    @Test fun presentationProjectionTracksPublishedToolsWithoutGrantingAuthority() {
        val plugins = PluginRegistry(ToolRegistry(), MemoryNativePluginCatalog())
        assertEquals(false, plugins.hasPublishedTools("mobile-use"))
        plugins.register(plugin("mobile-use"))
        assertEquals(true, plugins.hasPublishedTools("mobile-use"))
        plugins.setEnabled("mobile-use", false)
        assertEquals(false, plugins.hasPublishedTools("mobile-use"))
        plugins.setEnabled("mobile-use", true)
        assertEquals(true, plugins.hasPublishedTools("mobile-use"))
    }

    @Test
    fun registersExactPluginProvenanceIntoExistingToolRegistries() {
        val tools = ToolRegistry()

        val plugins = PluginRegistry(tools, MemoryNativePluginCatalog())
        val plugin = plugin("mobile-use")

        plugins.register(plugin)

        val descriptor = tools.all().single()
        assertEquals(ToolOrigin.PluginOrigin("mobile-use", "0.1.0", "mobile-use"), descriptor.origin)
        assertEquals("mobile-use", plugins.all().single().name)
    }

    @Test
    fun duplicatePluginIdAndMismatchedOriginFailClosed() {
        val tools = ToolRegistry()

        val plugins = PluginRegistry(tools, MemoryNativePluginCatalog())
        plugins.register(plugin("mobile-use"))
        assertThrows(IllegalArgumentException::class.java) { plugins.register(plugin("mobile-use")) }

        val bad = plugin("other", descriptorOrigin = ToolOrigin.PluginOrigin("wrong", "0.1.0", "other"))
        assertThrows(IllegalArgumentException::class.java) { plugins.register(bad) }
    }

    @Test
    fun bindingCollisionKeepsOriginalAndDoesNotPublishPlugin() {
        val tools = ToolRegistry()

        val plugins = PluginRegistry(tools, MemoryNativePluginCatalog())
        val plugin = plugin("mobile-use")
        val binding = plugin.tools().single()
        tools.register(binding.descriptor, binding.executor)
        val before = tools.snapshot()

        assertThrows(IllegalArgumentException::class.java) { plugins.register(plugin) }
        assertEquals(before, tools.snapshot())
        assertEquals(emptyList<PluginManifest>(), plugins.all())
    }

    @Test fun reenableNeverRevivesTheOldBinding() {
        val tools = ToolRegistry()
        val store = MemoryNativePluginCatalog()
        val plugins = PluginRegistry(tools, store)
        plugins.register(plugin("mobile-use"))
        val descriptor = tools.all().single()
        val old = tools.resolveBinding(descriptor.name, descriptor.version)
        plugins.setEnabled("mobile-use", false)
        assertEquals(false, store.find("mobile-use")?.enabled)
        assertEquals(0, tools.all().size)
        plugins.setEnabled("mobile-use", true)
        val fresh = tools.resolveBinding(descriptor.name, descriptor.version)
        org.junit.Assert.assertNotEquals(old.ref, fresh.ref)
        assertEquals(3L, store.find("mobile-use")?.revision)
        assertEquals(null, tools.resolveBinding(old.ref))
    }

    @Test fun restartPreservesDurableDisabledState() {
        val store = MemoryNativePluginCatalog()
        val first = PluginRegistry(ToolRegistry(), store)
        first.register(plugin("mobile-use"))
        first.setEnabled("mobile-use", false)
        val tools = ToolRegistry()
        val reopened = PluginRegistry(tools, store)
        reopened.register(plugin("mobile-use"))
        assertEquals(false, reopened.ready("mobile-use"))
        assertEquals(0, tools.all().size)
        assertEquals("mobile-use", reopened.all().single().name)
    }

    @Test fun failedPublicationDoesNotChangeBindingsOrInstallation() {
        val store = MemoryNativePluginCatalog()
        val tools = ToolRegistry()
        val plugins = PluginRegistry(tools, store)
        plugins.register(plugin("mobile-use"))
        val original = tools.snapshot().single().ref
        store.failPublication = true
        assertThrows(IllegalStateException::class.java) { plugins.setEnabled("mobile-use", false) }
        assertEquals(original, tools.snapshot().single().ref)
        assertEquals(true, store.find("mobile-use")?.enabled)
        assertEquals(1L, store.find("mobile-use")?.revision)
    }

    @Test fun missingProjectionRequiresRepairAndDoesNotReviveOldBinding() {
        val tools = ToolRegistry()
        val catalog = MemoryNativePluginCatalog()
        val plugins = PluginRegistry(tools, catalog)
        plugins.register(plugin("mobile-use"))
        val old = tools.snapshot().single().ref
        tools.replaceOwner(old.owner, emptyList())
        assertEquals(false, plugins.ready("mobile-use"))
        plugins.repair("mobile-use")
        assertEquals(true, plugins.ready("mobile-use"))
        assertEquals(2L, catalog.find("mobile-use")?.revision)
        plugins.repair("mobile-use")
        assertEquals(2L, catalog.find("mobile-use")?.revision)
        assertEquals(null, tools.resolveBinding(old))
    }

    @Test fun repairDoesNotEnableDisabledPlugin() {
        val tools = ToolRegistry()
        val store = MemoryNativePluginCatalog()
        val plugins = PluginRegistry(tools, store)
        plugins.register(plugin("mobile-use"))
        plugins.setEnabled("mobile-use", false)
        plugins.repair("mobile-use")
        assertEquals(false, store.find("mobile-use")?.enabled)
        assertEquals(false, plugins.ready("mobile-use"))
        assertEquals(0, tools.snapshot().size)
    }

    @Test fun duplicateEnableDoesNotInvalidateAnActiveBinding() {
        val tools = ToolRegistry()
        val plugins = PluginRegistry(tools, MemoryNativePluginCatalog())
        plugins.register(plugin("mobile-use"))
        val original = tools.snapshot().single().ref
        plugins.setEnabled("mobile-use", true)
        assertEquals(original, tools.snapshot().single().ref)
    }

    private fun plugin(
        id: String,
        descriptorOrigin: ToolOrigin = ToolOrigin.PluginOrigin(id, "0.1.0", id),
    ): HelixPlugin {
        val manifest =
            PluginManifestReader.parse(
                """
                {
                  "${'$'}schema":"${PluginManifest.AGENT_PLUGINS_V1_SCHEMA}",
                  "name":"$id",
                  "version":"0.1.0",
                  "description":"fixture",
                  "extensions":{"com.helix.agent":{"runtime":"$id"}}
                }
                """.trimIndent().toByteArray(),
            )
        val descriptor =
            ToolDescriptor(
                name = ToolName("fixture.$id"),
                version = ToolVersion(1),
                description = "fixture tool",
                inputSchema = buildJsonObject {},
                outputSchema = buildJsonObject {},
                operationClass = ToolOperationClass.READ_ONLY,
                timeout = 5.seconds,
                maxOutputBytes = 1024,
                requiredCapabilities = emptySet(),
                idempotency = Idempotency.IDEMPOTENT,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                origin = descriptorOrigin,
            )
        val executor =
            object : ToolExecutor {
                override fun execute(call: com.helix.tools.framework.ExecutableToolCall): ToolExecutorResult =
                    ToolExecutorResult.Completed(buildJsonObject {})
            }
        return object : HelixPlugin {
            override val manifest = manifest

            override fun tools() = listOf(ToolBinding(descriptor, executor))
        }
    }

    @Test fun collisionInTheLastPluginToolDoesNotPublishEarlierCandidates() {
        val tools = ToolRegistry()
        val plugins = PluginRegistry(tools, MemoryNativePluginCatalog())
        val source = plugin("mobile-use")
        val binding = source.tools().single()
        tools.register(binding.descriptor.copy(origin = ToolOrigin.BuiltInOrigin), binding.executor)
        val before = tools.snapshot()
        val batch =
            object : HelixPlugin {
                override val manifest = source.manifest

                override fun tools() =
                    listOf(
                        binding.copy(descriptor = binding.descriptor.copy(name = ToolName("new.tool"))),
                        binding,
                    )
            }
        assertThrows(IllegalArgumentException::class.java) { plugins.register(batch) }
        assertEquals(before, tools.snapshot())
        assertEquals(emptyList<PluginManifest>(), plugins.all())
    }
}
