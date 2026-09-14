# Helix 实施状态

更新时间：2026-09-10。HXA-161～184 已提交并快进合入本地 `main`（`81d60e6`）；未推送。main 工作树仍保留另一轮未提交的测试夹具与记录。

## Current summary

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

## Next task

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
- **执行与工具**：QuickJS 使用非导出的 isolated Service；PRoot/CLI 使用独立 APK/UID 与签名保护的 Binder/PFD。`read`、`write`、`edit`、`files.*`、`bash` 继续经过既有 scope、Policy、审批、限制、验证和审计路径；PRoot/CLI 按需冷绑定，不因被动刷新启动。
- **扩展与 Provider**：MCP、Skill、A2A Client 已按 M7 矩阵验收；A2A 是用户配置的外部服务，不是本地子 Agent。订阅适配器凭据保留在独立 CLI Runtime UID，developer/Advanced 接线不代表 consumer/store 开放或厂商官方支持。
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

## Connector 扩展线收尾

M13 首版 HXA-124、HXA-127/128 已验证；HXA-125 的受保护服务验收后置，HXA-126/129/130 未完成，不能宣布 M13 整体完成。来源样本与历史移交见 [Connector 工作交接](connector-handoff.md)。旧批次的推送记录不代表本次合并已推送；当前 Git 状态以实际工作树为准。

## 真机最新结果

HXA-186：App48、存储50、文件38通过；HXA-187已修复QuickJS无界面测试冻结、PID/OOM平台假设及绑定早退连接泄漏，真机77项、JVM85项通过，详见 [记录](../completion-records/HXA-187.md)。生产超时仍为10秒，未修改引擎/IPC；浏览器资源夹具停滞已由HXA-188归因为测试进程冻结并修复。临时包已清理，developer数据保留；未操作Claude模拟器，HXA-188已补充短时后台/锁屏与Root证据，不代表全量真机或长稳验收通过。

## Completed

历史交付见 [完成记录索引](../completion-records/index.md)，M0 见 [合并记录](../completion-records/M0.md)。索引由实际记录生成，并由文档门禁校验覆盖率；这里不再复制每条 HXA 的历史验收摘要。

## 历史验收索引

以下保留全部已完成 HXA 索引，便于追溯当时的范围、命令、制品和限制。当前机制优先看上方摘要与最新对应完成记录；历史数量不作为当前全量测试数量。

- [路线图](roadmap.md)：任务定义、依赖与状态。
- [验证矩阵](verification-matrix.md)：各任务验收范围与证据入口。
- [大类职责审查](large-class-responsibility-audit-2026-09-10.md)：拆分前快照及 HXA-183/184 收口入口；不因剩余类较长自动新增拆分任务。
