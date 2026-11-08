package com.helix.app

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** Test-only host: never constructs HelixApplication or opens its production container. */
class HelixIsolatedRoomTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader,
        className: String,
        context: Context,
    ): Application {
        check(context.packageName == ISOLATED_PACKAGE) { "Isolated Room tests require the validation APK" }
        return super.newApplication(cl, Application::class.java.name, context)
    }

    override fun onCreate(arguments: Bundle?) {
        check(targetContext.packageName == ISOLATED_PACKAGE)
        requireNotNull(arguments)
        check(arguments.getString("helix.isolatedRoom") == "true")
        check(arguments.getString("helix.preserveUserSettings") == "true")
        val selected = requireNotNull(arguments.getString("class")) { "Select explicit isolated test classes" }
        check(
            selected.split(',').all { selection ->
                val name = selection.substringBefore('#')
                name in CLASSES && (selection == name || selection.substringAfter('#') in METHODS[name].orEmpty())
            },
        ) { "Test selection is not allowed in the plain Application host" }
        check(arguments.getString("package") == null && arguments.getString("notClass") == null)
        super.onCreate(arguments)
    }

    private companion object {
        const val ISOLATED_PACKAGE = "com.helix.agent.developer.asrvalidation"
        val CLASSES =
            setOf(
                "com.helix.app.infrastructure.conversation.ConversationReferenceKnowledgeDeviceTest",
                "com.helix.app.chat.ConversationPersistenceBoundaryDeviceTest",
                "com.helix.app.infrastructure.conversation.ConversationGoalPlanBoundaryDeviceTest",
                "com.helix.app.chat.AttachmentNormalizationBindingDeviceTest",
                "com.helix.app.chat.ConversationScreenSnapshotDeviceTest",
                "com.helix.app.infrastructure.chat.RoomSessionInputCommandsDeviceTest",
                "com.helix.app.chat.MessageRegenerateDeviceTest",
                "com.helix.app.chat.AutomaticRecoveryPersistenceDeviceTest",
                "com.helix.app.documents.SafSnapshotCapacityDeviceTest",
                "com.helix.app.files.SafDirectoryReliabilityDeviceTest",
                "com.helix.app.documents.SafPlatformCancellationDeviceTest",
                "com.helix.app.files.SafSystemDirectoryReliabilityDeviceTest",
                "com.helix.app.files.SafSystemProcessRecoveryDeviceTest",
                "com.helix.app.eval.BackgroundPreviewUiDeviceTest",
            )

        // Whole audited classes are preferred; method selection is explicit rather than a wildcard.
        val METHODS =
            mapOf(
                "com.helix.app.documents.SafSnapshotCapacityDeviceTest" to
                    setOf(
                        "cancellationRetainsPhysicalCapacityAndStagingUntilProviderActuallyReturns",
                        "timeoutRetainsPhysicalCapacityAndStagingUntilProviderActuallyReturns",
                    ),
                "com.helix.app.files.SafDirectoryReliabilityDeviceTest" to
                    setOf(
                        "nestedSearchHonorsResultAndScanLimitsAndHidesMetadata",
                        "bidirectionalAdditionsAndSingleSideEditsUseVerifiedProviderBytes",
                        "doubleEditConflictSurvivesReopenUntilExplicitContentReconciliation",
                        "oneSidedDeletionAndTypeReplacementNeverDestroyTheOtherSide",
                        "revokedGrantStopsSyncAndReauthorizationResumesFromSavedBaseline",
                        "providerScanDepthLimitLeavesBothTreesUntouched",
                        "interruptedPrivateTransferBlocksSyncUntilExplicitRecovery",
                    ),
                "com.helix.app.documents.SafPlatformCancellationDeviceTest" to
                    setOf(
                        "cooperativeProviderCanCancelQueryAndAssetOpen",
                        "cancellationDoesNotForceAnUncooperativeProviderToReturn",
                        "providerCancellationCallbackCanItselfBlockItsCallingThread",
                    ),
                "com.helix.app.files.SafSystemProcessRecoveryDeviceTest" to
                    setOf("publishedSyncSurvivesProcessDeathWithoutAutomaticReplay"),
                "com.helix.app.files.SafSystemDirectoryReliabilityDeviceTest" to
                    setOf("systemProviderSearchAndSyncConflictRecoverAfterServiceReopen"),
                "com.helix.app.infrastructure.conversation.ConversationReferenceKnowledgeDeviceTest" to
                    setOf(
                        "referencesKeepTheirCapturedCeilingAndRejectChangedCapturedHistory",
                        "onlyOwnedUnsupersededTerminalRepliesPublishAndRepeatedSaveIsIdempotent",
                        "notePublicationDoesNotHoldRoomAndCannotRetargetItsCapturedReply",
                    ),
                "com.helix.app.chat.ConversationPersistenceBoundaryDeviceTest" to
                    setOf(
                        "retrySourceDoesNotBorrowFutureOrForeignInput",
                        "regenerateChecksMessageOwnerAndSourceFromOneRead",
                        "locatedWindowIsDetachedFromLaterHistoryWrites",
                        "messageEditorUsesOpaqueVersionAndRefusesStaleSnapshot",
                    ),
                "com.helix.app.infrastructure.conversation.ConversationGoalPlanBoundaryDeviceTest" to
                    setOf(
                        "approvalAndAuditRollbackTogether",
                        "reviseAndCancelAuditFailuresLeaveThePlanReviewable",
                        "adapterRejectsStaleExecutionBindingsWithoutCreatingGoals",
                        "concurrentApprovedExecutionReturnsOneBoundGoalAndOneAudit",
                        "invalidAndStaleObjectiveEditsDoNotClaimAnUnboundGoal",
                        "objectiveEditCommitsOwnershipRevisionAndAuditTogether",
                        "objectiveAuditFailureRollsBackTheEditAndNewOwnership",
                    ),
                "com.helix.app.chat.ConversationScreenSnapshotDeviceTest" to
                    setOf(
                        "durableFactsAreDetachedAndMissingIsNotAReadFailure",
                    ),
                "com.helix.app.chat.MessageRegenerateDeviceTest" to
                    setOf(
                        "completedAndBlockedGoalsRefuseRegenerationWithoutHidingHistory",
                        "exhaustedGoalKeepsBudgetBlockButDoesNotSupersedeAnswer",
                        "admittedGoalRegenerationCommitsHistoryRunAndRuntimeTogether",
                        "invalidHistoryAfterGoalAdmissionRollsBackTheNewRunAndTurn",
                    ),
                "com.helix.app.chat.AutomaticRecoveryPersistenceDeviceTest" to
                    setOf(
                        "stopDuringInputRevalidationCannotResumeOrFailTheParkedInput",
                        "inputEditDuringRevalidationCannotBeFailedUsingItsNewRevision",
                        "archivedSessionDuringRevalidationCannotResumeOrFailItsInput",
                    ),
            )
    }
}
