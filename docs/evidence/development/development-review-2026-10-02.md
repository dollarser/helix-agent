# 开发现状审查与请求头校验修复

日期：2026-10-02（本轮执行环境日期）。范围：所有者要求检查当前开发状态、代码和文档并实施有依据的优化；不是新功能 HXA，也不改变 J2 / Project Memory 顺序。

## 起点与审查范围

- 分支 `main`，HEAD `0619409dd1d7c07e87608def5df388459f6bf71a`。HXA-244 已提交；不能再把背景中的 `aa596b0c` 当作当前基线。仓库已有标注 2026-10-03 的交付记录，本轮不重写其历史日期或验收结论。
- 起点 tracked diff 为空；已有未跟踪调试脚本保留。基线状态和空 diff 保存于忽略目录 `build/review-2026-10-02/`。没有切换分支、提交、推送或修改 Mobile Use 实现。
- 已读根 AGENTS、README 产品说明、status、verification-matrix、上下文导航及相关 Agent / Provider / Connector ADR。`.agents/skills` 不存在；没有读取或修改本机历史 memory。
- 使用现有 CodeGraph CLI 查询 RecoveryCoordinator 与 ProviderHeaders 调用者，并核对当前源文件。抽查 RecoveryCoordinator、Engine TurnRecovery / AutomaticRecoveryPolicy、ToolDispatcher 取消与授权入口、ProviderConfig / ProviderHeaders、ComposerAvailability、PluginInstaller / PluginEndpointBindings。图结果只作导航，不作正确性证明。

实际源码阅读边界：RecoveryCoordinator 为完整纯决策文件；TurnRecovery 只读类契约与 recover 起始事务（不是完整数据库恢复审计）；AutomaticRecoveryPolicy 检查自动核查资格和剩余预算；ToolDispatcher 阅读 validate 后取消、policyStage 的硬拒绝优先、执行提交与 settleExecutionResult 的结果核验入口，不覆盖全部授权解析器或执行器。ProviderHeaders / ProviderConfig 检查完整校验与存储恢复；ProviderComposer / ProviderDraftDiscovery / ConfigRepositories 仅定位共用校验调用，不称为完整 Provider 链路审查。ComposerAvailability 检查选模和 local-only 发送门槛，未审查全部 Composer UI。PluginInstaller / PluginEndpointBindings 检查准备、取消检查、一次发布、清理和端点身份匹配，未覆盖全部 Catalog DAO 或进程崩溃窗口。JVM 测试覆盖比这些人工阅读范围广，不能用测试数替代审查覆盖。

## 确定问题与改动

1. `ProviderHeaders` 的 HTTP token 排除集漏掉反斜杠，非法请求头名称能通过配置、存储恢复与 ProviderConfig 共用的校验。补齐该字符，非法配置在本地校验阶段失败；没有扩大凭据或传输控制头许可。
2. 新增覆盖 ASCII 0–127 的参数循环回归，分别验证合法字母、数字、标点和非法分隔符/控制字符。该测试先在原实现上实际失败，再在修复后通过；不是仅增加一个与实现同构的字符串断言。
3. 公共验收文档 G3 和设备章节仍包含“模型不运行 / 仅准备”的绝对表述，与根 AGENTS 和同页 owner-explicit 规则冲突。统一为默认不运行、当次明确授权后按指定范围执行，CI 始终 host-only。
4. 在现有证据导航加入本记录。恢复、工具结果、Composer 和插件边界的有限抽查未形成另一项可证实修复；不据此宣称完整安全审计通过。

## 主机验证

本机默认 Java 未配置，显式使用已有 JDK 17。首次沙箱运行被 Gradle 用户缓存锁写入限制阻挡；经执行环境批准后使用既有缓存完成主机命令，没有下载或安装新工具。

- 修复前：`./gradlew :core:model:test --tests com.helix.core.model.ProviderHeadersTest`，exit 1，新测试 `headerNamesFollowHttpTokenCharacterSet` 失败，日志 `build/review-2026-10-02/header-baseline.log`。
- 修复后：`./gradlew :core:model:test :core:policy:test :core:agent:test :tools:framework:test :provider:api:test :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest spotlessCheck detekt`，exit 0，37 秒，364 tasks（14 executed / 350 up-to-date）。七个选定测试任务均实际执行；依赖编译/静态任务允许 Gradle 增量复用。日志 `build/review-2026-10-02/host.log`。

| JVM task | 报告用例数 | 失败/错误 | 跳过 |
| --- | ---: | ---: | ---: |
| core:model:test | 162 | 0 | 0 |
| core:policy:test | 198 | 0 | 0 |
| core:agent:test | 296 | 0 | 0 |
| tools:framework:test | 255 | 0 | 0 |
| provider:api:test | 115 | 0 | 0 |
| app:testConsumerDebugUnitTest | 1081 | 0 | 4 |
| app:testDeveloperDebugUnitTest | 1178 | 0 | 4 |

合计 3285 条报告记录，3277 通过、8 跳过；双渠道共享方法分别计数，不称为 3285 个不同方法。每渠道 4 项跳过如下：

- `ConnectorExternalAcceptanceTest.publicDocumentationServiceNegotiatesAndAnswersReadOnlyQuery`、`pinnedPublicSourceConfigsUseProductionReader`：`HXA-125 external acceptance is opt-in`。
- `ConnectorSuppliedArchiveTest.inspectProductionReaderAndSkillImporter`：`requires a local sample`。
- `WorkBuddySuppliedArchiveTest.marketplaceArchivesPreserveAllSkillsAndReferences`：`requires local WorkBuddy samples`。

这些测试及 assumption 条件均已存在于本轮 HEAD，相关文件与 HEAD 无差异，最近修改提交为 `aca92e11`；本轮没有新增跳过条件。这里证明的是既有条件导致本轮跳过，不声称核对了此前每次测试的跳过结果。

最终复核发现已有 `scripts/summarize-android-tests.py` 可以逐任务校验 JUnit XML；已删除本轮重复的临时汇总脚本，复用现有工具分别处理七个任务目录，输出 `build/review-2026-10-02/recheck-*.json`，数量与上表一致。该工具校验 testcase 身份和声明计数；两个 App 报告因各 4 项 skipped 正确返回 `INCOMPLETE` / exit 1，其余五项为 `PASS` / exit 0，不能把 Gradle 成功误写为外部验收通过。示例：`python3 scripts/summarize-android-tests.py core/model/build/test-results/test/TEST-*.xml --output build/review-2026-10-02/recheck-core-model.json`（输出已存在，重跑需新文件名）。

- `./scripts/check-all.sh --source`：基线及修复后均 exit 0；最终通过 704 篇 Markdown、227 个 HXA 任务、35 份 ADR、907 个生产源文件 / 1962 个资源键的对应门禁及脚本回归、Secret 扫描，见同目录 `source-final.log`。
- `git diff --check`：exit 0。

## 未覆盖边界

本轮没有构建 APK、执行 Android lint 全矩阵或 `--all` 门禁。没有启动/使用模拟器或真机，设备状态为 **not requested**。真实 Provider、订阅、FFmpeg / PRoot 媒体端到端与长稳均未执行，不继承 HXA-241 的历史设备绿色。J2、Project Memory、发行身份/签名和真实服务条件继续以当前 status 为准。
