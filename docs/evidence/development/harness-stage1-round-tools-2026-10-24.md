# Harness 阶段 1 B：round 与工具展示边界

日期：2026-10-24；承接本地 `8297a4be`，本记录随本片源码提交，不推送、不变更 v0.0.7。完整 Turn 宿主尚未完成；C 仍需迁移 lifecycle/driver 及其平台依赖。

## 实施与权责

- `core/agent/turn/TurnRoundCoordinator` 唯一持有 BatchTurnRuntime，直接实现 AgentTurnJournal；没有同步 API 与 `asJournal()` 双轨。
- `TurnRoundStore` 是原子 round 命令，`RoomTurnRoundStore` 直接提交原事务；提交成功后才推进运行投影。Steer 与最终答复仍在同一父事务结算；取消时保留已记录工具结果，不再打开下个 ModelCall。摘要身份记录和正文脱敏保留。
- 删除原 App TurnCoordinator、RoomTurnJournal 和已无消费者的 terminal transaction callback。宿主仅保留材料化输入到中立值的转换，Core 不接 Room entity/事务 lambda。
- `ApplicationToolGateway`/`ToolSettlement` 拥有原 Dispatcher、审批及有序持久结算；`ChatToolPresentation` 唯一负责卡片、字符串、ledger 和时间线。展示事实不能授予权限或提交业务结果。
- 审批 claim/取消交接身份属于应用 PendingTurnApprovals，UI cache 只显示。已领取但 broker 尚未决定时仍保留可取消 owner；跨 Turn、重复与冲突拒绝有回归。Room 身份/状态和原 broker 校验不放宽。
- 失败正文、UNKNOWN、原调用位置/intent/duration、跨会话隔离和取消本地化均保留；迟到显示事件不覆盖已成功终态。投影失败不重执行或重复计费。
- 纯工具/安全/诊断 helper 按职责迁入 application/tools、application/security、application/diagnostics，旧路径不留转发。共享 CancelSignal/NoCancellation 真实移到 core:model；原工具 Framework、App、Runtime 与扩展直接引用同一契约，未引入新模块或依赖版本。

目录是职责结果：Core 管运行协调、Room adapter 管持久提交、应用 gateway 管调用治理、聊天 bridge 管显示。当前 App Engine/live driver 仍未中立化，不以本片作为完整分层验收。

## 验证

- Source gate：296 项宿主 fixture 通过，文档/ADR/国际化/秘密扫描通过。
- 完整 Debug 组成：启用原有 spikes，单次 daemon/6 GiB/单 worker 执行 `spotlessCheck detekt lintDebug lintConsumerDebug lintDeveloperDebug test :app:assembleConsumerDebug :app:assembleDeveloperDebug`，5 分 39 秒通过；锁文件、两渠道 APK 与 CLI Runtime 边界通过。两渠道离线 ASR 引擎仍在、权重未打包。
- Core Agent 316、Tool framework 259 项 JVM 测试零失败/跳过；App Standard 1483、Advanced 1645 项零失败，各 5 项既有跳过。两个 AndroidTest 均实际编译通过；旧同步 fixture 在调用处显式进入协程，不保留兼容 journal API。
- 新增 Core 故障/取消/UNKNOWN/receipt 回归 5 项；真实 Room 消息 ID 冲突验证整笔回滚且 runtime 不前移。工具 result/state/Goal 预算先于 sink 通知，重复 settle 不重复计费，pre-dispatch row/result/audit 先于显示有 Room 回归；既有失败正文、写失败、投影失败和重开断言保留。
- 导入依赖检查 6 项通过，约束 Core 无 App/Android/Room/具体工具 Runtime 依赖，工具应用层无聊天 UI/资源/审批卡依赖，旧 coordinator/journal/取消契约路径不回流。检查不代替真实执行或进程隔离。

API 36 ARM64 模拟器采用独立验证包和合成/loopback/mock 模型。Standard **56 项通过、零跳过**，Advanced **56 项通过、零跳过**。覆盖 ToolSettlementRecovery、TurnCoordinator、取消竞态、Steer、review、真实审批流程、输入审批、压缩、定时任务和显式提交。运行器保存原始 instrumentation、日志及候选输入身份在忽略的 `build/refactor-stage1-2026-10-24/`，并关闭自建临时实例；日常模拟器保留。没有操作真机、真实账号或外部分享。

本片修复了迁移暴露的文件级注解导入位置、测试类型/引用与旧 fixture 同步调用；未跳过或弱化用例以放行。R8/正式发行未运行，集中留给最终阶段稳定源码；Debug 不等于 Release 通过。未采纳知识库原型和原有指南改动不纳入本片。
