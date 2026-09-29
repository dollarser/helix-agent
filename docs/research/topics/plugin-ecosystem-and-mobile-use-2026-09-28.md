# Agent 插件生态与 Mobile Use 调研（2026-09-28）

> **专项研究输入，目录整理：2026-09-29。**下文比较限于原核验日期；本次未刷新竞品状态。旧起点中的 PluginOrigin、Mobile Use 接线和优先级不能覆盖[当前状态](../../development/status.md)。通用绑定/安装生命周期的目标规范统一见[Harness §15/§17](../../architecture/harness-refactor-plan.md)，包格式与 native MVP 细节见[Plugin 专项](../../architecture/plugin-platform-plan.md)。
>
> 文中“规范 1.0.0 已发布”不单独证明已稳定定型，后续核验的 Working Draft 边界见 Harness 方案 §12。保留原研究原话，不以此承诺完整格式兼容；本专题不继续复制当前实现任务。

---

## 1. 目的与结论

本文只基于当前可核验的主流 Agent 官方规范/文档与 Helix 已有实现，不以“理想插件系统”反推设计。

结论先行：

1. **可移植插件底座正在收敛到 `plugin.json + skills/ + mcp.json`。** Agent Plugins 1.0.0 已发布，OpenAI ChatGPT/Codex 与 Cursor 均明确支持该格式；它刻意只标准化 Agent Skills 与 MCP，hooks、agents、commands、rules 等继续留给客户端扩展。
2. **主机深度能力仍然需要 host-native extension。** VS Code 的 Language Model Tool 通过 Extension Host 获得编辑器 API；Cursor/OpenCode 也各有 host-specific hooks/runtime。仅靠 MCP 无法获得 Android `AccessibilityService` 这样的进程内/平台能力。
3. **Plugin 不应拥有第二套 AgentLoop。** 主流方案把插件看作“能力和工作流的可安装单元”，规划仍由宿主 Agent 完成。Helix 应继续由 AgentLoop/TurnEngine 控制执行，Plugin 只提供 Skills、MCP、host-native tools 与受控生命周期。
4. **Helix 当前的 Connector 已承担了部分 Plugin package 职责。** `ConnectorPackageReader` 能安全导入 Codex/Claude 风格 manifest、MCP 与 Skills，但“Connector = 插件包”概念过载。应提升为 Plugin package，Connector/MCP 成为 Plugin 的一种组件/连接来源。
5. **Mobile Use 最适合作为 Helix client extension。** Helix 已有完整的 developer-only `:tools:automation`：Accessibility snapshot、stale token、防敏感 UI、click/type/scroll/back/home/wait。MVP 应把它从 Built-in tool provenance 抽到一个 `Mobile Use` Plugin，而不是重写 Android 自动化层。

## 2. 调研来源

以下来源在 2026-09-28 重新核验：

- Agent Plugins 1.0.0 Specification: <https://agent-plugins.org/specification>
- OpenAI Plugin architecture: <https://developers.openai.com/plugins/concepts/plugins>
- OpenAI Package your plugin: <https://developers.openai.com/plugins/build/plugins>
- OpenAI Skills: <https://developers.openai.com/plugins/concepts/skills>
- Cursor Plugins: <https://prod.cursor.com/docs/plugins>
- Cursor Plugins Reference: <https://prod.cursor.com/docs/reference/plugins>
- VS Code Language Model Tool API: <https://code.visualstudio.com/api/extension-guides/ai/tools>
- VS Code AI Extensibility: <https://code.visualstudio.com/api/extension-guides/ai/ai-extensibility-overview>
- OpenCode Plugins: <https://opencode.ai/docs/zh-cn/plugins/>
- Claude / Anthropic MCP: <https://docs.anthropic.com/en/docs/mcp>
- Claude Agent Skills best practices: <https://docs.claude.com/en/docs/agents-and-tools/agent-skills/best-practices>

本项目既有证据：

- `docs/architecture/connector-portability.md`
- `extensions/skills/.../connector/ConnectorPackage.kt`
- `extensions/mcp/`
- `extensions/skills/`
- `tools/automation/`

## 3. Agent Plugins 1.0：当前最值得对齐的可移植底座

Agent Plugins 1.0.0 的核心约束很克制：

```text
my-plugin/
├── plugin.json       # 必需
├── skills/           # 可选；Agent Skills
│   └── foo/SKILL.md
├── mcp.json          # 可选；MCP server 配置
└── com.example.host/ # 可选；客户端私有扩展目录
```

### 3.1 标准化的只有两个 component type

v1 明确定义：

- Skills
- MCP servers

并明确把下面这些留在 portable format 之外：

- commands
- hooks
- agents
- rules
- LSP 等 host-specific runtime

这点对 Helix 很重要：**不要试图把 Android Accessibility、PRoot、Root、Browser runtime 强行塞进可移植 MCP/Skill 格式。** 应使用 Agent Plugins 的 client extension 机制表达 Helix 本地能力。

### 3.2 manifest 是 root `plugin.json`

Agent Plugins 1.0 要求根目录 `plugin.json`，其 schema 为：

```text
https://agent-plugins.org/schemas/1.0.0/plugin.schema.json
```

可移植字段是闭合集合，client-specific 数据只能进入：

```json
{
  "extensions": {
    "com.example.client": {}
  }
}
```

因此 Helix 应使用稳定 reverse-domain namespace，例如：

```text
com.helix.agent
```

而不是在 root manifest 自创 `androidRuntime`、`permissions`、`tools` 等顶级字段。

### 3.3 固定发现路径优于 manifest 自由寻址

v1 固定：

- Skills -> `skills/<name>/SKILL.md`
- MCP -> `mcp.json`

好处是：

- 路径优先级不存在歧义；
- 安装器更容易做 traversal / symlink containment；
- Git 仓库天然可读可审；
- 客户端可独立失败一个组件，不拖垮整个插件。

Helix 当前 `ConnectorPackageReader` 已经具备更严格的 ZIP 边界、特殊文件拒绝、压缩炸弹限制和 Skill snapshot，因此安全基础可以保留。

### 3.4 component failure isolation

规范要求：一个 Skill 无效、一个 MCP server 不支持/握手失败，不能阻止独立有效组件加载。

这和 Helix 应采用的 lifecycle 非常一致：

```text
Plugin installed
├── Skill A: active
├── Skill B: invalid -> isolated diagnostic
├── MCP X: connected
└── MCP Y: unsupported transport -> isolated diagnostic
```

不能变成“插件任一子项失败 -> 整包不可用”。

## 4. OpenAI ChatGPT / Codex

OpenAI 当前将 Plugin 定义为可发现、安装、共享的能力包，主体组合是：

```text
Plugin
├── Skills
└── MCP server (optional)
    ├── tools / structured results
    └── UI resources (optional)
```

同时支持 lifecycle hooks 等 OpenAI-specific 扩展。Portable package 使用 root `plugin.json`；`.codex-plugin/plugin.json` 保留兼容入口。

对 Helix 的直接启示：

1. **Skill 是工作流，不是权限。** Skill 只说明何时/如何组合工具；authority 仍来自宿主。
2. **MCP 是外部服务能力。** 不应拿 MCP 来模拟 Android 本地平台 API，除非能力本身运行在独立进程/设备上。
3. **Plugin 是安装单元，不等于工具。** 一个 Plugin 可以没有 MCP，也可以含多个 Skill。
4. **host-specific overlay 是合理的。** Mobile Use 可通过 `extensions.com.helix.agent` 声明本地 runtime 绑定。

## 5. Cursor

Cursor 同时支持：

1. Agent Plugins open standard：root `plugin.json`，Skills + MCP；
2. Cursor 自有 `.cursor-plugin/plugin.json`：额外支持 rules、agents、commands、hooks、variables。

这证明一个很重要的模式：

> **portable floor + host-native ceiling**

Helix 不必为了“生态兼容”牺牲 Android 特有能力。正确做法是：

```text
Agent Plugins portable floor
  ├── skills
  └── mcp

Helix client extension
  └── Android native runtime binding
```

## 6. VS Code / GitHub Copilot

VS Code 明确区分三类 Agent tools：

- built-in tools
- extension-contributed Language Model tools
- MCP tools

其中 Extension Tool 的价值在于可以调用 VS Code Extension API；MCP 的价值在于跨客户端和远程复用，但运行在 VS Code 外，不能直接访问 Extension Host API。

这几乎一一对应 Helix：

```text
VS Code extension tool     <=> Helix native Plugin tool
VS Code MCP tool           <=> Helix MCP dynamic tool
VS Code built-in tool      <=> Helix BuiltInOrigin
```

因此 Mobile Use 如果要直接访问 `AccessibilityService`，应该是 native Plugin tool，而不是假装成 built-in 或强制包装成远程 MCP。

## 7. OpenCode

OpenCode 的插件是 host-native JS/TS 模块，可从项目/全局目录或 npm 加载，并通过 hook 订阅 session、permission、tool、file、shell 等事件。

它说明了 host-native plugin 的另一端形态：高度灵活、能深入生命周期，但代价是：

- 任意代码执行面更大；
- 依赖/供应链复杂；
- 插件可修改宿主行为；
- Android 上直接照搬 npm/Bun runtime 不合适。

Helix 不应在第一版复制 OpenCode 的“任意脚本 hook”模式。Mobile Use 只需要一个 host-known native runtime，不需要开放第三方任意 Kotlin/DEX/APK 加载。

## 8. Claude：Skills / MCP / hooks 的组合

Claude 当前可核验的稳定可复用构件主要是：

- Agent Skills：metadata 先暴露，完整 `SKILL.md` 按需加载；
- MCP：外部工具/资源连接；
- Agent SDK / Claude Code hooks：主机生命周期扩展。

这里最值得 Helix 复用的是 **progressive disclosure**：

```text
model always sees:
  skill name + short description

only on demand:
  full SKILL.md + references/assets
```

这与 Helix 刚在 P5 中暴露的 64-tool 截断问题是同一方向：能力越多，越不能把所有 schema/instructions 永久注入模型。

## 9. 主流方案共性

### 9.1 安装/分发单元与运行单元分离

插件 package 是身份、版本、组件归属；真正运行的能力可能是：

- Skill 文本；
- MCP server；
- host-native extension；
- UI resource；
- hook。

Helix 不应把“Plugin = 一个 ToolExecutor”作为模型。

### 9.2 portable component 与 host component 分层

跨客户端复用的：

- Skills
- MCP

宿主私有的：

- IDE API
- Android Accessibility
- Root / local runtime
- 生命周期 hook
- UI integration

### 9.3 权限由宿主最终裁决

插件描述、MCP annotation、Skill instructions 都不应降低宿主风险分类。Helix 已有 Dispatcher -> Capability -> Policy -> Approval -> Executor -> Audit 单入口，应原样保留。

### 9.4 延迟发现与按需暴露

大插件生态下，工具/Skill 必须支持 discovery/windowing，而不是无限增加每次模型请求的 tool table。

### 9.5 provenance 是安全契约的一部分

同名工具来自不同插件/版本，应当拥有不同 security binding。Helix 的 `contractHash` 已经包含 `ToolOrigin.canonicalOf()`，因此新增 Plugin provenance 是正确落点。

## 10. Helix 当前状态与差距

### 10.1 已有能力

Helix 当前已经具备：

- `ToolRegistry` / `ToolImplementationRegistry`
- Tool schema + contract hash
- Capability / Policy / Approval / Audit
- MCP dynamic tool registration
- Skills immutable snapshot
- Connector package 安全导入
- Marketplace
- Accessibility automation runtime

所以不需要新建第二套工具执行系统。

### 10.2 Connector 概念过载

当前 Connector package 同时承载：

- foreign plugin manifest adapter
- MCP endpoints
- Skills
- install/update ownership
- session selection

这在 Agent Plugins 1.0 发布后已经不再是最清楚的产品/架构边界。

目标应变成：

```text
Plugin
├── portable Skill components
├── portable MCP components
└── Helix-native extension/runtime components

Connector
└── 远程服务连接/认证/endpoint 生命周期
```

### 10.3 ToolOrigin 缺少 Plugin provenance

`ui.*` 目前虽然实现位于独立 `:tools:automation` 模块，但仍注册为：

```text
ToolOrigin.BuiltInOrigin
```

所以审计、approval contract 与 UI 都无法表达“此工具来自 Mobile Use 插件”。

### 10.4 缺少统一 PluginRegistry

当前 AppContainer 直接逐项调用：

```text
RootModule.register(...)
AutomationModule.register(...)
SkillTools.registerAll(...)
...
```

Mobile Use MVP 可以作为第一条 native plugin registration 路径，验证：

```text
PluginRegistry
  -> PluginDescriptor / manifest
  -> register tools into existing ToolRegistry
  -> tools still pass existing Dispatcher
```

## 11. Mobile Use 开源实现与 Helix 的关系

与此前调研一致，可参考：

- `mobile-next/mobile-mcp`：MCP 形式的手机控制；适合 host/ADB/远程设备 provider。
- `NeoAgentman/mobile-use-mcp`：snapshot/stale state/action API 设计参考。
- DroidRun/Mobilerun Portal：Android Accessibility runtime 参考。
- `agent-android`：轻量 ADB provider 参考。
- AndroidWorld：agent 手机任务 benchmark。

但 Helix 自身已经实现了比“再引入一套 runtime”更合适的底层：

```text
:tools:automation
├── HelixAccessibilityService
├── AutomationSnapshotEngine
├── generation/token stale protection
├── sensitive target policy
├── semantic sensitive action policy
└── ui.snapshot/find/click/... tools
```

因此 Mobile Use MVP 应主要完成**插件化**，而不是替换 runtime。

## 12. 推荐决策

### 12.1 采用 Agent Plugins 1.0 作为 package floor

Helix Plugin package 的公共部分遵循：

```text
plugin.json
skills/
mcp.json
```

不再新增另一套 Helix-only 顶层 manifest。

### 12.2 使用 `extensions.com.helix.agent`

例如 Mobile Use：

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

`runtime` 是 Helix extension 私有语义，不声称属于 Agent Plugins portable core。

### 12.3 第一阶段只允许 host-known native runtime

不实现：

- 下载并动态加载任意 DEX/APK；
- 第三方 Kotlin/JAR 直接注入主进程；
- 任意 shell hook；
- 插件绕过 Dispatcher；
- 插件自带 AgentLoop。

允许：

- 已随 APK 编译的 trusted runtime implementation；
- portable Skills/MCP；
- 未来通过独立进程/MCP 连接外部 runtime。

### 12.4 Mobile Use 作为第一条 native plugin MVP

MVP 成功标准：

1. `Mobile Use` 有标准 root `plugin.json`；
2. App 通过 `PluginRegistry` 注册；
3. `ui.*` descriptor provenance 为 `PluginOrigin(mobile-use, version, runtime)`；
4. contract hash 包含 plugin provenance；
5. existing Accessibility session/allowlist/sensitive UI/approval 全部保留；
6. 不修改 AgentLoop / TurnEngine / Dispatcher 所有权；
7. 原有 automation runtime tests 继续通过。

## 13. 不采用的方案

### 13.1 “把 Mobile Use 继续当 built-in”

不能验证插件生命周期/provenance，也无法形成后续 Desktop Use、设备 runtime 的统一扩展路径。

### 13.2 “所有插件都转成 MCP”

跨客户端很好，但 Android 同进程平台 API 会被强行变成额外 IPC/server 层；且 VS Code 等主流客户端本身也保留 native extension tools。

### 13.3 “直接照搬 OpenCode 任意 JS/TS 插件”

第一阶段安全面和 Android runtime 成本都过高，不符合 Helix 当前 fail-closed 设计。

### 13.4 “插件拥有自己的 agent/planner”

会形成双重 AgentLoop、双重 memory/approval/recovery，违背 Helix 当前 shallow harness 边界。

## 14. 后续验证指标

Mobile Use 插件化后应至少验证：

- plugin manifest/schema 本地验证；
- duplicate plugin id fail-closed；
- plugin tool origin/contractHash；
- session permission / tool disabled 一致性；
- Accessibility capability 未开启时拒绝；
- stale token 拒绝；
- target package allowlist；
- sensitive UI/action 拒绝；
- plugin disable 后 schema 不再可执行/暴露；
- process death 不自动重放 external action；
- tool exposure window 不因 Mobile Use 新增工具再次挤掉核心 Workspace 工具。

本轮实现与设备证据见
[Plugin Platform P0 / Mobile Use MVP 证据](../../evidence/development/plugin-platform-mobile-use-mvp-2026-09-28.md)。

## 15. Helix 扩展系统统一重构方案

### 15.1 重构目标

Mobile Use MVP 已证明 Helix 可以在不修改 AgentLoop、TurnEngine、Dispatcher 所有权的前提下，
将 Android 本地能力作为 Plugin contribution 接入。但继续审查现有代码后可以确认，当前仍存在
一套由历史演化形成的“半插件系统”：

    extensions/mcp
    extensions/skills
    ConnectorService
    ConnectorCatalog
    Marketplace
    ToolRegistry
    ToolImplementationRegistry
    PluginRegistry

问题不在于这些模块都应该合并，而在于顶层身份和生命周期边界不统一：

- ConnectorService 同时承担 package import、install/update、Skill ownership、MCP ownership、
  OAuth、connection test、enable/disable、cleanup/uninstall；
- Marketplace 把 CONNECTOR / MCP / SKILL 当作并列顶层安装类型，但三类最终基本都走
  Connector 安装路径；
- 纯 Skill 也会被临时包装成 Connector package；
- ConnectorPackageReader 已经在解析 Codex / Claude / Agent Plugins manifest、Skills 与
  MCP config，职责已经超出 Connector；
- 新增 PluginRegistry 后，如果不继续收敛，会长期出现 Plugin system 和 Connector system
  两套顶层扩展概念。

因此统一原则是：

> 统一安装身份、版本、组件归属、安装生命周期、session selection 和 Marketplace 视图；
> 保留 MCP、Skill、Tool runtime 各自独立的技术职责。

### 15.2 目标架构

最终目标不是把所有 registry 合并，而是形成：

    Plugin Installation / Catalog
      identity / version / source / ownership
      install / update / uninstall / selection
                    |
          +---------+---------+
          |         |         |
        Skill      MCP      Native
          |         |         |
          v         v         v
    SkillRepository McpAppService host runtime
                              |
                              v
                         ToolRegistry
                              |
                              v
                 Dispatcher / Policy / Approval

四个关键事实源分别是：

- PluginRegistry：谁安装、贡献了什么能力；
- SkillRepository：当前有哪些 Skill snapshot；
- McpAppService：当前有哪些远程 MCP 连接和动态工具；
- ToolRegistry：当前有哪些模型可调用的 tool contract。

它们是不同维度的事实，不应该机械合并。

### 15.3 extensions/mcp：保留协议层，只统一 ownership

extensions/mcp 应继续负责：

- MCP handshake；
- protocol version negotiation；
- remote tool schema adaptation；
- transport；
- egress facts；
- per-session send checkpoint；
- schema / endpoint / sensitivity change detection；
- cancellation 与 remote side-effect ambiguity。

这些属于 runtime protocol concern，不属于 Plugin installation concern。

目标关系：

    PluginInstallation
      -> owns MCP component
          -> McpAppService

需要迁移的是 ownership。当前 MCP server owner 主要由 ConnectorCatalog / InstalledConnector
间接表达，后续应改成：

    PluginInstallationId
      -> PluginComponentId
      -> McpServerId

用户手工添加的独立 MCP server 仍可存在，owner 为 user-managed connection，不强制伪装成 Plugin。

### 15.4 extensions/skills：保留 snapshot/runtime，移出 package 顶层职责

SkillRepository 当前设计应继续保留：

- immutable snapshot；
- content-hash identity；
- resource containment；
- references/assets 有界读取；
- global/session enablement；
- source ownership；
- 删除与 snapshot reference 生命周期。

不应把这些逻辑搬入 PluginRegistry。

真正需要移动的是当前：

    extensions/skills/connector/ConnectorPackageReader

它实际上已经负责 foreign plugin manifest、MCP config、Skills、ZIP 安全边界和 migration
diagnostics，应提升成独立 Plugin package import 层，例如：

    extensions/plugin/import
      PluginPackageReader
      PluginPackagePreview
      PluginComponentPreview

然后分别调用 Skill importer、MCP config adapter 和 Helix native extension resolver。

### 15.5 ConnectorService：收窄成连接生命周期服务

建议拆分为：

    PluginInstallationService
      preview package
      validate manifest/components
      install / update / uninstall
      revision / content hash
      component ownership
      session/default selection

    ConnectorService
      endpoint configuration
      credential binding
      OAuth
      connection test
      MCP enable / disable
      revoke / reconnect
      remote consent lifecycle

这样 Mobile Use 可以是没有 Connector 的 native Plugin，Cloudflare Docs 可以是只有 MCP
component 的 Plugin，Review Workflow 可以是只有 Skill 的 Plugin，GitHub 则可以同时包含
Skill + MCP。

### 15.6 Marketplace：改成 Plugin-first

当前 MarketplaceItemType = CONNECTOR | MCP | SKILL 已经与 Agent Plugins 的安装模型不一致。

目标应改成：

    MarketplaceItem
      type = PLUGIN
      components = [SKILL, MCP, NATIVE]

UI 仍然可以按 Skills、Connections、Device、Productivity、Coding 等 category/component
筛选，但安装、升级、卸载、版本和来源的顶层对象统一为 Plugin。

例如：

    Mobile Use
      Native

    Cloudflare Docs
      MCP

    GitHub
      MCP + Skill

    Review Assistant
      Skill

这样 Marketplace status 不再按三种 item type 分别推导，而是先读取 Plugin installation，
再展示各 component 状态。

### 15.7 PluginRegistry：统一身份，不成为万能 registry

PluginRegistry 已提升为 AppContainer 级唯一实例，这是正确方向。

长期应负责：

- installed/bundled Plugin identity；
- version；
- manifest；
- native runtime binding；
- component metadata；
- installation enabled/disabled；
- pluginId 到 components 的查询。

但不应该承担：

- Skill 文件读取；
- MCP handshake；
- Tool dispatch；
- approval；
- effect tracking；
- AgentLoop；
- arbitrary package code loading。

PluginRegistry 的核心价值是统一身份和 provenance，不是成为第二个 AppContainer。

### 15.8 ToolRegistry：继续作为唯一 runtime tool contract registry

Built-in、Plugin native runtime、MCP、A2A 都应最终进入同一个 ToolRegistry，再由 Dispatcher
执行。

必须长期保持：

    Plugin installed != tool executable
    Plugin enabled != approval granted
    Skill enabled != permission granted
    MCP connected != every MCP tool model-visible
    Marketplace item installed != all schemas permanently enter model context

Plugin system 不应成为绕过 permission / approval / effect truth 的新入口。

### 15.9 ToolRegistry 与 ToolImplementationRegistry 应单独原子化

当前 descriptor 与 executor 分别存放：

    ToolRegistry
      (name, version) -> ToolDescriptor

    ToolImplementationRegistry
      (name, version) -> ToolExecutor

MCP/A2A dynamic replace 是两个连续操作，Dispatcher 也分别 resolve descriptor 和 executor。
两张表各自线程安全，但没有跨 registry 的原子 snapshot，理论上存在 new descriptor +
old executor 的极短错配窗口。

建议单独引入 ToolBindingRegistry：

    ToolBinding
      descriptor
      executor

要求：

- register 原子；
- replace owner snapshot 原子；
- remove 原子；
- Dispatcher 一次 resolve 同一 ToolBinding；
- model tool exposure 只读取 descriptor projection；
- approval contract 继续绑定 descriptor contractHash；
- MCP/A2A dynamic replacement 通过同一个 binding transaction。

这是 Tool Framework P1，应与 Connector/Marketplace 产品重构分开。

### 15.10 Session selection 从 Connector-first 升级到 Plugin/component

长期目标：

    Session
      -> selected Plugin installations/components

组件内部仍保留自己的状态：

    Skill component
      -> SkillRepository session enablement

    MCP component
      -> MCP enabled tool selection

    Native component
      -> runtime-specific session/capability state

Plugin selected 不能自动 enable everything，否则会把 package selection 错当成 authority。

### 15.11 Tool exposure 与 Plugin 平台必须一起收敛

2026-09-29 证据校正：历史 AndroidWorld 试跑确认64项截断曾留下 `ui.click` 却缺少 `ui.snapshot`；后续P5还记录了Skill过度依赖搜索导致的额外模型往返。二者不能概括为“P5已证明核心write被挤掉”，也不证明当前源码仍有相同截断。完整来源与当前默认工具/发现分析见[工具曝光与发现专题](tool-exposure-and-discovery-2026-09-29.md)；安装量增长需要更好的组织与发现，不意味着全部schema进入每次请求。

长期必须明确：

    registered != admitted != model-visible

推荐：

    core window
      lifecycle / result / discovery / essential workspace

    contextual component window
      mobile / browser / files / skills

    dynamic remote window
      MCP / A2A discovered tools

安装 Plugin 只意味着能力可以被发现，不意味着全部 schema 永久进入每次模型请求。

### 15.12 数据模型建议

产品尚未发布，因此不需要为旧开发数据库保留冗余 Connector schema，可以 clean-slate 收敛：

    PluginInstallation
      id
      pluginId
      version
      source
      contentHash
      revision
      enabled
      defaultSelected

    PluginComponent
      installationId
      componentId
      type = SKILL | MCP | NATIVE
      componentRef

    SessionPlugin
      sessionId
      installationId

    SkillSnapshot
      existing SkillRepository identity

    McpServer
      existing MCP identity
      optional ownerComponentRef

原则：

- 不复制 Skill body 到 Plugin installation；
- 不复制 MCP credential 到 Plugin row；
- SecretStore 继续单独保存凭据；
- component ownership 只保存稳定 reference；
- historical audit 不因 uninstall 被重写。

### 15.13 推荐重构阶段

#### P0 — 已完成

- Agent Plugins 1.0 manifest floor；
- App 级唯一 PluginRegistry；
- PluginOrigin；
- Mobile Use native Plugin；
- root plugin.json import；
- API36 provenance smoke。

#### P1-A — Plugin package / installation

目标：

    ConnectorPackageReader -> PluginPackageReader
    ConnectorInstaller -> PluginInstallationService
    ConnectorCatalog -> PluginInstallationCatalog

退出条件：

- Skill-only package 不再构造成“假 Connector”；
- MCP-only / Skill-only / MCP+Skill / native Plugin 使用统一 installation identity；
- revision/content hash/ownership 只有一个真相；
- install/update/uninstall 原子；
- Connector package 不再是新代码路径的顶层安装概念。

#### P1-B — Tool binding atomicity

    ToolRegistry + ToolImplementationRegistry
      -> ToolBindingRegistry

退出条件：

- Dispatcher 一次读取 descriptor + executor；
- MCP/A2A replace 无跨 registry 窗口；
- contractHash / approval / audit 测试不退化。

#### P2-A — ConnectorService 收窄

只保留 remote connection、auth/OAuth、connection test、MCP enable/disable、revoke/reconnect。
package ownership/install/update/uninstall 全部移出。

#### P2-B — Marketplace Plugin-first

    MarketplaceItemType -> PLUGIN
    components -> SKILL | MCP | NATIVE

退出条件：

- UI 顶层不再把 Skill/MCP/Connector 当作互斥安装类型；
- install/status/uninstall 基于 Plugin installation；
- component 状态仍可独立显示。

#### P2-C — Session Plugin/component selection

将 SessionConnector 迁为 Plugin/component selection，但不能自动授予 tool permission、
Accessibility、OAuth、MCP selected tools 或 Skill authority。

#### P3 — 生态扩展

再考虑 generic portable Agent Plugin ZIP、remote Mobile Use MCP provider、Desktop Use、
additional host-known native runtimes 和 remote Marketplace catalogs。

仍不建议直接开放 arbitrary DEX/JAR/APK code injection 或任意 JS hook 进入 Helix 主进程。

### 15.14 推荐开发顺序

建议：

    1. PluginPackageReader / PluginInstallationCatalog
    2. PluginInstallationService
    3. ConnectorService 收窄
    4. Marketplace Plugin-first
    5. Session Plugin/component selection

    并行独立轨：
    6. ToolBindingRegistry

前五项属于产品扩展生命周期统一，可以共享数据模型和 ownership 迁移；ToolBindingRegistry
属于执行基础设施，应独立验证，不与 Marketplace/Connector 产品重构混成一个大变更。

### 15.15 最终目标

最终用户看到的是统一 Plugins：

    Plugins
      Mobile Use
        Native
      GitHub
        MCP + Skill
      Cloudflare Docs
        MCP
      Review Workflow
        Skill

底层仍保持：

    Plugin identity/lifecycle
      -> Skills -> SkillRepository
      -> MCP -> McpAppService
      -> Native -> host runtime
                    -> ToolRegistry
                    -> Dispatcher

这样既能对齐主流 Agent Plugin 生态，也能保留 Helix 已验证过的 shallow harness、
permission、approval、effect truth 和 runtime isolation。
