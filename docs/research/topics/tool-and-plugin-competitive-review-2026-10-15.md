# 内置工具与插件：桌面及移动端 Agent 对照与优化建议

2026-10-16 当前复核见 [§10](#10-2026-10-16-预览后重构与能力复核)。前文保留研究脉络，实施状态由最新 HXA/证据覆盖；这份研究不另建执行顺序，目标方案仍归 Harness §23。

> 调研日期：2026-10-15。Helix 基线为 `main` / `1e918cc7` 及本轮读取的工作区源码。本文整理本轮竞品分析，区分源码事实、产品文档声明和建议；未运行竞品，也未新增设备或真实模型测试。外部默认分支和在线文档会变化，本文不构成跨版本能力保证。
>
> **性质：日期化研究输入。** 2026-10-15 Owner 要求按本次评审优化文档和任务，已采用部分登记为 HXA-286～294，均未实施；其余仍为候选。当前顺序只看 [status](../../development/status.md)，任务范围看 HXA，目标契约看 [ADR](../../adr/README.md)；本文不另建开发计划。
>
> 2026-10-15 Owner 补充：工具及插件设计应扩大主流竞品参考，优先统一模型侧语义、常用命名和描述格式；本次在原研究基础上增补 §2／§4，未将源码事实整体重新定基线。另有文档整理进程，本次按要求不运行文档门禁，仅修改本专题。

## 1. 综合判断

Helix 已覆盖文件、网页交互、手机操作、文档读取、代码执行、计划、用户提问、Memory、Skill 和远程 MCP。主要差距是**检索入口不完整、部分工具的前置依赖和参数不够顺手、可导入组件与可运行组件之间仍有落差**。

建议先补齐高频任务链，再按真实需求扩充能力：

1. 迁移并保留已修正的 Mobile Use 前置工具配套，继续统一工具描述与真实回执语义。
2. 增加文件路径匹配、文件内容搜索，以及真正的网页搜索和正文读取。
3. 改善工具来源目录、发现排序与插件兼容状态，补 MCP Resources 消费路径。
4. 根据任务补浏览器表单、文档生成编辑、媒体处理和通用 Job 操作。
5. 子 Agent、LSP、hooks、模型可操作的持久终端，分别在明确场景下设计。

工具数量不是完整度指标。**设计优先参考主流桌面 Agent 已形成的工具语义、常用名称、参数及描述格式**，将模型既有工具使用习惯作为兼容性输入；不只在完成设计后附上竞品清单。已有 `read/write/edit` 保留，新增通用搜索优先考虑 `glob/grep`，网页研究优先考虑 `web_search/web_fetch`，具体映射见 §4。Android 的路径、权限、窗口、坐标和执行回执仍须准确表达。

这是一项设计方向，不是“改成桌面名字就必然提高成功率”的实测结论。公开资料不能证明每个模型都在同一套桌面工具上强化训练；工具名称相近、参数和效果不同，反而容易误用。当前开发阶段可以直接收敛旧接口，但实施时须同步调用方、Schema、提示、测试和文档，保留执行身份、权限与恢复语义。

## 2. 竞品提供什么参考

| 对象 | 已核实的公开能力或机制 | 对 Helix 的参考价值与边界 |
| --- | --- | --- |
| Codex 公开核心 | 按模型、配置和 feature 组装工具；包括 `exec_command/write_stdin`、`apply_patch`、工具搜索、MCP Resources 等。搜索有按需曝光路径 | 参考发现、执行与结果读取分离，以及来源描述。公开核心不能证明桌面版全部私有工具或每个用户的常驻列表；见 [工具装配](https://github.com/openai/codex/blob/main/codex-rs/core/src/tools/spec_plan.rs)和[搜索契约](https://github.com/openai/codex/blob/main/codex-rs/core/src/tools/handlers/tool_search_spec.rs) |
| Claude Code | 文件读写编辑、Shell、文件检索、网页访问、Skill、Agent、提问等；插件可包含 Skills、agents、hooks、MCP | 借鉴常见任务的短工具名和内容包复用；工具是否可用受环境与配置影响，不假定所有入口每次同时曝光；见[工具参考](https://code.claude.com/docs/en/tools-reference)、[插件文档](https://code.claude.com/docs/en/discover-plugins) |
| DeepSeek Harness（DSH） | 文件工具含 `read/read_image/write/edit/glob/grep`，网页包含搜索与读取，Shell 包支持平台 Shell；Jobs 与 subagent 有独立组件 | 最适合参考小工具的契约与组合方式，不照搬桌面路径、进程权限或安装假设；见 [fs](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/fs/README.md)、[web](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/web/README.md)、[jobs](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/jobs/tool-jobs/README.md)、[shell](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/shell/README.md)、[subagent](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/subagent/README.md) |
| OpenCode | 文件、Shell、检索、网页、Skill、提问等工具；LSP 有独立开关和适用条件 | 参考清晰的工具职责与配置边界。可选工具不等于默认全启用；见[工具文档](https://opencode.ai/docs/tools/) |
| Gemini CLI | 当前参考页列出 `read_file/write_file/replace/glob/grep_search/run_shell_command/web_fetch/ask_user`；部分旧资料仍用 `search_file_content` | 参考明确的文件、目录和参数命名；核对实际版本，不能从旧文档拼出一个并不存在的默认工具集；见[工具与参数参考](https://geminicli.com/docs/reference/tools/) |
| ClineCore／SDK | 当前工具包括 `read_files/search_codebase/run_commands/apply_patch/editor`；官方文档区分旧 XML 工具名与当前 Runtime，并说明默认按模型选择编辑入口 | 支持“模型熟悉的工具格式值得参考”，也说明桌面工具并无唯一标准。该契约不外推为所有 IDE 版本行为；见[工具参考](https://docs.cline.bot/tools-reference/all-cline-tools) |
| Playwright MCP | 将浏览器能力暴露为 `browser_snapshot/browser_type/browser_press_key` 等类型化工具；输入与提交、截图比例都有参数契约 | 作为桌面浏览器工具的接口参考，不作为独立 Agent 竞品计数；Helix 复用自身 WebView／执行器，不能宣称支持 Playwright 全部能力；见[官方工具说明](https://github.com/microsoft/playwright-mcp) |
| Operit | 官方仓库描述文件、网络、媒体、系统工具，Accessibility／Shizuku／Root、PhoneAgent、工具包／MCP／Skill 市场、工作流及 Linux／SSH 能力 | 移动端完整产品覆盖面的重要对照。这里只核验产品声明，未测其可靠性、安全隔离或各渠道可用性；见[官方仓库](https://github.com/AAswordman/Operit)、[脚本接口指南](https://github.com/AAswordman/Operit/blob/main/docs/SCRIPT_DEV_GUIDE.md) |
| DSHA | Android 上承载 DSH／Linux 环境并结合 ADB 的方案 | 说明移动产品可以复用桌面运行环境，但不能把这些能力都称为 Android 原生工具；见[项目仓库](https://github.com/DSH-APP/DSHA) |
| Open-AutoGLM | 手机动作层包括启动、点击、输入、滑动、返回、等待和接管等 | 参考动作词汇、参数便利性；其动作处理层不是完整插件平台，也不能直接作为业务成功判据；见[动作实现](https://github.com/zai-org/Open-AutoGLM/blob/main/phone_agent/actions/handler.py) |
| AndroidWorld | 研究环境中的类型化手机动作，支持索引／坐标等表达 | 参考可测试的动作抽象与任务结果核验；它是评测环境，不是应用市场或用户产品；见[动作定义](https://github.com/google-research/android_world/blob/main/android_world/env/json_action.py) |

桌面 Agent 的文件／进程环境更开放，手机 Agent 更依赖系统授权、界面观察和单设备控制。Helix 应分别借鉴两类优势，保留 Standard 的完整基础能力，渠道差异只来自实际平台约束和已接受决定。

## 3. Helix 当前覆盖与具体差距

| 能力 | 当前已核实事实 | 建议补充或整理 |
| --- | --- | --- |
| 文件 | 有读写、精确编辑、列举、属性、复制移动、删除、目录、归档及图片查看；`files.search` 仅按文件名子串匹配 | 补路径模式匹配与正文搜索；澄清名称搜索和内容搜索的区别 |
| 网页研究 | `http.fetch` 提供有界 GET／HEAD；Browser 能打开和操作网页 | 补独立搜索服务入口与正文提取，支持来源引用；HTTP 请求工具不能充当搜索引擎 |
| 浏览器 | 注册导航、标签页、snapshot／find、点击、输入、滚动、等待、截图、下载等工具 | 后续补键盘提交、选择控件与授权文件上传；明确输入是替换、追加还是提交 |
| Mobile Use | 已有语义节点与设备／像素动作，覆盖 snapshot、find、点击、输入、滚动、启动、截图及手势；默认及单独截图发现配套已在后续收尾修复 | 286 迁移并保留配套回归；292 再验证参数、单位与观察来源改进，不重复规划已修缺口 |
| Documents | `documents.inspect/read/view` 读取 PDF／DOCX，使用快照、来源身份及页／块引用 | 先补长文定位与表格阅读体验，再按任务增加生成／编辑、XLSX、OCR；目前不能声称支持文档编辑 |
| 代码与长任务 | Developer 有 `code.linux.run` 及后台 Job；QuickJS 是另一条隔离执行路径 | 已有终端 UI／PTY 不等于模型拥有通用持久交互终端工具；先统一可发现的 Job 控制和结果收取 |
| 计划与协作 | 已有 plan、todo、Goal、`ask_user`、Memory | 评估 Goal 状态报告重叠，避免多个入口含义近似；不以此重建执行引擎 |
| Skills | 有安装、激活、正文和资源读取，已有多个内置工作流；`helix-docs` 已有随包帮助预览 | 工作流优先用 Skill 复用现有工具；有指导文本不等于具备其所需搜索或执行能力 |
| 插件与 MCP | 有可移植包导入、市场、配置诊断、会话选择和远程 MCP；SDK 会读取部分 Resources／Prompts 元数据 | 导入成功不能表示 hooks／LSP／agents／stdio 全部可运行；优先补资源内容读取、模板与分页，Prompts 按场景推进 |
| Chat Reply | 当前插件以 Skill／原型为主 | 不将其描述为完整消息监听与自动发送产品；新增消息源和发送行为须单独定义权限及验收 |

源码依据：

- 文件：[FilesSearchTool](../../../tools/files/src/main/kotlin/com/helix/tools/files/FilesSearchTool.kt)、[EditTool](../../../tools/files/src/main/kotlin/com/helix/tools/files/EditTool.kt)。已有精确编辑和内容 hash 校验，不能因竞品有 `apply_patch` 就断言 Helix 缺少安全编辑。
- 网络和浏览器：[HttpFetchTool](../../../tools/android/src/main/kotlin/com/helix/tools/android/HttpFetchTool.kt)、[BrowserTools](../../../tools/browser/src/main/kotlin/com/helix/tools/browser/BrowserTools.kt)、[BrowserInteractionTools](../../../tools/browser/src/main/kotlin/com/helix/tools/browser/BrowserInteractionTools.kt)。
- 手机：[AutomationTools](../../../extensions/mobile-use/src/main/kotlin/com/helix/extensions/mobileuse/tools/AutomationTools.kt)、[AutomationDeviceTools](../../../extensions/mobile-use/src/main/kotlin/com/helix/extensions/mobileuse/tools/AutomationDeviceTools.kt)。
- 文档与帮助：[DocumentTools](../../../extensions/documents/src/main/kotlin/com/helix/extensions/documents/DocumentTools.kt)、[文档插件方案](../../product/document-processing-plugin.md)、[helix-docs 实现边界](helix-docs-design-2026-10-14.md)。
- 执行与 Goal：[LinuxRunTool](../../../app/src/developer/kotlin/com/helix/app/proot/LinuxRunTool.kt)、[DetachedJobTools](../../../app/src/developer/kotlin/com/helix/app/proot/DetachedJobTools.kt)、[GoalLifecycleTools](../../../app/src/main/kotlin/com/helix/app/goal/GoalLifecycleTools.kt)、[GoalReportTool](../../../app/src/main/kotlin/com/helix/app/goal/GoalReportTool.kt)。
- 扩展：[SdkMcpClientSession](../../../extensions/mcp/src/main/kotlin/com/helix/extensions/mcp/SdkMcpClientSession.kt)、[PluginDiagnostic](../../../extensions/plugin/src/main/kotlin/com/helix/extensions/plugin/PluginDiagnostic.kt)、[PortablePluginComponents](../../../extensions/plugin/src/main/kotlin/com/helix/extensions/plugin/PortablePluginComponents.kt)、[Chat Reply 边界](../../../extensions/chat-reply/README.md)。

## 4. 工具名与描述：优先收敛语义

### 4.1 参考桌面惯例，但区分工具、命令和实现

`grep` 既是命令行程序名，也被多个 Agent 用作独立工具名；`glob` 通常指文件路径模式匹配，并不是 Bash 内置命令。**模型工具是带名称、参数 Schema 和结果契约的调用入口，不由它是否依赖 Shell 决定。** DSH 的工具目录将 `glob/grep` 作为工具暴露；Claude Code 也区分查找文件路径的 Glob 和搜索内容的 Grep，是否曝光受版本／配置影响。见 [DSH 工具目录](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/tool-catalog.md)、[Claude Code 工具参考](https://code.claude.com/docs/en/tools-reference)。

例如候选调用 `grep({"pattern":"付款期限","path":"contracts/"})` 是向文件搜索执行器提交结构化参数。底层可以调用 `rg`，也可以由 Kotlin／文件 API 实现；它不等于让模型生成 `bash("grep ...")`。Helix 的 Standard 搜索应通过 Workspace 与授权目录实现，不把 Bash、Root 或 PRoot 作为前提，也不把不可信 pattern 拼进 Shell 命令。

设计顺序是：**先确认操作语义 → 选择模型熟悉的名称与字段 → 描述 Android 差异 → 映射到现有执行器与权限链**。不以统一外观为由删掉必需约束，也不沿用含义不清的旧接口只为兼容旧设计。

### 4.2 名称和语义对照

下表是模型侧目标契约建议，新增名字尚未注册。通用高频工具采用熟悉的短名；浏览器、手机、文档和外部服务保留必要领域或来源限定。内部归属 ID、工具版本与执行绑定不由短名称替代。

| 操作 | 主流参考及差异 | Helix 推荐方向 |
| --- | --- | --- |
| 文本读取 | DSH／OpenCode 的 `read`，Claude Code 的 `Read`，Gemini 的 `read_file` | 保留 `read`；返回有界正文、行号和截断信息，不把它扩大为所有格式转换器 |
| 整文件写入 | `write/Write/write_file` 通常表达创建或覆盖 | 保留 `write`，清楚声明已有文件的覆盖行为；不把精确局部修改也塞成同一种操作 |
| 精确编辑 | DSH `edit` 与 Claude Code `Edit` 采用字面量替换；Codex／Cline 还有 patch 形式 | 保留 `edit`，常见字段优先对齐 `old_string/new_string/replace_all`。补丁格式只有在编码任务受益且边界齐备时增加，不把任意 diff 当作字面量替换 |
| 路径匹配 | `glob/Glob`；模式作用于文件名／路径 | 首选模型名 `glob`，提供 `pattern` 和搜索根，整合旧名称子串入口；不要称为全文搜索 |
| 文件内容搜索 | `grep/Grep/grep_search`；大小写、正则和输出模式存在差异 | 首选 `grep`，明确字面量／正则、范围、上下文行及匹配上限；内部仍可属于 files 能力 |
| 网页搜索／读取 | DSH `web_search/web_fetch`，Claude Code `WebSearch/WebFetch`，Gemini 使用搜索与 fetch 分离入口 | 候选改为 `web_search/web_fetch`，优先于初稿的 `web.search/web.read`；原 `http.fetch` 保留原始 HTTP 职责。fetch 是否包含正文提取须写清，不默认承诺额外模型总结 |
| 命令与交互进程 | `Bash/bash`、`run_shell_command`、Codex 的执行／stdin 分离入口，语义并不等价 | 一次命令与持久进程输入分开。只有确实使用 Bash 才叫 `bash`；现有 argv／`/bin/sh` 入口不能只改名冒充 Bash |
| 提问／计划 | `ask_user/ask_question/AskUserQuestion` 及不同计划工具 | 现有 `ask_user` 保留；优先统一问题、选项和回答语义，Goal 修订不伪装成普通 Todo |
| 浏览器 | Playwright 的 `browser_snapshot/browser_type/browser_press_key` 等 | `browser.*` 领域可保留，动作词与桌面相近；统一快照引用、输入、提交、等待的契约，点号／下划线不作为单独收益项目 |
| 手机 | AutoGLM 的 Tap／Type／Swipe，AndroidWorld 的 click／input_text／scroll 等 | `ui.*` 继续标明设备目标，采用熟悉的 click／type／scroll／swipe 语义；`set_text` 明确替换，不能与追加输入悄悄混用 |
| MCP 资源 | DSH／Gemini 提供资源列举与读取工具 | 新增时优先采用 `list_mcp_resources/list_mcp_resource_templates/read_mcp_resource` 这类明确名称，不把资源正文伪装成远程 tool call |

名称依据来自 §2 的一手资料；表内 Helix 选择是综合建议，不声称各产品完全一致。**统一的是任务语义与可预期行为，其次才是拼写。**

同一请求不同时曝光 `read/Read/read_file` 或 `glob/files.glob` 等多份等价别名。外来 Skill 中的名称可用于发现；返回当前实际可用的规范名称和 Schema。外部 MCP 原始名称由来源映射避免冲突，不将两个服务的同名工具合并。配置、审计和恢复引用稳定绑定，不依赖模型猜到正确来源。

### 4.3 参数、单位与结果也要统一

[DSH 的 read／edit](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/tool-catalog.md)采用 `file_path`、行范围参数及 `old_string/new_string/replace_all`；[Gemini 当前参数参考](https://geminicli.com/docs/reference/tools/)又区分 `file_path/dir_path`。这些证明存在可借鉴惯例，并不意味着可以混用各版本字段。

- **路径与文本：**新文件契约优先 `file_path`；目录范围用清楚定义的 `path` 或 `dir_path`，选定后同领域一致。路径是 Helix 已授权 Workspace 引用，不因名称与桌面相同就获得主机任意绝对路径访问。
- **编辑：**字面量替换、正则替换和补丁分别表达；默认唯一匹配，批量替换显式声明。现有内容身份／过期校验继续保留，不能因竞品没有同名参数而删除。
- **范围与单位：**正文行号、字节偏移、列表 cursor 不混用；采用 `offset/limit` 时必须声明基数和单位，也可采用更明确的 `start_line/end_line`。时长明确为毫秒或秒。不要靠模型记住各工具的隐含差异。
- **目标引用：**文件、节点、页面、frame 和 Job 使用其所属来源的真实引用；模型不能通过换名称或换字段让失效引用继续执行。内部可验证字段由宿主负责；确需模型回传的引用应短而明确。
- **结果：**首先返回可决策事实和必要定位信息；空结果、截断、执行中、失败、拒绝与未知不合并。执行提交、输入注入或手势派发，不等于目标任务完成。

移动端尤其不能只统一 click/type 的字面名称。[AutoGLM](https://github.com/zai-org/Open-AutoGLM/blob/main/phone_agent/actions/handler.py)将 0–1000 的相对坐标转换成屏幕像素；[Playwright MCP](https://github.com/microsoft/playwright-mcp)区分 CSS／device 截图尺度，并为输入提供 submit、逐字输入等选项；[AndroidWorld](https://github.com/google-research/android_world/blob/main/android_world/env/json_action.py)区分索引与坐标目标。Helix 应明确采用哪种坐标空间和输入效果，由宿主按同一观察 frame 转换，拒绝猜测单位或把截图缩放交给模型手算。

### 4.4 统一描述格式及示例

模型侧描述使用一致的英文动作句和参数词汇，用户界面继续本地化，中文查询别名进入发现元数据。英文是本方案的接口一致性选择，不预先宣称比中文必然更准确。

采用共同内容顺序：**用途与适用时机 → 必需前置／目标范围 → 参数关键语义及副作用 → 返回内容／下一步**。参数自身的细节放在 Schema 属性描述；仅对容易误用的工具补少量例子，不把完整 Skill 和诊断手册粘到每个工具上。也不为节省 token 把工具缩成含义不足的一行。[Anthropic 工具定义指南](https://platform.claude.com/docs/en/agents-and-tools/tool-use/define-tools)强调描述覆盖用途、使用时机、参数和限制；这里借鉴内容完整性，不建立固定字数门禁。

以下是拟议描述样例，须待相应能力实现后使用；不能直接替换到能力尚不完整的现有工具：

| 候选工具 | 模型侧描述样例 |
| --- | --- |
| `glob` | Find files by path pattern within an authorized workspace directory. Use this to locate files; use grep to search their contents. Returns matching paths and whether the result was truncated. |
| `grep` | Search text inside files within an authorized workspace directory. Use pattern for the search expression and the declared matching mode for literal or regular-expression search. Returns file paths, line numbers, bounded excerpts, and truncation information. |
| `edit` | Replace literal text in an existing text file. old_string must match exactly once unless replace_all is enabled. Use the file's current read identity when required; a mismatch applies no edit and requires a fresh read. |
| `web_fetch` | Retrieve readable content from a specified HTTP or HTTPS page. Use web_search when the URL is not known. Returns the final URL, extracted content, and truncation or extraction limits; it does not interact with page controls. |
| 手机文本替换入口 | Replace the text of an editable element from the current UI observation. This action does not press Enter or submit the form. Returns the input outcome and any available post-action observation; verify submission separately. |

现有 `browser.type/ui.set_text`、`update_goal/goal.report`、`code.linux.job.*/jobs.await` 应按上述方法分别梳理输入效果、目标修改／状态报告、启动／观察／收取职责。不能为了名称简短丢失这些差异。

### 4.5 模型适配的范围

先形成一套稳定默认工具契约，避免一开始为每个模型维护一整套名字、Schema 和执行逻辑。[ClineCore](https://docs.cline.bot/tools-reference/all-cline-tools)按模型选择编辑格式、[Anthropic 官方 text editor](https://platform.claude.com/docs/en/agents-and-tools/tool-use/text-editor-tool)提供版本化工具 Schema，说明特定格式适配有实际设计依据；这些资料不能证明 Helix 改名后的收益。

若目标模型的官方原生工具要求或有界对照证明有必要，可在既有请求编译／Provider 边界做最小名称或格式投影，保持同一执行器、绑定、权限和审计。不改写第三方 MCP 的业务含义，不静默按不同模型改变 `write` 的覆盖行为或 `type` 的提交行为，也不伪装成未满足契约的厂商原生工具。

实施时分别比较“仅改名”“名称＋参数／描述对齐”和“增加新能力”，保持模型、任务、执行器和权限条件可比。记录选错工具、参数不合法、额外发现往返和实际完成率；避免把新增搜索能力的收益误归因于名称。这是后续功能验收设计，本次文档补充不运行模型测试或文档门禁。

## 5. 工具发现：保留现有基础，修正具体薄弱点

### 5.1 已有能力不重复建设

当前 [McpToolDiscovery](../../../app/src/main/kotlin/com/helix/app/mcp/McpToolDiscovery.kt) 已支持跨来源搜索、精确名称优先、部分关键词匹配与排序、来源／类别过滤、中文别名、有效旧工具保留和零命中不清空。工具 Schema 从下一次请求可用，搜索结果不是本次响应中直接调用未知参数的授权。

[ToolDiscoveryWorkset](../../../app/src/main/kotlin/com/helix/app/mcp/ToolDiscoveryWorkset.kt) 已按 Schema 字节成本保留近期工具，并按来源组选择小型 MCP 集合。当前的近期保留预算为 64 KiB、MCP 预曝光预算为 16 KiB；新发现组会保持完整，64 KiB 不是所有 Schema 的硬上限。这些是实现快照，不是新的固定门禁。

[ChatRequestAssembler](../../../app/src/main/kotlin/com/helix/app/chat/ChatRequestAssembler.kt) 当前装配路径没有旧的“统一最多 64 个工具”截断。单次搜索结果窗口最多 16 项，与注册总量、模型后端限制、请求上下文预算是不同概念。不能用旧研究中的数量描述当前实现。

### 5.2 值得继续优化的部分

- **来源目录补充用途。** 当前目录主要是 source 和 categories。加入简短、可信来源的能力摘要，帮助模型先判断去哪里搜索，避免倾倒完整 Schema 或把外部描述提升为系统指令。
- **排序先做任务评测。** 当前匹配仍以子串和规则排序为主。可比较中文分词、字段权重或 BM25，但先看已冻结任务是否更容易找对工具，不因 Codex 使用某类检索就直接换算法。
- **别名和依赖元数据归一。** 外来工具名是发现线索；依赖说明服务于曝光，不提升执行权限。成组装载必须继续经过当前会话的可用性过滤。
- **不要重复成功搜索。** 保留下一请求 Schema 为准、失效工具重新发现、零命中给出可行动反馈的规则；结果读取和工具执行分开。

### 5.3 Mobile Use 的具体前置依赖缺口

[MobileUsePlugin.preferredTools](../../../extensions/mobile-use/src/main/kotlin/com/helix/extensions/mobileuse/MobileUsePlugin.kt) 在初次评审时包含 `ui.screenshot` 与 `ui.gesture`，遗漏了截图所需的 `ui.device/frame`；Workset 也只对 `ui.gesture` 补齐视觉前置工具。Owner 随后要求结合竞品继续收尾，已在现有入口补入默认 `ui.device`，单独发现 `ui.screenshot` 时配套同来源 `ui.device`。具体主机结果与设备边界见[追加收尾](../../evidence/development/pre-refactor-finish-2026-10-15.md#竞品对照追加收尾)。

本次只修具体配套缺口，仍由原会话可用性、当前请求快照和完整插件来源绑定过滤；发现不授予权限。声明式依赖及删除插件 ID／工具名分支继续归 HXA-286，没有提前实施整个重构。没有把单元测试收益量化为真实模型完成率。

坐标简化应由宿主按同一 frame 的图片尺寸、旋转及坐标契约完成映射，减少模型手算；它不能去掉目标身份、屏幕状态校验或把疑似变化直接当作成功。

### 5.4 追加竞品核对与取消事实

2026-10-15 再次核对一手资料：[Claude Tool search](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool) 将发现与按需装载分开；Helix 据此保留已有发现机制，补齐所选截图能力的必要前置，而不常驻全部插件 Schema。[Claude Computer use](https://platform.claude.com/docs/en/agents-and-tools/tool-use/computer-use-tool) 要求应用处理截图与屏幕坐标缩放；这一方向继续归 HXA-292，当前修复不替代坐标契约重组。

[Codex 工具运行源码](https://github.com/openai/codex/blob/main/codex-rs/core/src/tools/parallel.rs) 区分 handler 能自行完成取消与外部中断，并追踪终态。采纳的原则是保留执行端能确认的结果，不复制其并发实现或将 task abort 推导成 Android 效果已回滚。Helix 在插件尚未派发动作、只读等待取消以及点击匹配的观察准备阶段返回已有的带效果事实结果；动作开始后的未知回执、watchdog 抢先终止及物理容量占用仍遵循原 Dispatcher。另修正 `ui.system` 中“锁屏结束授权”的旧描述，保持会话配置与运行就绪分离，恢复后重新观察。

随后真实模型验收暴露了已验证业务成功却未返回对话的遵循缺口；在插件必要指导中明确返回/观察/回复的顺序，保持 [HXA-250](../../development/tasks/HXA-250.md) 的既有例外和授权工具路径。没有由宿主猜测业务完成并强制导航；小样本复测只能证明本轮旅程，不量化普遍模型收益。

## 6. 插件划分和能力扩展

**保留 Browser、Mobile Use、Documents 的领域划分。PDF 与 DOCX 继续放在一个 Documents 插件中**，共享导入、读取、快照和引用体验；格式实现可拆为内部模块。只有依赖体积、授权或产品入口需要独立生命周期时，才再考虑拆包。

流程知识优先作为 Skill：资料研究、文件整理、仓库检查、数据转换、官方帮助等不必各建一组重复工具。Skill 负责指导调用，宿主负责能力和权限。工作流所需工具尚缺时，应明确限制，不能用提示词伪装为已实现。

可移植插件应按组件显示实际状态：已安装、需要配置、未连接、未选入会话、宿主不可用、仅元数据或不支持。Codex／Claude Code／WorkBuddy 包能导入，并不说明其中的桌面命令、hooks、LSP、子 Agent 和 stdio MCP 都可直接执行。继续复用现有诊断模型，避免再加一个含义模糊的全局“启用”开关。

MCP 下一项实用补充是**资源消费闭环**：列举／分页、模板发现、参数化资源读取、大小与类型边界、来源归属及结果引用。当前 SDK 获取 Resources／Prompts 元数据不等于模型可以读取资源正文或展开 Prompt。Prompts 应在有真实使用场景时接入，并保持外部内容不可信。

通用 hooks、LSP 和子 Agent 延后独立设计：研究／文档／代码任务适合先探索子 Agent；同一手机的前台控制是不可重入资源，不能让多个 Agent 同时争抢。A2A 是外部 Agent 协议，不能当成已有本地子 Agent 引擎。

### 6.1 后续四类扩展的结构影响

2026-10-15 补充评审：当前重构方向可以承接持久终端、子 Agent、hooks 和 LSP，但这是设计判断，尚无新实现或可扩展性测试结论。采用的是职责边界，不承诺源插件直接可运行。具体目标与反例已归 [Harness §23.9](../../architecture/harness-refactor-plan.md#239-后续扩展约束) 和原任务，不另排四个功能项目。

| 能力 | 相对影响与参考 | Helix 采用点 |
| --- | --- | --- |
| 模型持久终端 | 中等；已有 PRoot/PTY/Job 可复用，人工终端契约不等于模型交互契约 | 资源与调用身份/生命周期分离；后续另定义模型输入、游标、接管、期限与失效，不假装跨进程死亡恢复 shell 内存 |
| 子 Agent | 较大；[Claude Code](https://code.claude.com/docs/en/sub-agents)使用独立上下文及受约束工具面 | 同一引擎实现可以承载独立运行，父子关系/汇合在协调层；当前重构用普通会话验证隔离，不预建 child scheduler |
| hooks | 通知类较小，干预执行类较大；[Claude Code](https://code.claude.com/docs/en/hooks)区分执行前控制与异步后置 hook | 先定义阶段和权威边界；改参重验授权、后置失败不改已发生效果，权限语义不照搬外部宿主 |
| LSP | 中等；[Claude 插件配置](https://code.claude.com/docs/en/plugins-reference)将语言服务器作为独立组件声明 | 服务器归项目/工作区资源，内容版本、范围单位及运行路径映射保持领域契约；后续服务可执行文件、协议同步和诊断另行实现 |

现有有界委托 ADR 的只读、渠道和固定并发/深度属于首版策略；后续需按产品与资源重新决定，不能因重构写成共享 Engine 的永久限制。长期运行仍受 Android 实际存活能力、原权限和资源容量约束，不能仅凭统一接口宣称任意插件兼容或未来改动很小。

### 6.2 直接内置与内置市场的重新划分

2026-10-15 Owner 追加收尾与分层复核。以下以当前工作区注册、目录和安装实现为准，区分现状与建议；**本轮不迁移插件或重写 HXA-286 的贡献协议**。内置表示实现随 APK 到达，不等于默认选入每个会话，更不等于授予设备、文件、账号或截图外发权限。

建议分三层，不把所有东西都包装成可安装插件：

| 层次 | 应放内容 | 默认行为 |
| --- | --- | --- |
| 宿主基础能力 | Turn/历史/上下文、审批与授权、工具目录/调度、文件与产物、Memory 存储、Provider、插件安装与诊断；QuickJS/PRoot 等执行基础设施按当前渠道契约提供 | 由统一治理链维护，不能卸载一个市场项就移除权限或历史正确性；具体执行仍按授权和能力准入 |
| 随包领域插件 | Mobile Use、Browser、Documents，以及各自完成任务所需的基础 Skill | 保持独立 manifest、贡献、配置、会话选择、运行资源和验收；不把实现移回 Agent Core |
| 内置市场条目 | 专项工作流、组织模板、第三方账号/服务连接、低频开发辅助 | 目录可离线浏览，用户安装后配置和选择；安装不自动登录、联网或展开全部工具正文 |

当前原生插件与基础 Skill 的建议：

| 项目 | 当前源码事实 | 建议归属与原因 |
| --- | --- | --- |
| Mobile Use | Developer 的 `AutomationModule` 注册独立原生插件；consumer 的适配为空实现 | 支持该能力的渠道直接内置，首次不默认选中；它是主打能力且依赖 Android 服务、截图/输入及原生生命周期。扩大到其他渠道须单独核对制品和商店边界，市场不能补装未包含的原生实现 |
| Browser | 两渠道注册原生插件；仅首次安装设置新会话默认选择 | 保持内置和现有默认。WebView/下载/观察资源由插件与宿主既有接口管理；选中不自动导航网页 |
| Documents | 两渠道注册一个 PDF/DOCX 插件；没有产品新会话默认选择 | 保持内置，按任务选用；阅读是常用离线能力，保留单一文档入口。格式实现可在插件内部拆分，不拆成多个同义用户入口 |
| Chat Reply | Developer 以原生插件壳贡献一个 Skill，`tools()` 为空；只借用 Mobile Use 的观察/输入 | 适合改为市场工作流包，显式依赖 Mobile Use；不需要独立原生 Runtime。当前壳先保留，迁移必须在 286 的依赖/来源契约下完成，不能丢失选择、权限或可读内容；不宣称已有后台监听/自动发送 |
| helix-docs | 随包双语按需帮助，安装身份来自宿主 | 直接内置；没有网络、账号或再安装依赖才能解释应用本身 |
| mcp-installer、skill-installer | 内置安装指导，实际安装仍走宿主工具和审批 | 保持基础内置，避免用户先装一个安装指导包才能使用扩展；不是另一套安装执行器 |
| web-research | 当前为独立内置 Skill；Browser 另有自己的网页任务 Skill | 保留通用研究指导，后续收敛到 Browser 的内容贡献并去除重复指导。搜索 Provider 是独立可配置适配，不能把 Cloudflare 文档 MCP 当作全网搜索 |
| organize-files-preview | 当前为内置的只读整理预览；市场已有 workspace-organizer | 保留一个清楚的文件整理工作流，建议通过市场按需安装并合并重复入口；预览不改文件的边界继续保留 |
| data-transform | 当前内置 Skill，调用已有 QuickJS 工具 | 更适合市场的本地数据处理工作流；不随包重复一个执行引擎，缺少 Runtime 时明确不可用 |
| repo-inspection、skill-creator | 当前为独立内置 Skills | 更适合面向开发者的市场选装；仓库检查与 code-review 可归同一内容包的不同 Skill，读取和审查职责不硬合成一个万能工具 |
| notification-digest | 当前内置、用户限定应用和时间范围的摘要指导 | 更适合市场选装；通知读取能力与系统授权仍在宿主，安装不获得通知权限、不建立后台定时任务 |

当前市场 9 项逐项保留与修正：

| 条目 | 建议 | 实际运行条件 |
| --- | --- | --- |
| Cloudflare Docs | 留市场 | 免账号的特定文档服务仍是外部网络能力，不是每个用户都需要的搜索基础设施 |
| GitHub Operations、Linear Workspace、Notion Workspace | 留市场 | 用户自己的账号/组织权限与服务配置；已有两家账号结案不重开，也不复制凭据到默认安装 |
| GitLab Workspace | 留市场；本轮修正认证提示与类型 | 官方当前支持 OAuth `mcp` scope 或具有 MCP Execute 权限的细粒度 PAT，旧式 `api` scope PAT 被拒绝；Helix 原提示会误导用户。仅修目录和三语言说明，不声称已真实登录 GitLab |
| Code Review、SQL Assistant | 留市场 | 这是指导内容，不等于自带仓库访问、数据库驱动、SQL 连接或执行权限；缺失工具须明确说明 |
| System Assistant | 留市场 | 提供诊断指导；不另加 Root/任意 Shell，也不把读取系统状态变成默认收集用户数据 |
| Workspace Organizer | 留市场并作为文件整理工作流的统一候选 | 与内置整理预览重叠；合并时保留预览和授权后的执行阶段，不能把安装当作文件修改批准 |

这套分层不会自动显著减小 APK：把几个 Skill 文本移到仍随包保存的市场目录主要减少默认发现项，未必省包体；原生库、模型运行时和 RootFS 才是此前包体分析的主要对象。APK 中已有代码不能通过隐藏卡片实现按需下载。当前市场只安装可移植内容或已支持的远程组件，**不接受从市场下载 DEX/JAR/.so 来注入主进程**；重型 OCR、本地模型/媒体包及新执行域必须另定义实际交付与隔离方式。

参考取舍：[Claude Code](https://code.claude.com/docs/en/discover-plugins)将市场、安装范围、组件和配置状态分开，[Gemini CLI](https://geminicli.com/docs/extensions/)将指导、MCP 和其他扩展组件统一打包；Helix 可借鉴组织方式，不能继承其桌面进程能力。[Android 官方](https://developer.android.com/privacy-and-security/risks/dynamic-code-loading)建议优先随应用提供功能并说明远程动态代码风险，因此“插件化”不要求原生代码离开 APK。[GitLab 官方认证要求](https://docs.gitlab.com/user/model_context_protocol/mcp_server/#authentication)是本轮目录修正的直接依据。[Cloudflare 官方目录](https://developers.cloudflare.com/agents/model-context-protocol/cloudflare/servers-for-cloudflare/)确认 Docs 服务的专用范围。

源码定位：[composition root](../../../app/src/main/kotlin/com/helix/app/DefaultAppContainer.kt)、[Developer 装配](../../../app/src/developer/kotlin/com/helix/app/automation/AutomationModule.kt)、[市场条目](../../../app/src/main/kotlin/com/helix/app/marketplace/MarketplaceCatalog.kt)、[内置 Skills](../../../extensions/skills/src/main/kotlin/com/helix/extensions/skills/BuiltInSkills.kt)、[Chat Reply](../../../extensions/chat-reply/src/main/kotlin/com/helix/extensions/chatreply/ChatReplyPlugin.kt)。后续迁移优先 Chat Reply 和重复文件整理入口，再评估专业 Skills；不阻塞本轮收尾，也不在重构前添加第二套安装兼容层。

## 7. 评审后的任务承接

2026-10-15 已将本次评审与 [Harness §23](../../architecture/harness-refactor-plan.md#23-2026-10-14-执行边界重构提案) 合并安排。研究不再维护另一份 P0～P3 排序；下表只索引已采用建议的归属，已记录的设计依赖与状态查看 [status](../../development/status.md#后续架构队列未启动)。

| 研究建议 | 承接任务与边界 |
| --- | --- |
| 模型侧语义、名称/参数/描述，来源与前置依赖 | [HXA-286](../../development/tasks/HXA-286.md)：整体契约先行，先修 Mobile Use 默认与单独发现配套 |
| 应用入口、完整 Turn 和可测试宿主 | [HXA-287](../../development/tasks/HXA-287.md)：原 B+E 合并，不等第二个产品消费者；没有新增远程平台 |
| 现有 Job 操作、真实回执与产物 | [HXA-288](../../development/tasks/HXA-288.md)：原 C+D 产物部分合并；现有 Job 统一前置，模型持久终端仍为候选 |
| 文件路径与正文搜索 | [HXA-289](../../development/tasks/HXA-289.md)：glob/grep，Standard 不依赖 Shell |
| 网页搜索与正文 | [HXA-290](../../development/tasks/HXA-290.md)：web_search/web_fetch，搜索 provider/配置须先选定 |
| MCP Resources 消费 | [HXA-291](../../development/tasks/HXA-291.md)：列举/模板/分页/读取/引用；不重开已结案 OAuth |
| Browser 表单与 Mobile Use 参数 | [HXA-292](../../development/tasks/HXA-292.md)：分两条完整功能切片，保留观察/坐标/权限边界 |
| 来源快照、Memory 与发现/上下文成本质量 | [HXA-293](../../development/tasks/HXA-293.md)：契约前置、策略有界对照后置，无收益不改默认 |
| 完整路径、旧代码/测试退役与恢复验收 | [HXA-294](../../development/tasks/HXA-294.md)：只结算冻结范围，长稳与专项仍按当次授权 |

新任务均为已排期、未实施。命名收敛随能力与完整调用路径替换，删除旧内部别名/DTO/双注册/永久桥，不另做一次纯改名。当前外部插件格式支持继续保留；结构验收与任务质量收益分别记录。

Documents 生成/编辑、XLSX/OCR、媒体深化、模型持久终端、hooks/LSP、子 Agent、Code Mode 和新远程平台仍需具体用户旅程、依赖与独立范围。它们不自动成为本轮重构或高频能力的前置，也不能从竞品的工具清单推导全部必做。

## 8. 如何证明优化有效

以下是未来实施的验收设计，**本轮未执行**。先做最小契约与主机回归，再按当轮授权开展设备和真实模型测试，不自动启动长稳。

| 任务场景 | 核验重点 |
| --- | --- |
| 在授权目录找合同，并定位包含指定条款的文件 | 路径与正文搜索不混淆；行号／片段可回读；大目录截断、二进制和编码边界真实 |
| 搜索官方资料并生成带引用的答复 | 搜索结果能打开；引用支持具体结论；原始 HTML、正文和动态页面限制可区分 |
| 从截图开始点击、输入、滚动并检查结果 | 前置工具可用，frame 和坐标一致；失效／拒绝后不误报成功；避免不必要的重复观察 |
| 浏览器完成表单与授权文件上传 | 输入和提交分离；原点、敏感字段、文件范围及撤销边界仍成立 |
| 导入只含 Skill、远程 MCP 和桌面专属组件的包 | 各组件状态准确，支持的部分可用；不支持的部分有原因，不扩张权限 |
| 读取 MCP Resource 或 PDF／DOCX 后回答具体问题 | 来源、版本／快照、分页／截断和引用能回查；缺少正文时不声称已读取 |
| 后台任务启动、等待、取消、收取和恢复 | 保留原执行身份；取消后未退出的执行器继续占用容量；历史结果不能冒充新执行 |

评价采用任务完成率、参数正确率、发现往返、总调用数、输入／输出用量、延迟和人工介入次数，并记录模型／配置／制品。业务结果独立核验；小样本只支持对应范围，不宣称稳定统计收益。

不新增“常驻工具必须几个”“源码文件不得超过多少”“测试数量必须达到多少”等易漂移门禁。已有预算只在对应资源和后端约束下评估，不为追求工具数量无限曝光。

## 9. 与已有工作衔接

本专题补充[工具与扩展综合研究](../modules/04-tools-browser-and-extensions.md)、[插件架构调研](agent-plugin-architecture-research-2026-10-11.md)及[插件优化方案](helix-plugin-architecture-optimization-2026-10-11.md)，不重复建立 Harness、插件生命周期或权限体系。

2026-10-15 已完成的 R2-B 有界观察提示对照没有证明稳定收益，不能作为新检索方案已经有效的证据。Notion／Linear 验收已由 Owner 结案；本建议不重新开启账号授权、拒绝回调或自然刷新测试。具体事实与验证边界统一查看 [status](../../development/status.md)。

已采用范围由 §7 对应任务及原主题 ADR 承接；实现时记录最终取舍与证据，再回链本文。尚未采用部分仍为研究候选，不因任务编号、设计接受或旧测试通过就宣称新实现交付。2026-10-15 文档批次未运行门禁；下方 2026-10-16 复核另跑文档/秘密扫描，不新增设备或模型运行。

## 10. 2026-10-16 预览后重构与能力复核

Owner 要求在 ARM64 预览本地收尾后，按竞品与当前实现更新既有计划。本次源码起点 `4b09357c`（生产候选仍为 `89e8ffe7`）；CodeGraph 仅作导航，关键入口另读当前源码。未复制竞品实现、引入依赖或执行第三方账号业务；官方页面读取日期为 2026-10-16，动态文档的行为不外推到全部版本。执行顺序仍只由 status 决定，设计集中在 [Harness §23](../../architecture/harness-refactor-plan.md#23-2026-10-14-执行边界重构提案)。

### 10.1 官方证据与采用边界

| 一手证据 | 本次核实的事实 | 对 Helix 的设计判断 |
| --- | --- | --- |
| [Codex App Server](https://developers.openai.com/codex/app-server/) | thread、turn 和事件有不同操作；`turn/steer` 要求匹配 `expectedTurnId`；interrupt 是请求取消 | 保留 295 的身份、CAS 与分阶段回执；协议是参考，不因桌面存在 app-server 就拆本机服务 |
| [Codex 压缩源码](https://github.com/openai/codex/blob/main/codex-rs/core/src/compact.rs) | 压缩构建区分用户消息、摘要及重新注入的初始上下文 | 285 的统一物化已修；293 优化选择预算，模型摘要不升级为授权或执行事实 |
| [Claude Code MCP](https://code.claude.com/docs/en/mcp) | 当前文档默认延迟工具定义并按需搜索，支持情况与模型/代理有关；`auto` 是阈值模式 | 继续现有发现机制，不照搬旧“10% 默认阈值”或供应方私有引用协议；用 Helix 当前 Provider 请求实测预算 |
| [Claude 插件清单](https://code.claude.com/docs/en/plugins-reference)与[Skills](https://code.claude.com/docs/en/skills) | 插件区分内容、可执行组件、依赖和用户配置；Skill 可有按需材料及脚本引用 | 包结构可参考，依赖声明不在 Helix 自动安装/授权；指导、执行程序与账号凭据分别归属 |
| [OpenHands SDK](https://github.com/OpenHands/software-agent-sdk)与[Context Condenser](https://docs.openhands.dev/sdk/guides/context-condenser) | SDK/Agent Server、工具、Workspace 和客户端有职责区分；压缩保留指定开头事件并摘要其他历史 | 借鉴可独立验证的完整会话和选择策略；不搬入其远程部署、事件数量阈值或额外持久账本 |
| [MCP transport](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)与[lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle) | stdio 是子进程双向消息流，stdout 只承载协议、stderr 独立；需协商、请求超时和有界关闭 | 一次性 stdin 快照 Job 不等于常驻 MCP 会话；取消等待、取消请求、真实进程退出分别记录 |
| [Termux](https://github.com/termux/termux-app) | 应用/终端仿真与包仓库分离，文档明确 Android 对进程和 CPU 的限制 | 手动终端既有能力保留；长期服务不能承诺永不被杀，R8/进程退出要独立验收 |
| [Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM) | 公开动作包含输入、点击、滑动、等待及人工接管回调 | 参考动作清晰度与交接；不照搬业务敏感词、固定任务脚本或把动作受理当任务成功 |
| [Operit](https://github.com/AAswordman/Operit) | 官方声明统一市场、ToolPkg/MCP/Skills、本地/远程 MCP 和移动端操作 | 证明移动产品有这些需求；未测试其隔离、可靠性或账号兼容，不以功能清单推定 Helix 应全量照搬 |

### 10.2 当前源码与缺口分类

| 领域 | 当前证据与分类 | 不能据此推出的结论 |
| --- | --- | --- |
| 完整 Turn 宿主 | `AppAgentRuntime` 仍以 start/cancel lambda 接入 Chat；`TurnEngine` 和 driver 仍直接依赖 `HelixStorage`。287 结构工作未实施 | 已有 Engine/Core 不等于已完成脱离页面和数据库细节的完整宿主 |
| 会话 | `SessionToolDescriptors.names` 已有 status/history/search/read/rename，295 A 已交付；301 补了 Standard R8/Qwen 首版旅程 | 任意会话目录、持久目标授权、create/Queue/Steer 的模型入口仍是 295 B/C；UI 有功能不等于模型获权 |
| 存储/上下文 | 297 活动/搜索投影、窗口、调用来源、GC 与 JSONL v2；285 的 `ContextHistoryContent` 已统一引用正文；299 有分段成本/省略诊断 | 不重新规划 JSONL 实时双写、引用压缩修复或诊断首版；293 的全局预算和任务相关 Memory 选择仍未实现 |
| MCP Resources | `SdkMcpClientSession.snapshotMetadata` 读取部分 resources/prompts 元数据；`McpClientSession` 尚无正文/模板/分页消费端口 | 有元数据不等于模型读过资源；291 是真实未实现的闭环 |
| 本地 stdio | `McpStdioProtocol` 与 Developer `McpStdioJobBridge` 已有有界 Job/帧处理，调用点为设备夹具；包导入 `endpoint` 遇 command/stdio 明确不生成端点 | 不能说完全没有 stdio 基础，也不能说任意导入包已能常驻运行；新运输/安装/凭据/进程 owner 尚未接线 |
| 插件/Skills | 298～302 已统一配置、内容查看、诊断、精确选用、更新目标及已安装/市场/导入；`PluginComponentAudit` 标记脚本/setup 未执行 | 包内账号服务、CLI 二进制、hooks/LSP/agent runner 仍需实际宿主；导入不替代安装程序或账号授权 |
| 终端/执行 | Job 与手动 PTY 已存在；302 的最终 R8 输入、页面重连、停止及身份读取修复已验 | 模型交互 PTY 未开放；进程死亡不能恢复 shell 内存；288 迁契约而不重做终端 UI |
| Mobile Use | 插件、会话持久授权、应用范围、浮窗、后端重建、截图来源/返回已有实现及分项实测 | 尚未覆盖的系统/OEM/任务组合属于验证边界；特定 Qwen 数字误读已由 Owner 归属模型，不另列客户端待修 |

源码入口：[应用适配](../../../app/src/main/kotlin/com/helix/app/chat/AppAgentRuntime.kt)、[TurnEngine](../../../app/src/main/kotlin/com/helix/app/engine/TurnEngine.kt)、[会话工具](../../../app/src/main/kotlin/com/helix/app/chat/SessionToolDescriptors.kt)、[引用物化](../../../app/src/main/kotlin/com/helix/app/agent/ContextHistoryContent.kt)、[Memory 当前策略](../../../core/workspace/src/main/kotlin/com/helix/core/workspace/memory/MemoryContext.kt)、[MCP facade](../../../extensions/mcp/src/main/kotlin/com/helix/extensions/mcp/McpClientFacade.kt)、[stdio Job](../../../app/src/developer/kotlin/com/helix/app/mcp/McpStdioJobBridge.kt)、[包导入](../../../extensions/plugin/src/main/kotlin/com/helix/extensions/plugin/PluginPackage.kt)、[组件审计](../../../extensions/plugin/src/main/kotlin/com/helix/extensions/plugin/PluginComponentAudit.kt)。运行边界以实际构建和 ADR-RUNTIME-001 为准，旧源码注释中的独立签名说法不改变当前同 UID 私有进程事实。

### 10.3 本次裁决

1. **先固定共同语义，立即用完整路径证明。** 286 的身份/四个完成条件/取消/恢复和存储命令是 287/288 前置；不先造万能执行 API。287-A 与 288-A 可分别验收，联合接线必须包含持久读取和可控制外部效果，单纯只读路径不足以验证 UNKNOWN 崩溃窗口。
2. **近期能力与结构迁移分开记账。** 289 检索、291 Resources、292 Mobile Use/Browser、293 预算可沿稳定接缝交付，不必等全部插件迁完；不得为抢先交付复制 ChatService、Registry 或持久 writer。各片优先级、完成标准和不确定项写回原任务。
3. **插件运行补齐按依赖逐步推进。** 先复用远程 MCP/Skill 的现有闭环，再按明确目标包评估本地 CLI，常驻 stdio 需流式 IPC、进程生命周期和账号归属，不能用重复启动 Job 模拟。自动 npm/pip 安装、全包脚本执行和通用账号代理不是导入的隐含功能。
4. **共享存储契约防止返工。** 身份和原子写入条件在 286 固定；287/288 的最小真实 Room 同时验证，再按领域替换。Room/ContentStore 唯一权威，页面搜索、JSONL 和内存进度都不是恢复账本；不保旧内部接口，但保当前版本 crash/reconcile。
5. **保持浅 harness。** 模型决定业务路线，客户端保证获权执行、真实结果、资源和审计；不按竞品工具名称生成同名空能力，不引入远程平台、固定多 Agent 分工或新的手机任务规划器。

以上是基于证据的设计选择，不是新功能完成声明。没有重新打开账号结案、OCR 归因、完整长稳或远程发布；本次文档变更及检查归各 HXA/ADR 的本次更新记录。

## 11. 2026-10-17 目录、工作副本与执行环境

Owner 追加要求：充分参考支持云端/远端执行的 Agent，给出当前优化和后续重构方案，不承担旧内部接口兼容。此次以 Helix `374ccbc0` 为源码基线，CodeGraph 定位后复核当前文件；官方资料读取于 2026-10-17，源码采用下列固定提交。只研究与更新计划，不把远程执行列为当前产品能力，不复制参考实现。已交付的标题/路径优化及设备证据仍归[原记录](../../evidence/development/path-presentation-2026-10-17.md)。

### 11.1 一手资料与可借鉴范围

| 产品/参考 | 核实的目录与执行行为 | Helix 采用与取舍 |
| --- | --- | --- |
| Codex | 固定源码的 [exec_command](https://github.com/openai/codex/blob/96eb400d1231f0199e96f640c740096087cf3e9b/codex-rs/core/src/tools/handlers/unified_exec/exec_command.rs) 从所选 environment 解析 workdir；[apply_patch](https://github.com/openai/codex/blob/96eb400d1231f0199e96f640c740096087cf3e9b/codex-rs/core/src/tools/handlers/apply_patch.rs) 同样使用对应环境的文件面、cwd 和权限上下文 | 借鉴文件和命令共享明确环境绑定；这是该源码版本的内部实现，不是稳定公开 API，也不表示 Helix 需复制桌面沙箱或 environment 注册平台 |
| Claude Code | [Remote Control](https://code.claude.com/docs/en/remote-control) 由原电脑上的进程执行，浏览器/手机控制同一会话；[Web](https://code.claude.com/docs/en/claude-code-on-the-web) 使用独立云环境，拉取/上传仓库与将任务带回本地是明确流程，后续本地修改不自动回同步云端 | 远程交互、远程执行、文件传输是三件事；连接恢复不等于搬迁文件。可借鉴 Git 分支/worktree 与 diff 审阅，但不能为模拟其交接流程 reset/stash 用户脏工作区 |
| OpenHands SDK | [RemoteWorkspace](https://github.com/OpenHands/software-agent-sdk/blob/6530325e9dd0ea1b6b4f1994d7dab52764cfc3f1/openhands-sdk/openhands/sdk/workspace/remote/base.py) 明确 host、working_dir、command_id 与上传/下载源目标；[API workspace](https://github.com/OpenHands/software-agent-sdk/blob/6530325e9dd0ea1b6b4f1994d7dab52764cfc3f1/openhands-workspace/openhands/workspace/remote_api/workspace.py) 还管理 session/runtime 及暂停恢复 | 借鉴运行实例和传输身份。其 Agent Server 是部署架构选择，不能描述成所有工具都被透明 Workspace RPC 转发；Helix 先维持一个 Turn owner 和本机适配 |
| SWE-ReX / SWE-agent | [SWE-ReX 请求类型](https://github.com/SWE-agent/SWE-ReX/blob/5c995c365dfb1fd5bc56fda688be5d8538f9931f/src/swerex/runtime/abstract.py) 区分 shell session、命令和传输路径；[仓库准备](https://github.com/SWE-agent/SWE-agent/blob/3ea751c087f32b16e039a2233dd6eefecef325d5/sweagent/environment/repo.py) 明确 clone/upload 与基线准备 | 保留持久 shell 和单次命令的不同 cwd 语义；可控评测仓库的 reset/清理策略不适用于用户共享目录 |
| VS Code / Dev Containers | [虚拟工作区](https://code.visualstudio.com/api/extension-guides/virtual-workspaces) 以 URI/provider 表达资源，不保证本机文件路径；[挂载配置](https://code.visualstudio.com/remote/advancedcontainers/change-default-source-mount) 区分 workspaceMount 和 workspaceFolder；[远程 Docker](https://code.visualstudio.com/remote/advancedcontainers/develop-remote-host) 不能直接挂载客户端本地文件 | 文件可访问与可作为 POSIX cwd 分开。SAF 不能靠替换前缀变成终端目录，远程文件必须显式传输或由真实共享存储提供 |
| E2B | [文件 API](https://docs.e2b.dev/filesystem/read-write) 操作指定 Sandbox 内的路径；[持久化](https://docs.e2b.dev/sandbox/persistence) 使用 sandbox 身份恢复，文件系统与内存恢复模式有区别 | 用原实例身份辨认恢复，不把 create、resume 和重发命令混为一谈；Android PRoot 不据此承诺恢复 shell 内存 |
| Modal | [快照](https://modal.com/docs/guide/sandbox-snapshots) 有不同种类、身份和保留期，文件系统快照用于创建新 Sandbox；[Volumes](https://modal.com/docs/guide/volumes) 的 commit/reload 决定变更可见性，同文件并发写并不提供分布式文件锁 | 区分执行实例、快照和共享存储生命周期；不把 rootfs、工作副本、Volume 与成果压成一种“目录”。不引入透明同步或假定快照永久存在 |

共同点是明确“哪个环境、哪份文件、哪个执行实例”，不是统一用一种路径字符串。上述研究没有对竞品执行可靠性、安全边界或账号功能做实测。

### 11.2 当前 Helix 的真实路径

| 当前入口 | 实际目录/数据面 | 当前边界与待改进处 |
| --- | --- | --- |
| 原生文件工具 | 请求捕获的 Workspace 与子目录；相对参数在哈希、策略和批准前变为完整 scoped 身份 | 已有请求绑定及显示投影；不应为“重构”再建第二解析器。路径可读不等于所有 Runtime 都能访问 |
| SAF 文档树 | ContentResolver/Document Provider 下的直接文档操作，执行前重查系统 grant 和能力 | Provider URI 不是 POSIX cwd；授权撤销、Provider 不在线与不支持操作须区分 |
| 手动 PRoot 终端 | App/managed 中实际目录挂载为 `/workspace`，以其为初始 cwd；抽屉入口使用自身目录 | 是 live mount，但当前不是任意外部 Path/SAF，且不自动等于聊天目录；终端内 `cd` 不改会话绑定 |
| Linux Job | 输入经有界复制/归档进入 Job 独立 workspace，再挂载 `/workspace` | 是 snapshot，非聊天目录 live mount。输入被压平成 basename，第二项起每项都追加序号，即使不重名；cwd 只接受单层目录名。输出收取/产物发布不等于回写源文件 |
| Room 与模型历史 | WorkspaceEntity 保存资源身份，SessionWorkspaceEntity 保存绑定及 revision，ModelCallWorkspaceEntity 保存原请求目录快照 | 这组职责有价值，应扩展原契约而非废弃重写；显示名/相对路径不能代替持久身份 |

核对入口：[文件参数](https://github.com/dollarser/helix-agent/blob/9965d2dff694441458b4036942c4b8b9749f0f8e/app/src/main/kotlin/com/helix/app/chat/FileToolArguments.kt)、[Workspace 持久身份](../../../core/storage/src/main/kotlin/com/helix/core/storage/entity/WorkspaceEntity.kt)、[会话目录服务](../../../app/src/main/kotlin/com/helix/app/chat/SessionWorkspaceService.kt)、[输入快照](../../../app/src/developer/kotlin/com/helix/app/proot/LinuxInputSnapshot.kt)、[Job 参数](../../../app/src/developer/kotlin/com/helix/app/proot/LinuxRunTool.kt)、[Job 挂载](../../../runtime/proot-app/src/main/kotlin/com/helix/runtime/proot/app/ProotJobRunner.kt)、[PTY 挂载](../../../runtime/proot-app/src/main/kotlin/com/helix/runtime/proot/app/ProotTerminalLaunch.kt)、[抽屉终端装配](../../../app/src/main/kotlin/com/helix/app/DefaultAppContainer.kt)、[文件来源展示](../../../app/src/main/kotlin/com/helix/app/files/WorkspaceFileSources.kt)。RootFS 提供程序运行环境，工作目录提供任务文件，产物库保存已发布结果；三个位置不能互相替代。

### 11.3 设计结论与分工

目标是**同一绑定的文件、命令与终端访问同一工作副本；显式快照任务是可见的例外**。普通 cwd 只说明相对路径的起点，不是授权根或安全沙箱。资源身份、环境路径、内容版本和展示文本分别表达，具名工具继续存在；类型是职责，不要求每个名词都新建表、模块或服务。完整责任表、流程与失败矩阵集中在 [Harness §23.3.1](../../architecture/harness-refactor-plan.md#2331-目录资源与执行环境)，不在各研究文档复制第二份方案。

- 资源/Workspace 层负责真实资源、版本、能力和可用性；执行适配层负责工作副本物化、guest 路径与实例 generation；会话/Turn 层负责绑定、请求快照、原调用及持久结算；产物层负责发布与引用。Memory 仍属内容域，不因落盘而并入执行器。
- 输入采用有界的“来源引用＋可确认版本 → guest 相对目标”manifest，保留层级并拒绝冲突/越界。复制中的内容变化需报告或拒绝，不能声称拿到了全目录原子快照。运行前能发现真实映射，不让模型猜后缀。
- 产物注册、展示、模型实际读取、回写原文件分别留事实。回写单独按当下授权和源版本处理；Provider 真支持条件写才提供该保证，先 hash 后写不等于原子 CAS。
- URI-native 消费者直接使用 Provider；只有不能使用 URI、确需 POSIX 桥接的消费者才物化。相关任务工具明确源/目标，不能让 read 留在源目录而 bash 悄悄写副本；不强迫任意 Provider 实现 POSIX。远程能访问真实工作区时可原位工作，不因远程固有复制。
- 若未来选定远程真实旅程，再定义 endpoint 认证、环境身份核验、传输授权、lease、去重 receipt 和断线对账。保持一个 Turn owner，不提前建 Host/Node/Device 目录、同步平台或通用 transport；同名路径不代表同一资源。

### 11.4 近期优化与重构承接

2026-10-17 Owner 再确认：下述近期保留当前行为只描述补丁范围；重构以当前最合理及未来扩展性为基准，现有 Entity、接口、映射及默认模式都不是兼容前提。上文源码表是事实基线，复用建议须经目标评估，不具有保留优先权。

**近期仍是计划，未借本次文档工作实施。** 优先补实际环境/cwd、live/snapshot、输入映射、产物位置和准确失败反馈；现有 Job 默认快照保持。目录选择器补搜索及来源辨认：项目/会话名称、相对子目录和可用状态用于区分重名，完整引用放详情，不清理用户已有目录。此次日常模拟器上的大量来源已证明可发现性成本，尚未做选择器改造。

输入 manifest、跨工具同资源、嵌套 cwd 和显式导回属于 286～288 的共同契约与完整路径替换；不为了短期显示而修补成第二套映射。既有身份、授权及存储边界保留，废弃的内部 DTO/别名直接删除。默认直接操作真实工作区，worktree/snapshot 显式选择，非 Git 目录不强制复制或 Git 化，不添加文件冲突全局执行锁。

可执行切片、退出条件和 HXA 归属见 [§23.7.3](../../architecture/harness-refactor-plan.md#2373-目录优化与工作副本迁移检查点)，失败矩阵见 [§23.8](../../architecture/harness-refactor-plan.md#238-验证矩阵收益与接受条件)。顺序仍为 286 → 287-A/288-A → 最小真实 Room 联合路径；本次研究不自动启动重构、远程平台或新增设备/账号验收。

本次文档检查：`./scripts/check-docs.sh`、`./scripts/verify-adr.sh`（含当前状态断言）、`./scripts/check-secrets.sh` 和 `git diff --check` 通过。研究/计划/原主题 ADR 及 HXA 接续之外未改生产源码；没有为本次规划重新运行设备或模型。原路径 UI 的主机、模拟器和真实模型结果仅见其独立记录。

### 11.5 同日收尾：持续工作区与差异审阅

2026-10-17 追加核对官方文档：[Codex worktrees](https://learn.chatgpt.com/docs/environments/git-worktrees)将聊天关联到工作树，Local/Worktree 之间通过明确的 Git handoff 交接；[Claude Code worktrees](https://code.claude.com/docs/en/worktrees)在独立 checkout 持续工作，恢复时核验目录，清理不跟随符号链接；[VS Code review](https://code.visualstudio.com/docs/agents/run/review-code-edits)区分 harness，但本地编辑均已落盘，pending 的 Keep 是确认、Undo 是撤销，不是首次写入。以上是产品文档声明，不是对其实现完整审计。

当前 Helix 已有 [GitWorkspaceReader](../../../app/src/main/kotlin/com/helix/app/git/GitWorkspaceReader.kt)与 [GitStatusScreen](../../../app/src/main/kotlin/com/helix/app/ui/GitStatusScreen.kt)，提供 JGit 的 staged/unstaged 只读状态和差异；不能写成“完全没有 diff”。缺口是按原调用关联的有界变更记录、非 Git 文件差异，以及明确区分写前审批预览和写后效果的工具卡片。Git 累积差异不能自动归因给某次工具调用，也不能证明所有写入均可撤销。

目标取舍：交互式任务持续使用同一 binding 的工作区，文件/exec/终端共享实际文件面；批处理或受限后端保留显式快照及目录/版本映射。Git 并行任务可选择 worktree，普通非 Git 目录不必复制或 Git 化。产物发布与源文件写回分开，源变更冲突保留结果，SAF 如实声明条件写能力。变更记录/卡片 diff 列入原 286/288 接缝验收，本次收尾不实现该功能或整体执行重构。

### 11.6 2026-10-19：原位默认与近期边界

Owner 明确默认选定真实工作区原位操作，独立终端无需聊天，worktree/snapshot 显式选择。对照 [VS Code 终端](https://code.visualstudio.com/docs/terminal/basics)在工作区起始并独立管理实例、[Claude Code worktrees](https://code.claude.com/docs/en/worktrees)为并行隔离显式创建 checkout，以及 [ContentResolver](https://developer.android.com/reference/android/content/ContentResolver#openOutputStream(android.net.Uri))按 URI 打开输出流：采用能力决定桥接，而非按 URI/远程标签一律复制。Provider 可写不推导 Shell/Git 或原子条件写能力；远程协议仍未立项。287/288 必须同时验原位与显式快照的权限/身份/恢复；当前 `code.linux.run` 保持快照到完整迁移，近期只改善真实来源、模式、输出与既有冲突/Git 入口，不提前实现通用变更账本或逐调用 diff。

## 12. 2026-10-24 当前实现与后续计划校核

Owner 要求结合现状优化原重构计划与任务。本节为一手资料复核与采用理由，当前状态矩阵仍只在 [Harness §23.1](../../architecture/harness-refactor-plan.md#231-设计判断范围和现有工作去重)，详细实施/验收只在原 HXA，不另建总计划。核查日期 2026-10-24；GitHub 链接为当日 main 及在线文档，未固定 commit SHA，不是固定版本源码审计或性能比较。没有复制参考实现，不能从设计相似推出 Helix 已通过验收。

| 一手来源 | 本轮可采纳的边界 | 明确不采纳/证据限制 |
| --- | --- | --- |
| [Claude plugin manifest](https://code.claude.com/docs/en/plugins-reference)、[pi 扩展](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/extensions.md) | 工具注册与内容/生命周期贡献有不同职责，支持保留 ToolRegistry，只补当前内容贡献接缝 | 不把 hooks 直接移入 Helix，不允许任意改写历史、系统安全约束或取得工具权限 |
| [Operit PromptHookRegistry](https://github.com/AAswordman/Operit/blob/main/app/src/main/java/com/ai/assistance/operit/core/chat/hooks/PromptHookRegistry.kt)、[AIToolHandler](https://github.com/AAswordman/Operit/blob/main/app/src/main/java/com/ai/assistance/operit/core/tools/AIToolHandler.kt) | 提示阶段与工具入口可区分，当前 docs/插件只需要窄内容接口 | 它仍有 Android/UI 依赖，不能称作严格分层范本；不复制 hook runner、注册系统或其代码 |
| [Claude Skills](https://code.claude.com/docs/en/skills)、[pi Skills](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/skills.md)、[Claude MCP tool search](https://code.claude.com/docs/en/mcp#scale-with-mcp-tool-search) | 目录/简短说明与正文分开，允许按需读取；支持对 Helix 现有 search/read 与预载摘要做有限对照 | 可读不保证模型读；模型兼容/回退条件有差别。不能凭 1000 字预载判为无检索，必要指令不被裁掉，默认策略须效果实测 |
| [pi bash 输出](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/src/core/tools/bash.ts) | 大输出保留可定位全文引用与退出事实，支持按需补读 | Android 用受控资源/调用 ID，不照搬主机临时路径。Helix Result/Log 已交付，不重复建设，实际模型消费另验 |
| [pi AgentSession](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/src/core/agent-session.ts) | 浅业务编排仍可有有界、可取消的技术恢复 | 保留 Helix 当前恢复实现及上限/取消/最终失败回归；不叠加另一层业务重试或把未知写效果当可安全重放 |
| [Codex App Server](https://openai.com/index/unlocking-the-codex-harness/)（2026-02-04） | 核心循环/配置/执行与客户端可通过应用接口分开，支持缩窄 AppAgentRuntime 对 ChatService 的依赖 | Helix 当前单 Android 产品不因此新增常驻服务、RPC 或事件总线；真实 Room/Runtime 适配仍有必要 |
| [Operit EnhancedAIService](https://github.com/AAswordman/Operit/blob/main/app/src/main/java/com/ai/assistance/operit/api/chat/EnhancedAIService.kt) | 参考具体职责与调用，而非类名 | 仍耦合 Android Context；pi AgentSession 也有 theme import，不能宣称竞品已全面“纯 Core” |

本地裁决依据：PluginExecutionContext 仍有 helix-docs 特判/拼串，ChatRequestAssembler 合并 readiness；AppRuntime/Engine 已有单 owner，而 ChatService/批次投影仍有收窄空间。PRoot 设置调用 app 级被动状态/显式维修入口，属于展示耦合，不是绕权 executor；Android string resource ID 与安全访问 resource ID 完全不同，不以“统一资源标识”混合它们。

旧默认快照前提已被后续原位交付替代；Job 观察 executor 识别、截图披露条件和 FileHistoryPaths 展示分支仍有真实职责，尚无证据支持全域删除。知识库的原件/核对笔记与 Memory 的作用域/记忆资格也不因 Markdown 共性合并。未覆盖设备、服务端质量、模型漏读与结构缺口分别归因，不能靠重构计划将任何一类伪报修复。

> 2026-10-24 目录迁移说明：已删除旧类的链接指向公开 v0.0.7 发布基线，供追溯该旧路径；不证明当年未提交快照逐字可重建。当前替换入口和验收以 [HXA-287](../../development/tasks/HXA-287.md) 为准，旧结构不再是现行规范。
