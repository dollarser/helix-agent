# HXA-206 准备：场景映射与报告契约

- **日期**：2026-09-20
- **基线**：main @ `d5645774`
- **执行角色**：小模型工作包 A（受控准备切片，不关闭整项 HXA-206 验收）

## 1. 场景映射与差距分析

按照 `docs/development/small-model-handoff.md` 要求，建立 206 六大核心场景组的完整映射：

| 场景组 | 实际源文件 / 测试类 / 方法 | 已有证据 | 缺失断言 / 差距 | 将来执行入口 |
| :--- | :--- | :--- | :--- | :--- |
| **1. 读取资料、修改文件、打开产物、返回来源** | `app/src/androidTest/kotlin/com/helix/app/TaskJourneyDeviceTest.kt` (`taskArtifactsDialogListsByRealOwnership`, `crossSessionTasksOpenTheirOwnChatPages`) | HXA-203、HXA-194 完成记录 | 缺乏单次长流程中产物生成后经由外部查看器实际消费且无多余弹窗的端到端串联断言 | `python3 scripts/verify-product-journeys.py --manifest <manifest.json> --output <dir>` |
| **2. ENABLED/DISABLED、三预设、CUSTOM 授权** | `app/src/androidTest/kotlin/com/helix/app/PermissionAtomicityDeviceTest.kt` (`customPolicyRulesEvaluateWithoutSideEffects`), `core/policy/src/test/.../PolicyEngineTest.kt` | HXA-209 完成记录 | ALLOW 预设下非敏感指令免确认卡、与仅显式 `rm -rf` 触发审批的对比端到端链路未在统一 UI 旅程中成组验证 | 同上 |
| **3. 取消、真实重启对账、能力修复后继续** | `app/src/androidTest/kotlin/com/helix/app/RecoveryJourneyDeviceTest.kt` (`recoveryFactsSurviveProcessDeathWithoutReexecution`, `cancelledTurnRendersNoAutoContinueOperation`), `TaskJourneyDeviceTest.kt` (`stopPersistsCancellingUntilSettledAndIsRaceSafe`) | HXA-202、HXA-204 完成记录 | 真实进程死亡后由用户在恢复中心手动点击继续、且执行身份不重放的无副作用闭环断言 | 同上 |
| **4. Plan 审阅、搜索/主题、MCP 添加到禁用** | `app/src/androidTest/kotlin/com/helix/app/ExtensionJourneyDeviceTest.kt` (`mcpToolWorkflowFollowsStandardLifecycle`), `FixedPlanEvaluationDeviceTest.kt`, `SubscriptionThemeDeviceTest.kt` | HXA-192、HXA-191、HXA-207 完成记录 | 搜索与主题切换等纯应用路径不发起 LLM 网络请求且不扩大 Agent 权限的负向断言 | 同上 |
| **5. JSONL 导出** | `app/src/androidTest/kotlin/com/helix/app/export/SessionExportJourneyDeviceTest.kt` (`writesAndClosesActualDocumentWithCompleteTailAndStableMessageIdentity`, `cancellationClosesBlockedPipeAndLeavesOriginalSessionIntact`) | HXA-211 完成记录 | 真实会话界面菜单触发导出后，通过独立回读校验大内容引用与取消不损坏原会话的集成断言 | 同上 |
| **6. Git R1 只读防护** | `app/src/androidTest/kotlin/com/helix/app/files/ImportExportFacadeDeviceTest.kt` (`gitStatusDiffRejectsMaliciousConfig`), `feature/files/src/main/.../GitRepositoryInspector.kt` | HXA-200 历史记录 | 针对恶意 `.git/config`、恶意 filter / transport 配置的只读 status/diff 防护断言，需在 debug / release 双变体独立验收 | 同上 |

## 2. 报告契约与实现

实现标准库交付物：
1. **共享报告库**：`scripts/acceptance_reports.py`
   - 提供有界读取（10 MiB / 10000 项上限）、路径逃逸检测、唯一 JSON 键校验、测试计数校验 (`executed == passed + failed + skipped` 且 `executed > 0`)、设备 lifecycle（`owner_pid` 与 `closed=True`）核验。
   - 区分 `mode: "fixture"` 与 `mode: "real"`：合成 fixture 输出标明 `FIXTURE_ONLY`，严禁冒充产品真实验收通过。
2. **验证入口**：`scripts/verify-product-journeys.py`
   - 校验 HXA-206 六大必跑场景组，缺失任一组立即返回退出码 2。
   - 测试失败返回退出码 1；通过返回退出码 0。
   - 输出机器可读 `report.json` 和结构化 `report.md`。
3. **单元测试与夹具**：
   - 夹具：`scripts/fixtures/acceptance/valid_fixture_manifest.json`
   - 单元测试：`scripts/tests/test_product_journeys.py`（覆盖有效输入、失败、跳过、必跑组遗漏、计数不一致、未关闭 owner、坏 JSON、路径逃逸、超限等 12 项测试）。

## 3. 验收命令与执行结果

```bash
python3 scripts/verify-product-journeys.py --help
python3 -m unittest discover -s scripts/tests -p 'test_product_journeys.py'
python3 scripts/verify-product-journeys.py --manifest scripts/fixtures/acceptance/valid_fixture_manifest.json --output build/test_206_out
```

- 单元测试：12/12 PASS (0.050s)
- 合成夹具校验：Exit Code 0，输出报告生成正常。
- 门禁状态：不包含任何生产代码修改，仅为脚本和文档。
