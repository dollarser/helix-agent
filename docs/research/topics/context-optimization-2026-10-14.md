# 上下文优化建议复核与设计补充

后续实施：Owner追加“先做收尾”，[HXA-285](../../development/tasks/HXA-285.md)已完成下述引用/压缩差异的生产修复及主机切片，完整全仓与双渠道测试APK编译通过；10月15日后续API36 Room6项及保存引用的Qwen真实压缩/跟进也已通过，见[后续验收](../../evidence/development/pre-refactor-closeout-2026-10-15.md)。下文“未修改生产代码/尚未修复”描述本次研究的原基线，不覆盖后续交付；其余策略仍是候选，结果与边界见[收尾证据](../../evidence/development/context-reference-closeout-2026-10-14.md)。

> 核验日期：2026-10-14；源码基线：`main` / `14824251bc8578162ff4c7ed5ba61bb66c9014c3`。工作区存在并行文档改动；本轮只新增研究、导航和本地诊断探针，未修改生产代码。本页是设计输入，不是 accepted ADR、实施完成记录或下一任务队列；当前范围仍由 [status](../../development/status.md)、owner 请求和对应 ADR 决定。

## 1. 判断与修正

原建议总体合理。应先修上下文事实的一致性，再增加可解释诊断，最后比较选择策略。不能用 token 下降推导任务完成率提高，也不能把所有建议都视为已发生的产品缺陷。

| 原建议 | 当前核验 | 补充后的判断 |
| --- | --- | --- |
| 引用会话与压缩源不一致 | 源码确认两条物化路径不同；本轮纯 JVM 探针进一步复现编译器拒绝计划，详见 §2 | 最高优先级正确性修复。必须同步规范消息、摘要源正文及资源计量，不能仅修消息比较 |
| 可选上下文缺少统一预算 | 工具近期工作集为固定字节策略，Skill 目录与激活正文另行装配；已有统一总输入估算和准入 | 缺口是**预算分配协调**，不是没有总预算。必要指导与当前协议组不能作为普通可选内容静默删减 |
| Memory 选择较粗糙 | 自动注入摘要取前 1,000 个 Kotlin 字符；已有 Markdown 索引、`memory.list/search/read` 和两级 scope | 不重建索引／读取机制。先优化摘要截取、入口信息和按需检索，再用任务证据决定是否自动选择条目 |
| 压缩连续性依赖摘要 | 一般对话的语义连续性仍依赖摘要；已有 Goal、恢复事实和 Job 观察投影 | 扩展现有持久事实投影及来源锚点；不另建模型维护的“事实真相库”，不重复实现 Job 状态注入 |
| 工具结果／图片选择偏通用 | 大成功结果有分页，最新两张工具图像有当前 Turn 限制与全请求字节预算；已有少量工具专用投影 | 方向合理，但“全部工具结果只留前 2,048 字符”不准确。优先补关键字段和可重新读取性，视觉按领域比较 |
| 上下文成本诊断不足 | 已有分类估算、实际输入校准、预算事件和提示指纹；逐提示段记录来源、信任级别、hash 等 | 建议前移到正确性修复之后、策略实验之前；增加选择／省略原因和估算成本，复用既有审计链 |

与原分析相比，第一项从“静态路径差异”增加了**编译器层运行证据**；尚未运行 App／Room 的完整引用发送→自动压缩集成链路。其余仍是经源码支持的优化候选，没有本轮真实模型完成率证据。

## 2. 引用压缩缺口：触发条件、复现与正确修复边界

### 2.1 当前路径

1. [ConversationReferenceResolver.prepare](../../../app/src/main/kotlin/com/helix/app/chat/ConversationReferenceResolver.kt) 在输入接受时生成有界引用快照。后续引用的是已保存内容，不是源会话的实时链接。
2. [ChatRequestAssembler.persistedRow / referenceContext](../../../app/src/main/kotlin/com/helix/app/chat/ChatRequestAssembler.kt) 将消息正文与保存的引用合并，并附上 `UNTRUSTED_CONVERSATION_REFERENCE` 标记，随后构建模型消息。
3. [ContextCompaction.plan](../../../app/src/main/kotlin/com/helix/app/agent/ContextCompaction.kt) 另读历史；[ContextHistoryMapping.rows](../../../app/src/main/kotlin/com/helix/app/agent/ContextHistoryMapping.kt) 只读 `ContextHistory.read` 的消息正文，未追加引用。
4. [ContextSegments.remainingRequest](../../../core/agent/src/main/kotlin/com/helix/core/agent/ContextSegments.kt) 验证实际历史与物化历史的数量、顺序及规范消息一致性；这里只排除类型化视觉投影的差异。正文不同会返回 `null`，上层因此得不到压缩计划。

触发需要压缩被请求／达到条件、存在可压缩组，并且参与一致性检查的历史仍包含引用。**引用位于保留尾部也会阻断旧前缀的压缩**，不要求引用本身正在被压缩。这不是“所有带引用的发送都会失败”，也没有证据表明它必然导致提前结束。

### 2.2 本轮最小运行证据

使用真实 `ContextCompiler`／`ContextSegments`，构造三个 Turn、一个可压缩旧前缀及相同消息 ID／顺序；以下测试强制进入规划，以排除窗口阈值干扰：

| 探针 | 实际结果 | 证明范围 |
| --- | --- | --- |
| 请求与源完全相同，不含引用 | 生成计划 | 控制组，排除“本来没有候选” |
| 仅请求追加引用，分别放在旧前缀和当前受保护尾部 | 两种位置均返回 `null` | 当前两条物化路径的差异足以触发拒绝 |
| 将源 `messages` 对齐请求，但源 `content` 仍是原正文 | 生成计划，但旧引用既不在摘要输入也不在保留请求中 | 只补消息映射会造成第二处信息丢失 |
| 源 `content` 与 `messages` 均包含同一引用 | 生成计划；旧引用进入摘要输入，尾部引用保留在请求中 | 证明必要的输入契约；不证明摘要模型一定忠实保留 |

新增四项探针与既有十三项 `ContextCompilerTest` 共 **17 项实际运行通过，0 失败、0 跳过**。探针是在纯 JVM 上重建两条路径的输入差异，没有调用 Android 存储／真实 Provider，也没有把这个缺陷修入生产。命令及证据见 §8。

### 2.3 修复目标

建立一个有界、不可变的**规范历史物化结果**，供请求投影和压缩源共同使用。至少携带原消息 ID、session／Turn／sequence、角色／调用身份、正文与引用快照内容、引用来源 ID／hash、附件关系以及 checkpoint 边界。名称和类拆分由实施决定，不新增一份持久历史。

- 同一个编译批次共享已读取的数据。只提取公共拼接函数、随后两次独立读取数据库，不能完全解决不同版本混用；明确读取一致性边界，文件内容以冻结引用和 hash 验证。
- 同一规范内容同时产生 `ContextSourceRow.messages` 和 `content`。[ContextCompiler.serializeRow](../../../core/agent/src/main/kotlin/com/helix/core/agent/ContextCompiler.kt) 实际把 `content` 送给摘要模型；二者不能各自拼接。
- 展开的引用及边界标记也要计入物化字节限制、输入估算和摘要预算。[ContextHistory](../../../app/src/main/kotlin/com/helix/app/agent/ContextHistory.kt) 目前在读取正文前按 contentRef 计量；不能在该上限之外无限追加引用。
- 保留严格相等检查及原身份核验。不要删除引用来“让两边相等”，不要按文本相似度、位置猜测或忽略任意正文差异来放行。
- send、backfill、retry、fork、recovery 复用物化规则，但不要求所有未来请求永久复用同一快照。输入接受快照保持原内容；新请求可依据当前合法状态重建；每次请求内部版本一致。重试／恢复仍重验权限、绑定、附件字节与目标配置。
- 视觉像素是经披露和能力检查后的请求投影，不应复制进压缩正文；保留现有 `withoutVisualProjection` 的窄边界。
- 现有 [TurnCoordinator.commitCompaction](../../../app/src/main/kotlin/com/helix/app/agent/TurnCoordinator.kt) 已在事务内检查取消并提交摘要及调用结算；新增一致性身份应接入这一 owner。测试摘要生成期间历史／checkpoint 变化的提交与拒绝条件，不能另造一个 checkpoint 提交者。这里是需补验证的契约，尚未证明存在并发漏洞。

## 3. 统一预算：共享容量计算，分开硬限制与选择策略

[ModelInputEstimate](../../../core/agent/src/main/kotlin/com/helix/core/agent/ModelInputEstimate.kt) 已把 instructions、history／工具参数、schemas、images 汇总；单张图片的估算仍是通用余量，不是 Provider 实际精确计费。[ToolDiscoveryWorkset](../../../app/src/main/kotlin/com/helix/app/mcp/ToolDiscoveryWorkset.kt) 的近期 schema 预算为 64 KiB、小 MCP 提前曝光预算为 16 KiB；新发现组及其前置工具优先完整保留，64 KiB 不是全部模型工具的统一硬上限。[PluginRegistry.instructions](../../../extensions/plugin/src/main/kotlin/com/helix/extensions/plugin/PluginExecutionContext.kt) 分别装入 required instructions、目录和本 Turn 激活内容。

建议在现有编译器上增加一次全请求分配，不再在每个装配器内独立推测“还有多少空间”：

| 层次 | 内容与处理 |
| --- | --- |
| 硬边界 | 模型窗口、单次输出预留、协议数量／字节上限、图片总量与内存限制。沿用实际输入校准；token、UTF-8 字节、字符、图像像素分别计量 |
| 必须保留 | 系统规则、当前用户意图、当前必须指导、未闭合调用组及关键恢复／未知效果事实。超过容量时明确报原因，不静默截断 |
| 可重建上下文 | 当前 checkpoint、已接受的 Goal／Job 等事实投影、明确激活的 Skill 快照。保持来源／版本／scope；是否可以缩短须有明确契约，不由“相关性低”单方面删除 |
| 可调整选择 | 较旧且已闭合的历史、非当前必需的旧工具 schema、目录描述、Memory 摘要及非必要旧观察。按模型可用空间选择，省略时保留可发现／重读入口 |

可用输入容量以 `窗口 − 输出预留 − 协议／估算余量` 为出发点，避免已在估算中计算的开销再次扣除。未知 tokenizer 或视觉模型采用保守估算与真实用量校准；不能把图像 Base64 字符数直接当模型 token。新参数先作为可测策略，不把每个预算都塞进普通用户设置。

必要工具组必须完整，模型仍看到有类型的独立工具；不能为节省 schema 改成万能 `execute(action,args)`。目录缩短后要能按名称／能力继续发现，未曝光不等于未安装／未授权／已禁用；曝光同样不授予执行权限。插件禁用、绑定版本变化和会话切换要使相关缓存失效。

**避免压缩抖动与重复开销。**现有 `ContextSummaryFormat.hasUsefulGain` 已拒绝无有效收益的摘要。应进一步区分“不需要压缩、无候选、历史不一致、必要上下文不可缩、摘要无收益、摘要失败”，接入现有预算事件；不能都表现为一个难以解释的空计划。若主要开销是目录／Skill，而历史已压无可压，继续压缩历史不会解决容量问题。重建也不能立即把刚缩减的可选内容全部塞回。

这里讨论每次模型上下文容量，不恢复已经撤销的普通 Turn 累计 token／调用／工具轮数限制；现行语义以 [ADR-AGENT-006](../../adr/agent/006-model-data-budget-boundaries.md) 的有效 Decision history 为准。

## 4. Memory、连续性、结果与诊断的增量设计

### 4.1 Memory：改善已有渐进读取

[MemoryContext.render](../../../core/workspace/src/main/kotlin/com/helix/core/workspace/memory/MemoryContext.kt) 已只预载摘要，最多两个 scope、总计 8 KiB，并提示 `memory.list/search` 后再 `memory.read`。原建议的“短索引＋相关条目”已具备大部分基础。

首先改善 1,000 字符硬切：按完整条目／段落和 Unicode 边界截取，给出截断及继续读取入口；把真实内容 hash 与 scope 带入选择记录，区分缺失、未启用、权限不足和超预算。若检索质量有证据不足，再比较固定摘要、短索引、确定性关键词候选三种方式；不默认增加一次模型检索调用，也不引入向量库或后台 Memory Agent。

继续保持 [ADR-AGENT-013](../../adr/agent/013-markdown-memory.md) 的默认关闭、独立自动维护开关、可信 Project 成员关系、expectedHash 冲突与不可信内容边界。[SystemPromptContext.registerMemory](../../../app/src/main/kotlin/com/helix/app/application/context/SystemPromptContext.kt) 当前先检查外部读取权限；新的“相关性”不能越过权限加载正文。仓库 `MEMORY.md` 与应用的 global／project Memory 不能因同名或相似内容合并来源。

### 4.2 连续性：事实投影、语义摘要和操作凭据分开

已有 [JobObservationContext](../../../app/src/main/kotlin/com/helix/app/chat/JobObservationContext.kt) 从持久审计读取并按当前工具绑定／策略过滤，`ContextCompiler.withJobObservations` 有界注入观察时间、原身份和审计来源，并明确 terminal 不等于任务成功。应复用这些结构，补齐一般对话压缩所需的原用户约束、后续纠正和来源，而非从零再写一个 Job／任务摘要系统。

每项“关键约束”保留消息来源和适用范围；后来的用户纠正可以替代旧要求。未经确认的模型推断始终标为推断，工具已退出、命令已成功和用户任务已验收分开。摘要不能生成权限、把 UNKNOWN 改成成功，或直接修改 durable 任务状态。也不把所有历史工具结果都永久钉住；重要结果保留可核验引用。

Mobile Use 的长期会话授权与短期 node／gesture 凭据是不同事实。压缩或 runtime 重建后可投影当前会话的授权状态，但不能从摘要恢复旧操作 token、将旧屏幕当成当前屏幕、继承其他会话的授权，或因摘要缺失而自动撤销有效授权。需要当前画面时继续走插件的观察／截图工具，不由通用 ContextCompiler 操作手机。

### 4.3 结果与视觉：先保证可找回，再优化相关性

[ToolModelResult](../../../app/src/main/kotlin/com/helix/app/chat/ToolModelResult.kt) 对符合条件、超过 16 KiB 的成功结果生成 2,048 个 Unicode code point 预览和 `resultRef`，并非所有结果固定截断。[ToolResultReadTool](../../../app/src/main/kotlin/com/helix/app/chat/ToolResultReadTool.kt) 只读同一会话已结算成功结果，逐页读取不重执行原工具；原始完整输出仍是事实来源。

建议由领域提供有界、类型明确的模型投影：保留操作状态、错误／部分完成、关键定位、范围／分页信息和结果来源，再选预览正文。先覆盖实际会丢掉关键信息的尾部错误、跨页表格、搜索命中等场景；未知格式保留现有安全回退，不能递归删除同名用户字段或截破 JSON。复用原结果存储／审计，不为上下文建立第二份业务输出。

[ToolImageWindow](../../../app/src/main/kotlin/com/helix/app/vision/ToolImageWindow.kt) 的两张窗口与 [ToolVisualFeedback](../../../app/src/main/kotlin/com/helix/app/chat/ToolVisualFeedback.kt) 的来源／披露复核有明确价值。应分别比较文档跨页对照、图表比较和 Mobile Use 连续动作：前者可能需要明确保留两页，后者更需要新鲜屏幕；不能统一扩大历史图片数量。

重读必须验证引用仍存在、属于可访问会话／scope、hash 与内容一致；失效或回收给出事实，不能悄悄拿当前文件冒充旧快照。保留文档／页码／版本／截图时间，记录真正送入哪次请求的像素；有本地图片文件、有模型引用、成功编码、Provider 收到和模型正确理解是不同验证层。改变窗口或跨 Turn 选择需更新 [ADR-AGENT-011](../../adr/agent/011-tool-multimodal-vision-feedback.md)，继续遵守对应截图／普通图片的有效披露契约。

### 4.4 诊断：解释这一次请求，避免复制敏感正文

[PromptSnapshotRecord](../../../app/src/main/kotlin/com/helix/app/agent/PromptSnapshotRecord.kt) 已保存段名、顺序、scope、来源、信任级别和 hash；[ADR-AGENT-006](../../adr/agent/006-model-data-budget-boundaries.md) 已有 `budget.request/admitted/result` 与 `context.compaction`。建议在同一审计／manifest 体系增加有界元数据：

| 诊断维度 | 建议记录 |
| --- | --- |
| 请求身份 | modelCall／attempt、模型与配置版本、历史快照标识／checkpoint、选择策略版本 |
| 组成成本 | 系统／项目指导、目录、已激活 Skill、Memory、历史、工具定义、工具结果、图片各自估算；区分 bytes、token estimate 和图片数量 |
| 选择解释 | 保留／省略原因，例如当前必需、已禁用、无权限、旧窗口、超预算、来源失效；按条目有界记录或汇总 |
| 压缩解释 | 前后估算、候选范围、实际收益、无计划／失败原因、额外摘要调用成本；关联源模型调用与 checkpoint |
| 校准与使用 | Provider 整体实际输入、估算偏差和用量来源；没有分段计费证据时，分段只能叫估算 |

不落盘原提示／Memory／引用正文、截图或凭据。hash 和来源元数据仍按既有日志访问与保留规则管理。若现行事件单条限额容不下，应先调整对应 ADR／schema 并测试大小门禁，不能偷偷扩成无限日志。普通 UI 不需要新增预算控制台；先服务开发诊断及固定样本比较。

## 5. 一手参考与取舍

以下为 2026-10-14 查阅的公开源码／官方文档；网页及 `main` 会变化，本轮未安装测试这些产品，也未复制其实现。数字仅说明机制，不作为 Helix 的默认阈值。

| 参考 | 本轮可确认机制 | 采用与边界 |
| --- | --- | --- |
| [Codex compact.rs](https://github.com/openai/codex/blob/main/codex-rs/core/src/compact.rs) | 压缩流程收集用户消息，构造摘要历史，再重建初始上下文并替换历史、重算用量 | 借鉴“历史转换＋明确重建”的结构；Helix 继续使用自身 Room 身份、checkpoint、恢复和权限语义。公开 Codex core 不证明本会话云端 harness 的内部实现 |
| [Claude Code 工作机制](https://code.claude.com/docs/en/how-claude-code-works) | 文档说明旧工具输出清理、必要时摘要、上下文诊断，以及延迟工具／Skill 内容装载；反复压缩仍装不下会停止尝试 | 借鉴可解释选择和避免无收益压缩；不能清除未闭合调用或未知效果事实，也不照搬客户端阈值 |
| [Claude Code Skills](https://code.claude.com/docs/en/skills) | 目录描述有预算；相同渲染内容避免完整重复装载，变化后重新装载；压缩后 Skill 重附加也有边界 | 参考按内容版本去重、目录和重新注入共同预算。Helix 已激活内容的完整性、版本、来源与授权先满足自身契约，不能直接复制截断策略 |
| [OpenHands Context Condenser](https://docs.openhands.dev/sdk/guides/context-condenser) | 事件达到配置阈值时保留开头事件并摘要较老历史 | 借鉴显式保留集合；Helix 还须以 tool batch／Turn／恢复事实为原子边界，不能按固定事件数切断调用配对 |

更早的工具发现研究已覆盖元数据成本与过度隐藏带来的额外往返，继续复用[工具曝光专题](tool-exposure-and-discovery-2026-09-29.md)，无需另建发现系统。竞品共同支持“有界、渐进、可解释”的方向，不能证明某一窗口大小、检索算法或压缩比例对 Helix 最优。

## 6. 实施顺序与现有重构的衔接

以下是本专题内部建议顺序，**不自动调整当前 HXA 或全局排期**。架构承接 [Harness 方案 §23](../../architecture/harness-refactor-plan.md#23-2026-10-14-执行边界重构提案)，本轮不修改其目标和阶段，也不因研究新增第二个 Context 服务／AgentLoop。

| 建议切片 | 可审查交付 | 与目标架构关系 |
| --- | --- | --- |
| 1. 规范历史一致性 | 统一引用／正文物化，完整来源及边界计量；将本轮探针提升为生产回归，加 App 存储装配测试 | 对应上下文快照和 Turn 持久边界；可独立修正，不必等待整个执行层重构 |
| 2. 失败原因与诊断 | 可区分规划失败，逐段估算／选择记录，固定任务基线 | 仍是唯一上下文编译器和审计链；不增加面向用户的复杂设置 |
| 3. 可选内容分配 | 在必要规则、调用配对和能力发现不变的条件下比较目录／旧 schema 预算 | Context 层决定选择，插件提供内容及领域约束；执行器只报告结果 |
| 4. 有证据的领域优化 | 分别比较 Memory 截取／检索、结果投影和文档／Mobile Use 视觉选择 | 与 Memory 快照输入、类型化产物接缝一致；每项独立测试，最终只留一套生产路径 |

不保留旧接口兼容别名、双写历史或长期双实现。短期 A/B 只作为实验对照，不变成永久双管线。涉及契约变化时更新原有 AGENT-002／005／006／011／013、工具／插件相关 ADR 的有效决定与 Decision history；不新建一个覆盖所有主题的总 ADR。实际模型实验承接现有 R2-B 基线与来源指纹，不将本轮主机测试转写为成本／质量收益。

## 7. 验收与单变量比较

| 范围 | 必须覆盖的断言 | 验证层 |
| --- | --- | --- |
| 引用／历史一致性 | 无引用控制组；引用位于压缩前缀／保留尾部；多引用、重复正文不同 ID、空正文带引用、Unicode；正文与引用均进入摘要源及预算 | JVM；App／Room 集成另测 |
| 请求与恢复 | send／backfill／retry／fork／successor Turn；源会话后来改变不重写已接受引用；错序、身份不符、损坏内容失败关闭 | JVM＋持久适配；进程恢复需独立设备证据 |
| 压缩状态 | 连续多次压缩、人工／自动压缩、无候选／无收益／摘要失败／取消；摘要生成期间新输入、旧 checkpoint、事务失败；不重复效果、不回滚已发生外部操作 | JVM＋Room 事务边界 |
| 容量 | 小窗口、未知窗口、多插件大目录、大激活 Skill、中文／emoji／JSON／图片；必要内容超窗有解释，不靠无限压缩或非法截断解决 | 确定性主机样本；端侧峰值另测 |
| 授权与隔离 | Memory 关闭／项目隔离、插件禁用／绑定变化、跨会话结果引用、图片披露拒绝／撤销／目标改变；省略内容不能在重建时越权回归 | JVM＋适配契约；UI／系统权限需设备 |
| 结果与视觉 | 失败／部分成功／UNKNOWN 不转成功；分页 Unicode 完整、结果丢失或 GC 明确；文档跨页对照；旧 Mobile Use 凭据不能因历史引用复活 | JVM；实际截图／恢复需当前任务授权的设备 |
| Provider 协议 | Responses、Chat Completions、Anthropic 的整批调用配对、原调用顺序、视觉来源和无像素原因；三种分别验证 | 编码／解析主机契约不等于真实服务通过 |
| 语义效果 | 多次压缩后的约束、用户纠正、未完成事项与来源保留；Memory 召回和跨页事实比较正确率 | 真实模型固定任务，需要显式授权及配置 |

先冻结模型／Provider／窗口、任务、插件和 Memory 内容版本及授权配置，记录源指纹。每次只改变一个选择策略；使用多次、配对的任务运行，防止把单次随机波动当收益。比较任务完成率、事实／约束错误、无谓重读／发现往返、压缩次数与失败、全任务输入／输出及摘要成本、端到端时延；端侧另外记录峰值内存、序列化／IO 和帧响应。缓存 token／费用与总输入分开，目录排序应稳定；过度动态重排可能降低缓存命中，需测量。

正确性底线是身份／授权／配对不倒退、来源可核验；策略收益需同时看完成质量和总成本。没有当前真实模型与设备证据时，只接受对应主机契约，不宣称完成率、长稳、三个 Provider 或媒体端到端已通过。

## 8. 本轮审查记录与剩余项

- 基线：分支 `main`、上述 HEAD；先保存工作区状态及已有 diff。只读核验 AGENTS、当前状态、验收规则、相关 ADR；CodeGraph 定位后按当前源码复核。审查时 `git diff HEAD --name-only -- app core extensions tools` 无输出，生产源码未在工作区修改。
- 产出：本专题及两处研究导航；没有修改 accepted ADR、重构目标、任务排期或历史证据正文。
- JVM：JDK 17，使用共享 host slot 和离线 Gradle；实际运行 17 项，0 失败、0 跳过。首次沙箱执行无法写本机 Gradle 缓存锁，获执行权限后同命令成功；该环境失败不是测试失败。命令如下（本地 probe 只在指定 init script 下进入测试集）：

```bash
python3 scripts/with-host-slot.py -- ./gradlew --offline --no-daemon \
  -I scripts/debug/2026-10-14/local/context-reference-probe.init.gradle \
  :core:agent:test \
  --tests com.helix.core.agent.ContextCompilerTest \
  --tests com.helix.core.agent.ContextReferenceConsistencyProbeTest
```

本地证据根为 `build/context-optimization-review-2026-10-14/`：`baseline-head.txt`、`baseline-status.txt`、`baseline.diff`、`source-sha256.txt`、`context-tests.log`、`junit/`。探针位于 `scripts/debug/2026-10-14/local/context-reference-probe/`；这些是忽略的本机产物，不作为受版本控制文档的必需依赖，最小复现输入／断言已保留在 §2.2。

`./scripts/check-all.sh --source` 实际执行 exit 0：228 项 Python 主机脚本测试、861 份 Markdown／267 项 HXA 导航、37 份当前 ADR、1105 份生产源码国际化扫描／2292 个资源键及 Secret scan 均通过；日志为同目录 `source-gate.log`。其中报告器测试打印的 FAIL／INCONCLUSIVE 是受控失败夹具的预期输出，没有运行设备。`git diff --check` 通过；12 份关键源码 SHA-256 结束复核一致。

尚未修复生产缺口、未运行 App／Room 集成复现；设备验证 `not requested`，真实 Provider、模型效果与长稳本轮未运行。正常的 JVM 结果不能替代上述边界，也不重新开启“提前结束”的独立问题排查。
