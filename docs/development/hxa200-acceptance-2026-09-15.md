# HXA-200 整体验收复核（2026-09-15）

最终结论：**HXA-200 已完成本任务范围验收**。审计契约、迟到批准、真实停止/进程恢复及JGit lint阻塞已修复，当前证据与边界见[完成记录](../completion-records/HXA-200.md)。下方保留发现问题时的证据，不再作为待开发清单。HXA-201可复用已验收的服务开展产品界面集成。

历史复核结论（本次修复前）：**不予整体关闭**。C1/C2 已修复，新增存储跨 API 与 Policy 组合证据通过；ADR-0052 第 8 条审计信息仍有实现缺口，JGit 的 P3 lint 阻塞未修复。不得写 HXA-200 完成记录或宣称全量门禁通过。

源码基线：`397608fc`，`worktree-harness-2.0`。工作树含并行产品和 Runtime 改动；本次新增验收测试、脚本和报告，不把整个工作树称为已验收。无 push、merge 或发布。

## 历史复核结果（已由上方最终验收取代）

| 项目 | 结果与边界 |
| --- | --- |
| 跨 scope ASK、审批后执行开始重验 | 已由 `397608fc` 修复。复用同源码设备证据 `build/hxa200-closeout-20260915-173545/`：API29/36 × consumer/developer 各22，共88通过；本次没有重复跑这88项。 |
| Room 迁移及证明生命周期 | 本次补跑 `RoomMigrationFixtureTest` 和 `ApprovalProofLifecycleTest`，API29/36 各38，共76通过，零失败/跳过；`build/hxa200-closeout-20260915-174702/`。独占模拟器 finally 关闭。 |
| Policy 与偏好组合 | 新增 `ToolPreferencePolicyMatrixTest` 1024/1024通过。实际 PolicyEngine × 四模式 × 四风险 × 两profile × 两操作类别 × 能力有无 × Chat工具开关 × 四偏好。仅代表这个有界组合，不代表全部外部来源、egress、Runtime或UI场景。 |
| 生效偏好审计 | **实现未达标**，见下节。测试通过不能替代此契约。 |
| 停止、迟到批准和恢复 | 已有 dispatcher/scheduler 停止与持久结算证据。审批记录保留PENDING本身不能判为错误；完整生产停止→迟到批准→重启恢复路径仍须补充验收，不能仅从审计终态推导UI和恢复全部通过。 |
| JGit / P3 lint | 两个flavor各报告同一依赖的两个TrustAll错误；未修复、未suppress。独立记录，不能归因于本次新增矩阵测试。 |

## 必须补齐的审计契约

ADR-0052第8条要求记录生效偏好、来源、规则/修订及实际决定与原因。当前偏好持久表有ID和revision，但 `ToolApprovalPreferenceRepository.toRecord()` 输出的 `ToolApprovalPreferenceRecord` 只有偏好、scope和contractHash；ID、revision、scopeRef没有传至决策快照。

`DispatchAuditEvent` 与 `StorageAuditSink` 的持久payload未包含这些偏好来源信息。`DecisionSource.USER` 不能区分明确ASK、失效ALLOW、默认ASK以及命中的规则版本；审批卡说明文字也不能代替持久结构化证据。

下一切片应保留命中记录身份、范围和修订，分别记录呈现审批卡时与最终执行开始时的决策快照，避免重验覆盖旧卡含义。采用脱敏、白名单字段并兼容历史payload；先评估现有payload承载能力，不预设必须升级Room。真实Room回归须覆盖偏好翻转、失效ALLOW、UNSET/NEW_DEFAULT、删除/重置与重启后可读性。

## JGit 是否需要修复

需要独立处理，不能把“只使用status/diff”直接当豁免。锁定依赖 `org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r` 的jar SHA256为 `43f92f3adb681a5f3006b979e8d341c12a8cfd8029f287c42bcf0a80377565ae`。本次本地javap确认 `NoCheckX509TrustManager.checkClientTrusted/checkServerTrusted` 方法直接返回；TransportHttp包含 `disableSslVerify` 分支。当前debug APK的DEX仍检出该类名。

这些证据说明不能宣称危险代码已消除；并不证明Helix当前执行路径触发了不安全TLS。旧release制品未重新构建，不作为当前release结论。证据保存在 `build/hxa200-acceptance-jgit/`。

按[后续工作R1](harness-2.0-next-work.md)独立完成恶意Git配置、网络触达、filter/transport调用边界及debug/release制品验证，再决定隔离/替换/受控修补方案。不能使用全局TrustAll suppression或未经验证的依赖升级冒充修复。若选择局部豁免，必须先证明不可达并遵循该任务的决策约束。

## 复现与交接

在工作树根目录、项目支持的JDK17和Android SDK环境执行：

```sh
./gradlew spotlessApply :core:policy:test --console=plain
python3 scripts/debug/2026-09-15/verify-apref-closeout.py --storage
python3 scripts/debug/2026-09-15/inspect-jgit-trustall.py
./gradlew spotlessCheck detekt --console=plain
./scripts/check-all.sh --source
git diff --check
```

HXA-200继续处理审计缺口和恢复验收；JGit作为独立P3阻塞推进。HXA-201可开展不依赖上述缺口的界面映射与验收设计，相关执行/审计集成不能以“200已完成”为前提。六个gap历史提交保留，但不代替本报告的逐条契约验收。
