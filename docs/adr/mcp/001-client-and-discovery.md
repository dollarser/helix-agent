# ADR-MCP-001: MCP Client、传输与工具接入

Status: accepted
Date: 2026-09-16; revised 2026-10-16
HXA: HXA-070, HXA-286, HXA-291
Deciders: Project owner（2026-10-15 要求按评审更新目标设计和任务；接受范围见 Decision，实施另计）

## Context

MCP 需要 Android 可运行的 Client 底座，同时保持 core 与外部 SDK 解耦。

## Decision

采用 io.modelcontextprotocol:kotlin-sdk-client，HTTP 使用 Ktor OkHttp engine；版本以依赖清单和锁文件为准，不依赖 umbrella/server artifact。extensions:mcp 对外只暴露 McpClientFacade、McpClientSession 与 Helix 值类型，SDK/Ktor/OkHttp/JSON-RPC DTO 保持内部。

Client 不声明未实现的 sampling、elicitation 或 roots。initialize result 是协商版本事实源，按 request ID 提取并同步会话快照和传输 header；缺失版本时失败并关闭资源，不猜服务端版本。

工具发现、搜索与会话曝光复用统一 Registry；启用/禁用在 schema、搜索、缓存和实际调用中一致，外部 hints 不能授予权限。配置与安装流程看 Skill 决策，操作授权看权限决策。

取消 Helix 自设的全局64工具数量上限及请求/绑定/manifest联动计数校验，不以另一固定全局数量替代，不静默截断最终集合。工具数量不等于上下文成本；已有上下文预检、Provider已知协议限制和manifest字节容量仍有效。实际工具绑定必须完整记录，无法容纳则明确失败，不删绑定掩盖超限。默认工具复用共享常用契约及已选原生插件preferred组，避免动作可见而观察不可见；模式、功能开关与用户禁用先于曝光选择，优先级不注册工具或扩大能力。具体集合由当前源码与授权任务定义，不设随实现变化的固定计数门禁。

`tools.search` 复用同一注册表，覆盖已启用的内置、MCP、A2A 工具；完整名称优先，再按名称/描述/来源/参数字段及宿主维护的中英文别名相关性排序。全关键词和完整词命中用于排序，不排除互补的部分匹配或词形片段。名称、描述、参数字段及元数据保留子串召回；词边界提升排序质量而非缩减可用能力，camelCase字段、中文和限定名称仍可检索。精确名称被禁用时不自动替换成另一能力。可按source/category过滤，搜索自身不进入结果。每次请求提供准入快照的简短来源/类别目录，元数据不是权限或instructions。一次搜索最多返回16个匹配；本次发现及可用、同来源的宿主已知操作前置契约完整装载，近期有效成员按估计schema字节预算保留，优先新发现，不设整个请求的工具数量上限。零命中保留有效旧成员，失效/撤销/模式变化立即过滤。其他会话不继承；下一次模型请求重新校验版本、当前模式和可用性。

近期成员采用64KiB估计schema预算，本次匹配和前置契约不因该软预算丢弃，超出实际上下文或manifest字节容量仍明确失败。MCP按来源组在16KiB估计schema预算内提前提供，超过成本的来源继续可搜索；不再有合计16/17个工具的数量突变。成本使用与请求相同的最终路径说明、展示元数据及规范化schema投影，加现有每工具32-token包络估算，不再按未增强的descriptor计费；MCP组先以含名称的原始字节下界跳过已超预算目录，避免为未选中契约提前触发模型投影验证。不改变模型输入估算总口径。近期预算包含本次匹配成本，超过64KiB的单工具或能力组可能在下一次搜索后需要重新发现；该静态缓存不随256K上下文扩大，不应称作大窗口自适应优化。以上是当前有界启发式，不是模型精确token容量、权限规则或普遍最优值；基于真实任务对照和当前上下文约束继续评估。Byte估计与最终Provider/token口径区别保留，动态缓存不是Room恢复事实。进程重开重新发现；搜索和加载从不授予执行许可。

依赖排除 ktor-server-websockets 必须由 Client fixture 和 Android/R8 检查证明可行；升级后若依赖链变化重新评估。仅对明确的可选 JVM TLS provider/日志 adapter 做定点 missing-class 处理，其他错误保持门禁。

### 同源依赖与 Resources 消费（HXA-286/291）

工具前置组从可信、经校验的贡献元数据取得，默认曝光与按需发现共用同一规则，取代所涉来源名/工具名分支。按当前会话资格过滤后才能加载；缺失、循环或撤销不能被补依赖绕过。保持下一请求 Schema 生效和原绑定校验，元数据不产生执行许可。

[HXA-291](../../development/tasks/HXA-291.md) 补资源列举、模板发现和读取的模型工具入口，优先采用 list_mcp_resources/list_mcp_resource_templates/read_mcp_resource。复用现有 SDK facade、连接/认证和会话生命周期，处理分页、URI/模板参数、大小、类型与来源引用；不是服务端 tools/call 的伪装。

资源正文与元数据区分，文本和二进制分别进入有界内容/产物路径，保持不可信来源。没有 Resources 能力的服务明确不支持；本增量不启用 Prompts 执行、sampling、elicitation、stdio 或 MCP Apps。Notion/Linear 已结案账号流程不作为新增资源验收前置。

### 本地 stdio 的现状与候选边界

当前存在有界 JSONL 帧和 Developer `McpStdioJobBridge` 基础，不是导入插件可用的常驻 MCP 会话；包内 command/stdio 仍明确诊断为需要 Runtime。HXA-291 只补 Resources，不由此启用本地进程。

未来常驻接入需先确定真实目标 server、安装依赖、隔离/凭据 owner 和资源期限；复用原 Registry/Dispatcher 与 Runtime，不按每请求重启 Job 代替双向流。原安装 revision/command identity、实例 generation、连接/requestId 及取消/超时/真实退出分别绑定；stdout 协议、stderr 诊断、背压和有界关闭须按实际协议验证。只协商已实现能力；HTTP OAuth 凭据不会自动导入进程 env，外部服务请求和消息不能获得本地权限。具体进入条件归 [Harness 第 23.12 节](../../architecture/harness-refactor-plan.md#2312-插件管理与提示审查接续)，本节不授权新增 Runtime 产品。

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

- 2026-11-13（大窗口召回修正）：Owner明确未来以≥256K模型为前提、任务成功率和工具可用性优先。撤回上一轮全关键词硬过滤及英文词边界准入，保留它们的排序优势和原子能力组，部分匹配、词干和字段片段仍可达；精确名称与授权边界不变。不为小窗口另加策略，不凭空扩大默认常驻或引入固定全局工具数。最终投影成本与诊断继续保留，16KiB eager/64KiB recent仍是已知静态启发式，不宣称解决大型schema往返淘汰或真实模型成功率；以256K任务组合、160项默认能力、80KiB schema重新发现的JVM回归明确当前边界。

- 2026-11-13：Owner要求参考主流Agent实际改进工具加载。采用最终modelSchema成本统一计费、可用全关键词匹配优先和词边界匹配，保留中文/限定名称、部分匹配回退、完整能力组及全部原授权边界。对照[OpenAI工具发现](https://developers.openai.com/api/docs/guides/tools-tool-search)与[Claude工具搜索](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool)的按需活跃集合原则，不复制原生deferred协议、不套固定工具数或常驻数量。默认工具组暂不整体隐藏；改变近期保留成本不等于扩大执行许可，也不声称已完成整上下文自适应工作集或真实模型成功率验收。

- 2026-10-16（现状与运行资格）：Owner 要求收敛后续文档。区分 Resources 元数据与正文消费、有限 stdio Job 基础与常驻协议服务；保持 291 独立范围。依据 [MCP transport](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports) 和 [lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle) 明确未来双向流/协商/关闭及凭据前置，不宣称本次已经实现常驻 stdio。

- 2026-10-13：Owner要求优化工具发现误调用并直接复用外部插件。工具描述/Skill/历史中的名称仅是发现线索，调用以本次实际schema为准；tools.search版本4明确schemaAvailability=next_request，仍重验撤销/版本，不在同批或旧请求开放。其他宿主的mcp__限定名可按远端动作部分检索，返回Helix当前名称；不注册执行别名、不自动调度或重放。Browser观察描述就近提醒动作需先发现。实际模型收益与设备验证另计，主机契约不冒充遵循率。

- 2026-10-12：Owner授权实施完整工具优化，HXA-280切片3将替换窗口改为新发现优先、可用前置契约及有界近期保留；MCP提前加载按来源组成本，取消16/17数量阈值。Mobile Use只将ui.device改为发现，坐标动作发现同时加载可用截图/设备契约；其他默认组保持。保留撤销、原身份、取消和UNKNOWN不重放；有界真实对照及成本取舍记录在任务证据，不预报收益。

- 2026-10-12：HXA-280切片2采用注册事实的只读来源/类别/字段投影与宿主中文别名，tools.search新增可选过滤及来源输出，版本随契约更新；目录仅来自当前会话准入快照，撤销即时过滤。没有复制竞品代码或将远端hints用于权限/常驻优先级。

- 2026-10-12：Owner要求结合Codex、Claude Code和DSH重新设计并实施工具优化，明确取消没有充分依据的全局64上限。采用原子注册/模型投影分离、实际上下文与Provider约束、完整绑定和字节存储边界；不全量常驻或引入新的脚本执行域。研究与候选切片见[工具专题第 12 节](../../research/topics/tool-exposure-and-discovery-2026-09-29.md#12-2026-10-12-工具架构重新评估与优化设计)，实施与实际验证由HXA-280承接；后续窗口/目录策略在同一ADR更新，不由历史计数倒推当前默认集合。

- 2026-09-28：所有者明确授权优化工具数量与曝光。AndroidWorld 小样本发现64截断留下 `ui.click` 却丢掉 `ui.snapshot`，且旧搜索仅支持MCP。收敛为基础集合、已开启自动化组和统一按需发现；不增加上限，不改变 Dispatcher/approval/effect owner。证据见[公开评测试跑](../../evidence/development/public-agent-pilot-2026-09-28.md)。

- 2026-09-28：19项候选的skill-004轨迹显示基础启停需要多次发现，耗尽固定模型调用预算。将Skill读取/启停作为同一默认基础组，收敛到21项（有自动化会话30项）；安装、预览、移除仍按需发现，保持64上限和独立权限检查。

- 2026-09-30：所有者要求继续优化历史审查遗留项。搜索优先完整名称及名称关键词，零命中不再清空仍有效的加载窗口；仍重新过滤禁用、撤销和替换，不累加窗口、不放宽权限。MCP 16/17 曝光策略和 schema 成本预算留待后续。

- 2026-10-10：所有者要求按 HXA-251 对照建议优化，明确授权 API36 与现有 Qwen 有界多任务对照。采用通用 base/工具描述提示：相关能力组合检索、精确名称单工具、命中替换窗口、同会话不在一批并行多次搜索、仅使用下一请求实际 schema；执行前未曝光/绑定失效拒绝先重新发现，UNKNOWN 副作用仍先核查。窗口、排名、禁用/模式、授权、取消及重放不变；描述变化仍受契约哈希约束，不继承失效批准。主机门禁与三类各两轮有效严格对照候选 6/6、基线 5/6，支持本次提示选择，不能继承旧 3/3 或推断普遍收益；[HXA-252](../../completion-records/HXA-252.md)与[完整证据](../../evidence/development/discovery-guidance-2026-10-10.md)保留环境/夹具失败及边界。

### 2026-11-08 文本 Resources 实施边界

本轮加入每来源命名空间内的 `list_mcp_resources`、`list_mcp_resource_templates`、`read_mcp_resource` 宿主 descriptor，直接调用 Resources 协议，不调用远端同名 tools/call。复用 MCP origin、Registry、Dispatcher/egress、原调用身份、deadline、凭据和真实连接关闭；来源撤销在每次协议请求与结果返回前复核。Resources-only 服务允许空工具选择启用；不支持 Resources 的来源不注册入口。

每页最多 128 项/256 KiB，读取正文最多 64 块/256 KiB UTF-8，发现扫描累计最多 1 MiB/每类 16 页，超限明确失败。远端 cursor 不跨连接：宿主 continuation 绑定来源和类别，重连后有界重读前序页，校验元数据链 hash，再使用当前连接的新 cursor；前序内容变化要求重新发现，不静默继续。此方案无持久缓存，不承诺可变远端列表的原子快照。

读取在同一连接重验列举 URI 或简单标量 `{variable}` 模板，先使用有界动态规划检查形状，重复变量再用共享工作预算内的显式栈搜索校验绑定一致性，不使用回溯正则或声称任意重复变量匹配为多项式算法。其余 RFC6570 运算符尚不支持；URI 始终是该 MCP server 的身份，不打开本地 `file://`。文本返回包含来源、URI、内容 hash 和不可信标识；服务返回不同 URI、二进制、未发现资源明确失败。二进制产物、订阅/通知、Prompts、stdio、MCP Apps 不在此切片。

主机协议夹具覆盖分页重连/错来源/变化/边界、正文、模板、恶意模板、撤销、错误 URI、无能力、二进制拒绝、超限和取消。最终统一主机门禁由本轮收口补录；未运行真实 Resources 服务、设备 Resources 验证或实际模型下一请求消费，不将 SDK 调用成功视为这些验收通过。

模板匹配共享每次发现 16,777,216 个工作单元的计算预算，DP cell、绑定搜索分支与逐字符比较共同消耗，跨模板/页/阶段不重置；超额返回 `MATCH_BUDGET_EXCEEDED`。DP 每8行、绑定搜索每1024个工作单元重新检查来源/取消并 `yield`，让同事件循环的 deadline 生效，避免累积 CPU 阻塞。搜索只保留模板深度的显式栈与 URI 区间，不递归、不复制候选值、不建立无界缓存。

2026-11-08 契约补正：依据 [RFC6570 §3.2.2](https://www.rfc-editor.org/rfc/rfc6570.html#section-3.2.2)，简单标量允许空展开；非空展开只消耗 ASCII unreserved 字符或完整 `%HH` 单元，不允许原始 reserved/非 ASCII 字符，不能拆开百分号编码。变量名称继续仅支持 `[A-Za-z0-9_]+`，不声称覆盖 RFC6570 的点号/百分号编码名称或全部标量语法；文字中的百分号也必须属于完整 `%HH`，不得跨文字/变量边界拼接。相同变量名必须在各处匹配完全相同的编码片段（不做 URI 解码、大小写或路径归一化）；相邻不同变量及相邻重复变量仍支持。新增空值、编码边界、重复绑定、歧义预算与搜索阶段取消/撤销/deadline 回归；最终通过结果由统一门禁补录。

### 2026-11-12 二进制 Resources 产物路径

`resources/read` 使用类型化 Text/Blob 内容边界。Blob 在 SDK 接缝校验严格 base64、URI 与已发现请求一致，正文全批校验后才允许宿主发布；每次读取累计二进制最多 1 MiB，文本仍累计 256 KiB、最多64块。二进制内容版本为解码字节 SHA-256，文本沿用原 hashFields 契约，不混淆二者。

发布只接受宿主原始 session/turn，写入 App 私有工作区 output 的随机名称，复用原子写入、quota、内容探测和 Room artifact 注册。远端 URI、MIME 不指定路径、不成为执行资源或授权，不自动上传图像给模型。结果保留来源 URI、声明 MIME、不可信标识及内容 hash；二进制仅返回本机产物/路径引用，不将 base64 放入模型上下文。后续查看、导出、执行等仍走已有工具权限路径。

文件已保存但注册失败明确返回 `SAVED_NOT_REGISTERED`、已验证路径和 hash，不捏造 artifactRef、不回滚文件；取消或撤销仍不得伪称成功，已发生的文件效果保留并遵循现行 UNKNOWN/review。发布前和返回前复查来源，发布过程中复核取消/deadline；缺宿主发布接缝明确失败。

决策依据：[MCP 2025-11-25 Resources](https://modelcontextprotocol.io/specification/2025-11-25/server/resources) 定义 Text/Blob 及 application-controlled 消费；[Codex Resources handler](https://github.com/openai/codex/blob/main/codex-rs/core/src/tools/handlers/mcp_resource/read_mcp_resource.rs) 使用独立 resources/read、解析实际服务器/环境并保留来源。采纳来源绑定和独立协议操作，Helix 按移动端现有工作区/产物路径做有界物化，不照搬其他宿主协议 JSON 回填，也不复制参考代码。
