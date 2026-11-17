# 当前实施状态

更新：2026-11-17。本页只维护当前能力、事实来源与开放边界；当前对话决定工作范围，历史授权不自动延续到新任务。历史命令、统计和安装流水留在原任务/证据，不在这里重复。

## 当前能力与唯一说明入口

| 领域 | 当前事实 | 说明与证据 |
| --- | --- | --- |
| 对话与 UI | 原生 UI 已接入设计原型参考，页面状态与既有业务服务分层；编辑用户消息不自动发送/建分支，历史回答保留旧输入标记。原型不创造后台能力 | [UI 接入](ui-prototype-integration.md) |
| 内置模型与账号 | 官方账号 `accounts.hilex.me`、API `api.hilex.me`，语音 WS 为 `wss://api.hilex.me/ws/asr`、`/ws/tts`；Owner 确认令牌跨旧新部署通用，不因换域强制登录。内置默认模型为 `gpt-6-luna`；Android Chat Completions/Responses 真实请求成功，11-17 生产 Android ASR/TTS 合成音频复验通过且 TLS 校验开启；固定合成句识别内容也已核对；真实麦克风、普遍识别准确率和听感尚未验收。详见下方最新验收，不把历史 502/TLS 失败归为当前全部不可用，也不外推服务稳定。文件等真实业务目标尚未完成新批次验收。内置模型仅账号 Bearer + 固定 ClientID；单独 ClientID/内置 key 入口已删除。未登录可用自配 API、订阅和可选本地模型；历史绑定在发送前校验，不静默换模型 | [账号](account-login-integration.md)、[历史绑定](terminal-recovery-provider-bindings.md) |
| 云端语音 | 云端 HTTP 识别上传 WAV，指定云合成模型支持 PCM 流式边收边播；自建模型 WS 支持 ready 后裸 PCM、ASR partial/final 与同回答 TTS 连续文本输入。ASR 进入 composer 编辑、不自动发送；停止朗读与取消 Agent 分离 | [语音契约](cloud-speech-integration.md)、[最新非真机回归](../evidence/development/nondevice-client-acceptance.md) |
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

- 候选边界：含原生密码找回、人机验证与 renderer 退出处理的新候选已通过完整 Debug 与合成账号设备验收；当前收尾的完整 Debug/Release 主机门禁通过且前后源码指纹一致，双渠道隔离 R8 核心各一项及匿名入口一项通过，分别记录于 ignored `build/account-final-all-20261117/`、`build/account-final-r8-20261117/`。本批独立验证，不沿用账号接入前的 Release/R8 结果；日常数据与登录保留，未推送或发布。

- 2026-11-17 后续官方网页路径已完成真实注册、登录及邮箱已验证核查，覆盖网页验证码发信；此前只读批次的“未发信”仅描述当时范围。原生客户端已接入密码找回与三类发送前人机验证，完整 Debug 门禁与日常 API36 合成账号 17 项回归通过，已保数据覆盖安装；随后 opt-in 隔离匿名表单通过真实原生 WebView/Turnstile 发码，HTTP 200；随后独立收件列表核验确认实收（不是原 instrumentation 自身的断言）；未读取验证码、未重置密码。该测试复用生产表单和正常 TLS，不等于从日常已登录界面导航的完整真实 UI 验收；实际重置及新密码登录仍未验。见[原生接入边界](account-login-integration.md#2026-11-17-原生密码找回与人机验证接入)。

- 2026-11-17 Luna 视觉受控服务实验证实当前部署支持图片：不同合成色块及无图对照、PNG/JPEG/WebP 经 Chat Completions/Responses 六种组合均通过。客户端据此只为精确 Luna 模型启用视觉，保留图片身份/哈希/披露、正常 TLS 与原输入输出预算，不放开音频、文件或外链。随后账号找回接入前的整合候选已通过完整主机门禁及有界应用视觉闭环：真实请求携带可解码图片并完成动作目标。此单例不证明一般视觉可靠性，也不继承为后续账号候选的全产品验收；证据在 ignored `build/mobile-boundary-visual-final-20261117/`。契约及证据入口见[模型 ADR](../adr/provider/001-models-and-connection.md#2026-11-17luna-图片输入按当前部署实证启用)。

- 2026-11-17 Mobile Use 可选动作后观察与技能渐进披露已通过完整 Debug 门禁及有界真实模型任务；入口保留授权、过期 token、页面核验和不重放规则，专项参考按需读取。请求级插件/技能展示快照随后合并重复准备工作，完整 owner/source/name/hash 身份与执行时实时复验保持。八对生产 assembler 样本的 prompt/tool-schema 哈希一致，暖态中位从约 614 ms 降至 136 ms，READINESS 每次从 58 降至 6；这是准备阶段证据，不是整体任务的普遍提速比例。最终候选真实节点任务完成输入、单次点击与文件回读；保留先写文件缺目录恢复和无 active Goal 的报告失败，不把目标成功解释为所有调用都成功。效率改动纳入本轮收尾；随后账号找回接入前的整合候选已通过完整 Debug/Release 主机门禁及双渠道隔离 R8 核心各一项，证据在 ignored `build/final-integration-all-20261117/` 与 `build/final-r8-isolated-20261117/`。后续账号找回及 WebView 修改改变源码输入，不能继承这批 Release/R8 通过；本轮未做真机验收。临时每轮同步 audit 计时已移除。原始证据在 ignored `build/prompt-performance-candidate-20261117/`、`build/prompt-snapshot-debug-final-20261117/` 与 `build/mobile-prompt-snapshot-final-20261117/`。
- 2026-11-17 真实 Android ASR/TTS 两项复验通过：TTS 返回非零 PCM 并完成静音队列；TTS 音频往返及独立合成 WAV 均返回非空识别文本，正常 TLS 校验保持开启。后续固定合成句经云 TTS 和独立 Tingting 两条来源复验，去标点后均 10/10 字符一致；这不证明普遍准确率，未采集真实麦克风或验证听感，历史 TLS/502 失败仍保留。账号只读复验也通过资料、注册配置、额度及通知；未发送验证码、注册或改绑。见[语音最新边界](cloud-speech-integration.md#2026-11-17-合成音频真实服务复验)及[账号只读复验](account-login-integration.md#2026-11-17-账号只读边界复验)。

- 2026-11-16 账号并发审查修复了调度前后切换账号的请求归属：资料读取、邮箱验证码、绑定、改密及通知读取/已读请求在进入 IO 前冻结会话 revision；改密后的自动重登录在会话锁内再次复核，旧操作不作用于新账号，也不覆盖较新登录。资料读取与更新共享独立串行边界，避免同账号旧读取覆盖刚完成的绑定，不锁推理或其他工具。账号聚焦 JVM 97 项及静态分析通过，含七类延迟请求切换账号后零传输、旧重登录拒绝及跨客户端资料排序回归；该补丁未单独运行设备验证，整合候选验收另计。原始日志在 ignored `build/account-review-20261116/`。
- 2026-11-16 账号交接更新已接入本地用户名/头像与独立用户页、额度/通知局部加载、每账号每 24 小时最多一次通知自动检查、验证码注册/邮箱绑定及云端 PCM。完整 Debug 门禁和 14 项账号/邮箱/通知界面检查有通过记录；真实资料、注册配置、通知及额度只读接口通过，真实验证码发送、注册和绑定没有执行，模拟流程不代替真实服务验收。当天手势 schema 极端整数与错误诊断误遮修复已分别验收；上述账号及协议整合随后已本地提交，不属于已发布 APK。
- 2026-11-16 13:05–13:13 UTC 的真实服务复测覆盖了上表和下方 11-15 的失败快照：Luna Chat Completions/Responses 两轮成功，返回合法工具参数但未执行手势；最新双请求合计约 91 秒，不代表延迟稳定。TTS 有一次非零 PCM 和静音播放队列完成，未覆盖真实听感。ASR 后续重试仍在 TLS 阶段失败、未收到 HTTP 响应或识别文本，其当前地区限制状态未知。此前三类请求曾由网关日志确认 OpenAI 403 `unsupported_country_region_territory` 映射为 502，这是历史事实，不再表示当前 Luna/TTS 一律不可用。间歇 TLS 原因及地区判断依据仍未确定；正常证书校验保持开启。证据分别留在 ignored `build/real-cloud-retest-latest/`、`build/asr-retry-20261116T1310/` 与 `build/luna-recheck-20261116/`。四项真实业务基线仍未由这些接口探针补齐。

- v0.0.11 / code 1 已作为 GitHub 开发预览发布，ModelScope 同步提供双渠道开发签名 ARM64 APK 与更新清单。源码/tag 固定为 `a957defd`，完整与 Debug CI 通过。整合候选双渠道 Debug 40 项/R8 会话核心各 4 项通过；公开签名包在隔离 API36 上经系统安装器从 0.0.10 覆盖安装，并实际查询线上更新服务。两批范围分别记录，不外推所有设备或历史数据兼容。v0.0.10 首次升级须手动下载；详见[版本说明](../product/releases/v0.0.11.md)与[验收记录](../evidence/development/v0.0.11-preparation-2026-11-13.md)。

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
- 电脑 ADB helper 当前源码覆盖 Android 12+ 的 Advanced Debug/Release，使用应用签发、绑定进程与 APK 的一次性交接，不再依赖 `run-as`；Standard 当前无手机操作宿主适配，保持无此入口。v0.0.10 公开制品不变；新路径随 v0.0.11 Advanced 预览提供。此前 API34 真机 Debug 的 Root/ADB 证据仅用于其未变路径，不能代表新 Release 交接验收；使用、退出及范围见[电脑 ADB helper](computer-adb-helper.md)。无线配对、跨 Helix 进程 task 关联与纯无障碍独立屏仍明确延期。

- 当前仍需区分的失败：历史自然日志诊断未补读 stderr，修正后的有限成功不证明一般可靠性；真实网页/MCP 任务也曾在仅表示下一步意图后早停，后续带完整 SSE 留痕的成功不能倒推旧轮原因或消除该风险。不能把 Turn COMPLETED 当作目标完成；Memory 索引对照未证明优于默认，保留当前策略。见[Memory/日志实验](../evidence/development/memory-log-effect-experiment.md)、[客户端回归](../evidence/development/nondevice-client-acceptance.md)。
- 合成语音同一 PCM 的 HTTP/WS 传输通过，但“接入→接助”等字词差异仍在；TTS 服务全局单会话及排队/取消收尾需要服务端配合。模拟音频、播放控制和网络测试不代表真实麦克风/耳机体验。
- 既有结构、原位执行、结果日志和日常体验的具体批次移交[证据库](../evidence/development/README.md)保存，不再把各轮安装、测试数量和旧“未提交”状态复制为当前进度。实际通过范围仍以绑定源码/制品的原记录为准，其他 OEM、活动终端覆盖安装恢复与资源矩阵不由局部结果推导。
- 发布快照统一从[产品版本入口](../product/README.md)查阅；当前源码的后续修改不属于旧 APK，也不继承其发行通过。日常迭代用 Debug，签名与发布条件见[发行就绪](release-readiness.md)。

## 仍开放与未启动

| 范围 | 边界与入口 |
| --- | --- |
| 架构重构 | 完整 Turn 宿主、内容/预算、装配/输入契约，以及 conversation、共享 UI 与 Room 模块迁移均已有有界交付；后续仅按[蓝图 24.1](../architecture/harness-refactor-plan.md#241-结论基线与范围)的证据边界及[渐进路线](../architecture/harness-refactor-plan.md#245-分阶段实施路线)选择仍有收益的纵切。已完成内容不重做，不把网页、MCP Resources 等新能力当结构前置 |
| 功能新增 | Fetch/浏览器搜索、API Search 接线、MCP 二进制产物及跨会话查询/创建/投递已有有界交付；付费 Search 实接、第三方 MCP 服务/二进制模型消费、一般自主完成可靠性与动态网站质量等按[功能任务](roadmap.md#功能新增与能力扩展)的剩余范围分别推进；生产子 Agent、远程、hooks/LSP、模型 PTY 等仍依[候选索引](candidate-decisions.md)进入条件，建议不等于已授权实施 |
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


### 2026-11-15 动态浏览器与真实任务基线

动态页面前插节点可导致旧索引误点的问题已作窄修：节点动作在同次 JS 中复核观察语义，目标变化明确拒绝，不自动重放；找到目标后停止剩余遍历。固定 300 节点 JS 对照降低操作读取次数，不代表 Android 或模型端到端提速。select/submit/upload 已有实现，HXA-292 的旧待实施表述已按当前源码纠正，未把文档整理当新功能交付。

文件定位、原失败日志诊断、网页引用、跨会话找回已冻结单次真实任务基线，统计业务目标、调用/耗时与干预并保留失败。该次运行时旧配置返回 AUTH，且 Owner 当时确认后端未启动，四项未执行，不作模型质量得分。后续服务状态已变化：普通文本/语音主机探针已有成功，带工具 Luna 请求被地区限制阻断；这不补齐四项业务验收或插件名称自然模型验收。同一源码候选的 API36 隔离 Developer Debug 16 项通过（浏览器12、资源/搜索/会话消费3、原位 Runtime 死亡组合1），MCP 消费夹具有1次精确审批，不计为模型自主成功。范围见 [HXA-290](tasks/HXA-290.md)、[HXA-291](tasks/HXA-291.md)、[HXA-295](tasks/HXA-295.md)。

本轮完整 `scripts/check-all.sh --debug` 通过，含423项脚本测试、双渠道单测/构建/AndroidTest编译、静态分析、Lint及制品边界；最后文档补记另经文档/ADR检查。Developer Debug已保数据覆盖日常 Helix_API_36，安装字节与成功构建制品一致。原插件名称36项改动保持上轮输入，未提交、推送、发布或使用真机；本轮未运行Release/R8。完整日志、通过/失败及最终安装收据在 ignored `build/task-efficiency-20261115/`。

### 2026-11-15 当前内部契约清理

已移除旧工具名与旧四段来源的启动迁移、无调用兼容别名/转发层、批量调度的旧结果投影以及旧 MCP OAuth refresh 别名清理分支。当前工具来源与作用域、插件选择/授权/修订保持明确；旧键不再自动映射到新工具，也不自动删除历史数据。PluginName 成为插件展示元数据的唯一入口，文档插件准备按内置稳定 ID 判断，避免本地化名称或同名第三方影响身份。浏览器观察/动作共用节点分类，同时保留过期目标拒绝和敏感字段边界。

保留仍有实际用途的 Android/外部协议适配、当前中英文检索别名、未知结果/取消恢复、备份与显式数据库重置边界；Room 仍为当前 version 1，无新增旧 schema 迁移或破坏性自动重建。令牌按 Owner 确认通用，官方 WS 完整地址与模拟器实际自动选择已复核。最终完整 Debug 门禁、7 项 App 与 12 项浏览器设备回归通过，日常 Developer APK 已保数据覆盖；未重新发布已有渠道制品，也未声称 Release/R8、真机或真实引擎验通。详细本轮证据与数据保持范围见[账号当前契约复验](account-login-integration.md#当前契约整理与保数据复验2026-11-15)。


### 2026-11-15 工具名修复前候选整合与核心复核

保留进入本轮时的 262 项未提交修改，按账号/模型/语音、插件命名与权限、调度、浏览器过期目标、Runtime 身份和文件边界分组复核。初轮静态抽查未确认新的 P0/P1/P2 缺陷，但后续真实业务请求发现了下述工具名协议缺陷，不能用静态结论覆盖真实失败。初轮只调整测试、基线和文档；工具名生产修复另绑定新候选验收。没有提交、推送、发布或操作物理真机。

工具名修复前候选的 API36 Debug 核心对话集双渠道各 35 项通过，另 19 项账号/模型/语音/插件/Runtime 定向测试和 12 项真实 WebView 动作测试通过。独立签名、不可调试的双渠道 Release/R8 核心旅程也各通过，覆盖普通对话、只读工具回填、进程重启后历史持久化、取消与不重放后继续；这些模型响应是受控夹具，不是生产模型质量通过。制品哈希、源码输入冻结、逐项结果与清理收据保存在 ignored `build/candidate-readiness-20261115/`。这些设备批次后，新的工具名生产修复已改变输入；旧结果不代表修复后候选通过，新增候选须重新绑定完整门禁和核心旅程。

四项真实业务基线不再固定跨会话旧 Qwen：从当前候选 `BuiltInModel` 冻结公开 provider/model 及源码摘要，要求每次持久 ModelCall 的公开身份都匹配；缺失、混模型或“成功但零模型调用”明确拒绝。当前实际模型是 `gpt-6-luna`，稳定 provider ID 的旧名字不表示用了旧模型。新增准入只对同一合成会话、原始请求、官方 HTTPS 目的地、无附件/敏感内容执行正常确认，并分别统计夹具审批；不会将审批当自主成功。5 项准入边界设备测试通过。旧 AUTH 和准入失败批次保留，不能作为新候选的服务失败或业务通过。

Owner 批准的日常模拟器正式登录与真实额度读取/界面已通过，按要求保持登录。真实 ASR/TTS 均返回 HTTP 502，尚未取得音频/识别结果；本地取消/失败/PCM 回归不能替代真实录音播放。具体请求编号与边界见[真实账号复验](account-login-integration.md#2026-11-15-当前候选真实账号与额度复验)和[语音复验](cloud-speech-integration.md#2026-11-15-当前候选-android-真实服务复验)。未改变网络出口或绕过地区限制。

普通 Android Luna 已通过真实界面单次发送及 Activity 重建，但最终 Turn FAILED，未取得目标回答。持久 ModelCall 身份全部为 `gpt-6-luna`，真实网络次数与具体错误原因尚无留存证据，不推断地区原因、不重发掩盖失败；详见[账号当前复验](account-login-integration.md#2026-11-15-当前候选真实账号与额度复验)。

### 2026-11-15 真实工具名协议缺陷与新候选

新鲜单次文件定位请求已正常 Accepted，实际 `gpt-6-luna`、1 次 Wire 和 1 条 ModelCall，但服务返回 HTTP 422，明确要求工具名为 1–64 位英文字母、数字、下划线或连字符。该生产请求带有 `tools_search`、`tools_read_result`、`files_list` 等点号名称，尚未进入工具执行。这是客户端/服务协议兼容问题，不是该轮已观察到的地区限制，也不作为模型质量分数。精确 Turn 的持久诊断、原始合成请求、模型身份和安装字节证据保存在 ignored `build/real-task-live-admission-fixed-20261115-181644/`；修复前失败保留，不覆盖为成功。

修复把既有 Responses 的请求内名称表扩展到 Chat：预留合法名称、确定性别名、声明与历史统一编码，生产响应只接受当前声明工具并回映射原 canonical 身份。历史-only 工具不会获得新调用许可；未知名、变名、过长名和分块前缀歧义明确拒绝。内部工具身份、调用 ID、参数和 Dispatcher 权限不变，Codex 独立映射与不支持工具的订阅边界不扩展。三 provider 模块 341 项聚焦 JVM 已通过，完整新候选及设备结果另以新证据目录为准。

修复后新候选的完整 `scripts/check-all.sh --all` 已通过，含 429 项主机脚本、双渠道 JVM/静态/Lint、Debug/Release/R8 构建、AndroidTest 编译及制品边界。App 的 Consumer/Developer JVM 分别 1869/2070 项，均零失败/错误、各 5 项既有 opt-in 跳过。初次全门禁的两项 App 传输测试未声明工具便返回 `read`，按新协议应拒绝；已使正例显式声明，并增补未声明工具的 PROTOCOL 负例，不放宽生产 decoder。

修复后两渠道 Debug 核心各 35 项、两渠道独立签名且不可调试 Release/R8 核心各 1 项均通过；R8 实际覆盖合法 wire 名回映射后真实 Dispatcher 的只读发现/回填、重启历史、取消与不重放后继续。独立 Release selector 2 项也通过。首个 Consumer 批次在专用 API36 实例完成；随后新 AVD 因主机磁盘不足在启动前退出，不算产品失败。剩余核心/R8 改在已运行的日常模拟器用独立包执行，主 Helix 未被清数据或退出登录；原独立测试包仅保数据覆盖，新建 R8 包完成后清理。当前证据位于 ignored `build/candidate-readiness-wirefix-20261115/`，不沿用工具名修复前的 R8 通过。

修复后另有 42 项变更路径设备回归全部通过，覆盖输入协议、重复 wire ID、附件/资源消费、定时执行授权变化和合成请求准入；Scheduled 全类 8 项补验也通过。旧夹具以 canonical 名硬编码 OpenAI 响应、将目录 GET 消耗为推理响应，以及把具体 Schema 拒绝标签误认作泛化状态的问题已在测试层纠正：响应从当前请求声明选择合法名，目录请求独立核查，保留未知名拒绝、每轮本地调用独立、wire ID 配对、真实权限变化前置拒绝等原断言。初次失败与后续复验分别保存，不跳过失败项、不放宽生产契约；补验证据在 ignored `build/wire-fixture-validation/device-r2/`。最后 12 项真实 WebView 动作回归也全部通过，继续核查动态节点插入、过期索引、敏感字段、取消和提交结果边界；主 Helix 安装字节保持不变，未清数据或退出登录，证据在 `build/candidate-readiness-wirefix-20261115/browser-final/`。

修复后的普通 Developer APK 已保数据覆盖日常模拟器并核对安装 SHA，真实额度再次通过、原登录保留。普通 Luna 的唯一 UI 提交最终 SERVER_ERROR，6 条生产 HTTP 502 诊断对应 5 次自动技术恢复和末次恢复耗尽，无人工重发。新的单次文件业务提交使用合法 `helix_tool_*` wire 别名，6 次内部请求均 HTTP 502，精确持久诊断明确“OpenAI 拒绝了当前地区的访问，请联系管理员核查”，没有再出现旧 422，亦没有工具执行。网页/跨会话依赖同服务而停止；自然日志仍保留独立隔离包/认证边界，不复制主账号令牌或放宽 guard。业务质量分数保持 null。新批次及原始/修复前失败分别保留在 ignored `build/real-task-live-wirefix-20261115-192002/` 与先前各批次，普通模型/登录证据在 `build/cloud-readiness-20261115/live-wirefix/`。地区恢复后需新批次，不把协议修复或确定性夹具通过当真实业务成功。

真实重置后续交互在等待用户输入阶段到达时限，只确认挑战打开和发码 HTTP 200，未确认重置完成或新密码登录。缺少请求开始见证，不能由没有完成日志推断重置从未提交；未自动重放，日常前台已恢复。该未知边界与已通过的匿名入口导航、合成重置及 Release/R8 核心回归分别记账。
