package com.helix.app.maintenance

import com.helix.app.proot.ExecutionOwnershipStore
import com.helix.core.storage.PreviewDatabaseResetRequired
import com.helix.tools.framework.ExecutionOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File

class PreviewPrivateStateGateTest {
    @get:Rule val temporary = TemporaryFolder()
    private var mobile = listOf("invalid-old-format")
    private var databaseOld = false
    private var deletes = 0
    private var rebootProof = false
    private var failClear = false
    private var mobileValid = false
    private var runtimeActive = false
    private val root get() = temporary.root

    private fun gate() =
        PreviewPrivateStateGate(
            root,
            {
                if (!databaseOld) {
                    null
                } else {
                    PreviewDatabaseResetRequired {
                        databaseOld = false
                        deletes++
                    }
                }
            },
            { mobile },
            { mobile.isEmpty() || mobileValid },
            {
                if (failClear) error("write failed")
                mobile = emptyList()
            },
            { rebootProof },
            { runtimeActive },
        )

    private fun emptyOld(): File =
        File(root, "execution-admission/owner").apply {
            parentFile.mkdirs()
            DataOutputStream(outputStream()).use {
                it.writeInt(2)
                it.writeInt(0)
            }
        }

    @Test fun discoversResidualStateAfterDatabaseWasAlreadyClearedAndDoesNotResetOnRead() {
        val file = emptyOld()
        val plan = requireNotNull(gate().inspect())
        assertEquals(setOf(PreviewResetSection.TERMINAL, PreviewResetSection.MOBILE_USE), plan.sections)
        assertTrue(file.exists())
        assertTrue(mobile.isNotEmpty())
        plan.confirm()
        assertFalse(file.exists())
        assertTrue(mobile.isEmpty())
        assertNull(gate().inspect())
    }

    @Test fun changingAnyTargetAbortsBeforeAnyDeletion() {
        val file = emptyOld()
        databaseOld = true
        val plan = requireNotNull(gate().inspect())
        mobile = listOf("changed")
        assertThrows(IllegalStateException::class.java) { plan.confirm() }
        assertEquals(0, deletes)
        assertTrue(file.exists())
    }

    @Test fun partialFailureLeavesDatabaseUntilRemainingStepsCanBeReviewedAndRetried() {
        emptyOld()
        databaseOld = true
        failClear = true
        val plan = requireNotNull(gate().inspect())
        assertThrows(IllegalStateException::class.java) { plan.confirm() }
        assertEquals(0, deletes)
        failClear = false
        requireNotNull(gate().inspect()).confirm()
        assertEquals(1, deletes)
        assertNull(gate().inspect())
    }

    @Test fun nonemptyIdentityRequiresLaterBootAndPreservesUnknownOutcomeEvidence() {
        mobile = emptyList()
        val file = File(root, "execution-admission/owner")
        val owner = ExecutionOwnership.Owner("original", "generation")
        assertTrue(ExecutionOwnershipStore(file).update(emptySet(), setOf(owner)))
        databaseOld = true
        val blocked = requireNotNull(gate().inspect())
        assertTrue(blocked.requiresRestart)
        assertThrows(IllegalStateException::class.java) { blocked.confirm() }
        assertTrue(file.exists())
        val original = file.readBytes()
        rebootProof = true
        requireNotNull(gate().inspect()).confirm()
        assertFalse(file.exists())
        val retired = requireNotNull(File(root, "preview-retired-terminal").listFiles()).single()
        assertTrue(original.contentEquals(retired.readBytes()))
    }

    @Test fun resetNeverTouchesLinuxDocumentsModelsOrUnrelatedPreferences() {
        val preserved =
            listOf("runtime/home/keep.txt", "workspaces/app/work/keep.md", "models/keep.bin")
                .map {
                    File(root, it).apply {
                        parentFile.mkdirs()
                        writeText("synthetic")
                    }
                }
        emptyOld()
        requireNotNull(gate().inspect()).confirm()
        preserved.forEach { assertEquals("synthetic", it.readText()) }
    }

    @Test fun validConfigurationAndControlStateAreNotResetOnOrdinaryStartup() {
        mobileValid = true
        val file = File(root, "execution-admission/owner")
        assertTrue(ExecutionOwnershipStore(file).update(emptySet(), setOf(ExecutionOwnership.Owner("live", "current"))))
        assertNull(gate().inspect())
        assertTrue(file.exists())
        assertTrue(mobile.isNotEmpty())
    }

    @Test fun activeRuntimeOrLateStartCannotLoseControlRecords() {
        val file = emptyOld()
        runtimeActive = true
        assertTrue(requireNotNull(gate().inspect()).requiresRestart)
        runtimeActive = false
        val reviewed = requireNotNull(gate().inspect())
        runtimeActive = true
        assertThrows(IllegalStateException::class.java) { reviewed.confirm() }
        assertTrue(file.exists())
        assertTrue(mobile.isNotEmpty())
    }

    @Test fun incompatibleNonemptyStateAloneNeedsRebootAndNeverClaimsACommandOutcome() {
        mobile = emptyList()
        val file = emptyOld()
        DataOutputStream(file.outputStream()).use {
            it.writeInt(2)
            it.writeInt(1)
        }
        assertTrue(requireNotNull(gate().inspect()).requiresRestart)
        rebootProof = true
        requireNotNull(gate().inspect()).confirm()
        assertTrue(requireNotNull(File(root, "preview-retired-terminal").listFiles()).isNotEmpty())
        assertNull(gate().inspect())
    }

    @Test fun symlinkControlDirectoryCannotExpandResetScope() {
        val outside = temporary.newFolder("outside")
        java.nio.file.Files
            .createSymbolicLink(File(root, "execution-admission").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { gate().inspect() }
    }

    @Test fun bootProofNeedsSameFingerprintAndStrictlyLaterBoot() {
        var boot: Int? = 5
        val records = mutableMapOf<String, Int>()
        val witness = PreviewBootWitness({ boot }, records::get) { key, value -> records[key] = value }
        assertFalse(witness.provesStopped("original"))
        assertFalse(witness.provesStopped("original"))
        boot = 6
        assertTrue(witness.provesStopped("original"))
        assertFalse(witness.provesStopped("changed"))
        boot = null
        assertFalse(witness.provesStopped("original"))
    }
}
