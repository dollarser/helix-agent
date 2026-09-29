# 工具、浏览器与扩展生态

> 综合初稿：2026-09-25；工具曝光部分更新：2026-09-29。综合 tool exposure、browser、MCP/Skill/Connector/A2A 研究，其他比较仍按各自来源日期和证据边界理解。

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

旧研究中“需要从零增加 tool router”不应直接成立。Helix 已有 registry、跨来源发现、mode/policy 和 request assembler。完整数量口径、竞品核验、源码 SHA、历史评测与实验矩阵见[工具曝光与发现专题](../topics/tool-exposure-and-discovery-2026-09-29.md)，本节只维护综合判断。

**当前取舍：小而完整的默认工具组 + 统一按需发现 + 有界请求，不以工具总数或越少越好验收。**2026-09-29 源码静态默认名称集合为22个，有有效自动化会话时加10个UI；它们是过滤前候选，不是每次实际发送数。请求硬上限仍为64，搜索默认8/最多16。ADR及历史评测的21/30属于较早口径；差异在专题中显式记录，不静默改写历史或有效决定。

已完成的跨来源搜索、共享禁用过滤、UI观察/动作成组以及Skill正文按需读取应保留。优先研究的是AND子串检索与名称排序、全替换窗口/零命中、MCP合计16→17突变、最终schema的token预算、Skill元数据检索，以及R1已经承接的原子绑定。具体影响仍区分代码事实和待验证推断。

分组按业务能力与必要依赖，不只按来源；Skill是内容、MCP是协议、Plugin是归属/安装，索引只是事实投影。发现与装载不授予执行权限，禁用和撤销继续作用于搜索、请求及实际调用。schema精简不能删除required、enum、身份和必要前置条件。

R1和R2-A保持现有行为并收敛绑定/编译入口；有限保留、阈值与检索等策略属于后续R2-B研究输入，尤其窗口语义须先更新同主题ADR。用冻结任务比较召回、参数正确率、发现往返、token、延迟和真实目标完成情况，不另造规划器、权限系统或新重构主线。

## 3. 浏览器

Helix 已有自有 WebView + typed browser tools：snapshot/find/click/type/scroll/navigation/screenshot/download。其 token/generation/origin/TTL 和 URL/sensitive-field fail-closed 边界应保留。

### 工具视觉回流的承接状态

原研究识别的“截图只返回 Workspace reference、模型看不到像素”已由 [HXA-225](../../completion-records/HXA-225.md)承接：`view_image` 与浏览器截图接入受预算/来源约束的工具视觉通道，见[使用说明](../../product/image-reading.md)。后续真实工具图片识别的有限证据见[最终收口](../../evidence/development/final-closeout-2026-09-29.md)，不扩大为任意视觉任务都通过。

Mobile Use 整机截图、设备内模型视觉和文档/视频解析仍是不同能力，不能因图像回填已交付就视为全部完成。候选入口看[待裁决索引](../../development/candidate-decisions.md)。

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

插件/Mobile Use 与异步执行的原比较分别见[专题目录](../topics/README.md)；通用 binding、安装生命周期、ContextCompiler 和等待的目标规范汇总于[Harness 方案](../../architecture/harness-refactor-plan.md)，不在多个研究模块复制完整实施步骤。

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
| Browser DOM vs screenshot | 二者互补；复用已交付视觉回填，不放开任意 page JS |
| MCP / Skill / A2A 是否统一成一个插件概念 | 产品可统一管理入口，执行/信任语义保持分层 |
| marketplace 是否当前核心 | 先做安全 lifecycle 和真实调用闭环，再扩大 catalog |
