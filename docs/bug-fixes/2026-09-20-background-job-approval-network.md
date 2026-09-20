# Bug Fix: 后台 Job 审批卡按执行域展示联网能力

Status: fixed
Date: 2026-09-20
Related HXA: HXA-196
Affected modules: app

## Problem

`code.linux.job.start` 的 schema 版本为 1，审批卡却用工具版本是否至少为 2 判断 PRoot 联网能力，导致 script/argv 两种卡片错误显示离线。

## Impact

用户看到的执行边界不准确；实际 Runtime 仍共享 developer 应用的网络权限。错误发生在展示层，不改变授权或实际网络能力。

## Root cause

旧同步工具的版本升级曾与执行域变更同时发生，UI 将这段历史关联写成了长期判据。后台工具复用相同执行域但从版本 1 开始，暴露了错误关联。

## Fix and invariants

PRoot script/argv 审批卡依据 `ExecutionTargetType.LOCAL_PROOT` 展示共享网络能力，与工具名称和 schema 版本解耦。QuickJS 代码卡仍显示离线。沿用 ADR-RUNTIME-001，不新增能力或改变审批策略。

## Alternatives considered

不合并旧实验的三工具 `code.linux.start/status/cancel` 接线；现有四工具协议、持久占用、预算与独立结果收取已取代它。只修复旧实验中有价值的 UI 意图，不添加旧工具名例外或为了 UI 提升 schema 版本。

## Regression verification

共享 host slot 下执行 `./gradlew :app:testDeveloperDebugUnitTest --tests com.helix.app.proot.DetachedJobToolsTest --tests com.helix.app.proot.LinuxRunToolTest --tests com.helix.app.approval.ApprovalCardUiMapperTest :app:testConsumerDebugUnitTest --tests com.helix.app.approval.ApprovalCardUiMapperTest spotlessCheck detekt`，exit 0，日志 `build/approval-network-tests.log`。developer 4 + 7 + 22 项、consumer 22 项，共 55 项，零失败/错误/跳过。

新增用例使用真实 `DetachedJobTools.start()` v1 descriptor，分别断言 script 与 argv 展示联网及原命令；旧判据会令该用例失败。同步 PRoot 与 QuickJS 离线回归同时通过。

## Residual risk

此次验证为纯映射 JVM 回归，不冒充新的真机验收。未来若新增不同网络边界的执行域，必须同步更新展示合同；工具版本不得重新承担执行域含义。

## Related records

- [HXA-196](../development/tasks/HXA-196.md)
- [ADR-RUNTIME-001](../adr/runtime/001-execution-domains.md)
