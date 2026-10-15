# ADR-MCP-001: MCP Client、传输与工具接入

Status: accepted
Date: 2026-09-16; revised 2026-10-15
HXA: HXA-070, HXA-286, HXA-291
Deciders: Project owner（2026-10-15 要求按评审更新目标设计和任务；接受范围见 Decision，实施另计）

## Context

MCP 需要 Android 可运行的 Client 底座，同时保持 core 与外部 SDK 解耦。

## Decision

采用 io.modelcontextprotocol:kotlin-sdk-client，HTTP 使用 Ktor OkHttp engine；版本以依赖清单和锁文件为准，不依赖 umbrella/server artifact。extensions:mcp 对外只暴露 McpClientFacade、McpClientSession 与 Helix 值类型，SDK/Ktor/OkHttp/JSON-RPC DTO 保持内部。

Client 不声明未实现的 sampling、elicitation 或 roots。initialize result 是协商版本事实源，按 request ID 提取并同步会话快照和传输 header；缺失版本时失败并关闭资源，不猜服务端版本。

工具发现、搜索与会话曝光复用统一 Registry；启用/禁用在 schema、搜索、缓存和实际调用中一致，外部 hints 不能授予权限。配置与安装流程看 Skill 决策，操作授权看权限决策。

取消 Helix 自设的全局64工具数量上限及请求/绑定/manifest联动计数校验，不以另一固定全局数量替代，不静默截断最终集合。工具数量不等于上下文成本；已有上下文预检、Provider已知协议限制和manifest字节容量仍有效。实际工具绑定必须完整记录，无法容纳则明确失败，不删绑定掩盖超限。默认工具复用共享常用契约及已选原生插件preferred组，避免动作可见而观察不可见；模式、功能开关与用户禁用先于曝光选择，优先级不注册工具或扩大能力。具体集合由当前源码与授权任务定义，不设随实现变化的固定计数门禁。

`tools.search` 复用同一注册表，覆盖已启用的内置、MCP、A2A 工具；完整名称优先，再按名称/描述/来源/参数字段及宿主维护的中英文别名相关性排序，部分匹配可回退；可按source/category过滤，搜索自身不进入结果。每次请求提供准入快照的简短来源/类别目录，元数据不是权限或instructions。一次搜索最多返回16个匹配；本次发现及可用、同来源的宿主已知操作前置契约完整装载，近期有效成员按估计schema字节预算保留，优先新发现，不设整个请求的工具数量上限。零命中保留有效旧成员，失效/撤销/模式变化立即过滤。其他会话不继承；下一次模型请求重新校验版本、当前模式和可用性。

近期成员采用64KiB估计schema预算，本次匹配和前置契约不因该软预算丢弃，超出实际上下文或manifest字节容量仍明确失败。MCP按来源组在16KiB估计schema预算内提前提供，超过成本的来源继续可搜索；不再有合计16/17个工具的数量突变。以上是当前有界启发式，不是模型精确token容量、权限规则或普遍最优值；基于真实任务对照和当前上下文约束继续评估。Byte估计与最终Provider/token口径区别保留，动态缓存不是Room恢复事实。进程重开重新发现；搜索和加载从不授予执行许可。

依赖排除 ktor-server-websockets 必须由 Client fixture 和 Android/R8 检查证明可行；升级后若依赖链变化重新评估。仅对明确的可选 JVM TLS provider/日志 adapter 做定点 missing-class 处理，其他错误保持门禁。

### 同源依赖与 Resources 消费（HXA-286/291）

工具前置组从可信、经校验的贡献元数据取得，默认曝光与按需发现共用同一规则，取代所涉来源名/工具名分支。按当前会话资格过滤后才能加载；缺失、循环或撤销不能被补依赖绕过。保持下一请求 Schema 生效和原绑定校验，元数据不产生执行许可。

[HXA-291](../../development/tasks/HXA-291.md) 补资源列举、模板发现和读取的模型工具入口，优先采用 list_mcp_resources/list_mcp_resource_templates/read_mcp_resource。复用现有 SDK facade、连接/认证和会话生命周期，处理分页、URI/模板参数、大小、类型与来源引用；不是服务端 tools/call 的伪装。

资源正文与元数据区分，文本和二进制分别进入有界内容/产物路径，保持不可信来源。没有 Resources 能力的服务明确不支持；本增量不启用 Prompts 执行、sampling、elicitation、stdio 或 MCP Apps。Notion/Linear 已结案账号流程不作为新增资源验收前置。

## Alternatives considered

直接实现最小 HTTP/JSON-RPC 可减少 SDK 依赖，但需要自行维护 session、SSE、取消和恢复，仅在 SDK 无法满足 Android 条件时评估。采用 Server/umbrella 依赖扩大无关能力，不采用。固定失配的旧依赖不是兼容方案；需要升级时同步整个实际依赖链并验证。

## Consequences

同一主题使用一份有效契约，避免并行实现各自解释权限和生命周期。代价是实现、UI、数据与恢复需要一起验证；accepted 表示决定，不代表相关任务全部完成。

## Verification

本次为现行决策整理，不新增功能通过结论。实现范围与实际命令结果以[实施状态](../../development/status.md)、对应 HXA 及完成记录为准；修改本契约后须覆盖成功、失败、取消、边界和恢复，不能用文档门禁代替设备/功能验收。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## Decision history — 2026-10-15

接受 HXA-286 的依赖元数据接线及 HXA-291 Resources 消费闭环；工具总量仍无 Helix 固定全局上限，真实搜索/资源能力与已交付认证分开验收。 本轮只更新设计与任务，代码和验收由对应 HXA 记录。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)

## Decision history

- 2026-10-13：Owner要求优化工具发现误调用并直接复用外部插件。工具描述/Skill/历史中的名称仅是发现线索，调用以本次实际schema为准；tools.search版本4明确schemaAvailability=next_request，仍重验撤销/版本，不在同批或旧请求开放。其他宿主的mcp__限定名可按远端动作部分检索，返回Helix当前名称；不注册执行别名、不自动调度或重放。Browser观察描述就近提醒动作需先发现。实际模型收益与设备验证另计，主机契约不冒充遵循率。

- 2026-10-12：Owner授权实施完整工具优化，HXA-280切片3将替换窗口改为新发现优先、可用前置契约及有界近期保留；MCP提前加载按来源组成本，取消16/17数量阈值。Mobile Use只将ui.device改为发现，坐标动作发现同时加载可用截图/设备契约；其他默认组保持。保留撤销、原身份、取消和UNKNOWN不重放；有界真实对照及成本取舍记录在任务证据，不预报收益。

- 2026-10-12：HXA-280切片2采用注册事实的只读来源/类别/字段投影与宿主中文别名，tools.search新增可选过滤及来源输出，版本随契约更新；目录仅来自当前会话准入快照，撤销即时过滤。没有复制竞品代码或将远端hints用于权限/常驻优先级。

- 2026-10-12：Owner要求结合Codex、Claude Code和DSH重新设计并实施工具优化，明确取消没有充分依据的全局64上限。采用原子注册/模型投影分离、实际上下文与Provider约束、完整绑定和字节存储边界；不全量常驻或引入新的脚本执行域。研究与候选切片见[工具专题§12](../../research/topics/tool-exposure-and-discovery-2026-09-29.md#12-2026-10-12-工具架构重新评估与优化设计)，实施与实际验证由HXA-280承接；后续窗口/目录策略在同一ADR更新，不由历史计数倒推当前默认集合。

- 2026-09-28：所有者明确授权优化工具数量与曝光。AndroidWorld 小样本发现64截断留下 `ui.click` 却丢掉 `ui.snapshot`，且旧搜索仅支持MCP。收敛为基础集合、已开启自动化组和统一按需发现；不增加上限，不改变 Dispatcher/approval/effect owner。证据见[公开评测试跑](../../evidence/development/public-agent-pilot-2026-09-28.md)。

- 2026-09-28：19项候选的skill-004轨迹显示基础启停需要多次发现，耗尽固定模型调用预算。将Skill读取/启停作为同一默认基础组，收敛到21项（有自动化会话30项）；安装、预览、移除仍按需发现，保持64上限和独立权限检查。

- 2026-09-30：所有者要求继续优化历史审查遗留项。搜索优先完整名称及名称关键词，零命中不再清空仍有效的加载窗口；仍重新过滤禁用、撤销和替换，不累加窗口、不放宽权限。MCP 16/17 曝光策略和 schema 成本预算留待后续。

- 2026-10-10：所有者要求按 HXA-251 对照建议优化，明确授权 API36 与现有 Qwen 有界多任务对照。采用通用 base/工具描述提示：相关能力组合检索、精确名称单工具、命中替换窗口、同会话不在一批并行多次搜索、仅使用下一请求实际 schema；执行前未曝光/绑定失效拒绝先重新发现，UNKNOWN 副作用仍先核查。窗口、排名、禁用/模式、授权、取消及重放不变；描述变化仍受契约哈希约束，不继承失效批准。主机门禁与三类各两轮有效严格对照候选 6/6、基线 5/6，支持本次提示选择，不能继承旧 3/3 或推断普遍收益；[HXA-252](../../completion-records/HXA-252.md)与[完整证据](../../evidence/development/discovery-guidance-2026-10-10.md)保留环境/夹具失败及边界。
