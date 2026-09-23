package com.helix.core.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object ConnectorMigration {
    val MIGRATION_26_27 =
        object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS connector_installations (" +
                        "id TEXT NOT NULL PRIMARY KEY, identity TEXT NOT NULL, revision INTEGER NOT NULL, " +
                        "manifest TEXT NOT NULL, defaultSelected INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX index_connector_installations_identity ON connector_installations(identity)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS connector_skill_ownership (" +
                        "source TEXT NOT NULL, name TEXT NOT NULL, hash TEXT NOT NULL, independent INTEGER NOT NULL, " +
                        "PRIMARY KEY(source, name, hash))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS session_connectors (" +
                        "sessionId TEXT NOT NULL, connectorId TEXT NOT NULL, PRIMARY KEY(sessionId, connectorId), " +
                        "FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE TABLE IF NOT EXISTS connector_endpoints (id TEXT NOT NULL PRIMARY KEY)")
                db.execSQL("CREATE TABLE IF NOT EXISTS connector_catalog_state (id TEXT NOT NULL PRIMARY KEY)")
            }
        }
}
