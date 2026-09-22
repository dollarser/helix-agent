# 当前实施状态

更新：2026-09-22。此页只维护当前结论、下一步和未闭合边界。具体测试数字与过程见证据，不从历史 commit/设备推断当前现场。

HXA-213 会话 fork 已在独立 `codex/session-fork` 分支交付：消息旁“从这里新建分支”，保留完整前缀、适用压缩摘要与附件引用，使用新会话权限默认值，不复制执行/Goal/审批，不回滚文件。完整主机门禁及 API29/36 × 双 flavor 定向设备 **244/244** 通过，见[完成记录](../completion-records/HXA-213.md)。已随压缩补强及 marketplace 一起整合到本地 main（8bd2d177）；本轮验证见下方收敛记录，未推送。

压缩机制补强已在独立 `codex/context-compaction` 分支完成本地验证：摘要与普通请求统一容量准入，保留同模型用量校准，历史元数据分页及活跃正文读取有界，取消/检查点语义保留，窗口默认值明确标为估算。完整主机门禁与四象限压缩/Goal 定向设备 196/196 通过，见[修复与验证记录](../bug-fixes/2026-09-22-context-compaction-admission.md)。已整合到本地 main，未推送；分支测试数字保留为原验证范围。真实模型 token 偏差、物理设备内存峰值仍不在本轮验证范围。

本轮收口：独立 `codex/acceptance-199-206` 分支从 `215b7d81` 完成 [206 本地核心产品验收](../completion-records/HXA-206.md)，四象限、同 fixture 基线对照、Git R1 debug/release 与完整主机门禁通过。199 的实际 30 分钟脱离/默认两小时租约、双 API Runtime/日志/Job/双终端、普通页面恢复及覆盖升级已验；物理 OEM/HOME/锁屏/Doze/热压与真实 16 KiB 设备未提供，199 保持未完成。证据及条件跳过见[本轮记录](../evidence/development/acceptance-199-206-2026-09-21.md)。验收与提交快检优化已通过 `995c9baf` 合入并推送 main；远端验证见[整合记录](../evidence/development/ci-staged-gate-integration-2026-09-21.md)，未发布。

本轮分支收敛：市场/签名索引、压缩、会话 fork、Connector 会话启停与对话交互规划已整合到本地 main；修复市场安装名称导致状态误判后，完整主机门禁与四象限设备 276/276 通过，详见[收敛记录](../evidence/development/branch-integration-2026-09-22.md)。后续开发顺序见[工作计划](next-work-plan.md)，不把 proposed 设计计为功能交付。

## Completed

所有已交付 HXA 见[完成记录索引](../completion-records/index.md)，M0 见[工程基线](../completion-records/M0.md)。记录的完成只限各自范围。

本轮整合交付：HXA-204跨执行域恢复与HXA-205首次准备已交付（见[204记录](../completion-records/HXA-204.md)、[205记录](../completion-records/HXA-205.md)）；HXA-193 默认锁定资产准备已收尾；HXA-195 一次性 Job 有界实时日志与详情页已完成，API29/36 各新增6项与既有35项通过、完整主机门禁通过，见[195完成记录](../completion-records/HXA-195.md)。同时修复大输出字节计数导致错误 FAILED 的问题；不包含后台 Job/PTY 或真机长稳。

最近完成：HXA-130 Connector 签名索引与来源验证（离线索引格式、ECDSA P-256 分离签名解析、默认允许降级安装与审计标记，见[130完成记录](../completion-records/HXA-130.md)），HXA-212 内置扩展市场与典型目录（9 项精选目录、类型/关键字/标签检索、端侧一键安装、生命周期恢复与成功路径的降级替换，见[212完成记录](../completion-records/HXA-212.md)；完整分支交接见[市场分支交接说明](marketplace-branch-handoff.md)），HXA-203 产物可用性与文件交付闭环（四态可用性、变更横幅、管线导出与运行中取消、无查看器外部打开、返回产生任务、四象限矩阵与批次A出口旅程，见[完成记录](../completion-records/HXA-203.md)），HXA-194 命令详情与现有结果导航（只读投影、档案优先于过期持久化输出、任务页/工具行真实入口与返回来源、四象限旅程与 202 入口回归，见[完成记录](../completion-records/HXA-194.md)），HXA-202 任务过程与跨页面操作导航（六类状态只读投影、跨会话稳定 ID 定位、CANCELLING 持久结算、四象限旅程与两阶段进程恢复，见[完成记录](../completion-records/HXA-202.md)），HXA-192 Plan 审阅到执行的用户闭环验收与 HXA-209 授权联动（四象限设备与主机 --all，见[完成记录](../completion-records/HXA-192.md)），HXA-209 会话授权预设、工具禁用与自定义权限（含工具二态与 CUSTOM 效果限制，见[完成记录](../completion-records/HXA-209.md)），HXA-208 完整 Goal 工具与前后台连续运行，HXA-201 设置/UI，HXA-200 审批审计/恢复。基线回归及 thinking/startup 修复见[基线修复](../bug-fixes/2026-09-16-pre-hxa-baseline-regressions.md)与[连接/启动修复](../bug-fixes/2026-09-16-main-thinking-and-startup.md)。不重做这些任务，不将旧三态验收用作新授权方案证明。

收尾补验：SGLang UI smoke已修复，在API29/36 developer真实端点各1项通过，本地表单各4项通过；默认profile各1项明确跳过，见[修复与证据](../bug-fixes/2026-09-16-sglang-smoke-synchronization.md)。这不关闭192/193的其他范围。

Root 专项收尾：OnePlus API35 真机生命周期、重建、撤权/拒绝与真实 App 工具链已验收，见 [HXA-094](../completion-records/HXA-094.md)、[HXA-095](../completion-records/HXA-095.md)。已通过 `32788bf8` 合入 main，真实 App Root 工具链复核 1/1；历史 11 项失败已在 49/49 定向回归中通过。P0 独立修复基线的真机普通套件 450 PASS / 69 条件跳过 / 0 FAIL，存储分阶段及 Root 工具链另验通过；批次 A 整合证据与范围见 [P0 基线修复](../bug-fixes/2026-09-17-physical-p0-baseline.md)。条件跳过、长稳及发行项不算通过。

会话搜索切片已整合：标题/消息正文有界只读搜索、归档命中、清空与打开结果；HXA-191 已完成主题与搜索最终四象限，见[完成记录](../completion-records/HXA-191.md)。193 已补 API29/36 实际旧版→新版 debug APK 覆盖安装，会话/配置/结果保留及旧锚不激活通过，见[覆盖升级补验](../evidence/development/apk-replacement-upgrade-2026-09-18.md)；默认入口已改用固定归档并通过双 API Runtime 与完整主机门禁，见[193完成记录](../completion-records/HXA-193.md)；显式旧包镜像重建仍失败，不作为通过项。前次整合修正及验证见[整合记录](../evidence/development/integration-193-191-2026-09-18.md)。

## Planned：对话交互优化

2026-09-22 所有者授权将调研收敛为需求，尚未执行代码实现。新增 [214](tasks/HXA-214.md)（P0发送回执/草稿/停止修复）→ [215](tasks/HXA-215.md)（P1编辑分支重发）→ [216](tasks/HXA-216.md)（P1统一Queue/Steer）→ [217](tasks/HXA-217.md)（P2请求清单/JSONL）。默认普通发送排队、显式选择转向，发送本身不自动取消Goal；对应三份 proposed 见[Agent ADR入口](../adr/agent/README.md)。215～217实施前接受对应提案；008接受时同步修订Goal新输入条款。214可先按既有契约修复，不偷跑队列。

新增4项设计任务独立于历史12项未闭合义务；不重复开发已交付213/211，不关闭外部设备、账号和发行缺口。文档门禁只证明规格一致，不能当作发送/编辑/转向已上线。需求文档已合入本地 main，尚未实现或推送。

## In progress

HXA-126 已由所有者授权修复并整合预注册 public-client OAuth 切片；合并前缺陷与当前验证见[修复记录](../bug-fixes/2026-09-21-connector-oauth-merge.md)。保留两家真实服务与动态注册未完成范围，不提前关闭任务。

CI 分层优化已合入 main，完整远端五个 job 通过，见[验证记录](../evidence/development/ci-scoped-gates-2026-09-20.md)；小模型准备与198双终端已整合，见[整合记录](../evidence/development/hxa-198-main-integration-2026-09-21.md)。本轮206本地核心范围已完成，199只剩物理专项；验收分支及快检优化已合入并推送 main，本轮远端结果见[整合记录](../evidence/development/ci-staged-gate-integration-2026-09-21.md)。排序与分工见[工作计划](next-work-plan.md)。main 已整合 191、197、207、211 及 196 当前实现；196 真机缺口不记通过。

HXA-197 单个手动 PTY 已交付，见[完成记录](../completion-records/HXA-197.md)。本轮修复快速逐键输入丢字符，补齐关闭页面后的 1 MiB 以上输出、超限输入、Ctrl-C、运行中租期，以及普通应用主进程/Runtime 死亡和实际重启对账。完整主机门禁、双 API 各 49/49、独立恢复旅程 2/2 通过；准备测试另计，详见[最终证据](../evidence/development/hxa-197-recovery-closeout-2026-09-20.md)。未把 30 分钟 idle、长租期、OEM/Doze/热压或真实 16 KiB 设备计为通过，这些继续归 199。

HXA-211 会话 JSONL 导出已交付，见[完成记录](../completion-records/HXA-211.md)。完整主机门禁、存储/应用导出 JVM 19 项、独立解析器 7 项、应用四象限 36 项与存储设备 16 项通过；覆盖实际 DocumentsUI、窄屏大字体、真实进程中断、取消和资源上限。具体制品与边界见[最终证据](../evidence/development/hxa-211-export-progress-2026-09-20.md)。191、197 已完成；196 本地实现和模拟器阶段已收敛，真机 HOME/锁屏/Doze 保留待验，不记整体完成。

2026-09-19 所有者建立持续开发 Goal，并于 2026-09-20 更新范围：完成 **196 后台 Job → 191 主题收尾 → 197 单手动 PTY → [211 会话 JSONL 导出](../completion-records/HXA-211.md)**，随后只生成 **198、199、206 产品集成验收的任务计划**，不直接实施这三项。191、197、211 已交付，196 真机未验，本次按所有者指示跳过；此为2026-09-20的历史范围；所有者随后授权198实现、返修与本轮合并收口，198现已交付，199/206继续独立验收。每次仅一个 HXA 进行中，进入下一项前解决强制门禁的已知失败。外部设备或账号缺口独立记录，不计为通过；不阻断无该依赖的独立工作。191/207 的具名交付已整合到 main。

196 已完成 developer 四工具注册、持久执行占用、Goal 预算、原结果导入、独立 Tasks 行及人工查询/取消/收取入口；consumer 不注册。模型/Goal/Dispatcher/Runtime 旅程及终态结果跨主进程恢复已有设备证据，pending 租期禁止提前报告完成。历史投影、Tasks、Runtime、Goal 矩阵保留在 [196 任务记录](tasks/HXA-196.md)，不作为最新源码的全量重跑证明。当前仍不是完整后台产品验收。

所有者已授权继续剩余任务。**HXA-196** 的租期、私有前台 owner、幂等提交/取消与恢复查询已合入并推送 main；平台/核心验证及未接入产品的边界见[196 切片记录](../evidence/development/hxa-196-platform-plan-2026-09-18.md)。已整合订阅终态流式传输与后台提交扣时修复，见[Runtime 修复](../bug-fixes/2026-09-18-runtime-streaming-terminal-and-lease.md)；已接入执行线程占用和持久身份机制，完整主机门禁与四象限定向 20 项通过，见[执行占用切片](../evidence/development/hxa-196-execution-ownership-2026-09-18.md)。异步工具与预算/结果回收组合已合并并推送 main；剩余验收见上，不能将当前切片视为完整后台功能。

**207 与 191 的具名提交已合入 main。** 207 的扩展旅程交付见[完成记录](../completion-records/HXA-207.md)，fixture 与 consumer 条件跳过仍按原边界保留。191 已完成 API29 真实深色、订阅内容/系统栏及对话框验收，见[完成记录](../completion-records/HXA-191.md)；[分支交付陈述](../evidence/development/hxa-191-delivery-review-2026-09-18.md)保留为证据，不作为整体完成记录。历史定向矩阵见[整合验证](../evidence/development/merged-191-207-runtime-2026-09-18.md)；206 后续已获实施授权并完成，不再受当时“仅规划”限制。

本轮交付 HXA-209 会话授权（四象限设备与主机 `--all` 全绿，见[完成记录](../completion-records/HXA-209.md)）与 HXA-192 的 Plan 审阅→执行闭环及 209 授权联动（见[完成记录](../completion-records/HXA-192.md)），并按所有者决定处理网络执行域候选：同 APK isolated UID 不直接支持现有 PRoot 的 RootFS/工作目录，保留当前 Runtime、撤下统一禁网要求，具体工具禁用与文件/远端写限制仍有效（见[验证记录](../evidence/development/isolated-proot-feasibility-2026-09-16.md)）。批次A（202/194/203）已交付，批次B HXA-204/205 与 HXA-195 已交付，HXA-207 已交付，191 已交付。该次历史复核的12 项未闭合义务分为：3 项收尾验收、2 项集成验收、3 项待决策、4 项发行队列，分类与依赖见[任务索引](roadmap.md)。有代码无完整验收不等于从零待做，proposed 且未立项的方案不计入任务数。

## Next task

本轮整合验证完成后，先实施 [HXA-214](tasks/HXA-214.md) 的发送回执、草稿保留及停止一致性。同步审查 ADR-CONNECTORS-003，接受后按 [HXA-129](tasks/HXA-129.md) 完成安全替换和会话独立启停；再推进 215 → 216 → 217。具体切片、设计门槛与资源分工以[工作计划](next-work-plan.md)为准。本次只规划，不自动启动新功能。

## 已整合能力的历史验收边界

运行中主进程死亡已完成有效验证：普通应用页面在 Act 模式启动 Job，宿主只杀主 PID，原 Runtime PID 与 boot_id 不变，原 Job 完成后两次收取无重放。最新完整主机门禁通过；双 API 最终旅程 **2/2**，准备 **2/2** 单列。该证据替代会因 instrumentation 收尾杀包而失真的诊断方式；仅覆盖普通 Act 会话，Goal 计费和 OEM 真机边界不扩大。详见 [196 任务记录](tasks/HXA-196.md)。

191 最终主题矩阵通过：developer 双 API 深浅模式 **32/32**；consumer **28/28** 且最终主/测试 APK 哈希与验收时一致。已覆盖真实对话框、六个订阅页面配色与实际系统栏标志，修复大字体返回箭头裁切；完整主机门禁通过，见 [191 记录](../completion-records/HXA-191.md)。API29 使用自有模拟器 root shell 设置被锁定的系统夜间模式，没有赋予应用权限。最终搜索 UI/真实进程恢复 32/32、Room 16/16 通过，191 已关闭。197 的单终端、实际进程死亡对账、页面退出重连、输出/输入压力和运行中租期验收已完成，211 会话 JSONL 导出也已交付，后续交接计划已写入 198／199／206 任务文件。主题与搜索是应用能力，不新增 Agent 工具。

196 已补齐原 Runtime 记录缺失出口：明确 NOT_FOUND、原 Turn 终态或 INTERRUPTED 且真实重启证据成立后，收取结算预算并解除占用，保留 UNKNOWN 和结果丢失提示，不生成终态或重跑。最新完整主机门禁通过，双 API 实际重启缺失记录验证 **2/2**、收取/Goal/Runtime 死亡回归 **20/20**。真机继续独立待验。并行交付中的三工具实现因效果分类、错误/取消回执和缺少持久占用/Goal 结算问题未直接采纳，旧 WIP 已在分支收敛时按实际差异取舍，历史原因见[交接复核](../evidence/development/hxa-196-handoff-review-2026-09-20.md)。

2026-09-18 审查修复已落代码：会话权限快照与 v23 迁移、权限修改/审计事务和并发编辑、SAF 来源移除、浏览器标签超限、目录截断提示、模型列表读取失败及订阅 Runtime 有界预览/落盘/失败结算。原问题与取舍见[审查复核](../evidence/development/review-followup-2026-09-18.md)，实施与验收见[修复收敛记录](../bug-fixes/2026-09-18-authorization-runtime-convergence.md)。不恢复 ADR 已撤销的订阅累计配额；终态完整结果物化与真机资源压力仍有验证边界。

207 与 191 经所有者授权实施后已全部交付并合入 main。小模型准备切片与198双终端已整合；206的完成由本轮实际执行支持，199报告工具与模拟器证据不替代物理专项。

本轮合并后完整主机门禁通过；API29/36 × consumer/developer 修复/Runtime 定向矩阵共 244 项 instrumentation（含 2 项探针准备），另有 2 次独立主进程死亡检查通过，独占设备全部正常退出。此证据不等于 206 全产品验收，详见上述收敛记录。

远端 CI 收尾已完成：main `5bfce200` 已推送，[35331054907](https://github.com/dollarser/helix-agent/actions/runs/35331054907) 的 source、runtime-assets、两条 Android 门禁与 verify 全绿，包含前次合并的 CI 并行与诊断优化。此为历史验证；优化前最新 main `382674c3` 的 [35515226699](https://github.com/dollarser/helix-agent/actions/runs/35515226699) 五个 job 已全部成功，CI 调整需单独重新验证。

基线优先：[P0 真机收尾](../bug-fixes/2026-09-17-physical-p0-baseline.md) 已完成固定源码验收，并与批次 A 整合。后续 HXA 开始前仍须核对当前源码与并行改动，解决新的强制门禁失败；历史证据不代替修改后的验证。

CI 收尾：PR #1 已合入远端与本地 main；已验证提交 `437f8d49` 的两个远端任务全绿，合并提交 `03617128` 内容一致。批次 B 已同步此基线。HXA-193 选定旧/新 debug APK 的覆盖升级已补验，默认资产准备已收尾；显式旧包重建的供应边界仍保留，见[CI证据](../evidence/development/ci-runtime-assets-2026-09-17.md)与[覆盖升级补验](../evidence/development/apk-replacement-upgrade-2026-09-18.md)。

大型开发按[统一交接Prompt](implementation-guide.md)交接；具名切片可本地提交，不push/合并/发布。

批次 B 开发前的本地收尾、验证范围与 Claude Code 交接见[2026-09-17 准备记录](../evidence/development/batch-b-readiness-2026-09-17.md)。HXA-204/205 已按顺序切片明确交付边界；JSONL 导出已由所有者在本会话授权实施，已由独立 HXA-211 交付，见[完成记录](../completion-records/HXA-211.md)。

- **后续计划：[206](../completion-records/HXA-206.md) 本地核心范围已交付，[199](tasks/HXA-199.md) 待物理设备**；191、197、198、211 已交付，196 真机历史豁免未计通过。193、195、204、205 均已交付，各任务证据与范围见完成记录。
- **执行环境后续**：196后台Job与197手动PTY→198多会话→199终端验收；197不等待196。193资产/升级/CI、194命令详情、195实时输出不跟随完整终端后移。
- **条件允许时收尾**：190 真实订阅、125 受保护 Connector；其外部设备/账号项不阻塞无依赖的本地功能。发行按 120→122→121→123，不自动开始外部提交。
- 会话独立目录仍为 proposed [ADR-WORKSPACE-004](../adr/workspace/004-workspace-binding.md)，不自动启动 HXA-210；Connector 版本/索引候选及工具 descriptor 候选不因整理而接受。

## Blocked

| 项目 | 缺少条件 / 决策 |
| --- | --- |
| HXA-199 终端物理专项 | OEM/HOME/安全锁屏/Doze/热压/物理长稳与真实 16 KiB 设备未提供；双 API 模拟器、实际默认时长、覆盖升级及普通双 shell 恢复已验，不重复用模拟器替代真机 |
| HXA-125 受保护 Connector 服务验收 | WorkBuddy 来源样本已补齐；仍需独立测试账号验证凭据无效、权限拒绝、厂商撤销和重连；匿名服务与 fixture 不替代这些证据 |
| HXA-126 外部验收 | [ADR-CONNECTORS-002](../adr/connectors/002-oauth.md) 已接受；仍缺两家独立服务账号、App 注册与 redirect 条件，动态注册未交付 |
| HXA-129 | [ADR-CONNECTORS-003](../adr/connectors/003-ownership-and-installation.md) 已收窄为安全替换、安装归属与会话启停，仍待审查；未实现 |
| Claude / Grok 真实付费调用 | 账号不可用，按所有者决定暂缓；本地与设备夹具通过不代表真实账号验收 |
| 发布验收 | HXA-122 尚待稳定 applicationId、渠道命名、升级路径与签名发行决策 |

## Current interfaces

- **上下文/预算优化**：聊天正文不再重复工具协议；大成功结果可按会话只读分页；上下文、Turn 与 Goal 使用统一输入估算；预算维度与压缩诊断分别保留；普通预算停止可明确从已有结果新建有界 Turn。已有自定义额度不自动更改，Goal 与未知副作用恢复路径保留。设计见 [ADR-AGENT-006](../adr/agent/006-model-data-budget-boundaries.md)，定向验收及与批次 A/P0 的整合证据见[修复记录](../bug-fixes/2026-09-17-context-budget-tool-projection.md)；专项模拟器回归不替代新版本的真机验收。

- **聊天与装配**：`ChatService` 保留应用入口及运行协调，草稿、请求组装、附件重试、界面投影、工具调用与结算已有独立组件；`AppContainer` 接口与 `DefaultAppContainer` 组合根分离。资源与可变状态仍由原所有者管理，拆文件不产生第二套状态源。详见 HXA-179/183/184。
- **Goal 与后台任务**：Goal 采用 ADR-GOAL-001；暂停可由用户继续，blocked 表示不能主动继续。后台能力是有界工具任务及结果回收，生产子 Agent、Agent graph 和声明式 Workflow 尚未实现；ADR-AGENT-004 的只读 Spike 不等于产品启用。
- **手动文件管理与 Agent 工具**：两者范围分离。手动界面支持 Workspace、共享存储和可写 SAF 的新建、改名、同来源复制/移动与删除；跨来源继续使用导入/导出。手动授权不扩大 Agent resolver，不能把手动共享根视为 Agent 已获授权的文件范围。边界以 HXA-180/182 与对应 ADR 为准。
- **执行与工具**：QuickJS 使用非导出的 isolated Service；当前实现的 PRoot/CLI 按 ADR-RUNTIME-001 内置于 developer APK，在私有进程共享主 UID，通过私有 Binder/PFD 通信，consumer 排除。`read`、`write`、`edit`、`files.*`、`bash` 继续经过既有 scope、Policy、审批、限制、验证和审计路径；PRoot/CLI 按需冷绑定，不因被动刷新启动。
- **扩展与 Provider**：MCP、Skill、A2A Client 已按 M7 矩阵验收；A2A 是用户配置的外部服务，不是本地子 Agent。订阅凭据由订阅模块持有，正常 API 不返回 token；共享 UID 不构成凭据隔离。developer/Advanced 接线不代表 consumer/store 开放或厂商官方支持。
- **浏览器**：WebView 由浏览器功能持有并绑定 Activity owner；下载队列和结果映射已分离。真实 AutofillService 的 fill/save 回归通过，不能据此关闭系统 JNI/Binder 累积问题。

## Known limitations

这里只列仍有效的范围限制和验收缺口；已修复缺陷、旧测试数量和机制演进保留在完成记录，不再列为当前故障。

- **系统与长稳**：应用侧释放路径和短回归已有证据。模拟器侧 24 小时长稳已按可采维度跑完：EV-02 两臂完成（API36 a11y 重尾归因 + API29 功能满绿，system-Binder 模拟器不可采）；EV-03 应用 FD 门禁为模拟器固有 goldfish 节点（X 类，非应用泄漏）。但**系统 JNI/Binder 根因仍 open，权威资源/Binder 门禁需真机**（模拟器无法关闭 goldfish FD 与 UID-proxy Binder 两维）。见 [释放路径调查](../evidence/development/native-reference-release-trace.md)、[浏览器引用验证](../evidence/development/browser-controller-reference-verification.md) 与 [优化待办](../evidence/development/main-optimization-todo.md)。
- **Root 真机**：094/095 在 OnePlus API35 完成专项验收；其他 OEM、Doze/热压力与长稳随发行矩阵继续。历史 11 项及后续全套失败已在 P0 固定源码上修复并重验，详见 [P0 基线修复](../bug-fixes/2026-09-17-physical-p0-baseline.md)。整合后的模拟器回归不冒充新的真机全套；条件跳过与其他 OEM 仍按各自验收边界处理。
- **设备覆盖**：API29/36 模拟器及历史 API34/35、16 KiB 模拟器证据不替代物理低内存、OEM、热压力、Doze、安全锁屏和 Root grant/revoke/loss 验收；x86_64 静态制品证据也不等于实际运行。
- **文件恢复**：HXA-182 实现显式对账恢复，不承诺字节偏移续传、断电事务、自动后台队列或跨 Provider 原子事务；既有 picker 导入/导出及旧版无日志暂存不在该恢复管线内。目标/备份变化时保留人工核查，云盘厂商与全部中断阶段仍需外部设备验收。
- **模型与附件**：导入成功不等于模型理解。图片受实际模型视觉能力与端上预算约束；既有文本/图片管线不代表任意文档、音视频解析或 OCR 已实现。真实服务可用性与连接参数需要按服务当前状态验证。
- **后续功能**：已有只读 Git 状态/diff 界面；完整持久仓库写操作、remote Git/凭据，以及生产子 Agent/Workflow 尚未据此交付；接受 ADR 不构成实现证据。Connector OAuth 已按所有者授权整合本地切片、保留外部验收；版本管理与市场扩展未据此启动。
- **发行**：Standard 完整产品形态是 ADR-PLATFORM-001 的决定；当前 consumer/developer 构建与 CI debug APK 不是签名 release、完整渠道权限申报或商店审核证据。
- **测试条件跳过**：HXA-184 的 8 项 JVM 跳过需要 supplied Connector/WorkBuddy 与外部验收材料；每台设备的 2 项浏览器跳过需要显式长稳/诊断参数。均未计为通过，具体条件见 HXA-184。
- **外部依赖边界**：默认门禁不依赖真实业务服务、账号或付费调用；显式 profile 的缺参跳过不算通过，启用后失败如实记录。构建下载、本地测试服务器与外部 smoke 的边界统一见[公共验收规则](verification-matrix.md)。

本轮整合：Codex的193/195与Claude Code的204/205已合入并推送 main，远端 CI 全绿，未发布。完整主机门禁通过，合并后设备矩阵102通过、2条件跳过、0失败，见[合并验证记录](../evidence/development/merged-193-195-204-205-2026-09-18.md)及后续[远端收尾](../evidence/development/ci-parallel-gates-2026-09-18.md)。分支原有证据与本次合并后验证分开记录；Claude工作区未提交脚本原样保留。

2026-09-20 所有者明确要求跳过本次 196 真机 HOME／锁屏／Doze 验收，继续整合当前分支到 main、推送并验证远端 CI。该豁免解除本次交付阻塞，不是物理设备验收通过；保留 HXA-196 的未验证边界，该豁免不替代198／199／206各自验收。
