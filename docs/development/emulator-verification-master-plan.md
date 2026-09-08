# 待模拟器验证总计划与小模型交接

日期：2026-09-09。状态：**设计完成，未启动执行；新增夹具未实现**。覆盖当前已实现产品的模拟器证据缺口及新一轮验收必要回归，不授权新产品功能。执行时以 [status](status.md)、[roadmap](roadmap.md)、[验收矩阵](verification-matrix.md) 和 Git 为准。

## 1. 当前方案评审

[浏览器/Autofill 长稳方案](browser-autofill-soak-plan.md) 可作为专项子计划，但不能单独回答“所有模拟器测试是否完成”。主要缺口是主 App 接线连续使用、旧应用24小时资源门禁、实际设备网络故障、系统生命周期、ABI/页大小，以及有外部条件的模型/Connector 验收。

本总计划将证据分类，而不是把历史报告中的所有“未完成”重新打开：

| 分类 | 本轮处理 |
| --- | --- |
| G：当前明确缺口 | 必须设计并执行，未实现夹具先实现 |
| R：已有通过证据，当前产物回归 | 按影响复验；不得称历史未实现，也不无条件重复全部旧矩阵 |
| C：外部条件项 | 写清启动条件、可执行步骤与阻塞；条件缺失不假绿、不无限重试 |
| X：模拟器不能关闭的门禁/暂停功能 | 单独列出，不因这轮总计划扩大产品范围 |

依据核对：当前 status 的 In progress 为空，HXA-160已完成；M0～M11本轮功能/非真机短测和HXA-147 UI已收口；HXA-153～160完成的是有界诊断/修复/验证，系统问题和长稳仍开放。HXA-125真实来源包导入已验收，受保护服务仍缺账号。历史恢复阶段“未测”以后续 [HXA-102边界审计](hxa102-boundary-audit.md)、[main验证报告](main-merged-verification.md) 的补验为准。

编号 EV-00～EV-12 是本计划的执行包，不是新HXA，也不改变已完成HXA状态。实施夹具时按仓库规则登记一个独立测试HXA检查点，一次一个；本轮只设计，不创建持续Goal。

## 2. 总体任务表

| 执行包 | 类别/目标 | 环境和预算 | 前置与退出条件 |
| --- | --- | --- | --- |
| EV-00 证据清单与冻结 | G：把待测/已测/不适用映射到实际case ID | 宿主，约1小时 | 下节清单齐全，产物和配置固定 |
| EV-01 当前产物基础回归 | R：确保后续测试有有效基线 | API29/36、两flavor，约2～6小时 | 按影响集合通过；所有opt-in逐项解释 |
| EV-02 浏览器/Autofill | G：真实表单及JNI/Binder稳定性 | API29/36，按子计划各约32小时 | 子计划P0～P5，正式2×24小时，条件不足单列 |
| EV-03 应用资源24小时 | G：关闭既有API36应用长稳缺口 | API36 consumer，约25小时+pilot | 七项资源夹具同进程循环，原门限不变 |
| EV-04 主App连续组合任务 | G：补模块测试APK与产品接线之间的覆盖差异 | API29/36×两flavor，各2小时 | 确定性服务，正常任务与受控恢复各自断言 |
| EV-05 设备网络故障 | G：超出“只断loopback SSE”的平台覆盖 | API29/36×两flavor，约2～4小时 | 实际路由/连接状态变化、有请求证据、不盲重发 |
| EV-06 Android生命周期/资源 | G+R：锁屏、系统idle、内存与任务语义 | API29/36，另API35页大小，约2～4小时 | 平台事件真实发生，未发生不得算通过 |
| EV-07 crash/ANR及持久恢复 | R：已有证据按影响复验 | API36真实系统退出信息；API29降级，约1～3小时 | 类型/时间/PID/新进程读回匹配，非force-stop冒充ANR |
| EV-08 API/ABI/页大小 | G+R：补x86_64实际运行，保留16KiB覆盖 | API34/35 smoke；API35 arm64 16KiB；可用x86_64，约2～4小时 | 实测ABI/page size；无镜像/可用执行主机为C |
| EV-09 固定模型评测 | C+R：当前配置逐case能力与误调用 | 显式可用SGLang等，预算见下文 | 精确dataset、模型/协议绑定；不拼45项不同产物 |
| EV-10 M11/Connector外部验收 | C：真实订阅/受保护服务剩余门禁 | 独占账号设备，时长由条件决定 | 独立账号、可撤销测试scope、用户授权；Claude/Grok搁置 |
| EV-11 接近发布产物的smoke | C+R：R8后实际运行与升级保留 | API29/36、两flavor，约1～3小时 | 可安装的测试签名产物，不冒充正式发布签名 |
| EV-12 汇总与独立证据复核 | G：每项目标对应结果、保留失败 | 宿主，约1小时 | 无未解释skip、无无证据PASS、遗留条件明确 |

以上时间是执行排程预算，不含新夹具编码或修复。必须长稳共72设备小时（浏览器48+旧App24）；同一API36设备串行两类24h，加预检至少约57小时，再加短项。两台独占设备整套预计3～4天；资源不足则串行延长，不承诺模型能在固定时间内修复未知故障。API29旧App24小时、双flavor各24小时和真实模型24小时不是本轮既有必需门禁，作为扩展项单独授权/排程，不偷换为已覆盖。

## 3. EV-00：最先交付机器可读执行清单

在忽略的build报告目录建立 `plan.json / artifacts.json / run-index.json / handoff.md`。每行必须有执行包、来源文档/HXA、具体测试class/method或脚本、分类G/R/C/X、前置、设备、flavor、预期case数量、成功断言、超时、日志路径、状态。新夹具记 `NOT_IMPLEMENTED`，不能填一个猜测命令；执行前将真实命令写入manifest并校验class存在。

每个包状态为 `NOT_STARTED / NOT_IMPLEMENTED / RUNNING / PASS / FAIL / BLOCKED / INCONCLUSIVE / CANCELLED`；浏览器子计划细分失败状态映射到FAIL并保留原值。BLOCKED必须有具体缺少条件和可继续部分。已完成旧证据记录commit/hash和日期；影响分析可复用时明确 `REUSED_EVIDENCE`，不记为本轮执行PASS。

构建一次，复制主包、测试包、companion包到只读快照，记录SHA-256、源commit、相关未提交补丁及未跟踪文件、签名指纹、包名、runner、ABI、版本。工作区有并行修改时不覆盖，使用经核实的隔离checkout；不从不同目录混装APK。每设备有排他锁，记录任务/runId/主机PID/启动时刻；重入须核实PID/starttime和心跳，不能看到旧锁就杀别人的进程。

宿主检查JDK/SDK/NDK/磁盘、AVD名称/配置、ADB授权、安装UID、页大小、系统/WebView版本。所有带安装/force-stop/系统设置操作的脚本先读源码；阶段之间方可安装、更改服务、恢复快照。每轮恢复原系统设置，清理仅限自身合成数据。主机防休眠、日志轮转、watchdog及原子写结果沿用浏览器子计划。

## 4. EV-01/03/04：基础、资源和产品链路不能互相替代

EV-01先跑实际影响到的JVM/构建/Spotless/Detekt/完整lintDebug，以及docs/ADR/i18n/secrets/lockfiles/variant检查。设备集合从验收矩阵提取：App/Storage、browser、files、android工具、QuickJS、MCP/A2A、PRoot/CLI的基础连接和回归。HXA-160浏览器owner/Autofill/MainActivity必须包含。第一次清单冻结时解析JUnit预期测试集合，不能写死历史总数，也不能以connected任务退出0忽略assume/opt-in。外部账号、raw WebView、长稳、宿主强杀入口分别归包，不在普通全量类扫描里误启。

EV-03复用 `scripts/run-hxa103-browser-soak.py --workload app` 的 `ContinuousAppResourceDeviceTest`：真实Room迁移、FGS、资源门控七项每轮无skip。先按子计划runner契约补固定产物身份/PID/采样失败归因/取证优先；旧脚本不具备的能力标为待补。原FD+8、线程+16、PSS+96MiB峰值门禁不变，默认不使用observe-breaches。15分钟pilot→2小时预检→同进程24小时；运行量自然由七项夹具决定，记录轮数和嵌套实际case总数。它不是全部App功能长稳，也不覆盖真实Autofill。

EV-04新增主App组合夹具，必须走实际MainActivity/AppContainer/ChatService/Dispatcher，不能只new模块controller。固定本地协议服务提供模型输出，标明不是模型能力评测。每个组合运行2小时：每10分钟块包含以下五类任务各一次，然后资源采样与空闲；共12块、60个业务任务，任务最长90秒，剩余时间空闲，不并发追赶：

1. 普通Chat流、持久消息与停止后的稳定终态。
2. 独立workspace内write→read→edit结果核对与文件预览；由测试用户显式创建scope/所需精确审批，不注入verified或审批数据库行。
3. 实际浏览器工具导航→snapshot→非敏感click→关闭；DOM代次/请求次数与持久ToolResult一致，应用后台/重建不会静默重复提交。
4. 已启用的本地MCP/A2A fixture一次只读任务，核对远端ID、调用计数、终态和空闲解绑；Task结果只是外部证据，不授权新的本地动作。
5. Goal创建→显式Continue→确定性工具证据→完成，或一次明确Stop→PAUSED/取消契约；验收条件通过产品UI绑定，不能伪造完成。奇偶块交替成功/Stop，总数各6。

developer每块另加一次已批准PRoot真实guest读写合成快照、一次CLI协议fixture对话（无真实token）；consumer以“不存在/不可注册该渠道能力”断言替代，不强行注册。每轮结束检查未决绑定/任务/临时host清理；持久消息/审计允许随业务量增长，独立统计，不能当作泄漏，亦不能每轮清库掩盖问题。目录只在整轮完成后按测试所有权清理。

若基础调用链未变且已有同版证据，可复用EV-01；EV-04是新组合覆盖。各模块成功不能拼成真实模型自主端到端成功，也不能用这2小时替代EV-03的24小时。

## 5. EV-05：网络矩阵

每API/flavor对现有API Provider三协议各做四个边界：发请求前断网、headers后/body中断网、工具已被远端确认但结果未交付时断网、恢复网络后显式Continue。每个case只执行一次故障，服务端记录requestId、body hash、执行次数和终态。主App网络可达性须有独立探针/连接状态证明；仅关闭fixture socket属于已有协议回归，不标“整机断网”。

设备路由阻断可用专用AVD受控网络配置，但先证明所用方法阻断当前实际transport；切Wi-Fi图标不证明模拟器以太网也断。无法控制设备网络时标BLOCKED，不修改共享主机防火墙。DNS失败、TLS无效证书、读超时各另设确定性端点，每API/flavor各一次，绝不通过信任所有证书让测试通过。恢复设置并验证通路后再进行下一case。

成功：模型/工具按契约有限时间失败或取消，预算预留结清，原远端Task/Job按ID对账；恢复不自动重发不确定写入，UI明确显示结果/恢复限制。阶段动作可用fixture，但未经真实模型执行不标能力评测。已有 `run-model-stream-process-kill.py`、`run-mcp-process-kill.py`、`run-a2a-process-kill.py` 提供参考，SIGKILL不是网络故障的替代。

## 6. EV-06/07：生命周期和退出原因

| case | 次数/条件 | 必须断言 | 边界 |
| --- | --- | --- | --- |
| 前后台/旋转/Activity重建 | 各20次，API29/36，两flavor | 原已批任务按既有策略执行；owner/DOM token更新；不自动重复副作用 | 有同版HXA-160证据可复用对应部分 |
| 安全锁屏解锁 | 专用AVD设置临时测试PIN，各5次；结束恢复 | 窗口工具正确不可用/恢复；不越过锁屏动作；后台非UI任务遵守已有契约 | 屏幕熄灭不能冒充安全锁屏 |
| 强制idle进出 | 两API各3轮 | 原Job查询/取消及恢复正确，无盲重放，无多余FGS/wake lock | 复用 `accept-hxa-086-forced-idle.py`，明确forced idle |
| 自然idle观察 | 可用AVD每台最多90分钟，独立轮次 | 记录系统确实进入idle、任务状态和离开后的恢复 | 未进入为INCONCLUSIVE；不能替代OEM真机自然Doze |
| 实际低内存压力 | 既有 `accept-resource-pressure.py` 两API各一次/按影响复验 | 真实分配、gate降额、释放后恢复；记录实际可用内存 | 不把am kill冒充LMK |
| 自然LMK受控观察 | 独占低内存AVD，每次≤15分钟、最多2次 | 系统lmkd/退出原因/目标PID证据，持久任务恢复不重放 | 未杀目标=未覆盖；不可无限增加内存拖垮主机 |
| Runtime停用/强停/更新 | 已有跨APK生命周期/升级脚本按影响复验 | 保留原JobID、输出hash、取消与终态，空闲不绑定 | 既有PRoot/CLI已通过部分不重标缺口 |
| 真实crash/ANR | API36各一次；API29各一次兼容路径 | 系统事件+PID+时间+新进程脱敏读回 | API29无API30+退出信息，验证已有降级摘要 |

crash/ANR已有API34/36实证，归R不是新缺口；使用 `accept-hxa104-process-death.py` 的明确阶段。API29须先核对夹具支持降级，不能原样调用只支持ApplicationExitInfo的验证。恢复强杀矩阵按 [边界审计](hxa102-boundary-audit.md) 选受影响case：模型headers/body、文件发布、JS、browser/UI未结算、MCP/A2A、PRoot/CLI结果/ACK、Goal预算/删除/证据读取。每次真实执行开始→强杀→两次读回，服务执行计数不增加；已有记录不全部无差别重跑。故障注入只在debug测试组件，绝不进入正式24小时段。

## 7. EV-08/09/10/11：平台与有条件项目

**API/ABI**：API34/35普通arm64覆盖最低兼容smoke（启动/Storage/QuickJS/browser/通知/资源门控）；API35 16KiB必须用 `getconf PAGESIZE` 核实，跑QuickJS真实执行/超时/取消、WebView实际导航、PRoot页大小门禁/具备支持时真实guest、CLI握手。已有75项QuickJS/27项浏览器是历史覆盖，后续改动只刷新受影响集合。x86_64选择真实可运行镜像/执行主机，跑所声明ABI支持的native模块；若PRoot产品只声明arm64，验证明确unsupported，不为测试临时打包新ABI。Apple Silicon缺可用x86_64执行条件时列C，不把AAR/ELF静态检查写成运行通过。

**真实模型**：EV-09先核实用户提供的SSH转发端口（历史30008不是活服务保证）、实际model catalog、三协议端点和可用工具能力。现有runner默认取catalog首个模型；多模型比较前须补显式模型选择及记录，禁止更换首项后仍沿用原model标签。冻结 `evals/m10/fixed-evals.tsv` 与prompt/injection集合hash，按实际case ID计数；45项曾通过是历史证据，不再写Goal未实现。当前完整评测只对明确选定的一组服务/模型运行；多Provider/模型每组单独45项，不拼组凑数。默认预算：每组最多45项+每个失败case一次独立复验，每case≤5分钟、每组≤4小时；记录实际token/请求数，模型未按要求调用tool保留模型失败，不先改产品。其他模型、真实账号或付费调用没有配置/授权时列C；本地协议fixture成功不能代替这些结果。

**外部Connector/订阅**：EV-10无账号部分复用HXA-124/125/148～150导入/启用/停用/卸载及M11冷绑定/取消/凭据隔离fixture证据。待真实账号项目分别执行：有效配置一次只读调用→错误凭据拒绝→服务权限拒绝→厂商撤销→恢复授权后显式重连一次；核对无凭据泄漏/自动重放，真实撤销与本地删除Secret分开。每项需独立可撤销测试scope；没有则BLOCKED。禁止撤销用户日常账号、不发外部写消息、不读取其他App token。Claude/Grok按用户决定保持搁置；Codex/Copilot只在当前账号入口可用且获真实调用授权时测试，不沿用旧登录成功推断额度可用。

**可选真实API长稳目标**：若执行范围明确包含模型服务持续调用，在EV-09下单列C项，不能与“API29/36”操作系统版本长稳混淆。先用已授权本地SGLang固定模型跑2小时pilot，再选定一个API36 developer主App进程24小时；每5分钟一条无工具固定合成文本请求，共288条，输出上限128 token，每条deadline60秒。记录实际输出token、输入量、HTTP状态、SSE正常终结、连接/线程/FD与解绑；首个非预期失败停止取证，不通过增加请求重试凑数。结束时已发请求须全部唯一终结，无活跃流。模型目录变化、SSH转发断开、服务重启分别记外部故障而非猜测App泄漏。付费/订阅服务须预先写明账号调用授权和额度上限，否则BLOCKED。该项夹具待实现，当前不自动启动、不计入72小时必需设备预算，也不以45项能力评测替代它。

**R8/升级**：EV-11先确认HXA-122 applicationId/签名决定未完成，因而只验收当前构建形态。使用隔离测试签名的安装副本，不能改正式签名配置；同key旧版本合成会话/Goal/Workspace→升级当前版本，验证迁移/Keystore可读和记录保留，卸载不是升级。API29/36两flavor各一次，覆盖启动/导航/文件/浏览器/本地API流/MCP；developer再覆盖companion握手和一个允许的Job。若release不可安全安装或无UI驱动夹具，标C/NOT_IMPLEMENTED，不用Debug instrumentation冒充release运行。Debug crash hook必须不存在于release。商店审核、正式release签名和listing仍归M12。

## 8. 统一验收与方案改进

所有包必须先冻结case集合、工作量、最大时长和阈值，再启动；输出至少含：原始stdout/stderr、JUnit/实际step结果、请求数/ID、设备/安装身份、采样时间与失败、首轮及复验结果。退出码0不是唯一成功条件。外部副作用只用合成/本地fixture，保护并行任务的AVD、端口、目录和账号。

资源有意持久化增长、系统代理自然锯齿、observer产生的临时对象与未释放页面要分开。浏览器子计划的数值只是筛查门限，不能自动认定源码泄漏。JNI target归零只证明该类/负载，不等于整个进程零native引用。不能用资源缺项的“部分通过”关闭系统Binder目标。

每包首次失败先取证结束；若是夹具bug可以在同一测试检查点修复并开新attempt，保留原失败。若是产品bug，独立登记最小修复，不边跑长稳边改生产APK。停止条件还包括设备失联、时钟/采样缺口、源/包hash改变、磁盘不足和其他任务占用。恢复模型会话先读run-index与心跳，不重新启动已有runner。

总体报告至少分成：当前执行通过、复用历史证据、失败待修、条件阻塞、真机/发布排除。只要G包有NOT_IMPLEMENTED/INCONCLUSIVE，不能宣称“全部模拟器验收完成”；C包未完成时可说“无外部条件的模拟器范围完成”，必须同时列C清单。不创建无法兑现的自动通知或保证模型持续在线；runner独立落盘，已有任务的监控由执行者明确配置。

## 9. 不由模拟器关闭的项目

- HXA-094/095：Root管理器真实grant/revoke/loss、RootService crash和高阶只读工具，仍需rooted物理设备；adb root或shell su不是应用授权证据。
- OEM真实热限/温升/功耗、真机冷启动p95/jank、真实低内存与自然Doze、物理4/16KiB最低设备矩阵。模拟器可验证契约，不替代这些结果。
- HXA-105真机30分钟收益/资源与生产启用门禁；child/workflow仍未进入产品，本计划不启动新实现。
- HXA-120～123正式发布物、SBOM/最终签名/applicationId/商店审核；可做EV-11技术预检，不能宣布发布完成。
- HXA-126/129/130及ADR-0030/31/32未接受的新增功能，不生成“待测已实现能力”。
- Chromium/AOSP源码级修补、符号化及系统问题根治：可在EV-02形成证据，不以模拟器24小时通过推断上游已修复。

## 10. 小模型总执行Prompt

```text
请执行 docs/development/emulator-verification-master-plan.md，浏览器专项按 browser-autofill-soak-plan.md。
先完成EV-00清单：核实status/roadmap/当前源码和历史完成记录，把G/R/C/X逐项映射到真实case和命令。
当前只增加测试夹具/runner/证据，不新增产品功能。按AGENTS一次一个独立测试检查点，不重开已完成HXA。
先EV-01及所有新runner的短pilot/故障检测，再冻结产物。先短项排除明显失败，再在独占设备跑EV-02/03长稳。
同设备只允许一项任务；不得在长稳期间安装、清数据、注入GC或用强杀脚本取证正常轮次。
EV-04～08补缺口并按影响复用旧证据；EV-09～11缺条件时记录BLOCKED，继续独立可执行包。
不要读取真实密钥、付费账号或修改生产功能来让fixture通过。Claude/Grok保持搁置，真机门禁独立。
每包保留全部attempt，首次失败先取证归因；明确是测试bug可修夹具开新轮，产品bug独立登记。
EV-12逐行复核原始证据、时间/轮数/哈希/PID/skip和请求执行次数，不能仅信脚本PASS。
上下文恢复先读run-index/handoff/心跳，正在运行的任务只接管监控，不重启。等待由外部runner承担。
最后交付当前通过/历史复用/失败/外部阻塞/真机发布排除五类结果，不把任何缺项省略成全绿。
```
