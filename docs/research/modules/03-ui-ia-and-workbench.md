# UI、IA 与移动工作台

> 更新：2026-09-25。综合早期 UI research、HXA-218/219、Operit/PalmClaw 最新公开结构。

## 1. 当前产品形态

Helix 不应该变成“手机 IDE 缩小版”，也不应该回退成纯聊天。合理结构是：

> **Conversation-first task workbench**：Chat 是主任务入口；Files/Tasks/Artifacts/Browser/Terminal/Git 等是围绕同一 Session/Workspace 的专业视图。

这和 Operit 当前“task-oriented agent workspace + chat + workspace + terminal/browser”的产品演进一致；PalmClaw 则证明更轻量的 session-centric UI + unified permission/settings 也可成立。

## 2. 已验证的 UI 方向

HXA-218/219 已经实现/验证的方向应保留：

- Header 减负：导航 + 标题 + contextual actions；
- Session details 使用 sheet，而不是把所有状态塞标题栏；
- Composer 保留 mode/model，但减少次要按钮噪音；
- 运行状态用真实 stage/等待原因，不伪造百分比；
- ToolCall 默认 compact，可展开；
- Artifact 在会话内就地预览，返回时保留上下文/草稿；
- Drawer 分组，而不是 10+ 平级入口。

## 3. 导航 / IA

当前不建议立即改 bottom navigation。原研究的裁决仍合理：

- 保留 drawer + 分组；
- 高频 Chat/Workspace/Tasks 可通过快捷入口/最近项缩短路径；
- 只有真实用户测试证明 bottom nav 明显更好，再改 shell；
- route graph 不应为了视觉分组同时大重写。

### 推荐分层

- **会话**：Sessions / search / new conversation；
- **工作**：Tasks / Artifacts / Workspace(Files/Git/Terminal)；
- **扩展**：Browser / Extensions / Capabilities；
- **设置**：Provider/Model、Permissions、Runtime/Developer、Appearance；
- **管理/审计**：Readiness、Audit、About/diagnostics。

长期可以让 Workspace 成为 Files/Git/Terminal/Artifacts 的统一上下文，但不要把这些专业页面全塞进 Chat。

## 4. Settings

PalmClaw 最近公开版本专门做 UI/Settings/permission 统一，Operit 也把 model/tool/workspace/marketplace 形成独立设置域。这说明 Helix 的 Settings 不应继续按技术类名平铺。

推荐按用户问题分组：

1. 模型与连接；
2. 权限与安全；
3. 工作区与文件；
4. 扩展与能力；
5. 开发环境（Advanced）；
6. 外观/交互；
7. 数据、导出与审计；
8. 关于/诊断。

默认页只展示常用控制；复杂 JSON/高级策略进入二级页。

## 5. 任务状态与恢复 UX

按已接受的 successor-Turn recovery，移动 UI 可以简化为：

```text
上次任务被中断
- 已完成：...
- 1 个操作结果不确定
[继续任务] [查看不确定操作]
```

用户直接发送新消息也可以创建 successor Turn。

不要暴露 `BUILDING_CONTEXT`、`WAITING_MODEL`、`ToolCallState.INTERRUPTED` 这类内部枚举作为主要 UX；映射成用户可理解的“正在准备 / 等待模型 / 需要确认 / 已中断”。

## 6. Result / Diff / Terminal 第二轮

仍值得继续：

- 文件变更/patch 的 in-place diff；
- Tool group summary 与关键结果突出；
- Terminal Job 和 Chat Task 互相跳转；
- Artifact/文件 availability 明确 changed/missing/revoked；
- long task 的“最近发生了什么 / 正在等什么”；
- error recovery CTA 与结果上下文绑定。

## 7. @ / slash

仍是低优先级增强：

- `@` 用于稳定引用，不用于授权；
- `/` 只表达少量用户级动作，不复制工具/Skill 命令系统；
- 不应在 Engine owner 和 Workspace IA 尚未收口时抢优先级。

## 8. 移动竞品启示

### Operit

优势：Workspace、terminal、browser/device automation、tool progress、marketplace、local models 很完整。Helix 不必比“功能数量”，应在**任务闭环、权限一致性、恢复可解释性和更清晰 IA**上竞争。

来源：https://github.com/AAswordman/Operit

### PalmClaw

其近期更新集中在 UI refactor、settings、unified permission、per-session runtime，说明“先把信息架构和 session 状态做清楚”比继续加入口更重要。

来源：https://github.com/ModalityDance/PalmClaw

## 9. 冲突裁决

- Bottom nav vs drawer：当前继续 grouped drawer；需用户测试才改。
- Chat-centric vs workspace-centric：Chat 作为任务入口，Workspace 作为操作上下文，两者互补。
- 展示所有内部状态 vs 用户摘要：优先用户摘要，详情页保留审计/调试事实。
- 功能入口越多越强 vs 收敛 IA：选择收敛 IA，能力通过 context/action 暴露而不是不断增加一级 destination。
