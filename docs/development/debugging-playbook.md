# 测试与调试维护手册

## 门禁验证什么

用例数量、文件数量、代码行数及历史报告总数是观测值，不作为固定通过条件。新增或合并用例不应要求手动改一个“总共N项”的数字。统一主机发现入口为 `python3 scripts/run-host-tests.py`；新增 `scripts/tests/test_*.py` 会自动进入 source 门禁。空套件、导入失败、执行失败及全跳过仍失败。

Android 原始状态使用已有 `instrumentation_junit.py`：校验每个方法的开始、结束和身份、runner 正常结束，拒绝重复、缺失、不完整、失败及跳过。存在 runner 声明总数时，和本次实际方法数动态比对。固定评测清单仍检查具体 case/method 身份，不用另一个硬编码总数代替覆盖。

副作用次数（例如停止后不能多执行一次）、身份唯一性、输入/归档大小上限、256自动轮边界、FD/线程/PSS资源阈值及安全/权限/恢复断言属于行为契约，继续保留。资源循环逐类要求实际非空、零失败、零跳过，输出 `HelixSoakCases` 实际完成数量；host只汇总这些完成事件。缺少新事件的旧日志不估算为固定8项，原历史报告保持当时事实。

## 主机复核模板

```sh
python3 scripts/run-host-tests.py
scripts/check-all.sh --source
# AndroidTest或构建入口改变时，包含可选实验模块的完整Debug验证：
ORG_GRADLE_PROJECT_includeSpikes=true python3 scripts/with-host-slot.py -- \
  ./gradlew test detekt spotlessCheck lintConsumerDebug lintDeveloperDebug \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest
```

主机Mock、语法检查和APK编译不证明真机、模拟器或模型任务成功。注入的失败日志属于回归输入，需和实际设备失败分开报告。

## 有授权的设备测试模板

每个当前任务先明确设备与模型授权，选择未运行的AVD、自有serial和新输出目录；不借日常实例，不清数据，不用快照恢复冒充当前数据库。设备授权不含真实账号或模型额度授权。

```sh
python3 scripts/with-host-slot.py -- python3 scripts/run-owned-emulator.py \
  --avd YOUR_INACTIVE_AVD --port YOUR_FREE_EVEN_PORT --memory-mb 4096 --cores 2 \
  --raw-results \
  --apk app/build/outputs/apk/developer/debug/app-developer-debug.apk \
  --test-apk app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk \
  --runner com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner \
  --classes YOUR_CURRENT_CLASSES --output build/debug/YOUR_FRESH_ATTEMPT
```

runner负责冻结APK及SHA、自有进程检查、键盘默认值、连续TestRunner/System.out日志和finally关闭。中文输入应执行实际拼音预编辑/候选/中文commit验证。允许真实模型时才加 `--reverse-port YOUR_PORT` 和明确的 `--instrument-arg KEY=VALUE`，只用本轮指定服务和合成会话。

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

## 何时删除或晋升脚本

### 中文输入和视觉补验

Root/Shizuku依赖的UiAutomation与测试自己持有的UiAutomation可能竞争同一个系统服务。高权限后端测试使用外部ADB完成已授权的临时设置，在自有MainActivity可见之后启用服务，退出恢复原值；避免一边保留测试UiAutomation、一边声称验证了另一Root连接。授予App Root后仍须明确connect。合成目标的视觉ALL_CAPS与实际节点文字应保持一致，精确选择器按实际文字/ID验证，不能因测试不匹配而放宽生产匹配规则。单独的shell UID0不证明App RootService或任务可执行。[真机对照与失败记录](../evidence/development/physical-followup-2026-10-12.md#批准后的升级后端与模型补验)保留当前案例。

语言已勾选、键盘可见或UTF-8注入成功不能证明拼音可用。运行实际预编辑→中文候选→点击提交夹具，并在Helix未运行的系统输入框对照原生崩溃。Gboard公开词包缺失/0字节与解码器故障应保留大小、哈希、版本及健康对照；root读取须单独授权，只检查公共资源，不读个人词库、账号或凭据。不要清日常数据；可在新空白自有AVD等下载完成、普通重启输入法后复验，再冷启动确认。原故障证据与替代环境通过分别记录。

真实视觉用运行时像素决定且提示/初始DOM未披露答案的合成目标，独立核验操作结果。fake Provider编码、请求manifest输入ID、准备图片标志都不能证明模型收到图片；实际请求图片字节的哈希应与持久附件对应。截图前后相同仍可能有DOM状态变化，要明确效果核验来源。后台返回还须检查实际会话可见内容及截图；非单色首次使用须知并非成功返回会话。当前案例及失败尝试见[2026-10-12补验](../evidence/development/device-vision-stability-followup-2026-10-12.md)。

一次性源码/文档改写器完成后，以当前源码、稳定回归和Git历史承接，不继续当迁移入口。重复launcher/解析器合并到现有稳定入口并更新调用方；只有Mock/Pure host测试进入自动发现，设备/真实服务测试仍显式opt-in。依赖锁、vendor输入、许可证、唯一协议或未关闭问题的诊断不能按日期删除。

删除前逐路径核对实际用途、所有当前调用者及替代；tracked源码记录Git来源，未提交或本地唯一脚本先保存并校验恢复副本。原始失败日志、数据库及模型轨迹和脚本分开留存，本地tar不是长期备份。历史复现以删除清单中的Git提交和path/hash恢复，禁止直接在当前工作树重跑旧改写器。

本次具体删除、迁移与验证见[HXA-267记录](../completion-records/HXA-267.md)及[清理证据](../evidence/development/test-script-maintenance-2026-10-10.md)。

历史版本日志不必永久另存副本：经Owner明确授权，核对其归属及当前复现入口后，按“来源提交/版本与APK身份 → 当时成功和初轮失败机制 → 修正与验证边界 → 现行复现入口 → 删除路径/hash”汇总。删除后在原报告注明原始日志不可用；未经执行不填当前通过，不用hash冒充备份。示例见[历史版本整理](../evidence/development/historical-version-retirement-2026-10-10.md)。

## 插件市场与模型夹具生命周期

订阅 Runtime 生产入口是 Developer 主 APK 内的 `:subscriptions` 私有进程，不再安装 `runtime/cli-app` 独立 APK。库 AndroidTest 宿主只用于组件自测试，其包、沙箱及登录记录与 Helix 产品不同；真实账号/模型验收使用 App 中的 Provider 与原生登录配置，不从宿主 CLI 导入凭据。测试前核对当前 module 类型、最终 manifest、APK hash 和 instrumentation target，而非根据残留文件名猜测架构。旧独立产物与脚本的删除清单见[退役记录](../evidence/development/runtime-artifact-retirement-2026-10-13.md)。

市场列表从IO异步加载，Compose空闲不代表条目已经就绪；在操作前对真实条目做有界等待。本地化测试覆盖LocalContext时，要保留真实ActivityResultRegistryOwner，否则文件选择器不能注册。安装确认和详情展开是独立交互，不能在未展开时断言会话按钮存在。

模型夹具必须区分插件安装、Skill内容启用、会话选择、MCP连接和执行审批；安装或选择不会自动完成所有前置条件。按渠道选择夹具：Developer可临时使用精确LAN授权的loopback MCP，Standard保持既有拒绝边界并使用独立内容/文件工作流，不借用Developer专属chat-reply。

真实模型必须分别记录业务结果与JUnit/清理结果：Turn.COMPLETED不能证明按钮已点击，token失效也不表示成功。读取持久assistant后有界等待UI投影，避免把用户提示误当模型回复。反复NEW_TASK启动以及对已被另一路由替换的ActivityScenario执行close会制造堆积/清理失败；实际Main UI dispatcher与SINGLE_TOP/CLEAR_TOP复用现有宿主，不吞掉close异常。

Instrumentation force-stop可让enabled list残留而系统标记Helix无障碍服务crashed；重复写同一列表不会重连。授权的夹具只重新加入自己的服务，等待系统确认移除再启用，保存/恢复其他服务与原设置。资源idle断言先记录当前数值及新增FD，仍用原阈值；诊断GC不算接受依据。完整长测保留所有业务失败并在结束统一断言，不能删掉失败或靠重新试跑覆盖历史。当前案例见[HXA-282证据](../evidence/development/external-mcp-market-and-model-2026-10-13.md)。

资源基线与后续样本必须处于相同生命周期/渲染状态。硬件加速窗口可用实际帧提交确认及固定静置对齐，不能依据 FD 下降动态等待、过滤描述符或上调阈值。主机终末采样无 PID 时，核对方法终态时间、最后成功采样间隔和原始 meminfo，区分退出后的空采样与测量中的失联；保留失败条目，不将 null 当零。当前两小时资源满足阈值但业务失败的整轮仍为 FAIL_FUNCTIONAL。

参数错误与业务停滞分别回归。宿主已绑定会话时，应在工具描述中明确模型省略可选身份字段，保留空值拒绝与跨会话隔离测试。对屏幕外目标，除普通表单外，先要求模型实际查找以获得 offscreen 结果，再检查模型自行继续滚动及独立业务结果；夹具不能代做动作。固定次数、保留所有失败，不以重试到通过或正常 Turn 终态替代任务核验。

stopTurn只接受停止请求；删除测试会话前应等待AgentRuntime.observe结束、核对持久终态，避免后台结算仍引用已删除Turn。清理只处理本轮明确拥有的ID；进程崩溃留下的合成Provider按基线差集核对后，走原生产删除接口，不直接删除用户配置或吞掉异常。具体复现与失败证据见[HXA-281设备补验](../evidence/development/plugin-marketplace-device-model-2026-10-13.md)。
