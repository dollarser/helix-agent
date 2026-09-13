from pathlib import Path
p=Path('tools/root/src/main/kotlin/com/helix/tools/root/LibsuRootAccess.kt');s=p.read_text().replace('    private var serviceBinder: IBinder? = null','    @Volatile private var serviceBinder: IBinder? = null').replace('    private val deathRecipient = IBinder.DeathRecipient(::notifyLost)','    private var deathRecipient: IBinder.DeathRecipient? = null')
s=s.replace('                RootService.bind(Intent(context, HelixRootService::class.java), newConnection)', '''                // libsu removes connection entries after invoking callbacks. Always enqueue callbacks
                // so our unbind cannot re-enter its ArrayMap iterator during service death.
                RootService.bind(Intent(context, HelixRootService::class.java), { mainHandler.post(it) }, newConnection)''')
s=s.replace('            notifyLost()\n            RootOperationResult.Failed', '''            mainHandler.post {
                if (serviceBinder === binder) notifyLost()
            }
            RootOperationResult.Failed''')
s=s.replace('                serviceBinder = binder\n                try {\n                    binder.linkToDeath(deathRecipient, 0)', '''                serviceBinder = binder
                val recipient = IBinder.DeathRecipient {
                    mainHandler.post {
                        if (connection === this && serviceBinder === binder) notifyLost()
                    }
                }
                deathRecipient = recipient
                try {
                    binder.linkToDeath(recipient, 0)''')
s=s.replace('            serviceBinder?.unlinkToDeath(deathRecipient, 0)', '            deathRecipient?.let { serviceBinder?.unlinkToDeath(it, 0) }').replace('        serviceBinder = null\n        lossCallback = null','        serviceBinder = null\n        deathRecipient = null\n        lossCallback = null');p.write_text(s)
p=Path('tools/root/src/androidTest/kotlin/com/helix/tools/root/LibsuRootAccessDeviceTest.kt');s=p.read_text();i=s.index('    private fun verifyGrantedRootServiceAndLoss(')
s=s[:i]+'''    @Test
    fun d_repeatedServiceDeathAllowsOnlyExplicitRebind() {
        assumeTrue(InstrumentationRegistry.getArguments().getString(EXPECTED_ROOT_ARGUMENT) == "granted")
        repeat(3) {
            val rootAccess = newAccess()
            assertEquals(RootRequestStatus.STARTED, rootAccess.requestRoot())
            verifyGrantedRootServiceAndLoss(rootAccess, killService = true)
            awaitCachedShellClosed()
            SystemClock.sleep(250)
            assertEquals(RootGrantState.LOST, rootAccess.status().grant)
            assertNull(rootAccess.rootServiceProcessIdForTest())
            rootAccess.disconnect()
        }
    }

'''+s[i:];p.write_text(s)
p=Path('docs/development/roadmap.md');s=p.read_text().replace('允许 feature/browser、app、tools/root 的相关代码/测试','允许 feature/browser、app、tools/root 的相关代码/测试').replace('安全锁屏由所有者正常解锁；阻塞项保留','真机发现的 RootService 死亡回调重入崩溃采用主线程排队与连接身份校验修复，不升级 libsu/依赖、不改变授权或重放语义。安全锁屏由所有者正常解锁；阻塞项保留');p.write_text(s)
