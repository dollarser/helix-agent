# Bug Fix: 会话中切换模型不生效与侧边栏平铺菜单层级优化

Status: fixed
Date: 2026-09-24
Related HXA: HXA-209, HXA-218
Affected modules: core/storage, app/chat, app/ui

## Problem

1. **会话中切换模型不生效**：用户在聊天会话中点击模型选择下拉框切换模型后，当前会话的模型未能持久化更新，后续发言仍然使用默认模型；
2. **侧边栏菜单层级平铺无折叠**：侧边栏导航原有十几个功能入口全部以一级菜单形式平铺展开，没有层级概念，界面拥挤杂乱；
3. **Codex Client 订阅冒烟版本滞后**：测试与冒烟配置中硬编码的 Codex Client 版本仍为旧版，未跟进至 0.156.1。

## Impact

1. 会话模型切换属于核心交互，切换失效导致用户无法针对特定会话使用指定的模型；
2. 侧边栏过长导致用户很难快速聚焦于常用模块（会话、工作、设置），影响操作效率；
3. 依赖过时的客户端版本标识影响与官方 CLI 行为对齐的一致性。

## Root cause

1. 原实现已有 `SessionDao.selectModel` 持久化路径，但将 INTERRUPTED Turn 当成活动执行而拒绝切换；ChatService 的草稿分支未核对草稿是否属于目标会话，且部分选择成功路径未刷新会话列表；
2. `GroupedNavigation` 已按功能分组，但组内入口始终展开，没有收折状态；
3. Codex Subscription Smoke 测试脚本与客户端定义中的固定版本号未及时跟踪官方发布。

## Fix and invariants

1. **模型选择边界**：原提交 `c5d452168` 将 INTERRUPTED 纳入既有 DAO 更新准入，ChatService 同步区分中断历史与活动执行；草稿只在 ID 与目标会话一致时使用，并补齐持久选择后的会话列表刷新。实际字段为 `providerId`/`modelId`，没有新增 `selected_model` 列或 `switchModel` 方法。
2. **两级折叠抽屉导航**：
   - 在 `GroupedNavigation.kt` 中引入按 `navigationGroup()` 分组的二级折叠抽屉机制；
   - 默认根据当前所在路由自动展开对应的大类组（如处于文件或终端时自动展开“工作”组），支持点击组标题（带 ▴/▾ 指示符）展开或收起；
   - 保持所有语义标签（如 `navigation-group-work`、`navigation-files` 等）兼容。
3. **版本更新与对齐**：更新 `CodexSubscriptionSmoke.kt` 与测试用例中的版本标识至 0.156.1，并保持冒烟测试通过。

## Alternatives considered

- 曾考虑在顶栏放置折叠筛选器，但这破坏了主界面操作流并占据屏幕视线，采用侧边抽屉分组折叠更符合标准 Material 导航规范；
- 曾考虑直接在内存中做模型切换，但这会在进程被杀死恢复后丢失用户的模型选择，因此必须持久化到 Room 数据库。

## Regression verification

以下保留原交付记录的验证声明；该页未提供独立运行目录、设备身份或 APK 绑定。本次文档核查通过 Git `c5d452168` 确认上述代码变更，未重新执行这些测试，也不据此扩展其验证范围。

1. 单元测试与端到端测试：
   - `./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest` 全部通过；
   - `GroupedNavigationDeviceTest` 与 `MainActivityTest` 全部通过；
2. 真机验证：
   - 在真机上测试侧边栏抽屉：点击“工作”组可平滑展开并收起，“文件”、“任务”、“终端”等按层级缩进显示；
   - 在会话界面切换模型，退出应用重新进入后验证所选模型保持生效。

## Residual risk

原记录的真机/测试声明缺少本页可定位的原始运行证据；当前模型目录、运行中切换与中断恢复契约应查 [Provider ADR](../adr/provider/001-models-and-connection.md)，不能仅凭本次历史修复推断全部会话切换场景通过。

## Related records

[HXA-209 完成记录](../../docs/completion-records/HXA-209.md)、[HXA-218 完成记录](../../docs/completion-records/HXA-218.md)。
