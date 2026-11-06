# 当前实施状态

更新：2026-11-04。本页只维护当前能力、事实来源与开放边界；当前对话决定工作范围，历史授权不自动延续到新任务。历史命令、统计和安装流水留在原任务/证据，不在这里重复。

## 当前能力与唯一说明入口

| 领域 | 当前事实 | 说明与证据 |
| --- | --- | --- |
| 对话与 UI | 原生 UI 已接入设计原型参考，页面状态与既有业务服务分层；编辑用户消息不自动发送/建分支，历史回答保留旧输入标记。原型不创造后台能力 | [UI 接入](ui-prototype-integration.md) |
| 内置模型与账号 | 内置模型仅账号 Bearer + 固定 ClientID；单独 ClientID/内置 key 入口已删除。未登录可用自配 API、订阅和可选本地模型；历史绑定在发送前校验，不静默换模型 | [账号](account-login-integration.md)、[历史绑定](terminal-recovery-provider-bindings.md) |
| 云端语音 | HTTP 是整段上传/分段合成；WS 支持 ready 后裸 PCM、ASR partial/final 与同回答 TTS 连续文本输入。ASR 进入 composer 编辑、不自动发送；停止朗读与取消 Agent 分离 | [语音契约](cloud-speech-integration.md)、[最新非真机回归](nondevice-client-acceptance.md) |
| 工作区与 PRoot | 默认在已授权 POSIX 工作区原位执行，代码/文档留在原处；主应用登记文件引用。snapshot 为显式复制模式，不自动回写；PATH 当前权限/目录身份复验后原位映射；SAF 工具直接走 Provider，不猜 POSIX 路径、不以双向同步作前置 | [原位执行](proot-in-place-execution.md)、[工作区 ADR](../adr/workspace/004-workspace-binding.md) |
| 日志与结果 | 工具状态、持久正文、日志流与模型投影分开；同会话已结算成功/失败可补读。日志收取/ACK 不重执行原命令，UNKNOWN 不自动重放；原位内部日志包不包含整份工作副本 | [结果日志](result-log-closeout.md)、[结果契约](../adr/agent/006-model-data-budget-boundaries.md) |
| 执行与恢复 | TurnEngine 驱动 durable 生命周期，SessionWorkScheduler 仲裁 Queue/Goal；业务工具单次执行，明确安全条件下保留有界技术恢复，未知副作用不视为回滚 | [总体架构](../architecture/overview.md)、[执行审计](../adr/permissions/003-dispatch-and-audit.md) |
| 终端 | 独立于聊天；进入/新建 shell 与核对历史执行分开。普通工具按 CPU 最多四、压力降一；手动 PTY 最多四、Job 最多四，共享六槽，UNKNOWN 不冒充已释放容量 | [恢复与绑定](terminal-recovery-provider-bindings.md)、[覆盖安装故障](../bug-fixes/2026-10-19-terminal-package-replacement.md) |
| 知识库、分享、提醒与朗读 | HXA-308～314 的知识库、检索问答、确认式提醒、本机朗读和最小定时 Agent 均已有有界交付；原阶段“不含 TTS”等措辞不再是产品全局限制。分享面板打开不等于接收 App 已保存 | [知识库/分享](knowledge-sharing-closeout.md)、[知识库 ADR](../adr/workspace/007-local-knowledge.md)、[Goal ADR](../adr/goal/001-lifecycle-and-completion.md) |
| 文档与应用备份 | PDF 导入复用隔离解析器，区分页覆盖、无文本和部分提取；回答引用可打开修订绑定的页/片段。设置中的本地备份默认包含会话、记忆、配置与任务历史，原件/工作区可选，静态凭据独立加密且默认关闭；恢复先预检与明确确认，重启前消费者屏障、替换日志及失败回滚，任务暂停且不重放。不是进程/系统授权或账号登录镜像 | [PDF](../adr/workspace/006-pdf-reading.md)、[应用备份](../adr/workspace/008-content-backup.md) |
| 请求观测 | 消息操作按需显示持久请求的客户端首响应/首字、耗时、报告 Token 和全请求平均吞吐；缺失值为未知，不推算金额或服务器速度 | [结果投影](../adr/agent/006-model-data-budget-boundaries.md) |
| 工具、扩展与内置帮助 | Plugin/Skill/MCP 等贡献复用宿主授权与执行边界；工具按来源发现和按需 schema，独立短名不机械命名空间化。内置帮助的资源读取仍是动态有效入口 | [工具契约](tool-contracts.md)、[安装归属 ADR](../adr/connectors/003-ownership-and-installation.md)、[插件计划](../architecture/plugin-platform-plan.md) |

消息中完整的 `math` 围栏和独占行块公式提供 KaTeX 离线预览，保留源码复制与原 Markdown；完整闭合的 `mermaid`、`svg` 围栏默认内嵌离线图，支持源码切换/复制/放大；完整有界 SVG 文件可在文件管理中预览，图内链接/配置和外部资源不执行；行内公式已接保守分隔符识别、原生位图混排及点击放大；不支持、未闭合和超限保持源码，Tiny 不支持图种同样回退。知识库设置已提供默认关闭的语义检索：明确选择资料和内置嵌入模型，复用受控 search/read；正文权威、本机派生缓存、修订/权限隔离及离线词法降级保持。连续语音与 SAF 同步的具体剩余接线见[增量条件](candidate-decisions.md#资料检索展示与输入增量)。

审计页支持对已脱敏工具调用字段进行全历史关键词分批检索，保留会话/工具/日期筛选；不搜索原始参数、输出或系统日志正文。定时结果可在权限允许时发送无正文通知，点击仅审阅记录，不重放任务。

## 最新验收及未通过项

- 有序收尾补齐真实系统 SAF 的新建、原件读取、撤权失败及跨 UID 分享接收验证，修复树 URI 编码/身份与创建 API；超大文本改为有界分页，支持页码跳转和同页可见分块选择，跨页选择仍不提供。范围见[文件 ADR](../adr/workspace/002-manual-files-and-recovery.md)。API36 文件/菜单/预览及选择设备回归通过，具体候选分开记账。
- 本轮整合生产源码的双渠道 Debug/Release R8 主机门禁通过；新增验证包隔离工具另经完整主机测试。API36 双渠道全新隔离测试签名 R8 各三项基础入口及同候选覆盖后语言配置保留通过，隔离包已清理、日常包未清数据；不是正式签名、完整产品或真机 Release 验收。
- 后台 helper 异常死亡后的主屏焦点抢占已作有界清理修复，API36 正常释放、真实死亡和重连通过；Owner 后续接受双进程退出后的主屏焦点行为为合理；历史 ANR 与旧最近任务空白缩略图保留历史证据，未复现时不重复追查。历史 Mobile Use 耗时分解与模型数字核验边界见[研究记录](../research/topics/mobile-use-device-readiness-and-reliability-2026-09-29.md#2026-11-04有序收尾的断连与历史耗时复核)。Owner 随后授权真机覆盖安装；ONEPLUS_A6013/API34 保数据安装同一 Debug 候选后，七项文件/菜单/分页复制/预览界面检查，以及正常释放、原 helper 真实死亡后焦点恢复和重连通过。复制夹具固定像素回滚曾使第二块在真机离屏，已移除该回滚并在真机/模拟器重测，实际剪贴板断言保留。双渠道隔离测试签名 R8 各三项基础入口也在该真机通过并完成隔离包清理；未进行真机覆盖安装配置保留专项或正式签名验收。真实系统 SAF 的专项证据仍来自 API36；本轮未在真机新增该目录授权。首轮测试运行器曾写入简体中文选项且未事先记录原选项，后续使用保留设置模式；没有清除应用数据。
- 电脑 ADB helper 当前源码覆盖 Android 12+ 的 Advanced Debug/Release，使用应用签发、绑定进程与 APK 的一次性交接，不再依赖 `run-as`；Standard 当前无手机操作宿主适配，保持无此入口。v0.0.10 公开制品不变，新路径未发布。此前 API34 真机 Debug 的 Root/ADB 证据仅用于其未变路径，不能代表新 Release 交接验收；使用、退出及范围见[电脑 ADB helper](computer-adb-helper.md)。无线配对、跨 Helix 进程 task 关联与纯无障碍独立屏仍明确延期。

- 架构主线有界收尾见[记录](architecture-closeout.md)：过时 Engine／ChatService 缺口描述校正；PRoot 安装事实与 UI 本地化分离，旧接口删除。Dispatcher 内部观察角色、截图披露与请求路径投影按实际职责保留，不以消除类型／名称分支为目标重造框架。

- 最新日常体验收尾见[集中记录](usability-closeout.md)：离线 ASR/TTS 显式下载、占用、重新下载与确认删除，取消及物理资源使用边界已补齐；扫描 PDF 沿用视觉模型。独立 API36 ARM64 模拟器最终 55 项回归通过；自然日志生产上下文诊断仍失败，最小上下文对照不替代生产通过。最终两渠道完整 Release/R8 主机门禁与独立测试签名 Release 冒烟/同候选覆盖配置保留均通过；不代表正式签名发布或跨版本迁移。

- 历史路径与诊断收尾见[清理记录](obsolete-path-cleanup.md)：去除旧 UI 负载、不可达 Runtime 分支和源码重放器，合并过时指导；本轮模型仍未完成 stderr 读取，契约改善不等于模型行为通过。
- 前一非真机客户端批次已完成 Debug 主机、独立 API36 ARM64 模拟器、真实 PRoot/PTY 与授权模型有界验证，见[集中记录](nondevice-client-acceptance.md)。中文/英文压缩效果通过；自然日志诊断样本仍有模型未读 stderr 的失败，不能把 Turn COMPLETED 当作任务成功。
- 合成语音同一 PCM 的 HTTP/WS 传输通过，但“接入→接助”等字词差异仍在；TTS 服务全局单会话及排队/取消收尾需要服务端配合。模拟音频、播放控制和网络测试不代表真实麦克风/耳机体验。
- 原位文件、Binder/Room 登记、失败日志及原调用不重发已有后续模拟器/模型证据，不能继续按最初“未做设备”阶段重开实现。完整 OEM/低内存/全部 Runtime 矩阵未由此覆盖。
- 早期 OnePlus 的本地终端/模型验证范围见[真机记录](../evidence/development/physical-workspace-model-2026-10-19.md)。手机活动终端覆盖安装恢复、横竖屏/字体矩阵仍未由该记录覆盖；不继承当时设备或删数据授权。
- v0.0.7 ARM64 双渠道开发预览已发布，精确构建来源与分开的 CI/模拟器证据见[发布记录](../evidence/development/v0.0.7-publication-2026-10-24.md)。知识库融合原型已在后续本地修改中接入，实际能力与通过/未验范围见[接入记录](knowledge-conversation-integration.md)，不属于该 APK；后续修改不继承发行通过。日常迭代用 Debug，发行边界见[发行就绪](release-readiness.md)。

- v0.0.9／versionCode 1 开发预览已发布，源码冻结于 `93e813f2`；[下载](https://github.com/dollarser/helix-agent/releases/tag/v0.0.9)、升级数据影响与实际验证边界见[发布说明](../product/releases/v0.0.9.md)。双 APK 匿名下载校验通过，最终 Advanced 模拟器限定入口通过，不等同真实麦克风或真机迁域验收。
- v0.0.8／versionCode 1 的开发预览源码冻结于 `0ec955c1`，公开状态与下载见[Release](https://github.com/dollarser/helix-agent/releases/tag/v0.0.8)；最终源码冻结、重建身份与复用设备结果的边界以[候选记录](v0.0.8-candidate.md)为准，不继承为稳定正式版或真机通过。

## 仍开放与未启动

| 范围 | 边界与入口 |
| --- | --- |
| 完整架构重构 | 286 插件内容与 293-A 预算接缝已在 `8297a4be` 交付；287-A 应用命令已交付，B 展示/round 接缝主机与两渠道模拟器通过，C 完整宿主已交付，最终 Debug/Release 与两渠道各 103 项独立模拟器通过；288 仅保留有收益证据的局部候选。289～292 是独立能力/验证，293-B 服务恢复后的有界实验已完成，更正样本未证明候选优于默认，日志消费仍有波动，详见[实验记录](memory-log-effect-experiment.md)；294 按实际切片验收。已交付 Registry、Engine、UI、原位/日志与清理不重开；实施证据见[生命周期收尾](../evidence/development/harness-stage1-lifecycle-2026-10-24.md)，详见[当前矩阵](../architecture/harness-refactor-plan.md#231-设计判断范围和现有工作去重)和各 [HXA](tasks/README.md)，这里不维护第二执行队列 |
| 后续能力 | Project Memory 完整设备验收仍开放；生产子 Agent、远程、hooks/LSP、模型 PTY、持久 Git 写凭据等依[候选索引](candidate-decisions.md)进入条件，不随文档清理启动 |
| 服务与账号 | Notion/Linear 既定测试已结案，Claude/Grok 付费账号暂缓；新账号/付费额度/真实外部副作用需当前明确授权，配置缺失与无效分别处理 |
| 系统与资源 | 其他 OEM/API、物理低内存/热压/Doze/16 KiB、24h 与全部 Runtime 组合未全部验收；已有资源结果不外推，见[资源复核](../evidence/development/resource-gate-review-2026-10-12.md)及[原生引用调查](../evidence/development/native-reference-release-trace.md) |
| 附件与模型 | 导入不等于理解；图片受视觉能力/预算约束，任意文档、音视频、OCR 不因管线存在就算交付；真实 token 偏差及物理内存峰值仍需测量 |
| 文件恢复 | 不承诺跨 Provider 原子性、透明后台同步或运行进程迁移。显式双向文件夹同步提供新增/单侧修改、周期与冲突暂停，不传播删除；全设备/旧进程系统日志搜索及账号登录/系统授权镜像仍未提供；已核验留存命令输出跨会话分页搜索、当前 Helix 进程有界日志快照搜索与审计历史脱敏字段检索分别提供；应用备份范围和公共资料冲突处理见现行 ADR |

## 查阅方式

现行规则查 [ADR](../adr/README.md) 与[总体架构](../architecture/overview.md)；任务实施查[完成记录索引](../completion-records/index.md)和[证据索引](../evidence/README.md)。历史阶段记录是当时证据，不覆盖当前源码和接受的契约，也不授予新的设备、账号、清数据或发布权限。
