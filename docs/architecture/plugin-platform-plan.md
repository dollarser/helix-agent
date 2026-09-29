# Plugin Platform 专项设计：包格式与 Mobile Use

初始设计：2026-09-28；职责归类：2026-09-29。保留包格式、host-known native contribution 与 Mobile Use MVP 的细节。通用 Binding、安装/更新/会话选择/局部失败的目标规范已汇总到 [Harness §15/§17](harness-refactor-plan.md)，本页不另维护一条重构主线。

**起点与现状分开：**§2 记录初始设计时的问题，不是当前缺口清单；PluginOrigin、PluginRegistry 与 Mobile Use MVP 已有实现基础。完整生命周期收敛仍按 R3 任务接受范围推进，参见[候选索引](../development/candidate-decisions.md)。本文不因“决策摘要”标题接受未获授权的能力。

依赖研究：[插件生态与 Mobile Use](../research/topics/plugin-ecosystem-and-mobile-use-2026-09-28.md)。外部格式按核验版本和支持矩阵对待，不承诺覆盖所有客户端扩展。

## 1. 决策摘要

Helix 插件平台采用以下边界：

```text
Agent Plugins 1.0 portable floor
  plugin.json
  skills/
  mcp.json
          │
          ▼
Helix Plugin Package / Registry
          │
          ├─ Skills -> existing SkillRepository
          ├─ MCP    -> existing McpAppService
          └─ extensions.com.helix.agent
                    -> host-known native Plugin runtime
                              │
                              ▼
                    existing ToolRegistry
                              │
                              ▼
                  Dispatcher / Policy / Approval / Audit
```

**不新增 Plugin AgentLoop。** TurnEngine、AgentLoop、Dispatcher、effect truth、approval ownership 保持现状。

Mobile Use 是第一条 native Plugin MVP，用于把已经存在的 `:tools:automation` 从 Built-in registration 收敛到 Plugin provenance。

## 2. 当前问题

### 2.1 Connector 已经实际上是半个 Plugin package

当前 `ConnectorPackageReader` 可以读取：

- `.codex-plugin/plugin.json`
- `.claude-plugin/plugin.json`
- `.codebuddy-plugin/plugin.json`
- MCP JSON
- Skills

并负责安全 archive 读取、content hash、ownership、install/update。

这解决了“迁移别家配置”，但产品概念已经过载：一个只有 Skill 的包也被叫 Connector，一个 Codex plugin 也被转成 Connector。

### 2.2 本地工具只有 BuiltIn / MCP / A2A provenance

`ToolOrigin` 当前没有 Plugin。导致本地扩展工具只能假装成 Built-in。

### 2.3 native capability 在 AppContainer 中硬接线

例如 developer flavor：

```text
AutomationModule.register(...)
```

最终直接把 9 个 `ui.*` 注册进全局 ToolRegistry。

### 2.4 Marketplace 的顶层类型仍是 Connector/MCP/Skill

没有表达：

```text
Plugin = 安装/分发单元
Components = Skill / MCP / native extension
```

## 3. 目标模型

### 3.1 PluginDescriptor

Plugin 是稳定 identity + version + components/runtime binding：

```kotlin
data class PluginDescriptor(
    val id: String,
    val version: String,
    val description: String,
    val runtimeId: String?,
)
```

MVP 只需要支持 bundled, host-known runtime；后续安装包再补持久化 lifecycle。

### 3.2 PluginManifest

根 manifest 对齐 Agent Plugins 1.0：

```text
plugin.json
```

公共字段按标准验证。Helix 私有内容只能放：

```text
extensions.com.helix.agent
```

MVP 私有字段：

```json
{
  "runtime": "mobile-use"
}
```

### 3.3 PluginRuntime

```kotlin
interface HelixPlugin {
    val manifest: PluginManifest
    fun tools(): List<ToolBinding>
}
```

它只是 Tool/Capability 注册入口，不拥有：

- model client
- turn lifecycle
- approval broker
- persistence truth
- recovery scheduler

### 3.4 PluginRegistry

MVP 负责：

- plugin id 唯一；
- runtime registration 顺序确定；
- 调用 Plugin 将 descriptors/executors 注册到既有 ToolRegistry；
- 提供 list/find 供 UI/诊断使用。

未来再扩展：

- installed/disabled/version 状态；
- component diagnostics；
- marketplace source；
- update/uninstall；
- per-session selection。

## 4. Tool provenance 重构

新增：

```text
ToolOrigin.PluginOrigin(
    pluginId,
    pluginVersion,
    runtimeId,
)
```

canonical security identity：

```text
plugin:<id>:<version>:<runtime>
```

因为 `ToolDescriptor.contractHash` 已包含 origin canonical form，所以：

- Plugin 升级会改变 approval binding；
- runtime binding 改变会改变 approval binding；
- 同名工具不能借旧版本 approval 执行。

对应 Policy source：

```text
ToolCallSource.Plugin
```

注意：**Plugin provenance 本身不是风险降低信号。** dynamic risk 仍由 operation class、capability、data origin、scope、effect 等宿主事实决定。

## 5. 包格式与 Connector 的目标边界

### 5.1 Plugin 是顶层安装单元

```text
Plugin
├── manifest
├── skills
├── mcp servers
└── host extension
```

### 5.2 Connector 降为连接组件

Connector 继续负责：

- endpoint
- auth
- OAuth
- connect/test/enable/disable
- remote service state

而不再定义“一个插件包是什么”。

### 5.3 迁移路径

由于产品未发布，不需要保留面向用户的旧概念兼容；但实现应分阶段以避免一次性破坏已验证的安全代码：

1. P0：新增 PluginManifest/PluginRegistry/PluginOrigin；Mobile Use 走新路径。
2. P1：`ConnectorPackageReader` 提升/拆成 `PluginPackageReader`，原有 archive hardening 原样复用。
3. P2：Connector install storage 拆为 Plugin installation + MCP connection ownership。
4. P3：Marketplace 顶层切到 Plugin，MCP/Skill 作为组件标签。

这里的“分阶段”是代码提交边界，不是旧产品兼容承诺。

## 6. Mobile Use MVP

### 6.1 为什么选择它作为首个 Plugin

它已经存在完整 runtime，因此能单独验证“插件边界”而不是同时验证新 runtime：

```text
:tools:automation
  AccessibilityService
  snapshot / find
  node action
  stale token
  sensitive UI
  session allowlist
```

### 6.2 package

```text
:extensions:mobile-use
├── plugin.json
└── MobileUsePlugin
```

`plugin.json`：

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

### 6.3 工具表

第一版直接保留已经验证的 9 个 schema，避免同时改变模型接口与 provenance：

- `ui.snapshot`
- `ui.find`
- `ui.click`
- `ui.long_click`
- `ui.set_text`
- `ui.scroll`
- `ui.back`
- `ui.home`
- `ui.wait`

后续是否对模型压成 `mobile.observe + mobile.act`，应通过 eval 决定，而不是为了“看起来像 Computer Use”先改接口。

### 6.4 runtime

```text
MobileUsePlugin
  -> AutomationPermissionCenter
  -> PermissionCenterAutomationToolPort
  -> AutomationTools(origin = PluginOrigin(...))
```

AccessibilityService 不进入 Plugin core abstraction；它只是 Mobile Use 的 Android implementation。

### 6.5 UI

MVP 继续复用现有 developer settings session controls，但产品文案改成：

```text
Mobile Use（开发者插件）
```

用户必须显式：

1. 在系统设置开启 Accessibility Service；
2. 填允许目标包；
3. 开始 time/action bounded session；
4. 可立即 Stop。

插件安装/存在本身不能自动获得 Accessibility authority。

锁屏、后台与真实跨 App 任务的现状及后续建议见 [Mobile Use 设备就绪与可靠性评审](../research/topics/mobile-use-device-readiness-and-reliability-2026-09-29.md)。当前短时 AutomationSession 不等同聊天 Session；现行熄屏/安全锁定结束许可、检查点和权限规则不因本专题而改变。就绪诊断、生产等待与动作后核查优先复用现有服务；用户解锁后接续和有条件亮屏仍是候选，不新增 Plugin AgentLoop。

## 7. 安全不变量

### 7.1 Tool pipeline 唯一入口不变

Plugin tools 必须经过：

```text
ToolRegistry
 -> schema validation
 -> capability
 -> policy
 -> approval
 -> executor
 -> output validation
 -> audit
```

禁止 Plugin 直接调用 approval broker 或绕过 dispatcher。

### 7.2 manifest 不授予权限

`plugin.json`、Skill 文本、MCP annotations 都是描述/配置，不能：

- 降低 risk；
- 自动开启系统 permission；
- 创建 approval；
- 扩大 package allowlist。

### 7.3 PluginOrigin 进入 contractHash

Plugin id/version/runtime 都是 approval security binding。

### 7.4 Mobile Use 保留现有 fail-closed 规则

- no active session -> refuse
- package not allowlisted -> refuse
- target changed -> refuse
- stale token -> refuse
- password/data-sensitive node -> refuse
- sensitive app -> refuse
- sensitive semantic action -> refuse
- action budget exhausted -> refuse

### 7.5 不支持任意 native code injection

P0 不存在“下载 plugin -> ClassLoader 加载第三方 DEX”。Native runtime 只能来自已随 Helix APK 构建且 host 明确认识的 runtime id。

## 8. 生命周期

### P0 bundled Plugin

```text
app startup
  -> PluginRegistry.register(MobileUsePlugin)
  -> validate unique plugin id
  -> plugin registers descriptors/executors
  -> tools initially capability/session gated
```

这里只证明平台边界。

### P1 installed Agent Plugin

```text
select directory/zip
 -> bounded package read
 -> validate root plugin.json locally
 -> hash package
 -> discover skills + mcp independently
 -> publish installation disabled
 -> user enables components
```

### P2 native extension activation

只允许 manifest `extensions.com.helix.agent.runtime` 匹配宿主已注册 runtime catalog：

```text
mobile-use -> bundled MobileUsePlugin factory
```

未知 runtime：

```text
diagnostic + ignore native extension
```

不得下载执行未知代码。

## 9. 动态工具暴露

Mobile Use 加入后，Helix 不能继续假设所有 registered tools 永远进入每个 request。

现有 P5 已证明 64-tool 截断会把核心 `write` 挤出模型表。Plugin 平台应与当前 tool discovery/windowing 收敛：

```text
registered != admitted != model-visible
```

建议长期分层：

```text
always/core window
  lifecycle + result + essential workspace/discovery

contextual plugin window
  ui.* only when Mobile Use session active / discovered

dynamic MCP window
  loaded by tools.search
```

MVP 不改当前 WIP 的 exposure 算法，只要求 Plugin registration 不破坏其不变量。

## 10. Artifact / screenshot

现有 Mobile Use MVP 以 semantic Accessibility tree 为主，不新增 screenshot tool。

后续 screenshot 应：

```text
mobile screenshot
 -> ArtifactStore image/png
 -> image reference
 -> vision-capable model on demand
```

不要把 base64 图片塞进普通 ToolResult JSON。

屏幕采集权限、API30/34 支持差异、受保护窗口、树/图像身份关联和 HXA-225 复用边界见上述评审 §4.8；这不是已有手机截图功能或绕过锁屏的证明。

## 11. 与 MCP Mobile Use 的关系

未来 Plugin 可有多个 runtime/provider：

```text
Mobile Use
├── native-android   # this phone, AccessibilityService
├── mobile-mcp      # remote/host ADB device
└── test-uiautomator
```

对模型暴露的 capability 可以保持一致，但 provider 选择属于 Plugin/runtime 配置，不进入 AgentLoop。

## 12. 测试计划

### JVM

- Agent Plugins 1.0 manifest parse/validation
- duplicate plugin id rejected
- PluginOrigin canonical / contractHash update
- MobileUsePlugin descriptors 全部是 PluginOrigin
- built-in `AutomationTools` 默认 origin 行为（若保留默认值）

### Android

复用已有 automation instrumentation：

- snapshot
- find
- click/set_text/scroll
- stale token
- sensitive target/action
- force-stop/session cleanup

新增 integration assertion：

- developer ToolRegistry 中 `ui.snapshot` provenance 为 `PluginOrigin(mobile-use)`。

### Source gates

- `spotlessCheck`
- `detekt`
- `./scripts/check-all.sh --source`
- `git diff --check`

## 13. 本次 MVP 完成定义

- [ ] 调研文档完成并引用当前官方方案；
- [ ] Plugin Platform 重构文档完成；
- [ ] `:extensions:plugin` 提供 manifest/registry；
- [ ] `ToolOrigin.PluginOrigin` + policy/audit provenance；
- [ ] `:extensions:mobile-use` 有标准 `plugin.json`；
- [ ] Mobile Use 复用现有 `:tools:automation` runtime；
- [ ] `ui.*` 不再以 BuiltInOrigin 注册；
- [ ] existing permission/approval/effect pipeline 未被绕过；
- [ ] JVM/source gates 通过；
- [ ] 可行时跑 developer emulator integration，并关闭 owned emulator。

## 14. 明确非目标

本次不做：

- 完整 Agent Plugins client conformance；
- 任意第三方 native code loading；
- Marketplace 全量 IA 迁移；
- Connector Room schema 大迁移；
- Mobile screenshot/vision；
- Remote device provider；
- Plugin hooks；
- Plugin 自有 AgentLoop；
- 更改 TurnEngine/Dispatcher ownership。

## 15. 现有 Extensions / Connector / Marketplace / Tool Registry 统一审查

### 15.1 结论

需要继续统一，但统一对象是**身份、安装生命周期、组件归属和商店视图**，不是把所有实现塞进一个 `ExtensionsService`。

目标边界：

| 现有模块 | 目标定位 | 是否保留独立实现 |
| --- | --- | --- |
| `extensions/mcp` | MCP protocol/runtime adapter；远程工具动态桥 | **保留** |
| `extensions/skills` | Skill 文档、snapshot、resource、enablement | **保留** |
| `ConnectorService` | 远程 endpoint/auth/OAuth/connect lifecycle | **收窄** |
| Connector package/catalog | 目前承担 Plugin 安装身份/ownership | **迁移到 Plugin package/catalog** |
| Marketplace | Plugin 发现/安装 UI；组件作为 badge/capability | **重构顶层模型** |
| `ToolRegistry` | 所有模型可调用工具的唯一 descriptor registry | **必须保留唯一** |
| `ToolBindingStore` / `ToolRegistry` | 不可分 descriptor/executor 快照 | R1 已删除独立实现表；生命周期扩展复用原子发布 |
| `PluginRegistry` | Plugin identity/native runtime contribution catalog | **App 级唯一实例** |

因此最终不是：

```text
PluginRegistry
  ├── MCP registry
  ├── Skill registry
  └── Tool registry
```

而是：

```text
PluginCatalog / PluginRegistry        # 安装身份、版本、组件归属、native runtime
        │
        ├── Skill component ────────> SkillRepository
        ├── MCP component ─────────> McpAppService / extensions:mcp
        └── Native component ──────> host runtime (Mobile Use...)
                                      │
                                      ▼
                               ToolRegistry
                                      │
                                      ▼
                        Dispatcher / Policy / Approval
```

### 15.2 `extensions/mcp`：不应并入 PluginRegistry

MCP 的核心职责是协议和远程 effect：

- handshake / protocol version；
- remote tool schema adaptation；
- dynamic tool replace；
- egress facts；
- send checkpoint；
- cancellation / delivery ambiguity。

这些是 runtime protocol concern，不是 package lifecycle concern。Plugin 只应记录“这个 MCP server 属于哪个 Plugin component”，不能接管协议实现。

需要统一的是 ownership：当前 MCP server id 的 owner 由 `ConnectorCatalog` 间接保存；目标应变成 Plugin installation/component ownership。

### 15.3 `extensions/skills`：不应并入 PluginRegistry

SkillRepository 的 immutable snapshot、resource containment、global/session enablement 是独立内容生命周期，应该保留。

但 `extensions/skills/connector/ConnectorPackageReader` 的位置已经不合理：它解析 Codex/Claude/Agent Plugin manifest、MCP 和 Skills，职责实际是 **Plugin package import**。

当前阶段已经让它识别 Agent Plugins 1.0 root `plugin.json`，后续应把 package reader/DTO 从 `extensions/skills.connector` 提升到独立 package-import 层；Skill metadata adaptation 可继续调用 `extensions/skills`。

### 15.4 `ConnectorService`：职责明显过宽

当前同时负责：

- ZIP/JSON package preview；
- package install/update；
- Skill ownership；
- MCP endpoint ownership；
- bearer/OAuth；
- test/enable/disable；
- cleanup/uninstall。

目标拆成：

```text
PluginInstallationService
  preview / validate / install / update / uninstall
  component ownership / revision / content hash

ConnectorService
  endpoint auth / OAuth
  connection test
  MCP enable / disable
  remote consent lifecycle
```

### 15.5 Marketplace：当前类型模型已经暴露概念过载

当前 `MarketplaceItemType = CONNECTOR | MCP | SKILL`，但三类安装最终都走 `ConnectorService.install()`；Skill 甚至会临时构造一个只有 `SKILL.md` 的 `ConnectorPackage`。

目标：

```text
MarketplaceItem
  type = PLUGIN
  components = [SKILL, MCP, NATIVE]
```

UI 可按 component/tag 过滤，但安装/状态/卸载的顶层对象是 Plugin。

独立手工添加 MCP server 可以继续存在，它不是 Marketplace Plugin，身份属于 user connection。

### 15.6 ToolRegistry：不要并入 Plugin 系统

`ToolRegistry` 是 runtime execution contract 的唯一真相，应该继续独立。Plugin/MCP/A2A/Built-in 都只是工具来源。

必须坚持：

```text
Plugin installed != tool executable
Plugin enabled   != approval granted
Skill enabled    != authority granted
MCP connected    != all MCP tools model-visible
```

所有来源最终仍进入同一个 ToolRegistry + Dispatcher。

### 15.7 原子绑定沿用 HXA-231

早期 P0 的双注册表与逐项注册方案已由 R1 替换。`HelixPlugin.tools()` 返回统一 `ToolBinding`，`PluginRegistry` 验证全部来源/重复项后一次发布；MCP/A2A 使用同一原子 owner 替换入口。模型请求持有实际曝光 BindingRef，调度、审批和执行不能查到不同实现。

稳定实现身份、撤销准入、失败保留旧快照与验证边界统一见 [工具 ADR](../adr/tools/001-descriptor-contract.md) 和 [HXA-231](../completion-records/HXA-231.md)。本计划未来的包安装/更新/卸载不得恢复独立可写实现表，也不以 R1 完成推导整个插件生命周期已实现。

### 15.8 PluginRegistry 必须是 App 级唯一实例

P0 初版在 `AutomationModule` 内临时创建 registry，只能服务 Mobile Use。现已收敛为 AppContainer 级唯一 `PluginRegistry`；后续所有 native plugin 都必须注册到同一实例。

它与 ToolRegistry 的区别：

- PluginRegistry：谁安装/贡献了能力；
- ToolRegistry：现在有哪些 tool contract；
- SkillRepository：现在有哪些 Skill snapshot；
- McpAppService：现在有哪些远程连接和动态 tools。

四者不是重复 registry，而是四个不同维度的事实。

### 15.9 推荐后续重构顺序

1. **P0（当前）**：App 级 PluginRegistry、PluginOrigin、Mobile Use、Agent Plugins root manifest、设备验证。
2. **P1**：`ConnectorPackageReader` -> `PluginPackageReader`；新增 `PluginInstallationService/Catalog`，迁移 package ownership。
3. **P1**：Tool descriptor/executor -> 原子 `ToolBindingRegistry`。
4. **P2**：Marketplace 顶层统一为 Plugin，组件 badge/filter；Connector 页面只保留连接配置。
5. **P2**：session selection 从 `SessionConnector` 提升为 Plugin/component selection；MCP/Skill 仍执行各自内部 enablement。
6. **P3**：第三方 portable plugin import、remote Mobile Use provider、更多 native host-known runtime。

不建议在 P1/P2 引入任意 DEX/JAR/APK 动态加载；这不是完成 Plugin 平台统一所必需的。

本轮 P0 实现与 API 36 owned-emulator provenance 证据见
[Plugin Platform P0 / Mobile Use MVP 证据](../evidence/development/plugin-platform-mobile-use-mvp-2026-09-28.md)。
