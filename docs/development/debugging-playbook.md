# 测试与调试维护手册

## 门禁验证什么

用例数量、文件数量、代码行数及历史报告总数是观测值，不作为固定通过条件。新增或合并用例不应要求手动改一个“总共N项”的数字。统一主机发现入口为 `python3 scripts/run-host-tests.py`；新增 `scripts/tests/test_*.py` 会自动进入 source 门禁。空套件、导入失败、执行失败及全跳过仍失败。

Android 原始状态使用已有 `instrumentation_junit.py`：校验每个方法的开始、结束和身份、runner 正常结束，拒绝重复、缺失、不完整、失败及跳过。存在 runner 声明总数时，和本次实际方法数动态比对。固定评测清单仍检查具体 case/method 身份，不用另一个硬编码总数代替覆盖。

副作用次数（例如停止后不能多执行一次）、身份唯一性、输入/归档大小上限、256自动轮边界、FD/线程/PSS资源阈值及安全/权限/恢复断言属于行为契约，继续保留。资源循环逐类要求实际非空、零失败、零跳过，输出 `HelixSoakCases` 实际完成数量；host只汇总这些完成事件。缺少新事件的旧日志不估算为固定8项，原历史报告保持当时事实。

## 主机命令示例

按当前变更选用，任务明确要求的门禁优先；不把示例变成每次执行的完整流程。

Owner 本地迭代偏好：日常调试使用 Debug，不每轮运行 Release/R8。应用当前仅 Release 开启优化；无参数 `scripts/check-all.sh` 与 `--debug` 执行 source、Debug 静态检查、JVM 测试、两渠道 Debug 打包和 Debug 制品边界，不包含 Release/R8。正式发布前或明确需要优化/keep-rule/JNI/反射回归时显式使用 `--all`；CI 的显式完整门禁不变。两类验证结果分别记录，Debug 通过不冒充 Release 通过。

```sh
scripts/check-all.sh # 默认 Debug 主机门禁
scripts/check-all.sh --source
# AndroidTest或构建入口改变时，包含可选实验模块的完整Debug验证：
ORG_GRADLE_PROJECT_includeSpikes=true python3 scripts/with-host-slot.py -- \
  ./gradlew test detekt spotlessCheck lintConsumerDebug lintDeveloperDebug \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest
```

完整发布前主机门禁为 `scripts/check-all.sh --all`，包含 Release/R8；仍不能替代获得授权后对实际发布候选的设备验收。

主机Mock、语法检查和APK编译不证明真机、模拟器或模型任务成功。注入的失败日志属于回归输入，需和实际设备失败分开报告。

## 有授权的设备测试模板

仅在当前任务已有对应授权时使用设备/模型；同一任务的许可不因压缩或交接重新询问。新建实例时使用自有 serial 和新输出目录；所有者指定日常实例则保留既有数据并记录安装/清理边界。不因设备验收而清数据，不用快照恢复冒充当前数据库；设备授权不含真实账号或模型额度授权。

```sh
python3 scripts/with-host-slot.py -- python3 scripts/run-owned-emulator.py \
  --avd YOUR_INACTIVE_AVD --port YOUR_FREE_EVEN_PORT --memory-mb 4096 --cores 2 \
  --raw-results \
  --apk app/build/outputs/apk/developer/debug/app-developer-debug.apk \
  --test-apk app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk \
  --runner com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner \
  --classes YOUR_CURRENT_CLASSES --output build/debug/YOUR_FRESH_ATTEMPT
```

runner负责冻结APK及SHA、自有进程检查、键盘默认值、连续TestRunner/System.out日志和finally关闭。仅当任务覆盖输入/IME或所有者要求准备手动中文输入时，才执行实际拼音预编辑/候选/中文commit验证；无关设备测试不新增该阻塞条件。允许真实模型时才加 `--reverse-port YOUR_PORT` 和明确的 `--instrument-arg KEY=VALUE`，只用本轮指定服务和合成会话。

after-script必须在读取合成结果或执行切点前核对 `owner.json` 的PID仍活着、serial及AVD一致。只导出本轮自己创建的fixture及最小逐调用字段；不读取既有用户会话/凭据。进程死亡的setup本来会崩溃，它不是通过；必须记录新PID、持久状态以及原执行未重放的verify结果。

## 结果整理模板

```sh
# 单个原始instrumentation结果，不要求固定套件总数：
python3 scripts/instrumentation_junit.py build/debug/YOUR_ATTEMPT/instrumentation.txt
# 同一渠道、同一候选的不同API/独立实例；输出必须是新路径：
python3 scripts/summarize-owned-runs.py \
  build/debug/YOUR_API29_ATTEMPT build/debug/YOUR_API36_ATTEMPT \
  --output build/debug/YOUR_FRESH_SUMMARY.json
# XML格式结果沿用同一解析器：
python3 scripts/summarize-android-tests.py YOUR_EXPLICIT_XML \
  --output build/debug/YOUR_FRESH_XML_SUMMARY.json
```

owned汇总严格检查实际方法结果、owner与closed PID一致、正常退出、同一组APK hash一致；不同渠道分组。多阶段可重复 `--log RELATIVE_LOG_NAME`。颜色模式、HTTP请求次数、文件原子发布或工具业务结果等领域断言仍由专门fixture/after-script核实，通用汇总不替代它们。旧summary-only日志不能伪造逐方法结果，历史证据从Git恢复或按当时报告阅读。

### 成功构建与最终候选

先确认构建命令的终态和退出码，再冻结实际 APK/测试 APK 及构建输入，最后核对安装对象。构建失败时即使输出文件存在，也不能用它证明本次修改；误跑旧候选应保留原输出并标为 INVALID。后续修改只使受影响的证据失效：字节相同的未变渠道可以明确复用，改变了实现或依赖的候选需要对应补验。指纹及详细清单保存在 `build/<task>/`，指导文档不重复维护。

真实模型旅程同时核对原 Turn/Call/Job 身份、工具终态、实际文件或目标状态，以及需要时的下一次模型请求内容。归档中有 stdout、返回图片标志、Turn.COMPLETED 或模型自然语言结论都不能单独证明模型消费或业务结果。前一次失败与后一次通过分别保留，Provider/模型/渠道不能交叉冒领结果。[HXA-304](../evidence/development/preview-upgrade-confirmation-2026-10-17.md)包含旧候选 INVALID 和 collect 输出未进入模型的实际案例。

## 常见误判与定位顺序

| 现象 | 首先核对 | 保留的方法 |
| --- | --- | --- |
| 超时但没有模型响应 | 自己session是否有Turn、输入是否被admission接受、异步screen投影是否指向目标 | fixture等待自己的投影与配置，显式传sessionId；无Turn不能归因模型服务或额度 |
| OUTPUT_TOKEN_LIMIT | 当前安装APK/会话单次maxOutputTokens、reasoning和逐调用usage | 正向测试显式用适合该旅程的配置；原1-token失败测试保持，不据历史无usage轨迹统一改生产默认值 |
| 安装后启用/读取失败 | preview与实际安装hash、AWAITING_APPROVAL/DENIED、snapshot是否启用 | 批准只匹配本轮自建目录及repository实际source/name/hash；安装批准不代表启用批准 |
| 早期压缩/输入指标丢失 | logcat收集是否先于instrumentation、collector是否提前退出 | 连续过滤流替代最后固定N行；不调大系统缓冲，不清日志，不根据最终尾部补造早期结果 |
| 黑屏/卡顿 | 设备/API/渠道/安装版本、焦点窗口、Activity生命周期、主线程/ANR/崩溃、悬浮层 | 区分整屏和Helix页面；模拟器不能证明实体手机问题解决 |
| FD/PSS漂移 | 同PID、实际完整时长、fixture峰值与host采样、周期结束后是否已退进程 | 终末无PID/PSS记null；强制GC仅诊断，不能代替资源验收；15分钟pilot不等于2h/24h |
| 停止/恢复结果不一致 | Room原身份、UNKNOWN事实、真实executor退出、请求阶段 | 保留headers/body/执行已完成但尚未结算等切点；只读恢复新请求与原副作用重放分开 |
| 运行环境显示就绪但不能执行 | 当前安装记录、实际文件/版本、连接握手、命令启动与结果消费分别检查 | 不以旧验证回执或握手替代安装事实，不将显式检查变成自动重装；已有损坏内容按维护契约处理 |
| 文件看似改过但后续读不到 | 原资源、请求绑定目录、直接编辑或输入快照、运行目录映射、产物发布与显式导入 | UI名称不当身份；快照输出不自动覆盖源文件，不能把成功产物登记当作源目录已更新 |
| 模型表示已修改但看不到 diff | 本次工具参数/结果、是否有前后版本、Git仓库及当前选定比较区间 | 区分逐调用差异与Git累计差异；仓库无差异不证明未写文件，原始参数也不等于可复核差异 |

### 手机操作 ANR 取证

先将设备、候选、目标 PID、时间和显示身份对齐，再读取 `dumpsys activity lastanr` 与系统 DropBox 的 `data_app_anr`；后者可能仍含主线程及 RenderThread 栈，不能因 `/data/anr` 不可直接读取就断言没有堆栈。仅保留本任务目标条目，诊断材料留在忽略收据中，不输出凭据。

沿主线程实际等待链检查持锁线程、渲染线程或 Binder 对端。FocusEvent 超时不等于无焦点窗口、Shizuku 断连或权限失效；渲染线程停在模拟器 GLES 通道时，应结合压力数据和系统调度/窗口轨迹判断，不能仅凭栈顶归因应用死锁。模拟器磁盘配置、实际启动参数和 SurfaceFlinger 的运行时渲染器可能不同，应分别记录。

性能/ANR 复现期间避免并行大构建造成测量争用；捕获主屏对照、冷/热启动、创建/释放及原始失败。采样器必须容忍进程尚未创建或已退出，采样失败不能冒充产品失败；检查轨迹是否覆盖故障起点，环形缓冲尾部不能当完整启动轨迹。没有复现不等于根因消失，不以加长超时、隐式重启/重放或扩大授权作为修复。

### 删除与维护的诊断边界

1. 核对实际删除对象、所有者和仍在使用的 Job/终端；安装目录、用户工作区及未收取结果分别判断。
2. 核对确认前后状态：取消不清状态，过期或目标身份变化的确认不能继续删除；失败后保留实际已发生的部分效果。
3. 对涉及目录清理的实现，用合成数据检查普通子目录、内部目录链接、根链接、悬空链接及相邻保留文件。遍历不跟随链接，删除链接本身；验证不靠 `exists()` 对悬空链接返回 false 来冒充已删除。
4. 只有本任务明确拥有且获准清理的资源进入清理范围。运行环境修复不顺带清除用户工作区，删库遵循当前存储 ADR；“不兼容旧设计”不提供数据删除权限。

这些是诊断与用例选择依据，不要求每个文档或无关功能任务执行全套测试。[HXA-305 主机记录](../evidence/development/bundled-linux-environment-2026-10-17.md)保留链接越界删除的反例与修复边界，未执行设备/模型验收。

## 中文输入和视觉补验

Root/Shizuku依赖的UiAutomation与测试自己持有的UiAutomation可能竞争同一个系统服务。高权限后端测试使用外部ADB完成已授权的临时设置，在自有MainActivity可见之后启用服务，退出恢复原值；避免一边保留测试UiAutomation、一边声称验证了另一Root连接。授予App Root后仍须明确connect。合成目标的视觉ALL_CAPS与实际节点文字应保持一致，精确选择器按实际文字/ID验证，不能因测试不匹配而放宽生产匹配规则。单独的shell UID0不证明App RootService或任务可执行。[真机对照与失败记录](../evidence/development/physical-followup-2026-10-12.md#批准后的升级后端与模型补验)保留当前案例。

语言已勾选、键盘可见或UTF-8注入成功不能证明拼音可用。运行实际预编辑→中文候选→点击提交夹具，并在Helix未运行的系统输入框对照原生崩溃。Gboard公开词包缺失/0字节与解码器故障应保留大小、哈希、版本及健康对照；root读取须单独授权，只检查公共资源，不读个人词库、账号或凭据。不要清日常数据；可在新空白自有AVD等下载完成、普通重启输入法后复验，再冷启动确认。原故障证据与替代环境通过分别记录。

真实视觉用运行时像素决定且提示/初始DOM未披露答案的合成目标，独立核验操作结果。fake Provider编码、请求manifest输入ID、准备图片标志都不能证明模型收到图片；实际请求图片字节的哈希应与持久附件对应。截图前后相同仍可能有DOM状态变化，要明确效果核验来源。后台返回还须检查实际会话可见内容及截图；非单色首次使用须知并非成功返回会话。当前案例及失败尝试见[2026-10-12补验](../evidence/development/device-vision-stability-followup-2026-10-12.md)。

## 何时删除或晋升脚本

一次性源码/文档改写器完成后，以当前源码、稳定回归和Git历史承接，不继续当迁移入口。重复launcher/解析器合并到现有稳定入口并更新调用方；只有Mock/Pure host测试进入自动发现，设备/真实服务测试仍显式opt-in。依赖锁、vendor输入、许可证、唯一协议或未关闭问题的诊断不能按日期删除。

删除前逐路径核对实际用途、所有当前调用者及替代；tracked源码记录Git来源，未提交或本地唯一脚本先保存并校验恢复副本。本地历史脚本按日期归入 `scripts/local/debug/`，详细校验清单也保留在该忽略目录；本地历史文档放入 `docs/local/`。Git文档记录稳定来源及处置结果。原始失败日志、数据库及模型轨迹和脚本分开留存，本地tar不是长期备份。历史复现以删除清单中的Git提交和路径恢复，禁止直接在当前工作树重跑旧改写器。

本次具体删除、迁移与验证见[HXA-267记录](../completion-records/HXA-267.md)及[清理证据](../evidence/development/test-script-maintenance-2026-10-10.md)。

历史版本日志不必永久另存副本：经Owner明确授权，核对其归属及当前复现入口后，按“来源提交/版本与APK身份 → 当时成功和初轮失败机制 → 修正与验证边界 → 现行复现入口 → 删除路径”汇总。删除后在原报告注明原始日志不可用；未经执行不填当前通过，不用清单冒充备份。示例见[历史版本整理](../evidence/development/historical-version-retirement-2026-10-10.md)。

## 插件市场与模型夹具生命周期

以下是相关测试的诊断参考，不是所有任务的前置流程：

- 订阅生产入口在主 APK 的私有进程；测试宿主、旧独立 APK 与产品的包、沙箱及凭据不同。按实际 manifest/target 确认对象，不导入宿主登录记录。[退役记录](../evidence/development/runtime-artifact-retirement-2026-10-13.md)保留旧架构证据。
- 安装、启用、会话选择、连接及执行审批各有事实；异步条目、持久结果和 Activity/窗口分别等待实际条件，Compose 空闲或 startActivity 返回不代表就绪。夹具只执行获准渠道路径，不代做被测模型动作。
- 保留原 Turn/Call/Job 与恢复 Turn 身份、业务结果和清理结果。停止请求不等于执行已退出；删除本轮合成数据前等待观察/执行结算结束并核对持久终态，不重放未知效果，不清其他用户数据。
- 资源对照使用相同生命周期/渲染状态与原阈值，缺失 PID 样本记未知而非零；诊断 GC、反复重试或正常 Turn 结束不替代业务通过。测试需恢复其临时设置和本轮服务，不覆盖其他服务配置。
- 模型叙述、可见 UI、原 HTTP/SSE 输出、输出 schema、持久回填和下一请求各是不同证据；保留全部失败与未测条件，不由小样本推断 EOS 根因或普遍收益。

具体夹具同步、返回帧、服务重连、输出契约及清理竞态案例在 [HXA-281 补验](../evidence/development/plugin-marketplace-device-model-2026-10-13.md)和 [HXA-282 证据](../evidence/development/external-mcp-market-and-model-2026-10-13.md)中，不将单次故障步骤升级成全仓强制流程。

## Agent 日志分层与失败诊断

排障按原 session/Turn/ModelCall/ToolCall 身份关联，不能把界面卡片或 logcat 尾部当成完整执行账本。

| 层 | 保存内容与入口 | 留存/边界 |
| --- | --- | --- |
| 执行事实 | Room 的 Turn、模型调用、工具调用/结果、审批与恢复身份 | 持久事实；终态不证明业务成功，不因进程重启自动重放 |
| 模型上下文 | prompt 指纹/分段来源、request manifest、usage/client observation、budget 事件 | 可定位实际模型消费与耗时；不额外复制整份 prompt 到诊断日志 |
| 模型失败 | 同 ModelCall 的 `model.failure` 与 `model.automatic_retry` | 持久记录阶段、HTTP/流/网络分类、计数、重试决定；会话导出包含这些事件 |
| 工具输出 | 原工具结果、命令日志/Job 的分页内容与原执行引用 | 输出、收取与 ACK 分开；截断/缺失不是空成功，不为读日志重新执行命令 |
| 进程故障 | 设置中的诊断预览：最后任务、heartbeat、系统退出原因、异常类型/指纹及有界 cause/代码位置 | 崩溃同步保存后可跨重启查看；不含异常消息/源码路径；仅最近一次，不是全量系统日志 |
| 当前系统输出 | 应用日志搜索读取当前 PID 的 logcat 快照 | 临时有界，可能被系统覆盖，不能代替持久任务诊断 |

新模型失败至少判断：发生在连接、HTTP 还是流读取；HTTP 状态/响应类型；DNS/TLS/连接/超时；SSE/JSON/工具格式/缺失终止；是否已有内容或工具片段；原适配器可重试判断与最终策略；次数和等待时间。服务请求标识仅留 hash；未知服务错误保留 UNKNOWN，不把任意正文伪装成可信错误类别。HTTP 错误前缀最多读取 8 KiB，Retry-After 最长采用五分钟；等待可取消，不形成额外动作重放。错误输出与协议内容不进入普通系统日志。

参考 [OpenAI Agents SDK tracing](https://openai.github.io/openai-agents-python/tracing/)、[LangSmith runs/traces/threads](https://docs.langchain.com/langsmith/observability-concepts)、[LangSmith 内容保护](https://docs.langchain.com/langsmith/mask-inputs-outputs) 和 [OpenTelemetry HTTP 语义](https://opentelemetry.io/docs/specs/semconv/http/http-spans/)，采用关联身份、阶段/错误和内容分离；不引入这些平台的远程导出、采样或新 SDK。现有 Room 不新增自动淘汰策略；会话删除/导出遵循原生命周期。历史仅含 PROTOCOL 的记录无法反推当时丢失的 HTTP/SSE 原因。

模型请求准备阶段的配置校验继续 fail closed，不能用五次网络重试掩盖无效请求配置；托管运行时整体执行不在空推理重试范围。纯解码器的非法结构异常会转换为不可重试的结构错误，并立即停止读取；收集器、持久化、授权和副作用提交异常不在该捕获边界，不得伪装成可重试网络故障。

### 诊断容量与过期

HTTP 失败的自由文本摘要在“诊断存储 → 最近诊断 → 详情”按原 Turn/ModelCall 身份查看；只保存选定、脱敏且有界的 `message/detail`，完整响应和请求头不落盘。长期 Room 中的 `model.failure` 不含该自由文本；摘要过期或当时未采集时，不能从 HTTP 状态或 UNKNOWN 类型还原它。400 是服务端拒绝请求，不能一律归为网络重试问题；先核对参数、上下文窗口及历史回填是否一致，禁止为排障重放历史工具。

“诊断 → 诊断存储”管理独立诊断详情：默认普通 14 天、故障 90 天、运行日志 7 天/32 MiB，总预算 256 MiB，80% 水位启动清理到约 70%。可调整期限/预算、保留排查、查看详情和清理过期项；最近记录按需展开。容量不足可提前清理未保护详情，已清理内容保留明确索引；受保护内容无法回收时计数显示未保存项，不影响执行事实写入。

当前来源是宿主结构化故障、模型失败、重启归档的最近崩溃、诊断预览和主动读取的日志快照，不是完整 logcat 长期录像。普通/故障附件分类分别支持 7/30 天，但本轮不自动采集截图。会话/工具结果、Room 审计与恢复事实、成果及用户文件不属于诊断预算；因此该预算不是整个 App 的磁盘总上限。原 Runtime 的预览和结果交接清理继续独立执行。

维护在主进程启动及存活期间每日机会性运行，进程退出或强停后会推迟；不唤醒模型。导出仅回收超过 24 小时的无主本地暂存，未解决操作、外部目标及用户保存的文件保留。损坏或未识别文件不盲删；保留标记跨重启，Room 中仍在运行/核查/恢复的原身份受保护。具体契约见 [ADR-PERMISSIONS-003](../adr/permissions/003-dispatch-and-audit.md)。

`model.automatic_retry` 的 `phase=BACKOFF` 表示已安排等待，不证明下次 HTTP 请求已发出；取消可能在等待期间结束它。实际尝试次数需与模型调用的请求/结算和 client observation 一起核对，不能仅数安排重试事件。TLS/协议配置与账号身份错误不会按普通 IOException 重复请求；内置 401 的既有认证刷新与模型层重试分别计数。

阶段名 `CONNECT` 指 `Wire.open`（含连接、提交请求和等待响应头），不是“请求尚未送出”的证明。`receivedBytes=0` 仅表示尚未收到响应正文；服务端可能已经收到请求。`elapsedMillis` 与 client observation 都是客户端观测，不等于服务端推理耗时。
