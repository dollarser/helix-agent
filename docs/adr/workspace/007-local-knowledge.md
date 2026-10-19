# ADR-WORKSPACE-007: 系统分享收集与本机知识库

Status: accepted
Date: 2026-10-19
HXA: HXA-308, HXA-311
Deciders: project owner（开始执行收集/知识库第一阶段）；实现 Agent（有界实现取舍）

## Context

旧系统分享入口仅将材料放入会话草稿，没有独立知识管理。Owner 要求从 X、微信及其他应用收集文字、链接、图片/文件，先保存原件与来源，再提取、检索、选择引用和可选分析。此领域不同于人格 Memory，也不以 HXA-286～294 的整体重构为前置。

## Decision

收集记录明确是 **原始来源资产**，并非已经确认的知识。`CollectedSource` 保存原文/原件、提取正文和来源附属笔记；`KnowledgeNote` 独立保存手写或综合知识；`KnowledgeNoteSource` 保存用户选择的多对多引用快照。来源库长期保留材料，提炼后不搬走或清空，不强制走“清空收集箱”流程。不将每次分享或自动摘要固化为一个知识，不按语义相似自动合并。

- Standard 与 Advanced 提供相同的独立收集页；系统分享使用明确的“收集到 Helix / Helix Advanced”目标标签，Advanced 应用名也区分渠道，避免同机安装时误投。Android SEND/SEND_MULTIPLE 指向收集 Activity，不自动创建 Turn。微信内部转发不等于系统分享；没有入口时提示复制文字/链接或保存文件后分享。
- 原件在应用私有 `knowledge/`，来源、笔记、引用、问答与 FTS 在独立 Room 数据库 `library.db`，保证笔记和引用的原子保存；中文子串使用 SQLite 补充。当前预览版不建立旧版兼容层；问答新增表通过 Room 加法 schema 变更保留当前已收集资料，不重置任何数据库。不引入向量服务、账号、同步或新依赖。
- 收集 Activity 在临时 URI 授权有效期间保持存活，应用作用域进行有界物化：最多八文件、单文件 16 MiB、文字 200000 字符。只接收 content URI，不让外部调用者借用本应用 FileProvider 路径。文件名仅展示，不参与存储路径。按文字和实际文件字节摘要去重；相同材料保留首次来源/时间。
- IMPORTING/QUEUED/EXTRACTING 是持久状态。每个完成文件登记后再继续；进程中断后 IMPORTING 明确标不完整并要求重分享，排队/提取项从本机原件重试。不宣称临时 URI 可永久恢复或断电事务。失败不得显示完成，已接收资料不丢弃。
- 本机 PDF/DOCX/HTML 提取复用现有有界文本提取器；图片及未知文件只保存原件，不冒充 OCR 或完整 PDF 理解。链接只通过现有公开网络 SSRF/TLS/重定向防护 transport 抓取，不携带浏览器 Cookie/登录态、不执行脚本、不绕过登录。保留分享 URL 与公开页面原始快照；通用可见文字提取不能证明正文完整，页面明确标注未核验，失败/登录/超限均标不完整。
- 原始资料、提取内容和来源附属摘要独立，来源附属摘要不是已确认知识。用户可直接写知识笔记，从单源提炼，或选择最多八份来源综合。笔记引用保存有界片段、来源身份/时间/完整性与内容摘要；来源重新提取不静默更新笔记或引用。
- 可选生成复用 `ProviderService` 的现有 API/设备内模型通道，不调用工具，也不使用具备独立执行能力的订阅 CLI。用户明确选模型，发送前展示目标、模型、来源数量和字符范围；确认绑定当次配置/目标及不可变内容快照，配置或笔记变化使请求失效。现有凭据形态拒绝规则继续执行，没有自动上传或将本地保存称为本地推理。
- 模型流必须正常完成，拒绝错误、拒答、工具调用、截断与伪造来源 ID。生成先存为未核对建议，不覆盖用户正文；用户明确载入编辑器、修改并核对保存后才标为知识。失败/取消保留用户草稿，进程重启将 RUNNING 标 INTERRUPTED，不自动重复模型调用。持久记录保留当次模型、目标、内容摘要和状态。
- 引用包含稳定来源 ID、来源、接收时间、完整性状态及有界片段。检索后可将所选资料带入聊天提问而不创建笔记；保存笔记是独立操作。外部内容是不可信资料，不能授权工具或改变权限；全库不注入上下文，不写入人格记忆。
- 独立问答入口先离线检索原始来源：最多 24 个查询词（英文词/中文双字片段），FTS OR 与中文子串补充，候选有界、按命中覆盖排序，最多展示 16 份。不以生成摘要当原始证据，不保证同义词或语义召回；无结果提示改关键词，不发模型。用户选择最多八份后，按命中位置冻结最多三个窗口及位置/完整性说明，核对实际片段和所选目标再发送。总正文预算 24000 字符，附少量片段边界标记；不上传全库或既有问答。
- `KnowledgeAnswer` 独立持久化问题、冻结证据、模型/目标/输入摘要和 RUNNING/READY/FAILED/INTERRUPTED。恢复只改中断状态，不自动重发；确认时重核来源版本与 Provider 配置。回答必须含有效所选来源引用，不能把应用追加的来源清单冒充模型引用。用户可展开冻结片段核对；引用 ID 检查不是事实正确性验证。
- 问答的“保存为可编辑笔记”在同一事务保存草稿与冻结引用并登记对应笔记 ID，重复保存返回同一笔记；不自动标为已核对，不删除来源或覆盖其他笔记。

## 官方项目参考与取舍

本轮只参考产品/设计和已有公开接口说明，没有复制参考项目代码。

| 项目 | 采用的设计启发 | 本阶段取舍 |
| --- | --- | --- |
| [Readwise Reader](https://readwise.io/read) | 统一收集入口、离线全文检索、阅读与后续 AI 操作 | 不实现同步账号、RSS、TTS 或社交平台完整线程专用解析 |
| [Obsidian Web Clipper](https://obsidian.md/help/web-clipper) / [Interpreter](https://obsidian.md/help/web-clipper/interpreter) | 本地材料与显式模型处理分开；所选模型可能远程，必须说明内容去向 | 复用既有聊天 Provider，不新建模型调用栈 |
| [Joplin Web Clipper](https://joplinapp.org/help/apps/clipper/) | 收集页面/截图成为独立笔记，收集和后续笔记管理分开 | Android 系统分享足以完成首阶段，不增加桌面 HTTP clipper 服务 |
| [Karakeep](https://github.com/karakeep-app/karakeep) | 文字、链接、图片统一收藏，全文检索与 AI 元数据处理分层 | 不引入其自托管服务、爬虫部署、自动云端标签或外部搜索服务 |
| [Omnivore](https://github.com/omnivore-app/omnivore) | 稍后阅读材料与阅读界面独立于聊天 | 仅作产品参考；不引入其后端、同步或 AGPL 代码 |
| [NotebookLM 来源选择与引用](https://support.google.com/gemininotebook/answer/16179559?hl=en) | 用户选择来源集合，再生成有引用的回答，保存笔记与问答分开 | 本机来源/知识笔记多对多，不引入其云端知识库 |
| [Reader Library](https://docs.readwise.io/reader/guides/workflows/library-configuration) / [Global Ghostreader](https://docs.readwise.io/reader/guides/ghostreader/global) | 来源长期保留；按用户问题检索/综合不等于每份材料自动产出知识 | 当前显式检索、选择引用或保存笔记，不自动全库合并 |
| [Obsidian Templates](https://obsidian.md/help/web-clipper/templates) | 新建或追加笔记可由用户流程决定 | 来源不是知识的一对一映射，不预建图谱 |
| [Android 接收分享](https://developer.android.com/develop/ui/compose/sharing/receive) / [文件信息](https://developer.android.com/training/secure-file-sharing/retrieve-info) | SEND/SEND_MULTIPLE、临时 URI 授权、ContentResolver 元数据 | 实际复制成功才视为收集了文件；来源缺失不猜测 |

## Alternatives considered

不沿用只填聊天草稿作为收藏：其会话生命周期和来源/原件管理不能满足知识库要求。不引入向量库、自托管爬虫或新 Provider 调用栈：现有 Room、文本提取和聊天足够覆盖此阶段，也减少权限和数据副本。单独新增知识数据库避免将新领域强耦合到历史会话 schema；后续仍由同一应用服务维护一致性。

## Consequences

原件先落地，网络/模型失败不丢掉资料；分析有明确的内容发送边界。通用网页提取不能保证文章完整，扫描 PDF、图片及任意二进制也不因文件已收藏而变成可读文本。模型建议与已核对笔记分离，保留可编辑保存和引用追溯；没有自动知识确认。

## Verification

[HXA-308](../../development/tasks/HXA-308.md)规定收集与笔记验收，[HXA-312](../../development/tasks/HXA-312.md)规定检索问答的主机、Owner 已授权的合成设备及真实模型测试。设备执行、构建来源和限制由证据记录，不从 accepted 状态推定通过。

## Reconsider when

提醒、TTS 按 Owner 后续安排推进，本阶段不实施。更精确正文提取、OCR、关联建议、分页/大库性能、标签与同步需后续具体范围；不提前创建知识图谱或向量/同步基础设施。

## References

官方项目与 Android 文档见上表。许可与运行边界继续遵循 [AGENTS.md](../../../AGENTS.md)、[有界文档读取](006-pdf-reading.md)、[模型数据预算](../agent/006-model-data-budget-boundaries.md)。

## Decision history

- 2026-10-19：Owner 授权开始知识库第一阶段；采用本机原件 + Room/FTS、明确云端分析确认及不完整内容边界，保留现有用户数据和版本。
- 2026-10-19：Owner 追问零散材料与跨来源知识关系，进一步确定独立笔记、多对多来源引用、单源提炼/多源综合/手写和明确确认。Owner 随后要求不做旧版迁移/兼容；采用统一资料库和当前最简模型，保留真实会话与用户文件。

- 2026-10-19：Owner 授权真实跨应用收尾验收；系统分享面板发现同名渠道/历史实验包目标歧义，收集目标及 Advanced 应用标识改为可辨识名称，不改变来源/知识分离或数据发送边界。
- 2026-10-19：Owner 授权第二阶段检索问答，参考 NotebookLM 的来源选择、引用核查及显式保存，以及 Obsidian Interpreter 的所选模型/发送内容边界。采用本地有界词法检索、独立问答记录和冻结片段，不引入向量/同步或具备工具执行能力的模型路径；实现范围见 HXA-312。
