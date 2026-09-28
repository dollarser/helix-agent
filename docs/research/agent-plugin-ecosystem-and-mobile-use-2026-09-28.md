# Agent 插件生态与 Mobile Use 调研（2026-09-28）

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
[Plugin Platform P0 / Mobile Use MVP 证据](../evidence/development/plugin-platform-mobile-use-mvp-2026-09-28.md)。
