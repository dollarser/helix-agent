"""Historical signatures and required host phases; never blanket pass exemptions."""

# Known Phase Runner classes that require host phases / process kill / port injection
KNOWN_PHASE_RUNNER_CLASSES = {
    "com.helix.app.chat.MemoryProcessRecoveryDeviceTest": "Requires recoveryPhase setup and actual process death before reading the saved PID and memory",
    "com.helix.app.localmodel.ModelPublicationRecoveryDeviceTest": "Requires recoveryPhase setup and process death during model publication",
    "com.helix.app.localmodel.FirstSuccessJourneyDeviceTest": "Requires P4 host driver, explicitly supplied local model and two-phase process death",
    "com.helix.app.MainAppCombinedSoakDeviceTest": "Requires EV-04 host runner to judge soak-done.json, cycles and duration; JUnit completion is not task success",
    "com.helix.app.chat.WorkspaceProcessRecoveryDeviceTest": "Requires fixture setup and actual process death before verification",
    "com.helix.app.files.WorkspaceBackupRecoveryDeviceTest": "Requires backup-boundary setup and actual process death before verification",
    "com.helix.app.chat.SessionInputProcessRecoveryDeviceTest": "Requires host runner port injection and two-phase SIGKILL",
    "com.helix.app.connector.ConnectorInstallRecoveryDeviceTest": "Requires host runner recoveryPhase argument and boundary kill",
    "com.helix.app.ui.SharedStorageDeviceTest": "Requires host AppOps storage phases (granted/revoked)",
    "com.helix.app.chat.ComposerProcessRecoveryDeviceTest": "Requires host runner two-phase SIGKILL between seed and verify",
    "com.helix.app.export.SessionExportRecoveryDeviceTest": "Requires host runner two-phase setup/verify kill mid-copy",
    "com.helix.app.ui.MessageEditRecoveryDeviceTest": "Requires host runner two-phase SIGKILL of concrete PID",
    "com.helix.app.ui.ManualSharedFileDeviceTest": "Requires host storage permission setup (hxaStoragePhase)",
}

# Historical failures are matched by class *and* exact-enough failure signature. A class name
# alone is never an exemption: if the failure count or message changes, it becomes NEW_REGRESSION.
KNOWN_EXISTING_FAILURES = {
    "com.helix.app.chat.ChatServiceAttachmentRetryDeviceTest": {
        "failures": 1,
        "required": ("test chat session is not open",),
    },
    "com.helix.app.ApprovalFlowDeviceTest": {
        "failures": 1,
        "required": ("production state must settle",),
    },
    "com.helix.app.RecoveryJourneyDeviceTest": {
        "failures": 2,
        "required": (
            "the production state must settle",
            "ComposeTimeoutException: Condition still not satisfied after 10000 ms",
        ),
    },
    "com.helix.app.TaskJourneyDeviceTest": {
        "failures": 1,
        "required": ("production state must settle",),
    },
    "com.helix.app.chat.GoalModelCancellationDeviceTest": {
        "failures": 4,
        "required": ("TURN_TOTAL_TOKEN_LIMIT",),
    },
    "com.helix.app.ui.SessionDraftDeviceTest": {
        "failures": 1,
        "required": ("expected:<1> but was:<2>",),
    },
    "com.helix.app.ui.FilesImportExportUiTest": {
        "failures": 1,
        "required": (
            "removingTheCurrentSafLocationReturnsToWorkspaceWithoutStaleActions",
            "ComposeTimeoutException: Condition still not satisfied after 30000 ms",
        ),
    },
}

# Environment limitations receive the same signature discipline; a different failure in the same
# class is not hidden behind the historical headless-emulator label.
KNOWN_ENVIRONMENT_LIMITATIONS = {
    "com.helix.app.ui.ProductFileJourneyDeviceTest": {
        "failures": 4,
        "required": ("Failed to inject touch input",),
    },
    "com.helix.app.ui.SessionSearchDeviceTest": {
        "failures": 2,
        "required": (
            "openingAResultCancelsTheSearch",
            "clearingTheSearchRestoresTheSessionList",
            "ComposeTimeoutException: Condition still not satisfied after 10000 ms",
        ),
    },
    "com.helix.app.ui.GoalLifecycleFlowDeviceTest": {
        "failures": 1,
        "required": (
            "humanMessagePreemptsWithoutResettingGoalOrBudget",
            "Timed out waiting for 60000 ms",
        ),
    },
}
