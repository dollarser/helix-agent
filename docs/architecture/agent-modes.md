# Agent 模式、Goal 与上下文

四种模式共用模型工具循环。工具曝光和 Policy 决定允许行为，类名包含 Chat 不意味着存在四套执行器。

| 模式 | 契约 |
| --- | --- |
| Chat | 只读交互与允许的内置元数据动作 |
| Plan | 只读研究和计划元数据；审阅计划不批准所有执行期工具 |
| Act | 执行当前任务；Turn 结束与任务完成分别展示 |
| Goal | 持久目标、累计预算、显式激活后的连续运行与恢复 |

## Goal

[Goal 契约](../adr/goal/001-lifecycle-and-completion.md)统一 create/get/update/report、版本与目标编辑、激活、预算和完成语义。模型创建或编辑需要可归因的直接用户意图；Plan 不借元数据操作激活 Goal。编辑活跃目标须按修订与结算边界应用，不能覆盖正在执行的版本。

目标状态与运行激活分离；用户停止、新消息抢占、等待输入、预算耗尽及异常均影响准入。允许离开前台后在合法后台运行窗口继续，但进程重启不自动重新激活，系统拒绝保活时如实暂停。工具仍遵守当前授权。

模型报告完成与依据，harness 在 Turn 结算消费；不恢复全局强制 verifier evidence。可继续修复的测试失败不是自动 BLOCKED。多维预算跨 run 累计，恢复预算不等于自动续跑。HXA-208 的交付证据与后续剩余任务分别从[状态](../development/status.md)进入。

## 上下文与执行入口

[Turn 协调](../adr/agent/001-turn-coordination.md)、[压缩](../adr/agent/002-context-compaction.md)、[附件](../adr/agent/003-attachments.md)规定生产路径。模型请求前检查压缩，摘要完成后重建上下文，保留 tool call/result 配对、附件绑定和恢复语义。

Prompt sections 固定顺序、来源与作用域；Skill 或项目文本不会因进入 system prompt 获得可信权限。界面只能提交、取消和观察；后台入口复用同一准入边界，不能自行构造第二个 Loop。

[有界委托](../adr/agent/004-bounded-delegation.md)是受门禁约束的设计，不代表子 Agent、可执行 workflow 或递归编排已经交付。
