# Bug Fix: main 四维审查二次复核与局部修复

Status: fixed
Date: 2026-09-13
Related HXA: HXA-190

## Problem

日期：2026-09-13。基线是 main `0bcd9d3` 加已有未提交内容，不把它等同于公开 HEAD 或 EV 冻结制品。本轮按所有者要求只修改 main，不修改、合并、重置或构建 `.claude/worktrees/harness-2.0`；不操作既有模拟器。归入 HXA-190 当前缺陷收口，不开展 Harness 2.0 架构迁移。

## Impact

会话停止可能误伤其他会话，忙碌拒绝可能丢弃附件草稿，异步绑定与输出收集的异常路径可能挂起或误报成功。

## Root cause

审批所有权使用全局指针，草稿清理早于准入确认，异步回调和输出 EOF 缺少独立完成判据；具体复核见下表。

## Fix and invariants

| 发现 | 复核与修改 |
| --- | --- |
| CLI 绑定无限等待 | 成立。只为初次 ServiceConnection 回调增加 30 秒等待边界，超时/线程中断解绑；不限制模型生成、长回复或已建立的流，不重放 Job。不能据此归因 EV 的 FD/thread 增长。 |
| 停止 A 误取消 B 审批 | 成立。全局最后审批指针改为 approvalId→turnId 集合；stop/stopTask 只唤醒目标 Turn 的审批，finishTurn 不清除其他 Turn 的卡。 |
| 确认附件后忙碌拒绝导致丢失 | 成立。launchTurn 返回准入结果；用户消息与附件绑定落库后才清除批准附件，忙碌明确提示，拒绝保留附件并恢复未发送文本。 |
| 发布初始 UI 异常导致 startGate 不释放 | 存在该异常路径。finally 必须释放 gate；发布未完成则取消 worker，由原 Turn 结算路径处理。 |
| 工具调用同一步的 assistant 说明丢失 | 成立。说明文字和工具调用记录在关闭 ModelCall 的同一事务持久化；不会在终态再次重复写入。 |
| A2A 旧快照覆盖新状态 | 成立。UPDATE 比较原 sequence、时间、state、deliveryState；冲突拒绝而非覆盖，不盲目重试外部操作。无 schema 变更。 |
| 能力检测先收完整流再判断上限 | 成立。仅探测流 take(MAX_PROBE_EVENTS+1)，及时取消上游；普通对话流不套此上限。 |
| 全局 onStopTasks 被第二个容器覆盖 | 成立。移除构造期静态回调赋值；系统服务从所属 Application 的容器取得任务列表。未开展 DI 重构。 |
| PRoot 并发 open 覆盖连接槽 | 接口存在风险，不等于已在生产发生泄漏。连接槽准入/关闭同步，已有连接时拒绝重复 open；等待中断也解绑。 |
| PRoot 输出 pump 未结束即生成成功结果 | 存在。capture 发布后不可再变更，只有实际 EOF 才是完整输出；读取失败/未结束不能落 SUCCEEDED，仍存活的管道所属进程组终止。未证明所有 OEM 的 kill/pipe 时延已解决。 |
| 明文密码字段漏判 | 成立于 type=text 且 name/placeholder 明示 password/passwd/密码。主机与 live JS 同步分类，快照不读取这些字段值；普通 password 链接和邮编框不因此拒绝。启发式不是任意页面敏感性识别的完备证明。 |
| share 与正在物化的 draft 竞争 | share 等待当前 preparation 结束再切换，不丢弃 incoming share。 |
| GoalReducer 旧 verifier 注释 | 成立。按 ADR-GOAL-001 更正；不修改完成/预算/Continue 语义。 |

## Alternatives considered

| 报告意见 | 二次复核结论 |
| --- | --- |
| API<35 没有 onBindingDied | 错误。该回调从 API26 提供；PRoot 注释一并纠正。丢回调仍需独立连接等待边界。 |
| TurnReducer 零调用意味着删掉或生产跑错 | 两者不能由该事实推出。BatchTurnRuntime KDoc 明确说明串行 M1 reducer 与生产并发 batch 不同；本轮不删历史测试、不替换状态机。 |
| app 行数/导入 storage 就是 P1 | 属于耦合与维护成本，行数没有容量上限；应用组合层使用 repository/entity 不自动违反模块依赖。职责与存储抽象迁移交给重构分支。 |
| DAO 直接加 LIMIT | 不采用。会话和未决恢复记录不能静默截断；需按 checkpoint、稳定游标、工具配对设计分页并测大数据量。现有证据不足以声称发生 OOM。 |
| 每步图片 hash 重验可省略 | 当前附件授权绑定要求每次发送/恢复前复验（ADR-AGENT-003）；缓存优化须证明文件变化能使缓存失效，不能直接移除。 |
| SSE 没有边界测试 | 过度概括。ChatSseReaderTest、AnthropicSseReaderTest、ResponsesSseParserTest 与 Wire 测试存在。UTF-8 非法字节和 JSON 的 escaped surrogate 是不同问题；跨协议合并需先固定差异用例。 |
| Long→Int 现在会回绕 | 当前 ToolDescriptor 强制输出上限 8 MiB，未成立；未来改变 cap 时再同步类型。 |
| 孤儿审批卡 error 一定使 App 崩溃 | 未成立为直接崩溃结论。broker 在 cardSink 异常时清理 wait slot 并抛到执行边界；保持无法真实展示的卡不可批准。 |
| File/Path 混用就是外部存储故障 | 类型混用本身不是证据，需要具体 content URI 被当文件路径及实际调用链；不批量替换 SAF 访问。 |
| Provider 默认目录等于服务端能力写死 | 必须区分默认启动配置与已实现的运行时目录/推理/窗口解析，不能据默认值直接回退已有自动发现。 |

## Regression verification

脚本：`scripts/debug/2026-09-13/verify-main-review.py`（main 分支检查、指定路径格式、主机测试、双 flavor 编译、设备测试编译和 lint）、`summarize-main-review.py`（限定结果目录统计）、`check-a2a-cas.py`（实际 DAO SQL 的 SQLite 过期写入对照）。日志：忽略目录 `build/debug/2026-09-13/main-review/`。

最终主机测试 367 项，0 failure/error/skip：app 26、storage 89、provider API 95、browser tools 38、PRoot capture 2、browser feature 117。双 flavor 编译、app Developer 与 CLI instrumented 测试编译、app 双 flavor lint 和 CLI/PRoot client/app lint 通过；SQLite CAS、i18n 与 diff 检查通过。不是全仓测试或设备验收。

文档检查仍被两份已有根目录方案文档的分类阻塞：`docs/helix-agent-complete-research-and-product-plan.md`、`docs/helix-mermaid-architecture-diagrams.md`；本轮保留其路径，避免改变重构工作的引用。

## Residual risk

以下不在本轮做结构搬迁：app 编排/存储门面、PFD/SSE/JSON 去重、files.copy/move 共用逻辑、quota 扫描优化、根 Gradle convention 化、spike/testing 模块取舍和 lint variant 调度。原报告没有附完整逐项重现材料，不能把代码重复或模块数量当作确定故障。

PRoot watchdog 的 kill sweep 队头等待、线程池 close 后的完成时序、CLI progress preview 大小，以及超限响应的 retryable 分类，仍需按具体调用链与场景补证；本轮不增加模型输出限额或盲目重试。文件 orphan KDoc 与 section marker 属于文档卫生，应随责任模块整理，不声称 orphan 回收或全仓卫生已完成。

禁止把本次修复自动带入正在运行的冻结 EV 制品。重构分支后续按该表人工移植缺陷修复，不能在脏 main 上整体 cherry-pick 未经整理的历史修改。

设备回归未运行：新增 busy attachment、工具前说明持久化和 CLI 缺回调测试仅编译；真实跨会话审批、安装/绑定、PRoot 管道与分享竞争仍交给独占设备测试者。没有执行账号请求、长稳、提交或推送。

## Related records

参考：[Android ServiceConnection](https://developer.android.com/reference/android/content/ServiceConnection)、[ADR-RUNTIME-001](../adr/runtime/001-execution-domains.md)、[ADR-GOAL-001](../adr/goal/001-lifecycle-and-completion.md)。
