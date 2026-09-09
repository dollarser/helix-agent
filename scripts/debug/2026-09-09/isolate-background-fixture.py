from pathlib import Path
p=Path('app/src/androidTest/kotlin/com/helix/app/ui/BackgroundTaskFlowDeviceTest.kt')
s=p.read_text().replace('import androidx.compose.ui.test.performClick','import androidx.compose.ui.test.performClick\nimport androidx.compose.ui.test.isEnabled\nimport com.helix.app.DataSyncForegroundService')
s=s.replace('server.start()','server.start()\n                LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { secondServer ->\n                secondServer.start()',1)
a=s.index('                val provider ='); b=s.index('                val first =',a)
block=s[a:b]
secondblock=block.replace('val provider =','val secondProvider =').replace('${server.port}','${secondServer.port}').replace('server.port','secondServer.port').replace('runConnectionTest(provider)','runConnectionTest(secondProvider)').replace('server.forceTextResponses','secondServer.forceTextResponses')
s=s[:b]+secondblock+s[b:]
s=s.replace('chat.createSession("Background second", provider,','chat.createSession("Background second", secondProvider,')
s=s.replace('                        server.holdChatStreams.set(false)\n','')
s=s.replace('                    if (goalId != null) {','                    compose.waitUntil(10000) { DataSyncForegroundService.runningInstance.get() != null }\n                    if (goalId != null) {')
s=s.replace('                        compose.onNodeWithTag("task-collect-${done.id}").performClick()', '                        compose.waitUntil(10000) {\n                            compose.onNodeWithTag("task-collect-${done.id}").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not()\n                        }\n                        compose.onNodeWithTag("task-collect-${done.id}").performClick()')
s=s.replace('                    container.providerService.delete(provider)','                    container.providerService.delete(provider)\n                    container.providerService.delete(secondProvider)')
s=s.replace('            }\n        }\n}', '                }\n            }\n        }\n}')
s=s.replace('import androidx.compose.ui.test.isEnabled\n','')
p.write_text(s)
