"""Wrap settings actions and audit filters; group and simplify read-only audit records."""
from pathlib import Path
ui=Path('app/src/main/kotlin/com/helix/app/ui')
(ui/'SettingsActions.kt').write_text('''package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Measure each action against the whole available width, then wrap instead of crushing siblings. */
@Composable
internal fun SettingsActions(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}
''')
p=ui/'ProotRuntimeSection.kt';s=p.read_text().replace('import androidx.compose.foundation.layout.Row\n','').replace('Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {','SettingsActions {');p.write_text(s)
for rel in ['root/RootModule.kt','automation/AutomationModule.kt']:
 p=Path('app/src/developer/kotlin/com/helix/app')/rel;s=p.read_text().replace('Row(modifier = Modifier.padding(top = 8.dp)) {','com.helix.app.ui.SettingsActions(modifier = Modifier.padding(top = 8.dp)) {').replace('                Spacer(Modifier.padding(horizontal = 4.dp))\n','');p.write_text(s)
p=ui/'AuditScreen.kt';s=p.read_text().replace('import androidx.compose.foundation.layout.Row\n','').replace('import androidx.compose.foundation.layout.width\n','import androidx.compose.foundation.layout.widthIn\nimport androidx.compose.foundation.shape.RoundedCornerShape\nimport androidx.compose.material3.Surface\nimport androidx.compose.material3.TextButton\nimport androidx.compose.ui.text.style.TextOverflow\n')
s=s.replace('    Text(stringResource(R.string.audit_title), style = MaterialTheme.typography.titleLarge)\n','')
s=s.replace('Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {','SettingsActions {')
s=s.replace('Modifier.weight(1f).testTag("audit-filter-', 'Modifier.widthIn(min = 160.dp, max = 220.dp).testTag("audit-filter-')
s=s.replace('Modifier.width(140.dp).testTag(tag)','Modifier.widthIn(min = 140.dp, max = 220.dp).testTag(tag)')
s=s.replace('OutlinedButton(onClick = { expanded = true })','OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth())')
s=s.replace('                    ?: placeholder,\n','                    ?: placeholder,\n                maxLines = 1,\n                overflow = TextOverflow.Ellipsis,\n')
s=s.replace('Column(modifier = Modifier.testTag("audit-list"))','Column(modifier = Modifier.testTag("audit-list"), verticalArrangement = Arrangement.spacedBy(8.dp))')
a=s.index('    Column(\n        modifier =',s.index('private fun AuditRow('));s=s[:a]+'''    var details by remember(record.id) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("audit-row-${record.id}"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(lines.first(), style = MaterialTheme.typography.titleSmall)
            Text(record.startedAt, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { details = !details }, modifier = Modifier.testTag("audit-details-${record.id}")) {
                Text(stringResource(if (details) R.string.audit_details_hide else R.string.audit_details_show))
            }
            if (details) {
                lines.drop(1).forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
''';p.write_text(s)
# Keep every action and permission control; only remove the redundant in-page title.
p=ui/'SettingsScreen.kt';s=p.read_text().replace('        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)\n\n','');p.write_text(s)
for locale in ['values','values-zh-rCN','values-en']:
 p=Path('app/src/main/res')/locale/'strings.xml';s=p.read_text();en=locale=='values-en'
 for k,v in {'audit_details_show':'View audit details' if en else '查看审计详情','audit_details_hide':'Hide details' if en else '收起详情'}.items():s=s.replace('</resources>',f'    <string name="{k}">{v}</string>\n</resources>')
 p.write_text(s)
