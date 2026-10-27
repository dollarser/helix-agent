package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Review mode must not gain mutation/network admission through enum ordering. */
class EnumsContractTest {
    @Test
    fun operationClassesHaveNoOrdinalApprovalSemantics() {
        assertEquals(
            setOf(ToolOperationClass.READ_ONLY, ToolOperationClass.METADATA),
            ToolOperationClass.entries.filter { it.isReviewModeAdmitted }.toSet(),
        )
    }
}
