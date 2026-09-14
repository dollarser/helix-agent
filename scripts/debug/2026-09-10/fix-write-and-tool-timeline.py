"""Apply scoped write validation and compact timeline edits; no device operations."""
from pathlib import Path
p=Path('tools/files/src/main/kotlin/com/helix/tools/files/WriteTool.kt');s=p.read_text()
s=s.replace('const val VERSION: Int = 2','const val VERSION: Int = 3')
s=s.replace('val parsed =\n                    parseArgs(call.args)', '''val suppliedHash = (call.args["expectedSha256"] as? JsonPrimitive)?.content
                if (!suppliedHash.isNullOrBlank() && !SHA256_HEX.matches(suppliedHash)) {
                    return ToolExecutorResult.Failed(
                        "invalid expectedSha256: use the 64-hex hash returned by read; omit it for a new file. " +
                            "Never invent a hash.", sideEffectFree = true,
                    )
                }
                val parsed =
                    parseArgs(call.args)''')
s=s.replace('val expected = if (exists && parsed.expectedSha256 != null) parsed.expectedSha256 else null','val expected = parsed.expectedSha256')
s=s.replace('"file changed since you read it (hash mismatch); " +','"expectedSha256 precondition failed: file is missing or changed since read; " +')
s=s.replace('val expected = args["expectedSha256"]?.jsonPrimitive?.content','val expected = args["expectedSha256"]?.jsonPrimitive?.content?.takeUnless { it.isBlank() }')
s=s.replace('"When overwriting, the SHA-256 the current file must have (64-hex); " +\n                                    "else the write fails"','"Optional: omit or leave empty for a new file. A nonempty value must be the " +\n                                    "64-hex hash returned by read; missing or changed files fail. Never invent it."')
p.write_text(s)
p=Path('tools/files/src/test/kotlin/com/helix/tools/files/WriteToolTest.kt');s=p.read_text().replace('assertEquals(2, d.version.value)','assertEquals(3, d.version.value)');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/ui/ToolTimelineItem.kt');s=p.read_text().replace('import androidx.compose.runtime.getValue','import androidx.compose.runtime.mutableStateOf\nimport androidx.compose.runtime.remember\nimport androidx.compose.runtime.getValue')
s=s.replace(') {\n    Column(',') {\n    var details by remember(row.callId) { mutableStateOf(false) }\n    Column(',1)
a=s.index('        ExpandableSummary(');b=s.index('        if (row.prootRecoveryAvailable)',a)
old=s[a:b]
s=s[:a]+'''        Text(
            ToolPurpose.text(row.toolName, row.requestSummary),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        TextButton(onClick = { details = !details }, modifier = Modifier.testTag("tool-details-${row.callId}")) {
            Text(stringResource(if (details) R.string.tool_details_hide else R.string.tool_details_show))
        }
        if (details) {
'''+old+'''        }
'''+s[b:]
s=s.replace('row.card?.let { card ->','row.card?.takeIf { details || it.state == com.helix.app.approval.ApprovalCardState.PENDING }?.let { card ->')
p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/ui/ConversationMessage.kt');s=p.read_text();a=s.index('    var expanded by remember(entry.key)');b=s.index('        if (entry.recoveries.isNotEmpty())',a)
s=s[:a]+'''    Column(Modifier.fillMaxWidth().testTag("turn-operations-${entry.key}")) {
        entry.tools.forEach { ToolTimelineItem(it, intents) }
'''+s[b:];p.write_text(s)
