from pathlib import Path
p=Path('app/src/androidTest/kotlin/com/helix/app/ui/BackgroundTaskFlowDeviceTest.kt');s=p.read_text().replace('''                            val done = storage.turns.listBySession(second).last()
                            compose.onNodeWithTag''','''                            val done = storage.turns.listBySession(second).last()
                            // Turn persistence precedes the asynchronous task-list projection.
                            compose.waitUntil(10000) {
                                chat.backgroundTasks.value.any { it.id == done.id && !it.running }
                            }
                            compose.onNodeWithTag''');p.write_text(s)
