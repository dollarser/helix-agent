# Bug Fix: API35 真机 P0 基线收尾

Status: fixed
Date: 2026-09-17
Related HXA: HXA-094, HXA-095
Affected modules: app, tools/framework

## Problem

Root 已验收分支需要集成；历史 API35 全套 11 项失败仍未闭合。已在独立修复分支完成最终普通套件与存储权限分阶段验收；按用户要求暂不合回 main。

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

- 任务列表使用 LazyColumn，完整套件的其他历史会使固定夹具行不在可视区；逐项滚动定位，不要求多行同时组合，不删除历史。
- Runtime 集成测试错误地假设从未验证过；显式暂存/清空验证锚点并恢复，PRoot 作业各自初始化锁定资产，不依赖测试类顺序。此前大量 `JOB_FAILED` 的实际原因是 `no active runtime install`。
- 默认 CLI 持久化测试意外调用真实模型；改用 Runtime 已有离线模型夹具。无账号目录测试注入固定 AUTH 目录结果，模型运输仍走真实 Runtime，不读取或清除设备账号。成功结果必须先验证再显式 ACK；进程死亡后仅有界重查，不重交作业。
- Goal 生命周期与报告测试显式指定足够预算并恢复原配置，防止其他界面测试保存的小预算导致 `TOKEN_BUDGET_LIMIT`；不放宽生产预算。
- Plan 弹窗开启/关闭是异步状态更新，等待对应语义节点出现/消失后断言。
- 真机存储权限需分普通拒绝、授予、重启撤销三阶段；包级 AppOp 不能覆盖现有 UID 级 allow，脚本分别保存、切换、恢复两级值。OEM 不给 shell 管理 AppOps 权限时，仅使用用户已批准的本 App Root 身份调整本 App 的这一项权限。
- HXA-194 已提交基线的命令投影拆为输入事实、状态计算与输出展示，补损坏归档/未知/过期/失败回归；补齐 ArtifactDao fake 的新查询方法与 Compose 命名/格式门禁，不 suppress 新门禁失败。

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
- 早期主机全门禁重跑未通过：`build/p0-integration-20260917/check-all-final-retry.log` 的 5 项 detekt 全位于并行 HXA-194 新增 `CommandResultProjection.kt`，不修改或夹带该进行中的文件；P0 自身复杂度问题已修。不得以早先的 `--all` 通过替代本次最终门禁。
- 早期完整真机运行曾等待用户解锁；原充电亮屏设置已恢复为 0。runner 现先检查锁屏，临时亮屏设置在 finally 恢复；不关闭安全锁屏。

## 解锁后的复验

- 固定独立验收工作树，保留 main 的并行 WIP；随后纳入 HXA-194 已提交导航 `73e574f6`，处理命令投影接口冲突。
- 首轮全套 `build/p0-full-20260917-141113/`：431 PASS / 68 SKIP / 23 FAIL，仅诊断；无 Activity 用例曾人工唤回，不能作为无人干预验收。
- 分阶段诊断 `build/p0-full-20260917-142558/`：普通阶段 442 PASS / 68 SKIP / 9 FAIL，授予 3 PASS，撤销 1 FAIL。上述原因已修；后台预算失败后的 OEM 挂起曾人工唤回，同样只保留诊断证据。
- 最终固定源码 `e5091a8a`，`python3 scripts/debug/2026-09-17/p0/run-device.py <serial> full`：`build/p0-full-20260917-144124/` 普通阶段 **450 PASS / 69 SKIP / 0 FAIL**（519 项，约 579 秒）；存储 granted **3/3**、revoked **1/1**，全部真实执行，无人工唤回介入。
- `python3 scripts/debug/2026-09-17/p0/run-device.py <serial> storage`：`build/p0-storage-20260917-145248/` 授予态 **9/9**（含 All-files 六项）、新进程撤权 **1/1**，0 skip / 0 fail。普通阶段因无存储授权跳过的 All-files 正向用例在此实测通过；阶段重复项不重复计作独立用例。
- `JAVA_HOME=<JDK17> ANDROID_HOME=<SDK> bash scripts/check-all.sh --all`：`build/p0-resume/final2-host.log` **exit 0**。覆盖源码、detekt、spotless、完整 lintDebug/Release 与双 flavor lint、JVM test、构建、依赖锁和 APK 边界。App consumer 582、developer 616 项，各 4 项既有条件跳过，0 failure/error；命令投影新增四项边界单测包含在内。
- 最终 Root 真实 App Dispatcher 工具链：`build/p0-root-app-20260917-145355/`，**1/1 PASS**，0 skip / 0 fail；沿用用户已批准的 App Root 策略，测试包拒绝策略未改。
- 69 项条件跳过仍保持显式：外部账号/端点/材料、专用 kill/restart、长稳等 profile 不自动启用。本轮不把这些项目或 soak 的单纯 JUnit 返回声明为通过。

## 交付与后续整合

- 按用户 2026-09-17 的选择，保留 `codex/p0-verification` 独立分支，不合回 main、不 push，不改动 main 的并行 HXA-194/209 WIP。
- 已包含 main 已提交的 HXA-194 导航 `73e574f6`。修复提交为 `3e134aab`、`75c05244`、`e5091a8a`，合并协调提交 `0528e427`；本记录与脚本补验扩展另随收尾提交落盘。
- 后续先保存 HXA-194 WIP 再整合此分支。命令投影现接收 `CommandResultInput`，保留新增 `sessionId`；并行同名 `CommandResultProjectionTest` 必须合并双方用例，不能选择一方覆盖。其他重叠文件包括 Browser/Detail、ProotToolModule、ChatService 和命令 UI；本轮没有消费或提交 main 的未完成切片。
- 验收证据位于本分支工作树的 ignored `build/`；APK SHA 与源码基线见每轮 `manifest.json`。源码修改后这些记录不能替代新验证。

## Residual risk

OnePlus API35 的本地功能证据不替代其他 OEM、24 小时长稳、真实外部账号或发行验收；专用 soak 的 JUnit 返回不代替其宿主结算文件判定。

## Related records

- [Root 生命周期](../completion-records/HXA-094.md)
- [Root 工具](../completion-records/HXA-095.md)
- [当前状态](../development/status.md)
