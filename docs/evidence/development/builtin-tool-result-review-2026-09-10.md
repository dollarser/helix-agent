> 证据快照：本页的状态、分工、命令结果和设备仅对应记录时点，不作为当前开发指令；当前入口为[实施状态](../../development/status.md)。

# 内置工具的模型结果梳理

日期：2026-09-10。范围：HXA-190 所有者授权的全部内置工具返回设计；基于当前生产注册入口，不包含动态 MCP/A2A 工具，不把手动文件管理或后台任务 UI 当作已注册模型工具。

## 参考与取舍

核对 [Pi 的 AgentToolResult](https://github.com/badlogic/pi-mono/blob/main/packages/agent/src/types.ts)：content 交给模型，details 用于日志/UI；[OpenCode 的工具定义](https://github.com/anomalyco/opencode/blob/dev/packages/core/src/tool/tool.ts)提供独立 toModelOutput；[DeepSeek Harness 压缩机制](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/compaction.md)保留原事件与替换结果关联，维护工具调用/结果配对。这些是机制参考，不复制其代码，也不把各项目策略称为通用标准。

Helix 已有 tool_results 全量持久结果和 messages 模型历史，复用这两个层次即可。成功结果在 ChatToolMessageEncoder 内经 ToolModelResult 投影后写入模型消息，原始结果仍由 ChatToolSettlement 保存。UI 预览不能替代模型结果，模型投影不能改变 verifier、审批、恢复或 Goal 结算依据。本轮不改变跨模块 schema、工具版本、数据库格式及授权边界，因此是既有契约内的 app 局部投影。

筛选原则：下一步决策需要的内容、状态、实际路径、定位/分页标识、版本绑定保留；内部配额、执行统计和重复常量不进入模型。按工具精确匹配顶层字段，未知格式原样保留，不递归删除用户数据，不按字符硬截 JSON。失败/取消不做成功投影。MCP/A2A 命名空间保持原样。

## 注册工具清单与逐项结论

下表列出 60 个内置工具（不同 flavor/能力可用性不等于同时全部曝光）。每一行列出的工具共用该行结论；“保留”表示复核后不需要为了减少字段而改动。

| 工具 | 模型应得到的内容和本轮结论 |
| --- | --- |
| `time.now` | 保留当前时间及时区等时间语义；不能用 Harness 日志时间代替工具结果 |
| `read` | 保留正文、实际路径、编码、范围/截断信息和 sha256；edit 仍要求 expectedSha256 |
| `write` | 保留 path、sizeBytes、sha256、overwritten；移除 usageBytesAfter、固定 encoding 和 mimeType |
| `edit` | 保留 path、replacements、sizeBytes、sha256；移除 usageBytesAfter |
| `files.list` | 保留 path、entries、truncated；当前是有界枚举，没有 offset 游标，不伪造可翻页承诺 |
| `files.stat` | 保留所请求文件的类型、大小和现有属性；这些就是查询目标 |
| `files.search` | 保留匹配项、定位及截断信息；不能只报命中数量 |
| `files.mkdir` | 保留 path、created，已足够短 |
| `files.copy`, `files.move` | 保留 source/destination、大小、hash、覆盖状态；移除 usageBytesAfter |
| `files.delete` | 保留 path、trashRef、sizeBytes；移除 usageBytesAfter；回收引用仍支持人工恢复定位 |
| `files.archive` | 保留目标、格式、文件统计和产物版本；不改 work/ 目标约束 |
| `files.extract` | 保留 destination、format、files、directories；移除 usageBytesAfter |
| `browser.open`, `browser.navigate` | 保留 tabId/导航结果等当前输出，后续操作需要真实标签页身份 |
| `browser.back`, `browser.forward`, `browser.reload` | 保留导航状态；页面变化后必须重新获取可操作快照 |
| `browser.snapshot`, `browser.find` | 保留 URL、节点、token、generation/fingerprint 和截断标志；这些关联陈旧节点检查，不能当普通 hash 删除 |
| `browser.click`, `browser.type`, `browser.scroll` | 保留动作结果与页面变化信息；派发动作不等于页面业务已经成功 |
| `browser.screenshot` | 保留图像/产物引用及尺寸等当前输出；只返回“截图成功”无法继续识别 |
| `browser.download` | 保留下载状态、实际 reference、版本 hash 和拒绝原因；不能把 queued/拒绝说成已保存 |
| `android.open_uri`, `android.share` | 保留实际派发状态与原因；打开系统 Intent 不是外部操作完成证明 |
| `clipboard.read` | 保留实际文本及拒绝原因；内容可能正是用户任务输入 |
| `clipboard.write` | 保留 status、length、reason；当前不回显整段输入，无需再裁减 |
| `notifications.query` | 保留请求范围内通知内容与权限状态；不把无权限当空结果 |
| `calendar.prepare_event` | 保留 draftId 与事件字段，commit 依赖 draft，用户还需确认具体内容 |
| `calendar.commit_event` | 保留写入状态、事件标识和失败原因；不得删除身份后诱发重复创建 |
| `http.fetch` | 保留状态码、finalUrl、内容类型、body、truncated 和原因；重定向及长度字段帮助判断内容完整性，本轮保留 |
| `ui.snapshot`, `ui.find`, `ui.wait` | 保留节点 token、树/匹配、可操作属性和状态；不可统一截断掉后续操作标识 |
| `ui.click`, `ui.long_click`, `ui.set_text`, `ui.scroll`, `ui.back`, `ui.home` | 保留动作状态、原因；工具派发与目标 App 完成是两回事 |
| `root.status` | 保留真实 Root 能力状态，不能根据安装渠道或 UI 开关猜测 |
| `root.file.read` | 保留有界文件内容、范围/完整性信息 |
| `root.package.info` | 保留查询的包属性 |
| `root.process.list` | 保留查询的进程信息 |
| `root.log.read` | 保留日志行与数量，日志本身是任务证据 |
| `code.javascript.run` | 成功结果已经只有 result/outputBytes；源码 hash、执行限制等已在审计详情中，不重复改造 |
| `bash` | 保留 state、exitCode、stdout、stderr、outputImported、outputSha256；退出码和产物导入成功必须分别表达 |
| `skills.list` | 保留 source/name/snapshotHash、简述、enabled、nextOffset/eof；后续读取和操作以完整版本键绑定 |
| `skills.read`, `skills.read_resource` | 保留所请求说明/资源内容、编码和信任/版本信息；按需读资源优于安装时全量注入 |
| `skills.enable`, `skills.disable`, `skills.remove` | 保留精确版本、作用域及操作结果/可恢复标志 |
| `skills.preview` | 保留待安装树、主 snapshot hash 和诊断；每文件 hash 暂保留以支持审查，不与安装期验证脱钩 |
| `skills.install` | 保留 source/name/snapshotHash、installed、enabled；安装不等于已启用 |
| `connectors.preview`, `connectors.install` | 保留包 hash、端点/凭据需求、Skill 清单、诊断和结果；预览不能省略将启用的范围 |
| `goal.report` | 保留模型报告状态和摘要；当前是有效报告回执，不是所有副作用都已完成的替代证明 |

注册入口：AppWorkspaceTools、AppAndroidTools、DefaultAppContainer、developer ProotToolModule/RootModule/AutomationModule，以及 extensions/skills/SkillTools。没有额外的“CLI 任意执行”或可写子 Agent 工具。

## 输入与输出的约束

文件相对路径继续遵循 ADR-WORKSPACE-001。不要要求模型重新猜 scope ID；读取后的 sha256 可用于 edit/write 的乐观并发检查。工具成功后无需为“证明写入”一律再读整文件，但内容正确性、编译或用户明确要求的验证仍应按任务执行。

大内容优先在工具输入选择小范围；已有截断/分页标记必须保留。此次不新增全局正文截断或自动删除历史结果，也不扩展公共工具 schema。目录真正分页、任意工具结果回查属于后续接口增强，本次没有假装实现。

## 验证与限制

新增主机回归覆盖顶层投影、hash/覆盖/回收引用保留、正文同名字段不受影响、MCP/未知结果不变；原始 tool_results 保存路径不改。设备上的真实 Agent 任务按所有者安排人工验收。审核清单是源码设计审查，不代表 60 个工具全部重新执行过设备验收。
