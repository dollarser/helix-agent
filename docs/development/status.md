# 当前实施状态

更新：2026-10-24。本页只维护当前能力、事实来源与开放边界；当前对话决定工作范围，历史授权不自动延续到新任务。历史命令、统计和安装流水留在原任务/证据，不在这里重复。

## 当前能力与唯一说明入口

| 领域 | 当前事实 | 说明与证据 |
| --- | --- | --- |
| 对话与 UI | 原生 UI 已接入设计原型参考，页面状态与既有业务服务分层；编辑用户消息不自动发送/建分支，历史回答保留旧输入标记。原型不创造后台能力 | [UI 接入](ui-prototype-integration.md) |
| 内置模型与账号 | 内置模型仅账号 Bearer + 固定 ClientID；单独 ClientID/内置 key 入口已删除。未登录可用自配 API、订阅和可选本地模型；历史绑定在发送前校验，不静默换模型 | [账号](account-login-integration.md)、[历史绑定](terminal-recovery-provider-bindings.md) |
| 云端语音 | HTTP 是整段上传/分段合成；WS 支持 ready 后裸 PCM、ASR partial/final 与同回答 TTS 连续文本输入。ASR 进入 composer 编辑、不自动发送；停止朗读与取消 Agent 分离 | [语音契约](cloud-speech-integration.md)、[最新非真机回归](nondevice-client-acceptance.md) |
| 工作区与 PRoot | 默认在已授权 POSIX 工作区原位执行，代码/文档留在原处；主应用登记文件引用。snapshot 为显式复制模式，不自动回写；SAF 不通用转换成 POSIX，也不透明同步 | [原位执行](proot-in-place-execution.md)、[工作区 ADR](../adr/workspace/004-workspace-binding.md) |
| 日志与结果 | 工具状态、持久正文、日志流与模型投影分开；同会话已结算成功/失败可补读。日志收取/ACK 不重执行原命令，UNKNOWN 不自动重放；原位内部日志包不包含整份工作副本 | [结果日志](result-log-closeout.md)、[结果契约](../adr/agent/006-model-data-budget-boundaries.md) |
| 执行与恢复 | TurnEngine 驱动 durable 生命周期，SessionWorkScheduler 仲裁 Queue/Goal；业务工具单次执行，明确安全条件下保留有界技术恢复，未知副作用不视为回滚 | [总体架构](../architecture/overview.md)、[执行审计](../adr/permissions/003-dispatch-and-audit.md) |
| 终端 | 独立于聊天；进入/新建 shell 与核对历史执行分开。普通工具按 CPU 最多四、压力降一；手动 PTY 最多四、Job 最多四，共享六槽，UNKNOWN 不冒充已释放容量 | [恢复与绑定](terminal-recovery-provider-bindings.md)、[覆盖安装故障](../bug-fixes/2026-10-19-terminal-package-replacement.md) |
| 知识库、分享、提醒与朗读 | HXA-308～314 的知识库、检索问答、确认式提醒、本机朗读和最小定时 Agent 均已有有界交付；原阶段“不含 TTS”等措辞不再是产品全局限制。分享面板打开不等于接收 App 已保存 | [知识库/分享](knowledge-sharing-closeout.md)、[知识库 ADR](../adr/workspace/007-local-knowledge.md)、[Goal ADR](../adr/goal/001-lifecycle-and-completion.md) |
| 工具、扩展与内置帮助 | Plugin/Skill/MCP 等贡献复用宿主授权与执行边界；工具按来源发现和按需 schema，独立短名不机械命名空间化。内置帮助的资源读取仍是动态有效入口 | [工具契约](tool-contracts.md)、[安装归属 ADR](../adr/connectors/003-ownership-and-installation.md)、[插件计划](../architecture/plugin-platform-plan.md) |

## 最新验收及未通过项

- 最新日常体验收尾见[集中记录](usability-closeout.md)：离线 ASR/TTS 显式下载、占用、重新下载与确认删除，取消及物理资源使用边界已补齐；扫描 PDF 沿用视觉模型。独立 API36 ARM64 模拟器最终 55 项回归通过；自然日志生产上下文诊断仍失败，最小上下文对照不替代生产通过。最终两渠道完整 Release/R8 主机门禁与独立测试签名 Release 冒烟/同候选覆盖配置保留均通过；不代表正式签名发布或跨版本迁移。

- 历史路径与诊断收尾见[清理记录](obsolete-path-cleanup.md)：去除旧 UI 负载、不可达 Runtime 分支和源码重放器，合并过时指导；本轮模型仍未完成 stderr 读取，契约改善不等于模型行为通过。
- 前一非真机客户端批次已完成 Debug 主机、独立 API36 ARM64 模拟器、真实 PRoot/PTY 与授权模型有界验证，见[集中记录](nondevice-client-acceptance.md)。中文/英文压缩效果通过；自然日志诊断样本仍有模型未读 stderr 的失败，不能把 Turn COMPLETED 当作任务成功。
- 合成语音同一 PCM 的 HTTP/WS 传输通过，但“接入→接助”等字词差异仍在；TTS 服务全局单会话及排队/取消收尾需要服务端配合。模拟音频、播放控制和网络测试不代表真实麦克风/耳机体验。
- 原位文件、Binder/Room 登记、失败日志及原调用不重发已有后续模拟器/模型证据，不能继续按最初“未做设备”阶段重开实现。完整 OEM/低内存/全部 Runtime 矩阵未由此覆盖。
- 早期 OnePlus 的本地终端/模型验证范围见[真机记录](../evidence/development/physical-workspace-model-2026-10-19.md)。手机活动终端覆盖安装恢复、横竖屏/字体矩阵仍未由该记录覆盖；不继承当时设备或删数据授权。
- v0.0.7 ARM64 双渠道开发预览已发布，精确构建来源与分开的 CI/模拟器证据见[发布记录](../evidence/development/v0.0.7-publication-2026-10-24.md)。新知识库前端提案尚未接入，不属于该 APK；后续工作区修改也不继承发行通过。日常迭代用 Debug，发行边界见[发行就绪](release-readiness.md)。

## 仍开放与未启动

| 范围 | 边界与入口 |
| --- | --- |
| 完整架构重构 | 286 插件内容与 293-A 预算接缝已在 `8297a4be` 交付；287-A 应用命令已交付，B 展示/round 接缝主机与两渠道模拟器通过，C 完整宿主已交付，最终 Debug/Release 与两渠道各 103 项独立模拟器通过；288 仅保留有收益证据的局部候选。289～292 是独立能力/验证，293-B 需实验，294 按实际切片验收。已交付 Registry、Engine、UI、原位/日志与清理不重开；实施证据见[生命周期收尾](../evidence/development/harness-stage1-lifecycle-2026-10-24.md)，详见[当前矩阵](../architecture/harness-refactor-plan.md#231-设计判断范围和现有工作去重)和各 [HXA](tasks/README.md)，这里不维护第二执行队列 |
| 后续能力 | Project Memory 完整设备验收仍开放；生产子 Agent、远程、hooks/LSP、模型 PTY、持久 Git 写凭据等依[候选索引](candidate-decisions.md)进入条件，不随文档清理启动 |
| 服务与账号 | Notion/Linear 既定测试已结案，Claude/Grok 付费账号暂缓；新账号/付费额度/真实外部副作用需当前明确授权，配置缺失与无效分别处理 |
| 系统与资源 | 其他 OEM/API、物理低内存/热压/Doze/16 KiB、24h 与全部 Runtime 组合未全部验收；已有资源结果不外推，见[资源复核](../evidence/development/resource-gate-review-2026-10-12.md)及[原生引用调查](../evidence/development/native-reference-release-trace.md) |
| 附件与模型 | 导入不等于理解；图片受视觉能力/预算约束，任意文档、音视频、OCR 不因管线存在就算交付；真实 token 偏差及物理内存峰值仍需测量 |
| 文件恢复 | 不承诺断电事务、跨 Provider 原子性或透明后台同步。SAF 自动同步、日志全局关键词搜索和整库备份不由本轮清理补做 |

## 查阅方式

现行规则查 [ADR](../adr/README.md) 与[总体架构](../architecture/overview.md)；任务实施查[完成记录索引](../completion-records/index.md)和[证据索引](../evidence/README.md)。历史阶段记录是当时证据，不覆盖当前源码和接受的契约，也不授予新的设备、账号、清数据或发布权限。
