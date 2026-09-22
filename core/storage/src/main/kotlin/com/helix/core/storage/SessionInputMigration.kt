package com.helix.core.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** HXA-216 is additive: old history is neither queued nor replayed during upgrade. */
internal object SessionInputMigration {
    val MIGRATION_25_26 =
        object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE composer_drafts ADD COLUMN delivery TEXT NOT NULL DEFAULT 'QUEUE'")
                db.execSQL("ALTER TABLE composer_drafts ADD COLUMN expectedTurnId TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS session_inputs (" +
                        "inputId TEXT NOT NULL PRIMARY KEY, schemaVersion INTEGER NOT NULL, sessionId TEXT NOT NULL, " +
                        "sequence INTEGER NOT NULL, delivery TEXT NOT NULL, expectedTurnId TEXT, " +
                        "revision INTEGER NOT NULL, textRef TEXT NOT NULL, textBytes INTEGER NOT NULL, " +
                        "providerId TEXT NOT NULL, modelId TEXT NOT NULL, " +
                        "mode TEXT NOT NULL, configurationFingerprint TEXT NOT NULL, state TEXT NOT NULL, " +
                        "consumedTurnId TEXT, messageId TEXT, requestModelCallId TEXT, blockedReason TEXT, " +
                        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                        "FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(expectedTurnId) REFERENCES turns(id) ON UPDATE NO ACTION ON DELETE NO ACTION, " +
                        "FOREIGN KEY(consumedTurnId) REFERENCES turns(id) ON UPDATE NO ACTION ON DELETE NO ACTION, " +
                        "FOREIGN KEY(messageId) REFERENCES messages(id) ON UPDATE NO ACTION ON DELETE NO ACTION, " +
                        "FOREIGN KEY(requestModelCallId) REFERENCES model_calls(id) " +
                        "ON UPDATE NO ACTION ON DELETE NO ACTION)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX index_session_inputs_sessionId_sequence " +
                        "ON session_inputs(sessionId, sequence)",
                )
                db.execSQL(
                    "CREATE INDEX index_session_inputs_sessionId_state_delivery_sequence " +
                        "ON session_inputs(sessionId, state, delivery, sequence)",
                )
                listOf("expectedTurnId", "consumedTurnId", "requestModelCallId", "textRef").forEach { column ->
                    db.execSQL("CREATE INDEX index_session_inputs_$column ON session_inputs($column)")
                }
                db.execSQL("CREATE UNIQUE INDEX index_session_inputs_messageId ON session_inputs(messageId)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS session_input_attachments (" +
                        "inputId TEXT NOT NULL, ordinal INTEGER NOT NULL, " +
                        "artifactId TEXT NOT NULL, boundSha256 TEXT NOT NULL, " +
                        "PRIMARY KEY(inputId, ordinal), " +
                        "FOREIGN KEY(inputId) REFERENCES session_inputs(inputId) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(artifactId) REFERENCES artifacts(id) " +
                        "ON UPDATE NO ACTION ON DELETE NO ACTION)",
                )
                val attachmentArtifactIndex =
                    "CREATE INDEX index_session_input_attachments_artifactId " +
                        "ON session_input_attachments(artifactId)"
                db.execSQL(attachmentArtifactIndex)
                val attachmentInputIndex =
                    "CREATE UNIQUE INDEX index_session_input_attachments_inputId_artifactId " +
                        "ON session_input_attachments(inputId, artifactId)"
                db.execSQL(attachmentInputIndex)
            }
        }
}
