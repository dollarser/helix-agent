> 证据快照：本页的状态、分工、命令结果和设备仅对应记录时点，不作为当前开发指令；当前入口为[实施状态](../../development/status.md)。

# main 功能、测试与体验优化待办

> 历史执行账本：下列勾选项、待办和追加日志保留各自时点。当前开发状态以 [status](../../development/status.md) 为准；Goal 当前契约收敛于 [ADR-GOAL-001](../../adr/goal/001-lifecycle-and-completion.md)，旧“绑定/复核/完成证据仍待接通”不是当前任务。后续工作树提交与合并状态不能从这里的历史“已推送”或“未提交”推断。


日期：2026-09-06。范围：M0～M11 已合入 main 的实现及当前收尾修改。按所有者最新要求，长稳留到后期；先逐项完成功能与其他测试，再统一优化交互和界面。真机仍不在本轮测试范围。清单是待办，不是验收通过声明。

## 持续开发 Goal

2026-09-06，所有者明确要求设置持续 Goal，直至当前范围全部修复和优化完成。当时创建的开发 Goal 已随本轮收口关闭；此状态仅指该历史任务，不声明当前会话是否存在活动 Goal。执行期间不设置任意 token 截止，一次推进一个 HXA 检查点，以实际源码、测试与证据更新本清单。HXA-102生产接线与第一阶段已完成约定范围；HXA-147交互验收已形成完成记录，实现、验收与累计修改暂存收口均已完成，不以一次构建通过或阶段小修作为整个 Goal 完成依据。第三阶段长稳保持后置、当前不启动；真机和所有者明确暂不核实的付费调用仍按已有边界记录。

2026-09-08 交付更新：566 个已审核文件已形成提交 `8369043` 并推送至 `origin/main`，远端 SHA 与本地一致。下文“暂存/未提交”描述保留为验收时点记录；本段仅更新 2026-09-08 的交付事实；当前事实查阅 [实施状态](../../development/status.md)，当前任务由所有者请求确定。

## 第一阶段：功能与非长稳测试

- [x] **PRoot 成功结果持久恢复**：非破坏性取回、验证/会话私有保存、精确 ACK、结果查看与显式确认已接通；正常/待核查调用的完整 ChatScreen 导航、离线、owner、归档异常及确认前后进程终止矩阵已验证。宿主 2,637 项及 Debug/Release 门禁已刷新。见 [四项要求与证据对应表](proot-result-durable-recovery-gap.md)，不扩写为 HXA-102、外层 UI/设备总矩阵或整个 Goal 完成。


- [x] **恢复提交后的会话刷新**：修复页面早于启动恢复读取旧状态后不刷新；两API确定性回归及结果恢复UI6kill/12恢复通过。见 [缺陷记录](../../bug-fixes/2026-09-07-recovery-open-conversation-stale.md)。

- [x] **CLI结果持久化后确认**：正常订阅路径、三交接窗口、篡改拒绝、恢复正文与 Runtime 禁用后本地回读已验证；完整 Goal 成功结果在持久化+ACK后强杀，双API四适配器8kill/16恢复通过，运行分支另2kill/4恢复。见 [收口证据及兼容边界](cli-result-durable-recovery-gap.md)。

- [x] **CLI 已确认日志容量回收**：修复128条后永久拒绝新请求；仅回收已确认终态，JVM134/134及满容量设备后续Goal通过。见 [缺陷记录](../../bug-fixes/2026-09-07-cli-journal-acknowledged-capacity.md)。

- [x] **CLI 未确认日志到期标记**：30天 EVIDENCE_EXPIRED先保存marker再清理载荷，失败可重试、同Job不再执行；JVM137/137及App612/612通过。API29/36到期无副本/有副本4kill/8恢复、未到期另2kill/4恢复通过，真实Binder/UI/文件清理留证。时间通过本次fixture metadata加速，见 [缺陷记录](../../bug-fixes/2026-09-07-cli-unacknowledged-evidence-expiry.md)。

- [x] **文件发布后未结算Goal强杀**：测试APK暂停真实write返回，API29/36 2kill/4恢复通过；文件不变、ToolCall INTERRUPTED、无ToolResult、审计保留不确定调用、无重放。原回填模式另2kill/4恢复通过。证据 `file-settlement-boundary-summary.json`，暂停点与自然时序边界明确区分。

- [x] **M11中断Job查询/停止入口**：已接入变体接口、ChatService及会话列表内恢复组件；API29/36 × 四适配器实际组件点击、8次主进程kill/16次恢复通过，保持原Job/hash/预算且不重发。App JVM 608/608，构建与静态门禁通过。证据 `cli-recovery-ui-summary.json`；仅验证生产组件绑定真实服务，完整导航/布局与成功结果导入仍单列。
- [x] **订阅恢复入口发现边界**：较旧Turn保留、会话隔离、记录过滤和瞬态状态刷新，API29/36 × 两发行包16/16通过；证据 `subscription-discovery/result.json`，无Runtime或账号调用。
- [x] **CLI恢复会话导航**：生产ChatScreen列表选会话→滚动查询→停止，双API四适配器8kill/16恢复通过，证据 `cli-recovery-navigation-summary.json`；外层导航、大字体/语言布局和成功结果导入另验。

- [x] **输出校验失败待核对**：已执行但输出schema失败的调用保留效果不确定性；回归先红后绿，相关260项、两发行包构建和静态门禁通过。见 [缺陷记录](../../bug-fixes/2026-09-07-cancelled-execution-review.md#case-2026-09-07-invalid-output-effect-review)。

- [x] **当前累计修复全仓JVM复验**：CLI结果/到期修改后34个实际Test任务强制执行，2629/2629，无失败/跳过；root lintDebug、Spotless、Detekt通过，1069个源码/配置清单及hash前后无变化。最新证据 `post-cli-full-host-result.json`，历史2620项快照保留；不替代剩余设备和功能验收。

- [x] **Goal 双API双变体当前基线**：协调/绑定/预留、loopback停止与wake限额、Goal表单六类，API29/36 × Consumer/Developer每组28/28，合计112/112。证据 `post-cli-goal-device-summary.json`，不代替完成证据或真实账号流程。

- [x] **Accessibility Goal kill**：修正无关窗口内容变化造成的STALE_TOKEN；API29/36真实ui.snapshot→ui.click后2kill/4恢复通过，点击计数保持1、预算精确结算且无重放。证据 `ui-generation-kill-summary.json`；未结算窗口及其他生命周期验收另列。

- [x] **浏览器点击/在途导航Goal kill**：API29/36真实节点点击后，宿主确认导航与模型回填再kill，共2kill/4恢复通过，无动作重放、预算正确结算，清理完成。证据 `browser-goal-kill-summary.json`；Accessibility UI动作仍待验。

- [x] **文件完成后Goal回填kill**：API29/36实际write完成后在模型回填中杀App，共2kill/4恢复通过；文件/完成调用保持、预算精确结算、无启动请求重放。证据 `file-goal-kill-summary.json`，发布后未结算窗口已由 file-settlement-boundary-summary.json 单独验证。

- [x] **原子文件层实际kill**：API29/36临时写入/发布后共4次SIGKILL、8次恢复验证通过，旧/新文件完整性与显式孤儿清理幂等成立。证据 `file-publish-kill-summary.json`；不包含Goal/ToolCall链或启动自动清理验收。

- [x] **超时执行边界**：未提交与执行后超时分别保留已确认零副作用和待核对信号；两项回归先红后绿，相关260项通过及构建/静态检查通过。见 [缺陷记录](../../bug-fixes/2026-09-07-cancelled-execution-review.md#case-2026-09-07-timeout-execution-boundary)。

- [x] **执行后取消待核对信号**：修正副作用未知取消被普通失败结算的问题，两个回归先红后绿，framework/files260项通过，构建与静态门禁通过；见 [缺陷记录](../../bug-fixes/2026-09-07-cancelled-execution-review.md)。

- [x] **write 发布后异常结算**：真实写入后注入 I/O 失败，修正“未写入”误报并要求核对目标；相关 JVM 347 项无失败/跳过，两发行包构建与静态门禁通过。实际文件阶段进程死亡仍待验；见 [缺陷记录](../../bug-fixes/2026-09-07-write-post-publication-failure.md)。

- [x] **PRoot主App与M11/MCP剩余组**：API29/36各PRoot17/17、M11/MCP35/35；执行/stdio取消/重定基线、四适配器夹具和Runtime死亡恢复等均无跳过。证据 `current-proot-main-remaining/`、`current-m11-mcp-main/`，非真实账号/法律分发验收。
- [x] **语言与all-files条件矩阵**：共38通过、4个明确API或授权条件assumption，零失败；API36 all-files授权6/6，授权撤回后分支与API29不支持分支另记；AppOp恢复default。证据 `current-language-allfiles/`。
- [x] **Goal真实模型UI预算/Continue流程**：两API × 两发行包4/4，8次真实Goal模型调用；UI创建不运行、预算暂停、界面扩预算不自动运行、显式Continue、持久Goal/run/Turn关联与计数通过。证据 `goal-real-ui-current-matrix/verified-summary.json` 和8张UI截图；完成证据仍未接通，视觉问题另列。

- [x] **发行包入口差异复验**：API29/36 × 两发行包共8/8；consumer固定Standard且无egress编辑入口，developer切换/egress持久化与撤回通过。证据 `current-flavor-ui-result.json`，不等于全部M11真实账号集成验收。

- [x] **CLI匿名端点检查**：API36原轮4/4；API29原轮3/4（Grok解析/连接失败），空闲专用模拟器显式DNS重启后4/4。原失败与环境变更留证，不是账号/付费调用验收。证据 `current-cli-anonymous-endpoints/` 与 `current-cli-anonymous-endpoints-api29-dns-fixed/`。

- [x] **额外App完整回归矩阵**：20个实际测试类，每API/发行包59项，API29/36 × consumer/developer共236/236、零跳过。首次误用文件名作RunControl类名导致各组57通过加1个初始化失败，原记录保留；修正选择器后整组复验通过，非产品代码修复。证据 `current-additional-app-fixed-result.json`。

- [x] **额外模块本地回归**：API29/36各22/22（Root未授权5、Accessibility完整服务1、CLI Runtime本地契约16）；零跳过，合成凭据，非真实账号。原服务测试虽通过但清理存在缺陷，随后已修正并重新验证，见下项。
- [x] **Accessibility宿主强停证据收口**：空enabled列表清理已修正并在两API按原顺序复验；另修正setup返回后进程已被instrumentation杀掉的问题。新运行中强停API29/36均通过，各有完整服务1/1及恢复1/1；setup有意中断，不计JUnit通过。见 [清理修复](../../bug-fixes/2026-09-07-accessibility-test-empty-settings-cleanup.md) 与 [强停顺序修复](../../bug-fixes/2026-09-07-accessibility-host-kill-after-instrumentation-exit.md)。

- [x] **QuickJS修复后全仓主机复验**：34个实际Test任务强制执行，2,602/2,602、零跳过；18模块Debug Lint、Spotless、Detekt通过。证据 `post-json-full-host-result.json`、独立XML与1,057项源码/配置hash快照；18个聚合test任务不重复计入用例。

- [x] **PRoot冷绑定与包状态宿主验收**：HXA-083脚本在API29/36各八阶段通过，包含卸载、强停、实际进程死亡与重连。证据 `build/main-verification/proot-host-lifecycle/083-results.json`。
- [x] **PRoot生命周期前置与forced idle**：修正前台am kill未杀进程却当低内存证据的问题；API29/36修正后完整脚本均通过，独立forced idle作业各1/1且恢复原配置。见 [缺陷记录](../../bug-fixes/2026-09-07-proot-lifecycle-host-preconditions.md)。不等于真实内存压力或自然Doze验收。

- [x] **PRoot模块当前两API复验**：API29/36各56/56，无跳过，包含内置资产安装与执行；专用通知权限条件已满足。证据 `build/main-verification/current-proot-api*/`。宿主分阶段生命周期仍另验。

- [x] **当前核心整组回归复验**：API29/36 × 两发行包各87/87，共348/348，无跳过；Provider fixture修正已进入整组。证据 `build/main-verification/current-core-fixed-api*/`。API29浏览器短时27/27、文件38/38、Android工具30/30另有独立模块证据。
- [x] **Provider phase4波动核查**：源码要求完整工具调用，模型此次返回stop被正确判失败；无生产代码修复结论。同配置同APK一次复验browser4/4通过，原count0 FAIL保留。此次未改探测门禁、请求或模型配置。证据 `build/main-verification/fixed45-current-snapshot-api34/browser-probe-retry/`。

- [x] **Provider UI 与 M11 管理行兼容**：测试清理保留 Runtime 管理行、作用于目标可编辑行并显式滚动，API 29/36 × 两发行包共 20/20，无跳过。见 [缺陷记录](../../bug-fixes/2026-09-07-provider-ui-managed-row-fixtures.md)。后续两发行包核心矩阵各87/87 × 两 API，原 developer 83/87 的失败记录仍保留。

- [x] **QuickJS Android 深层 JSON 栈溢出**：共享校验器改为显式容器栈，保留 512 层上限和原深层往返测试；JVM 85/85、API 29/34/36 各75/75，无跳过，静态检查及四 App APK 构建通过。原失败保留，见 [修复记录](../../bug-fixes/2026-09-07-quickjs-json-validator-stack-overflow.md)。后续当前核心矩阵与同包固定45项已安装并验证包含该修复的主 App APK。
- [x] **Accessibility 评测改用当前模型快照**：不再预先提供 setup token，由模型 ui.snapshot 后执行精确点击；生产失效规则不变，新主包/测试包 API34 真实模型3/3。原失败保留，见 [修复记录](../../bug-fixes/2026-09-07-accessibility-eval-current-snapshot.md)。后续同包45项已完成核对，含一次browser探测复验，见统一结果。

- [x] **Goal API 29/36 两发行包组件/UI 回归**：修正新装首启前置后四组各 44/44，总计 176/176，无跳过。原始通知失败和一次 Worker 超时保留；后者未复现且根因未确定。见 [缺陷记录](../../bug-fixes/2026-09-07-goal-reminder-first-launch-fixture.md)。
- [x] **固定评测 MCP 取消边界修正**：首轮宿主 mcp-004 execution count 为 0，保留 FAIL；添加真实服务端执行开始确认后，新测试包 4/4、计数 0/1/1/1，不放宽门禁。见 [缺陷记录](../../bug-fixes/2026-09-07-mcp-eval-cancel-before-remote-start.md)。后续同一当前主包/测试包的45项已复核通过，含一次保留失败的browser探测复验，见下方统一结果。

- [x] **当前 APK API 29/36 模块与 CLI 矩阵**：每台 77/77（显式入口 1、IPC 16、存储 47、恢复 9、Provider fixture 契约 4），无跳过；新装 Runtime FORCE_STOPPED 的首轮失败及显式打开前置记录在全量验证报告。完整 App 矩阵和真实网络验收仍独立待完成。

- [x] **当前 APK API 34 模块与 CLI 恢复复验**：CLI client 16/16、storage 47/47、跨 UID fixture 恢复 9/9，无跳过；五个安装 APK 的设备 hash 与构建文件一致。证据 `build/main-verification/current-api34-modules/`。其他 API、真实模型与主 App 死亡矩阵仍待完成。

- [x] **M11 合并后的首组 JVM 回归已执行**：91 suites / 672 tests，666 通过、6 assumption skip、0 failure/error。6 项均为 Connector 外部验收开关/本地样本条件未提供，未计为通过。日志与结果见下文。
- [x] **HXA-102 本轮 Goal 生产接线**：Goal/run/Turn、显式 Continue、预算持久化、取消/暂停、恢复与副作用不重放已完成本轮非真机验收；ADR-GOAL-001 完成证据、真实模型 write/edit/PRoot、复核 UI 与读取中断均有独立证据，见 HXA-102 和边界核对表。
- [x] **Goal 接线前置修复**：预算耗尽时原子关闭 run、保存 outcome，并校正双时长限制顺序。API 34 恢复测试 9/9；见 [修复记录](../../bug-fixes/2026-09-06-goal-exhausted-run-left-open.md)。不等于生产接线完成。
- [x] **Goal/run/Turn 持久关联**：Room v8 新增关联表，TurnCoordinator 创建时同事务绑定，拒绝跨会话/关闭或缺失 run；API 34 应用组件 15/15、迁移 20/20。ChatService 创建/Continue 与模型预算已接线；工具预算、时长与 UI 仍待完成。
- [x] **Goal 创建/Continue 协调组件**：GoalRunCoordinator 创建 READY Goal、消费 Continued/StartRun、同事务创建 run 与 Turn、取剩余额度；扩预算持久化且不自动启动。API 34 组件组合 16/16 通过。后续 ChatService 创建/Continue 与模型调用已验证；UI、工具和时长预算仍待接通。
- [x] **Goal run 结束处理**：TurnCoordinator 的终态事务消费持久关联，同步关闭 run/更新 Goal/audit；普通完成暂停、取消关闭、未知副作用进入 INPUT_REQUIRED，且 Continue 拒绝未核清的历史调用。组件设备回归已通过；服务创建入口与模型预算随后已接线；UI、工具和时长仍待完成。
- [x] **模型输出剩余额度绑定**：ChatService 每次发请求前扣除累计用量和输入估算，约束实际输出上限；无正输出额度不调用 Provider。单元测试 7/7、API 34 ChatService 回归 16/16；见 [修复记录](../../bug-fixes/2026-09-06-model-output-remaining-budget.md)。不等于 Goal 持久预算接通。
- [x] **Goal run 旧快照计数回退**：checkpoint/finish 在 SQL 更新条件中比较当前持久计数，拒绝延迟旧快照把四项用量写小；API 34 创建/结算/恢复回归 17/17。见 [修复记录](../../bug-fixes/2026-09-06-goal-stale-run-usage.md)。
- [x] **Goal 持久预留与恢复组件**：Room v9 journal 与恢复/Turn 终态结算已实现，API 34 Goal 组件/恢复 22/22、存储/迁移 21/21；覆盖重复请求、已知/未知结算、重开数据库、并发准入。
- [x] **Goal 模型预算生产接线**：ChatService 创建/显式 Continue 服务入口、附件确认关联、真实 Provider stream 前预留与结束结算、异常流保留预留已接通；API 34 聊天与恢复回归 44/44，模型为脚本 wire fixture。
- [x] **Goal 工具预算生产接线**：调度前逐调用预留，已准入的调用尝试（含失败/拒绝）结算一次；超额调用持久化 BUDGET_EXHAUSTED，批次每个调用均有结果。API 34 聊天/Goal 回归 45/45，框架 JVM 146/146。
- [x] **Goal 单调时钟执行窗口**：预留至多 5 秒窗口、运行中续约，异常延迟停止准入并记全实际用量；API 34 聊天/Goal/恢复 49/49 通过，含真实 ChatService 脚本流阻塞后的 2 秒 wake 预算暂停。
- [x] **Goal 工具 deadline 与阻塞取消**：剩余时间在执行前动态下传；Dispatcher 等待期间检查取消，保留未知副作用语义。框架 JVM 150/150、API 34 回归 49/49；见 [修复记录](../../bug-fixes/2026-09-06-goal-tool-deadline-and-cancellation.md)。
- [x] **Goal 预留窗口真实进程 kill/restart**：两次 SIGKILL 后累计 5 秒/10 秒与模型预留保持，回拨/离线不返还预算，第三次 Continue 被拒绝；进程阶段恢复通过，常规回归 50/50。仅预算窗口证据，具体工具执行中断矩阵仍待完成。
- [x] **Goal 首批 UI 入口**：Goal 模式发送进入持久目标管理，创建/Continue/parked 预算编辑与持久状态/暂停原因展示已接线；Compose 表单与聊天/恢复组合 52/52。后续实际 UI→服务无 Provider 流程与相关组件 11/11；真实模型界面流程、完成证据与提醒仍待验证。
- [x] **Goal 提醒身份与已发布通知取消**：不同 Goal 的 PendingIntent/通知独立，实际 hash 冲突回归通过；API 34 3/3。后续生命周期、导航与并发接线见下列项目；身份修复见 [修复记录](../../bug-fixes/2026-09-06-goal-reminder-identity-and-cancel.md)。
- [x] **Goal 提醒生命周期首段接线**：检查点事务保存/清除、暂停状态设置、生产结算与恢复后重建队列、管理界面入口。core agent 169/169、consumer JVM 285 通过/3 条条件跳过、API 34 15/15；后续导航和并发验证见下列项目，按钮端到端仍待完成；详见 HXA-102 记录。
- [x] **Goal 提醒点击导航**：持久绑定定位会话、指定目标查看、等待 NavHost 就绪、Activity 重建不重复打开；API 34 13/13 + 导航重复 2/2 通过。打开本身不新增 run/Turn，完整通知 UI 仍待验证。
- [x] **Goal 提醒发布/取消竞态**：发布、替换和取消串行化，核实真实 WorkManager RUNNING 状态并等待取消完成后撤回通知；API 34 6/6，JVM 288 通过/3 条条件跳过。限当前单进程提醒路径，不等于停止所有后端执行。
- [x] **Goal 删除服务与提醒清理**：先检查无活动 run，取消提醒成功后原子删除记录，恢复遇到已删除 Goal 安全取消；生产删除与模拟器回归 16/16，JVM 288 通过/3 条条件跳过。删除 UI 和删除中途进程死亡仍不属于本组证据。
- [x] **Goal 检查点恢复与消费**：相同检查点保留 work，协调器消费匹配成功记录，新检查点不被旧记录清除；API 34 11/11。WorkManager 历史丢失时仍依 ADR 补发，不声称 exactly-once 或用户已读。
- [x] **Goal 提醒按钮真实 UI 回归**：GoalDialog 点击保存、关闭重开、移除，生产 Room 与 WorkManager 一致，既有 Goal/run/Turn 不变；组件与检查点组合 API 34 5/5。无 Provider，仍不是完整通知栏或真实模型聊天流程。
- [x] **Goal 完成证据契约决定**：[ADR-GOAL-001](../../adr/goal/001-lifecycle-and-completion.md) 于 2026-09-08 获所有者明确接受。
- [x] **Goal 完成证据实现**：ADR-GOAL-001 的绑定、来源/快照、复核 UI、失效反馈和原子完成已接入。API 29/36 三种本地协议真实写入与完成流程 6/6；发现并修复 Anthropic 缓存 token 漏记，双 API 重跑通过，相关强制 JVM 896 项通过。复核暂存、完成事务提交前/后双 API 真实强杀恢复 6/6；关闭预览/离开弹窗取消挂起保存及复核/Continue 回归双 API 26/26。复核录入事务提交前双 API 强杀恢复 2/2；edit 已接现有 Scope 读取、完整快照、候选与自动验证，14 JVM / 64 设备项通过；edit 实际模型/审批/完成 UI 双 API 三协议 6/6；PRoot 持久归档来源、候选/全文预览与完成规则已接线，Goal 包双 API 66/66；PRoot 真实模型/审批/归档完成双 API 三协议 6/6、归档人工复核 UI 双 API 2/2；阻塞读取取消接线已修复（6 JVM、78 设备回归）；快照读取检查点双 API 真实 SIGKILL/恢复 2/2；最新全仓 JVM 2,710/2,710 与 Debug 门禁通过，完成证据子项收口；详细阶段证据见 [HXA-102](../../completion-records/HXA-102.md)。
- [x] **Goal 预算编辑 UI 与组合回归**：非法输入/放弃/六个上限保存经真实 UI 与 Room 验证，不新增 run；当前 consumer 的 12 个 Goal 设备类 40/40。真实模型 UI 与独立后端 kill 矩阵未被该组合替代。
- [x] **Goal 重复通知点击**：生产 PendingIntent 对已有 Activity 的重复打开回归先失败，添加显式复用标志后导航 2/2、组合 7/7；Activity 实例与 Goal/run 保持一致。通知栏手势仍单独验证。
- [x] **Goal 系统通知栏点击**：API 34 真实 WorkManager 通知经 SystemUI 节点点击后显示选定 Goal，绑定会话、Goal/run/Turn 均保持不变；通知/提醒/预算 UI 组合 7/7。初版文本搜索未命中可见通知，逐节点读取后通过；其他 API 和进程外启动仍待覆盖。
- [x] **Goal 删除 UI**：GoalDialog 接入生产隐私删除服务，明确确认/取消；提醒清理失败保持目标并显示可重试错误，成功后列表移除、提醒终止、会话 Turn 保留。状态为 RUNNING 或存在未关闭 run 均禁用删除，服务再次校验。API 34 相关组合 17/17；删除中途进程死亡另测。
- [x] **Goal 删除中途进程死亡**：专用 API 34 两次真实 SIGKILL，分别在取消提醒后、删除事务提交前，以及删除成功提交后。前者恢复保留 Goal 并幂等重建提醒，后者不复活 Goal；会话 Turn 保留。独立 fixture DB 使用生产协调器/Room/WorkManager，无真实模型/工具执行；随后当前 consumer 全部 13 个 Goal 设备类组合 42/42，其中进程测试为 control 路径。
- [x] **模型请求取消及时释放连接**：修复共享 OkHttp 阻塞读取未响应取消的问题；等待响应头/正文两项真实 socket JVM 回归通过，生产 Goal Stop/单次时限设备回归由 2/2 失败转为 2/2 通过。Provider JVM 276 通过，当前 14 个 Goal 类加模型发现 UI 组合 48/48；模型流实际 SIGKILL 和真实服务中断仍单列。
- [x] **Goal 界面取消与保存重试**：保存/提醒不再把协程取消转为普通状态错误，保存重试清除旧错误；保存取消 Job 回归先失败后通过，相关 API 34 组合 11/11。提醒取消竞态与其正常操作回归区分记录。
- [x] **生产 HTTP 模型流 SIGKILL 首组**：API 34 宿主 fixture 保持连接，App PID 核实后杀进程；两次启动保持预算计账 5076 tokens/5000 ms、调用数 1，宿主请求计数不增长。进一步修复恢复后 ModelCall 遗留 RUNNING，覆盖历史记录、元数据保留及幂等审计，相关设备组件 20/20。首组仅 OpenAI Chat 脚本模型；后续三协议双边界结果见下一项。
- [x] **三协议模型流双边界 SIGKILL**：Chat/Responses/Anthropic × 等待响应头/部分正文，当前同一 APK 共六次真实杀进程、十二次启动恢复断言成功，6/6；每组调用数 1、预算/状态幂等、宿主请求数不增长。宿主脚本模型、API 34，真实服务与工具后端另测。
- [x] **API 29/36 强杀脚本与模型恢复扩展**：修复 SELinux 拒绝 run-as SIGKILL 的宿主权限路径，保留原失败；当前安装 APK hash 核对后三协议双边界 12/12、12 次实际 SIGKILL、24 次恢复无重放。Goal 预算/删除四组另通过，累计 8 次强杀。仅模拟器宿主 su 0 控制与 HTTP fixture，不计应用 Root 或真实服务验收。证据 `model-kill-api29-36-host-signal-fixed/`、`goal-kill-api29-36-host-signal-fixed/`；[缺陷记录](../../bug-fixes/2026-09-07-emulator-sigkill-selinux-authority.md)。
- [x] **当前工具调度与恢复基线**：最新 consumer 的 ToolSchedulerDeviceTest + ProcessRecoveryTest 18/18，通过生产调度集成及持久恢复 fixture；该组合不含真实工具后端 SIGKILL，不替代下列中断矩阵。
- [x] **MCP 执行中主进程强杀**：developer API 29/36 各一组，真实 SDK/HTTP 远端开始 + RUNNING 后 SIGKILL，socket EOF；两次启动不重发，原调用 INTERRUPTED 且恢复审计保留 uncertainToolCall，审批已消费，Goal 预算不返还。脚本模型/合成远端，证据 `mcp-live-kill-api29-unique-call/`、`mcp-live-kill-api36/`；显式后续 Continue 与其他后端仍单列。
- [x] **跨 Turn 重复 Provider 工具调用 ID 主键冲突修复**：本地执行/审批 ID 与协议 ID 分离，持久原 ID/localId 映射并按原协议 ID 回填。API 29 保留旧冲突数据连续复用两次、API 36 一次，三组强杀/六次恢复通过。JVM 新增四项身份/历史测试双变体通过；[缺陷记录](../../bug-fixes/2026-09-07-provider-tool-id-local-identity.md)。
- [x] **ID 分离后普通完成/拒绝/同 Turn 多轮回填**：API29/36 × 双变体，新增多轮复用与既有两类回填共16/16；未知合法工具拒绝为DENIED，Provider请求保留原ID且无本地ID。证据 `repeated-tool-round-device-final/`。
- [x] **ID 分离后全仓 JVM/root lint 刷新**：34个实际Test任务2610/2610、零跳过，根lintDebug/Spotless/Detekt通过，证据 `local-tool-id-full-host-result.json`；已带入Connector样本条件。
- [x] **ID 分离后 Goal/附件组合与预算断言关联**：修正测试以持久wire→local映射定位调用，原核心四组各88通过/1旧断言失败保留；修正后完整附件26+Goal44，双系统双变体280/280，零跳过。证据 `local-id-goal-attachment-summary.json`。
- [x] **ID 分离后固定45刷新**：核实11组同一安装主/测试APK与数据集hash，45/45首次通过、零重试。证据 `local-id-fixed45-summary.json`。真实SGLang模型；Root系统状态、MCP/A2A远端仍为合成fixture。
- [x] **A2A已知Task ID查询中主进程强杀**：API29/36各一次，Task ID先持久化，实际GetTask连接未返回时SIGKILL；四次启动恢复不SendMessage也不GetTask，显式原ID对账后COMPLETED，远端发送次数保持1。证据 `a2a-live-kill-current/verified-summary.json`。
- [x] **A2A发送响应前未知Task ID强杀**：API29/36实际SendMessage开始后SIGKILL，taskId未获得；两次启动不发送/不查询，显式对账抛NeedsReview；各SendMessage1/GetTask0。同APK已知ID查询两组也回归通过，四组/八次恢复证据 `a2a-process-boundaries-summary.json`。
- [x] **QuickJS执行中主进程强杀**：API29/36，真实隔离执行线程CPU采样后SIGKILL主App，worker退出；四次恢复预算/审批/uncertainToolCall保留，无模型重发或worker重建。证据 `javascript-live-kill-summary.json`，scripted模型+真实QuickJS执行。
- [x] **审批等待中主进程强杀**：developer API29/36，AWAITING_APPROVAL真实待决后SIGKILL；两次恢复decision/consumedAt仍空、无工具结果/worker，uncertainToolCall=null，无模型重发。同APK实际执行两组也回归，总4组/8次恢复；证据 `js-approval-boundaries/verified-summary.json`。
- [x] **CLI Runtime客户端主进程死亡**：API29/36 × 四Provider共8/8，RUNNING fixture Job后SIGKILL主App，十六次原ID恢复/取消/对账稳定CANCELLED、请求hash/创建时间不变。证据 `cli-owner-kill-matrix/verified-summary.json`。仅直接生产Runtime客户端+DEBUG wait，不含真实账号或ChatService/Goal订阅Provider绑定。
- [x] **PRoot guest 执行中主客户端死亡**：API29/36真实guest启动标记后SIGKILL主App，两组/四次原Job恢复稳定CANCELLED，输入hash/执行ID/terminal commit保持；证据 `proot-owner-kill-summary.json`。直接生产客户端验证，完整ChatService/Goal绑定仍待测。
- [x] **审批恢复后显式拒绝**：API29/36等待审批时SIGKILL，六次恢复检查含生产服务拒绝及下一进程验证DENIED；无消费/工具结果/worker/模型重发，预算不返还。证据 `approval-recovery-denial-summary.json`；服务入口验证，UI点击与接受后的处理仍单列。
- [x] **中断审批时间线显示修复**：已中断Turn中的待决调用显示“已中断”，保留原审批事实；两系统真实恢复screen state验证、正常审批6/6、双变体JVM602/602与根lint通过。[缺陷记录](../../bug-fixes/2026-09-07-recovered-approval-timeline.md)。
- [x] **PRoot提交后失败副作用语义**：修复错误的零副作用标记，未知结果要求复核；真实guest失败与既有用例两系统10/10，developer JVM307/307。[缺陷记录](../../bug-fixes/2026-09-07-proot-failure-effect-semantics.md)。
- [x] **PRoot生产ToolCall/Job持久关联**：提交前版本化审计绑定已接入，记录失败不提交；完整Goal/Chat/审批/真实guest两系统强杀/四次恢复通过，原ID查取消/对账和预算不返还验证。另有提交前失败阻断，LinuxRunTool12/12、developer JVM307/307；[记录](../../bug-fixes/2026-09-07-proot-durable-job-binding.md)。用户对账UI与其余提交/终态阶段仍待补齐。
- [x] **M11完整Goal/订阅Job持久关联与恢复**：两系统四平台8/8强杀、16次恢复，原Job/hash/预算/无重发验证；DEBUG模型无账号。[记录](../../bug-fixes/2026-09-07-cli-durable-model-job-binding.md)。
- [x] **CLI冷启动按需OAuth初始化**：修复API29四TLS客户端初始化OOM，两系统恢复矩阵与Runtime JVM102/102通过；付费网络内存验收仍不计入。[记录](../../bug-fixes/2026-09-07-cli-runtime-lazy-oauth.md)。
- [x] **会话模型选择回填回归**：不同会话模型与旧会话null模型各核对首轮+两次回填请求；API29/36双变体完整附件112/112零跳过，证据 `session-model-backfill-summary.json`。[记录](../../bug-fixes/2026-09-07-session-selected-model-request.md)。
- [x] **PRoot原Job查询/停止入口**：时间线接入查询、按原身份停止；两系统强杀后的生产组件实际点击及CANCELLED终态、4次恢复通过，证据 `proot-recovery-ui-summary.json`。结果导入与完整导航/布局仍单列。
- [x] **中断会话不再显示发送中**：仅修正UI投影，保留核心恢复语义；双变体JVM608/608与两系统恢复通过。[记录](../../bug-fixes/2026-09-07-interrupted-chat-not-sending.md)。
- [x] **后端主进程中断剩余边界核对**：各阶段见 [对应表](hxa102-boundary-audit.md)。浏览器/UI 动作未结算双 API 4kill/8恢复；MCP 不明确结果后显式 Continue 双 API 2kill/4恢复均拒绝且零重发。此项为明确阶段证据核对，不替代最终设备矩阵或远端真实业务验收。
- [x] **MCP 工具网络取消边界**：生产 McpToolRuntime + SDK/HTTP fixture 验证执行线程中断及 deadline 后退出、连接断开、工具请求不重复；JVM 网络证据，实际 Android 后端 SIGKILL/远端效果恢复仍待测。
- [x] **Provider 页静态违规首组**：归并行回调、移除重复视觉状态、提取编辑模板逻辑；根 Detekt 53→51，Consumer JVM 288 通过/3 条件跳过、Lint/构建/Spotless 通过，API 34 Provider UI 5/5。剩余 51 项及全量门禁继续。
- [x] **ProviderService 托管回调归并**：保留双变体原实现，将相关依赖归入 ManagedProviderHooks；Detekt 51→50。Consumer/Developer JVM 分别 288/300 通过、各 3 条件跳过，双变体 Lint/Spotless 通过；设备及全仓门禁另验。
- [x] **M11 订阅流静态清理**：分离请求资格/发送与响应状态/有界读取，新增字节/事件/收尾边界回归；CLI Runtime JVM 74/74、Lint/Spotless 通过，Detekt 50→47。真实账号/设备恢复不由本项替代。
- [x] **M11 OAuth 解析静态清理**：拆分 Claude 回调与 Grok 授权响应解析，保留既有判断顺序；补充无效凭据响应边界，CLI Runtime JVM 77/77、Lint/Spotless 通过，Detekt 47→45。未调用真实账号。
- [x] **CLI 状态握手 Binder 死亡**：捕获 RemoteException 返回 Failed，Android Binder 注入由 1/4 失败转 4/4 通过；CLI Client JVM 24/24、Developer 300 通过/3 跳过，Lint/构建/Spotless 通过，Detekt 45→44。真实跨进程恢复仍待验。
- [x] **CLI Job 普通 Binder 通信异常**：提取传输并捕获 RemoteException，模块 Android Binder 注入 3/3、事务不重发；CLI Client JVM 24/24、Developer 300 通过/3 跳过，模块 Lint/构建/Spotless 通过，Detekt 44→42。真实 Job/PFD 跨进程恢复仍待验。
- [x] **CLI Job PFD 读取失败**：捕获 IOException，拒绝返回部分事件且不重发；修复前 1/3 失败，修复后 PFD/Binder 6/6 通过，CLI Client JVM 24/24、Developer 300 通过/3 跳过，Lint/构建/Spotless 通过，Detekt 保持 42。提交管道/真实跨进程恢复另验。
- [x] **CLI 提交管道写线程 EPIPE**：记录上传失败并有界清理，拒绝请求不产生未捕获异常，完整上传保持接受；API 34 上传/PFD/Binder 8/8，CLI Client JVM 24/24、Developer 300 通过/3 跳过，Lint/构建/Spotless 通过，Detekt 42。真实跨进程和持有管道中断另验。
- [x] **CLI 上传提前接受/保留读取端**：未消费完整请求的 ACCEPTED 不成为成功；有界清理、释放后线程退出、事务一次。API 34 上传/PFD/Binder 10/10，测试构建/Spotless 通过，Detekt 42 无新增；真实 companion 中断另验。
- [x] **当前 CLI Runtime 跨进程 fixture 恢复**：API 34 fresh-stopped 拒绝/冷绑定各 1/1；四 Provider 封装的 PFD 对账、RUNNING 取消/Runtime 自杀重绑恢复 8/8，生命周期日志佐证。debug 模型不调用账号；主 App 被杀/所有提交边界/真实订阅网络仍待验，Detekt 42。
- [x] **CLI 跨进程测试结构清理**：拆分运行中恢复类，消除深嵌套/类函数数量/未使用变量三项；保留原八项跨进程场景并重跑 8/8，测试构建/Spotless 通过，Detekt 42→39。
- [x] **CLI Service 启动方法清理**：提取 debug fixture，保留后端选择和取消语义；Runtime JVM 77/77、Lint/Debug 构建/Release 编译/Spotless 通过，新 Runtime 跨进程 8/8，Detekt 39→38。
- [x] **CLI 本地可用性判断清理**：保留拒绝原因顺序，实际禁用/强停/恢复绑定 3/3，包状态恢复原值；CLI Client JVM 24/24、Lint/构建/Spotless 通过，Detekt 38→37。
- [x] **CLI 绑定流程清理**：保留绑定拒绝/安全异常/空绑定/等待中断及解绑责任，回调注入 6/6、真实 Runtime fixture 跨进程 8/8；JVM 24/24、Lint/构建/Spotless 通过，Detekt 37→36。
- [x] **M11 测试 JSON/SSE 行长清理**：三段 fixture 字节 hash 不变，移除三处整文件行长豁免；Runtime JVM 77/77、Spotless 通过，Detekt 36→33。
- [x] **订阅模块双变体契约**：显式共享接口，保留 Consumer 无订阅实现和 Developer 原接线；双变体 JVM 288/300 通过、各 3 既有跳过，双 Lint/Spotless 通过，Detekt 33→21，无新增豁免。
- [x] **CLI Job 提交回复编码整理**：提取提交解析/结果映射，回复码及查询/取消/对账不变；Runtime JVM 77/77、跨进程 fixture 8/8，Lint/构建/Spotless 通过，Detekt 21→20。
- [x] **CLI 状态与载荷存储分离**：保持持久化路径及对账删除顺序，新增旧格式重开/删除失败回归；修正首轮测试数据后 JVM 79/79，跨进程 fixture 8/8，Lint/构建/Spotless 通过，Detekt 20→19。
- [x] **CLI 等待中断取消**：修复多连接下等待已退出但 Runtime Job 仍 RUNNING；同 Job 单次 CANCEL 并保留中断标志，失败证据与复验分开记录。Client JVM 30/30、Developer 300 通过/3 既有跳过、API 34 跨进程 fixture 9/9，Lint/构建/Spotless 通过，Detekt 19→18。
- [x] **Codex 连通性响应完成与读取上限**：要求明确 completed 终态及固定输出，有界读取后解析；四项修复前失败留证，解析 9/9、Runtime JVM 88/88，Lint/Debug 构建/Release 编译/Spotless 通过，Detekt 18→14。未复验真实账号网络。
- [x] **Codex 模型目录结构验证**：修复非对象项未分类异常与数字 slug 被当作模型名称；目录回归 8/8、Runtime JVM 96/96，Lint/Debug 构建/Release 编译/Spotless 通过，Detekt 14→13。首轮两项失败留证，未复验真实账号网络。
- [x] **订阅 Provider 设备契约测试整理**：保留断言，四平台 debug fixture 探测/模型流/聊天落盘 4/4；构建/Spotless 通过，Detekt 13→12。未复验登录或真实账号网络。
- [x] **Copilot 登录页职责分离**：保留布局/按钮/回调，Runtime JVM 96/96，API 34 入口 1/1、未登录六按钮状态符合预期；Lint/构建/Release 编译/Spotless 通过，Detekt 12→10。未发起登录、网络或删除凭据操作。
- [x] **Grok 登录页职责分离**：保留布局/错误文案/剪贴板与生命周期，Runtime JVM 96/96，API 34 入口 1/1、未登录按钮状态通过；Lint/构建/Release 编译/Spotless 通过，Detekt 10→8。未发起账号网络调用。
- [x] **Codex 固定探测职责分离**：保留提交/轮询/取消及文案，Runtime JVM 96/96，API 34 入口 1/1及未登录八按钮状态通过；Lint/构建/Release 编译/Spotless 通过，Detekt 8→5。未调用真实账号探测。
- [x] **Codex 登录页布局职责分离**：保留按钮/剪贴板/取消边界，Runtime JVM 96/96，API 34 入口 1/1，八按钮文案和状态与拆分前一致；Lint/构建/Release 编译/Spotless 通过，Detekt 5→3。未触发账号网络调用。
- [x] **刷新异常边界与根 Detekt 收口**：保留取消/参数异常类型及原控制器凭据规则；刷新回归 6/6、Runtime JVM 102/102，Lint/Debug 构建/Release 编译/Spotless/根 Detekt 全部通过（3→0）。未调用真实账号；其他全量门禁仍独立待验。
- [x] **依赖锁与辅助门禁**：补齐 CLI Client 设备测试 Lint 锁配置，35 锁复查通过；secrets/i18n/Python/双变体与 CLI 边界、MCP/A2A 既定字节码检查通过（官方 A2A SDK 为预期拒绝）。CLI 七制品 hash 通过，terms-of-use 自动读取 403 保留，详见 `main-auxiliary-gates-result.json`。
- [x] **PRoot 实际资产复核**：tar 锁 hash/大小、53 包版本/许可证元数据、两 APK 各 11 项资产一致；三个上游 deb 对应四二进制一致，179 常规 ELF 的 arm64/16 KiB 字节检查通过。符号链接别名不重复计数，不作设备或法律分发结论。
- [x] **Goal 剩余生产收口**：本轮范围收口：所有列明后端中断/取消/对账、完成证据来源与生命周期、真实模型关键流程已补齐对应证据；不扩写为自然 LMK、真机或长稳验收。
- [x] **HXA-100 Goal 三项固定评测**：当前 developer APK + SGLang，Responses/Chat/Anthropic 三项 3/3 通过。审批场景是实际 write 调用等待审批、零写入；当时 Goal 为 RUNNING，不称为持久 PAUSED。日志 `fixed-goal-cleanup-verified-api34`。
- [x] **当前合并构建的固定45项统一复验**：同一当前 developer 主包/测试包的45个精确case ID均通过，安装hash与构建文件一致；browser连接探测首次FAIL，未开始业务用例，同配置一次复验4/4，保留失败，不称首轮全过。证据 `build/main-verification/fixed45-current-snapshot-api34/aggregate-result.json`（firstAttemptAllPassed=false）。后续生产变化仍需按影响复验。
- [x] **M0～M11 完整 JVM 回归**：最新 edit/PRoot/读取取消修改后，34 个实际测试任务强制执行，2,710/2,710、零失败/错误/跳过；1,159 项源码/配置指纹前后一致。根 lintDebug、Spotless、Detekt 和两种 Debug 主包/测试包构建通过。证据 `post-archive-host-result.json`，完整命令/原日志 `post-archive-host-command.json` / `post-archive-host.log`；Release 和设备矩阵独立推进。
- [x] **静态与构建门禁**：最新 edit/PRoot/读取取消修改后的根 Debug/Release lint、Spotless、Detekt、App/Runtime 八个 Debug/Release 主 APK 及两个 App 测试 APK 已刷新通过；见 `post-archive-host.log`、`post-archive-release.log` 与八产物指纹 `post-archive-release-result.json`。Release 为 unsigned，不代表发布签名或设备验收。独立依赖/资产/许可证检查按各自记录判断。
- [x] **API 29/36 设备回归**：完整 App 十二组946/946保留原快照；后续生产变化按影响以30/30和完整订阅Chat8/8复验，M11与模块专项另列。源码差异与安装hash均已保存，未将不同快照混称同一次全量执行。
- [x] **最新 App 操作与存储/CLI Client 子矩阵**：20 类 App 操作双 API 双变体 236/236，存储 47×2 与 CLI Client 16×2 合计 126/126，均零失败/跳过。结果 `post-evidence-additional-summary.json`、`post-evidence-storage-cli-result.json`；其他平台/Runtime 与全量覆盖仍按上一项继续。
- [x] **最新 App 会话核心矩阵**：16 类按当前源码计数，双 API 双发行包 362/362；API 29 的 API 35+ 前台超时回调不适用单列，见 `post-evidence-core-summary.json`。覆盖会话/附件/审批/调度/迁移/恢复等，实际强杀与真实服务仍按独立矩阵判断。
- [x] **浏览器/文件/QuickJS/Android 模块当前回归**：双 API 340 项获得通过证据；文件首次 35/38 暴露夹具固定目录残留，改为每例临时目录后双 API 各 38 项通过且原地再次通过。首轮失败保留，重复运行另计 76 项。浏览器长稳/原生压力入口后置；见 `post-evidence-platform-summary.json`。
- [x] **M11 订阅 Provider 集成回归**：本轮非付费范围收口：四平台文本/Runtime专项70/70、入口8/8、Consumer DEX排除、完整Chat预算/停止/死亡/跨平台切换8/8方法（32场景）及结果删除验证通过。HTTP错误由各协议fixture测试证明，设备死亡场景不冒充真实429；Claude/Grok真实付费调用继续未核实。
- [x] **M11 CLI 结果协议最新恢复子矩阵**：四平台 fixture 的读取保留/重启恢复/显式 ACK 删除与运行取消/死亡双 API 18/18。首轮 4 项旧自动 ACK 假设失败已保留并修正测试；未改生产协议、未调用真实付费账号。见 `post-evidence-cli-recovery-fixed-result.json`。
- [x] **前台服务与前后台资源门禁当前回归**：API29/36双发行包18/18；真实通知停止、快速启停、等待审批释放、直接onTimeout回调及Activity前后台资源采样通过。API29超时回调不适用单列，不算通过；不代表自然超时/内存压力。证据 `lifecycle-current-summary.json`。

- [x] **Goal 模型流期间实际旋转**：API29/36双发行包4项旋转通过，原Stop/wake回归8项通过；真实Activity方向变化与恢复，原Goal/run/Turn和模型连接保持唯一，随后停止结算。证据 `goal-rotation-summary.json`，不代表真机传感器或全部后端旋转验收。

- [x] **Goal 部分模型流连接中断**：实际关闭loopback SSE socket，API29/36双发行包4项通过，原Stop/wake/旋转12项回归通过；Goal/run/Turn FAILED、单次调用、预算预留结清，无工具/重发。证据 `goal-disconnect-summary.json`，不替代整机断网、DNS/TLS或真机网络切换。

- [x] **非长稳生命周期/资源回归**：本轮定义矩阵收口：API29/36已有前后台/FGS18、旋转与流断开、后端强杀恢复、PRoot冷绑定/显式forced idle和普通模块资源测试；当前API35 App14+WebView2、双API真实低内存降额及分配器退出后恢复2/2补齐。自然LMK杀App/自然Doze/OEM热限与长稳仍归独立发布矩阵。
- [x] **Connector 条件测试补跑**：使用现存样本与交接 ZIP，两变体共 6 个原跳过实例全部通过，含公开文档 MCP 只读查询；仅记录来源解析/样本导入/公共服务结果，受保护账号与完整业务兼容性不据此验收。
- [x] **汇总与收口**：状态、缺陷与HXA-147完成记录已更新；566个交付文件逐路径审核并完整暂存，索引与工作树一致，无剩余未暂存/未跟踪交付文件。20个Python缓存仅本地保留并忽略。原始XML2713项、34个Test任务、8个生产APK指纹及最终门禁核对通过，见 [收尾审计](main-closure-audit.md) 和 `build/main-verification/main-closure-final-audit.json`。未创建本地提交或推送；并行HXA-125外部验收不标完成。

- [x] **私有产物删除修复后的合并门禁刷新**：完整 JVM 2,713/2,713、零失败/错误/跳过，1,162 项源码配置指纹不变；根 Debug/Release Lint、Spotless、Detekt、App/Runtime Debug/Release 构建通过。受影响的双 API 双发行包 Goal 删除/产物/PRoot 结果与复核 UI 共 30/30；完整订阅 Chat 另有 8/8。证据 `post-private-delete-host-result.json`、`post-private-delete-release-result.json`、`post-private-delete-device-result.json`。原 946 项是此前完整 App 快照，本轮按生产变化影响复验，没有重称全量 946 新跑。

本轮收口依据：`post-private-delete-host-result.json`（2713 JVM）、`post-private-delete-release-result.json`、`final-app-matrix-summary.json`（946旧完整App快照）、`post-private-delete-device-result.json`（30受影响复验）、M11专项与`subscription-chat-routing-result.json`、`current-resource-api35-result.json`（14）、`current-resource-api35-browser/result.json`（2）、`resource-pressure-release-summary.json`（2真实压力）。各项快照/限制独立保留；发布/自然LMK/真机/长稳仍按后置矩阵。

## 第二阶段：统一交互与界面

本轮约定的功能及非真机、非长稳验证已收口；开始 HXA-147，具体范围和命令见 roadmap。早期条目的阶段性待验说明按后来记录核对，不代表发布/真机/长稳通过。

- [x] 官方流程资料调研完成：Claude Code Desktop、Cursor Agent 与 Android Compose，来源和移动端取舍见 [HXA-147 调研](hxa147-interaction-research.md)；这是文档调研，未声称实际操作竞品。
- [x] 统一会话/任务导航、模式与 Provider 设置、工具执行过程和结果展示；压缩长目标文本的默认展示，保持状态与主要动作容易找到（真实 edit 完成截图见 `goal-real-edit/`）。
- [x] 修正最新回复可见性：真实Goal UI截图显示新回复未自动跟随到底部，仅部分露出；同时压缩长Provider/模型标识占用。跟随最新内容时自动滚动，用户主动上翻阅读时保持位置并提供回到底部入口。证据 `goal-real-ui-current-matrix/api36-consumer/conversation.png`；原assertIsDisplayed仅能证明部分可见，不能作为完整可读验收。 已接入末尾跟随、上翻保持和回到最新入口，双API双发行包组件12/12、真实模型4/4；长标题改为同行展开，最终组件16/16、API29 Consumer真实模型追加1/1。测试使用未裁剪边界检查最新短回复，长流/阅读保持由组件专项验证，快照范围见 [HXA-147进展](hxa147-progress.md)。
- [x] 首次使用及发送确认文案已按当前行为修订，三语言移除旧 ADR/FR 与未来规则说法；发送确认明细改为单列滚动。双 API 双发行包首次说明/确认/长内容/模式回归16/16，截图核对通过，见 [HXA-147 进展](hxa147-progress.md)。中英文完整布局矩阵仍归后续总体验收。
- [x] 统一审批、停止、失败重试、暂停/恢复、空状态和加载反馈，使动作与任务状态一致。
- [x] 实施并验证 Android 小屏/大字体、可访问性、中英文布局与关键任务端到端流程。

第二阶段完成证据及各快照边界见 [HXA-147完成记录](../../completion-records/HXA-147.md)。累计修改已完整暂存，未提交/推送；本轮约定范围完成，第三阶段保持后置。

## 第三阶段：后期长稳（当时后置）

当时待模拟器验证的设计与目标见[总计划](../../evidence/development/verification-plans/emulator-verification-master-plan-2026-09-09.md)。该计划区分已完成短测与新增证据缺口，含应用24小时、组合任务、网络/生命周期、平台与外部条件；设计不等于已运行或重新打开已完成HXA。

浏览器/Autofill 专项已形成[小模型长稳执行方案](../../evidence/development/verification-plans/browser-autofill-soak-plan-2026-09-09.md)：夹具缺口、配对预检、双 API 单进程24小时、资源趋势与失败取证分别定义；该段保留方案制定时的状态，后续执行结果见对应方案及验收记录，不替代以下历史全 App 门禁。

- [ ] 验证浏览器 JNI 双映射清理与系统 Binder 代理累积的修复方案（[HXA-153 释放路径](native-reference-release-trace.md) 已完成配对与因果干预；[HXA-154](browser-controller-reference-verification.md) 的六条生产本地路径未复现目标残留，优先核实真实网络/后台窗口；原生问题仍 open。HXA-152 已补齐 API29/36 原生 WebView 与无 WebView 的 Autofill 对照，四组 FAIL，见 [调查记录](webview-native-reference-investigation.md)），复用五组原生对照失败证据；有针对性修复后再重跑对照，不以强制 GC 或放宽门限掩盖。
- [ ] 最新构建 API 29 浏览器连续 24 小时长稳。
- [ ] 最新构建 API 36 应用连续 24 小时长稳。
- [ ] 长稳前核实宿主休眠、模拟器存活、采样与进程生命周期；记录 APK hash、系统/WebView 版本和原始日志，不拼接失败轮次时长。

此前两组完整长稳均未通过，保留 FAIL。最新应用轮次因 adb 采样失败/设备离线中断，不能仅凭此认定应用缺陷。

## 相关记录

- [M11 交接](../../completion-records/index.md)
- [收尾历史与评测证据](m10-closure-followup.md)
- [main 全量验证报告](main-merged-verification.md)
- [WebView 原生对照调查](webview-native-reference-investigation.md)

## 首组实际命令与证据

`./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest :runtime:cli-client:test :runtime:cli-app:testDebugUnitTest --rerun-tasks --no-configuration-cache --no-daemon --max-workers=2`：exit 0，305 tasks 全部执行。cli-client 为 Android 模块，结果位于 `testDebugUnitTest`，不能按聚合 `test` 目录漏计。

本地证据：`build/main-verification/m11-merged-unit-tests.log` 与 `m11-merged-unit-tests-result.json`，后者记录逐组计数与所有跳过原因。该组不等于全仓 JVM 或设备验收。

## PRoot 阶段证据索引

以下 26 段原与全量验证报告逐段相同，现保留标题与链接，避免重复维护；失败、未完成边界及原命令仍在目标章节。

### 2026-09-07：PRoot 大归档登记的流式校验前置修复

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07proot-大归档登记的流式校验前置修复)。

### 2026-09-07：PRoot 私有结果保存组件

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07proot-私有结果保存组件)。

### 2026-09-07：PRoot 恢复协调器的保存/确认顺序

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07proot-恢复协调器的保存确认顺序)。

### 2026-09-07：真实 PRoot 归档保存与跨 UID 确认

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07真实-proot-归档保存与跨-uid-确认)。

### 2026-09-07：PRoot 只读结果预览数据

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07proot-只读结果预览数据)。

### 2026-09-07：聊天卡片接入 PRoot 结果查看

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07聊天卡片接入-proot-结果查看)。

### 2026-09-07：调用方死亡后的 Runtime 冻结诊断

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07调用方死亡后的-runtime-冻结诊断)。

### 2026-09-07：修复 PRoot 排队取消失效

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07修复-proot-排队取消失效)。

### 2026-09-07：已接入 PRoot 调用方死亡取消

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07已接入-proot-调用方死亡取消)。

### 2026-09-07：PRoot 终态提交后、App 消费前的真实恢复

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07proot-终态提交后app-消费前的真实恢复)。

### 2026-09-07：Runtime 禁用后的本地结果查看

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07runtime-禁用后的本地结果查看)。

### 2026-09-07：PRoot 保存及 ACK 边界进程终止

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07proot-保存及-ack-边界进程终止)。

### 2026-09-07：正常 PRoot 执行接入持久制品

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07正常-proot-执行接入持久制品)。

### 2026-09-07：正常路径精确 ACK 与失败证据

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07正常路径精确-ack-与失败证据)。

### 2026-09-07：修复 owner 监听晚于作业启动的竞态

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07修复-owner-监听晚于作业启动的竞态)。

### 2026-09-07：首次结果传输失败保留 Runtime 成功证据

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07首次结果传输失败保留-runtime-成功证据)。

### 2026-09-07：输出验证失败进入待核查并开放原结果恢复

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07输出验证失败进入待核查并开放原结果恢复)。

### 2026-09-07：完整 Goal 输出丢失与生产恢复动作验证

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07完整-goal-输出丢失与生产恢复动作验证)。

### 2026-09-07：owner 排队、重复归属与释放边界回归

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07owner-排队重复归属与释放边界回归)。

### 2026-09-07：恢复 ACK 响应丢失不再隐藏本地结果

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07恢复-ack-响应丢失不再隐藏本地结果)。

### 2026-09-07：结果确认状态与显式重试入口

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07结果确认状态与显式重试入口)。

### 2026-09-07：正常 Goal 结果验收发现并修复状态域混淆

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07正常-goal-结果验收发现并修复状态域混淆)。

### 2026-09-07：归档保存与部分传输失败边界

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07归档保存与部分传输失败边界)。

### 2026-09-07：PRoot 完整 ChatScreen 结果导航

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07proot-完整-chatscreen-结果导航)。

### 2026-09-07：PRoot 收尾后的合并宿主全量刷新

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-07proot-收尾后的合并宿主全量刷新)。

### 2026-09-08：确认交接进程终止与 PRoot 缺口收口

完整阶段记录（含原失败、修复与验收边界）见[全量验证报告](main-merged-verification.md#2026-09-08确认交接进程终止与-proot-缺口收口)。
