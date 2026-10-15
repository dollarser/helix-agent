# 工具、浏览器与扩展生态

> 综合初稿：2026-09-25；工具曝光与专题承接更新：2026-10-15。综合 tool exposure、browser、MCP/Skill/Connector/A2A 研究，其他比较仍按各自来源日期和证据边界理解。

2026-10-11 的[插件架构调研](../topics/agent-plugin-architecture-research-2026-10-11.md)保存八类 Codex 插件及 Codex 公开核心、Claude Code、Gemini CLI、OpenCode、DSH 与 Helix 的机制和证据对照；补充 Computer Use 采用 MCP 的实现及推断收益，并区分核心工具、官方插件包与宿主框架插件。样本支持原生与 MCP 等方式混合接入，未证明内置插件普遍优先 MCP。独立的[Helix 优化方案](../topics/helix-plugin-architecture-optimization-2026-10-11.md)承接 Skill 激活/调用规则、配置生效、兼容诊断、Browser 归属和共享资源释放；§6.5 补充原生/IPC/CLI/MCP 的选择、客户端导入与服务端导出的边界，保留建议顺序与验收条件。

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

旧研究中“需要从零增加 tool router”不应直接成立。Helix 已有 registry、跨来源发现、mode/policy 和 request assembler。[2026-09-29 专题](../topics/tool-exposure-and-discovery-2026-09-29.md)保留当时数量口径与评测；当前源码、桌面及移动端对照、具体优化建议见[2026-10-15 工具与插件专题](../topics/tool-and-plugin-competitive-review-2026-10-15.md)。

**当前取舍：小而完整的默认工具组 + 统一按需发现 + 有界请求，不以工具总数或越少越好验收。**2026-10-15 核验的请求装配路径已无旧的统一 64 个工具截断。搜索已有精确名称优先、部分关键词排序、来源／类别过滤、中文别名、有效旧工具保留与零命中不清空；不能继续将 AND 全匹配、全替换窗口或 MCP 16→17 数量突变写成当前缺陷。

近期工具保留与小型 MCP 预曝光已采用 Schema 字节预算；单次搜索窗口不等于注册总数上限。优先改善文件／网页检索缺口、来源用途摘要和工具前置依赖，尤其 Mobile Use 单独发现截图时的配套。事实与推断、拟议接口及验收设计分别见新专题，不把建议写为已交付。

分组按业务能力与必要依赖，不只按来源；Skill 是内容、MCP 是协议、Plugin 是归属／安装，索引只是事实投影。发现与装载不授予执行权限，禁用和撤销继续作用于搜索、请求及实际调用。Schema 精简不能删除 required、enum、身份和必要前置条件。

2026-10-15 R2-B 首个有界观察提示对照已结束，未证明稳定收益，具体边界见 [status](../../development/status.md)。不把该实验重新列为待做，也不将其结果外推为检索算法效果。后续独立改进仍用任务完成情况、参数正确率、发现往返、token 和延迟比较；不另造规划器、权限系统或新重构主线。

## 3. 浏览器

Helix 已有自有 WebView + typed browser tools：snapshot/find/click/type/scroll/navigation/screenshot/download。其 token/generation/origin/TTL 和 URL/sensitive-field fail-closed 边界应保留。

### 工具视觉回流的承接状态

原研究识别的“截图只返回 Workspace reference、模型看不到像素”已由 [HXA-225](../../completion-records/HXA-225.md)承接：`view_image` 与浏览器截图接入受预算/来源约束的工具视觉通道，见[使用说明](../../product/image-reading.md)。后续真实工具图片识别的有限证据见[最终收口](../../evidence/development/final-closeout-2026-09-29.md)，不扩大为任意视觉任务都通过。

Mobile Use 整机截图和原生手势已由 HXA-243 等后续工作接入；具体后端与有限设备证据看[当前状态](../../development/status.md)。设备内模型视觉和文档/视频解析仍是不同能力，不能因图像回填已交付就视为全部完成。候选入口看[待裁决索引](../../development/candidate-decisions.md)。

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

[helix-docs 专题](../topics/helix-docs-design-2026-10-14.md)承接 Helix 官方功能／配置／排障查询：当前已有内置 Skill、版本化随包帮助预览及资源发现／读取路径；完整实现边界见专题 §11。静态帮助 UI、官方在线服务、双语正文和正式发行不能据此视为完成。PDF／Word 处理仍归 documents 插件，后续建议不改变当前排期。

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

2026-09-29 的 [Mobile Use 设备就绪与可靠性专题](../topics/mobile-use-device-readiness-and-reliability-2026-09-29.md)固化了钉钉界面辅助、锁屏与后台讨论，以及当时的源码风险。手机截图等后续交付以[当前状态](../../development/status.md)为准，不恢复为待开发；动作失败分类、等待、观察元数据和恢复反馈按当前源码及具体验收旅程复核。自动解锁和调整授权寿命仍需独立决定，专题不修改当前实施顺序。

## 7. 冲突裁决

| 冲突 | 裁决 |
| --- | --- |
| 类型化工具 vs 通用任意脚本 | 默认类型化工具；明确 Runtime 提供脚本但不做权限 bypass |
| 全量 schema vs 动态发现 | built-in 稳定集合 + 大 catalog 按需加载 |
| Browser DOM vs screenshot | 二者互补；复用已交付视觉回填，不放开任意 page JS |
| MCP / Skill / A2A 是否统一成一个插件概念 | 产品可统一管理入口，执行/信任语义保持分层 |
| marketplace 是否当前核心 | 先做安全 lifecycle 和真实调用闭环，再扩大 catalog |
