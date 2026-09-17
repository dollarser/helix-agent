# Bug Fix: API35 真机 P0 基线收尾

Status: fixed
Date: 2026-09-17
Related HXA: HXA-094, HXA-095
Affected modules: app

## Problem

Root 已验收分支需要集成；历史 API35 全套 11 项失败仍未闭合。当前最终全套验收进行中。

## Impact

失效 Goal 可能无正常错误反馈；不匹配当前契约及 OEM 的测试无法提供可靠回归证据。

## Root cause

- 通知权限：OnePlus 的 shell 不具备 `GRANT_RUNTIME_PERMISSIONS`，即使应用已有通知权限，重复调用 `grantRuntimePermission` 仍抛异常。测试先检查真实权限，仅缺失时调用授权，仍断言实际状态；没有跳过失败测试。真机环境已授权，不据此声称验证了拒绝到允许的系统弹窗转换。
- Goal 续跑：旧附件测试把显式继续理解为只运行一轮。当前接受的 Goal 契约会连续推进；改为断言一次继续完成两轮、逐轮与累计用量准确、预算耗尽阻塞且不额外请求模型。
- 失效 Goal：绑定不存在的 Goal 时，先插入控制行触发 Room 外键异常，绕过正常领域错误反馈。绑定前解析 Goal，使调用方正常展示阻塞，且不创建 Turn、控制行或模型请求。
- 通知导航：OEM 通知容器的无障碍 `ACTION_CLICK` 可返回成功却不打开内容。测试改为点击唯一匹配的自有通知文本位置，继续验证目标可见、原会话正确、没有新 run/Turn。
- 附件工具管线：测试会话在独立数据库中，原先却复用主 App 的授权/审批数据库，工作目录解析失配并在审批记录外键处失败。测试改为同库的真实权限解析、Broker、Dispatcher 与 Audit，继续复用注册工具实现；意外审批使测试失败，不自动批准。
- 前台异步测试：明确持有 Activity，避免 OEM 挂起无界面测试进程；不修改系统后台策略，也不将此夹具用于后台生命周期验收。

- 全套旅程夹具：普通 JUnit 运行在每项测试前独立创建固定数据，不依赖方法顺序；重启 verify 阶段不补写数据，仍验证原有持久状态。
- Goal 附件单轮绑定夹具：显式使用一次模型调用预算，避免自动续跑与 `.single()` 断言竞争；READY Goal 的预算编辑断言同步当前契约。
- 内置 METADATA 被效果分类器误归为设备修改，触发意外审批。修复为闭合内置元数据空外部效果；Policy 的模式、来源及会话约束仍先执行。
- 调用在排队时已经取消，却先进入审批 Broker，可能等待无意义审批。schema 校验后即检查取消并持久结算，执行前取消复检仍保留；JVM 断言无卡、无 proof、无执行。

## Fix and invariants

`32788bf8` 将 `codex/hxa094-095-acceptance` 的 `1daac902` 合入 main。覆盖的旧 Root WIP 已单独保存到 Git stash；无关 PRoot 网络探针与并行 HXA-202 改动保持原状。未 push。

## Alternatives considered

不恢复单轮 Goal 行为，不删除失败测试，不授予全局系统权限，不让模型或测试自动批准意外工具操作。

## Regression verification

- 初次定向复现：`build/p0-focused-20260917-132320/`，49 项中 37 通过、12 失败，包括历史 11 项及工具回填失败。
- 主机：`build/p0-integration-20260917/check-all.log` 的 `check-all.sh --all` exit 0。此运行早于最终测试夹具调整，最终源码检查另记。
- OEM 测试设置：允许 Helix 打开其自有 `com.helix.agent.developer.test` 辅助 Activity，解决 AndroidX 测试跨包启动确认；未修改 Root 策略或全局安全设置。
- 49 项定向回归：`build/p0-focused-20260917-134055/`，49 PASS / 0 SKIP / 0 FAIL。
- main 集成 Root 真实 App 工具链：`build/p0-root-app-20260917-134259/`，1 PASS。
- `spotlessCheck detekt` 与双 flavor AndroidTest、storage AndroidTest 编译通过，见 `build/p0-integration-20260917/final-source-retry.log`；之后新增的全套修复另跑最终门禁。
- 工具框架 JVM：178 项通过；App consumer 578 项（4 项既有条件跳过）、developer 612 项（4 项既有条件跳过），均 0 失败，见 `build/p0-integration-20260917/dispatch-fixes.log`。
- Plan 生产管线：修复分类与取消后，`build/p0-integration-20260917/plan-submit-fixed.log`，4 PASS / 0 FAIL。
- 完整套件首次诊断：`build/p0-full-20260917-134417/`，522 项中仅结束 286 项（236 PASS / 35 SKIP / 15 FAIL），安全锁屏后主动终止，不能作为全套验收。暴露的旅程种子依赖、Goal 旧断言、前台服务权限与 Plan 分类问题已修；UI 层无 Compose hierarchy 的失败需解锁后重验，不能未经复测归因环境。
- 存储完整设备套件：`build/p0-integration-20260917/storage-device.log`，62 PASS / 0 FAIL。
- 最终主机全门禁重跑尚未通过：`build/p0-integration-20260917/check-all-final-retry.log` 的 5 项 detekt 全位于并行 HXA-194 新增 `CommandResultProjection.kt`，不修改或夹带该进行中的文件；P0 自身复杂度问题已修。不得以早先的 `--all` 通过替代本次最终门禁。
- 最终完整真机运行等待用户解锁；原充电亮屏设置已恢复为 0。runner 现先检查锁屏，临时亮屏设置在 finally 恢复；不关闭安全锁屏。

## Residual risk

OnePlus API35 的本地功能证据不替代其他 OEM、24 小时长稳、真实外部账号或发行验收；专用 soak 的 JUnit 返回不代替其宿主结算文件判定。

## Related records

- [Root 生命周期](../completion-records/HXA-094.md)
- [Root 工具](../completion-records/HXA-095.md)
- [当前状态](../development/status.md)
