package com.helix.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceDraftTest {
    @Test fun recognizedTextIsAppendedWithoutReplacingExistingDraft() {
        assertEquals("原草稿 新语音", VoiceInputMapper.appendDraft("原草稿", "新语音"))
        assertEquals("新语音", VoiceInputMapper.appendDraft("", "新语音"))
    }
}
