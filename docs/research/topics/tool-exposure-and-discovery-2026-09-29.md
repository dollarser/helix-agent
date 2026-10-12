# Agent 工具曝光、按需发现与大规模扩展组织调研

核对日期：2026-09-29。范围：工具数量、MCP/Skills/Plugins 的组织、竞品机制，以及 Helix 当前源码与历史评测。本文补齐本轮对话分析，不重建一套工具架构。

2026-10-12 Owner 要求结合 Codex、Claude Code、DSH 重新评估，当前源码复核和优化设计见 [§12](#12-2026-10-12-工具架构重新评估与优化设计)。§4–5 的日期化缺口不能作为今日待办：原子绑定、部分匹配排序、搜索提示与 Skill 渐进装载已有后续实现。

**2026-09-29 阶段结论（历史快照）：保留小而完整的默认工具组、统一按需发现和当时的 64 个请求硬上限；优先改善检索、工具依赖可达性、动态窗口连续性和精确绑定。** 当前设计已根据 Owner 反馈调整为取消 Helix 自设的全局数量上限，见 §12.8；历史结论不作为新的实施要求。

**状态与授权：**这是研究输入，不是已接受的新契约或实施记录。R1、上下文编译与插件安装的目标规范仍归 [Harness 方案](../../architecture/harness-refactor-plan.md)；当前实施顺序归 [status](../../development/status.md) 和 [HXA-231](../../completion-records/HXA-231.md)。本次只补文档，不修改生产代码、有效 ADR 决定、开发任务或设备验收结果。

## 1. 现有文档有没有覆盖

有，但此前覆盖的是原则、早期建议和分散证据，缺少一篇把数量口径、最新机制、当前代码与具体优化连起来的专题。

| 现有材料 | 已有内容 | 本专题补充什么 |
| --- | --- | --- |
| [工具综合研究 §2](../modules/04-tools-browser-and-extensions.md#2-tool-exposure) | 少量核心工具、动态发现、曝光不授权 | 数量口径、当前路径、六个具体缺口及验证方法；综合页只保留摘要 |
| [2026-09-18 曝光建议](../../evidence/research-history/tool-exposure-optimization.md) | 当时的截断风险、测量与发现扩展建议 | 区分已经完成的发现扩展和仍需研究的策略，不把旧建议重新排成待办 |
| [插件专题 §15.8–15.11](plugin-ecosystem-and-mobile-use-2026-09-28.md) | 安装、注册、准入、曝光分离；原子绑定与分组 | 大目录检索、窗口保留、Skill 元数据成本与跨来源任务 |
| [ADR-MCP-001](../../adr/mcp/001-client-and-discovery.md) | 已接受的基础组、替换式窗口、小 MCP 直接曝光及硬上限 | 记录数字与源码的差异；新策略必须另经同主题裁决，不能用研究静默替换决定 |
| [Harness §15–17](../../architecture/harness-refactor-plan.md) | 精确绑定、上下文预算、插件安装的目标规范 | 提供研究依据和实验输入，不复制其接口、迁移卡或实时排期 |
| [公开评测试跑](../../evidence/development/public-agent-pilot-2026-09-28.md) | UI 工具截断、Skill 额外发现往返、各轮失败与修复 | 提炼为什么过度曝光和过度隐藏都可能损害任务；保留原始运行口径 |

后续维护：本页保留日期化源码与外部证据；综合模块维护当前判断；接受的契约写回对应 ADR；实际运行结果进入独立证据记录。不能在四处各维护一份默认数字与实施清单。

## 2. “多少工具”必须分五种口径

| 口径 | 定义 | 应如何约束 |
| --- | --- | --- |
| 已安装能力 | 插件、Skill、连接及宿主能力的目录 | 按存储、索引、配置和资源治理；不由模型窗口倒推安装上限 |
| 已注册工具 | 当前注册事实中的可执行契约，可能含多个版本 | 身份、来源、版本和实现绑定准确；目录规模不等于模型可见规模 |
| 会话候选工具 | 经模式、用户选择、禁用与可用性过滤后的候选 | 与当前会话和能力边界一致；不是本次调用的 Approval Proof |
| 模型可见工具 | 某一次实际模型请求携带的工具定义 | 相关性、接口完整性、schema token、协议和上下文硬约束 |
| 实际调用次数 | 一个任务或 Turn 中发出的 ToolCall 数 | 预算、延迟、有效动作和真实任务完成情况 |

讨论“接入 20 个 MCP、800 个工具”时，800 可以是目录设计场景，不能自动变成每次发送 800 个 schema，也不代表 Helix 已测得这一规模下的资源容量。WebCodex 连接器本身暴露多少工具，与 Helix 手机端的注册量没有等价关系。

Skill 主要是指导内容与资源，不应默认变成“一份 Skill 对应一个函数”。将 300 个操作藏进一个 `execute(operation, arguments)` 虽然减少了函数名数量，却没有减少操作语义、参数和前置条件。反而可能失去直接 schema 校验的清晰度。

### 2.1 数量建议与证据等级

OpenAI 的函数调用指南建议先保持较少的可用函数，并把少于 20 个作为软起点，而非硬限制或普遍最优值；工具定义本身计入输入与上下文。[W1]

作为新系统的**实验起点**，普通任务可比较约 16–24 个、UI/跨域任务约 24–40 个模型可见工具。这些区间是本研究的工程假设，不是竞品实测分布，不应写成模型能力定律。Helix 已有自己的证据和默认组，不能为了贴合“20”直接删掉有用接口。

数量之外还要评估 schema 大小、近义工具混淆、必要接口是否一起可见、Skill 正文和结果体积、发现多一次所增加的模型往返。一个冗长 schema 不必然比多个短小而清楚的 schema 更便宜。

## 3. 竞品与协议机制比较

以下仅核验官方文档或原项目 README，访问日期均为 2026-09-29；没有安装并实测这些竞品。在线文档不是固定发布版本，不推断所有客户端、代理和模型具有相同行为。

| 对象 | 一手资料能确认的机制 | 对 Helix 的参考与边界 |
| --- | --- | --- |
| Claude Code | 当前默认对 MCP 使用延迟加载/工具搜索；可配置少量始终加载的工具或服务 | 核心常驻、长尾按需加载；部署与代理兼容条件需单独判断，不套一个固定工具数。[W3] |
| OpenAI Tool Search API | 函数、namespace、MCP 可延迟加载；支持服务端搜索和客户端执行搜索；分组概述仍占初始上下文 | 业务能力分组、客户端检索后装载精确 schema；API 机制不等于每个 Codex 入口的已实现细节。[W2] |
| Codex MCP / Skills | MCP 可配置 `enabled_tools`、`disabled_tools`；Skill 先提供发现信息，再按需读正文 | 用户允许使用哪些能力，与本次需要装载什么分开；Skill 正文不常驻。[W4][W5] |
| OpenCode | MCP 文档提醒上下文成本并支持全局/按 Agent 配置；Skill 工具描述含名称和简介，正文按需加载 | 静态筛选与渐进读取可以复用，但这不证明已实现语义工具检索或大目录最优窗口。[W6][W7] |
| Operit Android | 原项目 README 描述统一市场中的 ToolPkg/MCP/Skills，以及角色对工具包、Skill、MCP 的绑定 | 借鉴用户侧统一管理和会话/角色选择；README 不能证明其活跃工具数量、动态淘汰策略或任务成功率。此处不是另一个 Operit2 项目。[W9] |

### 3.1 两个容易误读的配置

Claude Code 当前的“超过上下文 10% 再启用搜索”对应显式 `auto` 模式，不是默认延迟加载行为的同义说法。非第一方 `ANTHROPIC_BASE_URL` 默认可能关闭工具搜索，原因是部分代理不能转发 `tool_reference`；不能据此假定所有兼容 API 都支持原生发现协议。`alwaysLoad` 也是宿主配置/协议机制，不应成为 Helix 接受外部元数据自授权的理由。[W3]

OpenAI 对小 namespace 的建议是组织提示，不是要求 Helix 每组固定少于 10 个或自动拆成多个 MCP 服务。一个服务可以有 Issues、PR、Repository 等多个逻辑能力组；来源回答“从哪里来”，能力组回答“什么时候需要”。延迟工具仍可能留下名称/简介，不能把 deferred 等同于零上下文成本。[W2]

### 3.2 元数据也需要预算

Codex 当前文档明确约束初始 Skill 列表：至多使用模型上下文的 2%；上下文大小未知时回退到 8,000 字符，并可缩短简介或在警告后省略部分列表。这个数字只说明该产品当前的元数据预算机制，不是 Helix 应直接复制的最优参数。[W5]

Agent Skills 规范也区分元数据、激活后的指令正文、按需资源三个层级。[W8] 因此，大量 Skills 的问题不止是正文懒加载，还包括元数据召回、目录预算和隐藏后如何重新找到。

### 3.3 程序化调用的适用范围

Anthropic 的 MCP code execution 研究讨论按需读取接口，并在代码侧过滤、聚合中间结果后再送入模型。[W10] 这是降低大量结果往返的另一个方向，不是用一个万能工具消除全部复杂度。

对 Helix 只保留为后续输入：复用已有隔离 Runtime，任何宿主能力调用仍经同一 Dispatcher/Policy/Approval。当前离线、隔离的 QuickJS 不能因此增加永久特权 bridge；也不把通用工作流、子 Agent 或另一个代码执行服务作为工具发现的前置条件。

## 4. Helix 当前源码快照

读取基线：本地 `main`，HEAD `742d93b635fbbefa2263da4234b8c7b8f79f7a6c` 加当时工作树。工作树含其他并行改动，不视为该提交的干净制品。关键文件与 SHA-256 见 §10；后续源码变化应重新计算，不把本页当实时遥测。

### 4.1 当前请求路径

```text
ToolRegistry 中全部已注册契约
  → 同名选择最新版本
  → ModePolicy / 功能过滤
  → McpToolDiscovery.visible(session, admitted, defaultNames)
  → 共享 disabledToolFilter 再过滤
  → ModelToolExposureOrder 稳定排序
  → take(ModelRequest.MAX_TOOLS)
  → FileToolArguments.modelSchema
  → ToolPresentationMetadata.augment
  → 后续请求物化与容量准入
```

依据 C1–C4。`McpToolDiscovery` 名称仍含 MCP，但搜索已经覆盖各来源的已注册工具；不应再立项“首次把发现扩展到内置工具”。搜索返回简要名称、版本和描述，下一次请求再放入真实 schema，不是允许模型猜任意 JSON 后直达 executor。

### 4.2 当前数字与限制

| 项目 | 静态读取结果 | 解释 |
| --- | --- | --- |
| 文件基础候选 | 7 | `read/write/edit/files.list/files.stat/files.search/view_image` |
| Goal 相关候选 | 4 | `GoalLifecycleTools.names`，包含报告工具 |
| 其他基础候选 | 11 | 发现、结果读取、JS、Plan/Todo、5 个 Skill 基础操作、时间 |
| 不含 UI 的默认名称集合 | **22** | 是过滤前候选集合，不是每个会话实测发送 22 个 |
| 有效自动化会话的 UI 组 | **10** | 包含观察、查找、动作及 `ui.set_progress` |
| 含 UI 的默认名称集合 | **32** | 由有效自动化 scope 触发，不是所有 Advanced 会话都加入 |
| 单次搜索默认 / 最大返回 | **8 / 16** | 当前动态发现窗口最多 16 个 |
| 小 MCP 直接曝光条件 | 所有准入 MCP 工具合计不超过 **16** | 不是每个服务器单独拥有 16 个配额 |
| 请求硬上限 | **64** | Helix 自身的请求约束，不是通用 Provider 上限 |
| 发现缓存会话数上限 | **128** | 不是工具数；缓存进程内存在，重启后重新发现 |

最终数量还受注册、模式、禁用、可用性及去重影响。按当前集合机械相加，`32 + 16 + 16 = 64` 是保守候选和，并非证明某次请求实际有 64 个或已经测得 token 可容纳。

**文档差异：**当前 ADR-MCP-001 正文仍写 21 个基础、9 个 UI、合计上界 62；历史评测也记录 21/30。当前源码增加后的静态候选为 22/32，不能拿旧数字作为今日源码断言，也不能把历史测试自动改记成新数字。本次保留原 ADR 决定和评测记录，显式记录差异；后续在原 ADR 中做有依据的事实对齐，窗口语义变化另经接受。

### 4.3 应保留的实现

C2/C3 已复用禁用判断，发现、曝光及执行的可用性不能独立漂移；曝光仍不替代执行时的实时授权。C1 将 UI 观察与动作放进完整组，避免动作可见却无法获取目标。C5 使用六个通用 Skill 操作而不是一 Skill 一函数。C6 已有 `PluginOrigin` 和 bundled 插件注册路径，不是还没有 Plugin 系统。

C9/C10 显示 Marketplace 与 Connector 已有安装、状态和会话选择基础；完整插件生命周期仍需后续收敛，但不能描述为全部尚未实现。

## 5. 六个具体改进点

本节区分已读到的代码行为、影响推断及建议；没有新的设备运行或性能证明。

### 5.1 AND 子串搜索与名称排序

**现状（C2:43–56）：**查询转小写后按空白分词，要求每个词都出现在名称或简介中，再按工具名字母序截取。没有在这条路径看到同义词、跨语言映射或相关性排名。

**影响：**较长自然语言、中文需求对英文描述可能零命中；按名字靠前不等于最适合任务。`find recent github issue comments` 可能因为缺少 `recent` 就排除正确工具。这是机制推导，不能虚构具体模型的失败概率。

**建议：**先做精确名称优先、分词相关性评分、来源/能力组过滤和少量可维护别名；无命中时返回更窄关键词或能力组提示。索引摘要来自可信装配和受限元数据，不接受工具描述要求“总是选我”的指令。向量检索不是第一步必需品，只有在冻结用例证明收益后加入。

### 5.2 每次搜索替换窗口，包括零命中

**现状（C2:57–59）：**`loaded[sessionId] = matches`，新搜索整体覆盖旧结果。

**影响：**“读 GitHub Issue → 搜索文档能力 → 写文档 → 回 GitHub 评论”可能在第二次发现后失去先前的非默认工具；若原工具仍属于小 MCP 直接曝光集合则不受此例影响。不能声称所有跨域任务都会失败。

**建议：**有限保留当前任务依赖、新命中和少量近期相关工具，低价值项按容量淘汰；零命中不清空仍有效的工作集。禁用、来源撤销或版本失效应立即清理，不能借保留策略使用旧权限。不要改成只增不减。

**裁决边界：**这与当前 accepted ADR 的替换语义不同，属于待接受的策略，不在 R1 顺带实施。

### 5.3 MCP 总量 16→17 的突变

**现状（C2:75–85）：**所有准入 MCP 合计不超过 16 时自动加入；超过后不自动加入整个 MCP 集合，只保留其他符合规则的已发现工具。

**影响：**新增一个不相关工具，可能使此前直接可见的 MCP 工具需要额外发现。该规则没有区分任务、schema 长度或使用频率。

**建议：**按明确的会话选择、任务相关性、常用组与最终 schema 成本决定提前加载。分组是逻辑索引，不强迫一个服务拆成多个连接。需保留无组元数据工具的通用搜索路径，并测试 15/16/17 项边界。改变阈值行为同样需要更新原 ADR，而非只改一个常量。

### 5.4 数量限制不等于工具 token 预算

**现状（C3:324–330）：**先按数量截取，再生成 schema 和补充展示元数据。缺口是这条选择路径未按最终工具定义成本分配软预算，**不是 Helix 完全没有上下文预检**。

**建议：**复用上下文编译，把最终 Provider 投影的工具定义、Skill 元数据/正文、历史、结果和多模态分别计量，再受共享硬窗口约束。估算器身份、字节数与估算 token 分开记录；能使用可靠 tokenizer 时也需与真实请求口径核对。

不能为了缩短 schema 删除 required、enum、前置条件或来源身份。必需能力放不下时给出诊断/重新发现路径，不只静默丢弃最后几个工具；缓存稳定前缀有价值，但不以缓存命中压过实时撤销。

### 5.5 Skill 正文已懒加载，目录发现仍是分页

**现状（C5:125–149）：**`skills.list` 从 repository 列表按 offset/limit 截取，返回名称、简介、快照与启用状态，没有 query 或相关性排名。

**影响：**安装大量 Skills 不会线性增加函数数，却可能让模型翻多页元数据。目录全部注入提示也只是把成本从函数 schema 搬到文本。

**建议：**在现有 list 增加查询，或者引入单一 search 入口，避免为同一职责同时增加多个工具。索引名称、简介、适用场景、来源、别名与快照；正文继续通过 `skills.read`，参考资料通过 `skills.read_resource` 读取。Skill 提示的工具依赖只帮助发现已可用能力，不能开启连接、改变会话选择或授予执行许可。

### 5.6 descriptor 与 executor 仍需原子绑定

**现状（C6:64–67、C7:146–151）：**插件和 MCP 分别写描述符与实现注册表。单张表线程安全不等于跨表原子快照。

**影响：**动态替换、禁用与请求并发时，绑定一致性是结构性风险；现有 MCP bridge 身份/来源检查仍在（C7:107–128），本次没有复现越权执行或实际线上错配，不把结构缺口夸大为已确认漏洞。

**承接：**复用现有 R1，将模型 schema、搜索结果、审批及 executor 指向同一个精确绑定。不要因为已有 `PluginToolBinding` 值对象，就宣称统一原子注册表已经交付；也不为检索另造一个可写事实注册表。

## 6. 推荐的整体组织

### 6.1 统一身份、发现与治理，不混同运行机制

```text
Plugin 安装身份 / 独立 Skill / 独立用户 Connection
  ├─ Skill 快照与元数据 → 内容发现索引 → 按需读取
  ├─ MCP Connection → 已就绪工具贡献
  └─ 宿主已知 Native Runtime → 工具贡献
                           ↓
                原子 Tool Binding（R1 目标）
                           ↓
                 会话、模式、可用性过滤
                           ↓
              能力分组与检索的只读索引投影
                           ↓
              ContextCompiler 选择本次工具
                           ↓
                       ModelCall
                           ↓
          同一 Dispatcher / Policy / Approval / Audit
```

图是职责关系，不要求一框一模块、一服务或一数据库。A2A 仍是外部 Agent Client 贡献，不能因统一目录变成本地子 Agent。MCP 管协议/连接，Skill 管内容，Plugin 管归属/安装，Marketplace 管发现/管理 UI；目录只是事实的投影。

`ConnectorService` 的通用包职责逐步归插件安装域，认证、账户与重连继续归连接域；保留独立导入，不为所有 Skill 和用户连接强造一个市场插件。具体安装、更新、卸载及局部失败契约只引用 Harness §17。

### 6.2 按能力分组，而不只按来源

```text
GitHub 来源
  ├─ Issues：search / read / comment
  ├─ Pull Requests：read / diff / review
  └─ Repository：list / read / commits

Mobile Use 来源
  ├─ Observation：snapshot / find
  ├─ Interaction：click / set_text / set_progress / scroll
  └─ Navigation：back / home / wait
```

示例名不是要求注册新工具。一个来源可以提供多个组；同名工具必须以可信来源、安装/连接身份、版本区分，不能仅靠向模型展示的短名解决账号或权限隔离。分组缺失时仍允许基于原始契约搜索。

### 6.3 有限工作集与最小完整能力

候选思路为“核心 + 当前任务所需组/依赖 + 新命中 + 少量近期项”，再在数量与 token 双预算内选择；不是给每类机械分配不可借用配额。

| 规则 | 要保留的行为 |
| --- | --- |
| 基础工具 | 发现、结果分页、必要文件操作等不被无关长尾挤掉；是否常驻以任务证据调整 |
| 依赖可达 | UI 动作依赖观察，读取长结果依赖分页；必要接口一起进入上下文，但不强制模型固定操作顺序 |
| 新命中 | 不向模型报告“已经加载”却在下一请求被无声截掉；明确实际装载与容量限制 |
| 最近使用 | 只有限保留仍相关工具，不无限累加；多轮上下文稳定性与成本一起评估 |
| 失效处理 | 来源停用、单工具禁用、绑定变化、会话/模式收紧优先；旧输出仍是历史，不承诺模型遗忘 |
| 生命周期 | 会话隔离，重启/压缩后的再发现可解释；不为窗口恢复复活旧 Turn 或扩展 Job 控制权 |

此工作集是上下文选择，不是第二个任务规划器。当前规则仍按 ADR 执行，表中窗口保留行为只是实验候选。

### 6.4 多 Provider 的落法

优先保留 Helix 已有的通用路径：本地搜索受限目录 → 返回简要匹配 → 下一次请求注入真实函数 schema → 正常执行。原生 `tool_reference`、namespace/deferred 协议可作为能力探测后的 Provider 适配，不成为本地模型完整 Agent loop 的前提。

不要为了降低函数计数默认改成 `invoke(toolName, arbitraryJson)`；通用调用器如确有协议需求，也须解析精确契约、校验参数并回到统一治理。对本地模型按真实任务调窗口、提示与预算，不能仅根据参数量把它降级为摘要助手。

### 6.5 用户界面和诊断

用户侧优先显示插件/连接、账户、组件、会话选择和可修复状态；工具详情展开。分别呈现已安装、已就绪、本会话可用、本次加载、被禁用/需配置，不用一个 Enabled 开关承担所有含义。一次动作需要审批，与工具是否已经进入模型上下文也不同。

诊断建议基于既有请求清单扩展：候选/可见数量、来源与绑定 revision、加入/未加入原因、schema 字节和估算 token、发现往返、失效原因。实际发送的映射才是本次曝光证据，不能只记录排序前的列表。默认不采集账户凭据、正文或整份私有工具描述。

## 7. Helix 自身评测给出的取舍证据

下面均引用已有 [2026-09-28 试跑记录](../../evidence/development/public-agent-pilot-2026-09-28.md)，不是本次新运行；APK、模型、预算和样本口径以原记录为准。

| 历史观察 | 能支持的结论 | 不能外推什么 |
| --- | --- | --- |
| 第三轮 AndroidWorld 中 64 个工具只包含 `ui.back/ui.click`，缺 `ui.snapshot`；当时搜索只覆盖 MCP（原文45行） | 截断和发现范围可让必要工具失联 | 不是当前发现仍只搜 MCP，也不是证明所有任务都受截断影响 |
| `ui.scroll` 未在 schema 公开 `forward/backward`，随后补充 enum（55行） | 参数可表达性比单纯压短描述重要 | 不证明增加描述必然提高所有模型表现 |
| 轨迹出现过长 AND 查询（74行） | 当前检索策略具有真实交互成本的研究依据 | 不把某次轨迹当成普遍零命中率 |
| 14/15 的 P5 中 Skill 禁用经过多次 search/list，操作成功但触及模型调用上限（100行） | 常用工具隐藏过度可能增加失败 | 不把操作成功等同整个任务成功 |
| Skill 读取/启停成组进入默认候选后，clean `8f0aa933` 完整 P5 15/15；skill-004 仅一次 disable（102–114行） | 当前基础组有任务证据，不能仅为低于20机械删除 | 单轮成功不是因果 A/B、广泛成功率或性能提升证明 |

较早 P5 的文件失败有精确内容/换行等原因，不能概括为“P5 已证明核心 write 被挤掉”。UI 截断、发现往返、参数错误、权限拒绝、预算耗尽和目标未完成需要分别归因。新的 22/32 候选也不自动继承旧 21/30 制品的验证结果。

## 8. 与既定重构如何衔接

| 承接阶段 | 本研究建议的输入 | 本轮不做的事 |
| --- | --- | --- |
| R1 原子绑定 | 搜索、实际请求映射、审批与执行统一引用精确 binding | 不顺带改替换式窗口、MCP阈值或默认工具组；不重建 Loop |
| R2-A 等价迁移 | 把现有选择、物化和容量结论迁入唯一上下文编译入口 | 不在结构迁移中混入未测 Prompt/排序收益变化 |
| R2-B 策略实验 | 检索相关性、窗口保留、元数据/schema预算和依赖组；先确认同主题ADR差异 | 不把本文当作立即实施授权或独立新排期 |
| R3 插件/连接闭环 | 安装身份、组件索引、Skill元数据发现和用户状态展示 | 不复制 MCP/Skill Runtime，也不让安装/搜索自动授予权限 |

当前建议暂保留 22 个基础候选、有效 UI 会话额外10个、默认搜索8/最大16、请求硬上限64；这只是保持已有源码基线的研究取舍，不是本次接受新的永久常量。新增 schema 成本控制及保留策略应通过冻结实验决定；不为接更多 MCP 直接把 64 提至128/256。

### 8.1 后续需要明确的裁决

需要在原主题确认：替换还是有限保留；零命中是否改变窗口；小 MCP 提前加载条件；工具组依赖与强制核心超预算时的处理；Skill 元数据查询入口；Provider 原生发现的适配边界。尤其前两项直接不同于当前 ADR-MCP-001，必须先接受再改代码。

本专题不新建开发 HXA，不把文档补充记成 R1 完成，不改变当前“R1 → 内测反馈 → 后续阶段”的次序。临时研究排序不应成为永久重构前置。

## 9. 后续实验与验收方案

这是未执行的建议矩阵。先记录当前 baseline，再分别调整检索、窗口、预算；不在一次对比中同时换模型、任务、Prompt、预算和权限。

### 9.1 分开目录规模和曝光策略

可使用 50/200/1000 个工具的合成目录作为实验条件，搭配短/长 schema、重复近义名和中英文描述。这些是测试设计，不是当前支持容量声明。真实业务工具另以固定合法配置测试。云端与本地模型分别记录版本、上下文、协议和预算；不默认调用收费服务或真实账户。

对比组为当前实现、仅改检索、再改有限窗口、再改 schema/元数据预算；每阶段保留失败与无改善结果。有代表性的重复任务应报告样本、分布和不确定性，不能用 temperature=0 的重复结果冒充独立样本。

### 9.2 指标

| 指标 | 口径 |
| --- | --- |
| 必需工具召回 | 对冻结任务标注必需接口，记录 Recall@k、零命中率；不能只看返回数量 |
| 发现开销 | 每任务发现次数、首次有效工具前的模型往返、反复搜索与翻页 |
| 工具使用质量 | 误选、参数校验失败、过期绑定、遗漏观察/分页等依赖 |
| 任务完成 | 独立产物/状态 oracle；Turn COMPLETED 或工具完成不能代替目标完成 |
| 成本与资源 | 最终schema字节/估算token、真实输入用量、模型调用数、延迟分布和宿主峰值 |
| 一致性与安全 | 禁用/更新/模式收紧后不泄漏或调用旧绑定；搜索和Skill加载不扩权 |

### 9.3 必测边界

| 场景 | 预期检查 |
| --- | --- |
| 两个 MCP 交替使用 | 后续必要工具可达，工作集有界，不靠额外权限找回 |
| 零命中、长句、中文查英文、同义词 | 解释无命中及收窄方式；保留策略按已接受契约处理 |
| MCP 15/16/17 项，单个超长schema | 观察曝光突变与token限制，不因数量低就宣称低成本 |
| 新命中与核心组合计超预算 | 保留最小完整能力，实际装载与返回说明一致 |
| 搜索后工具更新、审批中禁用 | 原绑定不跳到同名新executor；失效明确、拒绝不扩权 |
| 关闭来源、切换会话、收紧模式 | 搜索、请求与执行一致；历史结果不被当成授权 |
| Skill 多页目录或需要未启用工具 | 元数据可检索；提示需要配置，不自动启用或批准 |
| 压缩、进程重开、工具结果截断 | 可重新发现，结果分页可达，无隐式重放/旧Turn复活 |
| 不需要工具的普通问答 | 不强迫每次先搜目录、启动Goal或运行代码 |

先做确定性主机测试与合成模型响应；真实模型、模拟器、真机和账户测试分别获得当次授权并记录。GitHub CI 仍只做主机检查，不启动设备。本文的文档门禁通过也不构成功能或设备通过。

## 10. 源码证据定位

行号是本次读取快照，文件链接指向当前工作树；未来行号变化时用符号与 SHA 复核。C 编号仅用于本页，不是新架构对象。

| 编号 | 文件与读取范围 | 支持内容 |
| --- | --- | --- |
| C1 | [ModelToolExposureOrder.kt](../../../app/src/main/kotlin/com/helix/app/chat/ModelToolExposureOrder.kt)，13–75行 | 7+4+11默认候选、10UI、排序 |
| C2 | [McpToolDiscovery.kt](../../../app/src/main/kotlin/com/helix/app/mcp/McpToolDiscovery.kt)，24–160行 | AND检索、替换窗口、全体MCP阈值、8/16/128、下一请求加载 |
| C3 | [ChatRequestAssembler.kt](../../../app/src/main/kotlin/com/helix/app/chat/ChatRequestAssembler.kt)，289–330行 | 实际选择链、共享禁用、截取与schema投影顺序 |
| C4 | [ModelRequest.kt](../../../core/model/src/main/kotlin/com/helix/core/model/ModelRequest.kt)，256–280行 | 硬上限64与重复名称拒绝 |
| C5 | [SkillTools.kt](../../../extensions/skills/src/main/kotlin/com/helix/extensions/skills/SkillTools.kt)，25–167行 | 六类管理操作、分页元数据、hash固定正文读取 |
| C6 | [PluginRegistry.kt](../../../extensions/plugin/src/main/kotlin/com/helix/extensions/plugin/PluginRegistry.kt)，9–77行 | bundled插件、来源校验、描述符/实现双写 |
| C7 | [McpAppService.kt](../../../app/src/main/kotlin/com/helix/app/mcp/McpAppService.kt)，94–151行 | 用户选工具、bridge检查与双表停用 |
| C8 | [GoalLifecycleTools.kt](../../../app/src/main/kotlin/com/helix/app/goal/GoalLifecycleTools.kt)，19–21行 | 四个默认Goal名称 |
| C9 | [MarketplaceService.kt](../../../app/src/main/kotlin/com/helix/app/marketplace/MarketplaceService.kt)，12–86行 | SKILL/CONNECTOR/MCP状态及包安装 |
| C10 | [ConnectorService.kt](https://github.com/dollarser/helix-agent/blob/05e910039e03095d98649d96a9e64a3a71bcb078/app/src/main/kotlin/com/helix/app/connector/ConnectorService.kt)，19–97行 | 安装/连接入口与会话选择 |

```text
C1  00f1d1fda1b662012be321fe55d01cf5ad217aa928e92bdb680c7ff50e272ebf
C2  a6f2e30ae3fc73aeb37d94d8ac16af87903c118e17dde1b5d4d38840d9927575
C3  bd188fef95f66d2adfea3dd409ab11ea9f442ba6ac651e09c0d438604b5540f6
C4  aea4b42a6f2b3756dfb3d87c0b52fc7c7561e8b6835adb2032090acdbf40dc3d
C5  625445c2b854a547cfc7ebb95f927a9d7943028bddca8a20e9c25f1287a1eb71
C6  3a707f0bc5fd2fe1614bb7c30408e106af2dd86464c3971872fea23358c1fb96
C7  d29cdba0b3d2e17057558262ef0ad59b51659a294e2926d5715057fabaf71074
C8  71c74443455f8f6c7e716e440777eb11d1fa46c1581acc565b0225d4d462ac12
C9  9bd9b7808363e5e9722e4fcd8dd8ded72a6b39c4f18cfe9d2bf9c21e7dc238db
C10 5d2fb0926256430781d98854d24f904de99743c6b431f0b4a54e645cfd28ecee
```

## 11. 外部一手来源

以下均于 2026-09-29 阅读；除 W10 注明发布日期外，按在线文档或 README 的当日表述理解。没有从未核实的发布号推断功能，更未把外部工程示例的收益比例移植成 Helix 的实测收益。

| 编号 | 来源 | 本页使用范围 |
| --- | --- | --- |
| W1 | [OpenAI Function calling](https://developers.openai.com/api/docs/guides/function-calling) | 少量初始函数、少于20软建议、定义成本 |
| W2 | [OpenAI Tool search](https://developers.openai.com/api/docs/guides/tools-tool-search) | 延迟函数/namespace/MCP、客户端搜索、分组与初始元数据 |
| W3 | [Claude Code MCP](https://code.claude.com/docs/en/mcp) | 默认搜索、auto阈值、alwaysLoad、代理兼容边界 |
| W4 | [Codex MCP](https://learn.chatgpt.com/docs/extend/mcp?surface=cli) | enabled_tools / disabled_tools |
| W5 | [Codex Build skills](https://learn.chatgpt.com/docs/build-skills) | 按需正文与初始Skill元数据预算 |
| W6 | [OpenCode MCP servers](https://opencode.ai/docs/mcp-servers/) | 上下文成本、全局/按Agent配置 |
| W7 | [OpenCode Skills](https://opencode.ai/docs/skills/) | Skill名称简介与正文按需读取 |
| W8 | [Agent Skills specification](https://agentskills.io/specification) | 元数据/指令/资源的渐进加载 |
| W9 | [Operit Android 原仓库](https://github.com/AAswordman/Operit) | 市场与ToolPkg/Skill/MCP角色绑定；不推断内部窗口算法 |
| W10 | [Anthropic: Code execution with MCP](https://www.anthropic.com/engineering/code-execution-with-mcp) | 2025-11-04文章；接口按需读取和结果就地处理的方向 |

最终取舍是让能力可发现、当前接口完整、工作集有界、执行绑定准确；数量减少只是可能的手段，不是验收目标。

## 12. 2026-10-12 工具架构重新评估与优化设计

**设计结论：保留统一原子注册表、独立工具契约、会话准入与 Dispatcher；优化本次请求的能力目录、检索、工作集连续性和 schema 成本。** 当前不需要全量 MCP 化、通用动作 JSON、第二套注册表或新的脚本执行域。竞品机制不能直接决定 Helix 的默认工具数、权限或 Android 资源容量。

本节对应 Owner 本轮设计请求及随后取消 64 上限的需求反馈。Helix 核对基线为 `c31640a3be0f909cf52659e7122bf573e206ee1c` 加当前工作树；涉及实现未修改。当前目标需求见 §12.8；accepted ADR-MCP-001 及生产实现仍保持旧契约，实施时须同步修改有效正文和 Decision history。不重新打开 HXA-252、272～279 已交付切片。设备、真实模型和性能对照本轮未执行，收益尚待验证。

### 12.1 三个参考项目的实际取舍

| 参考 | 当前一手证据 | Helix 采用方向与边界 |
| --- | --- | --- |
| Codex 公开核心 | `build_tool_router` 合并内置、MCP、扩展和客户端动态工具；注册表与模型可见规格分离。MCP 暴露受模型搜索能力及配置控制；延迟工具经客户端 BM25 返回可加载定义，下一次模型调用可用 | 复用已有注册事实投影来源目录，按需提供真实 schema；不将公开 main 等同本机桌面部署，不复制代码或声称统一固定常驻数 |
| Claude Code | 官方 MCP 文档描述默认延迟工具定义，启动提供工具名及服务器说明；`alwaysLoad` 可指定提前加载，`auto` 才是上下文比例阈值模式；代理和模型兼容性会改变行为 | 来源简介应让模型知道何时搜索；少量常用组提前提供。外部 `alwaysLoad` 仅可作提示，不获得 Helix 权限或强占上下文；不照搬 10% 或通用工具数量 |
| DSH | ToolRuntime 合并作用域可见工具；默认 `native` 提供全部可见 schema。`ptc` 只提供 `run_code` 外层工具，另生成 SDK 提示，内部仍经工具执行链。MCP adapter 单独负责发现、注册与代际替换；工具变化可在请求边界重新组装 | 保留工具注册、呈现和执行分离及准确请求快照。PTC 是另一种呈现方式，不是已确认的通用按需搜索；外层函数少不等于 SDK/结果 token 少，不据此改造 Helix QuickJS |

固定核验版本：Codex `0c7362d121d1c7f69540262425fd4e2209fd8e8d`；DSH `d743267388641bc76f17c45ce8b4c231aed1d32c`。Claude 使用 2026-10-12 在线官方文档，没有运行三者的真实任务。§12.7 提供精确来源。

### 12.2 Helix 当前具备什么，缺口在哪里

当前生产链为：贡献发布到 `ToolRegistry` 的原子 binding → 当前模式/会话/来源可用性过滤 → 默认工具及发现窗口 → 最终模型 schema 和 `bindingRef` → RequestContextManifest → Dispatcher 执行。MCP/A2A/native 的执行 adapter 不必相同。

| 环节 | 当前事实 | 重新评估 |
| --- | --- | --- |
| 注册与调用身份 | `ToolRegistry` 使用 ToolBindingStore；请求 schema 绑定 owner、version、contractHash、implementationRevision、incarnation | 已交付，应保留。旧 §5.6 的分离表缺口不再是当前任务 |
| 会话准入 | `PluginCatalog.sourceAvailable` 检查来源及会话选择；`PluginRegistry.selectedRuntimes` 过滤就绪和选择；发现、schema 与执行复用可用性规则 | 已有权限边界，不能描述成未选择插件也能靠搜索启用 |
| 初始工具 | ModelToolExposureOrder 的共享默认候选为 18；无 preferred 时额外提供 JavaScript；Mobile Use preferred 14，Browser preferred 3 | 默认组有优化空间，但不能只为达到某个数量删除能力 |
| 搜索 | tools.search 已覆盖统一目录，完整名称优先；组合词有部分匹配与相关性排序，名称/描述仍是主要字段 | 不再立项首次统一发现或首次支持 OR；补来源、能力类别、字段名和中英文可维护别名 |
| 发现状态 | 默认返回 8、最多 16；命中替换窗口，未命中保留有效成员；最多缓存 128 个会话，进程内状态 | 搜索不同子任务会淘汰此前工具；是当前明确契约，是否改为有界保留需对照 |
| 请求选择 | visible 初步排列发现结果，随后 prioritize 重排并 take(64) | 多个 preferred 组挤满容量时，发现结果可能仍被最终截断；需要最终集合级的完整性保证和诊断，不仅检查搜索返回 |
| MCP 初始曝光 | 所有准入 MCP 合计不超过 16 时全部提供，超过则转发现 | 16/17 造成与任务用途及 schema 大小无关的行为突变；应评估统一按组/成本选择 |
| 上下文估计 | ModelInputEstimate 已估计 tools，RequestBudgetDiagnostics 已区分 instructions/history/tools/images | 缺口是曝光选择尚未按工具成本分配，不是没有上下文预算；现有估计也不等于 Provider 精确 wire/tokenizer 计量 |
| Skill 内容 | 名称/简介目录、requiredInstructions、按需正文、显式激活投影及参考资料已接线 | 保留 HXA-278；目录预算和检索可复用，不能重做整套内容装载 |

默认候选数量的静态示例：无插件 preferred 为 19，Mobile Use 为 32，Browser 为 21，两者同时选择为 35；不含额外 MCP 和发现窗口。这些是集合计数，不是本轮真实请求或 token 测量，模式、注册、禁用和就绪会减少实际集合。ADR 中的 21/9/62 是旧快照，实施时应在原 ADR 对齐当前事实，并保留原决定历史，不添加这些计数的固定门禁。

### 12.3 建议目标结构

```text
内置 / Native Plugin / MCP / A2A 工具贡献
                    ↓
已有原子 ToolRegistry 与精确 binding
                    ↓
会话选择、模式与当前可用性过滤
                    ↓
同一快照的只读来源目录和搜索索引
                    ↓
本次工具选择：必要控制 + 完整操作组 + 新发现 + 有效近期工具
                    ↓
最终 schema 成本、Provider 已知限制及完整性检查
                    ↓
ModelRequest + RequestContextManifest
                    ↓
原 Dispatcher / Policy / Approval / Execution / Audit
```

来源目录、搜索索引和工具选择是已有事实的投影，不新增可写权限或第二份执行注册表。模型负责从任务推导搜索词和选择调用；宿主决定允许发现的范围、初始组和最终集合，不先调用另一个模型给全部工具分类。

### 12.4 具体候选策略

**A. 让模型知道本会话有哪些能力。** 在现有环境提示中提供简短来源名、用途、可搜索类别及就绪事实，来源身份复用 PluginCatalog 和 binding owner。仅包含本会话可用能力；已选择但配置不足的原因复用 pluginReadiness，未选择插件不伪装可用。外部简介作为有长度限制的数据，不直接执行服务器 instructions。目录超出预算时保留简要来源名和单一 tools.search 入口，通过查询取得细节；不全量注入每个工具简介。

**B. 搜索增强而不扩权限。** 在现有 tools.search 中逐步加入可选来源/能力类别过滤，索引名称、简短描述、可信来源显示名、参数字段和宿主维护的中英文别名。首先保留可解释的精确名称及关键词相关性，不引入向量模型或独立服务。外部名称和简介受限、不能自设优先级。零命中区分“无匹配”与本会话已有可公开的配置原因，不泄露其他会话、未授权来源或凭据。返回来源信息和下一请求生效说明；真正可调用的集合仍由下一请求 schema 决定。

**C. 将默认规则写成能力组，而非注册顺序。** 工具继续保持独立名称和 schema，分组只影响选择。文件读写、目录/搜索、手机节点观察与动作、坐标截图与动作、异步任务启动与收取等保持工作流完整；宿主维护的组不能自动给予能力或启用来源。Goal 四个工具、JavaScript 默认回退及文件工具哪些条件常驻，需要独立任务对照，不一次全部隐藏。Browser 当前 open/snapshot/find 组先保留。

Mobile Use 首个保守候选只将 ui.device 延迟；后续再单独比较 screenshot/gesture 的呈现。14→11 只是一个实验候选，不设为设计目标。视觉任务可能因截图额外发现反而更慢；坐标动作必须有合法观察帧，语义节点动作必须保留 snapshot/find，不能为了少函数破坏工具前置条件。

**D. 用有界近期保留解决连续发现。** 比较现行“成功即替换”和“新结果 + 仍有效的近期工具”两种测试策略。候选窗口暂沿用 16，不无限累加；优先新发现、在途 Job 必需查询/收取及完整操作组，按实际使用与发现顺序淘汰可选成员。新查询所得完整操作组放不下时明确报告，不假装已加载。先继续同会话单次搜索约束，不因改窗口立即允许并行搜索。确定顺序、撤销、版本变化和请求绑定必须可回归；缓存依旧不是恢复事实，进程重启从当前授权目录重新发现，已有 UNKNOWN 不重放。

**E. 取消 Helix 自设的全局数量上限，保证最终可调用。** 删除请求链中的固定 64 截断及联动校验，不换成另一固定数量。必要控制、当前发现结果及其操作前置契约在最终选择后仍应可达；新工具未进入最终集合需有机器可读原因和可执行的缩小查询路径。只有 Provider 已知的硬限制、上下文和序列化/存储资源边界能约束请求；受保护集合无法完整容纳时给出明确诊断，不能只截去末尾。具体需求与验收见 §12.8。

复用 ModelInputEstimate、ContextCompiler 和 RequestBudgetDiagnostics 记录默认/发现/目录成本及选择原因，逐步核对 Provider 实际投影；准确 token、估计 token、字节分别记录。软预算只淘汰可选工具或目录详情，不剪掉 required、enum、前置条件或权限说明。先采样不同上下文大小、模型协议和渠道，再决定软预算；不照搬 Claude auto 的 10% 或 Codex 特定插件的字节上限。MCP 16/17 的全量阈值可在此阶段改为常用组/成本准入，其余统一发现。

**F. 原生 deferred 协议作为 Provider 适配。** Helix 本地 tools.search → 下一请求普通 schema 的通用链先保持，确保 Qwen、自建兼容 API 和其他 Provider 可用。以后只有实际支持 native tool_search/tool_reference 的 Provider 才走专用编码；不以该适配为以上改进前置。DSH PTC 和 Codex Code Mode 可作为将来大量结果就地聚合的输入，本轮不添加通用特权脚本桥接。

### 12.5 实施顺序与契约归属

| 切片 | 内容 | 工作量与依赖 | 完成判据 |
| --- | --- | --- | --- |
| 0 | 取消固定 64 截断及请求/绑定/manifest 联动校验，补 Provider 与存储边界 | 中；先同步原 ADR，复用现有上下文预检；实施前核对所有消费者 | 超过 64 仍能完整编码、绑定、记录和调用；无静默丢失、无另设固定全局数量；存储越界有明确失败 |
| 1 | 同源能力目录、来源字段与检索增强 | 小到中；复用现有注册及可用性。先补研究引用、当前数字事实和测试矩阵 | 中文/英文/来源查询可发现；禁用、模式、会话、替换与零命中正确；不重复已交付排序优化 |
| 2 | 最终集合完整性、选择原因及定向边界测试 | 中；先处理实际可调用性，再优化减少数量 | 搜索结果、实际 schema 和 manifest 一致；拥挤时保持完整组或明确未装载，不静默丢工具 |
| 3 | 有界近期窗口候选和工作流对照 | 中；依赖切片 2，改变替换语义须更新原 ADR | start→await、文件→网页→文件、交叉来源操作减少重新发现；取消/失效/恢复不退化 |
| 4 | 默认组与 MCP 阈值按成本调整 | 中；依赖已有计量与前面完整性，对照后一项一项选择 | 成功率无已知退化；总 token、请求轮次、耗时和搜索重试有明确取舍；不以默认函数个数作为通过门禁 |
| 条件性 | Provider 原生发现、PTC、向量检索 | 当前不启动 | 有具体兼容 Provider、大目录失败或结果聚合成本证据及 Owner 实现授权后再评审 |

曝光、搜索和窗口决定写回 ADR-MCP-001 同一主题及 Decision history；工具输入 schema、绑定与外部提示边界归 ADR-TOOLS-001；Skill 内容仍归 ADR-SKILLS-001。当前先完成设计，不创建 accepted 新契约、重复 HXA 或一份脱离 status 的执行排期。

### 12.6 验证方案

主机测试围绕行为而非固定计数：名称精确查询、组合部分匹配、中英文别名、来源同名、空结果、工具增加/删除/换版、会话隔离、禁用/撤销、Plan/recovery-only、过期 binding、请求软预算、超过旧 64 阈值的完整绑定/manifest、Provider 已知数量边界、MCP 15/16/17，以及发现窗口压力。样本数量用于边界验证，不作为生产默认数或随着工具增加持续调整的门禁。现有测试按新接受契约补充，不能因结果不理想删除安全断言。

合成大目录分别使用多来源、长 schema、相似描述和恶意元数据，记录检索耗时、Top-k 命中和下一请求实际可达性；注册数量与模型提供数量分开，不能从合成结果推导手机资源容量。

真实任务对照采用相同模型、权限、输入、APK 基线及合成业务夹具，分别运行文件任务、异步 Job、Browser、Mobile Use 节点/视觉、文件与浏览器交叉、Skill 引导和 MCP 连接查询。每个切片仅改变一个策略，先每类两轮筛查，失败再扩大定向样本；这个起始样本不足以声明普遍收益。记录业务正确性、权限/UNKNOWN 结果、总输入输出、工具 schema 估计、模型轮次、搜索次数/空结果、重复加载、最终未装载及总耗时。无真实模型授权时只完成主机夹具，不把 mocked pass 当完成率。

明确验收优先级：执行身份和权限正确 → 工作流可完成 → 没有静默缺失 → 总成本和延迟取舍。需要设备或付费模型时按当轮明确授权执行；本设计没有启动设备、模型或其他正在进行的测试。

### 12.7 本轮证据入口

Helix 当前事实：

- [McpToolDiscovery](../../../app/src/main/kotlin/com/helix/app/mcp/McpToolDiscovery.kt)：来源准入、关键词排序、替换窗口、小 MCP 全量及搜索输出。
- [ChatRequestAssembler](../../../app/src/main/kotlin/com/helix/app/chat/ChatRequestAssembler.kt)、[ModelToolExposureOrder](../../../app/src/main/kotlin/com/helix/app/chat/ModelToolExposureOrder.kt)：最终过滤、重排、hard cap 与 bindingRef。
- [ToolRegistry](../../../tools/framework/src/main/kotlin/com/helix/tools/framework/ToolRegistry.kt)、[PluginCatalog](../../../app/src/main/kotlin/com/helix/app/plugin/PluginCatalog.kt)：原子贡献与会话来源。
- [PluginExecutionContext](../../../extensions/plugin/src/main/kotlin/com/helix/extensions/plugin/PluginExecutionContext.kt)：目录、短规则、显式 Skill 内容与 preferred。
- [MobileUsePlugin](../../../extensions/mobile-use/src/main/kotlin/com/helix/extensions/mobileuse/MobileUsePlugin.kt)、[BrowserPlugin](../../../extensions/browser/src/main/kotlin/com/helix/extensions/browser/BrowserPlugin.kt)：当前默认组。
- [ModelInputEstimate](../../../core/agent/src/main/kotlin/com/helix/core/agent/ModelInputEstimate.kt)、[RequestBudgetDiagnostics](../../../core/agent/src/main/kotlin/com/helix/core/agent/RequestBudgetDiagnostics.kt)：现有上下文工具成本估计。
- [HXA-252 发现提示对照](../../evidence/development/discovery-guidance-2026-10-10.md)、[HXA-278 Skill 装载对照](../../evidence/development/hxa278-progressive-skills-2026-10-12.md)：历史有限证据，不是本节候选策略的新结果。

外部当前核验：

- Codex [工具组装](https://github.com/openai/codex/blob/0c7362d121d1c7f69540262425fd4e2209fd8e8d/codex-rs/core/src/tools/spec_plan.rs)、[工具搜索](https://github.com/openai/codex/blob/0c7362d121d1c7f69540262425fd4e2209fd8e8d/codex-rs/core/src/tools/handlers/tool_search.rs)、[搜索入口与来源简介](https://github.com/openai/codex/blob/0c7362d121d1c7f69540262425fd4e2209fd8e8d/codex-rs/core/src/tools/handlers/tool_search_spec.rs)。
- Claude Code [MCP 工具搜索及配置](https://code.claude.com/docs/en/mcp#scale-with-mcp-tool-search)。
- DSH [ToolRuntime 与 Native/PTC](https://github.com/deepseek-ai/deepseek-harness/blob/d743267388641bc76f17c45ce8b4c231aed1d32c/packages/core/tools/src/index.ts)、[请求工具贡献组装](https://github.com/deepseek-ai/deepseek-harness/blob/d743267388641bc76f17c45ce8b4c231aed1d32c/packages/core/system-prompt/src/index.ts)、[MCP 工具 adapter](https://github.com/deepseek-ai/deepseek-harness/blob/d743267388641bc76f17c45ce8b4c231aed1d32c/packages/mcp/mcp-client/src/tools.ts)。

### 12.8 优化后的目标需求与验收条件

**目标：让模型能够发现和完整调用当前会话允许的工具，以真实上下文及资源成本管理请求，不受 Helix 人为固定工具数量限制。** 本节是 Owner 要求完善的需求，不是实现完成声明。保留独立工具 schema、准确执行身份与原权限链；不增加全量常驻、通用执行引擎或用户必须配置的新预算项。

| 需求 | 目标行为 | 验收条件 |
| --- | --- | --- |
| R1 取消全局固定数量限制 | 请求组装不再 take(64)；ModelRequest、ModelToolBindings、RequestContextManifest 不按 64 拒绝；不以 128/256 等新常量替代 | 在真实生产组装/编码/绑定链中，构造超过旧阈值的合法工具集合，名称、schema、binding、manifest 一致，最后一个工具可准确解析和调用；重复名称与无效契约仍拒绝 |
| R2 Provider 限制归适配层 | 仅应用当前 Provider 明确支持或已验证的数量、协议及请求字节限制，缺少证据时不推定统一数量；不从错误文本自动猜出限制并循环重试 | 用有/无明确限制的 Provider 夹具核验；小于/等于/大于已知边界行为明确；服务方拒绝如实报告，不变更授权、不重放已执行动作 |
| R3 上下文成本替代数量裁剪 | 基础组、发现工作集、来源目录、历史/Skill/图片共享已有上下文容量；成本基于最终 schema 投影，并区分估算与实测；不增加累计任务 token 阻断 | 同数量长短 schema、不同窗口及多模态夹具覆盖；压缩历史不能假装减少工具 schema；必需集合容纳不下时说明原因，可选工具可延迟，不剪参数契约 |
| R4 持久记录不丢绑定 | 请求实际工具集合与持久化清单完整一致。当前 manifest 的 256 KiB 单行存储限制仍是独立字节约束，不能靠删除工具引用、仅保存前 N 个或 isTruncated 标志绕过 | 超过 64 可持久化并恢复解析；字节边界两侧回归。若现有存储不能承载完整绑定，先明确失败并阻止该模型请求，不发送一个无法完整记账的请求；必要的存储扩展在本切片设计中单独核对，不盲目放大限制 |
| R5 能力可发现但不全量注入 | 本会话可用来源有简短说明，长尾工具通过单一 tools.search 发现，下一次模型请求提供真实 schema；安装与选择不自动变成所有工具常驻 | 不同来源、中文/英文、精确名称、同名与长描述回归；搜索结果与下一请求实际集合核对。未选择、已禁用或不准入的来源不能因目录或搜索变可调用 |
| R6 连续工作流保持可达 | 默认组和近期发现按完整操作关系选择，避免只留下动作而丢观察、只留下启动而丢查询/收取；窗口仅是曝光缓存，不能代替 Room 恢复事实 | 文件→网页→文件、start→await、节点/坐标观察→动作回归；修改替换窗口前有严格对照；失效、取消和 UNKNOWN 不自动重放 |
| R7 用户反馈与诊断分层 | 普通界面仅说明缺配置、需重新发现或当前服务无法容纳并给出下一步；内部保留选择/延迟原因、成本口径和准确请求身份 | 用户可完成修复入口操作；没有不必要的计数/技术诊断堆积；内部能核对“搜索到了但未发送”的原因，不把未装载称为权限拒绝 |

**加载规则。** 每次请求从当前准入的原子快照选择必要控制、完整常用组、近期发现和当前流程需要的查询/收取工具；剩余能力继续可搜索。最终集合满足 Provider 和上下文条件后冻结 binding 并持久记录，再发送模型请求。来源、模式或契约变化重新验证；执行依旧独立检查当前权限和原调用身份。

**数字的不同职责。** 64 是本轮明确取消的全局请求数量限制；搜索一次返回多少、缓存多少会话是局部检索/资源参数，不能自动视为同一种限制。现有 16 项曝光窗口与 MCP 16/17 阈值仍需按切片 3/4 评估，不能拿它们作为取消 64 后限制整个请求的替代品。后续工作集应优先受 schema 成本与完整工作流约束，必要的局部资源边界必须有真实理由和明确反馈。

**实现顺序。** 切片 0 先完成无固定截断的请求与记录链；切片 1/2 完成目录、检索和最终集合诊断；切片 3/4 再用有界真实任务选择近期保留、默认组和成本策略。每项完成应更新原 ADR、当前任务状态与实际验证记录；不预先承诺 token 或耗时改善，不要求一次实现所有候选策略。

### 12.9 本轮实施结算

Owner随后授权完整实施与模拟器/真机/现有Qwen测试，HXA-280已实现无全局数量截断、同源能力目录、来源/类别/参数字段及中文检索、按schema成本的近期工作集和MCP提前加载。Mobile Use仅将设备信息延迟，坐标操作发现仍补齐观察契约；不是把所有插件工具全量常驻。近期64KiB与MCP16KiB为宿主局部估算启发式，当前匹配不会按前者裁掉，实际上下文和256KiB manifest继续独立约束；不将这些数字换成新全局工具上限。

基线和首候选固定六轮业务均成功；请求数和输入成本在Workflow/Browser下降，Mobile混合，未证明普遍收益或消除未曝光工具误调用。真实Qwen在正常组装/持久化/Dispatcher链调用第70个无副作用夹具工具成功。组合复测还发现并修复显式目标被陈旧UI会话提前阻断的启动接缝，显式目标不存在仍拒绝且不回退。完整命令、失败尝试、最后制品身份和验证边界见[实施证据](../../evidence/development/tool-discovery-workset-2026-10-12.md)及[HXA-280](../../completion-records/HXA-280.md)。Provider原生发现/PTC、其他OEM和长期跨Provider比较仍是条件性后续，不从研究自动扩展范围。
