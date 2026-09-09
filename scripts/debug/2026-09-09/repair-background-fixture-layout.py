from pathlib import Path
p=Path('app/src/androidTest/kotlin/com/helix/app/ui/BackgroundTaskFlowDeviceTest.kt')
s=p.read_text().replace('import com.helix.app.DataSyncForegroundService','import com.helix.app.foreground.DataSyncForegroundService')
s=s.replace('compose.onNodeWithTag("task-collect-${done.id}").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not()', 'val node = compose.onNodeWithTag("task-collect-${done.id}").fetchSemanticsNode()\n                            !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)')
p.write_text(s)
