# 项目结构与文档治理

2026-09-22 整理；当前复核基线 `9a9b25dd`。早期审查基线为 `645fa680`，其中取消、回执和输入交付结论已被214～216改变。执行引擎缺陷、证据和优先级统一维护在[深度复审](execution-engine-deep-review-2026-09-22.md)，本页不重复维护问题清单或旧源码行数。

## 保留现有模块边界

`core/`、`provider/`、`tools/`、`runtime/`、`feature/`、`extensions/`、`spikes/`、`testing/` 的方向可以保留。先修复状态与调度问题，再按明确所有权拆分；文件行数不是新建Gradle模块的充分理由。

| 位置 | 后续结构优化 | 保留边界 |
| --- | --- | --- |
| ChatService | 在app内逐步分离运行资源/终局协调与页面、草稿适配 | 复用AgentTurnHost/AgentRuntime；不产生第二个submit/cancel/settle owner |
| ToolDispatcher | 按权限、执行与结果校验提取内部阶段数据 | 保留统一入口、执行前授权重读、审批消费与每attempt审计；不做可任意重排的插件链 |
| ProotJobRunner | 分离输入准备、进程句柄和结果物化 | jobId/generation/lease与一个资源owner不变；手动终端与Agent Job仍分开 |
| TurnReducer及测试 | 把参考串行模型与生产BatchTurnRuntime覆盖清楚区分 | 先映射并补齐生产不变量测试，再考虑移动参考代码；不删测试换绿 |
| scripts | 有后续维护需要时，将跨任务使用的host slot/owned runner移到稳定入口 | 更新调用方并保留历史取证脚本；本轮不做整目录迁移 |

组合根集中装配依赖是合理职责，不因DefaultAppContainer较大就引入Service Locator。只有测量显示构建、依赖或测试收益后，才考虑新增独立模块。

QuickJS isolated UID、PRoot/订阅私有同UID进程是已经接受的不同执行域，不因结构调整合并成“统一沙箱”。外部副作用不能由数据库回滚，也不能在恢复时自动重放。

## 文档收敛规则与本轮处理

- 当前事实和顺序只维护在[状态](../development/status.md)与[工作计划](../development/next-work-plan.md)；任务保存范围，完成记录保存交付时点证据。
- 删除三份已结束交接：`claude-handoff-207-191-206.md`、`marketplace-branch-handoff.md`、`small-model-handoff.md`；独有定位和限制汇入[历史交接汇总](../evidence/development/completed-handoffs-2026-09-22.md)，原文在Git历史中可查。
- 保留198准备、准备批次和历史缺陷记录，页首明确历史基线；不让旧文件所有权、WAITING_CORE或“尚未授权”继续充当当前指令。
- 删除未提交的一次性文档替换脚本：工作已体现在diff中，重复执行会插入重复索引或再次删除文件。保留能复跑并核对源码身份的审查探针。
- 本页删去与深度报告重复的旧停止调用链、过时行数和“214后再实现216”的建议；不据此删除生产代码、测试或已有验收证据。

本轮只整理文档和审查工具，没有修改生产Kotlin、数据库或依赖。当前验证与剩余问题见[深度复审](execution-engine-deep-review-2026-09-22.md)和[遗留内容核对](../evidence/development/document-review-convergence-2026-09-22.md)。
