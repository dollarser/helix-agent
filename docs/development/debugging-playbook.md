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
