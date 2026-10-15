# Plugin Platform 专项设计：包格式与 Mobile Use

初稿：2026-09-28；内容收敛：2026-09-30；实现状态校正：2026-10-14。本页只维护包格式、宿主已知原生贡献、Mobile Use 专项和领域迁移理由。通用绑定以[工具 ADR](../adr/tools/001-descriptor-contract.md)为准；安装、组件局部失败、更新/卸载、会话选择与原生资源的设计决定归 [Plugin 归属 ADR](../adr/connectors/003-ownership-and-installation.md)，[Harness §17](harness-refactor-plan.md#17-pluginconnection-与能力产品化)维护接线说明。

**当前实现：**PluginOrigin、App 级 PluginRegistry、Mobile Use 已有实现，R1 原子绑定由 [HXA-231](../completion-records/HXA-231.md)交付，R3 安装、组件归属、选择、停用/更新/修复由 [HXA-235](../development/tasks/HXA-235.md)接入生产；[HXA-251](../development/tasks/HXA-251.md)补充有限 Room/设备旅程。完整在途引用/撤销/进程死亡交叉矩阵仍独立。本页保留的 MVP 示例是历史范围，不是现行能力上限，也不维护第二条 P0/P1/P2 排期。

来源：[插件生态与 Mobile Use 研究](../research/topics/plugin-ecosystem-and-mobile-use-2026-09-28.md)及[原 MVP 证据](../evidence/development/plugin-platform-mobile-use-mvp-2026-09-28.md)；参考产品和源码事实见[插件架构调研](../research/topics/agent-plugin-architecture-research-2026-10-11.md)，增量建议见[Helix 优化方案](../research/topics/helix-plugin-architecture-optimization-2026-10-11.md)。外部格式按明确支持的版本矩阵和 HXA-235 解析契约处理，不以早期研究的草案状态判断当前规范，也不承诺完整客户端兼容或运行时拉取远端 schema。

## 1. 决策摘要

```text
plugin.json + skills/ + mcp.json + extensions.com.helix.agent
    → 包解析/安装身份
        ├─ Skill → SkillRepository
        ├─ MCP   → 连接服务 / extensions:mcp
        └─ 已知 native runtime → MobileUsePlugin 等宿主工厂
                                   → 唯一 ToolRegistry
                                   → Dispatcher / Policy / Approval / Audit
```

本页的 Plugin 指 Helix 的交付包及能力贡献，不拥有 ModelClient、AgentLoop、Turn 生命周期、审批或第二份执行事实。DSH 的宿主基础模块也叫 plugin，其可替换 Agent Loop 的装配方式不属于本页接口，区别见[职责分工调研](../research/topics/agent-plugin-architecture-research-2026-10-11.md#25-agent插件与运行时的分工)。Mobile Use 将既有 Android 自动化作为插件贡献，不为它重建 runtime。

### 2026-10-11 任务化与目标实现

所有者于 2026-10-11 要求把插件研究转为 HXA-272～279；截至 2026-10-14 已按各自范围有界交付。具体承接见[研究到任务映射](../research/topics/helix-plugin-architecture-optimization-2026-10-11.md#7-任务承接与验收)，当前事实、未覆盖矩阵和下一步只在 [status](../development/status.md)维护；不从本页旧设计重建实现队列。跨轮变化见[演进索引](../evidence/research-history/architecture-evolution-2026-10-14.md)。

当轮采用单一目标实现：Skill 内容统一后删除原生正文专用注入；Browser 作为 APK 已知 native contribution 替换旧 built-in 发布；原生资源按实际 owner 和使用引用收尾。当轮未安排旧版本、数据库、会话或批准迁移，不保留双写/双注册；此历史范围不授权后续清库，外部包格式支持独立维护。MCP 仍是可选 adapter，Mobile Use 不为对齐 CUA 改成 MCP。相关契约归四份主题 ADR 及 Decision history，见 [ADR 归属表](../adr/README.md#插件优化任务的-adr-归属2026-10-11)。272首阶段结果见[证据](../evidence/development/hxa272-skill-content-2026-10-11.md)，后续各项验证由各 HXA 记录，不能从设计接受推导完整验收通过。

## 2. 当前问题

本节保留初始问题的承接关系，不作为实时缺陷清单。

| 初始问题 | 当前承接 / 剩余范围 |
| --- | --- |
| ConnectorPackageReader 解析 Codex/Claude/CodeBuddy 插件、MCP JSON 和 Skill，安装概念过载 | HXA-235 已将包导入、安装与组件归属收敛到 Plugin 领域；保留独立 Skill 和手动连接 |
| 本地工具没有 Plugin provenance，只能当 Built-in | PluginOrigin 已加入，Mobile Use 已采用，不再立项重做 |
| AutomationModule 直接注册 ui.*，局部创建 PluginRegistry | 已有 App 级唯一 PluginRegistry；R1 使用统一 ToolBinding 原子发布 |
| Marketplace 以 CONNECTOR/MCP/SKILL 分类，缺包与组件区分 | HXA-235 已统一包目录与组件投影；后续完善诊断体验和未覆盖的生命周期验收，不重做主体 |

当前实现、验证和下一步只看 [status](../development/status.md) 与[候选索引](../development/candidate-decisions.md)。

## 3. 目标模型

### 3.1 PluginDescriptor

已有概念是稳定 identity、version、说明与可选 runtimeId；安装 revision、组件身份与连接身份不可混用。字段的实际源码以相应类型为准，不在本页复制一份可变 DTO。

### 3.2 PluginManifest

根入口为 `plugin.json`，可包含 Skill 和 MCP 配置。公共字段按明确支持的规范版本验证；Helix 私有字段置于 `extensions.com.helix.agent`，首个已知 runtime 为 `mobile-use`。其他客户端的目录布局经 importer 适配，不成为 Core 数据模型。

### 3.3 PluginRuntime

```kotlin
interface HelixPlugin {
    val manifest: PluginManifest
    fun tools(): List<ToolBinding>
}
```

返回可信装配验证的工具贡献；不让插件分别维护可写 descriptor/executor 表，不注入 model client、approval broker 或 recovery scheduler。内容与远程返回仍不可信，后续本机效果重新进入治理。

### 3.4 PluginRegistry

App 级唯一实例维护插件身份和 native contribution，校验重复项及发布顺序；所有工具最终进入同一 ToolRegistry。安装/停用、组件诊断、来源、更新/卸载与会话选择由 HXA-235 接入主方案 §17 的统一生命周期，不是另外一套 Registry 真相。

## 4. Tool provenance 重构

`ToolOrigin.PluginOrigin(pluginId, pluginVersion, runtimeId)` 与 `ToolCallSource.Plugin` 区分插件来源。它们不是降风险或授权信号。

安全 canonical 编码、contractHash、稳定实现 revision、请求 BindingRef、批准与即时撤销均按[工具 ADR](../adr/tools/001-descriptor-contract.md)；本页不再定义冒号拼接的第二种哈希格式。同名工具不能借旧版本或不同 runtime 的精确批准执行，无关更新也不应无故失效当前合法绑定。

## 5. 包格式与 Connector 的目标边界

Plugin package 组织 manifest、Skill、MCP server 与 host extension；Connector/连接服务保留 endpoint、auth、OAuth、connect/test/enable/disable 及远端状态。

包解析/安装应复用已有 archive hardening、content hash 和 ownership，并从 Skill/Connector 特定顶层移往相应包职责。安装身份、连接身份与内容 snapshot 分开；独立用户 Skill 和手动 MCP 连接仍可存在，不能强制构造假的 Marketplace Plugin。

迁移卡、依赖、失败原子性和删除清单统一见 Harness §17–18 的 R3-1、R3-2/R5-min。内部不保留长期双轨，不以迁移删除用户内容；发行后的数据承诺另按有效决定。

## 6. Mobile Use MVP

### 6.1 为什么选择它作为首个 Plugin

已有 `:tools:automation` 提供 AccessibilityService、snapshot/find、节点动作、stale token 校验、敏感 UI 和 session allowlist，可独立验证插件归属，而非同时开发新执行域。

### 6.2 package

模块 `:extensions:mobile-use` 包含 manifest 和 `MobileUsePlugin`。初版示例：

```json
{
  "$schema": "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json",
  "name": "mobile-use",
  "version": "0.1.0",
  "description": "Observe and interact with allowed Android apps through bounded Accessibility automation.",
  "extensions": {
    "com.helix.agent": {
      "runtime": "mobile-use"
    }
  }
}
```

示例记录包结构，不代替当前构建内 manifest 或完整 conformance 声明；schema 按支持版本本地验证。

### 6.3 工具表

初版迁移保留九个既有模型接口：`ui.snapshot`、`ui.find`、`ui.click`、`ui.long_click`、`ui.set_text`、`ui.scroll`、`ui.back`、`ui.home`、`ui.wait`。这是初版范围，不是现行工具数量上限或完整清单。

是否改成 `mobile.observe + mobile.act` 等面向模型接口，应由实际 Eval 决定；不能为模仿 Computer Use 同时改 provenance 和工具语义。

### 6.4 runtime

```text
MobileUsePlugin → AutomationPermissionCenter
                → PermissionCenterAutomationToolPort
                → AutomationTools(origin = PluginOrigin(...))
```

AccessibilityService 留在 Android adapter，不进入 Plugin Core 抽象，也不建立插件自己的 Loop。

### 6.5 UI

MVP 复用 developer 设置入口并显示“Mobile Use（开发者插件）”。用户启用系统无障碍能力、设置允许目标、开启有界操作许可并可立即 Stop；安装不授予权限。AutomationSession 与聊天 Session 不是同一个对象。

HXA-232 已移除每十次动作的周期确认，并支持新快照验证后恢复**原已授权目标**；新目标仍需授权，旧节点不能复用。当前许可、锁屏与资源边界看[平台能力](android-platform-capabilities.md#5-accessibility-自动化)；具体缺口和候选见[设备就绪/可靠性专题](../research/topics/mobile-use-device-readiness-and-reliability-2026-09-29.md)。不把旧 MVP 中的人工恢复步骤当现行要求。

## 7. 安全不变量

Plugin 工具复用唯一 schema→capability/policy→authorization→execution→verification/audit 管线。manifest、Skill 或 MCP 注解不能造批准、启用系统权限、扩大 allowlist 或降低可信效果分类。

无有效许可、未授权目标、失效 token、密码/data-sensitive 节点、敏感目标/动作或预算耗尽，按现行契约拒绝。目标变化先重新观察并检查原授权；不能继续执行旧 token，也不能把已获准目标的可恢复变化永久写成人工确认门槛。

host-known native runtime 只能来自 APK 已构建、宿主认识的实现。不下载 DEX/JAR 通过 ClassLoader 执行未知代码。动作失败是否有副作用按真实执行阶段判断；本页不把历史错误映射当成需要保留的不变量。

## 8. 生命周期

这里只规定本专项的 native contribution 接点：

```text
App 装配 → 唯一 PluginRegistry 校验 plugin/runtime 身份
         → 获取完整 ToolBinding 候选 → 原子发布到 ToolRegistry
         → 会话可用性与正常执行准入
```

`extensions.com.helix.agent.runtime` 只匹配已注册工厂；未知 native extension 诊断并按支持矩阵处理，不猜测执行。是否允许包内其他组件继续可用按主方案 §17.2，不把未知原生扩展与恶意归档等同。

通用安装/激活、组件局部失败、账号绑定、revision CAS、更新/卸载、活跃 Job 资源保留和会话选择统一引用 [Harness §17](harness-refactor-plan.md#17-pluginconnection-与能力产品化)，不保留平行 P1/P2 流程。

## 9. 动态工具暴露

`registered != admitted != model-visible`。Plugin 贡献进入已有发现/曝光路径；按会话与真实能力选择 ui.*，不将所有安装工具常驻每轮请求。核心工具与按需 MCP/Plugin 窗口的策略归 Harness §16.3 和[发现专题](../research/topics/tool-exposure-and-discovery-2026-09-29.md)。

早期 64-tool 挤出 write 的失败只作历史回归来源；已有优先级/发现与 R1 精确命中、零命中保留不得被重新记为未开发。迁移归属不自动扩大窗口或权限。

## 10. Artifact / screenshot

Mobile Use 初版 MVP 主要使用 Accessibility 语义树。HXA-243 等后续工作已经接入手机截图、原生手势及按实际就绪能力选择的观察/动作后端；当前实现与设备证据看[状态](../development/status.md)和[平台能力](android-platform-capabilities.md#5-accessibility-自动化)。视觉结果复用 HXA-225 的类型化图片通路，不把 Base64 普通字符串当图片输入。

截图授权、API30/34 差异、受保护窗口、树/图身份、新鲜度、隐私与预算的研究输入见[可靠性专题 §4.8](../research/topics/mobile-use-device-readiness-and-reliability-2026-09-29.md)。[已有视觉契约](../adr/agent/011-tool-multimodal-vision-feedback.md)与有限设备通过，不证明设备内模型推理、绕过锁屏或所有 OEM/受保护窗口均可截图。

## 11. 与 MCP Mobile Use 的关系

未来可评估 native-android（本机 Accessibility）、mobile-mcp（宿主/远端 ADB）、test-uiautomator 等来源。provider 选择归配置与 adapter，不进入 AgentLoop；设备权限、身份和运行边界分别验收，不把候选写成已支持，也不将 A2A Client 当成本机 Runner。

## 12. 测试计划

这是专项回归要求，不是新的测试通过记录；现行证据看对应任务。

| 范围 | 本专项应验证 |
| --- | --- |
| Manifest/归属 | 支持版本解析、重复插件 ID、已知/未知 runtime、PluginOrigin/contractHash、MobileUsePlugin 全部贡献的来源；默认 Built-in 构造仅在仍保留时测 |
| 注册集成 | App 级唯一 PluginRegistry，候选批次发布；developer ui.snapshot 的 provenance 为 mobile-use，复用 R1 绑定/撤销测试 |
| Android 行为 | snapshot/find、click/set_text/scroll、stale token、敏感目标/动作、force-stop/许可清理；已授权目标恢复不引入额外周期确认 |
| 已有 R3 生命周期与剩余验收 | 主体及有限补验见 HXA-235/HXA-251；完整在途引用/撤销/进程死亡组合按主方案验收，不在这里复制一套 oracle |

主机命令按[公共验收](../development/verification-matrix.md)及当前任务，保持 Spotless/Detekt/source/diff 等适用门禁。设备仅在本次明确授权后运行，不能用历史“可行时跑模拟器”作为授权。

## 13. 本次 MVP 完成定义

初版目标已经由[Plugin Platform P0 / Mobile Use MVP 证据](../evidence/development/plugin-platform-mobile-use-mvp-2026-09-28.md)承接：manifest/registry、PluginOrigin 与 policy/audit、mobile-use manifest、复用既有自动化、不以 BuiltInOrigin 冒充来源、保持治理路径。后续原子注册由 HXA-231 完成。

不再保留可被误当待办的初版复选框。文档、源码和各类验证的完成边界按原记录分别解释；R3 和手机截图已有后续交付，完整生命周期矩阵、远端 provider 与 OEM 验收分别维护，不由 MVP 记录推导完成。

## 14. 初版 MVP 的非目标及后续承接

初版未包含完整 Agent Plugins client conformance、任意原生代码加载、全部 Marketplace IA、Connector 数据大迁移、手机截图、remote provider、hooks 或 Plugin 自有 AgentLoop。插件生命周期及手机截图后来分别由 HXA-235、HXA-243 等接入；其他能力只按当前任务和有效决定接受。TurnEngine/Dispatcher ownership 保持，不以假想实现撑大抽象。

## 15. 现有 Extensions / Connector / Marketplace / Tool Registry 统一审查

统一身份、归属与产品视图，不合并独立运行机制。以下保留模块划分理由；生命周期规则和实施顺序不再在本页重复维护。

| 模块 | 目标职责与需要保留的边界 |
| --- | --- |
| extensions/mcp | handshake/协议版本、schema 适配、动态工具替换、egress、send checkpoint、取消与交付不确定性；包只记录 component ownership，不接管远端协议 |
| extensions/skills | immutable snapshot、resource containment、global/session enablement；包 importer 不应继续等同 Skill 元数据实现 |
| 包读取/安装 | 从 ConnectorPackageReader 的多格式解析职责收窄出包层，复用归档防护、预览/安装/更新/卸载与 revision/content hash，不创建万能事务引擎 |
| ConnectorService | endpoint/auth/OAuth、连接检测、MCP 启停、远端 consent；从包和 Skill 所有权迁出后仍独立 |
| Marketplace | 包为安装/状态/卸载对象，Skill/MCP/native 作组件标签；允许独立手动连接与独立 Skill，不制造假包 |
| PluginRegistry | App 级插件身份/已知 runtime contribution；不是所有内容与执行的超级 Registry |
| ToolRegistry/ToolBindingStore | 唯一工具契约与 executor 原子绑定；来源只是贡献者，不合并到 PluginRegistry |

Plugin installed 不等于 tool executable；Plugin/Skill enabled 不等于 approval；MCP connected 不等于所有工具 model-visible。包身份、工具绑定、Skill snapshot、连接状态是不同维度，不是需要消掉的重复事实。

早期本页的双注册表和逐项发布方案已被 R1 替换：`HelixPlugin.tools()` 返回统一 ToolBinding，完整候选校验后发布，MCP/A2A 同样按 owner 原子替换。稳定身份、撤销、失败保留旧快照与验证以[工具 ADR](../adr/tools/001-descriptor-contract.md)和[HXA-231](../completion-records/HXA-231.md)为准。

后续只执行当前任务接受的增量和验收；不得从旧 P0/P1/P2 或 R3/R5 列表重新安排已交付工作，也不以文档整理启动远程设备或新的 native runtime。
