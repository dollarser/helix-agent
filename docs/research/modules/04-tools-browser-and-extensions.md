# 工具、浏览器与扩展生态

> 更新：2026-09-25。综合 tool exposure、browser、MCP/Skill/Connector/A2A 和竞品研究。

## 1. 工具架构原则

现代 Harness 的工具系统不是“把所有函数塞给模型”，而是：

- registry 中能力可以丰富；
- 当前请求只暴露相关/允许/可发现的 schema；
- permission/scope/effect 在执行前由平台重新解析；
- 模型、Skill、MCP metadata 不能自授权；
- output 是 untrusted，后续 effect 必须重新过 Dispatcher。

OpenCode 当前同样把 Build/Plan/subagent 与 allow/ask/deny permissions 作为 Harness contract；Operit 提供 per-tool Allow/Ask/Deny 和 marketplace/workflow。

来源：
- https://dev.opencode.ai/docs/permissions/
- https://opencode.ai/docs/zh-cn/agents/
- https://github.com/AAswordman/Operit

## 2. Tool exposure

旧研究中“需要 tool router”不应直接成立。Helix 已有 registry、MCP discovery、mode/policy 和 request assembler。

推荐：

1. built-in 高频基础工具保持稳定短名；
2. 大 MCP/catalog 用 search/discovery 按需加载 schema；
3. context/mode/workspace 决定可见性，但可见性不等于授权；
4. schema 压缩必须保证 tool identity/required args/risk semantics 不丢；
5. 用真实任务量化 token、选工具正确率和完成率，再决定是否增加模型路由器。

## 3. 浏览器

Helix 已有自有 WebView + typed browser tools：snapshot/find/click/type/scroll/navigation/screenshot/download。其 token/generation/origin/TTL 和 URL/sensitive-field fail-closed 边界应保留。

### 当前真正值得补的能力

**工具产出多模态回流。** 浏览器截图如果只落 Workspace reference，视觉模型无法直接检查 layout/canvas/image。正确方向是建立受预算和 provenance 约束的 tool-result image channel，而不是允许模型向页面注入任意脚本。

### 不建议照搬 Codex Control Chrome/任意 Node bridge

Android Chrome 没有同等扩展控制模型；Helix 也明确不应抽取其它 App 的 cookie/credential。自有 WebView 会话已经可以保留 Helix 自己的登录态。

通用 QuickJS 继续保持 isolated/offline/no privileged host bridge；需要 shell/network 的开发任务走受权限控制的 PRoot/CLI，不把 QuickJS 改成万能宿主脚本。

## 4. Extension：MCP / Skill / Connector / A2A

分层应保持：

- **MCP**：外部工具协议；
- **Skill**：流程知识/提示/资源；
- **Connector bundle**：来源、Skill/MCP/配置的可移植打包与生命周期；
- **A2A**：外部 Agent task 协议，不等于 Tool，也不继承本机 permission。

扩展安装、启用、更新和调用必须保持 capability/permission 不提升；来源签名只能说明来源，不自动把 ToolCall 变成 allow。

## 5. Marketplace / Workflow

Operit 已经验证统一 marketplace + ToolPkg/Skill/workflow 在移动端有产品价值，但 Helix 不应先复制“市场规模”，而应优先：

- portable bundle；
- install preview；
- dependency ownership；
- enable/disable/rollback；
- permission diff；
- source/signature；
- 真实调用闭环。

Workflow DSL 只适合用户明确需要的 repeatable automation；普通 agent task 仍由模型 loop 驱动，避免 Harness 变成第二个 Tasker。

## 6. Android device automation

Accessibility/Shizuku/Root/Intent 等是 capability provider，不是权限绕过。UI automation 的高风险点：

- target identity；
- stale screen state；
- irreversible tap/input；
- foreground/background变化；
- sensitive field；
- user takeover。

模型应使用类型化 action 与当前观察事实，不把“视觉模型能看屏幕”理解成可自动获得系统权限。

## 7. 冲突裁决

| 冲突 | 裁决 |
| --- | --- |
| 类型化工具 vs 通用任意脚本 | 默认类型化工具；明确 Runtime 提供脚本但不做权限 bypass |
| 全量 schema vs 动态发现 | built-in 稳定集合 + 大 catalog 按需加载 |
| Browser DOM vs screenshot | 二者互补；补 tool-result multimodal，不放开任意 page JS |
| MCP / Skill / A2A 是否统一成一个插件概念 | 产品可统一管理入口，执行/信任语义保持分层 |
| marketplace 是否当前核心 | 先做安全 lifecycle 和真实调用闭环，再扩大 catalog |
