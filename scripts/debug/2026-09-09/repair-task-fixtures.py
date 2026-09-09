"""Repair HXA-177 fixture ownership and formatter line limits."""
from pathlib import Path

p = Path('app/src/androidTest/kotlin/com/helix/app/chat/BackgroundTaskStorageDeviceTest.kt')
s = p.read_text().replace('HelixStorage.open(context, name).use',
                        'HelixStorage.open(context, name, java.io.File(context.cacheDir, name)).withClose')
s = s.replace('context.deleteDatabase(name)', 'context.deleteDatabase(name)\n            java.io.File(context.cacheDir, name).deleteRecursively()')
s = s.replace('    private fun id(): String', '''    private fun HelixStorage.withClose(block: (HelixStorage) -> Unit) {
        try { block(this) } finally { close() }
    }

    private fun id(): String''')
p.write_text(s)
p = Path('app/src/androidTest/kotlin/com/helix/app/ui/BackgroundTaskFlowDeviceTest.kt')
s = p.read_text().replace('    private fun exercise(',
                        '    @Suppress("LongMethod", "CyclomaticComplexMethod") // Shared two-session lifecycle fixture and teardown.\n    private fun exercise(')
s = s.replace('val goalId = if (goal) chat.createGoal("Fixture", listOf("Evidence"), GoalBudgets(10, 10, 100000, 600000, 300000, 0)) else null',
              '''val goalId = if (goal) {
                    chat.createGoal("Fixture", listOf("Evidence"), GoalBudgets(10, 10, 100000, 600000, 300000, 0))
                } else null''')
s = s.replace('compose.waitUntil(10000) { chat.backgroundTasks.value.any { it.sessionId == first && it.id != firstTurn && it.running } }',
              '''compose.waitUntil(10000) {
                        chat.backgroundTasks.value.any { it.sessionId == first && it.id != firstTurn && it.running }
                    }''')
s = s.replace('chat.backgroundTasks.value.filter { it.sessionId in setOf(first, second) && it.running }.forEach { chat.stopTask(it.id) }',
              '''chat.backgroundTasks.value.filter { it.sessionId in setOf(first, second) && it.running }
                    .forEach { chat.stopTask(it.id) }''')
s = s.replace('compose.waitUntil(10000) { chat.backgroundTasks.value.none { it.sessionId in setOf(first, second) && it.running } }',
              '''compose.waitUntil(10000) {
                    chat.backgroundTasks.value.none { it.sessionId in setOf(first, second) && it.running }
                }''')
p.write_text(s)
