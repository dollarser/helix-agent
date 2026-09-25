# 产品定位与竞品基线

> 更新：2026-09-25。当前综合研究，不是产品/架构授权。
> 竞品版本与来源台账见 `docs/product/competitive-*`；本页只维护影响 Helix 方向的综合判断。

## 1. Helix 应解决什么问题

Helix 的合理定位不是“把桌面 Claude Code/Codex 原样塞进 Android”，也不是“又一个 BYOK 聊天客户端”。更稳定的定位是：

> **Android 本机 Agent 工作台：以会话作为任务入口，把模型与本机文件、浏览器、项目工作区、终端/Runtime、Android 系统能力和扩展工具安全连接起来，最终交付可检查、可恢复的结果。**

本机执行不等于：所有模型都本地运行、数据永不出网、后台永不终止、任何系统能力都默认开放。

## 2. 竞品分层

### 桌面/通用 Harness

- **Codex**：Thread/Turn、App Server、工具/sandbox/approval、skills、多 thread/agent 并行。强项是统一 Harness + 多客户端 + 长任务协作。
- **Claude Code**：Session resume、Plan/permissions、checkpoint/rewind、subagents/background/batch，强项是让模型自主工作同时保留用户控制点。
- **OpenCode**：Session/Server、Build/Plan/subagent、细粒度 permission、自定义 skills/MCP，代表开源浅策略 Harness。
- **DeepSeek Harness**：event-sourced Session、durability checkpoint、crash repair、工具/模型 loop，可作为持久化和中断语义参考。

### Android 直接竞品

- **Operit**：当前公开能力最完整的一类 Android Agent 工作台，包含 workspace/terminal/browser/device automation/MCP/Skill/ToolPkg/workflow/local inference、工具 Allow/Ask/Deny 和历史/备份恢复。
- **PalmClaw**：原生 Android、本地优先、Memory/Skills/Tools/Channels、per-session runtime、统一权限和较轻量 UI。
- **AndCode / ClawMobile / DSH Android 等**：代表“Linux/CLI harness 跑在 Android”或“手机作为已有 Harness 客户端”的不同路线。
- **RikkaHub/Cherry Studio/LobeHub/Chatbox 等**：更接近增强型模型客户端/Agent 前端，是 Provider、MCP、知识库和移动 UX 的重要参照，但不应自动计入完整本机执行能力。

## 3. 最新外部信息带来的修正

### Codex mobile 不等于“手机本地执行”

OpenAI 2026 的 Codex mobile 重点是让用户从手机继续监督运行在 laptop/devbox/remote environment 的 active work。对 Helix 的启示是：**移动 UX 与执行位置可以解耦**，但 Helix 的差异点仍是“Android 本机能力真正进入 Tool/Permission/Runtime”。

来源：
- https://openai.com/index/work-with-codex-from-anywhere/
- https://openai.com/index/unlocking-the-codex-harness/

### Operit 已经从“工具型聊天”发展为完整工作台

当前官方仓库公开 workspace binding、Ubuntu terminal、browser agent、device automation、Allow/Ask/Deny permissions、parallel conversations、MNN/llama.cpp、marketplace/workflows 等；因此“移动端没有真正 Agent 工作台”的旧判断已经失效。

来源：https://github.com/AAswordman/Operit

### PalmClaw 验证“原生轻量 Harness + session + permission”也可成立

其当前路线强调 Android-native runtime、per-session processing、统一 permission、skills/tools/channels，而不是复制完整 Linux IDE。说明 Helix 不必在“全 Linux Harness”和“纯聊天”之间二选一。

来源：https://github.com/ModalityDance/PalmClaw

## 4. 综合产品方向

### 应继续强化

1. **会话工作台，而不是工具列表**：用户应围绕任务看到进度、工具、结果、文件/Artifact 和等待原因。
2. **本机 capability + 明确 authorization**：Android 文件/浏览器/系统能力是真差异点，但必须受统一 scope/effect/approval 控制。
3. **Workspace 作为操作上下文**：Chat、Files、Tasks、Terminal、Git、Artifacts 各自有专门视图，但围绕同一 Workspace/Session 联系起来。
4. **模型/provider 开放性**：BYOK、兼容 API、本地模型都是一等 Provider；本地模型可以直接驱动完整 Agent loop，不把模型供应商或执行位置锁定成产品核心。
5. **结果闭环**：用户能打开文件、查看 diff/output、返回来源会话；“模型说完成”与“结果真实存在/正确”分开。

### 不建议照搬

1. **桌面 IDE 布局**：手机不应硬复制多 pane IDE；优先 conversation-first + in-place result + 深入页面。
2. **通用任意脚本成为万能工具路由**：Android 高权限和共享数据边界更敏感，优先类型化工具/明确 Runtime。
3. **把工作流控制写死在 Harness**：模型已经能规划/复盘；Harness 应深管边界，不深管策略。
4. **以工具数量、Agent 数量、自动化复杂度作为“领先”指标**：必须回到任务完成率、人工步骤、恢复、资源和安全边界。

## 5. 当前差异化机会

| 方向 | 桌面 Agent | 直接移动竞品 | Helix 应争取的差异 |
| --- | --- | --- | --- |
| Android 本机能力 | 弱/远程为主 | Operit/PalmClaw 强 | 类型化能力 + 更严格授权/审计 |
| Coding Harness | Codex/Claude/OpenCode 强 | Operit/DSH Android 强 | 移动原生 UI + workspace/runtime 双路径 |
| 浏览器 | 桌面可有完整浏览器环境 | Operit 较强 | 自有 WebView + typed snapshot/action + 安全 token |
| 恢复 | Session/thread 强 | 多为 chat/workspace/backup | Session continuation + effect truth，而非过深 Turn rehydrate |
| 扩展 | MCP/Skills/plugins 成熟 | Operit marketplace 强 | portable bundle + permission-preserving extension |
| UX | 桌面多窗口/IDE | 移动工作台探索中 | conversation-first、少导航层、结果就地查看 |

## 6. 产品冲突裁决

- “端侧 Agent 必须尽量复制 Claude Code” vs “移动端应完全重新设计”：**取中间路线**。复用 Harness 原语（session/tool/permission/context），UI 和 Android capability 按移动约束重做。
- “工具越多越强” vs “少工具更稳定”：**不以数量裁决**。模型只暴露当前需要/可发现的能力，底层 registry 可以丰富。
- “本地优先 = 必须使用本地模型”：**拒绝等价**。本地数据/执行与本地推理是两个维度；但本地模型本身是一等 Provider，只要 capability/eval 满足要求，就允许直接驱动完整 Agent loop。摘要/分类只是低风险验证入口，不是产品限制。
- “恢复应该完全自动” vs “用户每次重新开始”：**推荐 Session 自动恢复 + successor Turn 显式继续；外部 effect 不确定必须保留人/状态核查。**

## 7. 重新评审条件

- Android 系统出现稳定、开放的 Agent-to-App 标准并覆盖大多数目标 App；
- 主流移动 Agent 的用户行为显示 conversation-first 明显劣于 workspace-first；
- 本地模型在目标机上对完整 Agent loop 的真实任务成功率、上下文容量和工具调用稳定性出现新的证据；
- 远程执行成为 Helix 主要用户路径，导致“本机工作台”不再是核心价值。
