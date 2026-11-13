# 当前实施状态

更新：2026-11-13。本页只维护当前能力、事实来源与开放边界；当前对话决定工作范围，历史授权不自动延续到新任务。历史命令、统计和安装流水留在原任务/证据，不在这里重复。

## 当前能力与唯一说明入口

| 领域 | 当前事实 | 说明与证据 |
| --- | --- | --- |
| 对话与 UI | 原生 UI 已接入设计原型参考，页面状态与既有业务服务分层；编辑用户消息不自动发送/建分支，历史回答保留旧输入标记。原型不创造后台能力 | [UI 接入](ui-prototype-integration.md) |
| 内置模型与账号 | 内置模型仅账号 Bearer + 固定 ClientID；单独 ClientID/内置 key 入口已删除。未登录可用自配 API、订阅和可选本地模型；历史绑定在发送前校验，不静默换模型 | [账号](account-login-integration.md)、[历史绑定](terminal-recovery-provider-bindings.md) |
| 云端语音 | HTTP 是整段上传/分段合成；WS 支持 ready 后裸 PCM、ASR partial/final 与同回答 TTS 连续文本输入。ASR 进入 composer 编辑、不自动发送；停止朗读与取消 Agent 分离 | [语音契约](cloud-speech-integration.md)、[最新非真机回归](../evidence/development/nondevice-client-acceptance.md) |
| 工作区与 PRoot | 默认在已授权 POSIX 工作区原位执行，代码/文档留在原处；主应用登记文件引用。snapshot 为显式复制模式，不自动回写；PATH 当前权限/目录身份复验后原位映射；SAF 工具直接走 Provider，不猜 POSIX 路径、不以双向同步作前置 | [原位执行](proot-in-place-execution.md)、[工作区 ADR](../adr/workspace/004-workspace-binding.md) |
| 日志与结果 | 工具状态、持久正文、日志流与模型投影分开；同会话已结算成功/失败可补读。日志收取/ACK 不重执行原命令，UNKNOWN 不自动重放；原位内部日志包不包含整份工作副本 | [结果日志](result-log-closeout.md)、[结果契约](../adr/agent/006-model-data-budget-boundaries.md) |
| 执行与恢复 | TurnEngine 驱动 durable 生命周期，SessionWorkScheduler 仲裁 Queue/Goal；业务工具单次执行，明确安全条件下保留有界技术恢复，未知副作用不视为回滚 | [总体架构](../architecture/overview.md)、[执行审计](../adr/permissions/003-dispatch-and-audit.md) |
| 终端 | 独立于聊天；进入/新建 shell 与核对历史执行分开。普通工具按 CPU 最多四、压力降一；手动 PTY 最多四、Job 最多四，共享六槽，UNKNOWN 不冒充已释放容量 | [恢复与绑定](terminal-recovery-provider-bindings.md)、[覆盖安装故障](../bug-fixes/2026-10-19-terminal-package-replacement.md) |
| 知识库、分享、提醒与朗读 | HXA-308～314 的知识库、检索问答、确认式提醒、本机朗读和最小定时 Agent 均已有有界交付；原阶段“不含 TTS”等措辞不再是产品全局限制。分享面板打开不等于接收 App 已保存 | [知识库/分享](knowledge-sharing-closeout.md)、[知识库 ADR](../adr/workspace/007-local-knowledge.md)、[Goal ADR](../adr/goal/001-lifecycle-and-completion.md) |
| 文档与应用备份 | PDF 导入复用隔离解析器，区分页覆盖、无文本和部分提取；回答引用可打开修订绑定的页/片段。设置中的本地备份默认包含会话、记忆、配置与任务历史，原件/工作区可选，静态凭据独立加密且默认关闭；恢复先预检与明确确认，重启前消费者屏障、替换日志及失败回滚，任务暂停且不重放。不是进程/系统授权或账号登录镜像 | [PDF](../adr/workspace/006-pdf-reading.md)、[应用备份](../adr/workspace/008-content-backup.md) |
| 请求观测 | 消息操作按需显示持久请求的客户端首响应/首字、耗时、报告 Token 和全请求平均吞吐；缺失值为未知，不推算金额或服务器速度 | [结果投影](../adr/agent/006-model-data-budget-boundaries.md) |
| 工具、扩展与内置帮助 | Plugin/Skill/MCP 等贡献复用宿主授权与执行边界；技能按安装归属管理，当前消息输入框已有原子技能标记与斜杠发现，见[技能 ADR](../adr/skills/001-authoring-and-installation.md)；工具按来源发现和按需 schema，独立短名不机械命名空间化。内置帮助的资源读取仍是动态有效入口 | [工具契约](tool-contracts.md)、[安装归属 ADR](../adr/connectors/003-ownership-and-installation.md)、[插件计划](../architecture/plugin-platform-plan.md) |

消息中完整的 `math` 围栏和独占行块公式提供 KaTeX 离线预览，保留源码复制与原 Markdown；完整闭合的 `mermaid`、`svg` 围栏默认内嵌离线图，支持源码切换/复制/放大；完整有界 SVG 文件可在文件管理中预览，图内链接/配置和外部资源不执行；行内公式已接保守分隔符识别、原生位图混排及点击放大；不支持、未闭合和超限保持源码，Tiny 不支持图种同样回退。知识库设置已提供默认关闭的语义检索：明确选择资料和内置嵌入模型，复用受控 search/read；正文权威、本机派生缓存、修订/权限隔离及离线词法降级保持。连续语音与 SAF 同步的具体剩余接线见[增量条件](candidate-decisions.md#资料检索展示与输入增量)。

审计页支持对已脱敏工具调用字段进行全历史关键词分批检索，保留会话/工具/日期筛选；不搜索原始参数、输出或系统日志正文。定时结果可在权限允许时发送无正文通知，点击仅审阅记录，不重放任务。

## 最新验收及未通过项

- v0.0.11 / code 1 已完成本地完整 Debug/Release R8 门禁及 ONEPLUS_A6013/API34 有界验收：Debug 专项 30、双渠道隔离测试签名 R8 入口 8 项通过；最终媒体子集在该真机/API36 模拟器分别通过。普通 Release 尚未签名，未提交/推送/发布；其他 OEM、16 KiB 真机、真实模型媒体和长稳不外推。测试参数/清理修正、复用证据与升级边界见[本轮记录](../evidence/development/v0.0.11-preparation-2026-11-13.md)及[版本准备说明](../product/releases/v0.0.11.md)。

- 跨会话查询/管理、幂等创建及明确 Queue/Steer 已完成 B/C 有界交付，不再等待宿主迁移；Debug、真实 Room 与模拟器应用/UI 验收通过，确定性本地模型不代表真实 Provider 的自然发现质量。见 [HXA-295](tasks/HXA-295.md)。
- 原生 glob/grep 的双渠道真实系统 SAF 与目录同步/冲突回归通过；自然模型定位先遇 HTTP 530，修正测试启动竞态后确认既有测试账号为 SIGNED_OUT，仍未通过，不等于文件工具失败。见 [HXA-289](tasks/HXA-289.md)。
- FFmpeg 复用原 PRoot/Job 的 API33/36 软件媒体、字体、多产物与 H.264/HEVC NDK sync/async 已有有界通过；同步模式的 encoder-flush 缺陷已由固定来源窄补丁修复，旧空文件/超时为修复前观察。0.0.11 的 ONEPLUS_A6013/API34 真机也通过完整 App/PRoot 媒体三项，硬编合成输入为 320×240/NV12，四种模式均核对帧数和实际解码；不表示该尺寸是最低要求或所有 Qualcomm/OEM 通用。API29、其他 OEM、16 KiB 真机及当前候选真实模型媒体旅程仍未验，见 [HXA-240](tasks/HXA-240.md)和[本轮记录](../evidence/development/v0.0.11-preparation-2026-11-13.md)。

- 长任务浏览器案例定位到 32 请求中的 27 次结果分页；已增加有界可调页长与保留真实链接 token 的正文视图。完整 Debug 与 API36 29 项回归通过，最后 15 请求、零分页的真实恢复样本经独立来源审计通过；原 JUnit 失败保留，未证明总延迟或普遍可靠性改善。见 [HXA-290](tasks/HXA-290.md#2026-11-12-长任务与浏览器结果效率)。

- 非语音补验新增真实系统 SAF 发布后跨进程恢复：不同 PID、REVIEW/禁自动执行、字节保留及显式恢复通过；原系统 SAF 回归同样通过。当前生产源码完整主机 Debug/Release R8、API36 双渠道隔离测试签名 Release 各三项基础入口通过；随机无语义图形的真实模型单轮核验编码图片传输、点击与文件效果通过，仍不代表一般模型可靠性。测试修正及各候选边界见[本轮补验](../evidence/development/continue-delivery-2026-11-08.md#2026-11-08-非语音补验)。本轮无真机、重登录、发布或语音质量验证。

- 长任务缓存优化已稳定工具/schema 顺序、固定提示前缀及缓存读写计量。Qwen3.8-27B 的真实 ChatService 四轮任务有一次严格验收通过，后续三轮报告缓存读取；这是优化后复用观测，不是前后 A/B、费用节省或普遍长任务收益。此前早停及失败样本保留，自主完成可靠性仍开放，见[缓存验证](../evidence/development/long-task-cache-2026-11-08.md)。

- 此前独立收尾的双渠道完整 Debug 主机门禁、API36 后台预览 17 项及私有 SAF 12 项通过，Developer Debug 已保数据覆盖日常模拟器。区分结果正文与独立日志后，真实自然诊断及修复均有界通过；真实模型的网页/MCP 文本读取、下一请求消费及答案也有严格通过样本。MCP 使用本机协议服务，不代表第三方生产服务；历史早停与诊断失败不抹去，旧 Release/真实系统 SAF 结果不继承为新候选验收。见[本轮证据](../evidence/development/continue-delivery-2026-11-08.md)。

- 会话 presentation/application、共享 UI 与 Room/ContentStore 已分别进入 `feature:conversation`、`ui:shared` 和 `infrastructure:storage`，实际依赖护栏、Debug 主机与 API36 有界设备回归通过；随后 SAF 文档快照发布失败/取消边界修复也有独立 Debug 与 storage 设备结果，两次对应候选均覆盖更新日常模拟器且未清数据。计数、候选与新旧证据边界只维护在[蓝图 24.1](../architecture/harness-refactor-plan.md#241-结论基线与范围)，不继承下述旧 Release/真机结果，也不等于自然模型用户目标已验收。
- 有序收尾补齐真实系统 SAF 的新建、原件读取、撤权失败及跨 UID 分享接收验证，修复树 URI 编码/身份与创建 API；超大文本改为有界分页，支持页码跳转和同页可见分块选择，跨页选择仍不提供。范围见[文件 ADR](../adr/workspace/002-manual-files-and-recovery.md)。API36 文件/菜单/预览及选择设备回归通过，具体候选分开记账。
- 本轮整合生产源码的双渠道 Debug/Release R8 主机门禁通过；新增验证包隔离工具另经完整主机测试。API36 双渠道全新隔离测试签名 R8 各三项基础入口及同候选覆盖后语言配置保留通过，隔离包已清理、日常包未清数据；不是正式签名、完整产品或真机 Release 验收。
- 后台 helper 异常死亡后的主屏焦点抢占已作有界清理修复，API36 正常释放、真实死亡和重连通过；Owner 后续接受双进程退出后的主屏焦点行为为合理；历史 ANR 与旧最近任务空白缩略图保留历史证据，未复现时不重复追查。历史 Mobile Use 耗时分解与模型数字核验边界见[研究记录](../research/topics/mobile-use-device-readiness-and-reliability-2026-09-29.md#2026-11-04有序收尾的断连与历史耗时复核)。Owner 随后授权真机覆盖安装；ONEPLUS_A6013/API34 保数据安装同一 Debug 候选后，七项文件/菜单/分页复制/预览界面检查，以及正常释放、原 helper 真实死亡后焦点恢复和重连通过。复制夹具固定像素回滚曾使第二块在真机离屏，已移除该回滚并在真机/模拟器重测，实际剪贴板断言保留。双渠道隔离测试签名 R8 各三项基础入口也在该真机通过并完成隔离包清理；未进行真机覆盖安装配置保留专项或正式签名验收。真实系统 SAF 的专项证据仍来自 API36；本轮未在真机新增该目录授权。首轮测试运行器曾写入简体中文选项且未事先记录原选项，后续使用保留设置模式；没有清除应用数据。
- 电脑 ADB helper 当前源码覆盖 Android 12+ 的 Advanced Debug/Release，使用应用签发、绑定进程与 APK 的一次性交接，不再依赖 `run-as`；Standard 当前无手机操作宿主适配，保持无此入口。v0.0.10 公开制品不变，新路径未发布。此前 API34 真机 Debug 的 Root/ADB 证据仅用于其未变路径，不能代表新 Release 交接验收；使用、退出及范围见[电脑 ADB helper](computer-adb-helper.md)。无线配对、跨 Helix 进程 task 关联与纯无障碍独立屏仍明确延期。

- 当前仍需区分的失败：历史自然日志诊断未补读 stderr，修正后的有限成功不证明一般可靠性；真实网页/MCP 任务也曾在仅表示下一步意图后早停，后续带完整 SSE 留痕的成功不能倒推旧轮原因或消除该风险。不能把 Turn COMPLETED 当作目标完成；Memory 索引对照未证明优于默认，保留当前策略。见[Memory/日志实验](../evidence/development/memory-log-effect-experiment.md)、[客户端回归](../evidence/development/nondevice-client-acceptance.md)。
- 合成语音同一 PCM 的 HTTP/WS 传输通过，但“接入→接助”等字词差异仍在；TTS 服务全局单会话及排队/取消收尾需要服务端配合。模拟音频、播放控制和网络测试不代表真实麦克风/耳机体验。
- 既有结构、原位执行、结果日志和日常体验的具体批次移交[证据库](../evidence/development/README.md)保存，不再把各轮安装、测试数量和旧“未提交”状态复制为当前进度。实际通过范围仍以绑定源码/制品的原记录为准，其他 OEM、活动终端覆盖安装恢复与资源矩阵不由局部结果推导。
- 发布快照统一从[产品版本入口](../product/README.md)查阅；当前源码的后续修改不属于旧 APK，也不继承其发行通过。日常迭代用 Debug，签名与发布条件见[发行就绪](release-readiness.md)。

## 仍开放与未启动

| 范围 | 边界与入口 |
| --- | --- |
| 架构重构 | 完整 Turn 宿主、内容/预算、装配/输入契约，以及 conversation、共享 UI 与 Room 模块迁移均已有有界交付；后续仅按[蓝图 24.1](../architecture/harness-refactor-plan.md#241-结论基线与范围)的证据边界及[渐进路线](../architecture/harness-refactor-plan.md#245-分阶段实施路线)选择仍有收益的纵切。已完成内容不重做，不把网页、MCP Resources 等新能力当结构前置 |
| 功能新增 | Fetch/浏览器搜索、API Search 接线、MCP 二进制产物及跨会话查询/创建/投递已有有界交付；付费 Search 实接、第三方 MCP 服务/二进制模型消费、一般自主完成可靠性与 Browser 新表单能力等按[功能任务](roadmap.md#功能新增与能力扩展)的剩余范围分别推进；生产子 Agent、远程、hooks/LSP、模型 PTY 等仍依[候选索引](candidate-decisions.md)进入条件，建议不等于已授权实施 |
| 验证与缺陷 | 已有文件搜索、设备交互等尚缺的验收与已知失败见[验证任务](roadmap.md#缺陷修复与验证收尾)；不把待验证写成未实现，不用文档整理关闭 pending |
| 服务与账号 | Notion/Linear 既定测试已结案，Claude/Grok 付费账号暂缓；新账号/付费额度/真实外部副作用需当前明确授权，配置缺失与无效分别处理 |
| 系统与资源 | 其他 OEM/API、物理低内存/热压/Doze/16 KiB、24h 与全部 Runtime 组合未全部验收；已有资源结果不外推，见[资源复核](../evidence/development/resource-gate-review-2026-10-12.md)及[原生引用调查](../evidence/development/native-reference-release-trace.md) |
| 附件与模型 | 导入不等于理解；图片受视觉能力/预算约束，任意文档、音视频、OCR 不因管线存在就算交付；真实 token 偏差及物理内存峰值仍需测量 |
| 文件恢复 | 不承诺跨 Provider 原子性、透明后台同步或运行进程迁移。显式双向文件夹同步提供新增/单侧修改、周期与冲突暂停，不传播删除；全设备/旧进程系统日志搜索及账号登录/系统授权镜像仍未提供；已核验留存命令输出跨会话分页搜索、当前 Helix 进程有界日志快照搜索与审计历史脱敏字段检索分别提供；应用备份范围和公共资料冲突处理见现行 ADR |

## 查阅方式

现行规则查 [ADR](../adr/README.md) 与[总体架构](../architecture/overview.md)；任务实施查[完成记录索引](../completion-records/index.md)和[证据索引](../evidence/README.md)。历史阶段记录是当时证据，不覆盖当前源码和接受的契约，也不授予新的设备、账号、清数据或发布权限。


### 2026-11-12 MCP Resources 二进制交付

HXA-291 增补严格 Blob 解码与累计1 MiB预算、来源/取消复核、App 私有产物及 Room 注册；模型输出仅含来源/hash/产物引用，保存但未注册返回明确错误事实。完整 Debug 门禁通过，MCP JVM111项及双渠道产物各5项通过；API36私有文件/Room重开1项通过，日常模拟器已覆盖更新。未验证二进制真实模型消费或第三方生产服务，未运行 Release、未提交推送。详见 [HXA-291](tasks/HXA-291.md#2026-11-12-二进制-resources-接续)。


### 2026-11-12 恢复与清理组合补验

修复原Job控制在无效/跨会话句柄准入阶段误报NEEDS_REVIEW：可信解析前置拒绝无副作用，实际执行、导入与存储异常仍保守处理。全Debug门禁及API36 Developer 49项组合测试通过，另7次真实进程终止后恢复验证通过；日常模拟器已覆盖更新。未以局部结果关闭全部恢复/OEM/真实服务矩阵，详见[组合验证记录](../evidence/development/recovery-combinations-2026-11-12.md)。


### 2026-11-12 Goal/工具重构前候选整合复核

在保留会话、搜索、MCP、Provider、恢复及 FFmpeg 未提交改动的同一源码候选上，重跑 `scripts/check-all.sh --all` 全部通过，覆盖双渠道 Debug/Release、R8、JVM、静态分析、测试源码编译及制品边界。Gradle 复用输入未变的任务结果；本次门禁对应 JVM 结果为 7,998 项、零失败、10 项跳过，不把跳过记作通过。定向静态审查未发现新的可复现缺陷，已同步本页过时的 FFmpeg sync 限制。

同候选 Debug 覆盖安装到 Helix_API_36，核对安装字节与构建制品一致，保留应用数据和设置；会话 37、MCP/Provider/搜索 4、恢复 27、FFmpeg 3 项确定性设备集成均通过。原始命令、非文档源码冻结清单、制品身份与测试结果保存在 ignored `build/candidate-integration-2026-11-12/`。这不代表真实模型/第三方生产服务、全部恢复/OEM/API 矩阵或 Release 设备验收；Release APK 仍未正式签名，未提交、推送或发布。


### 2026-11-12 Goal 协调与工具网关存储边界

Goal 创建、启动/恢复、轮次与解除阻塞的完整事务命令归 infrastructure；工具网关注入持久端口，不再持有 HelixStorage、Room entity 或在应用层构造 Room adapter。保留 plan/admission 外层原子性、预算与 UNKNOWN 恢复语义，并补齐独立恢复启动失败回滚。完整 Debug 门禁与 API36 77 项定向设备回归通过，日常模拟器已保数据覆盖更新；本片未重新验收 Release/R8、真机或真实模型。设计与具体证据见 [执行 ADR](../adr/agent/001-turn-coordination.md)。
