package com.helix.app.plugin

import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InstalledPluginOwnershipTest {
    private val key = SkillKey(SkillSource.USER_IMPORTED, "guide", "a".repeat(64), "conn-owner")
    private val record =
        InstalledPlugin(
            "conn-owner",
            "Owner",
            "CLAUDE",
            "b".repeat(64),
            emptyList(),
            listOf(key),
            emptyList(),
            "local:owner",
            1,
            true,
        )

    @Test fun manifestRoundTripPreservesExactInstallationOwner() {
        assertEquals(record, decode(encode(record)))
    }

    @Test fun restoredManifestCannotBorrowAnotherOwnersSnapshotOrAnIndependentInstall() {
        val valid = encode(record)
        listOf("conn-other", "").forEach { invalid ->
            val forged = valid.replace("\"ownerId\":\"conn-owner\"", "\"ownerId\":\"$invalid\"")
            assertThrows(IllegalArgumentException::class.java) { decode(forged) }
        }
    }

    @Test fun publicationCannotClaimBuiltInOrAnotherPackageContent() {
        assertThrows(IllegalArgumentException::class.java) {
            record.copy(skills = listOf(key.copy(source = SkillSource.BUILT_IN)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            record.copy(skills = listOf(key.copy(ownerId = "conn-other")))
        }
    }

    @Test fun nativeRecordsKeepHostSkillsOutsideImportedManifest() {
        val native =
            record.copy(
                source = "BUNDLED_PLUGIN",
                skills = emptyList(),
                native = NativePluginComponent("browser", "browser"),
            )
        assertEquals(native, decode(encode(native)))
        assertThrows(IllegalArgumentException::class.java) {
            record.copy(native = NativePluginComponent("browser", "browser"))
        }
    }
}
