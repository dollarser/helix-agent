package com.helix.app.proot

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProotLogToolTest {
    @Test fun unicodePagesRestoreTheSnapshotAndDetectChangedContent() {
        val text = "日志😀".repeat(2000)
        val restored = StringBuilder()
        var offset = 0
        var hash: String? = null
        do {
            val page = ProotLogTool.page("t/c", text, offset, hash)
            hash = page.getValue("contentHash").jsonPrimitive.content
            restored.append(page.getValue("content").jsonPrimitive.content)
            offset = page["nextOffset"]?.jsonPrimitive?.int ?: -1
        } while (offset >= 0)
        assertEquals(text, restored.toString())
        assertThrows(IllegalArgumentException::class.java) { ProotLogTool.page("t/c", text + "new", 0, hash) }
        assertThrows(IllegalArgumentException::class.java) { ProotLogTool.page("t/c", text, -1, null) }
    }
}
