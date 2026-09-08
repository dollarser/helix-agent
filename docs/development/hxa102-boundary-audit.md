# HXA-102 后端边界证据核对

日期：2026-09-08。范围为当前 M0～M11 收尾，真机和长稳仍后置。本表将累积记录中的“后续待验”与后来产生的具体证据对应，不把历史 APK 结果改写成最新 APK 全量通过。证据根目录为 `build/main-verification/`；构建快照与设备验收分开记录。

## 当前门禁

私有产物删除修复后，`post-private-delete-host-result.json` 证明2713 JVM零失败/错误/跳过、1162源码配置指纹前后相同；根Debug/Release Lint和App/Runtime构建通过，八主包指纹`post-private-delete-release-result.json`。完整App946保留旧快照，后续生产变更的影响复验30项及完整订阅Chat8项通过，见`post-private-delete-device-result.json`与`subscription-chat-routing-result.json`。Release未签名，不代表发布验收。

## 已有阶段与证据边界

| 路径 | 已获得的具体证据 | 主要记录 | 仍不能由此推断 |
| --- | --- | --- | --- |
| 模型流 | API29/36，三协议、headers/body 两窗口，12 次 SIGKILL、24 次恢复，无重发 | `model-kill-api29-36-host-signal-fixed/verified-summary.json` | 官方服务/真实网络切换、所有发行包当前快照 |
| 审批与 QuickJS | 等待审批及实际 isolated worker 执行，4 次强杀、8 次恢复；后续显式拒绝与再次重启已测 | `js-approval-boundaries/verified-summary.json`；`recovered-approval-label-emulator-5596/result.json`、`5598/result.json`（后者同目录名前缀） | 中断前审批可无条件复用；拒绝测试不是重新批准执行 |
| MCP | 生产 Goal/审批/SDK/HTTP，远端 tools/call 已开始，两 API 强杀后及显式 Continue 计数保持 1、无重放 | `mcp-live-kill-api29-unique-call/result.json`、`mcp-live-kill-api36/result.json`、`mcp-continue-result.json` | 远端副作用已撤销、真实第三方业务或 UI 点击验收 |
| A2A | 生产 Goal 调用与实际 HTTP、两次强杀、四次恢复，显式对账保留同一 Task ID | `a2a-live-kill-current/verified-summary.json` | 真实远端业务完成 |
| 文件 | 实际 write 发布后未结算与模型回填窗口，4 次强杀、8 次恢复；原子发布层另测 | `file-settlement-boundary-summary.json`、`file-publish-kill-summary.json` | 其他 UI/浏览器执行边界自动被覆盖 |
| 浏览器 | 实际点击后导航/回填期间两次强杀；新增结果未结算双 API 强杀通过，动作不重复 | `browser-goal-kill-summary.json`、`action-unsettled-result.json` | 不是长稳或真实站点业务 |
| Accessibility | 实际 snapshot/click 后回填期间及结果未结算两种窗口双 API 强杀通过，点击计数保持 1 | `ui-generation-kill-summary.json`、`action-unsettled-result.json` | 真机授权/敏感界面 |
| PRoot | 完整 Goal/调用/Job 绑定运行中强杀；成功结果本地持久化、确认前后强杀与完整恢复导航 | `proot-goal-binding-summary.json`、`proot-terminal-ui-kill-summary.json`、`proot-ack-kill-navigation-summary.json` | 单独 `proot-owner-kill-summary.json` 本身没有 Goal，不能用于替代完整链路 |
| CLI/M11 | 四平台完整 Goal 成功持久化+ACK 后强杀，原 Job 恢复；运行中与 fetch/persist 窗口分别有证据 | `cli-goal-success-summary.json`、[CLI 恢复记录](cli-result-durable-recovery-gap.md) | `cli-owner-kill-matrix/` 单独客户端测试不是 Goal；付费账号另界定 |
| Goal 完成证据 | write/edit/PRoot 真实模型完成、人工复核 UI、暂存/录入/提交前后强杀、读取取消与快照读取中强杀 | [HXA-102 后续记录](../completion-records/HXA-102.md)、`evidence-read-kill-result.json` | 快照读取检查点不是 PRoot 解压中的强杀，且不代表任意 I/O 即时可中断 |

## 下一步实际检查点

1. 浏览器与 Accessibility 的“动作已发生、结果未结算”窗口已补齐双 API 4kill/8恢复，见 `action-unsettled-result.json`；该项关闭。
2. MCP 不明确结果后显式 Continue 服务意图已完成双 API 2kill/4恢复验证：提示不可继续，原记录/审批不变、零重发。本项关闭；不等于远端效果核清或任意业务自动恢复。
3. 本轮功能及非真机、非长稳矩阵已按源码影响和证据边界收口；M11、API35短时资源与双API真实低内存门控补验见当前待办和HXA-103记录。自然LMK、真机和长稳仍独立。
4. HXA-147统一交互/UI已验收，见 [完成记录](../completion-records/HXA-147.md)；当前全局收口审计见 [总表](main-closure-audit.md)，不再把该UI任务列为待实施。

## 相关记录

- [当前待办](main-optimization-todo.md)
- [合并验证报告](main-merged-verification.md)
- [M11 交接](m11-handoff.md)
