from pathlib import Path
for locale in ['values','values-en']:
 p=Path('app/src/main/res')/locale/'strings.xml';s=p.read_text().replace('Delete %1$d selected items and their contents? Shared storage and SAF do not use the Workspace recycle bin.', 'Permanently delete the selection and its contents? Selected: %1$d. Shared storage and SAF do not use the Workspace recycle bin.');p.write_text(s)
p=Path('app/src/androidTest/kotlin/com/helix/app/ui/FilesScreenTest.kt');s=p.read_text().replace('import androidx.compose.ui.test.', 'import androidx.compose.ui.test.',1);s=s.replace('package com.helix.app.ui\n','package com.helix.app.ui\n\nimport androidx.compose.ui.test.onRoot\nimport androidx.compose.ui.test.printToString\nimport androidx.compose.ui.test.ComposeTimeoutException\n');old='''        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }''';new='''        try {
            composeRule.waitUntil(timeoutMs) {
                composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: ComposeTimeoutException) {
            val tree = composeRule.onRoot(useUnmergedTree = true).printToString()
            throw AssertionError("Timed out waiting for $tag\\n$tree", failure)
        }''';assert old in s;s=s.replace(old,new);p.write_text(s)
