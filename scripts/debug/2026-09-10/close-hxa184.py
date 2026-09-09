#!/usr/bin/env python3
"""Close HXA-184 only after the standalone evidence verifier has succeeded."""
import json
from pathlib import Path
import subprocess

root=Path(__file__).resolve().parents[3]
result=subprocess.run(['python3','scripts/debug/2026-09-10/verify-hxa184-evidence.py'],cwd=root,
                      check=True,text=True,capture_output=True)
evidence=json.loads(result.stdout)
(root/'build/debug/2026-09-10/hxa184/verified-evidence.json').write_text(result.stdout)
record=root/'docs/completion-records/HXA-184.md'
assert not record.exists(),'completion already recorded'
rows=[]
for dev in evidence['devices']:
    rows.append(f"| API{dev['api']} | app | {dev['appPassed']} | 0 |")
    for entry in dev['modules']:
        rows.append(f"| API{dev['api']} | {entry['suite']} | {entry['completed']} | {entry['assumptions']} |")
record.write_text('''# HXA-184 完成记录：谨慎提取与文件/装配整理

日期：2026-09-10。范围：所有者授权的 B 类11项、C 类15项；当前 `codex/phone-chat-polish` 工作树，不提交、推送或合并 main。保留此前 HXA161～183 未提交工作。

## 26 项交付

| 审查项 | 本轮具体变化与保留边界 |
| --- | --- |
| B ToolDispatcher | ToolDeadlineRunner 提取 watchdog/取消等待；审批消费、拒绝记忆和审计主流程仍集中 |
| B WorkspaceArtifactStore | WorkspaceTrashOperations/WorkspacePrivacyOperations 区分可恢复垃圾箱与用户隐私删除；复用同一 root/containment resolver |
| B FileManagerService | FileManagerBatchOperations 处理逐项冲突/进度/取消/结果聚合；保留公开嵌套结果类型和原单项操作 |
| B JsExecutionClient | JsClientPreflight、JsTransportPreparation；PFD/临时文件仍注册到原 execute 的 finally 清理列表 |
| B BrowserController | BrowserDownloadQueue 集中队列与保存工作；WebView/Activity owner 和回调失效逻辑不动 |
| B JsExecutionService | JsServiceValidation 提取请求/载荷校验；一次执行槽、interrupt 和引擎线程生命周期仍同一 owner |
| B A2aTaskRunner | A2aTaskArtifacts 管理输出转换、有界导入和原全局去重锁；发送/取消/按原 taskId 对账留在 runner |
| B BrowserToolBridgeImpl | BrowserToolResultMapper 提取结果/敏感字段映射；主线程 hop、timeout、失效检查留在 bridge |
| B ProviderService | ProviderConnectionProbe 负责探测和快照/状态记录；配置、密钥和绑定修改继续留在 service |
| B HttpFetchBridgeImpl | HttpFetchResponseParser 只解析响应；受验证地址连接、TLS、重定向和出网判断留在 bridge |
| B WireModelProvider | WireModelCatalogParser 提取目录解析；保留 protected override，凭据解析、流取消和 body 关闭不变 |
| C ConversationRepositories | 12个独立 Repository 按类型分文件，回执公共结果类型保留；不改变事务/持久化格式 |
| C AppContainer | 接口与 DefaultAppContainer 分文件，AppWorkspaceTools/AppAndroidTools 整理领域注册；仍是单一组合根，构造顺序不变 |
| C ArchiveTools | FilesArchiveTool/FilesExtractTool 分文件，复用格式、成员与限制帮助函数 |
| C ApprovalCardUi | 数据类型与 ApprovalUiMapper 分文件；显示映射无审批授权权力 |
| C FilesMetaTools | Stat/List/Search/Mkdir 四个 Tool object 分文件；保留共同 schema/参数帮助函数 |
| C NotificationsCalendarTools | Query/PrepareEvent/CommitEvent 分文件，原注册入口和权限判断不变 |
| C FilesMutateTools | Copy/Move/Delete 三个 Tool object 分文件，复用同一 store |
| C AndroidSystemTools | OpenUri/ClipboardRead/ClipboardWrite/Share 分文件；原 bridge 和注册入口保持 |
| C ConfigRepositories | Provider/Runtime/ExecutionTarget/Capability/MCP/Skill 各 Repository 分文件；配置类型保留 |
| C AutomationActions | Finder、NodeActionExecutor、Waiter 分文件；token generation、敏感节点校验和 recycle 保持 |
| C HelixDatabase | HelixMigrations 保存原迁移 SQL；数据库版本12、companion 入口和迁移注册顺序不变 |
| C SdkMcpClientFacade | facade、NegotiatedProtocolTransport、SdkMcpClientSession、内容转换、HTTP client factory 分文件 |
| C AutomationSnapshotEngine | AndroidSnapshotNode、AccessibilityGenerationTracker 分文件；遍历/指纹/回收仍保持原边界 |
| C ConversationDaos | 11个 DAO interface 分文件，SQL 未改变；旧文件的审查链接转到 SessionDao 导航 |
| C SettingsScreen | ProotRuntimeSection、LanguageSection 分文件；Runtime 被动进入仍不启动 companion |

不以行数作为完成条件：例如 ToolDispatcher 从1032到935行，WorkspaceArtifactStore从895到752行，JsExecutionClient从674到509行。AppContainer 接口119行，实际组合根 DefaultAppContainer566行，另有两个领域注册文件；不能把接口变短冒充整个装配逻辑消失。D类状态机/协议保持集中。

决策记录：不适用；既有契约内组织代码和修复竞态，未改变权限、Tool schema、IPC、数据库格式、依赖或接受新的架构决定。ADR-0012/0015/0016/0033 等边界保持。

## 扩展回归发现与修复

- API36 暴露审批记录已插入、wait slot 尚未注册时 decide 丢失唤醒。记录发布/等待槽注册受同一锁保护；不在等待用户输入期间持有数据库连接。ApprovalRegistrationRaceTest 用 latch 固定这个交错，两 flavor 都通过。
- API29 暴露 Goal 删除与终态后的提醒同步交错：先读 binding 后读 run 时，run 已级联删除，异常导致进程崩溃。关联读取改为同一数据库事务快照；已删除 Goal 不重建、不续跑，提醒 reconciler 按已有删除语义取消。
- ChatStopProgressDeviceTest 等待真实可见的重试按钮，包括异步 projection/自动跟随布局完成；不通过强制滚动隐藏产品问题。ProviderModelDiscoveryUiTest 对已在滚动区域外的 http 确认项先滚动、断言可见/勾选，再断言保存可用。原断言未删除，测试未跳过。

## 主机证据

完整最终门禁：

```sh
./gradlew spotlessCheck detekt test lintDebug :app:lintConsumerDebug :app:lintDeveloperDebug --continue --max-workers=1
```

`final-host-gates.log` BUILD SUCCESSFUL。2796项 JVM：2788通过、8项既有外部条件跳过、0 failure/error；34个有 XML 结果的测试任务，见 `final-host-counts.json`。8跳过为 supplied Connector/WorkBuddy 与 public external acceptance 条件各4种方法×双flavor，未删除或扩大跳过条件。

制品构建：`host-gates.log` 包含双app/测试APK，以及 `:core:storage`、`:feature:files`、`:feature:browser`、`:runtime:quickjs`、`:tools:android`、`:tools:automation` 的 `assembleDebugAndroidTest`；修复后的双app与测试APK见 `final-race-gates.log`、`final-ui-gates.log`，均 BUILD SUCCESSFUL。运行时不重建制品，验收核对当前 APK SHA 与冻结副本一致。

## 设备证据

```sh
sh scripts/debug/2026-09-10/run-hxa184-device.sh consumer 29 5586 build/debug/2026-09-10/hxa184/accepted29
sh scripts/debug/2026-09-10/run-hxa184-device.sh developer 36 5584 build/debug/2026-09-10/hxa184/accepted36
python3 scripts/debug/2026-09-10/verify-hxa184-evidence.py
```

脚本要求 `ANDROID_HOME` 指向本机 SDK。每侧独占启动 readonly AVD，固定1080×2400@420，拒绝已有 serial，finally 关闭自己的进程。未借用 emulator-5554，未操作真机。每侧163 app集成覆盖文件、审批、聊天、Goal、上下文、MCP/A2A、Provider目录和语言切换；库级结果来自原始 instrumentation 状态码：

| 设备 | 套件 | 通过 | 条件跳过 |
| --- | --- | ---: | ---: |
'''+ '\n'.join(rows)+'''

每侧另有 Accessibility setup 就绪标记、活跃PID与 host force-stop 记录；该 setup 故意被杀，不作为一个 JUnit 通过计数，重启恢复断言计入上表。浏览器2项跳过是明确 opt-in 的长稳/原生平台诊断，本轮保留后置，不把跳过当作系统 JNI/Binder 根因关闭。真实 AutofillService 的 fill/save 路径正常执行。

完整证据根目录：`build/debug/2026-09-10/hxa184/`（Git忽略），包括冻结APK/SHA、每库原始日志、主机XML汇总、owner/closed 和 `verified-evidence.json`。初轮 `api29/api36` 失败保留，不计为通过。可复用 runner 与一次性调试脚本按日期保存在 `scripts/debug/2026-09-10/`。

文档/ADR/i18n/secrets/diff 在收口后再次运行，日志 `final-docs.log`。本轮不替代长稳、外部受保护账号、Root真机、系统 JNI/Binder 根因或商店发布验收。
''')
p=root/'docs/development/status.md';s=p.read_text()
s=s.replace('最新实现和验证见 [HXA-183](../completion-records/HXA-183.md)，不自动启动其他新功能候选。','HXA-184 的 B11/C15 整理及扩展回归也已完成，见 [HXA-184](../completion-records/HXA-184.md)，不自动启动其他新功能候选。')
s=s.replace('## In progress','- M10 / HXA-184 已完成：B11/C15 职责与装配整理、审批注册/Goal提醒读取竞态修复；完整主机及独占双API回归通过，见 [完成记录](../completion-records/HXA-184.md)。\n\n## In progress',1)
s=s.replace('HXA-184：继续完成大类审查 B 类 11 项谨慎提取与 C 类 15 项文件/装配整理。按职责分阶段验证，保留当前 worktree；不提交、推送或合并 main。','无。HXA-184 的26项及回归已完成；保留当前 worktree，未提交、推送或合并 main。')
s=s.replace('完成 HXA-184 全部 26 项及回归，不以单个整理单元作为停止条件；不自动启动新功能。','等待所有者选择后续任务或提交范围；不自动启动新功能。');p.write_text(s)
p=root/'docs/development/roadmap.md';s=p.read_text();a=s.index('### HXA-184');s=s[:a]+s[a:].replace('状态：in progress。','状态：completed，见 [完成记录](../completion-records/HXA-184.md)。',1);p.write_text(s)
p=root/'docs/development/verification-matrix.md';s=p.read_text().replace('in progress；未将编译作为功能验收','主机2788通过/8条件跳过；独占双API app各163与六库/Accessibility恢复通过，见 [HXA-184](../completion-records/HXA-184.md)');p.write_text(s)
p=root/'docs/development/large-class-responsibility-audit-2026-09-10.md';s=p.read_text().replace('B/C 类已由所有者授权进入 HXA-184（11 项谨慎提取、15 项文件/装配整理）','B/C 类已在 [HXA-184](../completion-records/HXA-184.md) 完成（11 项谨慎提取、15 项文件/装配整理）');p.write_text(s)
print('HXA-184 closed from verified evidence')
