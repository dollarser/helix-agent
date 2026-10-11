package com.helix.extensions.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SkillInvocationTest {
    @Test fun durableLookupDoesNotHoldTheContentLock() {
        val root = Files.createTempDirectory("skill-lock")
        val document =
            SkillLoader().loadBuiltIn(
                "---\nname: workflow\ndescription: Workflow\ndisable-model-invocation: true\n---\nGuide",
                "workflow",
            )
        val worker =
            java.util.concurrent.Executors
                .newSingleThreadExecutor()
        lateinit var repository: SkillRepository
        repository =
            SkillRepository(
                root.resolve("s"),
                root.resolve("state"),
                root.resolve("trash"),
                listOf(document),
                userActivations = { _, _ ->
                    val key =
                        worker
                            .submit<SkillKey> { repository.list().single().key }
                            .get(2, java.util.concurrent.TimeUnit.SECONDS)
                    listOf(UserSkillActivation(key, ""))
                },
            )
        try {
            val key = repository.list().single().key
            assertTrue(repository.read(key, "s", "t").body.isNotBlank())
            assertEquals(1, repository.listForModel("s", "t").size)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    @Suppress("LongMethod", "NestedBlockDepth") // Four combinations share the same boundary assertions.
    fun fourCombinationsEnforceRealReadAndUserEntrySeparately() {
        for (user in listOf(true, false)) {
            for (model in listOf(true, false)) {
                val root = Files.createTempDirectory("invocation")
                val document =
                    SkillLoader().loadBuiltIn(
                        "---\nname: workflow\ndescription: Workflow\nuser-invocable: $user\n" +
                            "disable-model-invocation: ${!model}\n---\nGuide",
                        "workflow",
                    )
                var activations = emptyList<UserSkillActivation>()
                val repository =
                    SkillRepository(
                        root.resolve(
                            "snapshots",
                        ),
                        root.resolve("state"),
                        root.resolve("trash"),
                        builtIns = listOf(document),
                        userActivations = {
                            session,
                            turn,
                            ->
                            if (session ==
                                "s" &&
                                turn == "t"
                            ) {
                                activations
                            } else {
                                emptyList()
                            }
                        },
                    )
                val key = repository.list().single().key
                assertEquals(
                    user,
                    repository
                        .list()
                        .single()
                        .invocation.userInvocable,
                )
                assertEquals(model, repository.listForModel("s", "t").isNotEmpty())
                assertTrue(repository.inspect(key).body.contains("Guide"))
                if (model) {
                    repository.read(key, "s", "t")
                } else {
                    assertThrows(IllegalArgumentException::class.java) { repository.read(key, "s", "t") }
                }
                if (user) {
                    activations = listOf(repository.prepareUserActivation(key, "s", "参数"))
                    repository.read(key, "s", "t")
                    if (!model) {
                        assertThrows(IllegalArgumentException::class.java) { repository.read(key, "s", "other-t") }
                        assertThrows(IllegalArgumentException::class.java) { repository.read(key, "other-s", "t") }
                        assertThrows(IllegalArgumentException::class.java) {
                            repository.readResource(key, "references/a.md", "other-s", "t")
                        }
                    }
                    repository.setEnabled(key, false, SkillEnablementScope.SESSION, "s")
                    assertThrows(IllegalArgumentException::class.java) { repository.read(key, "s", "t") }
                } else {
                    assertThrows(IllegalArgumentException::class.java) {
                        repository.prepareUserActivation(key, "s", "")
                    }
                }
            }
        }
    }

    @Test fun strictFlagsDefaultsAndUnsupportedExecutionAreNotFlattened() {
        assertEquals(SkillInvocationPolicy(), SkillInvocationPolicy.parse(emptyMap()))
        listOf("true", 1, null).forEach { value ->
            assertThrows(InvalidSkillException::class.java) {
                SkillInvocationPolicy.parse(
                    mapOf("user-invocable" to value),
                )
            }
            assertThrows(InvalidSkillException::class.java) {
                SkillInvocationPolicy.parse(
                    mapOf("disable-model-invocation" to value),
                )
            }
        }
        val root = Files.createTempDirectory("unsupported-skill")
        val document =
            SkillLoader().loadBuiltIn(
                "---\nname: workflow\ndescription: Workflow\ncontext: fork\n---\nGuide",
                "workflow",
            )
        val repository =
            SkillRepository(root.resolve("s"), root.resolve("state"), root.resolve("trash"), listOf(document))
        val key = repository.list().single().key
        assertTrue(repository.listForModel("s", "t").isEmpty())
        assertThrows(IllegalArgumentException::class.java) { repository.prepareUserActivation(key, "s", "") }
        assertThrows(IllegalArgumentException::class.java) { repository.read(key) }
        assertTrue(repository.inspect(key).body.isNotBlank())
    }
}
