# ADR-SKILLS-001: Skill 创作、安装与 MCP 配置闭环

Status: accepted
Date: 2026-09-16; revised 2026-10-16
HXA: HXA-148, HXA-149, HXA-150, HXA-272, HXA-273, HXA-278, HXA-282, HXA-298
Deciders: Project owner（2026-10-11 要求将插件优化任务同步为 ADR，采用单一目标实现、不兼容旧版）

## Context

Skill 内容、MCP 配置和安装动作的信任与所有权不同，统一产品入口不能形成新的执行授权来源。

HXA-272/273/278 接受统一内容贡献、调用资格和渐进装载的目标契约。原生 Skill 依赖已发布工具再拼接正文的路径不能支持零工具流程插件，也难以让目录、读取和会话选择使用同一身份。统一内容、调用资格和渐进装载已有有界实施与验收；具体范围见下方评估，模型成本为混合结果。

## Decision

实现三个默认可发现的 Helix 内置 Skill：`skill-creator`、`skill-installer`、`mcp-installer`。知识层提供任务流程，原生工具负责校验、预览和安装；内置身份不授予工具权限。

### 统一内容贡献与独立激活（HXA-272）

APK 捆绑、包内和独立安装的 Skill 均由现有 SkillRepository 的统一 list/read/resource 路径提供，引用不可变内容快照及准确的 source/name/snapshotHash。包/组件 owner 与内容身份分别保存；同名不同来源不能覆盖，同一内容的多归属按 [Plugin 归属契约](../connectors/003-ownership-and-installation.md)处理。包内 Skill 不能借独立全局开关绕过来源选择。

内容可用、工具发布和会话选择分别投影。零工具包可以被发现、选择和读取，不要求 native runtime 或占位工具；某个 MCP 组件不可用不屏蔽独立可用的 Skill。每次发现、读取和请求装配核对已提交 revision、当前选择及下节调用资格，不能只在管理 UI 隐藏。

Mobile Use 的指南仍随所属插件交付，内容和资源接入上述统一路径；删除 `PluginBundledSkill` 正文专用通道和按已曝光 descriptor 反查正文的路径。ChatRequestAssembler 消费统一贡献快照，Core 不依赖外部 manifest。短操作指导由同一个 ContextCompiler 表达；正文装载策略由 HXA-278 承接。内容保持 SKILL / EXTERNAL_CONTENT 来源，不获得 ModelClient、AgentLoop 或工具权限。

### 用户与模型调用资格（HXA-273）

对明确支持的当前格式，调用字段只接受布尔值，缺省 `user-invocable=true`、`disable-model-invocation=false`；错误类型给出字段诊断，不能按字符串真假猜测。

| user-invocable | disable-model-invocation | 用户本次调用 | 模型自主发现与激活 |
| --- | --- | --- | --- |
| true | false | 允许 | 允许 |
| true | true | 允许 | 禁止 |
| false | false | 隐藏入口并拒绝用户调用 | 允许 |
| false | true | 禁止，仅管理预览 | 禁止 |

管理预览独立于执行读取：可查看停用或不可调用内容，但不会注册工具、选择来源或产生激活。模型目录、正文及 resource 读取共用资格判断；禁止自主调用的 Skill 不因模型猜中名称而可读。

用户显式调用由宿主接受输入的真实用户操作产生，绑定准确 Skill 身份/hash、本次参数及原任务上下文；会话选择、安装或模型提供 `userInvoked=true` 均不能代替该事实。有效用户激活允许宿主加载该正文，并允许该任务在原范围内按需读取资源；它不改变全局模型自主调用资格。激活仅属于本次已接受输入及其连续执行，新的独立任务或 fork 不继承活跃激活，取消后不再使用。排队、恢复和版本变化时重验来源选择及内容身份，不能将旧激活指向同名新快照；普通 Turn 与 Goal 使用同一规则。

调用资格约束内容使用，不产生 capability、approval 或外部写入许可。`allowed-tools` 不转为批准；`context: fork` 等未支持语义由组件诊断明确报告，不能展平后宣称支持。生产子 Agent、任意命令解释器和后台自动回复不由本决定引入。

### 渐进装载、失效与压缩（HXA-278）

在现有 ContextCompiler 中区分发现元数据、必要短指导、已激活正文、按需资源及历史引用。正文通过统一读取或可信用户激活装载；Mobile Use/Browser 的短操作规则保留，长例子和参考资料按需读取。同一任务同一快照避免无意义重复，不另建可写上下文库或 Skill 状态库。 同一次请求中，完全相同的 Skill 身份与目录描述只发送一份，合并保留全部 owner 修订；不同描述、快照或显式激活参数不合并。工具来源的相同发现说明保留一次，各来源元数据、不可信标记及来源修订分别保留。

原生内容目录只携带可用的 source/name/hash/description；宿主短操作规则与可选 Skill 正文分离。内置参考资料使用与文件快照相同的路径/大小 framing 计算内容 hash，复用 `skills.read_resource` 的来源、调用资格和读取上限；更新参考资料也使旧快照失效。显式激活正文标记准确身份和用户参数，同一快照/参数只装载一次。缺失快照与停用分别报告，绝不退回同名最新正文。

管理 UI 如实区分上述层次：必要指导、Skill 正文和原生参考资料可只读查看，不把“选用插件”描述为全文自动加载。原生必要指导与工作流保存在所属插件资源中，查看不产生选择、调用、注册或授权；零工具指导插件明确其他能力依赖，不从指导文字启动或授权依赖。

`skills.list/read/read_resource` 使用宿主绑定的调用会话与 Turn；模型无需提供 `sessionId`，参数不能替换真实调用身份。工具描述明确省略该可选字段，既有非空约束与跨会话拒绝仍保留。Mobile Use 的短操作规则明确：需要新观察或屏幕外滚动本身不是阻塞；模型应在原授权内继续观察、操作与核验，只有实际不可用能力、授权或用户输入缺失才需要停下。执行器不代模型滚动或盲目重放未知副作用。

每次请求的插件就绪信息按组件 kind/state/requestState/saved/loaded 汇总真实数量，优先已选择来源并限制为8 KiB UTF-8；被省略的插件及其中已选择数量明确报告。完整组件路径、目标、账号引用与诊断仍在设置中保留，不随每个模型请求重复注入。该摘要不授予权限，工具发现和Dispatcher仍是执行事实的边界。

更新、停用、选择撤销或目录清空后，后续候选与自动注入按当前事实失效；已发送请求、历史消息和原审计身份保留，不能承诺模型忘记已经见过的内容。压缩与恢复保留必要指导及准确版本引用；快照缺失、版本变化或预算不足要报告诊断并按有效资格重新读取，不能静默替换为同名最新正文。出网及预算仍由原上下文契约执行。

直接替换生产旧全文注入与旧读取接口，不保留兼容分支。策略对照使用固定基线制品；完成率、无效调用、发现往返、输入 token 和耗时分别记录，不能仅由 token 下降推导任务收益。内容正确性与效果验收分开，任务范围不改变工具发现排名、窗口、模型编码或审批。

### Skill Creator（HXA-148）

模型通过已有 scoped write/edit 在当前 Workspace 生成草稿目录，包含 SKILL.md 和实际需要的 references/assets/scripts；支持基于已固定旧快照创建新版本，不修改已安装快照。按用户意图直接生成内容，随后通过真实校验和固定 hash 安装；普通文本创建不额外要求人工草稿确认。用户可直接编辑草稿或在聊天中要求修改，失败显示可定位到文件/字段的错误。不得用尚未运行的脚本测试作为成功证据。

增加 `skills.preview`：只读取当前会话可访问的 Workspace 目录或 ZIP，复用 SkillImportService 校验，返回源 scope/相对路径、确定性内容 hash、文件清单/大小、依赖及诊断，不安装或启用。预览中维护的内部缓存不成为模型可指定的任意本机路径，也不构成授权。模型生成复用当前 Provider 与会话文件工具链；既有管理编辑器为可选入口，不是创建或使用的前置流程。

### Skill Installer（HXA-149）

补齐架构已规划的 `skills.install`，LOCAL_MUTATION，经统一 Dispatcher/Policy 判定；需要确认时精确绑定 source scope、相对路径和预期内容 hash。执行时重新读取及校验，内容变化拒绝安装并要求刷新预览；不能把已批准旧内容替换成新内容。安装复用固定快照和 USER_IMPORTED 来源，默认禁用；已有相同快照保留用户既有状态。返回实际 SkillKey/hash 与安装结果，后续启用复用既有 skills.enable 或 UI，不把安装当作能力授权。

支持现有目录/ZIP 导入、Agent 在已授权 Workspace 创建内容，以及按用户意图从 GitHub 等公开地址下载后安装。下载复用现有 browser.download，保留实际 URL、文件 SHA 与安装快照 hash；优先固定 commit/release，不能以仓库名或声明来源代替实际内容身份。单 Skill ZIP 可带仓库修订外层目录，按有效 manifest 名归一化；多 Skill 仓库先用现有文件解压工具选定目标目录，不猜测批量安装。http.fetch 的文本响应不是 ZIP。此流程不增加 Git credential、自动市场更新或安装脚本执行。

### MCP Installer（HXA-150）

增加 `connectors.preview` 和 `connectors.install`，分别用于本地 JSON/ZIP 的兼容预览及绑定 hash 的本地安装，沿用 ConnectorPackageReader/ConnectorService。补齐“粘贴 MCP JSON / 从文件导入”入口和安装结果卡，区分已安装、待配置认证、已连接、已选择工具。模型可生成不含凭据的 MCP JSON 或组合包草稿；不静默将 MCP 编译为 bash。

首版复用已实现的 HTTPS MCP 路径。安装只创建禁用记录和快照，不进行握手或自动连接。需要认证时打开现有原生凭据表单，Secret 不进入模型参数、草稿或结果；连接测试、工具选择与启用沿用既有用户流程。stdio/联网 CLI/OAuth/市场仍分别受 HXA-126/128/130 约束，不借安装器改变执行域。

### 共同执行与恢复契约

- Scope/路径解析复用 Workspace 服务，拒绝越界、链接逃逸和导入期间内容替换；校验和执行使用同一份有界快照。所有新工具必须注册真实 descriptor/schema/风险/效果，不由 Skill 自声明放宽。
- Preview 的 READ_ONLY 与 install 的 LOCAL_MUTATION 均走正常 Dispatcher；Plan 只能预览。安装按现有会话授权和具体效果判定；无需另加人工向导。取消在产生持久副作用前检查。
- 写入后取消或中断必须查询已确认安装结果；使用内容 hash/现有快照机制避免重复安装，不盲目重放。安装失败可保留未启用快照，按 [ADR-CONNECTORS-001](../connectors/001-portable-bundles.md) 明确提示，不宣称跨文件与数据库的原子事务。
- 复用批准、审计、快照与安装后默认禁用语义；新增持久结构、依赖或执行能力须在对应任务明确设计和验收，不由安装动作隐式引入。
- UI 用用户语言展示来源、内容变更、依赖和下一步，保留中英文、小屏大字体与无障碍。不能只增加三个提示词文件就标记功能完成。

## Alternatives considered

- 用占位工具激活纯 Skill，或保留原生正文专用路径：继续把内容与工具发布耦合，产生两套读取和失效规则，不采用。
- 仅在 UI 隐藏调用入口，或信任模型传入调用来源：实际 read/resource 可绕过资格，不能表达一次用户调用的范围，不采用。
- 生产保留新旧装载开关：增加行为分歧和失效组合；使用固定基线制品做对照，不保留兼容实现。
- 仅加入三个内置提示词：容易交付，但模型无法验证和完成安装，用户仍需手动搬运；不能满足完整创建与安装体验。
- 原样执行 Codex/QwenWork 安装脚本：依赖桌面文件布局、联网 CLI、宿主认证或代理，无法沿用 Android 的隔离和授权契约。
- 仅提供原生表单：适合手工安装，但不能覆盖聊天中生成、迭代、校验和安装的连续流程；表单作为同一原生服务的另一个入口保留。

## Consequences

内容目录和执行读取具有统一身份，纯流程插件无需占位工具。实现需维护准确调用来源、版本与失效检查，首次按需读取可能增加往返；渐进装载的收益由任务对照确认。

同一主题使用一份有效契约，避免并行实现各自解释权限和生命周期。代价是实现、UI、数据与恢复需要一起验证；accepted 表示决定，不代表相关任务全部完成。

## Verification

HXA-272已实现统一内容读取和零工具激活，有界验证见[272证据](../../evidence/development/hxa272-skill-content-2026-10-11.md)。273调用资格、可信输入与持久撤销已实现，主机、API36及Qwen边界见[273证据](../../evidence/development/hxa273-skill-invocation-2026-10-11.md)。278渐进装载、准确参考资料身份和请求事实大小限制已完成主机/API36及固定Qwen有界验收；[278证据](../../evidence/development/hxa278-progressive-skills-2026-10-12.md)记录混合成本结果，不宣称普遍提速或成功率提升。纯 Skill 样例走生产读取器；真实模型效果、设备和主机正确性分别记账，有限通过不代表全部OEM完成。

本次为现行决策整理，不新增功能通过结论。实现范围与实际命令结果以[实施状态](../../development/status.md)、对应 HXA 及完成记录为准；修改本契约后须覆盖成功、失败、取消、边界和恢复，不能用文档门禁代替设备/功能验收。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## References

- [HXA-272：统一内容贡献](../../completion-records/HXA-272.md)
- [HXA-273：调用资格](../../completion-records/HXA-273.md)
- [HXA-278：渐进装载](../../completion-records/HXA-278.md)
- [插件研究与任务映射](../../research/topics/helix-plugin-architecture-optimization-2026-10-11.md#7-任务承接与验收)
- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)

## Decision history — 2026-10-11：统一内容、调用资格与装载

所有者要求将 HXA-272～279 的规划补入现有 ADR，并明确不兼容旧版。本主题接受零工具独立激活、统一快照读取、可信用户激活和渐进装载；替换按工具曝光拼接原生正文的机制。沿用已有安装归属、ContextCompiler 和工具治理，避免第二套内容或执行系统；任务交付与策略收益仍需各自证据。

## Decision history — 2026-10-12：渐进装载和请求事实大小

Owner授权继续278并排查requestReadiness上下文风险。采用[Claude Code Skills](https://code.claude.com/docs/en/skills)的按需正文和支持文件思路；Helix保留每次UI操作必需的短规则、准确快照身份及既有权限路径，不采用参考项目的命令或子Agent执行扩展。原生参考资料复用现有快照 framing、资格和读取边界，不建立第二套内容存储。诊断正文留在设置，模型请求仅带有界的真实汇总及省略事实。是否带来策略收益由固定三类模型任务独立记录。

## Decision history — 2026-10-13：明确调用身份和正常观察流程

HXA-282 的真实 Qwen 长测出现空 `sessionId` 被 schema 拒绝，以及模型收到 offscreen 结果后提前结束。沿用当前宿主调用身份和渐进装载契约，仅澄清工具参数与每次操作所需的短规则；不删除约束、注入执行权限、扩大常驻 Skill 正文或硬编码模型动作。[Claude Code 官方 Skills 文档](https://code.claude.com/docs/en/skills)仍作为按需流程指导的参考，不推导其工具参数与 Helix 相同。修正效果及失败保留在[282证据](../../evidence/development/external-mcp-market-and-model-2026-10-13.md)，有界回归不覆盖历史协议根因或完整长稳验收。

Owner随后要求一并优化系统提示、内置工具描述与Skill，再完成新版两小时验收。采用[Codex工具发现模板](https://github.com/openai/codex/blob/main/codex-rs/core/templates/search_tool/tool_description.md)的下一次模型请求曝光契约，以及[Claude Code最佳实践](https://code.claude.com/docs/en/best-practices)的短通用指导、按需流程和可观察完成标准；保持Helix的当前schema、宿主绑定身份和权限边界。offscreen匹配结果、find/scroll/snapshot描述与Android参考Skill统一明确新观察、scrollable容器和token失效；浏览器短Skill说明陈旧/歧义目标恢复，安装Skill使用当前扩展配置入口。只改变指导与诊断提示，不由宿主选择动作或用户授权答案；验证结果见同一282证据。

## Decision history — 2026-10-16：提示分层可审查

Owner 要求审查 Mobile Use 等插件提示的提供方式和内容。[HXA-298](../../completion-records/HXA-298.md) 保留现有渐进装载，管理页增加必要指导和原生参考资料预览，并纠正全文自动加载的误述；Documents 必要指导归入自身资源，Chat Reply 明确独立能力依赖。参考 [Codex Skills](https://developers.openai.com/codex/skills) 和 [Claude Skills](https://code.claude.com/docs/en/skills)，不将这些指导变成审批或新执行能力；成本与模型遵循率需要独立真实模型对照，不能由 UI/主机验收推导。

## Decision history — 2026-10-27：内置文件选择导入

Owner 授权复用现有文件管理器选择插件 ZIP/JSON 与 Skill ZIP，并保留系统 SAF 文件选择入口。只读选择模式复用既有来源、目录列举与 FileRow，不提供新增授权、Root 或修改操作；仅显示已有可访问位置，不能绕过 Android 沙箱。选择后经现有后端再次验证权限、包含性、大小、取消及读取稳定性，形成有界快照，插件按真实字节而非 MIME/后缀进入同一解析器和原有预览确认/安装流程。同名不自动覆盖，更新仍绑定已审查内容身份；取消选择不开始导入。临时解析输入无论成功失败均回收，导入和读取不连接账号、不执行 Skill、不向模型授予文件权限。

PLC110 先前的测试 ZIP 存在，但系统选择器在加载阶段失败，不能归因于 Helix ZIP 解析或文件不存在。内置选择和系统选择的主机/设备验收须分别说明，旧设备结果不证明新入口可用。


## Decision history — 2026-10-30：主循环渐进上下文去重

Owner 根据单主循环与渐进披露调研要求减少可证实的重复开销。仅去重同一次请求中等价的 Skill 目录投影和工具来源发现说明，沿用 PromptRegistry/ContextCompiler 的保留、优先级、身份与读取引用；带发现说明的来源或获取遗漏摘要优先保留。每次装配仍从当前贡献获取 owner/内容身份，不缓存工具结果或跨修订复用正文；专家、显式激活正文/参数、必要插件指导及权限/预算/恢复检查保留。合成 fake-provider 主循环对照只证明请求字节与事实传递，不代表真实模型完成率或耗时收益。不引入生产子 Agent 或新的编排框架。

## Decision history — 2026-11-01：脚本清单与现有运行器入口

Owner 授权补齐 Skill 脚本使用。`skills.scripts` 列举启用、可调用且完整 tree hash 未变化的快照脚本；`skills.read_resource` 可将 `scripts/` 作为有界数据读取。读取不执行，不把来源未知文件自动视为可信。Developer 的 `skills.run` 复用现有 Linux 执行器、会话授权、真实目录、取消、输出和产物；只支持明确识别的 `.py/.js/.mjs/.sh`，参数数组不做 shell 拼接。固定快照再次核验并由 Runtime 复制后核对同一 hash；资源位于 `/tmp/skill`，工作区不因此复制，源快照无写回。Standard 仍可发现/读脚本，不能宣称未装载的 Linux 运行器可执行。

可选 `scripts/manifest.json` 是声明数据，格式为 `{"scripts":{"scripts/example.py":{"commands":["git"],"python":["json"],"node":["package-name"]}}}`。清单显示 `not_probed`；运行前以实际 PATH、Python 模块与 Node resolve 探测，缺失输出 `missing` 并失败，不自动安装；该字段不是完整依赖求解、权限声明或执行许可。用户 Skill 启停与显式激活契约保留，模型仍须通过正常工具授权。工具桥需独立用户开关及调用参数，不因启用 Skill 而打开。

## Decision history — 2026-11-02：Agent 创建与公开来源安装

Owner 将本轮任务 Skill 草稿按钮方案替换为 Agent 直接创建、已有 Skill 导入和公开来源下载安装。删除本轮新增的任务专用编辑确认链路；复用 scoped 文件工具、preview/install、统一 Repository 和 enable/read/run，不新增注册表、执行器或权限策略。preview/install 的相对源路径在原请求中固定，源读取保留 Workspace/External 效果分类；当前工作目录不等于全部授权边界。安装校验后立即注册准确快照，按用户请求通过既有启用工具进入发现与调用；安装本身不运行脚本、不授予能力。普通文本创建不新增确认，未知软件的安装或执行仍由现有授权与具体风险处理。路径包含性、符号链接、ZIP 多根/越界/大小限制与 hash-pinned 内容检查继续生效。合成入口验收不等同任意 GitHub 仓库兼容或真实模型效果。


## Decision history — 2026-11-05：调用与持续可用性分层

Owner 要求参考主流竞品区分调用一次与持续生效，并保留加号内专家、插件入口。[Codex Skills](https://developers.openai.com/codex/skills)区分显式/隐式调用与启用配置；[Claude Code Skills](https://code.claude.com/docs/en/skills)区分用户调用、模型调用资格及按需装载；[WorkBuddy 技能](https://www.workbuddy.ai/docs/zh/workbuddy/From-Beginner-to-Expert-Guide/Function-Description/Skills)区分安装、启用和对话调用，其[专家](https://www.workbuddy.ai/docs/zh/workbuddy/From-Beginner-to-Expert-Guide/Function-Description/Expert-Center)是角色入口，[连接器](https://www.workbuddy.ai/docs/zh/workbuddy/From-Beginner-to-Expert-Guide/Function-Description/Connector)有连接/禁用管理。官方资料不能证明 WorkBuddy 为每种能力都提供“仅下一条/整个会话”双模式，因此不作该推断。

Helix 沿用现有执行契约：加号“使用技能”准备当前消息的显式激活，提交前可以取消，不修改会话或全局开关；会话设置“会话技能开关”控制后续请求的可用资格，不代表每条消息执行，也不是清除已进入历史的内容。加号内专家、插件归为“本会话能力”；专家选择影响后续输入，插件选择提供后续按需可用的能力，均不立即执行或扩大授权。专家是现有会话角色快照，不冒充一次性子 Agent；不新增与现有能力不符的“一次调用插件/专家”按钮。全局安装、启用、配置仍归共享能力管理页，权限与返回后资格检查沿用现有路径。


## Decision history — 2026-11-05：插件配置与详情分工

Owner 要求合并配置页“查看详情”和独立详情标签，并筛选展示信息。配置保留实际设置、持续异常及原修复／配置动作，健康组件不再重复显示计数与诊断入口。卡片统一由带展开状态的标题进入管理内容，删除同功能的独立管理按钮和已安装页的自明介绍；状态使用简短标签，权限后果仍在实际授权与配置操作中说明。详情直接显示版本、可读组件名称与当前状态，MCP 连接目标用于区分服务；保存／加载事实、安装指纹及内部组件标识统一在按需诊断中，不显示内部账号引用，不重复来源枚举、空支持范围或正常组件文件清单。兼容性异常及依赖要求仍可检查，读取失败明确提供只读重试。

会话选择面板保留紧凑组件状态以帮助当前选择，但不再展开内部技术字段；模型上下文、选择、执行资格、修复、授权与连接身份不变。打开详情或重试只读取事实，不连接、修复、选择或授权；组件展示名称仅作 UI 标签，不替换原 owner、快照或请求身份。


## Decision history — 2026-11-05：独立技能展示统一

插件管理页中的独立技能与原生插件共享卡片、标题展开和使用操作层级，但内容型技能不添加空配置标签。宿主内置技能以本地化名称与简短用途展示，稳定英文标识留在详情；导入技能保持其原名称与描述，不能仅凭同名取得内置展示身份。内置技能归入默认收起的分组，搜索同时匹配本地化文案及原始标识／描述并展开匹配分组。清除搜索恢复默认分组。展示不修改 SkillKey、快照、模型目录、安装或会话启用事实，包内技能仍由所属插件管理。

独立技能的会话使用进入现有技能选择器，不进入插件选择器；打开或关闭选择器不自动启用技能。会话选择器复用相同的宿主名称与用途，标识与启用语义保持原约定。

同一管理页的插件与独立技能共享搜索空结果：任一来源命中时不显示另一来源的“没有匹配”提示；两类来源都成功载入且都未命中才显示统一空结果。载入错误保留原错误，不冒充没有结果。


## Decision history — 2026-11-05：技能检查与精确使用闭环

Owner 要求优化依赖与配置引导、兼容性和技能使用衔接。独立技能卡片以完整 source/name/snapshotHash 直接进入对应使用弹窗；本次调用只将已验证身份与参数加入当前会话的持久草稿，不发送消息，保留输入、附件引用和其他上下文，并对重复身份去重、数量上限、当前会话及来源失效重新检查。会话启用是另一项明确操作，只修改当前会话，关闭、查看、取消和本次调用不会自动改写启用或全局默认。原停用撤销与调用资格保持；已停用内容需用户明确开启本会话使用后调用，不通过一次调用绕过来源选择或撤销。

管理详情显示 compatibility、allowed-tools 的声明来源，不把声明当已验证环境或批准；未实现的扩展字段列为仅保留，context/agent/hooks 继续按现有规则拒绝执行。其他客户端占位符和执行前命令语法仅报告，不执行。脚本库存复用不可变快照及有界检查，管理预览可检查停用内容，不产生模型激活或执行；专用脚本入口是否发布与实际脚本依赖是否通过探测分开显示，既有 skills.run 的版本、执行域、依赖探测和权限契约不变。

宿主内置 web-research 的 browser 或 http.fetch 替代关系来自宿主已知流程，只有宿主发布的对应工具或当前会话选中且就绪的浏览器能形成“已提供”的展示事实；不从导入技能同名或 allowed-tools 推导权限。缺少能力时进入现有插件选择／配置路径，明确应用后重新读取事实，不自动选择插件、连接或授予权限。其他技能的自由文本环境／工具声明仍按未验证声明展示，不声称已完成任意依赖检测。所有执行继续通过既有工具发现、Policy、授权、限额及审计。

异步检查、参数和提交回调按原会话及完整技能身份保留；身份替换时销毁旧界面作用域，取消不等于回滚已持久化的用户操作。检查失败、未支持字段与应用失败保留可操作反馈；健康状态的说明仅在详情／使用弹窗，不在会话正文新增常驻行。
