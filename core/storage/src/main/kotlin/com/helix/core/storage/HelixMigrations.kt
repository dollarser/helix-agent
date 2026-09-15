package com.helix.core.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object HelixMigrations {
    /**
     * v17 -> v18 (HXA-200 Gap 2, ADR-0052 point 1; 2026-09-15 mechanism addendum): adds the trusted
     * tool-registration/upgrade baseline — `tool_registration_baseline` (one row per trusted tool
     * identity, its `firstSeenVersionCode`) and `tool_baseline_meta` (the single-row
     * `foundingVersionCode` anchor). Both are additive and EMPTY on upgrade: no rows are seeded, so
     * an unconfigured, un-upgraded user has no baseline and every tool stays UNSET (the original
     * behavior — "new tool default ASK" is never bootstrapped by a migration). Only the trusted app
     * registration path writes these tables at runtime.
     */
    val MIGRATION_17_18 =
        object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tool_registration_baseline` (" +
                        "`sourceRef` TEXT NOT NULL, " +
                        "`toolName` TEXT NOT NULL, " +
                        "`firstSeenVersionCode` INTEGER NOT NULL, " +
                        "`updatedAtEpoch` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`sourceRef`, `toolName`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tool_baseline_meta` (" +
                        "`id` TEXT NOT NULL, " +
                        "`foundingVersionCode` INTEGER NOT NULL, " +
                        "`updatedAtEpoch` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
            }
        }

    /**
     * v16 -> v17 (HXA-200, ADR-0052: user tool-approval preferences): adds the
     * `tool_approval_preferences` table — one standing user setting per (tool identity, scope),
     * written only by the user application service, never the model/Skill/MCP/A2A. Additive and
     * empty on upgrade: no ALLOW rows are seeded, so an unconfigured user keeps their original
     * behavior (ADR-0052 point 1). Mirrors the canonical Room v17 DDL for
     * [ToolApprovalPreferenceEntity]; the table has no foreign keys (a preference is keyed by the
     * stable tool source + name, not a relation to a session/turn/tool-call) and the unique index
     * makes "reset to default" a delete, not a fourth state (point 5).
     */
    val MIGRATION_16_17 =
        object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tool_approval_preferences` (" +
                        "`id` TEXT NOT NULL, " +
                        "`sourceRef` TEXT NOT NULL, " +
                        "`toolName` TEXT NOT NULL, " +
                        "`preference` TEXT NOT NULL, " +
                        "`scopeKind` TEXT NOT NULL, " +
                        "`scopeRef` TEXT NOT NULL, " +
                        "`contractHash` TEXT NOT NULL, " +
                        "`revision` INTEGER NOT NULL, " +
                        "`createdAtEpoch` INTEGER NOT NULL, " +
                        "`updatedAtEpoch` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_tool_approval_preferences_key` " +
                        "ON `tool_approval_preferences` (`sourceRef`, `toolName`, `scopeKind`, `scopeRef`)",
                )
            }
        }

    /**
     * v15 -> v16 (doc 02 §8; artifact-scope identity): `artifacts.relativePath` now stores the
     * file's FULL `scope:` model reference (e.g. `scope:app:output/a2a/...`) instead of a bare
     * scope-relative path, so the artifact identity carries its real scope through the unique
     * key, every lookup, open, and invalidation check. The pre-v16 sink always resolved a row
     * under the app scope, so every existing file physically lives under the app-scope root —
     * normalizing each legacy bare row to `scope:app:<path>` makes it addressable by the
     * scope-carrying readers (which parse via `FileScopePath.fromModelReference`).
     * The schema version identifies the stored format: EVERY v15 row is bare.
     * A legal legacy filename can start with `scope:` (even `scope:other:output/x.txt`), so
     * content-based detection would either lose that artifact or redirect it to another scope.
     * Room applies this migration once; it is not a normalizer for mixed-version input.
     * Rebuild the unique index inside Room's migration transaction: a prefixed destination
     * can equal another row's OLD bare path during UPDATE, although final keys are distinct.
     */
    val MIGRATION_15_16 =
        object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX index_artifacts_sessionId_relativePath")
                db.execSQL(
                    "UPDATE artifacts SET relativePath = 'scope:app:' || relativePath",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX index_artifacts_sessionId_relativePath " +
                        "ON artifacts(sessionId, relativePath)",
                )
            }
        }

    /**
     * v14 -> v15 (doc 02 §8: `artifacts`): adds `turnId` — the turn that last wrote the file —
     * so the artifact surface can show which session/turn produced each file instead of only
     * background turns. Nullable: rows registered before v15 and registrations without turn
     * context (A2A task artifacts) keep NULL. Purely additive; no data change.
     */
    val MIGRATION_14_15 =
        object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE artifacts ADD COLUMN turnId TEXT")
            }
        }

    /**
     * v13 -> v14 (research doc section 4.4): the per-request system-prompt record. Adds
     * `promptFingerprint` + `promptSections` to `model_calls` — the fingerprint of the exact
     * prompt bytes a request sent and the redacted section list (provenance + content hash,
     * never content) — so a request can be traced for which sources and which version of
     * content it used. Both columns are nullable: calls committed before v14 and compaction
     * summary calls keep NULL.
     */
    val MIGRATION_13_14 =
        object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE model_calls ADD COLUMN promptFingerprint TEXT")
                db.execSQL("ALTER TABLE model_calls ADD COLUMN promptSections TEXT")
            }
        }

    /**
     * v12 -> v13 (research doc section 34; HX2-01 §2e): the persistent submit-dedup receipt.
     * Adds `clientRequestId` + `inputFingerprint` to `turns` — the turn row becomes the durable
     * receipt for the client-request id that started it (created atomically with the turn, so a
     * restart can no longer let the same id re-start a second turn) — and a UNIQUE index on
     * `clientRequestId`, the DB-level backstop so one id can never back a second turn. Both columns
     * are nullable: rows created before v13 keep NULL (never matched by a non-null re-drive query)
     * and NULLs stay distinct under the unique index, so the backstop does not collide across them.
     */
    val MIGRATION_12_13 =
        object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE turns ADD COLUMN clientRequestId TEXT")
                db.execSQL("ALTER TABLE turns ADD COLUMN inputFingerprint TEXT")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_turns_clientRequestId` " +
                        "ON `turns` (`clientRequestId`)",
                )
            }
        }

    val MIGRATION_11_12 =
        object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE goals SET state = 'PAUSED', finishReason = NULL WHERE state = 'BLOCKED' AND " +
                        "(SELECT outcome FROM goal_runs WHERE goalId = goals.id " +
                        "ORDER BY startedAt DESC, rowid DESC LIMIT 1) = 'BLOCKED(EVIDENCE_BINDING_REQUIRED)'",
                )
            }
        }

    val MIGRATION_10_11 =
        object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE turns ADD COLUMN resultCollectedAt INTEGER")
                db.execSQL("ALTER TABLE turns ADD COLUMN pauseRequestedAt INTEGER")
                db.execSQL(
                    "UPDATE goals SET state = 'BLOCKED' WHERE state = 'PAUSED' AND " +
                        "(SELECT outcome FROM goal_runs WHERE goalId = goals.id " +
                        "ORDER BY startedAt DESC, rowid DESC LIMIT 1) LIKE 'BUDGET_EXHAUSTED(%'",
                )
            }
        }

    val MIGRATION_9_10 =
        object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN directoryRef TEXT")
            }
        }

    /** v8 -> v9: durable usage reservations, including unknown outcomes after process death. */
    val MIGRATION_8_9 =
        object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `goal_usage_reservations` (" +
                        "`id` TEXT NOT NULL, `runId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`reservedTokens` INTEGER NOT NULL, `reservedMillis` INTEGER NOT NULL, " +
                        "`state` TEXT NOT NULL, `chargedTokens` INTEGER, `chargedMillis` INTEGER, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`runId`) REFERENCES `goal_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_goal_usage_reservations_runId` " +
                        "ON `goal_usage_reservations` (`runId`)",
                )
            }
        }

    /** v7 -> v8: explicit Goal run/Turn association; existing Turns are never guessed into Goals. */
    val MIGRATION_7_8 =
        object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `goal_turn_bindings` (" +
                        "`turnId` TEXT NOT NULL, `runId` TEXT NOT NULL, PRIMARY KEY(`turnId`), " +
                        "FOREIGN KEY(`turnId`) REFERENCES `turns`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`runId`) REFERENCES `goal_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_goal_turn_bindings_runId` ON `goal_turn_bindings` (`runId`)",
                )
            }
        }

    /**
     * v1 -> v2 (HXA-034, approval hash and one-time consumption):
     *
     * - `approvals.argsHash` is renamed to `bindingHash`: from v2 it stores the full
     *   ApprovalBinding hash (tool/version/schema/scope/session/target/UI token/args),
     *   not just the argument digest;
     * - `approvals.expiresAt` bounds the approval window; every migrated row is written
     *   with `0` (fail closed: a v1 approval can never mint or consume a proof after
     *   migration).
     *
     * Renamed via copy-and-swap, NOT `ALTER TABLE ... RENAME COLUMN`: that statement
     * needs SQLite >= 3.25, which Android only ships from API 30, and `minSdk` is 29 —
     * on an API 29 (Android 10) device the column-rename form throws
     * `near "COLUMN": syntax error` and the v1 -> v2 upgrade crashes on launch.
     * The new table mirrors the canonical Room v2 DDL for [ApprovalEntity]
     * (`bindingHash` + `expiresAt INTEGER NOT NULL`) so the result is byte-identical to
     * a fresh v2 create; the `approvals` index is recreated after the rename.
     */
    val MIGRATION_1_2 =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE `approvals_new` (" +
                        "`id` TEXT NOT NULL, " +
                        "`toolCallId` TEXT NOT NULL, " +
                        "`bindingHash` TEXT NOT NULL, " +
                        "`decision` TEXT, " +
                        "`decidedAt` INTEGER, " +
                        "`consumedAt` INTEGER, " +
                        "`expiresAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`toolCallId`) REFERENCES `tool_calls`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "INSERT INTO `approvals_new` " +
                        "(`id`, `toolCallId`, `bindingHash`, `decision`, `decidedAt`, " +
                        "`consumedAt`, `expiresAt`) " +
                        "SELECT `id`, `toolCallId`, `argsHash`, `decision`, `decidedAt`, " +
                        "`consumedAt`, 0 FROM `approvals`",
                )
                // Dropping the old table also drops its indexes.
                db.execSQL("DROP TABLE `approvals`")
                // Plain table rename (not column rename) is supported on every SQLite.
                db.execSQL("ALTER TABLE `approvals_new` RENAME TO `approvals`")
                db.execSQL(
                    "CREATE UNIQUE INDEX `index_approvals_toolCallId` " +
                        "ON `approvals` (`toolCallId`)",
                )
            }
        }

    /**
     * v2 -> v3 (HXA-037, structured user questions with one-time receipts, doc 11 section 4):
     * adds the `interaction_receipts` table. No existing table changes; the table is
     * additive and empty on upgrade. A receipt row is deliberately NOT foreign-keyed to
     * `tool_calls` (a question is not a tool call) and carries no approval fields —
     * answering it can never create or consume an Approval Proof.
     */
    val MIGRATION_2_3 =
        object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `interaction_receipts` (" +
                        "`id` TEXT NOT NULL, " +
                        "`sessionId` TEXT NOT NULL, " +
                        "`turnId` TEXT NOT NULL, " +
                        "`requestId` TEXT NOT NULL, " +
                        "`version` INTEGER NOT NULL, " +
                        "`questionSummary` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`expiresAt` INTEGER NOT NULL, " +
                        "`answerHash` TEXT, " +
                        "`answeredAt` INTEGER, " +
                        "PRIMARY KEY(`id`))",
                )
            }
        }

    /**
     * v3 -> v4 (HXA-049, ADR-0014: message-attachment relation):
     * adds the `message_attachments` table — the ordered relation from a message to the
     * immutable Artifact snapshot it was bound to (`boundSha256` for fail-closed re-verification
     * on send, confirm and retry). Additive and empty on upgrade, mirroring the canonical Room v4 DDL
     * for [MessageAttachmentEntity]; both FKs (message, artifact) cascade and the
     * (messageId, ordinal) index is unique.
     */
    val MIGRATION_3_4 =
        object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `message_attachments` (" +
                        "`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`messageId` TEXT NOT NULL, " +
                        "`artifactId` TEXT NOT NULL, " +
                        "`ordinal` INTEGER NOT NULL, " +
                        "`purpose` TEXT NOT NULL, " +
                        "`boundSha256` TEXT NOT NULL, " +
                        "FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`artifactId`) REFERENCES `artifacts`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_message_attachments_messageId_ordinal` " +
                        "ON `message_attachments` (`messageId`, `ordinal`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_message_attachments_artifactId` " +
                        "ON `message_attachments` (`artifactId`)",
                )
            }
        }

    /**
     * v4 -> v5 (HXA-068, ADR-0005: persistent ADVANCED high-sensitivity egress rules):
     * adds the `high_sensitivity_rules` table — one row per exactly-bound, time-boxed,
     * revocable rule (stable Provider/MCP id + normalized origin + lossless user scope +
     * validity window). Additive and empty on upgrade, mirroring the canonical Room v5 DDL
     * for [HighSensitivityRuleEntity]; the table has no foreign keys (a rule is a standing
     * policy grant, not a relation to a session/turn/tool-call) and no data-category column
     * (a stored rule is always SENSITIVE — the invariant the rule's constructor enforces).
     */
    val MIGRATION_4_5 =
        object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `high_sensitivity_rules` (" +
                        "`id` TEXT NOT NULL, " +
                        "`targetKind` TEXT NOT NULL, " +
                        "`targetId` TEXT NOT NULL, " +
                        "`originFull` TEXT NOT NULL, " +
                        "`scopeEncoded` TEXT NOT NULL, " +
                        "`createdAtEpoch` INTEGER NOT NULL, " +
                        "`expiresAtEpoch` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
            }
        }

    /** v5 -> v6 (HXA-078): disabled A2A config plus bounded Agent Card/Skill snapshots. */
    val MIGRATION_5_6 =
        object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `a2a_agents` (" +
                        "`id` TEXT NOT NULL, " +
                        "`endpointRef` TEXT NOT NULL, " +
                        "`authAlias` TEXT, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`cardHash` TEXT, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `a2a_capabilities` (" +
                        "`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`agentId` TEXT NOT NULL, " +
                        "`interfaceUrl` TEXT NOT NULL, " +
                        "`binding` TEXT NOT NULL, " +
                        "`protocolVersion` TEXT NOT NULL, " +
                        "`skillId` TEXT NOT NULL, " +
                        "`skillHash` TEXT NOT NULL, " +
                        "`inputModes` TEXT NOT NULL, " +
                        "`outputModes` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`agentId`) REFERENCES `a2a_agents`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_a2a_capabilities_agentId_skillId` " +
                        "ON `a2a_capabilities` (`agentId`, `skillId`)",
                )
            }
        }

    /** v6 -> v7 (HXA-079): durable one-local-call-to-one-remote-Task reconciliation. */
    val MIGRATION_6_7 =
        object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `a2a_capabilities` ADD COLUMN `tenant` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `a2a_tasks` (" +
                        "`toolCallId` TEXT NOT NULL, " +
                        "`agentId` TEXT NOT NULL, " +
                        "`skillId` TEXT NOT NULL, " +
                        "`cardHash` TEXT NOT NULL, " +
                        "`skillHash` TEXT NOT NULL, " +
                        "`inputHash` TEXT NOT NULL, " +
                        "`interfaceUrl` TEXT NOT NULL, " +
                        "`binding` TEXT NOT NULL, " +
                        "`protocolVersion` TEXT NOT NULL, " +
                        "`tenant` TEXT, " +
                        "`taskId` TEXT, " +
                        "`contextId` TEXT, " +
                        "`lastEventSequence` INTEGER NOT NULL, " +
                        "`lastEventId` TEXT, " +
                        "`state` TEXT NOT NULL, " +
                        "`deliveryState` TEXT NOT NULL, " +
                        "`updatedAtEpochMillis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`toolCallId`), " +
                        "FOREIGN KEY(`toolCallId`) REFERENCES `tool_calls`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_a2a_tasks_taskId` ON `a2a_tasks` (`taskId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_a2a_tasks_state` ON `a2a_tasks` (`state`)")
            }
        }
}
