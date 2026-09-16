# Helix 实施状态

更新时间：2026-09-16（基线修复与本地主机、设备验收更新）。历史主线快照：HXA-161～184 已提交并快进合入本地 `main`（`81d60e6`），当时未推送；当前 Git 状态须接手实查。

Harness 新接手统一读取[实施导航与交接 Prompt](harness-implementation-handoff.md)。先核对192/193当前收尾，后按依赖推进产品、扩展与终端任务；下方190/191历史条目不指示接管main并行工作。旧内嵌 APK 安装、独立 UID 和订阅 UID 描述属于当时方案；本工作树已由 ADR-0049/HXA-193 替代，历史证据不能当新形态验收。

## Current summary

2026-09-16 main集成：所有者授权将Harness `69182f53` 合入main并退役重复工作树，冲突以Harness为准；必要本地证据及Runtime资产已复制并逐文件校验。验证、归档位置与退役步骤见[main集成记录](harness-main-integration-2026-09-16.md)。下文“未合并”“只在Harness”的表述保留历史含义；未推送，外部/发行验收未因此完成。

2026-09-16 WIP接手：所有者已授权本任务接手并提交此前保留的实现和文档，含单APK Runtime、测试语言修正与门禁重构；不再等待原并行所有方。完整`check-all.sh --all`通过，Runtime专项API29/36各2通过、0跳过，见[接手记录](wip-takeover-2026-09-16.md)。下方192/193的旧27 detekt、12 lint、JGit阻断和“待所有方提交”均为历史状态。Plan用户闭环、远端CI资产来源、真实账号及发行仍分别记录，未因此关闭整个192/193。

2026-09-16 基线修复完成：原18项设备失败及复测发现的问题已修复；主机4399通过/8条件跳过/0失败，全量构建、lint、制品检查与API29/36双flavor本地完整套件通过，详见[修复与证据](../bug-fixes/2026-09-16-pre-hxa-baseline-regressions.md)。证据针对保留并行WIP的实际工作树，真实账号和长稳仍独立记录。每个下一HXA先清理已知必过门禁；依赖允许为兼容性升级并更新锁文件和验证材料，不能以固定旧版本代替兼容性修复。

2026-09-16 HXA-201 已完成本任务范围验收，见[完成记录](../completion-records/HXA-201.md)及[当时验收证据](hxa201-acceptance-2026-09-16.md)。已补齐会话/Workspace设置、旧卡契约、实际保存反馈与真实恢复；P1/P2/P3及四象限专项通过。当时全套API29 consumer的18项失败现已按上方记录修复。下方201切片和200 gap均为历史快照，其旧“未完成”、无契约哈希、宽度变化等于旋转和JGit依赖漂移判断不再作为当前结论。

2026-09-15 HXA-200 已完成本任务范围验收，审计、停止/恢复和JGit门禁问题已修复，见[完成记录](../completion-records/HXA-200.md)。产品链下一项为HXA-201工具设置与审批卡，后端依赖已满足；其他并行HXA状态不变。下方旧复核与gap条目仅为历史证据。

最近的有界真机回归与修复见 [HXA-186](../completion-records/HXA-186.md)、[HXA-187](../completion-records/HXA-187.md)、[HXA-188](../completion-records/HXA-188.md)。[HXA-189](../completion-records/HXA-189.md) 已完成审查复核、源码修复与主机门禁；新增设备用例待 Claude 独占执行。完成记录仅代表各自范围，不等于全部设备/长稳/发布验收。EV-02 的 API36 24h 功能中止（a11y 重尾，非产品），API29 一臂已跑满 25h 出 INCONCLUSIVE（system-Binder 模拟器不可采），两臂均不能记为 24h 门禁全绿。EV-03 应用 24h 资源门禁：隔离复跑 pilot（单开 5558）仍 FAIL_RESOURCE，`128→137`（+9 全落在 `/dev/goldfish_pipe_dprctd` QEMU 虚拟驱动节点，真机无；逐 fd readlink 证实所有真实资源描述符不变），判定**模拟器固有（X 类）**，非应用泄漏、非 3 开 flake，不进 2h/24h，权威 FD 判定需真机；threads/PSS 均在门禁内。

| 最近交付 | 当前状态与证据 |
| --- | --- |
| 会话、文件界面与上下文 | HXA-161～178 已交付；支持会话管理、模型/推理选择、上下文显示与压缩、非阻塞后台工具任务及结果回收 |
| Goal 完成机制 | [ADR-0040](../adr/0040-model-judged-goal-completion.md)：模型提交结构化完成报告，Harness 校验报告有效性并处理运行状态和结算；已取代 ADR-0028 的强制条件绑定与独立 verifier |
| 独立文件管理 | [HXA-180](../completion-records/HXA-180.md)：Workspace、共享存储和可写 SAF 的手动变更；[HXA-182](../completion-records/HXA-182.md)：复制/移动中断后的持久记录与显式恢复 |
| 职责整理 | HXA-179～181 已收口；[HXA-183](../completion-records/HXA-183.md) 完成七项优先拆分，[HXA-184](../completion-records/HXA-184.md) 完成 B11/C15 提取与装配整理 |
| 最新主机回归 | [HXA-189](../completion-records/HXA-189.md)：2788通过/8条件跳过，Debug/Release构建与全量lint通过；新增设备回归尚未执行 |
| 历史全量回归 | HXA-184：主机 2788 通过、8 项外部条件跳过；API29 consumer / API36 developer 各 394 通过、2 项浏览器长稳/诊断条件跳过；自建模拟器均已关闭 |

系统 JNI/Binder 根因、长稳、完整真机矩阵与商店发布仍未关闭。历史主线验证见 [main 验证报告](main-merged-verification.md)，后置测试见 [优化待办](main-optimization-todo.md)。

## In progress

HXA-201：2026-09-16已关闭；本轮无未完成的201切片。其他所有方任务保持原状态，下方旧收尾条目仅用于追溯。

2026-09-15 HXA-200 C1/C2 修复：跨scope按DENY > ASK > ALLOW，审批返回后在与偏好写入共用的短时边界重验并承诺执行开始；旧卡批准不覆盖后来的DENY，等待/执行不持锁。主机及设备验证见[修复记录](../bug-fixes/2026-09-15-tool-preference-start-boundary.md)。下方收尾审查为修复前快照；不再把C1/C2列为待开发，其他矩阵与P3门禁仍独立记账。

2026-09-15 HXA-200 收尾复核（`fa01dde6`）：**尚有真实契约偏差，不能只补文档关闭**。Gap 1 的窄范围ALLOW覆盖外层ASK与ADR-0052第5条冲突；Gap 5 的等待审批后DENY仍执行与第7条冲突。六个gap提交记录保留为历史证据，不代表全部契约验收通过。修复与HXA-201交接见[收尾复核](hxa200-closeout-review-and-hxa201-handoff.md)。本轮仅源码复核，未重跑设备或lint；201独立UI工作可推进，相关执行集成须等待修复。

HXA-193（2026-09-14）：developer 单 APK 内置 Subscriptions/PRoot 的实现与本地专项验证完成；[方案与证据](integrated-developer-runtimes.md)。API29/36 同组各32项、库host6项通过；四个主APK构建与排除边界通过，旧锚不继承，订阅重新登录，两个私有进程共享主UID。全量门禁 spotless/detekt 已过、App lint 剩 2 项第三方 JGit TrustAll 阻断（独立决策，未 suppress），CI锁定资产准备与真实账号/发行验收未闭合；不标记整体验收完成。改动仅在Harness工作树，未提交、推送或合并。后续收尾与HXA-192 R1～R4对齐，旧跨APK脚本不作为新形态验收。

HXA-192（2026-09-14）Harness 分支专项：R1～R4 已推进。v16 迁移改为「删索引→全量加前缀→重建索引」（修正 scope: 前缀误判与唯一索引过渡冲突），CLI 旧契约测试按生产契约修复，CLI 162 / storage 89 主机回归通过；两工作区国际化漏扫已修、Harness 五缺翻译补齐。spotless/detekt 已过（27 detekt 债按行为保持重构清零），App lint 剩 2 项第三方 JGit TrustAll 阻断（独立决策，未 suppress/未降 TLS）。独占 API36+29 设备：RoomMigrationFixtureTest 各 28/28、PlanSubmitIntegrationDeviceTest 各 4/4（生产 Registry/过滤/Dispatcher + 真实 Room，无 Fake PlanRepository）。完整 check-all --all：source_checks 过，build_checks 过 spotless/detekt/test 后在 lint 因 2 项 JGit fail-fast，assembles/lockfiles/artifact 未达。ADR-0048 已接受架构，但其启用门禁（UI 级用户闭环、审阅不 mint 审批的设备证明）仍未通过，HXA-192 未关闭。核心 plan/storage 切片已本地 commit `0d52eae7`（未推送/合并）；R1 detekt/i18n/CLI 与 ADR/研究归档/文档随多任务 WIP 待单独提交。产品源码在 `worktree-harness-2.0`，不将局部通过写成整体验收。

HXA-190 main 缺陷复审（2026-09-13）：复核四维审查，修复跨会话审批取消、附件忙碌拒绝丢失、CLI 初次绑定无限等待、A2A 过期写、工具前说明持久化、探测流上限与局部 Runtime 生命周期问题。仅在 main 修改，不操作 Harness 2.0 worktree 或模拟器；主机验证和待设备项见 [复核记录](../bug-fixes/2026-09-13-main-audit-followup.md)。不将审查推测视为长稳资源根因，不做大类迁移。

HXA-190 内置组件安装：developer 主包现在自动嵌入同批 Subscriptions 与 PRoot APK（主包约 111 MiB），设置页用户点击进入安装来源授权和系统安装确认；固定包名/同签名校验、独立 UID 与既有文件共享保留。4 项签名策略主机回归、双 flavor 构建/lint、嵌入字节/实际三包签名/合并 manifest 核验通过。consumer 保持原接线；Release 需先配置组件发行签名。未操作设备、安装、提交或推送，安装/升级流程待人工验收。见 [实现记录](bundled-runtime-installers-2026-09-10.md) 与 [ADR-0047](../adr/0047-bundled-companion-installers.md)。

HXA-190/191 Goal 入口简化：选择 Goal 后直接发送任务即可创建并启动；同会话未结束目标的后续消息沿用原预算继续，阻塞不新建替代目标。默认预算与当前目标额度/提醒集中到设置，去除目标/补充说明创建表单；默认 128 模型调用、256 工具调用、400 万累计 token、2h 总执行、30min 单次、0 失败 wake 重试，实际请求继续受模型与 Turn 限制。25 项主机回归、双 flavor 构建/lint、设备用例编译及文档/i18n 通过；全局 detekt 仍有 21 处其他告警，本轮 Goal 接线告警已清理。未操作设备、未安装/提交/推送，界面与真实任务待所有者人工验收。详见 [设计与验收](goal-conversation-entry-2026-09-10.md)。

HXA-190/191：按所有者“只验证订阅账号”要求，Codex 两处连接检查改为认证目录请求，不再选择模型生成；设置多按钮区自适应换行并分组，审计筛选换行、记录摘要与详情分离。33 项主机回归、三项目标 lint、developer/consumer/Subscriptions 构建及文档/国际化门禁通过；设备已拔掉，本轮未操作真机或模拟器，视觉效果留待所有者后续验收。决定为部分替代 ADR-0044 的 [ADR-0046](../adr/0046-subscription-account-connection-check.md)，详见 [实现与人工检查项](account-connection-and-settings-layout-2026-09-10.md)。

HXA-190 订阅测试错误提示已修复：极小测试保留当前错误分类、收到完成事件后不再等待 EOF；主应用说明订阅网络/本地组件连接两种失败可能，连接通过不再误报能力已检测。32 项主机回归通过；PLC110 极小测试与主应用实际界面连接测试通过，凭据与 DNS 未变更。初始短暂 I/O 根因仍未确定，自动 instrumentation 的 BIND_REFUSED 未计为通过；详见 [修复与证据](../bug-fixes/2026-09-10-subscription-test-errors.md)。

HXA-190/191 DNS 与 Turn 默认值已更新真机：DNS 页面预填可编辑的 chatgpt.com 映射（已有配置优先），当前手机通过设置页保存为 24h，过期恢复系统 DNS。Turn 默认 32 工具步骤/48 模型调用、单次输入 1M/输出 16384、累计 1M；实际仍受所选模型窗口与 Goal 额度约束，移除请求组装隐藏的 4096 上限。旧默认模板升级、自定义组合及 v3 主动回退保留。21 项针对性主机回归、两包构建通过，已覆盖安装并返回 Helix；未自动运行真实模型任务。详见 [默认策略与验证](dns-and-turn-defaults-2026-09-10.md)。

HXA-190/191 写入与工具行精简：write v3 兼容空可选 hash，非空 hash 在目标缺失时也严格校验，非法值给出字段专属错误；新建不需要 hash，覆盖/edit 防护保留。会话工具行直接显示名称、操作目标与状态，参数/结果默认隐藏，保留详情、待审批及恢复入口。18 项 WriteToolTest + 1 项用途摘要测试、developer 构建及 i18n 检查通过；已覆盖更新真机，UI 由所有者人工验收。布局设备用例已同步但未运行，未操作共享模拟器、未提交推送。见 [记录](../bug-fixes/2026-09-10-write-hash-and-tool-timeline.md)。

HXA-190 本轮上下文与工具结果修复：只读真机记录确认摘要调用输出 2351 tokens 超过摘要目标额度而误判预算失败；现将目标长度与受用户预算约束的调用额度分开。Spark 目录已含 128000，圆环却只读设置表默认 200000；现与请求路径使用相同逐模型元数据合并规则，不写死模型值。参考 Pi、OpenCode、DeepSeek Harness 完成 [60 项内置工具返回梳理](builtin-tool-result-review-2026-09-10.md)，6 项文件结果移除内部配额/重复常量，必要 hash、正文和恢复标识保留，完整结果仍存 Harness。19 项针对性 JVM 回归、developer 构建、文档检查与 diff 检查通过，已覆盖安装当前真机并启动；真实压缩/模型任务由所有者人工验收，未运行模拟器、未提交推送。见 [修复记录](../bug-fixes/2026-09-10-compaction-budget-and-model-window.md)。

HXA-190 最新协议修复：新增 SYSTEM 上下文直接作为 input message 被 Codex 订阅端拒绝；已转换为顶层 instructions 并保留工具别名单次映射。当前真机同网络同合成输入对照：旧格式 HTTP 400（system），修复格式 HTTP 200 并完整结束。另修复重复重试没有 USER 行导致请求末尾校验失败。5 项主机回归及 1 项设备对照通过，两包已覆盖安装、测试 APK 清理，原会话保留供所有者人工验证。详见 [记录](../bug-fixes/2026-09-10-codex-system-instructions.md)。

HXA-190 文件输入统一（ADR-0045）：12 项内置文件工具的模型接口统一相对路径说明；`path/source/destination` 在审批和规范参数哈希前绑定会话工作目录，无目录时用默认 Workspace 根。普通根目录文件可写，`.helix/`、越界、配额和覆盖/hash 防护保留；MCP 参数不改写。公共模板位于 `app/src/main/resources/prompts/`，按工具曝光/模式选择并注入真实工作目录。207 项相关主机回归通过、模板 APK 打包检查及 developer 构建通过，已覆盖安装真机；真实模型任务继续由所有者人工验收，未提交推送。

HXA-190/191 文件任务与审批精简已更新真机：最新 8 轮失败由路径格式/工作区 ID 猜测造成，新增实时 Workspace 系统上下文和工具纠错提示；默认工具轮次 32、模型调用 33，旧默认迁移，自定义额度保留。审批摘要及活动工具记录默认折叠，完整信息可展开；待批准操作仍直接可用。41 项相关 JVM 回归、developer 构建通过并覆盖安装；真实写文件任务由所有者人工验收，未运行模拟器或自动模型任务。详见 [记录](../bug-fixes/2026-09-10-file-task-context-and-approval.md)。

HXA-190 本轮 Act 修复：真机最新会话的 `write` 空参数已被工具层拒绝，但旧历史构建又因空字符串抛异常，导致后续新消息持续 `INTERNAL`。现已统一空参数归一化并兼容读取旧记录，保留调用/拒绝结果，不删除会话；Responses 参数完成事件补齐未收到的后缀，矛盾参数仍拒绝执行。双方消息均有气泡，复制位于气泡外下方，历史错误保持红色；网络错误提示增加 DNS/代理排查。28 项解码器及 14 项历史构建回归通过，两包构建通过；实际连续 Act/长回复由所有者人工验收。详见 [修复记录](../bug-fixes/2026-09-10-act-history-recovery.md)。

HXA-190 新增手动 DNS 覆盖已实现并安装：Helix Subscriptions 首页的“网络设置 · 域名解析”支持精确域名、多 IPv4/IPv6、1h/24h/7d 有效期及编辑/删除/清除；覆盖仅在订阅 UID 内生效，保留 URL/SNI/证书验证，不需要 Root。新手机已通过设置页迁移当前地址（24h），系统 hosts 已恢复、ADB 已 unroot，4 项主机回归与 1 项应用 UID 无凭据 HTTPS 检查通过。详见 [设计与验证](subscription-dns-overrides.md)。本项完成不关闭旧手机长回复与完整订阅对话验收。

最新补充：连接测试与能力检测已拆分；仅连通显示能力尚未检测，能力失败不禁用已连接 Provider。网络中断时保留已输出前缀与错误，并增加不含正文/凭据的 I/O 分类日志。长回复 socket 中断根因仍未关闭；原手机已断开，所有者指定后续用 OnePlus 6T，现已首次安装订阅组件并更新主应用，等待重新登录后人工复现。两包构建与单项中断前缀回归通过，不代表长回复已验收。

长回复修复：按所有者要求移除订阅链路的 2048 事件、1 MiB 结果、2 MiB 流上限及 120/150 秒应用计时；PFD/结果存储无累计正文上限，保留分批、取消、签名/哈希和终态验证。仅构建并覆盖安装，长回复由所有者人工验收；不以旧短回复成功代替当前验收。

最新安排（所有者要求）：HXA-190 优先修 bug，暂停自动真机对话和扩展测试，交由所有者人工验收。已移除日常连接测试的全推理档位门槛（保留独立诊断）、将结果编码异常纳入 Job 失败收口避免永久 BUSY；保留工具别名、协议终态读流修复与有界网络前台服务。developer/订阅组件构建成功并覆盖安装，登录/会话数据保留；当前修复版尚未经连续 Chat/Plan/Act 人工确认，不能宣称验收完成。

HXA-190：修复Codex订阅目录/能力/协议并补Runtime安装；随后HXA-191配置引导、审批折叠、深色模式、会话搜索。HXA-189设备验收在独占真机推进，保留Claude模拟器。

订阅 companion 的用户可见名称经复核简化为 **Helix Subscriptions**；首页与四个登录页共用紧凑顶栏、浅色背景、圆角控件和滚动布局。内部包名仍为 `com.helix.runtime.cli`，现有登录数据保留。真机桌面旧入口残留已通过重启桌面进程刷新，实际只剩一个订阅图标；没有卸载应用或清除桌面数据。

真机已覆盖更新 developer/CLI Runtime；单启动图标、六模型目录及历史单次会话有通过记录，HXA-189 PFD 5项与权限/导航/系统栏7项通过。但最新 Codex 完整能力探测仍有 TRANSPORT/等待超时，不能视为订阅已修复。真实流式、工具回填、图片识别及推理档位保留独立能力检测；最新安排是所有者人工验收。当前发现 Android 报告未冻结时，主 App 的 UID cgroup 仍可处于 frozen=1；冻结来源与豁免对照待核实。详见 [订阅连接调查](subscription-connection-investigation-2026-09-10.md)；历史证据见 [真机更新记录](hxa190-physical-update-2026-09-10.md)。

HXA-189 剩余设备回归：双flavor完整系统授权允许/拒绝/设置恢复及兼容性矩阵，见 [复核交接](improvement-review-2026-09-10-followup.md#验证与交接)。历史源码/主机验证已完成，新真机证据见上方记录。

HXA-185 已由 Claude 独占模拟器验证完毕（主机门禁+4自测、Goal 双 flavor 16/16、Autofill 短项 1/1+6/6、FD 五臂 5/5、24h ON API36 = FAIL_FUNCTIONAL，Claude取证指向系统a11y重尾；跨版本长稳归因仍待补证），见 [完成记录](../completion-records/HXA-185.md) 与 [交接](hxa185-claude-test-handoff.md)。更宽 EV-02 浏览器正式 24h 的剩余 API29 一臂（方案第 11 行：API29+36 各一次）**已完成 = INCONCLUSIVE（非失败）**：独占 API29 模拟器 emulator-5584（AVD `Helix_EV_Repair_API29`，p3 ON，WebView 91.0.4472.114，冻结制品 `b9382e97…`，已 finally 关闭 exit 0）跑满整段 90006s（~25h）、615/615 cycle 全 ok、单 pid 1665、零 a11y 失败——**a11y 节点暴露重尾确认是 API36 系统 WebView 特有（非产品/夹具/金鱼缸）**；FD/threads/PSS 三可采样维度均在 24h 门禁内（FD+5/thread+4/PSS+14.7MB vs 门禁 +8/+16/+96MB），唯 PSS 有 +0.52MB/h 缓升未平台化（观察项，非泄漏判定）。整体 INCONCLUSIVE 因 system-Binder（UID-proxy）在模拟器物理不可采（结构性，重跑不变；正式判定需真机，X 类）。至此 EV-02 正式 2×24h 两臂齐（API36 FAIL_FUNCTIONAL a11y 重尾 + API29 INCONCLUSIVE 功能满绿），共同产出归因而非"24h 门禁 PASS"，详见 本机忽略产物 `../../build/emulator-verification/ev02-autofill-soak/p3-api29-on-24h-ev02-1-result.md` 与 run-index `ev02-p3-api29-on-1`。

## Planned：终端与后台命令

### 2026-09-14 任务与决策盘点

以下是 Harness 交接及当前状态中的未关闭项，不是重跑全仓验证后的新结论。HXA-190～199 尚无完成记录；部分功能和专项测试已交付，不能按“无完成记录”推断尚无实现。旧 27 detekt/12 lint 等数字保留历史含义，下一位执行者须重跑确认，不能重复修复或直接宣称仍有同数失败。

| 类别 | 未关闭事项 | 下一步 |
| --- | --- | --- |
| 已定收尾 HXA-192 | Room/Plan工具级设备证据已有，完整本地主机门禁已通过；Plan用户闭环与审阅不授予执行期权限的专项设备证明仍未完成 | 核心切片不重做；剩余R1与ADR/研究文档已由本轮接手，见WIP接手记录 |
| 已接受架构 HXA-193 | 单APK实现与本地主机/设备证据已收口；远端CI资产、真实账号、发行和真机后台验收未闭合 | Runtime代码与语言修正由本轮接手提交；按专项记录补外部证据 |
| 已授权 HXA-190/191 | 订阅真实协议/能力验收、配置/审批/主题/搜索与 Goal 入口 UI 验收 | 对照实际代码和所有者验收，不按旧待执行文字重做功能 |
| 已定范围 HXA-194/195 | 命令结果详情、有界实时日志，尚未开发；ADR-0050 已接受 | 相关基线稳定后可在现行契约内实施 |
| 已登记、启用待验证 HXA-196～199 | 异步 Job、PTY、多会话及集成；ADR-0051 accepted | 先 Spike/决策包；登记不代表全部生产实现已授权 |
| 既有验收债 | HXA-189 剩余设备项、Root 生命周期与真机资源/Binder 长稳、HXA-125 真实 Connector 服务、HXA-122 发行 | 各按原任务范围补证，账号/平台/发行条件独立记录 |

2026-09-14 已接受 ADR-0048/0050/0051 的内置元数据、日志/职责及后台/手动终端架构，均不等于功能验收。ADR-0051 仍需平台、组件许可、参数和设备启用证据。ADR-0030/0031/0032 的 Connector OAuth、版本所有权和市场方案仍 proposed，不在此次接受范围。GoalDriver、Schedule、Hooks、Code Mode 与子 Agent 仍属研究/启用门禁，不因研究图出现而进入当前实施范围。

Git 授权已更新：小模型可将 HXA-192～199 中已获授权且验证通过的独立切片提交本地 Git，检查具名暂存范围并报告 hash；保留并行改动，不推送、合并或发布。具体契约见 [专项交接](harness-2.0-next-work.md)。历史“未提交”是当时事实，不是继续禁止提交的指令。

HXA-194～199 已登记为待开发任务，见 [开发计划与小模型 Prompt](terminal-and-background-execution-plan.md)。不替换当前 In progress；先处理 HXA-192/193 的相关代码基线和门禁。HXA-194 沿用现有结果契约，195 的日志已由 ADR-0050 接受；异步 owner、手动 PTY 和并发规则依赖 accepted ADR-0051，不能把计划登记当作接受或实现。

## Next task

产品链下一项为HXA-202：先读[基线修复记录](../bug-fixes/2026-09-16-pre-hxa-baseline-regressions.md)与[审批体验复核](approval-experience-review-2026-09-16.md)，复用现有任务ID、取消和恢复事实，补待审批入口、通知直达与Plan执行授权说明。201依赖和本轮基线已满足，不重做三态后端或设置，不扩大L2/L3授权；允许按任务规则验证后具名本地commit，不push/合并。

2026-09-14 体验补充：[工作区与能力体验方案](workspace-and-capability-experience-plan.md) 定义四条用户路径，补充194～206的验收；新增207为现有Skill/MCP/Connector来源的添加到使用闭环（planned）。不重做124/127，不提前实施126/129/130的OAuth/市场/版本生命周期。207沿用验证后本地commit授权，不推送合并；当前在途收尾顺序不变。

2026-09-14 新授权产品任务包：HXA-200～206（均planned），见 [三态审批与产品闭环](product-completion-and-approval-plan.md)。ADR-0052 accepted：允许/询问/禁止偏好在真实执行生效，保留能力/scope与精确高风险批准；并补任务过程、产物交付、错误恢复和首次任务。先处理当前192/193相关收尾，不重做`0d52eae7`已有切片；新任务可在对应验证后本地commit，不推送合并。本条不将新功能标为已实现，也不覆盖上方在途证据。

2026-09-15 HXA-200 推进（未整体完成）：按 2026-09-14 澄清保留解析来源——`ToolApprovalResolver` 产出带来源的 `EffectiveToolPreference`（UNSET 沿用原 Policy / EXPLICIT 用户明确 / ALLOW_INVALIDATED 契约失效回退 ASK / NEW_DEFAULT 预留待可信登记升级基线），scope 合并保持 DENY > ASK > ALLOW、窄 scope 不覆盖外层禁止；ADR-0052 已补精确修订（仅第 1 点，不改第 2–8 点）。设备验收（生产 source 接线 + 真实 Room/broker/dispatcher）：新增 `ToolApprovalPreferenceDeviceTest` 4 用例（UNSET-L0 免卡并钉住新工具默认=空记录即 Unset、明确 ASK 强制卡、失效 ALLOW 回退 ASK 卡、跨 scope DENY>ASK>ALLOW），原 `ToolSchedulerDeviceTest` 9 用例免卡回归保持绿；API 29/36 × consumer/developer 共 8 次 gradle 运行全过（每 API 4+9），独占模拟器已在 finally 关闭（证据 `scripts/debug/2026-09-15/run-apref-device-regression.sh`、`build/hxa200-device-20260915-105951/`）。剩余三态×风险/模式/能力全矩阵、精确批次、版本/范围/迁移、排队撤销、外部来源碰撞尚未覆盖，故不写整体完成记录。P3 lint 仅被 2 处既有第三方 JGit TrustAll 挡住（HXA-192/ADR-0048 所有方决定），非本切片代码。

2026-09-15 HXA-200 Gap 6（真实旧库迁移、不批量 ALLOW，commit `ad5b9912`）：发现 v17 schema（`tool_approval_preferences`，ADR-0052）虽随迁移落地，但 `MIGRATION_16_17` 未注册进生产 `HelixStorage.ALL_MIGRATIONS`——真实 v16→v17 升级会在启动时崩（fresh install 不触发该路径，故无升级设备测试前不可见）。修复=生产链补注册 `MIGRATION_16_17`；fixture 同步 v17（drift-guard 改名 v17、v1→全链与「后续步骤已落地」断言加新表、expectedTables 加 `tool_approval_preferences`）。新增 2 设备用例：加性迁移后旧行存活且新表为空（无 seed ALLOW，未配置用户保留原免卡）+ 唯一键 `(sourceRef,toolName,scopeKind,scopeRef)` 强制；以及经生产 `HelixStorage.open` 打开真实 v16 库验证 ALL_MIGRATIONS 链（漏注册直接崩）。API 29 独占模拟器 finally 关闭：`RoomMigrationFixtureTest` 30/30（含 2 新例 + v1→v17 全链 + v17 drift-guard）；spotless/detekt/`:core:storage:testDebugUnitTest`/`git diff --check` 全过。本地 commit `ad5b9912`（不 push/合并）；文档同步在本条（文档 commit 待共享验收文档归属协调，不夹带 192/193/194–199 并行改动）。剩余 Gap 1–5（全矩阵、精确批次、NEW_DEFAULT 可信基线、版本/范围/碰撞、排队撤销竞态）未覆盖，不写整体完成记录。

2026-09-15 HXA-200 Gap 1（三态×风险/模式/能力，尤其全局 ASK + 窄 scope ALLOW，commit `79fd7d85`）：补齐 `effectivePreference(records, currentContractHash)`（scope 合并）的直接主机测试——此前 `ToolApprovalResolver` 套件只覆盖 `resolve(effective, decision)`，未覆盖决定 GLOBAL/WORKSPACE/SESSION 哪条生效的合并逻辑。新增 6 主机用例钉住合并矩阵：会话 ALLOW 覆盖全局 ASK（Gap 1 强调用例，DENY 是唯一跨 scope 权威态，故 ASK vs ALLOW 取最窄 scope，调用免卡）、会话 ASK 收紧外层 ALLOW、workspace ASK 介于全局 ALLOW 与会话 DENY-free 之间、外层 DENY 胜过所有更窄 ASK/ALLOW、仅剩一条陈旧 ALLOW（无其他存活记录）解析为 ALLOW_INVALIDATED 而非全新 Unset、空记录集解析为 Unset。另加 1 设备用例（真实 Room/broker/dispatcher）把同样的全局 ASK + 会话 ALLOW 端到端跑通断言免卡——与外层 DENY 设备用例互为相反。API 29 独占模拟器 finally 关闭：`:core:policy:test` 20/20（0 skip，含 6 新例）、`ToolApprovalPreferenceDeviceTest` 5/5（consumer+developer）；spotless/detekt/`git diff --check` 全过。本地 commit `79fd7d85`（不 push/合并）；文档同步在本条（文档 commit 待共享验收文档归属协调，不夹带 192/193/194–199 并行改动）。剩余 Gap 2–5（NEW_DEFAULT 可信登记/升级基线、版本/范围/碰撞、精确批次、排队撤销竞态）未覆盖，不写整体完成记录。

2026-09-15 HXA-200 Gap 2（NEW_DEFAULT 可信登记/升级基线、旧工具 UNSET、重启与恢复默认，commit `c63172a0`）：按 ADR-0052 第 1 点澄清，新工具默认由**可信登记/升级事实**驱动，不把所有空记录当新工具，也不根据模型声明判断。加性 Room schema 17→18 两张新表（升级后均为空、无 seed、不批量创建 ALLOW）：`tool_registration_baseline`（每工具一行，PK `sourceRef+toolName`，记 `firstSeenVersionCode`）+ `tool_baseline_meta`（单行 `foundingVersionCode` 锚点）；first-write-wins（`OnConflictStrategy.IGNORE`），同 build 重启稳定、下一 build 自然老化。纯决策函数 `ToolBaseline.isNewDefault(current, founding, firstSeen)`（`NEW_DEFAULT ⟺ firstSeen == current && current > founding`，两者皆需存在）；resolver 加默认 false 的 `newToolDefault` 参数，终分支排序为 失效 ALLOW（ALLOW_INVALIDATED）> 新工具默认（NEW_DEFAULT）> Unset；仅在该工具**任何 scope 都无配置记录**（`hasAnyPreference`）时生效，用户实际选择（ASK/ALLOW/DENY）恒覆盖默认。`ToolApprovalPreferenceService.reconcile` 是基线的唯一写路径（model/Skill/MCP/A2A/UI 不可达），容器构造时以当前 versionCode 登记 built-in 工具身份（动态 MCP/A2A 工具暂不入基线）。ADR-0052 追加 2026-09-15 基线机制说明。主机：`ToolBaselineTest` 8 例 + resolver 新增 6 例 + service 新增 4 例（全新安装全 OLD、升级新工具 Ask(NEW_DEFAULT)、显式配置覆盖默认、旧升级版本老化出 NEW）。API 29 独占模拟器 finally 关闭：`RoomMigrationFixtureTest` 31/31（v17→v18 加性空表 + 唯一键、生产 open 真实 v16→v18、v18 drift-guard、v1 全链）、`ToolApprovalPreferenceDeviceTest` 6/6（consumer+developer，含 NEW_DEFAULT 全生命周期用例：founding 全 OLD → 升级新工具 Ask(NEW_DEFAULT) → 显式 ALLOW 覆盖 → 重置回 NEW_DEFAULT → close/reopen 重启仍稳定）。spotless/detekt/`git diff --check` 全过。本地 commit `c63172a0`（不 push/合并）；文档同步在本条（文档 commit 待共享验收文档归属协调，不夹带 192/193/194–199 并行改动）。剩余 Gap 3–5（版本/范围/外部来源同名碰撞、精确本次/批次证明复用、排队期间修改偏好/取消竞态）未覆盖，不写整体完成记录。

2026-09-15 HXA-200 Gap 3（ALLOW 契约失效、范围不匹配、外部来源同名碰撞，commit `0ca65b4a`）：契约失效一半此前已钉死（主机 + 设备 `anInvalidatedAllowFallsBackToACard`），本切片补齐另两半钉死。范围不匹配：workspace ALLOW 不泄漏到其他/无 workspace、session ALLOW 不适用于无会话上下文（主机，经真实 repository 过滤）；端到端走 PRODUCTION pipeline——GLOBAL ASK + 会话 A 的 session ALLOW 对 turn 属于会话 B 的 dispatch 仍出卡（dispatcher 按调用自身持久化 session 重解析，point 7，行不跨会话泄漏；若泄漏 B 将 Allow 免卡）。外部来源同名碰撞：身份是 (sourceRef, toolName)、绝不只用裸名——built-in 的已存 ALLOW 不授权另一来源的同名工具，一侧的 DENY 也不把另一侧从模型 exposure 中隐藏（主机 + 真实 Room 经生产 service）。主机：service 套件 17/17（0 skip，含 3 新例）；API 29 独占模拟器 finally 关闭：`ToolApprovalPreferenceDeviceTest` 8/8（consumer+developer，含跨会话出卡与同名碰撞 2 新例）；spotless/detekt/P2/`git diff --check` 全过。本地 commit `0ca65b4a`（不 push/合并）；文档同步在本条（文档 commit 待共享验收文档归属协调，不夹带 192/193/194–199 并行改动）。剩余 Gap 4–5（精确本次/批次证明复用、排队期间修改偏好/取消竞态）未覆盖，不写整体完成记录。

2026-09-15 HXA-200 Gap 4（精确本次/批次证明复用，不重复询问、不跨参数复用，commit `ea91efe6`）：钉死存储原语语义「one refund per consumption」——同一笔消费的双重冲销在 SQL guard 下结构性不可能（0 行），新的消费独立冲销；重试次数上限（hard cap 2）在 dispatcher 的 hard cap，不在 storage（`ApprovalRepository.refund`/`ApprovalDao.refundByBinding` KDoc 按此修正）。新增 `countByToolCall`：broker 每次确认面呈现恰好建 1 条记录，count>1 即重复询问的直接证据。主机：`ToolSchedulerTest` 新增 2 用例——同批次两个同工具调用各拿自己的 acquire + 不同 bindingHash（不跨调用共享证明）；fail-once sideEffectFree + maxAttempts=2 → 1 acquire / 1 reMint / 2 consumes / audit attemptIds [1,2] / 同一 bindingHash（不重复询问）。设备：存储半在真实 Room 钉死——`ApprovalProofLifecycleTest` 7/7（refund 后 re-mint 恰好一次、mismatched binding 的 refund 永不触碰记录、再消费后 CONSUMED、新消费独立冲销、refund 不延长窗口）；app 新增 2 用例走生产 `StorageApprovalBroker` 类 + 真实 Room/registry/audit sink（card sink 为 no-op——生产 dispatcher 的 card sink 对直接 dispatch fail-closed：卡片须由 chat pipeline 的 dispatch facts 构建，UI 卡片渲染是 `ApprovalFlowDeviceTest` 的覆盖；本类同时自己遵守审批 FK 要求 dispatch 前落 tool_call 行（id==callId，PENDING）的生产契约）。重试用例：被批准调用的有界技术重试从同一记录 re-mint（恰好一张卡、`countByToolCall==1`、记录 APPROVED 且已消费、attemptIds [1,2]、同一 bindingHash）；参数用例：同批次同工具不同参数的两个调用，批准 call-1 的参数不覆盖 call-2（独立记录、独立 bindingHash、DENIED 的 consumedAt 为 null、不影响 call-1 已消费的证明）。API 29 独占模拟器已在 finally 关闭：`ToolSchedulerDeviceTest` 11/11（consumer+developer，含 2 新例）+ `ApprovalProofLifecycleTest` 7/7；P1（spotless/detekt）+ `git diff --check` 全过。本地 commit `ea91efe6`（不 push/合并）；文档同步在本条（文档 commit 待共享验收文档归属协调，不夹带 192/193/194–199 并行改动）。剩余 Gap 5（审批等待/排队期间修改偏好、取消与执行开始竞态、保证持久结算）未覆盖，不写整体完成记录。
2026-09-15 HXA-200 Gap 5（审批等待/排队期间修改偏好、取消与执行开始竞态、保证持久结算，commit `fa01dde6`）：生产 turn stop 是双路径——翻转 per-turn `TurnCancelSignal`（与 `ToolDispatchRequest.cancel` 同一对象，`ChatDispatchRequests` 接线）+ 对每张 pending 卡片 `broker.cancel`（`ChatToolCalls.cancelPendingApproval`）；设备测试按此对真实 broker/Room 建模 1:1。主机 `ToolDispatcherTest` +2 用例：broker 返回 Approved 后、pre-start 门前到达的 stop → 门优先：Cancelled、executor 0 次调用、已授予证明从不消费（`consumeCalls==0`）、audit CANCELLED_BEFORE_START；卡片 pending 期间偏好翻转 DENY 不能改写已呈现的决策（呈现卡的 per-call 决定为准，source 恰好读一次——`calls.size==1` 钉死）。主机 `ToolSchedulerTest` +2 用例（新增 `FlipPreferenceSource` seam）：调用还在 QUEUED 时翻转偏好在 dispatch start 生效——DENY 无卡 settle PREFERENCE_DENIED（acquire/consume 均 0、USER 决策源、executionStartedAt null）、ASK 在 dispatch start 恰好出一张卡（1 acquire / 1 consume、双双 Succeeded）。设备 `ToolSchedulerDeviceTest` +2 用例（13/13 consumer+developer，API29 独占模拟器已在 finally 关闭）：turn stop 落在真实 broker 审批等待期间 → slot `Thrown(ApprovalCancelledException)`、audit 行持久 CANCELLED_BEFORE_START（bindingHash 已计算；approvalAcquiredAt null——被中断的 acquisition 从不 mint；executionStartedAt null）、审批记录保持 PENDING / 未决 / 未消费 / 恰好一张卡、兄弟调用仍 settle SUCCESS（停止项不取消批次）；排队期间经真实 `ToolApprovalPreferenceService` 翻转 → PREFERENCE_DENIED、零卡片、USER、queue stamp 保留。顺带修正两处 audit payload 的 null 检查：kotlinx-serialization 的 `JsonNull` 是 `JsonPrimitive` 子类型，`is JsonPrimitive` 判不出 null（D1 断言改写为 `is JsonNull`/`!is JsonNull`，Gap 4 的 `queuedAt` 检查同步收紧为 `!is JsonNull`）。P1（spotless/detekt）+ `git diff --check` + P2 全过（`ToolDispatcherTest` 62/62、`ToolSchedulerTest` 19/19）；本地 commit `fa01dde6`（不 push/合并）。**HXA-200 六个 gap 至此全部落地**（`79fd7d85`/`ad5b9912`/`c63172a0`/`0ca65b4a`/`ea91efe6`/`fa01dde6`）；不写整体完成记录：共享验收文档的提交归属待协调（三份文档含未提交并行改动），JGit 第三方 lint 2 项保持独立阻断记录（不 suppress），HXA 不关闭。

2026-09-15 HXA-201 Slice 1（工具审批设置区块，commit `7356453b`）：设置屏提供常驻工具审批偏好——每个已注册工具名一行（最新版本），按工具名称/提供方搜索（大小写不敏感），生效态由与 Dispatcher 相同的 `ToolApprovalPreferenceService` 解析（ADR-0052 第 7 点：设置屏显示的即运行时执行前重验的，不会漂移），行上携带适用记录作为来源（scope+scopeRef）；GLOBAL 作用域的 允许/询问/禁止/恢复默认 四个独立动作，ALLOW 绑定行当前契约哈希（后续契约变化在读取时失效），恢复默认仅移除 GLOBAL 记录并显示实际解析结果。保留来源的六态投影：未设置绝不显示为已允许（`UNSET`），失效 ALLOW 显式为「需重新确认」（`ASK_INVALIDATED`），新工具默认单独标注（`ASK_NEW_DEFAULT`），禁止态说明对模型隐藏且调用被阻止。新增 `ToolApprovalSettingsModel`（设置 UI 唯一读写面，不访问 DAO、不另建偏好存储）+ `ToolApprovalSettingsSection` + `AppContainer.toolApprovalSettings` 接线（接口 `error()` 默认保住测试 fake 编译）+ 三语言 strings（zh/zh-rCN/en，check-i18n 键集一致）。主机新增 7 用例（真实 registry + 真实 service over 内存 DAO）双 flavor 全过；P1（spotlessCheck/detekt）+ `git diff --check` 全过；P2 app 主机套件 consumer 526 pass / developer 552 pass / 0 fail。本地 commit `7356453b`（不 push/合并），具名路径 + 具名 hunk 暂存，并行改动全部保留未夹带。剩余范围：会话/Workspace 作用域设置、审批卡改进（首屏动作/目标/范围/摘要、本次批准与未来偏好分开、高风险不显示「永远允许」）、`ToolApprovalSettingsDeviceTest`（API29/36 × consumer/developer 独占运行、真实工具行为 + 三语言/深色/大字体/小屏/旋转）、P3；整体未达标，不写完成记录。
2026-09-15 HXA-201 Slice 2（审批卡：本次决策与未来偏好分离，commit `4f349dff`）：PENDING 审批卡新增独立的「保存未来偏好」动作组（允许/询问/禁止）——与本次批准/拒绝完全分离：保存经唯一写路径 `ToolApprovalSettingsModel.setPreferenceFor(sourceRef, toolName)` 写 GLOBAL 作用域常驻偏好（UI 不触 DAO；卡只携带 (sourceRef, toolName) + baseRisk、从不携带契约哈希，写不出过期契约）；保存偏好不批准/不拒绝本次调用（compose 用例钉死 approved==0）；高风险（L2/L3）卡不显示未来允许按钮，改给「请在设置中设置，高风险调用仍须逐次确认」说明；终态卡（已批准/已拒绝/成功/失败，含过期/已消费/已取消）不显示任何动作按钮。`ApprovalCardUi` 新增 toolName/sourceRef/baseRisk（descriptor 身份，绝非显示名），`ApprovalUiMapper.buildCard` 填充；`ToolApprovalSettingsModel` 的 rows 按 (origin, name) 键控（跨 server 同名工具 = 两行：registry 以 (name, version) 键控且 MCP 前缀命名空间隔离，跨源同名碰撞只能经不同版本的同名 MCP 工具表达），`rowFor`/新增 `rowForIdentity`/`setPreferenceFor` 一律以 (sourceRef, toolName) 为身份（fail-closed：未注册工具返回 null 且不写任何东西）；`ConversationIntents.onSaveFuturePreference` → `ChatScreen` → model 接线。主机 +3 用例（同名跨 server 写入隔离、fail-closed null、同名分行）、compose +3 用例（L1 卡三未来动作且保存不批准本次、L2 卡无允许按钮+说明、终态无任何动作）、设备 fixture 更新过新字段。P1（spotlessCheck/detekt）+ `git diff --check` 全过；P2 全过（core model 142 / policy 1177 / agent 271 / tools 164 / storage 89；app consumer 529 / developer 555，0 fail；双 flavor androidTest assemble 通过）。本地 commit `4f349dff`（不 push/合并），10 个具名路径 + 5 个具名 hunk（mapper buildCard 3 行、MainActivity 1 行、strings 2 行 × 三语言）暂存，并行改动全部保留未夹带。剩余范围：保存设置后的真实工具行为验证（低风险 ASK/UNSET、DENY、ALLOW 高风险仍确认、跨 scope、失效版本、等待期间修改、取消、重启、迟到批准）、`ToolApprovalSettingsDeviceTest`（API29/36 × consumer/developer 独占运行 + 三语言/深色/大字体/小屏/旋转）、P3；整体未达标，不写完成记录。

Claude 完成原冻结轮次后，按交接执行主机门禁、独占模拟器短测与 FD 对照，再决定新制品长稳。HXA-189 主机门禁已通过，Claude 按复核交接执行新增设备回归；HXA-186～188 有界真机工作已有完成记录，不操作原模拟器，不自动推送；Claude 在途结果独立落盘。

## Blocked

| 项目 | 缺少条件 / 决策 |
| --- | --- |
| HXA-125 受保护 Connector 服务验收 | WorkBuddy 来源样本已补齐；仍需独立测试账号验证凭据无效、权限拒绝、厂商撤销和重连；匿名服务与 fixture 不替代这些证据 |
| HXA-126 / HXA-129 | [ADR-0030](../adr/0030-connector-public-client-oauth.md) / [ADR-0031](../adr/0031-connector-version-ownership-journal.md) 待审查；HXA-126 还需两家独立服务账号与 redirect 条件 |
| HXA-130 | [ADR-0032](../adr/0032-connector-offline-signed-index.md) 已准备；离线 fixture 依赖 HXA-129，市场运行时不在当前批次范围 |
| Claude / Grok 真实付费调用 | 账号不可用，按所有者决定暂缓；本地与设备夹具通过不代表真实账号验收 |
| 发布验收 | HXA-122 尚待稳定 applicationId、渠道命名、升级路径与签名发行决策 |

## Current interfaces

- **聊天与装配**：`ChatService` 保留应用入口及运行协调，草稿、请求组装、附件重试、界面投影、工具调用与结算已有独立组件；`AppContainer` 接口与 `DefaultAppContainer` 组合根分离。资源与可变状态仍由原所有者管理，拆文件不产生第二套状态源。详见 HXA-179/183/184。
- **Goal 与后台任务**：Goal 采用 ADR-0040；暂停可由用户继续，blocked 表示不能主动继续。后台能力是有界工具任务及结果回收，生产子 Agent、Agent graph 和声明式 Workflow 尚未实现；ADR-0009 的只读 Spike 不等于产品启用。
- **手动文件管理与 Agent 工具**：两者范围分离。手动界面支持 Workspace、共享存储和可写 SAF 的新建、改名、同来源复制/移动与删除；跨来源继续使用导入/导出。手动授权不扩大 Agent resolver，不能把手动共享根视为 Agent 已获授权的文件范围。边界以 HXA-180/182 与对应 ADR 为准。
- **执行与工具**：QuickJS 使用非导出的 isolated Service；本 Harness 工作树的 PRoot/CLI 按 ADR-0049 内置于 developer APK，在私有进程共享主 UID，通过私有 Binder/PFD 通信，consumer 排除。`read`、`write`、`edit`、`files.*`、`bash` 继续经过既有 scope、Policy、审批、限制、验证和审计路径；PRoot/CLI 按需冷绑定，不因被动刷新启动。
- **扩展与 Provider**：MCP、Skill、A2A Client 已按 M7 矩阵验收；A2A 是用户配置的外部服务，不是本地子 Agent。订阅凭据由订阅模块持有，正常 API 不返回 token；共享 UID 不构成凭据隔离。developer/Advanced 接线不代表 consumer/store 开放或厂商官方支持。
- **浏览器**：WebView 由浏览器功能持有并绑定 Activity owner；下载队列和结果映射已分离。真实 AutofillService 的 fill/save 回归通过，不能据此关闭系统 JNI/Binder 累积问题。

## Known limitations

这里只列仍有效的范围限制和验收缺口；已修复缺陷、旧测试数量和机制演进保留在完成记录，不再列为当前故障。

- **系统与长稳**：应用侧释放路径和短回归已有证据。模拟器侧 24 小时长稳已按可采维度跑完：EV-02 两臂完成（API36 a11y 重尾归因 + API29 功能满绿，system-Binder 模拟器不可采）；EV-03 应用 FD 门禁为模拟器固有 goldfish 节点（X 类，非应用泄漏）。但**系统 JNI/Binder 根因仍 open，权威资源/Binder 门禁需真机**（模拟器无法关闭 goldfish FD 与 UID-proxy Binder 两维）。见 [释放路径调查](native-reference-release-trace.md)、[浏览器引用验证](browser-controller-reference-verification.md) 与 [优化待办](main-optimization-todo.md)。
- **Root 真机**：HXA-188的授权/新请求拒绝/服务死亡与有界读取已通过；存活Root会话撤权、完整App生命周期及Root长稳仍需补测。设备已可用，不再以缺少rooted真机作为阻塞原因。
- **设备覆盖**：API29/36 模拟器及历史 API34/35、16 KiB 模拟器证据不替代物理低内存、OEM、热压力、Doze、安全锁屏和 Root grant/revoke/loss 验收；x86_64 静态制品证据也不等于实际运行。
- **文件恢复**：HXA-182 实现显式对账恢复，不承诺字节偏移续传、断电事务、自动后台队列或跨 Provider 原子事务；既有 picker 导入/导出及旧版无日志暂存不在该恢复管线内。目标/备份变化时保留人工核查，云盘厂商与全部中断阶段仍需外部设备验收。
- **模型与附件**：导入成功不等于模型理解。图片受实际模型视觉能力与端上预算约束；既有文本/图片管线不代表任意文档、音视频解析或 OCR 已实现。真实服务可用性与连接参数需要按服务当前状态验证。
- **后续功能**：持久 Git Workspace、结构化 Git UI、remote Git/凭据，以及生产子 Agent/Workflow 不在当前实现范围；接受 ADR 不构成实现证据。Connector OAuth、版本管理与市场扩展也不因本轮整理自动启动。
- **发行**：Standard 完整产品形态是 ADR-0013 的决定；当前 consumer/developer 构建与 CI debug APK 不是签名 release、完整渠道权限申报或商店审核证据。
- **测试条件跳过**：HXA-184 的 8 项 JVM 跳过需要 supplied Connector/WorkBuddy 与外部验收材料；每台设备的 2 项浏览器跳过需要显式长稳/诊断参数。均未计为通过，具体条件见 HXA-184。
- **外部依赖边界**（2026-09-15 措辞修订：原「无网络门禁」→「不依赖外部业务服务、真实账号或付费调用」）：默认本地验收与强制 CI gate（`scripts/check-all.sh --source/--build`、P1/P2/P3）**不依赖外部业务服务、真实账号或付费调用**——JVM `test` 全 hermetic（MockWebServer/内存 fake/断言常量，不拨号）。边界三分：构建时的一次性依赖下载（Gradle/AGP/Maven，lockfile+verification 固定版本）允许联网；本机运行的测试服务器（MockWebServer、本机测试服务等）是本地验收的合法组成；触达真实外部服务的 smoke（`realSubscription`/`realCodex`/`realCopilot`/`helixDnsProbe`/sglang 探针等）是显式单独运行，经 instrumentation 参数 + JUnit Assume 选择性启用，缺参数即 skip（非 fail）、不消耗配额、绝不作为默认必过项——**显式启用后失败即失败，不得伪装成 skip**，运行后须记录设备/版本/实际结果。设备测试与网络/运行时资产资格是显式单独运行，不并入强制 gate。

## Connector 扩展线收尾

M13 首版 HXA-124、HXA-127/128 已验证；HXA-125 的受保护服务验收后置，HXA-126/129/130 未完成，不能宣布 M13 整体完成。来源样本与历史移交见 [Connector 工作交接](connector-handoff.md)。旧批次的推送记录不代表本次合并已推送；当前 Git 状态以实际工作树为准。

## 真机最新结果

HXA-186：App48、存储50、文件38通过；HXA-187已修复QuickJS无界面测试冻结、PID/OOM平台假设及绑定早退连接泄漏，真机77项、JVM85项通过，详见 [记录](../completion-records/HXA-187.md)。生产超时仍为10秒，未修改引擎/IPC；浏览器资源夹具停滞已由HXA-188归因为测试进程冻结并修复。临时包已清理，developer数据保留；未操作Claude模拟器，HXA-188已补充短时后台/锁屏与Root证据，不代表全量真机或长稳验收通过。

2026-09-15 HXA-201 Slice 3（设备验收 + 主线程安全写入，commit `b4f20607`）：新增 `ToolApprovalSettingsDeviceTest`（11 用例），从 201 表面（设置模型 + 卡的 identity-based setPreferenceFor）走生产 pipeline（真实 Room/dispatcher/broker，ADR-0052 第 7 点执行前重验）：设置保存 GLOBAL ASK 全会话出卡、ALLOW 免卡（audit preferenceAtStart=ALLOW）、DENY 无卡拦截、ALLOW 高风险（L2）仍出卡且批准后才执行、契约变化（v2 注册）使 ALLOW 失效为可见的重新确认（reason=ALLOW_INVALIDATED、contractValid=false）、卡上经 identity 路径在 pending 期间保存 DENY 连已呈现的卡也拦截；raw service-write 矩阵（unset/取消/迟到批准/跨 scope）复用 HXA-200 `ToolApprovalPreferenceDeviceTest`，不重做。重启用例走两阶段 `adb shell am instrument` 协议（普通阶段写 SharedPreferences fixture（工具身份 + app PID）+ Room ALLOW → `am force-stop com.helix.agent` → `-e hxa201SettingsPhase restart` 整类重跑；不用 gradle connected：本 AGP 每次 connected 运行结束后卸载应用（连用户数据一起，logcat 取证 deletePackageX），跨 run 状态无法持久；fixture 写者按 phase 参自门控）。新进程（PID 断言 ≠ 旧 PID）下 ALLOW 仍生效且免卡分派成功。Compose 用例：三语言（zh/zh-CN/en）标题与未设置文案、按名称/提供方搜索过滤、深色 + 2x 大字体 + 360dp 小屏四动作全可触达、640dp「旋转」后真实点击 DENY → 禁止文案 → 恢复默认 → 未设置（EN locale 往返 Room，effectiveFor=Deny/Unset 断言）。生产修正（设备矩阵逼出）：`ToolApprovalSettingsModel` 三个写方法改 suspend（单一 mutex 串行 + IO dispatcher，Room 主线程 guard 下 Compose 点击无需调用方切线程；setPreferenceFor 对未注册工具 fail-closed 返回 null 且不写）、Section 动作行改 FlowRow（窄屏/大字体四动作全可触达）、ChatScreen 经 rememberCoroutineScope 接线、审批卡摘要加 testTag；`ApprovalCardScreenTest` 对齐 HXA-201 卡形态（首屏摘要 + details 展开器：pending 时恰 3 个可点击=批准/拒绝/展开，终态仅展开器）。设备（自建独占模拟器，用完即关）：API 29/36 × consumer/developer 四象限两阶段全过（每象限 phase 1 = 10 过 + 重启 skip、phase 2 = 新进程同数据 11/11 含重启用例；设备执行的 APK 与提交源仅注释/导入级差异，无行为变化）。P1（spotlessCheck/detekt）+ `git diff --check` 全过；P2 主机套件：core model 142 / policy 1177 / agent 271 / tools 164 / storage 89，app consumer 529 / developer 555，0 fail（每 flavor 4 项既有条件跳过，见 HXA-184）。本地 commit `b4f20607`（不 push/合并），7 个具名路径暂存，并行改动全部保留未夹带。剩余范围：P3（lint 等）、会话/Workspace 作用域设置；整体未达标，不写完成记录。
2026-09-15 HXA-201 Slice 3 附记（full app 设备套件状态，非本任务门禁）：一次 full `:app` connected 设备套件运行（gradle connected，API 29）中，`ToolApprovalSettingsDeviceTest` 之外的 22 个测试类失败，失败模式横跨 attachments/goals/providers/git（JGit `NoSuchMethodError: readNBytes`）/tasks/share/composer，均不在 HXA-201 diff 范围；JGit 项为工作树依赖级红（并行未提交 WIP 的依赖漂移症状）。本任务强制设备门禁是隔离的两阶段 `ToolApprovalSettingsDeviceTest`（API 29/36 × consumer/developer 四象限全绿，见上一条）。此 full-suite 红记录为既有/并行 WIP 红，不计入 HXA-201。
2026-09-15 HXA-201 Slice 3 收尾门禁：P3 全过——`:app:assembleConsumerDebug` / `:app:assembleDeveloperDebug` / 双 flavor `assembleAndroidTest` + `:app:lintConsumerDebug` / `:app:lintDeveloperDebug` 均执行且 BUILD SUCCESSFUL；已知的 2 项第三方 JGit TrustAll lint 不在本轮 app lint 结果中（保持 HXA-192/ADR-0048 所有方独立记账，未 suppress）。至此 Slice 3 的 P1/P2/P3 + API 29/36 × consumer/developer 四象限两阶段设备门禁全绿（本地 commit `b4f20607`/`b0211448`/`6975d29e`，不 push/合并）。HXA-201 整体仍未达标：剩余范围（会话/Workspace 作用域设置等）见路线图，不写完成记录。

## Completed

历史交付见 [完成记录索引](../completion-records/index.md)，M0 见 [合并记录](../completion-records/M0.md)。索引由实际记录生成，并由文档门禁校验覆盖率；这里不再复制每条 HXA 的历史验收摘要。

## 历史验收索引

以下保留全部已完成 HXA 索引，便于追溯当时的范围、命令、制品和限制。当前机制优先看上方摘要与最新对应完成记录；历史数量不作为当前全量测试数量。

- [路线图](roadmap.md)：任务定义、依赖与状态。
- [验证矩阵](verification-matrix.md)：各任务验收范围与证据入口。
- [大类职责审查](large-class-responsibility-audit-2026-09-10.md)：拆分前快照及 HXA-183/184 收口入口；不因剩余类较长自动新增拆分任务。
