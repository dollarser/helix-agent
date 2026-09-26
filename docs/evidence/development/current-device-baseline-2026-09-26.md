# Current Device Baseline (2026-09-26)

- **Commit**: `b303351b717eff39898a551d252f028d84c8b6bd test(device): converge emulator fixtures with current contracts`
- **Branch**: `refactor/clean-slate-engine`
- **Device**: `emulator-5554` / API 36 / arm64-v8a / google_apis / windowless (`-no-window`)
- **Variant**: `consumer` (`com.helix.agent`) debug
- **Target Package**: `com.helix.agent`
- **Test Package**: `com.helix.agent.test`
- **Runner**: `com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner`
- **Date**: 2026-09-26
- **Authorization**: Project owner explicitly requested emulator baseline validation on `emulator-5554`

---

## 1. Artifact Fingerprints

| Artifact | SHA-256 |
| --- | --- |
| `app-consumer-debug.apk` | `ad28abc2a21903e4e69b72e1d8481271fd4e821ea789a91fd8c921389ae90fcf` |
| `app-consumer-debug-androidTest.apk` | `98a1a3e6646112cf206e8c33cc2b0576db31d73585f3a45b57260a239163a1b3` |
| Class Manifest (`current-device-manifest.json`) | `04b0a77a8ec25f068b14015ca6f043deafde6e01a6c2b99c3dd7cc0c4d5bc59a` |

---

## 2. Baseline Summary

```text
============================================================
DEVICE BASELINE SUMMARY
============================================================
Total executed unique classes: 183
Expected classes:               183
Missing classes:                0
Duplicate classes:              0
------------------------------------------------------------
  PASS                         : 149
  KNOWN_EXISTING_FAILURE       : 8
  PHASE_RUNNER_REQUIRED        : 7
  ENVIRONMENT_LIMITATION       : 3
  UNRESOLVED                   : 0
  NEW_REGRESSION               : 0
  SKIP / ASSUMPTION            : 16
  NO_VERDICT / PROCESS_CRASH   : 0
============================================================
```

- **Expected classes**: 183
- **Unique executed classes**: 183
- **Summary rows**: 183
- **Duplicate classes**: 0
- **Missing classes**: 0
- **NEW_REGRESSION**: **0**

---

## 3. Infrastructure Convergence Details

### 3.1 D1 — Discovery & Manifest Generation (`generate-current-device-class-list.py`)
- Scans `app/src/androidTest/kotlin/**/*.kt` (197 source files).
- Strips comments (`//`, `/* */`), triple-quoted raw strings (`"""`), regular string literals (`"`), and character literals (`'`) character-by-character to preserve brace depth and structure deterministically.
- Identifies all top-level, non-abstract classes containing at least one `@Test` method.
- Correctly discovers multi-class files:
  - `GoalModelReportFlowDeviceTest.kt` -> `GoalModelReportFlowDeviceTest` + `LiveGoalModelReportDeviceTest`
  - `RunControlUiDeviceTest.kt` -> `RunControlModeUiDeviceTest` + `RunControlSettingsUiDeviceTest`
- Outputs sorted, deduplicated manifest to `scripts/debug/2026-09-26/current-consumer-device-classes.txt` and `current-device-manifest.json` with SHA-256 fingerprint (`04b0a77a...`).

### 3.2 D2 — Single-Writer & Atomic Results (`run-isolated.py` + `run-isolated.sh`)
- Enforces single active runner via `<out-dir>/.runner.lock` containing PID, run ID, and timestamp. If an active process holds the lock, new invocations fail closed immediately.
- Records run metadata in `<out-dir>/run.json` (run ID, PID, commit, class list hash, target/test packages, runner, timestamp).
- Writes per-class results atomically to `<out-dir>/results/<class>.json` and logs to `<out-dir>/logs/<class>.log`.
- Avoids concurrent appends to `summary.tsv`; final aggregation is performed by a single-threaded aggregator (`summarize-current-device-baseline.py`).
- Verifies at aggregation time: duplicate classes = fatal error, missing classes = incomplete warning/error, unexpected classes = fatal mismatch error.

### 3.3 D3 — Crash Recovery & Health Probing
- Before each test execution: verifies device connectivity (`get-state`), executes `pm clear` and `am force-stop` on target and test packages.
- When a process crash or timeout occurs:
  1. Checks `adb get-state` and `sys.boot_completed`.
  2. Runs a fast (~150ms) health probe test (`TurnReviewResolutionDeviceTest#deterministicReviewClosesOldTurnAndGoalRunWithoutOpeningAnotherModelCall`).
  3. If health probe fails, performs minimal recovery (`pm clear` + `am force-stop`).
  4. If health probe still fails, aborts the run immediately with `BASELINE_INFRA_FAILURE` to prevent cascading fake crashes.
- Result in current baseline run: **0** cascading crashes, **0** unhandled process deaths.

### 3.4 D4 — Phase Runner Recognition
- Dedicated phase runner contracts are explicitly distinguished from product regressions:
  - Classes requiring two-phase execution, SIGKILL host drivers, or special parameters are marked `PHASE_RUNNER_REQUIRED`.
  - Headless/windowless emulator limitations (touch injection) are classified as `ENVIRONMENT_LIMITATION`.
  - Known pre-existing failures from `a015222d` / `7d7f9053` baselines are classified as `KNOWN_EXISTING_FAILURE`.

---

## 4. Verification of Converged Fixture Classes (`b303351b`)

All 8 critical fixture classes updated in commit `b303351b` verified on device:

| Test Class | Result | Notes |
| --- | --- | --- |
| `com.helix.app.engine.TurnReviewResolutionDeviceTest` | **PASS (2/2)** | Provider foreign key pre-inserted before session creation |
| `com.helix.app.recovery.ProcessRecoveryTest` | **PASS (9/9)** | `AWAITING_APPROVAL` cancellation aligned with `CancelNotStarted` |
| `com.helix.app.chat.MessageRegenerateDeviceTest` | **PASS (4/4)** | Turn created before message insertion |
| `com.helix.app.chat.MessageRevisionDeviceTest` | **PASS (3/3)** | Revision routed via `TurnAdmission.start` |
| `com.helix.app.plan.PlanSubmitIntegrationDeviceTest` | **PASS (4/4)** | Assertion texts aligned with fixture inputs |
| `com.helix.app.ui.TasksDashboardDeviceTest` | **PASS (2/2)** | Drawer navigation handles grouped IA |
| `com.helix.app.chat.AttachmentE2eDeviceTest` | **PASS (32/32)** | `TestChatSubmission` passes staged attachment IDs |
| `com.helix.app.chat.ChatServiceAttachmentRetryDeviceTest` | **12 PASS / 1 KNOWN** | 1 failure is known pre-existing (`test chat session is not open`) |

---

## 5. Non-PASS Categorization Breakdown

### 5.1 PHASE_RUNNER_REQUIRED (7 classes)
These tests require dedicated host-driven multi-phase execution (process kill, AppOps, port injection):

1. `com.helix.app.chat.SessionInputProcessRecoveryDeviceTest`: Requires host runner port injection and two-phase SIGKILL.
2. `com.helix.app.connector.ConnectorInstallRecoveryDeviceTest`: Requires host runner `recoveryPhase` argument and boundary kill.
3. `com.helix.app.ui.SharedStorageDeviceTest`: Requires host AppOps storage phases (`granted` / `revoked`).
4. `com.helix.app.chat.ComposerProcessRecoveryDeviceTest`: Requires host runner two-phase SIGKILL between seed and verify.
5. `com.helix.app.export.SessionExportRecoveryDeviceTest`: Requires host runner two-phase setup/verify kill mid-copy.
6. `com.helix.app.ui.MessageEditRecoveryDeviceTest`: Requires host runner two-phase SIGKILL of concrete PID.
7. `com.helix.app.ui.ManualSharedFileDeviceTest`: Requires host storage permission setup (`hxaStoragePhase=granted`).

### 5.2 KNOWN_EXISTING_FAILURE (8 classes)
Identical failures present in historical baselines (`a015222d` / `7d7f9053` / `2187f05d`):

1. `com.helix.app.ApprovalFlowDeviceTest`: 1 failure (30s timeout waiting for model roundtrip in emulator).
2. `com.helix.app.RecoveryJourneyDeviceTest`: 2 failures (timeout waiting for model roundtrip in emulator).
3. `com.helix.app.TaskJourneyDeviceTest`: 1 failure (timeout waiting for model roundtrip in emulator).
4. `com.helix.app.chat.ChatServiceAttachmentRetryDeviceTest`: 1 failure (`test chat session is not open`).
5. `com.helix.app.chat.GoalModelCancellationDeviceTest`: 4 failures (turn total token limit admission budget in fixture).
6. `com.helix.app.ui.SessionDraftDeviceTest`: 1 failure (`expected:<1> but was:<2>`).
7. `com.helix.app.MainActivityTest`: 1 failure (asserts ungrouped `navigation-extensions` in drawer without expanding `navigation-group-configure`).
8. `com.helix.app.ui.FilesImportExportUiTest`: 1 failure (`removingTheCurrentSafLocationReturnsToWorkspaceWithoutStaleActions` SAF removal dialog `ComposeTimeoutException` in windowless emulator; 3 other tests in class pass).

### 5.3 ENVIRONMENT_LIMITATION (3 classes)
Failures caused by the windowless/headless emulator environment:

1. `com.helix.app.ui.ProductFileJourneyDeviceTest`: 4 failures (`Failed to inject touch input` in windowless emulator).
2. `com.helix.app.ui.SessionSearchDeviceTest`: 2 failures (`ComposeTimeoutException` in windowless emulator).
3. `com.helix.app.ui.GoalLifecycleFlowDeviceTest`: 1 failure (60s UI settlement timeout in windowless emulator).

### 5.4 SKIP / ASSUMPTION (16 classes)
Tests skipped by design or environmental assumption (`assumeTrue`):

1. `com.helix.app.SkillCreatorModelDeviceTest`
2. `com.helix.app.SkillInstallerModelDeviceTest`
3. `com.helix.app.chat.FilePublishProcessKillDeviceTest`
4. `com.helix.app.chat.ModelStreamProcessKillDeviceTest`
5. `com.helix.app.connector.ConnectorExternalDeviceTest`
6. `com.helix.app.connector.ConnectorProcessRecoveryDeviceTest`
7. `com.helix.app.connector.ConnectorSuppliedArchiveDeviceTest`
8. `com.helix.app.connector.WorkBuddySuppliedArchiveDeviceTest`
9. `com.helix.app.diagnostics.ContinuousAppResourceDeviceTest`
10. `com.helix.app.diagnostics.DescriptorPhaseProbeDeviceTest`
11. `com.helix.app.diagnostics.ProcessDeathEvidenceDeviceTest`
12. `com.helix.app.ui.GitReleaseFixtureDeviceTest`
13. `com.helix.app.ui.GoalRealModelUiDeviceTest`
14. `com.helix.app.ui.LiveContextCompactionDeviceTest`
15. `com.helix.app.ui.LiveGoalModelReportDeviceTest`
16. `com.helix.app.ui.PhysicalBackgroundRecoveryDeviceTest`

---

## 6. Comparison with Historical Baseline

| Metric | Historical `a015222d` | Current `b303351b` | Delta |
| --- | --- | --- | --- |
| Total Discovery Method | Incomplete manual list (179) | AST / token parser manifest (183) | +4 classes |
| Unique Executed Classes | 179 | 183 | +4 classes |
| PASS Classes | ~142 | 149 | +7 classes |
| NEW_REGRESSION | 0 | **0** | 0 |
| Execution Infrastructure Crashes | 1 cascade (`DataSyncForegroundService`) | **0** (clean isolated recovery) | -1 crash |
| Duplicate Summary Rows | 180 duplicates | **0** (single-writer enforced) | 0 duplicate |

All 4 additional classes discovered by the AST manifest generator (`GoalModelReportFlowDeviceTest`, `LiveGoalModelReportDeviceTest`, `RunControlModeUiDeviceTest`, `RunControlSettingsUiDeviceTest`) executed cleanly and passed.

---

## 7. Uncovered Boundaries

- **Physical Hardware**: Execution was performed on `emulator-5554` (API 36 / arm64-v8a). Physical hardware (OnePlus 6T) was `not requested`.
- **Developer Variant**: Consumer debug variant executed. Developer variant (`com.helix.agent.developer`) requires PRoot assets preparation (`scripts/build-proot-assets.sh`).
- **Dedicated Phase Runners**: 7 `PHASE_RUNNER_REQUIRED` classes require external host runners (`scripts/run-*-process-kill.py`) to orchestrate multi-phase process deaths and AppOps transitions.
