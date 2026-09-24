# 维度一：文档与代码一致性

**审查基线**：git HEAD `3cf89027`（2026-09-24）+ 未提交工作树（16 个修改文件 + 未跟踪的 Root 文件管理/两级导航 WIP）
**审查方式**：只读静态阅读 + grep 取证；所有结论附 file:line；未运行 gradle 构建/测试
**审查范围**：docs/ 全部 524 篇 md、README.md、AGENTS.md 与 settings.gradle.kts、app/build.gradle.kts、各 AndroidManifest.xml、consumer/developer 源集、核心实现代码

## 总评

这是一个文档纪律罕见地好的仓库：2365 条跨文件相对链接 0 断链；完成记录索引与实际文件完全对应；36 个 ADR 的状态（34 accepted + 2 proposed）与主题 README、status.md、AGENTS.md 的引用逐一对齐；README 的全部渠道/执行域声明（32 恒建模块 + 3 spikes、developer 单 APK 内置 PRoot/Subscriptions 私有进程、consumer 排除、ADVANCED developer-only、QuickJS 非导出 isolated 进程）与构建脚本、manifest、源集桩实现完全一致；status.md 抽查的 12 条 Completed 声明（129/196/198/199/206/213/214/215/216/217/218/219）全部在代码与完成记录中找到对应实现与证据。**没有发现"文档说 A、代码是 B"且会掩盖数据/安全风险的 P0 硬偏差。** 问题集中在 2026-09-22/23 分支收敛后未同步的叙述性状态快照：roadmap.md 的"新增对话交互需求"整节、next-work-plan.md 整页、extensions.md 一句 ADR 状态陈述仍停留在"129 未合并 / 217 待 ADR / 196/199 待真机"的旧状态，与 status.md 及自身表格直接矛盾，会诱导实施者重做已完成工作（AGENTS.md 明令禁止）。

**一句结论**：无 P0；4 处 P1 均为"状态快照滞后且与唯一状态源矛盾"，11 处 P2 为记录/文案/结构树的小滞后与"代码有文档无"缺口，总体一致性良好但 2026-09-22 收敛后缺少一次文档对账。

## 核验过的关键声明

| # | 声明（来源） | 证据 | 结论 |
| --- | --- | --- | --- |
| 1 | 32 个恒建模块 + 3 个 spikes 需 `-PincludeSpikes=true`（任务背景） | `settings.gradle.kts:30-63` 实计 32 个 include；`:66-71` 条件 include 3 个 spikes | 一致（仓库文档未另写模块数，无数字矛盾） |
| 2 | developer 单 APK 内置 Subscriptions 与 PRoot，私有进程按需运行、共享主 UID（README.md:5） | `app/build.gradle.kts:129-141` proot-*/cli-*/terminal-renderer 全部 `developerImplementation`；`runtime/proot-app/src/main/AndroidManifest.xml:9-25` 全部 `:proot`+`exported=false`；`runtime/cli-app/src/main/AndroidManifest.xml:9-29` 全部 `:subscriptions`+`exported=false` | 一致 |
| 3 | consumer 不包含 PRoot/Subscriptions（README.md:5） | consumer 依赖 0 处 proot/cli 模块；桩实现 `app/src/consumer/.../proot/ProotToolModule.kt:24 AVAILABLE=false`、`provider/SubscriptionProviderModule.kt`（`create()=null`、`openAccount()=NOT_SUPPORTED`）、`terminal/ManualTerminalModule.kt` | 一致 |
| 4 | ADVANCED 仅 developer 变体（README.md:5） | `app/src/consumer/.../profile/AdvancedProfileAvailability.kt:14 =false` vs `app/src/developer/...:17 =true`；`core/model/.../SafetyProfile.kt:13` 双态枚举 | 一致 |
| 5 | QuickJS 非导出 isolated UID 进程 `:helix_js`（AGENTS.md、local-code-execution.md） | `runtime/quickjs/src/main/AndroidManifest.xml:13-17`：`process=":helix_js"` `isolatedProcess="true"` `exported="false"`；consumer/developer 共享依赖 `app/build.gradle.kts:118` | 一致 |
| 6 | PRoot 是可信开发者执行环境，不承诺离线或隔离（README.md:5） | `local-code-execution.md:8`、`runtime/proot-app/AndroidManifest.xml:3`（"PRoot is not an offline sandbox"）、ADR-RUNTIME-001 同文 | 一致 |
| 7 | "所有工具进入同一条 schema/Capability/Policy/Approval/执行/验证/审计管线"（README.md:24） | `tools/framework/.../ToolDispatcher.kt` 单入口；security/testing-and-release.md:13 分派链；HXA-035/037 记录 | 一致 |
| 8 | 工具禁用"从曝光和分派两处生效"（security/testing-and-release.md:8） | `ToolDispatcher.kt:481` 执行前 `ToolAvailabilityState.DISABLED` 实时复检；`ToolDispatcher.kt:515-522` live 读取 | 一致 |
| 9 | 特殊递归删除确认仅限 `rm -rf dir`（AGENTS.md、security doc:10） | `core/policy/.../RmCommandRule.kt:6`（"EXPLICIT rm -rf"）、`SessionPermissionResolver.kt:57` | 一致 |
| 10 | status.md:18 HXA-129 连接器安全替换/归属/会话启停已整合 main | `app/.../connector/ConnectorService.kt`、`ConnectorSessionPanel.kt`、`ConnectorInstallationService.kt` 存在；证据 `docs/evidence/development/connector-lifecycle-2026-09-23.md` 存在；git 历史含 129 整合 | 一致 |
| 11 | status.md:20 HXA-196 异步工具 `code.linux.job.start/status/cancel/collect` | `app/.../tool/SessionToolEffectClassifier.kt:237-239` 四个工具名常量；developer 侧 DetachedJob* 20+ 文件 | 一致 |
| 12 | status.md:21 HXA-199 双 API 144 项矩阵/真实 2h 租期/30min 脱离 | `docs/completion-records/HXA-199.md`、证据 `acceptance-199-206-2026-09-21.md`、`hxa-197-terminal-page-2026-09-20.md` 均存在且被链接 | 一致 |
| 13 | status.md:19 HXA-217 请求来源记录：Room 27→28、JSONL 溯源、Thinking Accordion | `core/storage/.../RequestManifestMigration.kt:7`（MIGRATION_27_28，注册于 `HelixStorage.kt:272`）；schema `28.json` 存在；`AgentLoop.kt`/`TurnCoordinator.kt`/`ModelCallRepository.kt`/`SessionExportQuery.kt` 均有 requestManifest；3 个设备测试位于 `core/storage/src/androidTest/.../`；`app/.../ui/ThinkingAccordion.kt` | 一致 |
| 14 | status.md:13 HXA-214 发送回执/草稿保留 + HXA-215 会话内修订 | `ChatService.kt:1466-1541`（`acceptedComposerReceipt`/`acknowledgeSubmission` CAS）、`:1568 prepareLatestRevision`；`ui/MessageRevisionDialog.kt` | 一致 |
| 15 | status.md:14 HXA-216 默认排队/显式转向 | `ui/SessionInputQueuePanel.kt`、`ui/SessionInputDeliverySelector.kt`、`ChatSubmission.kt` | 一致 |
| 16 | status.md:19 HXA-213 按消息创建会话分支 | `ChatService.kt` fork 实现；ADR-AGENT-007 accepted；完成记录 HXA-213.md | 一致 |
| 17 | status.md:16 214/215/216/218/219 已整合本地 main、920 项联合验证 | git：`1c1b8ab7` 是 HEAD 祖先（`git merge-base --is-ancestor` 通过）；`branch-convergence-2026-09-22.md` 记 "final-summary.json PASS：30/30 批、920 项" | 一致 |
| 18 | status.md:9 物理真机 184 项（OnePlus 6T，0 失败）含 HXA-196 SIGKILL 后 `:proot` 存活 | `docs/evidence/development/physical-oneplus-acceptance-2026-09-24.md` 存在；HEAD commit `3cf89027` 即"close HXA-196 and HXA-199 with full physical device acceptance" | 一致 |
| 19 | ADR 纪律：36 个 ADR = 34 accepted + 2 proposed（TOOLS-001、WORKSPACE-004） | 逐文件提取 `Status:` 行；两主题 README（`adr/tools/README.md:7`、`adr/workspace/README.md:13`）与 `adr/README.md:25` 状态表一致 | 一致 |
| 20 | AGENTS.md"Child agents 与声明式 workflows 未实现"；ADR-AGENT-004 accepted 但不代表已接线 | app/src/main grep `childagent` 0 命中（仅 spikes）；`adr/agent/004:38` Verification 明言"不声明生产子 Agent/Workflow 已接线" | 一致（缺口被文档显式声明） |
| 21 | ADR-CONNECTORS-002 accepted；HXA-126 核心切片已整合、外部验收未完成（status.md:25） | `app/.../mcp/oauth/` 7 个生产类 + manifest 回调 Activity `AndroidManifest.xml:101-110`；`adr/connectors/README.md:8` 与 `tasks/HXA-126.md:3` 同口径声明"外部验收未完成" | 一致 |
| 22 | ADR-WORKSPACE-004 仍 proposed、不自动启动 HXA-210（AGENTS.md:11、status.md:48） | `adr/workspace/004-workspace-binding.md:3 Status: proposed`；`Deciders: pending` | 一致 |
| 23 | requirements.md §2.1 二十项必须实现 vs 代码 | 20 项全部有实现（MCP stdio 为 developer-only，`developer/McpStdioJobBridge.kt`，与渠道声明一致）；唯一缺口见问题 P2-8（手动终端未入需求清单） | 基本一致 |
| 24 | security doc 信任边界（模型/Skill/MCP 不能自授权） | `SessionPermissionResolver`、`ApprovalProof.kt`、`EffectFootprint` 平台归一（历史审查已证 MCP annotation 无路径进入，本次复核 `ToolDispatcher.kt:173` 注释与结构未变） | 一致 |
| 25 | 全部 docs/README/AGENTS 相对 markdown 链接 | 脚本（/tmp/helix_linkcheck.py）：CHECKED=2365 BROKEN=0 | 一致 |
| 26 | 完成记录索引 vs 实际文件 | index.md 193 条目 = 192 个 HXA 文件 + M0 区间行；无"索引有文件无"或"文件有索引无" | 一致 |
| 27 | 三语资源键集合一致（HXA-069 设计） | values/values-en/values-zh-rCN 各 1362 键，差集为空 | 一致（但 base 136 键为英文值，见 P2-9） |
| 28 | terminal.md 工具表（code.linux.job.* 参数/租期 300/1800s） | `SessionToolEffectClassifier.kt:237-239`；`code.linux.job.start` 投影 `CommandResultProjection.kt:86` | 一致 |
| 29 | session-export.md 独立校验器与合成样例 | `scripts/validate-session-export.py`、`scripts/fixtures/session-export-v1.jsonl`、`scripts/tests/test_session_export_validator.py` 均存在 | 一致 |
| 30 | requirements §5.4 工具清单（time.now/read/write/edit/files.*/http.fetch/code.javascript.run/bash/browser.*/skills.*/a2a.*/ui.*/root.*/android.*/clipboard.*/calendar.*/notifications.query） | 各工具名在 tools/framework、tools/files、tools/browser、tools/android、tools/automation（`AutomationTools.kt:364-372`）、extensions/a2a、app/proot 均有注册或实现 | 一致 |

## 问题清单

> 严重度定义：P0=误导实施者做错误决策或掩盖数据/安全风险；P1=明确的状态失实或文档自相矛盾，会误导日常开发；P2=滞后、冗余、小瑕疵。

### P1

#### P1-1 `roadmap.md`"新增对话交互需求（2026-09-22）"整节为过期快照，与自身表格、status.md 及 ADR 文件矛盾（5 处）

**问题描述（偏差机制）**：该节是 2026-09-22 时点的叙述，分支收敛（2026-09-22/23）后未同步，与同文件下方状态表格和唯一状态源 status.md 直接冲突。AGENTS.md:9 要求实施者"必读 roadmap 当前 HXA"，此处会把已完成工作展示为待办，诱发重做（AGENTS.md 明令"Do not restart completed HXA work"）。

**证据**：
- `docs/development/roadmap.md:19`「129已完成本地实现与验收，**尚未合并main**」↔ `status.md:18`「**已整合入 main**」、roadmap 自身 `:178`「HXA-129 | **已交付**」
- `docs/development/roadmap.md:5`「**217仅完成格式成本准备**」↔ 同文件 `:249`「HXA-217 **已交付**」、`status.md:19`「已完成本地实现与验收」——同一文件自相矛盾
- `docs/development/roadmap.md:24`「依赖… **proposed ADR-AGENT-010** 接受」↔ `docs/adr/agent/010-request-context-manifest.md:3`「Status: **accepted**」(2026-09-23)
- `docs/development/roadmap.md:26`「**三份提案**及来源见 Agent ADR 入口」——008/009/010 现全部 accepted（`adr/agent/README.md:15-17`）
- `docs/development/roadmap.md:39`「后续按 status 整理 **198** 与集成验收计划」↔ 同文件 `:231`「HXA-198 **已交付**」、`status.md:11`「HXA-198 双终端均已完成」

**影响范围**：所有按 AGENTS.md 阅读 roadmap 的实施者；尤其 129 整合、217 实现两条主线。
**严重度+理由**：P1——明确的状态失实 + 同文件自相矛盾，历史审查已指出 3 处，本次复核仍成立且新增 2 处（:26、:39）。
**改进建议**：将该节整体改写为"已交付"口径（链接 HXA-214~217 完成记录与 branch-convergence 证据），删除 :5 中"217仅完成格式成本准备"、:19 中"尚未合并main"等时点语句；或在节首加"本节为 2026-09-22 历史快照，当前状态以 status.md 为准"。

#### P1-2 `next-work-plan.md` 整页为过期状态快照，与 status.md 四项矛盾

**问题描述（偏差机制）**：该页更新于 2026-09-23，但 09-23/24 的收敛（129 整合、ADR-AGENT-010 接受、HXA-217 交付、196/199 物理真机闭合）后未再更新；roadmap.md:19 仍在当前叙述中主动链接它（"详见工作计划"，链接指向 [next-work-plan.md](../../../docs/development/next-work-plan.md)）。页内表把已完成工作列为待办。

**证据**：
- `docs/development/next-work-plan.md:3`「129 已在独立分支本地交付…**当前main仍为此前整合基线**」↔ `status.md:18`「已整合入 main」
- 同页「**217仍待接受ADR**」、表格「P2，后续主线 | 217轻量来源记录 | **ADR-AGENT-010 接受**（未完成条件）」↔ `adr/agent/010:3 Status: accepted`、`status.md:19`「217 已完成」
- 表格「P1，待整合授权 | **129分支整合**」↔ 129 已整合（上条）
- 表格「条件队列 | **196/199真机**」↔ HEAD commit `3cf89027`「close HXA-196 and HXA-199 with full physical device acceptance」、`status.md:9/20-21`
- 缓解因素：页首自述"当前状态以 [status](../../../docs/development/status.md) 为准"，降低了误导概率

**影响范围**：roadmap 当前章节的下游读者；接手执行的任意编码 Agent。
**严重度+理由**：P1——4 项具体当前状态失实，且被 roadmap 作为"下一步"活文档引用；虽有页首兜底，表格仍可直接诱导重做。
**改进建议**：更新为当前状态（129 已整合、217 已交付、196/199 已闭合），或按 docs/README.md 规则在页首标明历史适用时间（"本页截至 2026-09-23，后续以 status.md 为准"）并从 roadmap 当前章节移除活链接。

#### P1-3 `extensions.md:24` 称"安装所有权与签名索引 ADR 仍为 proposed"，与 ADR 文件、主题 README、status.md 均矛盾

**问题描述（偏差机制）**：该句指示"对应实现必须等待显式接受"，但两个 ADR 已 accepted 且 HXA-129/130 已交付——这是"文档说未批准、实际已批准并交付"的方向性失实，可能让实施者拒绝合法的后续工作（如 129/130 的扩展切片）或误判授权状态。

**证据**：
- `docs/architecture/extensions.md:24`「安装所有权与签名索引 ADR **仍为 proposed**，对应实现**必须等待显式接受**」
- `docs/adr/connectors/003-ownership-and-installation.md:3`「Status: **accepted**」(2026-09-22, HXA-129)
- `docs/adr/connectors/004-signed-index.md:3`「Status: **accepted**」(2026-09-16, HXA-130)
- `docs/adr/README.md:25`「Connector 安全替换/会话启停**已接受**…签名索引**已接受并交付离线范围**」
- `docs/development/status.md:10`「HXA-130 离线签名索引…已交付」；`roadmap.md:178-179` 两者"已交付"
- 代码存在：`extensions/skills/.../connector/index/SignedConnectorIndexVerifier.kt`；`app/.../connector/ConnectorInstallationService.kt`

**影响范围**：Connector 方向的实施者；与 adr/README.md（权威入口）直接冲突。
**严重度+理由**：P1——明确的状态失实且方向是"低估已获授权"，与 ADR 体系唯一规范入口相矛盾。
**改进建议**：改为"安装所有权（ADR-CONNECTORS-003）与签名索引（ADR-CONNECTORS-004）均已接受并交付离线范围；HXA-126 OAuth 真实服务验收仍开放（见 status.md）"。

#### P1-4 `task-experience.md §2` 承诺的"工作区详情四入口"页与"文件→当前任务"交接入口在代码中不存在

**问题描述（偏差机制）**：§2 把"工作区详情提供'文件、任务、终端、变更'四个直接入口"写为产品契约，落位任务（HXA-194/197/198/202/203）全部标记已交付，但 UI 中没有统一的工作区详情页；文件管理侧也没有"在当前任务使用/附加到会话"动作。

**证据**：
- `docs/product/task-experience.md:7`「工作区详情提供"文件、任务、终端、变更"四个直接入口」
- `app/src/main/kotlin/com/helix/app/ui/` 无 WorkspaceDetail 类页面（grep 0 命中）；导航 13 个 destination 中 Files/Tasks/Git 各自独立（`GroupedNavigation.kt:105`），Terminal 仅是抽屉项拉起外部 Activity（历史审查 UI-13 仍成立）
- `app/src/main/kotlin/com/helix/app/files/` 下 `stageAttachment` 调用 0 命中（历史审查 UI-6 复验：文件列表/预览仍只能弹预览，无入会话动作）
- 终端入口部分存在：`ui/FilesScreen.kt:91-94`（目录"打开终端"→`ManualTerminalModule`），与文档"工作区文件→在终端中打开所在目录"行一致

**影响范围**：按文档验收"输出→产物→来源任务"导航链的测试/产品人员；文件管理→Agent 工作流的核心用户路径。
**严重度+理由**：P1——文档把已交付 HXA 落位的用户入口描述为已存在，实际不存在；历史审查"文件管理器→Agent 工作区断链"（当时归维度三）从文档一致性角度同样成立。
**改进建议**：二选一：(a) 实施统一工作区详情页 + files 行/预览弹窗"在当前任务使用"（调 `ChatService.stageAttachment`）；(b) 修订 task-experience.md §2 为"四个入口当前分散于 Files/Tasks/Git 目的地，统一详情页待 HXA 立项"，并把落位行改为未完成。

### P2

#### P2-1 `terminal.md:3` 状态头过期

**问题描述**：页首"状态：设计已接受，分切片交付；**不据本页宣称已有独立终端或多会话**"，但 HXA-197/198 已交付（roadmap.md:230-231"已交付"；本文件后半"手动终端页面"章节已在描述已交付页面与验收证据）。
**证据**：`docs/architecture/terminal.md:3` vs `docs/completion-records/HXA-197.md`、`HXA-198.md:7`（"developer最多两个手动终端"）。
**影响**：读者可能误判终端能力未交付。**严重度**：P2（页内正文与表格已自洽，仅头注滞后）。
**建议**：头注改为"194~199 已交付（见实施与验收表），后续切片边界见 status.md"。

#### P2-2 `mobile-tool-orchestration.md` 采纳矩阵"A2A 外部 Agent Client | M7 已接受，**未实现**"

**问题描述**：矩阵仍标未实现，但 HXA-077/078/079 已交付，生产 A2A Client 模块存在且双 flavor 引入。
**证据**：`docs/architecture/mobile-tool-orchestration.md:31`；`extensions/a2a/src/main/kotlin/com/helix/extensions/a2a/`（A2aDynamicToolBridge.kt、A2aDiscoveryClient.kt、OkHttpA2aTaskClient.kt 等 7 文件）；`app/build.gradle.kts:108`（shared implementation）；`roadmap.md:116-118`"已交付"。
**影响**：低估 A2A 交付状态。**严重度**：P2（该页自我声明"只定义推荐路线"，且 09-22 后未复核）。
**建议**：矩阵行改为"M7 已接受，HXA-077~079 已交付（Client/发现/Task bridge）"。

#### P2-3 `overview.md:48`"HXA-194～199 终端链…不能仅凭本图声明已实现"措辞滞后

**问题描述**：194~199 全部交付后，该句仍把终端链与 proposed Workspace binding 并列，读起来像终端链未交付（字面上只是"图不构成证明"的 hedge）。
**证据**：`docs/architecture/overview.md:48` vs `roadmap.md:227-232`（194~199 全部"已交付"）。
**严重度**：P2（无硬矛盾，属陈旧对冲）。**建议**：改为"终端链 194~199 已交付（见完成记录）；proposed Workspace binding（ADR-WORKSPACE-004）未实现"。

#### P2-4 HXA-218/219 完成记录仍称"未合入main/独立工作树交付"（历史发现复验：仍成立）

**问题描述**：记录正文为交付时点快照，未注记后续已合并，与 status.md:16 的"已整合至本地 main"形成需要跳转收敛记录才能弥合的证据链。
**证据**：`docs/completion-records/HXA-218.md`（"本次改动保留在独立工作树，未合入main、推送或发布"）；`docs/completion-records/HXA-219.md:3`（"在 codex/ui-interaction-refactor 独立工作树交付"）；git 证据 `1c1b8ab7` 为 HEAD 祖先；`branch-convergence-2026-09-22.md` 记载合并与 920 项验证。
**严重度**：P2（链路可追溯，但记录本身滞后）。**建议**：在两条记录"边界与后续"节追加一行"2026-09-22 已随分支收敛整合入 main（见收敛记录）"。

#### P2-5 `TurnReducer` 在文档中作为已交付核心状态机，但生产路径零引用（历史发现复验：仍成立）

**问题描述**：roadmap 记 HXA-011"Turn reducer 已交付"，core/agent 保留 694 行串行状态机，但生产用 `BatchTurnRuntime`（并行批次），TurnReducer 仅被 KDoc 提及。文档未反映该架构分叉，core:agent 的测试覆盖不覆盖线上行为。
**证据**：`roadmap.md:54`；`core/agent/src/main/kotlin/com/helix/core/agent/TurnReducer.kt`；app/src/main 中唯一引用为注释 `app/.../agent/TurnCoordinator.kt:60`（"It deliberately does not reuse the M1 serial TurnReducer"）。
**严重度**：P2（非文档撒谎，HXA-011 当时确实交付；属"文档未跟踪分叉"）。**建议**：overview.md/roadmap 明确标注 TurnReducer 为 M1 参考实现，或按历史审查 F5 方案合并/下线。

#### P2-6 UI 层持有 storage repository（7 文件），overview.md 分层规则未表态（历史发现复验：仍成立，略减弱）

**问题描述**：overview.md:9 字面规则"（UI）不直接访问 DAO、网络客户端或执行器"成立（ui/feature 中 androidx.room/okhttp3/retrofit2 引用 0 命中），但 7 个 ui 文件直接 import `core.storage.repository.*`，与"UI 不承担持久化职责"的意图相悖，文档未给出边界。
**证据**：`docs/architecture/overview.md:9`；`grep com.helix.core.storage.repository app/src/main/.../ui/` = 7 文件（历史审查为 10，略有收敛）。
**严重度**：P2。**建议**：按历史审查建议二选一：文档明确"UI 可持有 repository，不可持有 DAO"，或把 repository 下沉到协调层。

#### P2-7 "turn regeneration（重新生成）"功能已进 main，但无任何文档足迹（代码有、文档无）

**问题描述**：提交 `8aa8ff97`（2026-09-23，HEAD 内）为聊天增加"重新生成"动作（UI 入口 + `MessageRegenerateTest` + `StorageGarbageCollector` 孤儿文件回收），但 status.md/roadmap/完成记录/requirements.md 均无提及——违反仓库自身"结果保存在完成记录、status 是唯一当前状态源"的纪律。
**证据**：`git show 8aa8ff97 --stat`（ChatService.kt +102、`ui/ConversationMessage.kt` +65、`core/storage/.../StorageGarbageCollector.kt` 新 143 行、`MessageRegenerateTest.kt` 新 52 行）；`app/src/main/res/values/strings.xml:1265 chat_regenerate_action`；grep docs 无"重新生成"相关记录（仅 HXA-080 的无关"重新生成 lock"）。
**严重度**：P2（增量功能、无矛盾，但状态源失忆）。**建议**：补完成记录（或并入相关 HXA 记录）并在 status.md Completed 加一行；同步说明孤儿回收属于 §8 数据保留的哪条规则。

#### P2-8 requirements.md 功能清单缺"手动终端（developer）"（代码有、文档无）

**问题描述**：HXA-197/198/199 交付了完整的 developer 手动终端（双会话、PTY、租期/回收、UI 页面），架构文档 terminal.md、ADR-RUNTIME-002、roadmap 均有记载，但产品需求 §2.1"必须实现"20 项与 §7 页面需求 15 项均未包含手动终端（§7.8 只覆盖 Runtime 安装/状态页）。
**证据**：`docs/product/requirements.md:21-40`（无终端项）、`:370-386`（页面清单无终端页）vs `app/src/developer/.../terminal/` 13 个文件、`ManualTerminalActivity`（`app/src/developer/AndroidManifest.xml:4-5` 非导出）、`docs/architecture/terminal.md` 全文。
**严重度**：P2（需求文档漏项，非矛盾；§2.1 第 17 项"可选 PRoot + Alpine Linux 开发者模式"可被宽泛解读为涵盖）。**建议**：§2.1 增补"developer 手动 PTY 终端（双会话，见 ADR-RUNTIME-002）"，§7 增终端页一行。

#### P2-9 三语资源：base（声明为简体中文）中 136 个键为纯英文值

**问题描述**：HXA-069 注释声明"base/primary locale is Simplified Chinese"，但 base values/strings.xml 的 1362 键中 136 个值是纯英文（含 `marketplace_*` 12 个、`skill_creator_*`、`provider_subscription_*`、`settings_proot_title` 等用户可见标签）；zh-rCN 有对应中文（如 `marketplace_tab_market` base="Marketplace" vs zh-rCN="发现市场"）。非 zh-rCN 的中文 locale（如 zh-HK）会回退 base 显示英文。`check-i18n.sh` 只校验键集合不校验值语言，门禁无法拦截。
**证据**：`app/src/main/res/values/strings.xml:11-12`（HXA-069 注释）、`:1193-1205`（marketplace 英文值）vs `values-zh-rCN/strings.xml:1190-1202`（中文值）；全量统计 1362 键/1209 含中文/136 纯英文。
**严重度**：P2（默认 zh 设备上多数情况命中 zh-rCN 正确显示；属设计声明与实现的偏离 + 门禁盲区）。**建议**：将 136 个 base 英文值改中文（或把 HXA-069 的 base-locale 声明改为"英文 fallback"并同步 zh-rCN 定位）；check-i18n.sh 增加"base 值含 CJK"抽样检查。

#### P2-10 `task-experience.md §4` 操作链入口命名与实际 UI 不符

**问题描述**：文档承诺首版入口为"添加能力"、分"已添加"和"添加来源"；实际交付的 UI 是"扩展"页 + "发现市场 / 已安装与自定义"双 Tab（HXA-212）。文档中的六个状态阶段（已添加/待配置/待连接验证/未启用/可使用/需处理）与实现（NOT_INSTALLED/INSTALLED_INACTIVE/ACTIVE 三态，历史审查 UI-7）也不一致。
**证据**：`docs/product/task-experience.md:36-52`；`app/src/main/res/values/strings.xml:1195-1196`（`marketplace_tab_market`=Marketplace/发现市场、`marketplace_tab_manage`=Installed & Custom/已安装与自定义）、`:1201-1203` 三状态；strings 中"添加能力/已添加/添加来源"0 命中。
**严重度**：P2（流程骨架存在，命名与状态机粒度滞后）。**建议**：§4 按 HXA-212 实际命名重写，状态表与实现三态对齐或注明六阶段为验收目标。

#### P2-11 `android-platform-capabilities.md §3.2` 浏览器模块树与实际包结构不符

**问题描述**：文档给出 `feature/browser/{api, engine-webview, automation, downloads, ui}` 规划树，实际为扁平包 + `engine/webview/snapshot/storage/ui` 子包，无 `api`/`automation`/独立 `downloads` 目录（下载逻辑为 BrowserDownload*.kt 顶层文件）。
**证据**：`docs/architecture/android-platform-capabilities.md:55-61` vs `ls feature/browser/src/main/kotlin/com/helix/feature/browser/`（BrowserController.kt、engine/、webview/、snapshot/、storage/、ui/ 等 21 条目）。
**严重度**：P2（2026-08-31 基线设计树未更新，不影响行为）。**建议**：更新为实际包结构，或标注"设计期规划，实际结构以代码为准"。

### 未单列的轻微观察

- `adr/README.md:25`「HXA-129已本地交付，整合状态见实施页」——129 已整合 main（status.md:18），措辞滞后但指向权威源，不算矛盾。
- `roadmap.md:5`「历史复核基线保留12项未闭合义务：3项收尾验收、2项集成验收、3项待决策、4项发行队列」——当前表格实际只剩 2 项收尾验收（125/190）+ 1 项进行中（126）+ 4 项发行队列；该句自述为"历史复核基线"台账口径，按历史陈述不计矛盾，建议注明记账时点。
- 工作树未提交改动（两级导航抽屉 GroupedNavigation.kt、Root 文件管理 RootFileModule/RootFileOperations/RootFileAccessor、SessionDao 小改）尚无对应文档更新——属正常 WIP，不判偏差，但合并前需补完成记录。
- HXA-217 完成记录称"1639 键"（check-i18n.sh 扫描口径，含 developer 源集多个 res 文件），与 base 文件 1362 键不冲突（口径不同），不构成矛盾。

## 历史审查"维度一"发现的状态复核

| # | 历史发现（reviews/2026-09-24-code-review.md） | 本次复核 | 证据 |
| --- | --- | --- | --- |
| 1 | P1 roadmap.md 三处状态矛盾（:5/:19/:24） | **仍成立**，且扩大为 5 处 | 三处原矛盾逐一复现（见 P1-1）；新增 `roadmap.md:26`"三份提案"、`:39`"后续按 status 整理 198"；ADR-AGENT-010 仍 accepted（`adr/agent/010:3`） |
| 2 | P2 HXA-218.md:43"未合入main"与 status.md:16 矛盾 | **仍成立**（证据链由收敛记录保全） | `HXA-218.md` 边界节原文未变；git `1c1b8ab7` 为 HEAD 祖先；`branch-convergence-2026-09-22.md` 记载合并 |
| 3 | P2 TurnReducer 生产未用但文档当核心状态机 | **仍成立** | `TurnCoordinator.kt:60` 注释仍在；app/src/main 0 生产引用 |
| 4 | P2 UI 持有 storage repository（10 文件），overview.md:9 未表态 | **仍成立（减弱：7 文件）** | `grep com.helix.core.storage.repository app/.../ui/` = 7；DAO/okhttp/retrofit 0 命中 |
| 5 | 8 项关键声明核验表（QuickJS isolated 进程、PRoot/订阅私有进程、consumer 排除、STANDARD/ADVANCED 切换、32 模块、HXA-209、UI 分层、ADR accepted≠实现） | **全部仍成立** | 本次独立复证：manifest（quickjs :13-17 / proot-app :9-25 / cli-app :9-29）、`app/build.gradle.kts:129-141`、consumer 桩（ProotToolModule AVAILABLE=false、AdvancedProfileAvailability=false）、`SafetyProfile.kt:13`、`settings.gradle.kts:30-63`、`SessionPermissionResolver.kt` 等 |

## 改进建议汇总（按优先级）

1. **【P1，立即】一次性文档对账**（2026-09-22 分支收敛的收尾）：同步 `roadmap.md:5/19/24/26/39` 与 `next-work-plan.md`、`extensions.md:24` 到 status.md 当前状态；预计 <1 小时纯文档改动，消除 4 处 P1。
2. **【P1】task-experience.md §2 定夺**：实施"工作区详情四入口"页 + files→会话交接（`stageAttachment`），或改写 §2 为未交付并立项——当前状态是"文档承诺 + 落位 HXA 全绿"但入口不存在，最需要产品决策。
3. **【P2，本迭代】过期状态头清理**：terminal.md:3、mobile-tool-orchestration.md:31、overview.md:48 三处"未交付"口径改为已交付；HXA-218/219 记录补"已整合 main"注记。
4. **【P2】补文档足迹**：turn regeneration（8aa8ff97）补完成记录 + status 行；requirements.md §2.1/§7 补手动终端项。
5. **【P2】i18n 收尾**：base strings 136 个英文值转中文或修正 HXA-069 声明；check-i18n.sh 增加值语言检查。
6. **【P2，结构性】文档状态单一入口自动化**：在 `scripts/check-all.sh --source` 增加两条轻量一致性断言：(a) roadmap 表格状态列 vs status.md Completed 列表的 HXA 集合差告警；(b) 所有 `docs/architecture/*.md`、`docs/development/next-work-plan.md` 中的 "proposed/未实现/尚未合并" 关键词必须能在 ADR Status 行或 status.md 中找到对应（防 extensions.md:24 类漂移）。
7. **【P2】结构性决定**：TurnReducer 去留（合并进 BatchTurnRuntime 或标注参考实现并清测试）；UI repository 边界（文档表态或下沉）——两项与维度二结论同源，建议随架构整改一并处理。

---
*本报告为只读审查，未修改 /Users/dollars/Helix 下任何文件；辅助脚本位于 /tmp/helix_linkcheck.py。标注"待核实"项：无（所有结论均基于已读代码/文档或 git 取证）。*
