package com.helix.core.storage

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolEffectReviewDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ToolCallReviewMigrationDeviceTest {
    @Test
    fun migration28To29CreatesToolCallReviewsTableAndPreservesOldUnresolvedCalls() {
        val name = "tool-call-review-migration-${UUID.randomUUID()}.db"
        val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HelixDatabase::class.java)

        helper.createDatabase(name, 28).use { db ->
            db.execSQL(
                "INSERT INTO sessions(id,title,providerId,modelId,createdAt,archivedAt,directoryRef) " +
                    "VALUES ('s1','Session 1',NULL,NULL,0,NULL,NULL)",
            )
            db.execSQL(
                "INSERT INTO turns(id,sessionId,state,stepCount,startedAt,clientRequestId) " +
                    "VALUES ('t1','s1','INTERRUPTED',1,0,'req-01')",
            )
            // Existing parked calls before migration
            db.execSQL(
                "INSERT INTO tool_calls(id,turnId,callId,name,version,argsJson,argsHash,state) " +
                    "VALUES ('c1','t1','call-01','bash','1.0','{}','hash1','${ToolCallState.INTERRUPTED.name}')",
            )
            db.execSQL(
                "INSERT INTO tool_calls(id,turnId,callId,name,version,argsJson,argsHash,state) " +
                    "VALUES ('c2','t1','call-02','edit','1.0','{}','hash2','${ToolCallState.NEEDS_REVIEW.name}')",
            )
        }

        helper.runMigrationsAndValidate(name, 29, true, HelixDatabase.MIGRATION_28_29).use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")

            // 1. Verify old parked calls exist but have NO review records (unresolved)
            db.query("SELECT COUNT(*) FROM tool_call_reviews").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }

            // 2. Insert a review record for c1
            val reviewedAt = 1774300000000L
            db.execSQL(
                "INSERT INTO tool_call_reviews(toolCallId, decision, reviewedAt) VALUES (?, ?, ?)",
                arrayOf<Any>("c1", ToolEffectReviewDecision.CONFIRMED_APPLIED.name, reviewedAt),
            )

            db.query("SELECT decision, reviewedAt FROM tool_call_reviews WHERE toolCallId = 'c1'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(ToolEffectReviewDecision.CONFIRMED_APPLIED.name, cursor.getString(0))
                assertEquals(reviewedAt, cursor.getLong(1))
            }

            // 3. Verify CASCADE delete
            db.execSQL("DELETE FROM tool_calls WHERE id = 'c1'")
            db.query("SELECT COUNT(*) FROM tool_call_reviews WHERE toolCallId = 'c1'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }
}
