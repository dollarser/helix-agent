package com.helix.app.voice

import com.helix.feature.conversation.input.appendVoiceDraft
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceDraftTest {
    @Test fun recognizedTextIsAppendedWithoutReplacingExistingDraft() {
        assertEquals("原草稿 新语音", appendVoiceDraft("原草稿", "新语音"))
        assertEquals("新语音", appendVoiceDraft("", "新语音"))
    }
}
