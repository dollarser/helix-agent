package com.helix.core.storage

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class ConnectorMigrationDeviceTest {
    @Test
    @Suppress("NestedBlockDepth") // migration assertions keep database/cursor resources scoped
    fun migrationPreservesSessionsAndAddsOnlyEmptyConnectorFacts() {
        val name = "connector-migration-${UUID.randomUUID()}.db"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HelixDatabase::class.java)
        try {
            helper.createDatabase(name, 26).use { db ->
                db.execSQL(
                    "INSERT INTO sessions(id,title,providerId,modelId,createdAt,archivedAt,directoryRef) " +
                        "VALUES ('old','Old',NULL,NULL,0,NULL,NULL)",
                )
            }
            helper.runMigrationsAndValidate(name, 27, true, HelixDatabase.MIGRATION_26_27).use { db ->
                db.query("SELECT title FROM sessions WHERE id = 'old'").use { cursor ->
                    check(cursor.moveToFirst())
                    assertEquals("Old", cursor.getString(0))
                }
                listOf(
                    "connector_installations",
                    "connector_skill_ownership",
                    "session_connectors",
                    "connector_catalog_state",
                    "connector_endpoints",
                ).forEach { table ->
                    db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                        check(cursor.moveToFirst())
                        assertEquals(0, cursor.getInt(0))
                    }
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
