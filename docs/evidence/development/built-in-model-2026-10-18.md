# 内置模型接入验收记录

任务：[HXA-307](../../development/tasks/HXA-307.md)。基线为 `main` / `00e529b9`；接续本任务已有未完成的 Provider/默认选择代码，保留全部既有工作。验证后仅本地提交，不推送、发布或更改已发布资产。

## 本地实现

固定内置 Qwen 来源、公开 Client ID 传输和不可编辑配置；新安装预选、已有明确选择与会话保持；复用图片与工具 SSE；连接事实与声明能力分开。401/429/502/504 提示按原调用身份展示，禁止自动重试和付费回退。设置与新空白会话披露云端数据范围，共享额度不显示成个人额度。

## 缺陷与修正

本轮编译检查发现新增 provisioning 的测试枚举需要补齐；定向测试修正了 504 超时映射的旧断言。边界审查还发现规范化地址显式带 `:443`，已允许同一固定端点的规范写法并拒绝其他端口、路径、查询及来源。失败日志保留于 `build/hxa307/`；未放宽权限、地址或能力检查。

进一步验证了传输层隐藏重试：本地 `503 + Retry-After: 0` 在只关闭连接重试时仍产生额外请求，新增断言修复前为预期 4、实际 5。内置请求体增加 one-shot 语义，阻止 HTTP follow-up 重发而不改写状态/响应头；修复前 XML 与日志独立保留。普通自配 API 默认传输行为不变。错误展示只给内置调用快照增加来源字段，不改写其他来源的快照格式或历史 Turn 身份。

其他门禁曾发现固定内置模型与“所有来源都须手选”的旧测试断言冲突、简体中文资源漏项及描述中模型名触发 Android TypographyDashes。现已按新契约替换断言、补齐三套语言资源，并从描述移除重复模型名；单独的模型标识仍保留准确拼写，没有关闭 lint 或放宽检查。

## 验证结果

以下结果来自本轮提交前冻结的同一生产与测试输入；后续本地提交固定这些已验证源码与文档，不把 APK 内原基线元数据当作新提交的干净构建。输入清单、校验结果、原始日志、失败 XML 和汇总保存在忽略的 `build/hxa307/`；未将旧发布 APK 的验收转作本次证据。

| 检查 | 本轮结果 | 边界 |
| --- | --- | --- |
| `:app:testConsumerDebugUnitTest` | 1301 通过、0 失败、4 条件跳过 | 包含新增内置模型、默认选择和真实 HTTP socket 夹具 |
| `:app:testDeveloperDebugUnitTest` | 1425 通过、0 失败、4 条件跳过 | 两渠道重复的方法分别计数，不是 2726 个独立业务场景 |
| `:provider:api:test` | 118/118 通过，零跳过 | 固定目标/公开标识与 HTTP 状态映射 |
| `:core:model:test` | 163/163 通过，零跳过 | 新 provisioning 与 HTTPS/无凭据契约 |
| `BuiltInModelTransportTest` | 8/8 通过，零跳过；已包含在应用套件中 | 分段 SSE、图片、工具及回填、401/429/502/503/504、重定向、取消与半截工具流；没有公网调用 |
| `:app:assembleConsumerDebugAndroidTest`、`:app:assembleDeveloperDebugAndroidTest` | 编译通过 | 新增 Room 3 项、Compose UI 2 项的设备执行见下表 |
| `./scripts/check-all.sh --all` | 退出 0 | 276 项 source 测试、文档/ADR/i18n/秘密扫描、Spotless/Detekt/lint、全仓 JVM、双渠道 Debug/Release/R8、Release smoke 测试 APK 编译、依赖锁和制品边界通过 |
| 日常 API36/ARM64、两渠道 Debug 的 `BuiltInModelStorageDeviceTest` 与 `BuiltInModelUiDeviceTest` | 每渠道 5/5 通过，零失败/跳过 | 临时 Room 数据库和纯 Compose 夹具；不是完整真实服务验收 |
| 同源 R8 两渠道冷启动与进程重启 | 每渠道 2/2 通过 | 原会话恢复、已有 Provider/model 绑定、界面模型名及安装身份一致 |

应用每渠道 4 条件跳过分别是公开配置、公开 MCP 只读查询、QwenWork 包、WorkBuddy 包；本次没有启用其外部输入变量，未修改或删除用例，不借用先前通过记录补算本轮结果。使用现有 JUnit 汇总脚本读取当前模块报告，含跳过的应用汇总如实标为 `INCOMPLETE`；它不表示主机必选检查失败。

完整门禁日志为 `build/hxa307/host-all-verified.log`，双渠道测试 APK 编译日志为 `build/hxa307/android-test-final.log`。成功构建的双渠道 Debug、未签名 Release 及本轮 Debug AndroidTest APK 身份保存在 `build/hxa307/artifacts.sha256`；源码输入复核一致，未把目录中旧 Release AndroidTest APK 纳入本轮编译证据。

## 日常模拟器追加验收

Owner 明确要求实际执行上述 5 项设备用例并验证保留已有选择的重启。使用现有 `Helix_API_36` / `emulator-5554`，没有创建新实例或清理应用数据。复用本地保数据安装检查及 `scripts/instrumentation_junit.py`；签名和 Room schema 一致、没有活动 Turn 后才执行 `adb install -r`。两渠道命令均为 `adb -s emulator-5554 shell am instrument -w -r -e class com.helix.app.provider.BuiltInModelStorageDeviceTest,com.helix.app.provider.BuiltInModelUiDeviceTest <package>.test/com.helix.app.HelixAndroidJUnitRunner`，其中 `<package>` 分别为 `com.helix.agent` 和 `com.helix.agent.developer`。

Room 夹具只创建/删除独立临时库；界面夹具没有真实服务请求。每次 APK 替换前后逐表摘要一致；首次实际启动只增加内置 Provider，原生插件按既有注册流程递增 revision。原 Provider 全字段、会话/消息/调用及其他表保留；插件除 revision 外的内容、启用和默认选择一致。既有自配模型仍绑定原会话，冷启动和再启动截图/XML 中的模型名一致，没有被内置来源替换。测试运行器的语言设置已恢复，两个测试包已卸载，日常模拟器保留本轮 R8 包并停在 Advanced 供手测。此处使用主机默认多 ABI 构建在 ARM64 上运行，没有制作新的单 ABI 发布资产。

首个 Standard 批次设备测试已 5/5，但后续临时保数据脚本错误地要求插件 revision 也不变而中止。经核对 `PluginRegistry.register/publish`，并将新记录的两次 revision 增量还原后，完整表摘要与原值相同，证明没有其他字段变化。收紧到“仅允许既有原生插件 revision 递增，其他字段仍严格一致”后，两渠道完整批次及 4 次启动全部通过；首批失败日志保留，不混入最终 10 项统计。设备阶段没有发现需要修改生产代码的新缺陷。

最终原始 instrumentation、JUnit、截图、安装摘要及选择核验位于 `build/hxa307/device-final/`；初次检查和修正依据保存在 `build/hxa307/device/`。设备全程没有发送模型任务，`model_calls` 表未变化；这里不把已有会话中的历史 Qwen 输出计作本轮模型调用。

## 交付与剩余边界

新增实现集中于 `BuiltInModel` / `BuiltInModelRegistration` / `BuiltInModelProvider` / `BuiltInModelRow` 及 `PublicClientWireClient`，现有 Factory、ProviderService、选模存储、错误投影和资源接入；没有新增依赖、Room schema 或第二套 Agent 执行循环。Provider ADR、当前状态、HXA 和双语 README 同步，README 明确已发布 v0.0.6 不含此接入。

真实网关联调为 **pending**：等待 Owner 确认公开 Client ID 白名单登记。登记后需验证公网 TLS、内置模型文本/图片与工具结果的实际消费、真实错误/取消及完整 Agent 闭环。新安装默认选择已有独立 Room 夹具证据，但未清空日常数据进行全新安装旅程；保数据启动/重启已按上节实际执行。真机不在范围。JDK loopback 夹具不代表 SGLang、图片理解质量或跨用户限额实测通过。无历史 Key 回退、推送、发布、tag/资产修改或用户数据清理。

## 2026-10-18 追加：动态目录、布局与插件版本身份

本节承接 Owner 后续要求；前文为 `e4434a90` 阶段事实，不作为追加改动验收。内置服务从 Provider 配置移除，仅在对话选择网关目录；目录按需刷新、成功 30 分钟/失败 1 分钟缓存，失败保留已有目录，没有历史则为空。查询合并且取消不发布半份结果，不做推理健康探测、不改已有选择。当前 Qwen 保留 Owner 已给出的能力声明，后续目录新增 ID 不继承视觉/工具支持；原调用错误按精确目标处理，不切换付费 Provider。

模型来源和插件同层入口改为 Material 可滚动 tabs，聊天配置入口等宽单列；选模弹窗使用统一滚动区域，保留原 Composer 同排及完全访问仅文字提醒。4 个内置插件和当前工具声明版本统一为 1；界面将安装 CAS 计数明确标为内部修订。原生内容/启用相同的启动不写库或递增，健康 repair 不换绑定；实际内容、启停或投影修复才递增。禁用下内容变化仍更新修订且不启用。

原生持久工具来源以 pluginId/runtimeId 识别。当前已安装数据的旧版本键由一个启动事务规范化，保留各范围明确禁用并移除旧键，不建立长期双轨。Mobile Use 授权仍由用户配置与持久会话选择派生；包版本、安装 revision 和运行 incarnation 不互作用户许可。单次证明与执行 ref 的替换保护保留；一次批准不会变成未来批准，ALLOW 不因单纯版本升级新增卡片。

追加主机验证已通过。主机环境已核实为 Owner 的真实 Mac 工作区，现有 Gradle 9.5.0 和 Java 17 可用；先前 wrapper 锁和 native 库错误来自执行沙箱访问限制，解除该命令的沙箱限制后正常启动，没有改 DNS 或重装 Gradle。新目录测试使用与 OkHttp 同版本锁定并校验的 MockWebServer 5.5.0；仅 JVM 测试依赖。

保留的失败包括旧固定目录断言、未许可 GET 夹具的旧预期、AndroidTest 渠道依赖范围和静态结构/格式项；已按新行为补足断言、把 Mobile Use 夹具限定在 Developer，并修正当前代码。完整 JVM/Release 构建曾在复用 4 GiB Gradle daemon 时出现 `Java heap space`，未将该次制品用于安装。后续同一门禁采用进程级 6 GiB、2 workers、关闭项目并行与 single-use daemon；不更改仓库全局内存设置或削减测试。锁文件门禁补齐 MockWebServer 的两渠道 UnitTest lint classpath 后通过；一次文档标题不符合 HXA 模板的错误已恢复规定的唯一“交付”标题，没有更改检查规则。原始失败、最终命令和输入摘要在忽略的 `build/hxa307-catalog-revision/` 中保留。

### 追加主机结果与候选来源

最终 `./scripts/check-all.sh --all` 退出 0；命令前设置 `GRADLE_OPTS='-Dorg.gradle.jvmargs=-Xmx6g -Dorg.gradle.workers.max=2 -Dorg.gradle.parallel=false -Dorg.gradle.daemon=false -Dfile.encoding=UTF-8'`。日志 `host-all-final.log` 包含 276 项 host fixture、930 份文档、37 份 ADR、i18n/秘密扫描、Spotless/Detekt/lint、全仓 JVM、两渠道 Debug/Release/R8、Release smoke 测试 APK 编译、42 份依赖锁及 Debug/Release 制品边界。fixture 中预期的失败/恢复输出不是实际设备执行。

| 当前模块/检查 | 通过 | 失败 | 跳过 |
| --- | --- | --- | --- |
| 应用 Standard JVM | 1309 | 0 | 4 |
| 应用 Advanced JVM | 1434 | 0 | 4 |
| Plugin 模块 JVM（其中 Registry 26 项） | 89 | 0 | 0 |
| 工具框架 JVM | 263 | 0 | 0 |
| 存储 JVM | 242 | 0 | 0 |
| Mobile Use JVM | 311 | 0 | 0 |
| Provider API JVM | 119 | 0 | 0 |
| Core Model JVM | 163 | 0 | 0 |
| 文件工具 JVM | 130 | 0 | 0 |
| Browser 工具 JVM | 45 | 0 | 0 |
| QuickJS JVM | 103 | 0 | 0 |
| 两渠道 `assemble*DebugAndroidTest` | 编译通过 | 0 | 0 |

应用各 4 个外部输入条件跳过项与原阶段相同；没有提供公开 MCP/配置及两种外部包输入，未改用例或补算历史结果。现有汇总器将含条件跳过的应用套件标为 `INCOMPLETE`，必选主机门禁本身成功。新增目录、缓存、取消和 transport 测试无跳过；两渠道重复方法分别计数。

当前候选以 `e4434a90` 加本轮工作区改动构建；最终本地提交固定这些字节。`candidate-source-inputs.json`、`final-jvm/`、`android-test-final.log` 及后续设备目录保存输入映射、原始 XML、精确 APK 和安装字节验证。不把 APK 内的基线提交元数据描述为本次提交后的干净构建，不用失败构建遗留的 APK 证明本轮验收。

合法成功空目录另有回归：`data: []` 清空缓存候选，失败仍保留原目录；当前用户目标不被替换。普通 API/订阅连接检查和通用配置验证遇到空目录仍失败且不新增生成请求。目录成功不被转写成推理或能力成功。

### 追加设备诊断

首次批量与随后按类分进程的 Standard 批次均停在插件导航测试的 `performScrollTo()`，线程栈显示 Compose/Espresso idle 等待；采集诊断后主动停止自有测试进程，因此 instrumentation 报 `Process crashed`。这两批均为不完整，不计通过，也没有把其他 PID 的历史 AndroidRuntime 输出归为本轮产品崩溃。已改为对可见 tab 先断言显示再点击，保留所有选择和内容断言，修正后的插件导航及 Standard 竖屏 28 项实际执行通过。此批随后因本地汇总脚本把结果字典按列表处理而中断，逐类原始日志保留，完整设备验收另计。

中止后核对最初日常数据：会话、原 Provider、选模、权限/凭据和显式禁用保留；Browser/Documents 因本轮内容变更从 25 各增至 26，随后多次进程启动均不再递增。测试语言会暂时改变 SQLite `android_metadata` 的 locale；恢复原语言偏好并普通启动后，原摘要恢复，无需放宽用户表断言或直接改库。各次自有测试包和设置均由 finally 清理/恢复。两次中止、线程诊断、汇总错误和严格复核结果保留于忽略的本任务目录。

首次标记为横屏 1.8 倍字体的来源导航执行 5 项，其中 3 项失败、2 项通过：旧用例直接要求单列配置入口在首屏可见，或未滚动即点击未组成的 LazyColumn 子项。改为通过弹窗列表滚动到目标，保留显示、目标回调、关闭弹窗和不选模型的断言；插件详情先定位外层纵向 viewport，再操作横向 tab。随后这些用例通过大字体检查；最终截图复核发现此阶段旋转夹具未实际转屏，因此这些记录只证明竖屏大字体，不作为横屏证据。失败批次保留，不并入最终通过计数。

实际模型管理截图中的原“Qwen API”是 Owner 既有的自配来源，保留其历史连接/能力状态；不是内置配置卡片，也不是本轮公开 Client ID 白名单或推理成功证据。没有使用该来源的 Key 代替内置请求。

随后 Advanced 实际页面先遇到已可见 tab 的通用滚动节点在重组后离树，再遇到导航根尚未就绪即开始操作。保留失败批次，改为断言可见后点击，并在既有 10 秒 UI 时限内等待导航入口；只读普通启动已确认入口存在且没有改变数据库/关键偏好。修正后实际页面通过。

原生 Room 重启/授权/禁用规范化用例实际通过；另一个全注册表版本断言失败，定位到 11 个带类型的 `VERSION: Int` 工具 API 常量仍为 2/3。已将文件、Browser、QuickJS 与 Plan 工具声明补齐为 1，保留执行协议、归属记录及持久版本计数；增加 JVM 常量回归，更新原版本声明断言，其余权限/边界断言保留。该失败不计入最终通过批次。

Standard 在 18:50:59 完成前一候选验收后出现独立状态差异：Provider、Session 和运行设置摘要以及草稿选模偏好变化，运行设置更新时间为 18:54:56，晚于上批 Advanced 测试结束 18:52:37；无新活动 Turn，其他业务表和 Mobile Use 配置保持。当时来源尚未确认，保留当前值并暂停安装 Standard，发出异步核对；Advanced 的原用户字段、选择、授权和禁用另经严格复核保留，按追加指示独立继续。

Owner 随后明确回答“是，保留当前设置继续验收”，确认上述变化为手动操作。原值不回滚，标准安装暂停已解除；最终两渠道以当前用户设置建立新保数据基线。白名单尚未确认，这一回答不作为真实推理授权或服务成功证据。

补齐主源码的 11 项后，库存进一步指出 Advanced 专属 Linux 三个 API 声明仍为 2/3，现已修正为 1。全生产源码集复查只剩历史执行封装的原版本反序列化与独立存储格式版本；它们保持不变。新增 Advanced JVM 回归同时断言公开 API 1、PRoot 协议 3、绑定 payload 2，区分声明与内部/历史身份。

`device-194216` 的两渠道竖屏和 1.8 倍字体用例均通过，真实全工具注册表版本断言也已通过。截图复核揭示 `settings put system user_rotation` 没有使实际显示离开 1080×2400；该目录的“landscape”标签不代表真实横屏。验收夹具随后改为同步 WindowManager 旋转命令，每个测试类前后检查实际 DisplayFrames，并断言截图宽高方向；保留原旋转模式并在 finally 恢复，重新执行同源两渠道验收。此改动只在忽略的本地 runner，不改变生产源码或已验证构建。

修正旋转后的 `device-194626` 已实际执行 Standard 的 28 项竖屏与 9 项 2400×1080/1.8 倍字体检查，全部通过；随后本地 runner 将 WindowManager 单词形式的 `free` 误当作两字段，恢复阶段报错，该批不计完整验收。自有测试包及原语言/系统设置已先恢复；补齐 `free` 分支后，普通启动恢复数据库 locale，严格复核全部表、关键偏好、授权、禁用和 revision 与该批前置摘要一致，旋转模式恢复 `free`。原始异常与恢复核查保留，完整两渠道另行重跑。

### 追加设备最终结果

最终本地 runner 退出 0，证据目录 `build/hxa307-catalog-revision/device-194912/`，命令日志 `device-final.log`。目标为日常 `emulator-5554` / `Helix_API_36` / API36 / `arm64-v8a`。使用成功主机门禁的 Debug、两渠道 AndroidTest 与同源 R8 制品；安装前验证相同签名/Room schema、无活动 Turn，安装后读回精确 APK。3116 份源码/资源/构建输入在主机构建、设备验收及最终来源核查间一致；没有用中止或失败构建遗留制品作为当前证据。

| 渠道 | 竖屏 1080×2400 | 横屏 2400×1080 / 1.8 倍字体 | R8 冷启动/进程重启 | 失败/跳过 |
| --- | --- | --- | --- | --- |
| Standard | 28 | 9 | 2 | 0 / 0 |
| Advanced | 30 | 9 | 2 | 0 / 0 |

76 次方法执行包含跨渠道/不同方向重复运行，不能当作 76 个不同用例。两渠道共同覆盖目录隐藏/动态新模型/选择保留、真实 Room 注册、三类来源导航、现有选模、Composer 同排和完全访问文字；Advanced 另有实际全注册表版本 1 及隔离 Room 的 revision/incarnation/授权/禁用/repair 两项。每个类前后实际 DisplayFrames 与截图方向均校验；已人工查看 API/订阅/本地与插件详情的竖横屏截图，标签同排、内容可滚动且无重叠。R8 未执行 AndroidTest 方法，仅记实际安装后的冷启动、进程重启与数据核对。

最终两渠道 `changedTables` 均为空；全部用户表摘要、会话/选模、权限范围、显式禁用和关键偏好与各自当前基线相同。Standard 保留 Owner 已确认的手动设置，未恢复旧值。原生修订 Standard Browser 27 / Documents 26，Advanced Browser 13 / 其余 12，在该批及随后两次 R8 启动均不变。此前 Browser 因补齐工具 API 声明产生的指纹变化递增属于实际内容变化；没有清零内部计数。自有测试包已卸载，原语言、字体与旋转模式恢复，既有中文输入法配置未修改；两渠道均留下同源 R8，Advanced 处于前台。

真实网关推理/图片/Agent 工具闭环仍为 **pending**，等待公开 Client ID 白名单；本追加收口未重复公网探测，也未调用既有自配 Key、订阅账号或其他付费来源来替代成功。目录夹具成功不能证明推理可用。真机/OEM/其他 API 矩阵与长稳未运行；本次仅日常 ARM64 模拟器的上述有界验收。完整主机/设备证据与失败诊断保留，本次仅创建本地提交，不推送、发布或更改既有 v0.0.6 tag/资产。

## 最新追加：显式保存、思考档位与普通版本呈现

本节对应 `608d4345` 之后的 Owner 反馈；不继承前轮通过结果。原生 revision 逻辑无需清零或再修改，普通就绪详情漏出的内部计数字段已移除；包详情继续显示声明版本 1，两渠道安装历史保留。

根因：内置能力将 reasoning 标为不支持，而精确内置来源禁止通用付费能力探测；原会话模型行点击直接调用切换，推理下拉也即时调用运行设置 setter。这两处改为同一弹窗编辑状态，保存经原有有序队列、空闲检查、原会话/原设置比较和当前模型校验，Room 中一次提交模型与强度。草稿保持内存编辑和既有物化流程，保存保留其他当前运行设置。取消/返回、刷新、身份变化和重建分别处理；失败保持编辑，保存中阻止重复提交。草稿将模型和强度在物化使用的同一锁内比较并替换，物化先取得锁则保存返回忙碌，不能静默报告成功；新会话/新弹窗使用请求代次隔离，旧成功或失败回执不能关闭或污染新弹窗。

### 开源与竞品核实

Owner 允许按最新开源 SGLang 检查，部署不支持时由 Owner 升级。官方 Qwen 仓库和模板由本次研究选作客户端已知适配参考，Owner 未确认该仓库/模板就是当前部署，不能将其写成用户确认的部署契约。[v0.5.21 发布](https://github.com/sgl-project/sglang/releases/tag/v0.5.21)是本次查询的最新稳定版。其 [OpenAI ModelCard](https://github.com/sgl-project/sglang/blob/v0.5.21/python/sglang/srt/entrypoints/openai/protocol.py) 没有模型思考档位枚举；[Chat 入口](https://github.com/sgl-project/sglang/blob/v0.5.21/python/sglang/srt/entrypoints/openai/serving_chat.py) 把请求 `reasoning_effort` 传给模板。通用 schema 接受某字符串不证明具体模型模板生效。

[官方 Qwen3.8-27B 模板](https://huggingface.co/Qwen/Qwen3.8-27B/blob/1d4bf0f2ff6012fd82039f2fa52739d0dd7c60c0/chat_template.jinja)的固定版本接受 `low/medium/xhigh`；默认 `xhigh`，直接 `high` 拒绝。精确内置 ID 因此提供默认/低/中/高，高沿用现有编码映射 `xhigh`，默认省略参数；未知动态 ID 不继承这些档位。界面说明默认不是关闭思考，实际部署效果尚待验证，不把模板提示等同于严格 token 预算。

在忽略的构建目录使用隔离 Jinja2 3.1.6 环境，直接获取固定版本官方模板并纯 CPU 渲染，6 项通过：低/中/xhigh 模板输入不同、默认等同 xhigh、raw high 拒绝、显式关闭模板产生空 think 前缀。结果 `build/hxa307-picker-reasoning/template/result.json` 与本地 helper `scripts/local/debug/2026-10-18/verify-qwen38-template.py` 保存来源和断言；未运行模型、未访问项目网关，测试依赖未进入应用或项目依赖。

[Codex model/list](https://learn.chatgpt.com/docs/app-server#models)有专有 supportedReasoningEfforts/defaultReasoningEffort；[OpenRouter Models](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens#discovering-per-model-reasoning-options)也有实际 per-model reasoning.supported_efforts/default_effort 等扩展；[OpenCode variants](https://opencode.ai/docs/models/#variants)体现 Provider 适配。这些不能当作 SGLang `/v1/models` 的通用标准。采用已有可信模型元数据优先，加精确官方模板契约，不引入启动/打开弹窗推理探测或新网关 API。

### 验证诊断（最终结果见下）

初轮格式与 Detekt 指出过长方法/复杂条件，已拆分当前字段比较和当前候选校验，未放宽规则；两个新增辅助函数因 return 换行推断 Unit 的编译错误已修复。首个完整门禁在并发审查修正后主动终止，日志保留为 `host-all-before-concurrency-fix.log`，不作为最终验收；最终候选重新冻结并完整执行。以下保留中间失败和定位过程，不计为最终通过；当前完整主机与双渠道最终结果见本节末。真实公开 Client ID 网关仍待白名单，没有调用自配 Key 或订阅替代。

首批新保存 UI 的 7 项中 5 项通过，2 项在打开思考下拉后调用 Espresso.pressBack 时发生 RootViewWithoutFocusException；正常保存、失败重试、保存中防重与旧回执隔离均通过。失败位于 Espresso 的 root 选择，改为项目既有 Instrumentation/UiAutomation 注入真实设备事件，保留所有取消/已保存状态断言。`device-205054` 为不完整批次，不计最终通过；finally 清理后再次严格核对全部 Standard 表、选择、权限、禁用与关键偏好均与该批前置基线一致。原语言、字体/旋转和自有测试包已恢复/清理，未清空用户数据。

夹具复核另将重开会话的等待条件改为组合 screen/runControl，确认已恢复持久模型和完整运行设置后再断言，避免读取旧 screen 或关闭后的临时默认值；同时补充真实外侧点击关闭与重开保持保存值的检查。此处只修正验收同步和补充覆盖，不改变生产保存逻辑。

事件夹具首次尝试引用未配置的 UiDevice 类型，AndroidTest 编译拒绝；已改用项目现有 SDK 注入方式，未新增依赖、未跳过用例。失败编译日志与前一完整主机门禁分别保留，最终测试 APK 以新成功构建为准。

`device-211117` 的新保存 UI 7/7 通过，包含取消、真实返回/外侧点击、重建、重复保存与旧回执隔离；Room 类因一个表达式协程方法推断出非 void 返回，被 JUnit 在初始化时拒绝，5 项未执行。已统一为 `runBlocking<Unit>`，不删除/跳过断言；该诊断批次不计完整验收。清理后严格核对全部用户表、权限、禁用及偏好保持，恢复核查保留。

`device-211427` 的新 UI 再次 7/7；Room 5 项均失败，根因为隔离 ProviderService 没有调用生产启动所需的 refresh，rows 保持初始空列表，表现为 badge 等待超时和 PROVIDER_UNAVAILABLE。已补齐夹具启动刷新与精确候选就绪等待，生产校验不变；该批不计通过。清理后全部日常表、权限、禁用和偏好严格保持。

`device-211834` 新 UI 7/7、Room 4/5 通过；唯一失败为正向旅程等待超时。首先加强为 Compose waitUntil 等待并断言实际 APPLIED 回执；后续 device-212242 仍在重开时超时，确认根因为夹具将 RunControlConfig 与存储 SessionRunControlRecord 比较，非同类型永不相等。已把存储记录完整转换为运行配置后比较，并保留实际回执及后续请求断言、增加模拟响应 COMPLETED 断言；没有扩大超时。两批全部日常数据再次严格复核保持。

`device-212705` 新 UI 7/7、隔离 Room 5/5、真实横屏 1.8 倍字体 7/7 全部通过，正向实际回执/重开/请求 xhigh 与模拟响应 COMPLETED 均通过。R8 首次冷启动严格摘要断言失败，唯一差异为 android_metadata 的 locale 从 zh_CN 变为系统实际 zh_CN_#Hans；用户表、关键偏好、权限、安装与显式禁用完全保持，未直接改库。普通启动此前只等 am start -W 即结束，可能尚未以原语言打开数据库。运行器现在先自然启动原应用并等待实际页面，允许且单独记录仅该内部语言元数据的基线规范化；测试后恢复原语言时同样等待页面与原 locale 摘要，再做全表比较。保留原语言选择和系统配置，不用手写 SQLite locale 或清库掩盖差异；该批仍不记完整 R8 通过，最终两渠道另跑。

### 最新追加最终结果

最终候选以 `608d4345` 加本轮冻结源码/资源/构建输入成功构建，测试 APK 另以相同输入编译。安装前验证签名、Room 基线及无活动 Turn，安装后读回 APK 字节核对；输入映射在完整门禁、设备验收及提交前保持一致。本地提交固定这些输入，不将 APK 内的基线提交元数据描述为本次提交后的干净构建。

执行命令为 `./scripts/check-all.sh --all`、`:app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest`，以及本地保数据 runner `hxa307-catalog-closeout.py --picker`。完整必选门禁退出 0，含格式/Detekt/lint、JVM、Debug/Release/R8、依赖锁和制品边界。应用 JVM Standard **1315 通过/4 条件跳过**，Advanced **1440 通过/4 条件跳过**，失败/错误均为 0；新增 6 项 JVM 方法均执行通过，含 3 项草稿物化交错、2 项编辑状态和请求四档编码。Registry 89、框架 263、存储 242、Mobile Use 311、Provider API 119、Core Model 163、文件 130、Browser 45、QuickJS 103 的相关 JVM 结果均通过。未变模块可复用 Gradle 相同输入缓存；新增用例本轮实际执行。应用汇总器因既有外部输入条件跳过标为 INCOMPLETE，不能称为零跳过；必选门禁自身成功。

最终设备 runner 退出 0，结果目录 `build/hxa307-picker-reasoning/device-213658/`，对应 `device-final.log`、`host-all-final.log`、`android-test-final.log`、`final-jvm-complete/` 和 `candidate-source-inputs.json`。设备为日常 `Helix_API_36` / `emulator-5554` / API36 / ARM64。

| 渠道 | 竖屏 1080×2400 | 真实横屏 2400×1080 / 1.8 倍字体 | R8 冷启动/进程重启 | 最终失败/跳过 |
| --- | --- | --- | --- | --- |
| Standard | 40 | 16 | 2 | 0 / 0 |
| Advanced | 42 | 16 | 2 | 0 / 0 |

114 次方法执行包含跨渠道/方向重复，不是 114 个不同用例。新保存 UI 各方向 7 项覆盖取消、设备返回与外侧点击丢弃、重开已保存值、Saveable 状态恢复、目录移除、会话变化、失败重试、保存中防重和旧回执隔离。两渠道 Room 各 5 项使用独立 UUID 数据库、内存偏好与脚本 SSE，验证实际 APPLIED 回执、模型/强度共同持久化、重开、下一次请求 model 与 xhigh、模拟响应 COMPLETED、忙碌/过期/未知档位拒绝、其他设置保留，以及草稿延后物化。没有用会清理日常数据的旧 SessionModelDeviceTest 作为此批设备入口；其改动仅编译。

完整集合还复验动态目录/隐藏/已选保留、来源导航、Composer 同排、完全访问文字和普通插件版本；Advanced 额外执行原生版本/修订/实例/禁用/repair 两项。逐类校验实际 DisplayFrames 与截图方向；已查看 Standard 横屏 API/插件版本 1、Advanced 竖横屏订阅来源截图，来源同排且滚动内容无重叠。既有“Qwen API”与已配置 Key 状态属于保留的用户来源，不是内置网关验证结果。

原应用先自然启动，单独记录仅 android_metadata 的语言基线规范化；恢复原语言后等待实际页面与原 locale 摘要，再作全表比较。最终两渠道 changedTables 均为空，全部用户表、关键偏好、会话选模、权限范围、安装内容与显式禁用保持，原生 revision 不变；同源 R8 两次启动后仍保持全部表。没有直接改库、清零计数或恢复旧手动设置。自有测试 APK 已卸载，原语言/字体/旋转与中文输入法配置保留，两渠道留下同源 R8，Advanced 前台可用。

官方固定模板的 6 项 CPU 验证与上述本地请求编码均通过，但未运行 SGLang GPU 后端，也未调用项目网关推理、图片或真实 Agent 工具闭环。公开 Client ID 白名单和实际部署模型仓库/模板、参数透传仍 **pending**；客户端已知模板适配不能代替这些确认。没有使用既有自配 Key、订阅或付费来源替代，没有推送、发布或更改 v0.0.6 tag/附件。

## 真实内置服务与会话提醒移除

Owner 随后明确要求在日常模拟器实际测试内置服务；本轮据此发送真实合成请求，不再以白名单未获书面确认为由暂停。另按最新要求移除会话窗口中的内置服务传输提醒及三份语言资源；数据处理范围继续在 README 如实说明，不增加替代弹窗或未知的数据政策承诺，原有工具与敏感附件授权保持。

### 真实服务结果

目标为日常 `Helix_API_36` / `emulator-5554` / API36 / ARM64，先确认已安装 R8 与 `539b06b5` 上轮冻结输入对应的候选一致。全部请求只使用固定项目 HTTPS 网关及公开 `X-Client-Id: helix-android-preview-v1`；内置配置为 None credential、无 SecretAlias/自定义认证头，没有读取或使用既有 Qwen API Key，没有改动服务器白名单。通过 UI 新建专用合成会话，不发送原有用户会话历史。

| 检查 | 实际结果 |
| --- | --- |
| Standard / Advanced 显式刷新目录 | UI 均返回内置 Qwen3.8-27B；Standard 的新鲜 LISTED 缓存与无凭据配置已核对 |
| Standard 首条文字 | 2026-10-18 14:28:27–14:28:31 UTC 完成；准确返回 `HELIX_BUILTIN_LIVE_OK`，Turn/model call 均 COMPLETED，用量 8696 输入 / 127 输出 tokens |
| Standard 文件闭环 | 一次批准唯一合成文件的 write，随后 read；两个工具均 SUCCEEDED，最终模型请求清单包含两个工具结果，回答正确读回文本；独立读取实际工作目录文件，字节准确等于 `HELIX_BUILTIN_FILE_OK` |
| Standard 图片 | 实际附件选择器选用 256×256 合成红方块图，沿原附件流程一次批准；模型准确返回 `Red square.`，图片绑定、内容哈希与已完成结果保留 |
| Standard 思考三档 | 新建无附件合成会话，UI 分别保存低/中/高；冻结 runtime 为 LOW/MEDIUM/HIGH，对应已验证编码 low/medium/xhigh，三轮均 COMPLETED，准确返回 LOW_OK / MEDIUM_OK / XHIGH_OK |
| Advanced 文字一致性 | 显式选内置来源并保存，实际返回 `DEVELOPER_BUILTIN_OK`，Turn/model call 均 COMPLETED |

合计 **7 个真实 Turn、9 次模型调用**，均成功；包含文件轮的三次模型调用。此结果证明本轮时间、设备与公共标识下的文字/SSE、图片及有界文件 Agent 闭环可用，不能外推长期稳定性、容量、所有模型或其他设备。思考三档成功仅证明请求被接受并完成，不证明部署模板版本或各档语义效果；没有声明已验证思考预算/质量差异。

应用没有保存这轮请求的准确 HTTP 状态码，持久 requestId 均为空，不能补写成已捕获 HTTP 200 或服务端 request-id。成功证据来自真实回复、SSE 完成/用量、冻结目标、工具结果与独立文件核验。另一次主机 urllib 只读 `GET /models` 得到 **403**，没有捕获响应体/request-id，没有重试；它不是模拟器 UI 请求，拒绝原因未定位，不能覆盖日常 App 已成功的结果。

原始证据在忽略目录 `build/hxa307-builtin-live-2026-10-18/`：`catalog-ui-result.json`、`live-durable-result.json`、`synthetic-messages-and-manifests.json`、`file-verified.json`、`file-consumption-verified.json`、`reasoning-live-results.json` 和 `developer/`。仅保存合成会话内容；已有数据库行仅保留摘要。首次 ADB 键盘输入被拼音改写，精确校验在发送前拦截；改用独立 SDK UiAutomation 的 ACTION_SET_TEXT，未修改中文输入法。图片输入夹具首次遇到 root 短暂为空、发送前失败，补有界等待后通过；没有重复发送那条失败输入。

### 提醒移除的候选与验收

实现仅删除 `ChatScreen` 空白内置会话的传输说明，以及 base/en/zh-rCN 对应字符串。增加独立公开 UI 回归 `OwnerBuiltinLiveUiTest`，要求明确的 owner opt-in、日常包白名单与真正空白新草稿；输入仅允许合成标记，不自动发送，不改 IME、不访问 App 私有状态。旧 Advanced R8 的提醒断言如预期失败，确认不是在已有消息的会话上假通过。

本轮重新运行 `./scripts/check-all.sh --all`，退出 **0**；格式/Detekt/lint、主机测试、双渠道 Debug/Release/R8、测试 APK、依赖及制品边界均通过。应用 JVM Standard **1315 通过/4 条件跳过**、Advanced **1440 通过/4 条件跳过**，零失败/错误；这些是原有外部输入条件，不宣称零跳过。随后以最终夹具补跑 `spotlessCheck :tests:release-smoke:assembleDebugAndroidTest :tests:release-smoke:lintDebug` 成功。未变测试允许同输入缓存复用；真实网络结果不由主机夹具代替。

成功门禁的双渠道 ARM64 R8 以原开发签名保数据更新到上述日常设备；安装前确认同签名、Room 基线、无活动 Turn，安装后读回 APK；`candidate/provenance.json` 记录 `539b06b5` 加本轮未提交输入与实际 APK。不是本轮提交后的干净构建。生产改动只有上述 UI/资源删除，传输及模型执行输入与真实服务验收时相同，故复用其真实服务证据，不额外发送公网请求。

最终 Standard 与 Advanced 各 **2/2** 真实空白会话方法执行通过，覆盖 1080×2400 竖屏与 2400×1080 横屏／1.8 倍字体，零失败/跳过；已查看截图，传输提醒消失，语音、正文、添加、发送同排。随后两渠道各一次 R8 进程冷启动通过，全部数据库表及原生 revision 与更新前相同。`updated-device-result.json` 与各 `device-*` 子目录保存安装读回、JUnit、实际 DisplayFrames 和截图。

精确恢复测试前两个会话入口/草稿模型偏好，其他偏好保持；逐表逐行摘要证明全部原数据库行未修改或删除，仅新增合成测试事实。原用户模型绑定、授权、禁用、中文输入法、字体与旋转保持，`final-preservation.json` 和 `restoration.log` 保存核验。留下两渠道新 R8 及合成会话/工作文件，卸载自有输入测试 APK，删除临时选择器图片副本；没有清空用户数据。真机、其他模型、负载/长稳、服务端模板身份与思考语义效果未测；它们不再作为本轮内置服务基本可用的前置阻塞。本轮仅本地提交，不推送、发布或修改 v0.0.6 tag/附件。
