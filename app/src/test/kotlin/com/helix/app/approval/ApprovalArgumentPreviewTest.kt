package com.helix.app.approval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalArgumentPreviewTest {
    @Test fun showsActualTargetsAndMessageInsteadOfOpaqueFieldNames() {
        assertEquals("com.android.settings", ApprovalArgumentPreview.text("""{"packageName":"com.android.settings"}"""))
        assertEquals("你好", ApprovalArgumentPreview.text("""{"nodeId":"opaque","text":"你好"}"""))
        assertEquals("{}", ApprovalArgumentPreview.text("{}"))
        assertEquals("not-json", ApprovalArgumentPreview.text("not-json"))
        assertTrue(ApprovalArgumentPreview.text("x".repeat(300)).length <= 240)
    }
}
