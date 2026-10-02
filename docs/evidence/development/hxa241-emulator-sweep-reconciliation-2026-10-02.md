# HXA-241：历史模拟器扫描与当前主线复核（2026-10-02）

> 当前结论：本次授权的 **API36 / ARM64 模拟器专项验收 passed**，覆盖历史报告 30 个失败类在当前版本的适用场景；精确结果见文末“最终构建验收”。下面的主机复核、B/C 分类和首次主机门禁保留当时过程，其中“仅编译 / not requested”不是当前设备状态。历史失败日志不覆盖、不改写为通过。

## 来源、当前基线与授权

所有者要求复核此前模拟器扫描问题并修复当前分支。本轮开始 HEAD 为 `a94ccc212ac2c2fce656c8ea015fa96767956aa9`，main；保留前轮已提交的 FFmpeg 及其他代理工作，不提交、不推送、不调用真实服务或账号。

用户指定 sibling worktree 中的 `docs/evidence/development/emulator-sweep-analysis-2026-10-01.md`。当前 Runner 无权读取该 worktree，因此使用主项目已有的[历史报告归档](../../../reviews/2026-10-01/emulator-sweep-analysis-2026-10-01.md)及[归档说明](emulator-sweep-triage-2026-10-01.md)。接收时原件 SHA-256 为 `af56b9785a26f4e37e955329e70f96eed9e8acb7b3fa1cc01ca8d50e896b786e`；提交归档仅替换宿主用户名/安装路径，原始字节保存在 ignored `build/hxa241/emulator-sweep-original-2026-10-01.md`，该哈希不表示路径归一化后的归档哈希。不能保证 sibling 原文件此后没有变化；未越界读取，也未改写历史报告来使旧结果变绿。

原报告基线为 `05e91003`，记录 52 个失败用例；它不是当前 main 的测试结果。最初主机复核阶段设备、真实账号和真实模型均为 **not requested**；所有者后来明确要求“完成模拟器验收”，已在独立模拟器执行下述专项。外部真实账号/付费额度和真机仍未使用。B/C 表中的“修订”记录静态定位与修改，当前设备结论以文末最终构建证据为准。

## 当前确认的产品问题

`ChatToolSettlement.persistPreDispatchDenied` 对未知/未曝光工具等框架错误持久化 `FAILED`、审计来源 `FRAMEWORK`，但实时工具时间线却一律使用“已拒绝”标签。这会把框架错误呈现成权限拒绝，且和持久化状态不一致。

现将 `PreDispatchDenialKind` 的状态、审计来源及 UI 标签统一映射：框架拒绝为 FAILED/FRAMEWORK/失败；恢复审查仍为 DENIED/POLICY/拒绝。没有放宽工具曝光、权限、审批、绑定或执行限制。新增两个 JVM 回归检查两种映射。

其余修订主要是让测试 fixture/交互与当前生产契约一致；没有证据就不把测试失败改称产品引擎故障，也不把修过测试宣称成已经修好所有设备问题。

## B 类：确定性断言和契约漂移逐项复核

| 原失败类 | 当前源码所见 | 本轮处理及保留验证 |
| --- | --- | --- |
| `AttachmentE2eDeviceTest` | 普通工具 fixture 未经过按需发现；图片源缺请求绑定 verifier；非默认模型缺精确证据；未知工具仍期待旧 DENIED 状态 | 通过实际 discovery 预加载 fixture 工具；接回生产 BoundImageAccess；仅在隔离 fixture 写明所选模型的合成能力证据；未知工具断言 FAILED 并保留独立 callId/原 wire ID 回填及错误诊断。图片的会话、消息、哈希与原始内容检查不删除 |
| `SessionInputProtocolDeviceTest` | 三协议脚本直接返回未发现的 `time.now`，当前请求不含其绑定 | fixture 先通过 discovery 加载该工具，不把 time.now 改成全局常驻；保留三协议的真实解析、Steer 和工具回填 |
| `GoalModelCancellationDeviceTest` | 用户 Stop 取消 Turn，但 Goal 依当前契约停泊为 PAUSED | 断言 Turn CANCELLED、pauseRequestedAt、Goal PAUSED、run USER_PAUSED，以及 reservation 清空和累计预算 |
| `EvaluationTrajectoryDeviceTest` | 预期成功的第二个调用也没有发现 time.now | 补发现；保留第一个实际失败和第二个成功的轨迹顺序，不把两者都容忍为失败 |
| `SessionInputAdmissionFailureDeviceTest` | 等待 INPUT_ADMISSION_FAILED 的中间 NEEDS_ATTENTION 状态，却未考虑一次性自动恢复后的 FAILED | 等待最终终态，检查唯一恢复 claim/结束通知、原文保留和没有第二次模型调用；把旧输入不能覆盖后续编辑的断言放到真正由用户 Stop 停泊的独立场景，未删除该覆盖 |
| `LocalProviderLoopDeviceTest` | 本地脚本模型返回 time.now，但当前工具窗口未加载它 | 与远端 fixture 同样走发现；保留本地 provider 完整 loop，不降低为只测适配器构造 |
| `CommandExecutionDetailsDeviceTest` | 详情入口在折叠的工具行内，旧测试直接滚动到尚未组合的详情按钮 | 先展开真实工具摘要，再验证详情入口和已存命令/结果，不强制生产时间线默认展开 |
| `TaskJourneyDeviceTest` | 停止旅程等待 echo 审批，但该可选 fixture 工具未曝光 | 先通过现有 discovery 加载 echo；仍验证真实审批、停止、CANCELLING/CANCELLED、原执行与跨会话事实 |
| `ProviderModelDiscoveryUiTest` | 模型管理已搬到独立 LazyColumn 对话框，旧 row 祖先/chip/编辑表单断言不再对应产品 | 四场景改测真实模型管理：显式勾选、取消不保存、保存不改基础测试模型、无目录手动添加、认证失败不可选、大目录末尾仍可检索；不恢复旧 200-chip 物化方案 |
| `ProviderContextDeviceTest` | 可滚动表单和软键盘下仍直接点击坐标不可达控件 | 先滚动到控件、完成输入后收键盘；清理时仅在对话框仍存在时关闭，避免二次异常遮住原失败；原 64k/70% 和逐模型设置断言保留 |
| `MainActivityTest` | 主线仍引用已撤掉的 drawer search/recent 节点，历史 worktree 修复未落在 main | 显式断言旧入口不存在，保留 New/All conversations 等现行入口 |
| `IaAuthorityDeviceTest` | 用 developer 的订阅分组断言 consumer 界面 | 按真实构建渠道分别断言存在/不存在；不向 consumer 添加订阅能力 |

`FixtureToolDiscovery` 只属于 androidTest，用当前生产发现接口建立受测前置；不改工具执行器、审批模式或绑定验证。新增 JVM 回归验证可选工具默认不曝光、精确发现后可见、不能泄漏到另一会话。

模型管理生产侧仅增加稳定的刷新/添加/关闭/目录说明语义标记，未借测试改动引入新的产品流程。

继续复核又补强两处端到端断言：重复 wire ID 的成功/框架失败/非默认模型场景，不仅核对数据库，还逐条检查实时工具时间线的本地 callId、中文完成/失败标签和审批卡清除；模型管理取消后先等待原对话框节点退出，避免下一次打开命中关闭中的旧窗口。上述设备断言本轮仅编译，不把代码可编译写成设备通过。

## C 类：超时与时序逐项复核

| 原失败类 | 当前处理 | 未验收边界 |
| --- | --- | --- |
| `SessionInputQueueDeviceTest` | 选 B 前先显式把 B 加入候选；正常结束后失效输入等待自动恢复最终 FAILED，检查唯一通知/claim、原附件与不重复发送；用户 Stop 停泊的输入仍要求 NEEDS_ATTENTION | 当前端到端设备运行未执行，不能只凭状态名变化称为已通过 |
| `ChatStopProgressDeviceTest` | 用户 Stop 等待 Goal PAUSED 而非 CANCELLED，仍要求网络流断开、Turn CANCELLED 和预算结算 | 真实触控/取消到达时序需设备复测 |
| `ProviderFlowTest` | 新模型选择器为对话框，改等待真实 search 节点；失败来源的模型可以显示修复引导，但不可选择 | 不再等待不存在的 Popup；设备点击和可见性未重新验证 |
| `ConversationHeaderDeviceTest` | 关闭后等待实际 sheet 节点退出，再断言内容消失、计数不变 | 仅约束异步完成条件，不断言已证明原抖动由 CPU 争用导致 |
| `MessageCopyDeviceTest` | 使用真实系统剪贴板，先清除旧值，等待完整新内容再断言；Markdown 原文/长文本完整性保留 | 不使用内存 fake；系统剪贴板和窗口焦点的设备表现仍需复测 |
| `SessionSettingsDeviceTest` | 对滚动页面和专家表单先滚动到目标，输入完关闭软键盘；仍核查草稿是否只在持久配置操作后落库、原 session 归属和默认权限不被改写 | 未把所有超时归因于同一原因，也没有任意延长所有等待上限 |

## A 类：宿主驱动前置而非普通产品失败

原报告的 12 个两阶段类必须保留专用 setup/进程死亡/recover 或外部 fixture 驱动。主线原登记已有 9 类，本轮补齐 `MemoryProcessRecoveryDeviceTest`、`ModelPublicationRecoveryDeviceTest`、`FirstSuccessJourneyDeviceTest`。既有 runner 读取同一 `KNOWN_PHASE_RUNNER_CLASSES`，普通扫描应标记 `PHASE_RUNNER_REQUIRED`，不是 PASS。

新增宿主回归核对完整 12 类登记，以及实际 FAIL 仍归入 NEW_REGRESSION，不凭类名掩盖新失败。真实模型、账号、平台能力和物理设备条件的既有跳过项不擅自启用；本任务没有执行 setup/recover、杀进程或 ADB。

## 继续复核修复的宿主验证缺陷

`run-isolated.py` 原先先搜索 `recoveryPhase` 等任意日志片段，再判断失败；真实失败甚至成功方法名中的该词都可能被改成 PHASE_RUNNER_REQUIRED。现在只由执行前的已登记类表决定是否需要专用驱动，执行后的失败不靠关键词豁免。原 12 个历史类加已有 combined-soak 驱动类共 13 个登记条目，未登记场景不能靠日志文字获得豁免。

原解析器还会接受只有 `OK (0 tests)`/`OK (1 test)` 的输出，忽略非零退出、错误类名、未完成或重复逐用例事件，在同时出现 FAILURES 与 OK 时优先判 PASS。现在复用 `scripts/instrumentation_junit.py`，核对开始/终结事件、原 class/method、唯一正常 runner 终结及退出码；实际失败优先，纯跳过不计 PASS，混合通过/跳过在详情中分别计数。健康探针也走同一判定，必须是指定方法真实通过，不能仅搜索 OK 字符串。

`summarize-current-device-baseline.py` 原先会把显式指定但不存在的 manifest 当作未提供基线，并跳过无法解析或缺类身份的结果文件。现在缺失/损坏/结构非法/重复类清单立即失败；损坏结果和无身份结果不能静默从统计中消失。失败发生在输出汇总之前，保留上一份 summary。缺失用例仍由既有 missing_count/missing_classes 如实报告，不填补为通过。

新增 16 个宿主回归方法（包含子场景）先在旧代码上复现问题再修复；与上一轮及既有 runner 回归合并后为 25 项。反例日志为 `build/hxa241/runner-before.log`、`summary-before.log`，修复后记录为 `runner-after.log`、`summary-after.log`。子场景失败条数不等于新增用例数；这些全部是合成日志/临时目录或 mock 命令，不访问 ADB/设备，也不改写历史 52 个失败的原记录。

## 首次主机收尾记录（后续设备阶段另行验收）

最终命令 `bash scripts/debug/2026-10-02/close-hxa241-host.sh` **exit 0**。该入口只做宿主验证，没有 ADB、模拟器、账号、Git 提交或推送操作。

| 验证 | 实际结果与证据 |
| --- | --- |
| 根 test、detekt、Spotless、双渠道 Android lint、Debug APK / AndroidTest APK | **BUILD SUCCESSFUL**；`build/hxa241/host-final.log`，990 个任务：31 实际执行、1 命中缓存、958 已是最新状态 |
| `python3 -O -m unittest discover -s scripts/tests -p test_device_baseline_runner.py` | **25 项通过，0 失败/跳过**；`runner-final.log`。相对任务起点新增 18 项，其中此次继续修复新增 16 项 |
| `python3 -O -m unittest discover -s scripts/tests -p test_instrumentation_junit.py` | **3 项既有共用 parser 回归通过**；`instrumentation-final.log`，不计为新增用例 |
| 新增状态映射 JVM | `PreDispatchDenialKindTest` 两个方法；consumer/developer XML 均为 2 项、0 失败/错误/跳过 |
| 按需发现 JVM | `McpToolDiscoveryTest` 类共 18 项通过，其中本任务新增 1 项验证可选工具、精确发现和跨会话不泄漏 |
| `scripts/check-all.sh --source` | **通过**；`source-final.log`，697 个 Markdown、224 个 HXA、35 个当前 ADR、1941 个多语言资源键一致及秘密扫描通过 |
| `scripts/check-all.sh --artifacts` | **通过**；`artifacts-final.log`，实际两渠道 APK 组件/UID/订阅排除/固定 FFmpeg 载荷与渠道边界检查通过 |
| 差异格式 | `git diff --check` 通过 |

所有 `*-final.log` 相对 `build/hxa241/`。全 HXA-241 相对起点的新增主机回归为 **18 项 Python + 3 项 JVM = 21 项独立方法**，不是将两渠道重复方法或多个断言/子场景相加。最后一轮 App JVM 测试复用了本轮较早、源码一致的 XML 结果，状态映射与发现报告时间为 2026-10-02T05:51Z；最后一轮新执行内容包括受改动的 AndroidTest 编译及静态门禁。增量通过不写成全量强制重跑。此前 `host.log` 和 9 项 runner 首轮结果是历史过程，不替代本表。

当前修改基于 main / `a94ccc21`，未提交、未推送。上轮主机收尾时模拟器状态为 **not requested**；所有者随后明确要求“完成模拟器验收”，当前转为 **pending** 并已实际执行下述设备检查。端到端 AndroidTest 的编译不证明历史 52 个设备失败全量消除，也不能引用前轮 HXA-240 或历史 sweep 的设备结果充当本轮通过。

## 后续设备交接

设备检查需当前明确授权后执行。优先串行验证本页 B/C 类和 3 个补登记的 phase-runner；不能把 setup 缺失误当产品故障。真实模型 FirstSuccessJourney 单独要求模型来源、大小/哈希和 host driver。保存新 APK 哈希、flavor、API、页大小、逐用例 pass/fail/skip、错误详情；若仍失败，应以当前轨迹继续修复，不继续套用历史原因。

当前源码修改不替代 FFmpeg 的 Bionic-in-PRoot/MediaCodec 设备验收，也不更改其他 HXA 的设备结论。

## 后续授权的模拟器验收过程（历史快照，2026-10-02）

所有者已授权本任务模拟器验收及修复重测。使用现有 owned-emulator 驱动、只读 AVD、串行宿主槽；不借用真机或其他代理的设备，不保存 AVD 快照，不使用真实外部账号额度。所有本阶段产物位于 `build/hxa241/emulator-20261002/`。

- 普通 B/C 组：`consumer-152627-31443`、`developer-153111-32244` 分别为 18 类 / 86 个方法全部通过，具备原始逐用例日志与 `verified-methods.json`。其中附件测试后续有修改，最终仍须按新 APK 重跑，不直接把该旧结果绑定到新测试代码。
- 恢复 cutpoint：`phases-consumer-154908` 与 `phases-developer-154717` 各 13 个 setup/kill/verify 场景通过；共享存储的 developer 四阶段为 `phases-developer-153905`。前期 Connector 三项失败及修复后的记录均保留，不覆盖原失败。
- 编辑重发：`revision-admission-final` 的 consumer/developer 均通过真实正常 Activity 编辑、SIGKILL、重开及 Room 核验。旧 fixture 绕过了已迁移到 TurnEngine 的重发准入；改走当前准入并保留 supersededBy、旧历史隐藏、原 Turn 数量及不重发断言。
- 输入队列恢复：当前 `input-recovery-current` 重新验证 pending/appended/http-in-flight/cancelling，明确区别新建 PLAN 只读核查、用户已经授权的后继输入和旧执行重放；未完成前不计通过。
- 本地模型首次成功：`first-success-current` 在重启前的单次读取数量断言失败（实际两次）。保留严格断言，`first-success-diagnostic` 增加实际工具参数/状态/结果诊断后重测；该失败不能被写成恢复检查通过，也不能未经诊断归因为宿主重放。

当前表为过程快照，不是全量设备验收完成声明。最终必须核对源码/APK 身份、全部既定用例和所有已启动进程的结束状态，再回填本节。

## 设备阶段追加修订

本地模型首次成功样例的旧失败发生在 setup 杀进程之前：一次写入成功、第一次读取报告文件不存在、第二次读取成功，不能把它归因为进程恢复重放。为明确模型实际批次，保留并补强一次写入/一次读取及持久结果断言；基础提示补充“同一回复的工具可以并发、有依赖的调用须分轮等待前置成功”。最新 `first-success-dependency-guidance` 在最终 APK 上记录 `[[write], [read]]`，两工具均 COMPLETED；真实进程死亡后同 session/turn/workspace/artifact 保持，`duplicateSideEffect=false`。没有恢复全局文件锁、重排或伪造模型输出。`PromptEnvironmentSectionsTest` 增加一项覆盖各模式的打包提示回归，因此相对任务起点累计新增主机回归为 **18 项 Python + 4 项 JVM = 22 项独立方法**；上一节的 21 项是增加提示前的历史口径。

附件测试取消了“会话未选模型时回退 Provider 默认”的旧断言，改为验证未选模型时附件保留且不发送，显式选择后才发送；这与当前 HXA-239 一致。Connector 恢复测试保存并逐项核对杀进程前的完整插件选择集合，包括合法的渠道内置插件，而非硬编码只剩一个插件。编辑重发 fixture 改走当前 TurnEngine 的实际准入；输入恢复 fixture 区分旧 INTERRUPTED attempt、独立 PLAN 核查和已授权队列后继，保留原输入、版本、哈希、HTTP 次数及恢复幂等断言。

## 最终构建验收

最终构建普通矩阵已完成：consumer / developer 各 **18 类、86 个方法，0 失败、0 跳过**；同一方法跨渠道的执行不能计成 172 个独立方法。`final-matrix` 的两渠道各 13 个 setup/kill/verify 断点及 developer 四阶段共享存储检查均已通过，owned emulator 已正常关闭。`composer-final` 两渠道和 `first-success-dependency-guidance` 本地模型旅程也匹配最终 APK。

版本复核发现 `input-recovery-current` 与 `revision-admission-final` 使用了提示更新前的 APK，故保留为历史并通过 `hxa241-final-recoveries.sh` 重新跑最终构建，输出分别为 `input-recovery-final-build` 与 `revision-final-build`。两组已全部通过，执行 exit 0，执行前后冻结校验一致，不借用旧版本绿色。最终 `python3 -O scripts/debug/2026-10-02/verify-hxa241-final-evidence.py` **exit 0**：复用既有逐用例/owned-runner 解析器，核对当前 29 个变化的代码文件及四 APK、原始日志、适用断点集合、正常进程死亡/重开和历史 30 个失败类覆盖，已生成 `build/hxa241/emulator-20261002/final-acceptance.json`。

| 本次最终验收范围 | 结果 | 当前构建证据目录（相对 `build/hxa241/emulator-20261002/`） |
| --- | --- | --- |
| 普通功能/UI，consumer | 18 类 / 86 方法通过，0 失败、0 跳过 | `final-matrix/classes/consumer-171530-62519/` |
| 普通功能/UI，developer | 18 类 / 86 方法通过，0 失败、0 跳过 | `final-matrix/classes/developer-172024-62519/` |
| 内存、模型发布、导出、Connector、Workspace、备份恢复断点 | consumer 13/13、developer 13/13，通过真实 setup 进程死亡与新进程 verify | `final-matrix/recoveries/`、`final-matrix/recovery-results.json` |
| 手动共享存储授权/撤销 | developer 四阶段通过，涉及两个类；仍不扩大 Agent 文件权限 | `final-matrix/recoveries/phases-developer-172711/` |
| 输入队列 pending/appended/http-in-flight/cancelling | 两渠道各四场景，共 8/8 通过 | `input-recovery-final-build/` |
| 输入框/草稿与取消边界恢复 | 两渠道各一场景，共 2/2 通过 | `composer-final/` |
| 编辑重发、版本历史与进程重开 | 两渠道各一场景，共 2/2 通过 | `revision-final-build/` |
| 真实本地模型首次产物及进程恢复 | developer 1/1 通过；一次 write、一次 read，重开后工具数仍为 2、模型调用仍为 3，副作用未重复 | `first-success-dependency-guidance/` |

普通矩阵是 **86 个不同方法、两渠道 172 次执行**；恢复表统计的是带 setup/kill/verify 的场景或断点，不能与方法数直接相加来宣称更多独立用例。覆盖集合为原报告 18 个 B/C 类及 12 个需要专用驱动的 A 类，共 30 类。共享存储在适用 developer 渠道验证，本地模型首次成功使用既有固定 Qwen3-4B-Instruct-2507-Q4_K_M 素材，仅验证该样例，不宣称所有模型都能同样遵循依赖顺序。

环境为本任务 owned 的只读 API36 / arm64-v8a 模拟器；正常进程恢复的 `device.json` 实测页大小 **4096 bytes**。过程实际更换主应用 PID，并核查 Room/HTTP/产物身份。全部最终 owned-runner 有对应正常 `closed.json`，没有借其他代理的设备或真机；前置 setup 的预期进程死亡不是未解决崩溃。

### 最终源码和 APK 身份

HEAD 基线仍为 `a94ccc212ac2c2fce656c8ea015fa96767956aa9`，这些已验收修改在 main 工作区、尚未提交。`final-source-identity.json` 固定变化的源码、基础提示和四份 APK，`freeze-hxa241-device-inputs.py verify` 在补跑前后及汇总时均通过。

| 制品 | bytes | SHA-256 |
| --- | ---: | --- |
| consumer app | 86,965,458 | `b630c7db64799b202534efd79338ef1bbdacc3fbcc855a59fe956f6cbde126f0` |
| consumer AndroidTest | 7,788,696 | `a971d55983b387cf375b0d8049123accf6b0036ba367754b51f253d19923a330` |
| developer app | 150,086,689 | `2f4cfb53cee3edfb634a4fb7564902f8862f7b95f74cf9769f0e791c8a34ca2f` |
| developer AndroidTest | 10,894,511 | `9c79645e1b2d8ac34b419648b4ad25a836e538df5cbf35b3731a60d727fe85f9` |

最新源码对应的联合主机门禁仍为 **BUILD SUCCESSFUL**，`host-final.log` 为 990 tasks：45 executed、1 from cache、944 up-to-date；新增提示回归所在类 `PromptEnvironmentSectionsTest` 7 项通过。不是上方首次主机记录中的 31 executed，也不称为所有单测强制重跑。源码/制品门禁在最终文档回填后分别重新检查，日志使用 `source-acceptance-closeout.log`、`artifacts-acceptance-closeout.log`，不覆盖早期阶段记录。

复核入口是 `python3 -O scripts/debug/2026-10-02/verify-hxa241-final-evidence.py`，只读取保留的原始设备证据并生成最终汇总，不启动设备。原补跑入口须放在 `with-host-slot.py` 下运行；再次做设备验收必须使用新的输出目录，不能覆盖本次记录。`build/` 为 ignored，本页保留 Git 可存的结论和关键身份，原始日志未自动成为仓库制品。

### 验收边界

提交说明（2026-10-02）：所有者在验收后另行授权本地提交。提交前重新核对最终证据与冻结的 29 个代码文件/四 APK，未重跑设备；未修改生产代码或测试逻辑。历史 `final-source-identity.json` 与冻结脚本绑定验收时 HEAD 和未提交路径集合，因此提交后不能直接重跑原冻结比较来判断代码变化；应比较记录的逐文件 SHA 与提交树内容，保留原验收快照，不为匹配新 HEAD 重写历史证据。以下“未提交”是验收完成时快照，当前提交号以 Git 日志为准；不推送。

本任务请求的历史模拟器问题专项已通过，没有在该最终矩阵中留下失败、跳过或证据缺项；**不是重跑历史全部 782 个用例，也不是证明整个产品无 Bug**。原报告的 34 项账号/真实样本/物理设备等条件跳过未被擅自改成通过。API29、真机/OEM、真实 16 KiB 页设备、外部订阅账号、长期稳定性，以及 HXA-240 FFmpeg 在 PRoot 内的实际执行验收均不在本次结论中。未提交、未推送；其他代理的工作保持原样。

