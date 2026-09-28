package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the closed, documented enum contracts (architecture docs sections 7, 9, 13; platform
 * capabilities doc section 2; modes doc section 8). Changing these value sets is a contract
 * change for storage, policy and UI and must be reviewed.
 */
class EnumsContractTest {
    @Test
    fun approvalDecisionMatchesClosedStorageContract() {
        assertEquals(listOf("APPROVED", "DENIED"), ApprovalDecision.entries.map { it.name })
    }

    @Test
    fun capabilityMatchesDocumentedSet() {
        assertEquals(
            listOf(
                "WEB_BROWSING",
                "SAF_DOCUMENT_TREE",
                "MANAGE_ALL_FILES",
                "ACCESSIBILITY_AUTOMATION",
                "ROOT_SHELL",
                "NOTIFICATION_READ",
                "CALENDAR_WRITE",
            ),
            Capability.entries.map { it.name },
        )
    }

    @Test
    fun toolOperationClassMatchesDocumentedSet() {
        assertEquals(
            listOf(
                "READ_ONLY",
                "LOCAL_MUTATION",
                "NETWORK",
                "EXTERNAL_ACTION",
                "CODE_EXECUTION",
                "PRIVILEGED",
                "METADATA",
            ),
            ToolOperationClass.entries.map { it.name },
        )
    }

    @Test
    fun errorCodeMatchesDocumentedSet() {
        assertEquals(
            listOf(
                "VALIDATION",
                "PERMISSION",
                "APPROVAL",
                "POLICY",
                "NETWORK",
                "PROVIDER_AUTH",
                "PROVIDER_RATE_LIMIT",
                "TOOL_TIMEOUT",
                "EXECUTION",
                "STORAGE",
                "INTERRUPTED",
                "INTERNAL",
            ),
            ErrorCode.entries.map { it.name },
        )
    }

    @Test
    fun executionTargetTypeMatchesDocumentedLocalSet() {
        assertEquals(
            listOf("LOCAL_ANDROID", "LOCAL_QUICKJS", "LOCAL_PROOT", "LOCAL_CLI_RUNTIME", "LOCAL_ROOT"),
            ExecutionTargetType.entries.map { it.name },
        )
    }

    @Test
    fun operationClassesHaveNoOrdinalApprovalSemantics() {
        assertEquals(
            setOf(ToolOperationClass.READ_ONLY, ToolOperationClass.METADATA),
            ToolOperationClass.entries.filter { it.isReviewModeAdmitted }.toSet(),
        )
        assertEquals(7, ToolOperationClass.entries.size)
    }
}
