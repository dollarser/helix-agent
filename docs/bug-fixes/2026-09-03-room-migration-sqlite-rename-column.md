# Bug Fix: Room v1→v2 migration used `RENAME COLUMN`, which crashes on API 29 (SQLite < 3.25)

Status: fixed
Date: 2026-09-03
Related HXA: HXA-034, HXA-048
Affected modules: `core:storage`

## Problem

`HelixDatabase.MIGRATION_1_2` (introduced by HXA-034) renamed `approvals.argsHash` to
`approvals.bindingHash` with:

```sql
ALTER TABLE approvals RENAME COLUMN argsHash TO bindingHash
```

`ALTER TABLE ... RENAME COLUMN` requires SQLite ≥ 3.25. Android only ships that version from
API 30 (Android 11); the project's `minSdk` is **29** (`build.gradle.kts`, `app/build.gradle.kts`).
On an API 29 (Android 10) device the v1→v2 upgrade throws
`android.database.sqlite.SQLiteException: near "COLUMN": syntax error` and the app crashes
on launch for any existing user still on Room schema v1.

Discovered during HXA-048 device verification: both `ProductionMigrationDeviceTest` cases
failed on the `Helix_API_29` emulator while passing on `Helix_API_36`.

## Impact

Any real Android 10 (API 29) user whose on-disk database is still schema v1 hits a fatal
upgrade crash at app launch — the database cannot be opened, so the app is unusable until the
database is wiped (losing local data). It is a production data-loss path, not test-only.

## Root cause

The migration was written against the newest SQLite the author's test device exposed. Room
does not enforce a minimum SQLite level for `Migration.execSQL`; the statement is passed
verbatim to the platform `SQLiteConnection`. `RENAME COLUMN` is a 3.25 feature, and
`ALTER TABLE ... ADD COLUMN` (the other statement in the same migration) is fine on 3.22 —
so only the column-rename form was incompatible. No JVM/Robolectric test could catch it: the
JVM `:core:storage:test` suite does not execute the migration, and the only migration test
before HXA-048 ran on the API 36 emulator (newer SQLite), where the statement is valid.

## Fix and invariants

`MIGRATION_1_2` now performs a copy-and-swap that works on SQLite 3.22 (API 29):

1. `CREATE TABLE approvals_new (…)`: the full canonical v2 `approvals` DDL, but with the
   column named `bindingHash` (instead of `argsHash`) and `expiresAt INTEGER NOT NULL` —
   byte-identical to what Room generates for the v2 `ApprovalEntity`.
2. `INSERT INTO approvals_new (…, bindingHash, …, expiresAt) SELECT …, argsHash, …, 0
   FROM approvals` — copies every row, renaming the hash column and writing `expiresAt = 0`
   (fail closed: a v1 approval can never mint or consume a proof after migration).
3. `DROP TABLE approvals` — also drops the old table's indexes.
4. `ALTER TABLE approvals_new RENAME TO approvals` — a plain **table** rename, supported on
   every SQLite version (unlike a column rename).
5. `CREATE UNIQUE INDEX index_approvals_toolCallId ON approvals (toolCallId)` — recreates the
   unique index that step 3 removed.

Invariants that must keep holding:

- The resulting `approvals` table is the canonical Room v2 DDL (primary key `id`, the
  `toolCallId → tool_calls` FK with `ON DELETE CASCADE`, the unique
  `index_approvals_toolCallId`, `bindingHash NOT NULL`, `expiresAt INTEGER NOT NULL`).
- `argsHash → bindingHash` is the only column-identity change; every other column and all
  row values are preserved verbatim, and every migrated row is permanently expired
  (`expiresAt = 0`).
- No other statement in the codebase may use a SQLite ≥ 3.25-only feature
  (`RENAME COLUMN`, `CREATE INDEX … ON … WHERE`, `ALTER TABLE … RENAME COLUMN`, etc.) while
  `minSdk` is 29. This is a durable constraint for future schema changes.

## Alternatives considered

**Bump `minSdk` to 30.** Rejected: drops the supported Android 10 target and does not repair
the existing broken on-device databases already shipped; the crash still hits any v1 user who
is on Android 10.

**Feature-detect the SQLite version and branch.** Rejected: more code for no benefit — the
copy-and-swap is correct on every SQLite ≥ 3.22, so a single path serves all supported
devices without a runtime version check.

**Keep `RENAME COLUMN` but ship a higher SQLite via `sqlite-android`/desugaring.** Rejected:
adds a native dependency and a large supply-chain surface for a one-time column rename; the
copy-and-swap removes the need entirely.

## Regression verification

The regression is exercised on a real Room/SQLite runtime (JVM cannot reach it). Actual run,
2026-09-03, `./gradlew :app:connectedConsumerDebugAndroidTest` on the two consumer emulators:

- `:core:storage:test` (JVM): `BUILD SUCCESSFUL` (compiles the new `MIGRATION_1_2`; contract
  suite green).
- **API 29 — `Helix_API_29(AVD) - 10`** (`TEST-Helix_API_29*.xml`): both migration cases now
  **PASS** (both were the API 29 failures before this fix):
  - `ProductionMigrationDeviceTest.openUpgradesAV1DatabaseThroughTheProductionBuilder` → PASS
  - `ProductionMigrationDeviceTest.theMigratedRowKeepsItsDecisionAndIsPermanentlyExpired` → PASS

  API 29 total: 47 tests, 8 failures — down from the 10 recorded in the HXA-048 record. The
  8 remaining are unrelated pre-existing issues (`GoalReminderTest` ×2
  `POST_NOTIFICATIONS` grant on an API 29 device; `FilesScreenTest` ×6 `ComposeTimeoutException`
  on the slow emulator under concurrent load).
- **API 36 — `Helix_API_36(AVD) - 16`** (`TEST-Helix_API_36*.xml`): 47 tests, **0 failures** —
  no regression, both migration cases PASS.

The copy-and-swap is also covered on-device by
`RoomMigrationFixtureTest.v1ToV2MigrationRenamesBindingHashAndExpiresLegacyApprovals`
(`:core:storage:connectedDebugAndroidTest`), which asserts the post-migration column is
`bindingHash`, the legacy row is expired, and the unique index survives.

## Residual risk

The API 29 device matrix is still not fully green for reasons unrelated to this fix:
`GoalReminderTest` calls `grantRuntimePermission("POST_NOTIFICATIONS")` (API 33+) without an
SDK guard, and `FilesScreenTest` can time out on the slow API 29 emulator under concurrent
full-suite load. Those are tracked separately and do not affect the migration path. The
migration itself now has no known residual risk on any supported API level ≥ 29.

## Related records

- [HXA-034 完成记录](../completion-records/HXA-034.md)（引入 `MIGRATION_1_2`）
- [HXA-048 完成记录](../completion-records/HXA-048.md)（设备验证发现本缺陷）
- `core/storage/src/main/kotlin/com/helix/core/storage/HelixDatabase.kt`（`MIGRATION_1_2`）
- `app/src/androidTest/kotlin/com/helix/app/ProductionMigrationDeviceTest.kt`（生产入口迁移回归）
- `core/storage/src/androidTest/kotlin/com/helix/core/storage/RoomMigrationFixtureTest.kt`（`v1ToV2MigrationRenamesBindingHashAndExpiresLegacyApprovals`）
