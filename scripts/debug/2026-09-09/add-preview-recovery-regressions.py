from pathlib import Path
p=Path('app/src/androidTest/kotlin/com/helix/app/ui/FilesScreenTest.kt');s=p.read_text();pos=s.index('    @Test\n    fun longTextPreview');s=s[:pos]+'''    @Test
    fun unavailablePreviewShowsFailureAndCanBeReopenedAfterRepair() {
        seed("work/note.txt", "original")
        seed("work/target.txt", "target")
        composeRule.navigateTo("files")
        composeRule.onNodeWithTag("files-quick-work").performClick()
        waitTag("files-entry-note.txt")
        val file = wsRoot.resolve("work/note.txt").toPath()
        java.nio.file.Files.delete(file)
        java.nio.file.Files.createSymbolicLink(file, wsRoot.resolve("work/target.txt").toPath())
        composeRule.onNodeWithTag("files-entry-note.txt").performClick()
        waitTag("files-preview-error")
        composeRule.onNodeWithTag("files-preview-text").assertDoesNotExist()
        composeRule.onNodeWithTag("files-preview-close").performClick()
        java.nio.file.Files.delete(file)
        seed("work/note.txt", "repaired")
        composeRule.onNodeWithTag("files-entry-note.txt").performClick()
        waitTag("files-preview-text")
        assertEquals("repaired", nodeText("files-preview-text"))
        waitTag("files-info-sha")
    }

    @Test
    fun binaryWithoutPreviewStillShowsCompletedMetadata() {
        seed("work/data.bin", "\\u0000binary")
        composeRule.navigateTo("files")
        composeRule.onNodeWithTag("files-quick-work").performClick()
        waitTag("files-entry-data.bin")
        composeRule.onNodeWithTag("files-entry-data.bin").performClick()
        waitTag("files-info-sha")
        composeRule.onNodeWithTag("files-preview-none").assertExists()
        composeRule.onNodeWithTag("files-preview-loading").assertDoesNotExist()
    }

'''+s[pos:];p.write_text(s)
