# 终端界面、多会话与后台命令开发计划

日期：2026-09-14。状态：开发计划；ADR-0050 的日志与职责已接受，ADR-0051 的后台/手动终端启用已 accepted，产品尚未实施。

适用工作树：`worktree-harness-2.0`。审查基线 HEAD 为 `a4a64039`，含未提交的 HXA-192/193 和并行修复；开始时重新核实 HEAD、差异与任务状态，不以 HEAD 代表全部源码。当前任务优先级仍由 [status](status.md) 决定，不抢占正在执行的收尾任务。

## 1. 目标与非目标

工作区→终端→输出→产物/变更的具体导航与环境/会话体验按 [体验优化方案](workspace-and-capability-experience-plan.md) 补充194～199验收；它不改变本包执行/IPC/授权契约，不另建一套终端。

按以下顺序交付：命令详情与结果查看 → 实时日志 → 有界异步命令 → 单个交互终端 → 多会话与界面重连 → 综合验收。

用户应能看到正在执行什么、查看输出和产物、停止任务；专家可从 Workspace 打开终端而无需先与模型对话。所有运行发生在手机上，developer 仍只安装一个 APK。consumer 不包含 Runtime、终端原生库或可达执行入口。

本包不实现独立终端 APK、完整 IDE、SSH/远程 Worker、开机自动重跑、无限后台服务、自动续 Goal、模型自动操作任意交互终端、Root/Shizuku/ADB 新执行器或 QuickJS 主机桥接。不迁移订阅凭据。RootFS/依赖可按所有者授权升级以解决兼容性或维护问题，须同步lock、来源与对应验证。

## 2. 已有事实与缺口

| 现有事实 | 源码位置（仓库相对路径） | 本包要补什么 |
| --- | --- | --- |
| PRoot 是 developer 库，运行于共享主 UID 的私有进程 | `runtime/proot-app/src/main/AndroidManifest.xml`；[ADR-0049](../adr/0049-integrated-developer-runtimes.md) | 保持打包/UID，补服务能力，不再拆 APK |
| Linux 工具等待一次性 Job；执行窗口最多 60 秒 | `app/src/developer/kotlin/com/helix/app/proot/LinuxRunTool.kt`、`LinuxJobExecution.kt` | 不修改旧调用含义，另定义异步结果和会话 |
| Job 有 journal、取消、归档、结果对账 | `runtime/proot-app/src/main/kotlin/com/helix/runtime/proot/app/ProotJobRunner.kt` | 复用持久身份与结算，不创建第二套任务事实 |
| 单线程 Job 队列；stdin 是可选文件 | `ProotJobRunner.kt`、`runtime/proot-ipc/src/main/kotlin/com/helix/runtime/proot/ipc/ProotJobWire.kt` | 新增有界日志协议；PTY 单独设计 |
| 后台任务是 Turn 投影 | `app/src/main/kotlin/com/helix/app/chat/BackgroundTasks.kt` | 命令详情关联原 Turn/ToolCall/Job，不能把 shell 会话伪装成 Turn |
| owner Binder 死亡会触发 Job 取消 | `runtime/proot-app/src/main/kotlin/com/helix/runtime/proot/app/ProotJobOwners.kt` | detached 执行需显式租期和 Runtime 所有权，不能只删 death recipient |

现有“后台任务”不证明命令在 App 退后台或进程被杀后仍可继续；聊天多会话不等于多个持久 shell。旧 HXA-083～086 中独立 APK/离线表述按 ADR-0049 解释，历史记录不改造成新形态验收。

## 3. 决策与启动条件

- 当前有效约束：[ADR-0007](../adr/0007-companion-runtime-lifecycle.md)、[ADR-0012](../adr/0012-capability-first-advanced-grants.md)、[ADR-0049](../adr/0049-integrated-developer-runtimes.md)。Goal 预算/唤醒继续遵循 [ADR-0004](../adr/0004-goal-run-wake-budget-semantics.md)。
- [ADR-0050（accepted）](../adr/0050-terminal-sessions-and-detached-jobs.md) 与 [ADR-0051（accepted）](../adr/0051-terminal-runtime-enablement.md) 已获得所有者明确接受，HXA-194～198 可按阶段实施。后台与 PTY 接线前仍需平台、参数、组件许可和设备 Spike；这些是启用条件，不再是重复请求架构接受。
- 先处理 HXA-192/193 与本包的交叉阻塞。若仅剩真实账号或制品发布等独立外部验收，且相关代码门禁通过，可由当前状态安排 HXA-194，不要求为纯结果页重复所有历史验收。
- ADR-0048/0050/0051 已接受，不重复请求决定；接受不是测试通过。小模型可做已授权实现与测试，不能扩大权限、跳过启用门禁或自动接受新的组件/底座 ADR。

## 4. 职责划分

| 层 | 所有权 | 不承担 |
| --- | --- | --- |
| 命令详情/终端 UI | 展示、输入意图、订阅游标、会话选择；通过应用服务访问 | 直接调用 ProcessBuilder、JNI、DAO 或 Runtime Binder |
| 应用命令服务（app 层） | origin、Workspace、Turn/ToolCall/Job 绑定；审批与预算；查询投影、产物导入 | 自己 fork shell；在 Activity 中持有任务所有权 |
| PRoot client / IPC | submit/query/cancel、日志分页、会话连接/输入/尺寸；版本协商 | 决定权限、续预算、把断线当成未执行 |
| `:proot` 执行服务 | 进程、PTY、租期、资源计量、日志 spool、journal 与退出事实 | 创建 Goal/审批、触发下一轮模型或重放未知命令 |
| core/storage | 主应用任务绑定、展示元数据、迁移及恢复 | 存活 PID 的绝对可信证明；第二份完整终端输出 |

只在消费者确实需要时提取类；不预先铺设通用插件、运行时注册框架或新模块。现有 Job 继续使用输入快照和校验后的输出；交互 Session 独立保存 cwd/env，二者共用进程取消、配额和日志原语，不强行共用完成状态。

### 4.1 身份与状态

- 命令：保留 `executionId/jobId`，新增绑定必须幂等；ToolCall 与真正进程完成分开。异步提交成功只能显示“已启动”，不能显示任务完成。
- 交互会话：`sessionId + runtimeGeneration`，元数据包含用户标题、origin、Workspace 引用、创建时间、最后目录、关闭原因。PID 仅是运行时句柄，不能单独用于恢复/杀进程。
- 会话状态候选：CREATING → READY → CLOSING → CLOSED；初始化失败为 FAILED；进程丢失为 LOST。是否有界面连接、是否有命令在执行是独立字段，不能合并成一个 running 布尔值。
- UI detach 不等于 close/cancel。系统进程死亡后显示 LOST/INTERRUPTED 和已保存输出；“重新打开”创建新身份，不回放历史命令，不声称恢复 shell 内存状态。
- 旧一次性 Job 的 enum、hash、查询与归档继续有效；升级后旧结果仍可读取，新增 schema 有迁移和回退说明，不能重建数据库消除不兼容。

### 4.2 日志和流控

提议日志批次含 `jobId/sessionId`、generation、单调 sequence、stream（stdout/stderr/pty）、字节内容、截断/结束标记。UI 按游标拉取或订阅，重复批次幂等，缺口明确显示；跨 stdout/stderr 的合并顺序只表示采集顺序，不宣称进程严格写入顺序。

初始日志限额已由 ADR-0050 接受：IPC 每批 32 KiB、UI 热缓存每 Job 256 KiB、spool 每 Job 4 MiB/总计 16 MiB；最多两个手动 PTY 已由 ADR-0051 接受。日志滚动截断与原执行输出硬配额分离，不提高旧 Job 限额；慢读端不阻塞 drain，断线不积累无界回调，UTF-8/取消/EOF 均须测试。

不主动写日志中的凭据；退出 echo、清除历史提供明确入口。ANSI 渲染不得执行 OSC 剪贴板、自动打开链接或其他系统动作；URL 只允许用户明确点击。PTY 原始流是非可信内容，不得直接成为模型系统指令。日志预览不是已验证产物，结果仍走原 hash/import 流程。

### 4.3 授权、并发与预算

| 来源 | 首版契约 |
| --- | --- |
| 模型命令 | 始终经过现有 Dispatcher/Policy/Approval；每次精确执行受限，不因会话存在获得新权限 |
| 模型异步命令 | 使用新工具描述/版本和租期，旧 60 秒调用不被静默延长；审批证明仅在真实开始执行时消费，入队前再次检查有效性 |
| 手动交互终端（ADR-0051 已接受） | 用户显式打开、选择 Workspace，在 developer/Advanced 中进入可信开发环境；人工键盘输入不逐字弹 ToolCall 审批；不是可供模型调用的授权接口 |
| 模型向手动 PTY 写入 | 本包不开放；模型建议命令可显示为文本，用户自行输入/粘贴执行，不自动 Enter，不借 clipboard/脚本回调绕过 |

手动入口需独立的应用服务契约，记录 USER origin 和会话生命周期；不能把 UI 意图直接注入 Agent Loop，也不能让网页/MCP/Skill 伪造人工输入。共享 UID 不是 Workspace 安全隔离，设置说明与 ADR-0049 一致。

原模型代码 lane 保持排他。ADR-0051 接受手动域与 Agent 本地代码/文件变更互斥、最多两个手动 shell；等待可见可取消，用户关闭手动会话后释放占用。不能凭 prompt 判断子进程已结束；回收必须确认进程组结束。若需更细粒度并发另行修改契约。

异步 Job 必须记录租期、owner、累计执行时间和停止原因。初始默认租期 5 分钟、最大 30 分钟，实际值取用户预算、Goal/Turn 剩余额度、平台窗口和任务上限的最小值；额度不够拒绝启动，不通过异步逃逸预算。首版没有自动续期、自动唤醒或 Runtime 死亡后自动重启命令；已明确移交 Runtime 的 Job 可在主进程死亡后继续消耗原租期，前提是后台路径仍有效。超时终止进程组并持久结算。宿主等待时间与子进程运行时间避免双计，重连不能重置累计量。

手动 PTY 不属于 Goal，前台空闲、后台租期与用户续期方式须在生产接线前定值并验证；异步 Job 默认5/最大30分钟已作为初始实现限额接受，不是 Android 存活保证。

### 4.4 Android 生命周期

区分应用内切换页面、Activity 重建、退后台、锁屏、主进程死亡、Runtime 死亡和 force-stop。只有有明确用户来源、匹配的 FGS 类型且平台允许时，才可在可见状态启动有期限的后台运行；通知包含任务摘要、耗时和停止入口。没有合适类型时提供前台运行并在退后台终止/结算的降级路径，不能伪称长期后台已经支持。

HXA-196 必须先给出 targetSdk/API、真实工作类型、FGS 选择与合法性证据；不照抄竞品 specialUse 声明，不把任意计算当 dataSync。等待用户审批不保活。启动被拒绝时不得留下无主 Job，取消/超时/异常路径释放绑定和资源。物理设备后台测试与模拟器测试分别报告。

## 5. 小模型任务包

执行顺序：HXA-194 → 195；随后 HXA-196 后台 Job 与 HXA-197 前台 PTY 按各自决策条件选择先后，每次只推进一个实现 checkpoint。197 不再硬依赖 196；198 依赖 197 及手动并发接受；199 汇总所选范围，未完成项明确保留。

### HXA-194 命令详情页与已有结果投影

- 允许：`app` UI/应用查询与 developer proot 结果适配、相关测试/docs。不得改 Runtime IPC、授权、数据库 schema 或执行上限。
- 新增命令详情入口，来自任务页/工具结果行；显示命令（按已有可见参数）、状态、范围、退出结果、stdout/stderr 和产物。运行中的命令先显示状态与“结束后可查看输出”，不伪造实时日志。
- 复用 `ProotResultRecovery/Preview`、任务身份和已有取消入口；取消路由应用服务。未知、证据过期、读取失败、无输出均有独立展示；只读浏览不冷启动 Runtime，用户明确对账另走现有入口。
- 测试：旧历史、成功/失败/取消/unknown/过期；旋转/返回不重执行；consumer 无可达执行入口；三语言资源及长输出布局。
- 验收：G1 + G2；运行新建 `CommandExecutionDetailsDeviceTest`（必须在本 HXA 创建并加入 app 标准 source set）。交付后用户已经可以通过界面理解一次命令结果。

### HXA-195 有界日志协议与实时输出

- 依赖：194；ADR-0050 已接受，可进入日志实现与测试，不等待后台/PTY 选型。
- FGS 与 PTY/许可证 Spike 归 ADR-0051，可另行推进，不作为本阶段前置。
- 允许：`runtime:proot-core/proot-ipc/proot-client/proot-app`、app proot/详情页，必要绑定持久化在 `core:storage`，测试/docs。
- 新 IPC 事务号和版本协商，不复用旧事务号；日志归属与 generation 校验；分页游标、spool、截断、背压、取消后尾部日志。先兼容一次性 Job，不提前加 PTY 或 detached。
- 测试：分片 UTF-8、stdout/stderr 压力、慢读端、重复/过期游标、跨 job 拒绝、低存储、Binder 断开、EOF/取消竞态；最终归档字节/hash 与日志展示关系明确。
- 验收：G1 + G2 + G3；新增 `ProotLogStreamDeviceTest`。UI 必须在命令结束前实际看见日志，不能只用最终文件一次刷新冒充 streaming。

### HXA-196 有界异步 Job 与后台可行性

- 依赖：195；可先做 FGS/owner Spike，生产接线需 ADR-0051 对应条款接受并记录平台结论。
- 允许：上述 Runtime/app 模块，`core:agent` 预算/绑定适配、`core:storage` 迁移、`tools:framework` 仅必要契约，测试/docs。不改变 Goal 自动继续或完成标准。
- 新异步启动返回持久 Job 引用；观察/取消权限由绑定限定，不能枚举其他会话输出。复用现有任务页，新增 Job 投影而不是复制 Turn；用户取消优先于完成竞态，最终只结算一次。
- detached 前先持久记录所有权和租期，再允许 UI 离开；若无法建立合法后台路径，不宣称“后台运行中”。保留旧同步工具 v2 的语义，不能一律返回“accepted”破坏原调用。
- 候选新工具名在开始时固定到 schema/工具文档，启动仍走精确审批；观察只读取已授权任务，取消幂等且不能启动新任务。日志/任务完成不自动发起模型新 Turn。
- 测试：启动前取消、审批过期、额度不足、UI detach、主进程死亡、Runtime 死亡、租期跨重连、退出/取消竞态、FGS 拒绝、退后台降级、结果导入失败重查不重跑。
- 验收：G1～G4；新增 `ProotDetachedJobDeviceTest`。真实物理设备后台/锁屏/Doze 单列；缺设备时保留待验收，不阻断其他独立实现，也不记此 HXA 完成。

### HXA-197 单个手动 PTY 终端

- 依赖：195 及 ADR-0051 的人工输入、前台回收、Workspace 映射和组件条款接受；不依赖 196 完成，初版单 PTY。
- 允许：Runtime/app、必要 storage 元数据、经决策的 native PTY 适配和渲染组件、测试/docs。禁止直接复制竞品受限制许可证代码。
- 先验证 PTY 创建、进程组、输入、resize、Ctrl-C、EOF 和回收；native/rendering 版本与许可证写入 ADR-0051 并接受后使用，不升级无关依赖。
- developer Workspace 有“打开终端”入口；UI 通过应用服务连接 `:proot`。显示当前目录、连接状态、停止/关闭操作；关闭页面仅 detach，关闭会话终止执行；无模型自动输入接口。
- 当前终端是可信共享 UID 环境，不能展示“只允许访问此目录”。Workspace 是起始目录/关联范围，实际访问边界按 ADR-0049；是否直接映射原 Workspace 的决定必须写入接受后的契约，不沿用 Job snapshot 名称掩盖变化。
- 测试：`cd`/env 在同一会话保持、中文/多字节、REPL 输入、窗口调整、Ctrl-C、prompt 后后台子进程、OSC 剪贴板不执行、Activity 重建、关闭终端不杀其他 owner。
- 验收：G1～G3；新增 `ProotTerminalSessionDeviceTest`；实际运行 REPL，并保存中英文界面/软键盘/长输出截图。编译成功不代替可交互验收。

### HXA-198 多会话、连接恢复与资源仲裁

- 依赖：197；ADR-0051 手动并发条款接受，不放开模型代码并发。
- 允许：上述会话/UI/storage、Runtime 调度与应用仲裁，测试/docs；不新增进程常驻、额外 APK 或 shell 自动重放。
- 最多 2 个 live 手动会话，标题/目录/缓冲独立；同一会话首版只有一个写入连接，其他观察端只读。连接凭据由应用服务创建，不能把 sessionId 当授权。
- 页面切换不重开 shell；Runtime generation 改变使旧句柄失效。进程丢失后的会话卡可查看历史、明确重新创建；不能向复用 PID 发信号。
- 生命周期资源：lease deadline、输出总量、最大会话数及后台限制统一在 Runtime；手动域与 Agent 写/代码任务冲突显示原因，不通过增加线程池掩盖。用户可以关闭手动会话释放 Agent 执行。
- 测试：两个 cwd/env 不串线、输出不串会话、重复 attach、单写入者、取消一个不影响另一个、第三会话拒绝、Agent 等待可取消、主/Runtime 死亡、屏幕重建、session 删除与日志读取竞态。
- 验收：G1～G4；新增 `ProotMultiSessionDeviceTest`。至少 20 次创建/连接/关闭循环后核对自身 PID/FD/线程没有遗留，记录起止证据，不将此短回归称为长稳。

### HXA-199 集成收尾与完整场景验收

- 允许：跨阶段缺陷修复、标准 tests/scripts/docs、发行边界检查；不得顺带改主题、Provider、Goal 语义或历史许可证。
- 场景：查看固定测试结果；长命令实时日志与停止；异步结束后回收；手动 REPL；双会话；切页重连；运行中杀主/Runtime；consumer 排除；旧版本数据库/结果迁移。
- 运行 G1～G4 及所有本包设备类，补 owned-runner 的 `scripts/verify-terminal-runtime.py`（新增）和此矩阵入口；它必须校验失败/跳过计数，保存设备 owner/关闭记录。
- 专项成功率使用“已通过场景/执行场景”，标明设备/API与跳过原因；记录日志首包、取消延迟、冷启动、包体增量和资源变化，不用测试数量替代产品成功率。
- 更新架构当前章节、工具文档、状态、HXA 完成记录和用户帮助。全量门禁失败、关键物理设备项未验或新 ADR 未接受时不得标记本包完成。允许验证通过的独立切片本地提交，遵循 [专项交接的 Git 本地提交授权](harness-2.0-next-work.md)；不自动推送、合并或发布。

## 6. 命令矩阵与设备纪律

先设置仓库要求的 JDK 17/Android SDK，核实 task 存在。以下命令从仓库根执行，新增测试类必须在对应 HXA 落入现有 source set；不存在时不能选择它后接受“0 tests”。

**G1：源码与格式（每阶段）**

```bash
./scripts/check-all.sh --source
./gradlew spotlessCheck detekt
git diff --check
```

**G2：主机/编译（按修改范围运行；195～199 全部运行）**

```bash
./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest
./gradlew :runtime:proot-core:test :runtime:proot-ipc:testDebugUnitTest :runtime:proot-client:testDebugUnitTest :runtime:proot-app:testDebugUnitTest
./gradlew :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest
./gradlew :app:lintConsumerDebug :app:lintDeveloperDebug :runtime:proot-app:lintDebug :runtime:proot-client:lintDebug
```

变更 storage 或 agent 时追加 `./gradlew :core:storage:testDebugUnitTest :core:storage:assembleDebugAndroidTest :core:agent:test`，并用独占设备执行新迁移测试。实际任务形态以实现前查询结果为准；若漂移先修矩阵，不跳过。

**G3：Runtime 回归与新增设备类**

```bash
python3 scripts/verify-integrated-runtimes.py --avd Helix_API_29 --port 5622 --output build/terminal-api29-fresh
python3 scripts/verify-integrated-runtimes.py --avd Helix_API_36 --port 5620 --output build/terminal-api36-fresh
python3 scripts/debug/2026-09-09/run-owned-emulator.py --help
```

前两行只执行既有 32 场景，不包含本包新增测试，不能充作新增功能验收。实现者按 help 的实际参数为本 HXA 新类保存启动脚本到 `scripts/debug/YYYY-MM-DD/`，使用 developer 主 APK 与 androidTest APK、`com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner`。每次新 output、未占用端口与新建独占模拟器进程；禁止借用现存 serial，finally 只清理自有进程。AVD 名/端口不适用时按本机状态显式替换并记录。

**G4：集成/产物**

```bash
./gradlew :app:assembleConsumerRelease :app:assembleDeveloperRelease
./scripts/check-all.sh --artifacts
python3 scripts/verify-integrated-runtime-apks.py --build-type release
./scripts/check-all.sh
```

正式 consumer 边界需扩展最终 APK/dex/native 检查到新增终端组件；不能只隐藏按钮。不得删除测试、放宽 detekt/lint 或生成新资产 lock 使门禁变绿。RootFS 资产来源遵循 [HXA-193 记录](integrated-developer-runtimes.md)。

## 7. 给小模型的启动 Prompt

本节仅用于已选中终端专项；首次接手使用[统一交接 Prompt](harness-implementation-handoff.md)，避免抢占当前收尾和产品主线。

```text
继续 Helix 终端与后台命令开发。先确认自己在 worktree-harness-2.0，读取 AGENTS.md、README.md、status、roadmap、verification-matrix 和 terminal-and-background-execution-plan.md。
保留所有未提交改动；按 status 处理当前 HXA，不抢占 HXA-192/193，也不重复已完成记录。
ADR-0048/0050/0051 已接受，按 HXA-194→195 后分别推进196/197、再198/199；每次一个checkpoint。后台/PTY接线前完成平台和组件Spike，不把架构接受写成已验收。前台PTY不硬依赖后台Job；已有本地commit授权保持。
先做当前任务允许的实现或独立 Spike，测试通过后写完成记录，再进入满足前置条件的下一项。UI 不直接执行 shell，不增加 APK，不修改 QuickJS/订阅/Goal 语义，不借会话绕过模型工具审批。
命令及设备按计划 G1～G4 执行；新增测试为零、跳过、编译成功均不是设备通过。缺真实设备或外部资产发布授权时写明未覆盖项并继续独立工作。
不reset/stash他人改动；依赖允许升级，必须更新lock并验证Android兼容性。按 harness-2.0-next-work.md 的 Git 授权，用具名路径或 hunk 暂存、检查 cached diff，验证通过后自主本地 commit 并报告 hash，不打包无关并行改动。未授权 push、合并、发布或改写历史；常规实现自主完成，只有真实未决架构/外部依赖才报告。
最终报告已交付的用户行为、实际命令/结果、遗留条件和下一项任务；同步状态文档但不提前宣称整体验收。
```

## 8. 参考与证据边界

- [Operit2 终端执行层](https://github.com/AAswordman/Operit2/blob/a39df99007d18dd473dc63abee9fda0550d8fb35/hosts/android/src/terminal.rs)：仅借鉴 PTY/会话和 UI attach 思路，不复制代码，不继承其授权/生命周期结论。
- [Android 后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)：2026-09-14 查询；实施时重新核实目标 API/渠道要求。
- [Android FGS 类型](https://developer.android.com/develop/background-work/services/fgs/service-types)：specialUse 不是通用无限后台许可，必须与真实任务及发布要求匹配。

本轮验证：`./scripts/check-all.sh --source` 通过（文档、ADR、国际化、secrets 及门禁脚本自测）；`git diff --check` 通过。G2 所列 app/PRoot 单测及追加 storage/agent 任务通过 Gradle `--dry-run` 名称解析，未实际运行测试。没有执行本包未来功能测试、修改生产行为或形成后台运行的真机结论。
