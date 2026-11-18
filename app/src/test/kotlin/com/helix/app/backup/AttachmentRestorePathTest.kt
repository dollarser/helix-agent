package com.helix.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentRestorePathTest {
    @Test
    fun snapshotsKeepTheirExactLocationWithoutAuthorizingOtherAppFiles() {
        val raw = "input/attachments/att_${"a".repeat(32)}/中文 文档.pdf"
        val derived = "input/attachment-derived/${"b".repeat(64)}/image-123/normalized.png"
        for (relative in listOf(raw, derived)) {
            val target = "files/workspaces/app/$relative"
            assertEquals(target, AttachmentRestorePath.target("scope:app:$relative"))
            assertTrue(AttachmentRestorePath.permitted(target))
        }
        for (relative in listOf("output/result.txt", "input/attachments/arbitrary/file", ".helix/config")) {
            assertNull(AttachmentRestorePath.target("scope:app:$relative"))
            assertFalse(AttachmentRestorePath.permitted("files/workspaces/app/$relative"))
        }
        assertNull(AttachmentRestorePath.target("scope:project:$raw"))
        assertFalse(AttachmentRestorePath.permitted("files/workspaces/app/$raw/../../secret"))
        assertFalse(AttachmentRestorePath.permitted("files/workspaces/app/input/../$raw"))
    }
}
