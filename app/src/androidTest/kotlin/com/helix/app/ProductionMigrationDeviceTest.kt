package com.helix.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.ApprovalDecision
import com.helix.core.policy.ApprovalProof
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.InteractionReceiptRepository.ReceiptRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The PRODUCTION database entry points upgrade an on-disk v1 schema (HXA-014 era) all the way
 * to the current version. `Room` does not auto-discover migrations: without the
 * `ALL_MIGRATIONS` registration inside `HelixStorage` this exact test throws
 * `IllegalStateException: A migration from 1 to 3 is required` — fresh installs and every
 * other device test use a brand-new database and can never expose the gap.
 *
 * Kept in the APP module on purpose: it drives the same `HelixStorage.create`/`open` that
 * [AppContainer] builds, not a fixture with its own `addMigrations`.
 */
@RunWith(AndroidJUnit4::class)
class ProductionMigrationDeviceTest {
    private lateinit var helper: MigrationTestHelper
    private lateinit var contentDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Fresh database file per run (installed APKs keep app data between runs).
        context.deleteDatabase(V1_DB)
        helper =
            MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                com.helix.core.storage.HelixDatabase::class.java,
            )
        contentDir = File(context.filesDir, "production-migration-content")
        contentDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .deleteDatabase(V1_DB)
        contentDir.deleteRecursively()
    }

    @Test
    fun openUpgradesAV1DatabaseThroughTheProductionBuilder() {
        val digest1 = digestOf("x")
        val v1 = helper.createDatabase(V1_DB, 1)
        v1.execSQL(
            "INSERT INTO approvals (id, toolCallId, argsHash, decision, decidedAt, consumedAt) " +
                "VALUES ('prod-mig-1', 'toolcall-prod-1', '$digest1', 'APPROVED', 10, 20)",
        )
        v1.close()

        // The EXACT production entry: no addMigrations at the call site — the registration
        // lives inside HelixStorage (the gap the review found).
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storage = HelixStorage.open(context, V1_DB, contentDir)
        try {
            // Row survived both migrations; the v1 APPROVED row is expired (fail closed):
            // the typed consume path throws, it can never mint or spend a proof.
            val proof = ApprovalProof("prod-mig-1", digest1)
            val notConsumable =
                runCatching { storage.approvals.consume(proof, 99L, 99L) }.isFailure
            assertEquals("a migrated v1 approval must be un-mintable (expired)", true, notConsumable)
            // The v3 table exists and is usable end to end.
            val now = System.currentTimeMillis()
            val entity =
                storage.interactionReceipts.open(
                    ReceiptRequest(
                        id = "prod-mig-rc",
                        sessionId = "s",
                        turnId = "t",
                        requestId = "r",
                        version = 1,
                        questionSummary = "升级后 receipt 可用",
                        createdAt = now,
                        ttlMillis = 60_000,
                    ),
                )
            assertEquals("PENDING", entity.state)
        } finally {
            storage.close()
        }
    }

    @Test
    fun theMigratedRowKeepsItsDecisionAndIsPermanentlyExpired() {
        val digest2 = digestOf("y")
        val v1 = helper.createDatabase(V1_DB, 1)
        v1.execSQL(
            "INSERT INTO approvals (id, toolCallId, argsHash, decision, decidedAt, consumedAt) " +
                "VALUES ('prod-mig-2', 'toolcall-prod-2', '$digest2', 'DENIED', 10, NULL)",
        )
        v1.close()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storage = HelixStorage.open(context, V1_DB, contentDir)
        try {
            val entity = storage.approvals.resolve("prod-mig-2")
            assertEquals(ApprovalDecision.DENIED.name, entity.decision)
            // expiresAt = 0: permanently expired, and mint is refused on that ground.
            val mint = storage.approvals.mint("prod-mig-2", 9_999_999L)
            assertEquals(
                "a v1 row can never mint after migration",
                true,
                mint is com.helix.core.policy.ApprovalMintOutcome.Rejected,
            )
        } finally {
            storage.close()
        }
    }

    private companion object {
        const val V1_DB = "production-migration.db"

        /** A stable 64-hex sha256 digest for fixture rows (the entity stores hex, never free text). */
        fun digestOf(seed: String): String =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(seed.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
