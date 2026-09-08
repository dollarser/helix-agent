# 浏览器与 Autofill 长稳执行方案

日期：2026-09-09。状态：**方案已设计，新增长稳夹具尚未实现，测试尚未启动**。

全部模拟器任务的范围、前置和交接见[总计划](emulator-verification-master-plan.md)。本文件是EV-02子计划；主App接线连续任务与旧应用资源24小时分别归EV-04/03，不能由模块测试APK替代。

本方案供小模型分步实现测试夹具并执行，目标是判断真实 Activity/WebView 导航和 Autofill 工作量是否造成持续资源累积。它不是全 App、真实模型 API 或真机长稳验收。依据：[当前状态](status.md)、[ADR-0033](../adr/0033-activity-owned-browser-views.md)、[HXA-160](../completion-records/HXA-160.md)、[JNI/Binder 释放证据](native-reference-release-trace.md)。历史测试结果不得当作新一轮结果。

## 1. 目标与执行边界

- API29、API36 各完成一次单目标进程、单次 instrumentation 的连续 24 小时混合浏览器工作量；每轮之前另有 30 分钟预热，之后另有 30 分钟自然回收观察。每台正式占用约 25 小时，不拼接轮次。
- 真实系统 AutofillService 填写和保存；服务使用测试账号和合成表单，不使用个人密码管理器或公网账号。
- 观测应用 local/proxy Binder、系统对目标 UID 的 Binder 代理、FD、线程、PSS，以及操作成功数、延迟和进程身份。
- JNI 句柄配对另用现有有界诊断 agent；正式长稳不加载 agent，不注入 GC，不重启应用“清资源”。
- 仅允许测试源码、诊断/执行脚本和证据文档的必要修改。禁止顺手改生产架构、依赖、权限、Goal 语义或 Autofill 产品功能。发现生产 bug 先形成最小复现和独立修复检查点。
- 本次用户请求只设计方案；本文件不启动定时任务、Goal 或设备运行。后续用户把执行 Prompt 交给小模型后，才进入实施/执行。

## 2. 现有入口与必须补齐的缺口

| 入口 | 目前实际覆盖 | 使用方式与限制 |
| --- | --- | --- |
| `scripts/run-hxa103-browser-soak.py --workload browser` | `WebViewResourceLifecycleDeviceTest#continuousResourceSoak`，Application Context 的宿主资源夹具 | 可保留为旧资源回归；不是 Activity owner/Autofill 长稳，不据此关闭本方案 |
| 同脚本 `--workload app` | Room、FGS、资源门控七项夹具循环 | 全 App 资源另线验收；不等于浏览器、Provider、MCP 或真实模型覆盖 |
| `BrowserAutofillDeviceTest#systemServiceFillsAndSavesTheActivityWebForm` | 真实填写、保存、Activity 重建与撤销服务 | 可复用表单/服务机制；不能从 shell 循环启动它冒充同进程长稳，也不能每轮重新配置 Autofill 服务 |
| `BrowserActivityLifecycleDeviceTest` | 实际 MainActivity 的浏览器重建接线 | 作为新夹具接线前后回归，不替代长稳 |
| `scripts/diagnostics/run-jni-reference-trace.py` | 目标 JNI 引用配对，计数上限 2000 | 每次会安装 APK、force-stop；只能在独立诊断阶段运行，不能与正式轮次同设备交叉执行；同名输出会复用，运行前必须归档旧目录 |

旧长稳 runner 缺少固定安装 APK 身份、持续 PID/starttime 校验、系统 UID 代理结构化采样和细分状态；单次 ADB 失败会走最终 force-stop。**不可不加审查地把它直接包装成新的验收入口。** 本方案中的新配置字段和命令契约是待实现要求，不是现有 CLI 参数。

进入实施时，先按 roadmap 当前状态登记一个独立测试检查点，不重开 HXA-160，不在本设计中预占新 HXA 编号。只让一个检查点处于 In progress；先完成夹具，再冻结产物执行。

## 3. 阶段与时长

| 阶段 | 每台设备的工作 | 进入下一阶段的条件 |
| --- | --- | --- |
| P0 身份与环境 | 核实专用 AVD、安装快照、服务配置、采样权限、剩余磁盘 | 清单齐全；同设备无其他任务 |
| P1 夹具试跑 | 正常 15 分钟；另开独立轮次验证 watchdog/失败状态 | 实际填充和保存证据齐全；人为故障必定非 PASS |
| P2 配对预检 | 服务关闭 2 小时、服务开启 2 小时；每组另加预热/末尾观察 | 同固定工作量、版本和设备配置；无功能失败，无持续增长告警 |
| P3 正式长稳 | 服务开启；30 分钟预热 + 24 小时 + 30 分钟观察 | 所有硬门禁满足，资源趋势无未决告警 |
| P4 JNI 独立复核 | 两 API 各三条生产路径 ×100 轮；裸平台正对照100轮 | 生产路径目标引用配对归零；原始/正对照独立报告 |
| P5 交付 | 汇总每台每轮与遗留风险 | 不能把单 API PASS、部分指标 PASS 写成总体 PASS |

API29/36 可同时运行，但每台串行，各自独立输出。若主机资源不足则顺序执行，不降低设备配置来勉强并跑。正式前 P2 共需每台约 6 小时（含各自预热/观察）；双设备并行整套至少约 32 小时，加夹具实现、构建与取证时间。失败立即结束该轮，不能为了凑时长继续反复重试。

P2 两组开始前分别从相同的干净 AVD 基线启动，完成相同预热；组间允许重启专用 AVD并记录。正式同一轮内不允许快照恢复、重启、清数据、安装更新或更换 WebView。发生服务启用组特有增长时，另做反向顺序配对排除顺序影响，不覆盖首轮结果。

## 4. 固定工作量

一个小时块：**50 分钟活动 +10 分钟自然空闲**。活动阶段每120秒开始一轮，共25轮；24小时共600轮。按设备单调时钟排程，迟到不并发追赶、不漏轮，单轮最长90秒；超过视为超时失败。剩余时间等待下一计划槽位，不能无限加速。预热采用相同路径但另计数，不计入600轮。

每轮使用实际 BrowserController、Activity owner 和 WebView，至少做：

1. 使用唯一 `runId/cycleId` 打开本地测试 HTTP 页面，等待页面标志、实际请求和 DOM 状态一致。
2. 读一次 snapshot，执行一次非敏感按钮动作并核对页面结果；临时开第二标签，切换回来再关闭，核对旧回调不能影响当前页。
3. 加载合成登录表单。服务开启组选择真实系统 dataset，等待 DOM 填入；通过实际输入路径编辑用户名，提交一次，点击真实系统保存按钮，等待 `onSaveRequest` 收到对应本轮值。
4. 服务关闭组在相同页面通过实际输入路径填入相同合成值、提交一次；核对成功页与唯一提交，没有系统填充/保存回调。配置仅在组开始/结束时改变，不能高频调用查询 API 来验证“关闭”。
5. 每5轮增加一次30秒后台/前台切换，在已终结表单后进行，核对前后台复用同一 View；合计120次。
6. 每10轮再进行 Activity recreation（不是杀进程），在上轮副作用已经确认后执行。核对只保留逻辑 URL/标题，DOM token 失效；未经显式导航无新增请求；合计60次。
7. 关闭本轮所有临时标签和页面，核对 host/pending evaluation/dialog/服务未决请求归零，再进入等待。收集指标不能为了读取 View 而创建一个新 WebView。

正式600轮须各有导航、动作、唯一提交成功证据；开启组另须600次真实填写与600次真实保存。系统额外重试的 fill 请求只记录，不假设 callback 总数必定600；按 cycleId 区分预期请求、迟到回调、重复提交和重复保存。每个轮次将步骤与耗时写入结构化日志，不以“测试方法返回”替代功能断言。

每个空闲段保留 Activity/目标进程，关闭临时页面，停止测试工作量但保持低频观察；不强制 GC，不执行 Autofill 查询探测。30分钟末尾观察也相同。它验证自然回收，不属于增加有效操作数的机会。

本地服务由夹具持有，或固定宿主服务持有；必须有请求计数和健康状态、确定性响应、超时及退出清理。只记录合成字段，响应数据有界，不引入真实模型或公网不确定性。

## 5. 新夹具与 runner 最小契约

建议新增独立 `BrowserAutofillSoakDeviceTest` 与 `scripts/run-browser-autofill-soak.py`（**尚不存在，不可直接调用**），提取现有测试 helper 而不复制整套业务代码。夹具使用一次 instrumentation 内部循环；Activity 可重建，但 controller 与目标进程不能每轮重建。服务一组只设置一次，在 finally 恢复；进程崩溃时由宿主取证后恢复原配置并记录，不遗漏清理。

配置最少包含 `runId / serial / phase / durationSeconds / warmupSeconds / cooldownSeconds / autofillMode / cycleSeconds / activeSecondsPerHour / idleSecondsPerHour / apkSha256 / configSha256`。正式固定86400/1800/1800秒，开启服务，周期120秒，活动3000秒、空闲600秒。试跑配置必须单列，不允许15分钟产物标记24h。

P1正常pilot允许缩短预热/观察各5分钟，活动15分钟；须完成7个完整120秒槽位并核对填写/保存，在独立短例覆盖后台/重建与服务恢复。P2每组固定2个完整小时块，共50轮；P3固定24块600轮。阶段差异写入配置，不允许在运行中动态减少工作量。

runner 必须做到：

- 输出目录必须不存在，拒绝覆盖；先复制 APK 到该轮只读快照并计算 hash，再安装同一份字节，核实设备安装路径对应包身份/版本/hash。把构建提交、工作区补丁hash及未跟踪测试文件清单一起记录；测试期间禁止修改相关源码/产物。
- 记录 serial、AVD名、API、build fingerprint、WebView提供者/版本、ABI、RAM、目标包/UID、instrumentation组件、系统及应用PID与starttime、boot_id、测试服务组件和配置原值。不存在唯一匹配设备/包时停止，禁止猜测历史 serial。
- 主机/设备各用单调时钟核实持续时间；启动前验证宿主防休眠进程在运行、模拟器不暂停。不要通过主机墙钟时间差推断设备有效运行24小时。
- 目标PID、starttime、boot_id每30秒核实；每轮结束心跳，活动期超过180秒无轮次进展即失败。空闲期仍每30秒发低开销状态心跳，不能被进展 watchdog误杀。
- 每分钟采集目标进程 FD、线程、local/proxy Binder、PSS；优先使用已验证 `dumpsys meminfo --local`，同两API试跑核实输出字段。服务若在独立进程，另记其资源/PID；renderer单独记录，不能把主进程PSS当总浏览器内存。
- 每5分钟记录 system_server 对目标UID的代理计数及可用类型统计；优先设备支持的受控诊断接口，P0核实读取权限。没有该权限就标记该指标 unavailable，不能伪造0或改用应用proxy数代替。相关状态只能部分验收，不能关闭系统Binder问题；不因此自动root真机。
- logcat覆盖目标进程、系统崩溃/ANR/LMK/Binder警告，持续落盘并轮转。样本记录成功/失败、采样耗时及时间间隔；一次ADB读取可在10秒后仅重试读取一次，不能重试工作量。连续丢失或间隔超过180秒终止为INFRA_INTERRUPTED。
- 配置磁盘限额和空间低水位：启动空闲至少20GiB，试跑估计25小时日志量×2必须小于可用空间；轮转不删除本轮证据，低于5GiB安全结束为INFRA_INTERRUPTED。必要时开新容量足够的输出位置，不静默丢日志。
- 启动后的测试进程不能由外部shell循环重拉；UI/ADB连接断开不自动重新安装/force-stop/retry整轮。先保全日志、进程状态与退出原因，再按停止契约处理自己创建的测试进程。

P1独立故障轮次至少覆盖：夹具断言失败、工作量卡住、目标PID改变、采样缺失、宿主runner中断。验证结果不会保留PASS、不会重复表单提交、不会清除原失败日志。监控可用录制输入做故障测试；不必真的填满磁盘或重启共享设备。

P1还需做采样扰动对照：同固定25轮工作量，分别只记录夹具心跳、开启完整采样；两组都保留起终资源快照。若完整采样组新增持续Binder/FD增长或轮次耗时中位数增加超过10%，先降低采样频率/排查采样路径，再重新冻结配置。此10%是测试工程筛查值。采集系统UID代理时不得额外轮询Autofill能力；指标查询本身会产生IPC，须记录次数。两组测试不是24h，也不能依靠关闭监控掩盖无法观测的增长。

末尾恢复系统设置必须在cooldown数据采集结束之后，恢复前后分别留样；设置变化引入的Binder不能算作正式轮次自然回收。runner用临时文件+原子替换提交progress/result，信号退出写CANCELLED；runner若硬崩无法写结果，下次接管标INFRA_INTERRUPTED，不能把残存RUNNING推断成成功。主机单调时钟、防休眠和设备elapsedRealtime三者一起核实；跨时钟偏差超出预先配置容差时不计连续时长。

## 6. 判定：稳定运行与资源平台期分开

结果状态封闭为：`PASS / FAIL_FUNCTIONAL / FAIL_RESOURCE / INFRA_INTERRUPTED / INCONCLUSIVE / CANCELLED`。历史runner的FAIL仍保留原值，必要时额外加归因字段，不覆盖。没有系统代理采样时可报告功能/应用指标通过，但完整Binder评估为INCONCLUSIVE。

硬失败：目标/系统进程非计划退出、JNI表溢出、Binder阈值终止、目标ANR、非计划renderer崩溃、断言失败、重复提交、错轮保存、超时或静默跳过。立即停止工作量，取证；不以“自动恢复后最终成功”覆盖失败。基础设施中断与产品失败分开；不确定归因标INCONCLUSIVE，不凭ADB离线直接认定产品崩溃。

资源判定使用下面的**本项目初始筛查门限，不是Android官方标准**。P2之前写入配置并hash冻结；发现过紧可另行分析、新建方案版本重跑，不能事后放宽把旧失败改成PASS。既有测试本身的断言原样保留。

- 每小时10分钟空闲段最后5分钟，按指标取中位数 `R_h`。先前30分钟预热最后5分钟为 `B`。采样缺失不插值成0；最后5分钟不足4个有效分钟样本，则该小时资源判定不完整。
- FD持续高于 `B+8`、线程高于 `B+16`、PSS高于 `B+96MiB`，若连续两个小时空闲中位数越界，判FAIL_RESOURCE。这些沿用现有资源测试漂移量作为起始筛查，但此处明确采用空闲中位数，不能冒称与旧峰值断言等价。
- 对每项资源计算前3个小时空闲中位数的中位数 `E`、最后3小时的中位数 `L`；增长阈值：FD8、线程16、PSS96MiB；应用local/proxy与系统目标UID代理各为 `max(100, E×25%)`。`L-E`超阈值则至少INCONCLUSIVE，不能PASS。Binder的100/25%只是筛查阈值，不能充当系统安全配额。
- 对24个 `R_h` 拟合每小时斜率，同时报告逐小时值和按完成轮次归一化的斜率。趋势条件：斜率>0、`斜率×23`超过上项阈值，且后三个6小时分块中位数逐块高于前块；判FAIL_RESOURCE。只见一个峰或锯齿回落不直接叫泄漏；符合累积门限也只称资源稳定性失败，根因仍需定位。
- 操作耗时报告每小时p50/p95；最后3小时p95连续超过 `max(基线p95×2, 基线p95+2秒)`，标INCONCLUSIVE并分析；任何实际超过90秒的轮次按功能超时失败，不被统计均值掩盖。
- 无崩溃但只有24小时墙钟、缺操作数或系统资源数据，不能整体PASS。即使全部门禁满足，也只能称该版本/设备/固定负载24小时通过，不能声称不存在更慢泄漏或所有OEM兼容。

耗时基线是预热所有完整轮次中各同名步骤的p95；后台等待30秒、规定空闲时段不混入业务耗时。业务按case类别分别统计，不能把重建/普通导航混合比较。P2只有两个小时，不套用24点/前后三小时公式；只检查功能、两个空闲窗口相对预热B的绝对漂移及新增Binder差值，触发任一筛查门限即INCONCLUSIVE并停止进入P3。P1/P2的通过只是夹具/预检通过。

24h额外报告各指标累计最小值曲线及按操作数归一化的增长，帮助识别GC锯齿；模型不能单凭统计斜率称“源码泄漏已定因”。本方案600轮是固定混合使用量，不是极限创建压力；历史每小时大量裸创建故障仍由独立正对照覆盖。可将末尾同PID自然观察延长至60分钟作诊断，但原30分钟门禁结果不得被延长后的回落覆盖。

## 7. JNI有界证据与故障取证

P4使用现有工具，先归档同名目录；下列是现有真实CLI形状，SERIAL必须替换为专用模拟器实际序号：

```bash
bash scripts/diagnostics/build-jni-reference-trace.sh
python3 scripts/diagnostics/run-jni-reference-trace.py SERIAL --count 100 --scenario settled-close
python3 scripts/diagnostics/run-jni-reference-trace.py SERIAL --count 100 --scenario network-recreate
python3 scripts/diagnostics/run-jni-reference-trace.py SERIAL --count 100 --scenario network-background
python3 scripts/diagnostics/run-jni-reference-trace.py SERIAL --count 100
```

按脚本要求预先设置SDK/NDK/JDK路径并构建浏览器测试APK，不能直接复制历史机器环境。后三条生产/网络路径须有实际请求到达及唯一轮次计数；正对照是最后一条裸平台命令，不是PASS门禁。观察器报告须无溢出、无unmatchedDeletes，目标类配对归零才能声称已测生产JNI释放通过；其他类残留另报。未复现历史正对照时先检查版本、观察器与工作量，不能预设它必须失败或把未复现直接写成修复。

任一正式失败时，记录最后成功cycle、当前step、单调时间、PID/starttime、组件/包身份；保存logcat、instrumentation、meminfo、系统UID代理样本、服务端请求和原始result。先取证再清理；有权限才收集tombstone/ANR，无权限明确列缺项。取证最多5分钟、有单命令超时；不得为取证无期限阻塞。原PID已消失时保留事实，不拉起新进程冒充现场。

强制GC、SIGUSR1、高频Autofill查询、JNI hooks仅允许另开明确标记诊断轮次；本方案不要求它们进入P3，也不以它们成功回收充当长稳通过。

## 8. 交付与给小模型的执行Prompt

每个run单独保存：`manifest.json`（身份/配置）、`progress.json`（心跳/步骤）、`samples.jsonl`、`cycles.jsonl`、`requests.jsonl`、轮转原始日志、`result.json`、`summary.md`、安装APK快照及hash。机器文件只在忽略的build报告目录，仓库文档放汇总和相对路径，不提交APK、日志中的凭据或机器绝对路径。

最终表逐行列出API/WebView/APK、阶段、有效时长、轮数、填写/保存数、后台/重建数、主进程是否连续、失败/缺样数、空闲资源起终值与斜率、JNI独立结果。保留所有失败attempt，不拼接、不只展示最后一次成功。测试结果落到本次独立检查点的完成记录；计划状态不能提前改成完成。

可把下面整段交给小模型：

```text
请按 docs/development/browser-autofill-soak-plan.md 实施并执行 Helix 浏览器/Autofill 长稳测试。
先读 AGENTS.md、README.md、status.md、roadmap.md 和 ADR-0033，核实当前源码/工作区/设备。
本任务不修产品、不换内核、不做真机、不调用付费模型；先登记一个独立测试检查点。
先完成第2/5节所列缺失夹具和runner，保留旧测试，完成局部门禁和P1故障检测后冻结APK/config。
依次执行P0～P5；每台同一轮保持一个目标进程与一次instrumentation，禁止循环重启拼时长。
遇到正式轮次首次失败，先按第7节取证并结束该轮，报告最小复现和归因，不自动改阈值或产品。
每阶段更新机器可读状态和简短交接；上下文恢复先核实runId/PID/心跳，已有进程仍运行就接管监控，不能重新启动。
等待由外部runner承担，不让模型每秒轮询日志。仅在阶段结束、失败、状态变化时报告；未变化保持安静。
完整执行不依赖模型一直在线：runner持续落盘；若模型恢复时结果已结束，核验原始证据再写总结。
不要仅因为脚本退出0就报PASS。按第6节判定，缺系统Binder数据标INCONCLUSIVE，模拟器不冒充真机。
交付所有attempt的结果表、检查点完成记录和剩余限制；不要将其他模块或全App长稳写成通过。
```

建议执行模型先交付夹具和15分钟pilot证据供独立审阅，再进入正式24小时；这是减少长时间无效占用的工程建议，不是本文件额外设定的权限门禁。用户已经授权整套执行时，普通夹具修正与阶段推进无需重复确认。
