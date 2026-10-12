# 长稳门禁复核与同机复测

日期：2026-10-12。Owner要求先评估门禁再复测OnePlus/API34两小时资源失败；沿用HXA-271阶段11，不操作日常模拟器、账号或Qwen，不重建数据库。[原失败](mobile-backend-closeout-2026-10-12.md)及重启影响保留。

**当前状态：设备两小时应用资源precheck passed，原主机自动报告生成 failed；报告器已修复并用原证据离线复核通过。** 同一进程完成340轮、设备测量7,220,700毫秒，原资源门禁通过；不伪造缺失的原result。24小时、其他OEM和连续Mobile Use/Qwen仍未由此验收。详细数值与归因见[完成后的离线核对](#完成后的离线核对)。

## 测试前评估

| 检查 | 本轮判断与处理 |
| --- | --- |
| 真实业务断言、非空/无skip | 保留：实际Room/FGS/前后台门控，不用套件固定总数代替结果 |
| 制品身份、单一进程 | 保留，并拒绝host/fixture PID不一致；固定实际安装hash和工作树身份 |
| 完整时长 | 保留；补设备实际elapsedMs，主机等待/预热不能补足短设备运行 |
| 实际方法终结 | 加强：复用统一instrumentation解析器，要求指定方法开始/成功结束、无skip，不凭OK摘要通过 |
| FD+8、线程+16 | 保留为当前重复创建/关闭工作负载的增长报警，不是通用Android安全上限；结合描述符/线程类型和趋势归因 |
| PSS峰值+96 MiB | 原压力预算保留作同条件对照，但不能单独认定泄漏；单轮预热、GC/页回收、Graphics/ART缓存和采样阶段均影响差值。未校准不直接调大 |
| 30秒采样/90秒缺口 | 保留连续取证；缺口记INFRA_INTERRUPTED，不能推断产品失败，采集失败不写零值 |
| duration+180秒watchdog | 保留最后一轮/收尾余量及有界终止，不代替24小时验收 |

[Android内存管理](https://developer.android.com/topic/performance/memory-overview)说明PSS含共享比例分摊的物理页，GC时机不等于Activity关闭立即回收。[dumpsys](https://developer.android.com/tools/dumpsys)提供Java/Native/Graphics及Activity等对象计数；[Perfetto排查](https://perfetto.dev/docs/case-studies/memory)结合时间序列与堆/原生分配证据定位保留对象。读取日期2026-10-12，仅采用诊断原则，不复制代码或引入依赖。

上次约102.3 MiB峰值是原预算超限的真实失败，尚不能证明持续泄漏，也不能因可能GC波动就抹去失败。本轮原APK、单轮预热、原阈值、默认fail-fast、无observe-breaches和无额外显式GC保持；保存现有每次meminfo分类原文，不增加手机profiling扰动。若稳定复现，再独立区分生产保留对象、平台缓存或夹具分配，按证据修订同一契约，不追改原通过状态。

## 管理器修正及验证

显式`--detach`闭合stdin、输出到ignored日志、使用独立session，worker父进程为系统init；临时caffeinate仅跟随worker。仍必须当轮明确授权设备，Mac关机、休眠或USB断开可能中断。

补设备时长/方法完成/PID一致性判定及完整meminfo路径。新增host回归覆盖缺少/短时长、仅摘要或缺终结、PID不符、独立session/闭合终端、预检只读、内存分类保存和旧输出拒绝。原资源门禁未删或放宽。source统一217项及文档/ADR/i18n/secret检查通过，runner单元13项通过；Android源码/安装APK未改，历史构建及模型结果不重记为本轮执行。

命令：`python3 scripts/run-hxa103-browser-soak.py <OWNED_PHYSICAL_SERIAL> --physical-device --workload app --flavor developer --preset precheck --detach --output <FRESH_IGNORED_OUTPUT>`。这是存储/FGS/Activity资源工作负载，不是两小时Mobile Use/Qwen、24小时或所有OEM验收。启动/最终结果按实际执行追加，未结束不写passed。

首次独立后台启动在新增采集代码误置于制品预检时失败，未到instrumentation；日志保留，修正后补预检/采样回归。正式复测于18:26:58（北京时间）启动，ignored路径`build/mobile-followup-20261012/soak-reviewed-2h-r2/`。启动时核实worker父进程为1、独立PGID，手机实际方法已开始、单轮预热完成；当时记录pending，按Owner要求未继续等待两小时。最终状态见下节，不再作为当前pending项。主APK仍为`8102cc38e48eae8903a07389e9b9b771542614b1992286304b7a24d681f4f916`，Room identity与完整性保持；没有更新包、重建库或改变系统权限。

## 完成后的离线核对

Owner通知两小时已结束后读取既有制品，不再次启动或操作设备。指定 `ContinuousAppResourceDeviceTest#continuousAppResourceSoak` 实际开始、成功结束、无skip，instrumentation为 `OK (1 test)`；TestRunner为1 tests、0 failed、0 ignored。18:27:01开始、20:27:44结束；包含预热的instrumentation总时间7,243.879秒，设备测量阶段7,220,700毫秒，完整覆盖7,200秒请求。

**结论：本轮同机Developer应用资源两小时precheck通过，自动报告生成失败。** 原独立worker已结束；原始日志中的9行存在非UTF-8字节，`scan_logcat()`严格解码抛出UnicodeDecodeError，发生在设备成功结束后的finalize阶段，因此没有`result.json`，manifest仍写RUNNING。这是主机证据结算缺陷，不是设备测试未运行或应用资源失败；未修复脚本、改门禁或伪造原result。

离线分析按字节逐行读取，损坏行替换解码且计数；损坏的HelixSoak/HelixSoakCases/TestRunner行数为0。复用既有严格instrumentation解析和classify函数，另外核对采样间隔和完整循环编号。结果在ignored路径`build/mobile-followup-20261012/soak-reviewed-2h-r2/offline-analysis.json`，带明确离线来源；它不冒充自动管理器结果。

| 核对项 | 实际结果 |
| --- | --- |
| 进程 | fixture与237次有效host采样均为同一PID，未发现重启 |
| 循环 | 连续1～340，共340轮；每轮8个真实内部case，共2,720个测量case，另1轮预热8个，总2,728；不是2,728个独立测试种类 |
| 原资源门禁 | FD+8、线程+16、PSS+96MiB设备断言通过；observeBreaches=false，没有放宽、跳过或新增强制GC |
| 循环后FD | 132～134，首尾均132 |
| 循环后线程 | 42～54，首46、末52 |
| 循环后PSS | 最低136.9MiB、最高263.9MiB、末203.3MiB；精确预热后baseline数值未记录，不能把首轮值当baseline重算门禁 |
| host采样 | 237/238有效，最大间隔30.7秒，无超过90秒缺口；末次在成功收尾后返回No process found，保留该失败，不填零 |
| host PSS | 峰值298.9MiB，最后有效样本194.7MiB；host在循环中采样，与循环后Debug.getPss的设备门禁口径不同 |
| 崩溃/ANR标记 | 已保存日志内目标PID Java/native致命标记、目标包ANR标记均0；不是其他OEM或完整系统故障覆盖 |

四个连续85轮区间的循环后PSS中位数为190.6、192.6、193.7、195.4MiB，首末约+4.8MiB。host峰值时Private Other约82.4MiB、Graphics约32.7MiB，最后有效样本分别约29.6和2.4MiB；Java Heap最后也低于首个host样本。高峰有明显回落，本轮没有复现首轮PSS超限，没有证据支持大幅持续累积；少量增长的归属和是否存在小泄漏仍不能仅凭PSS判定。未读取堆、开启profiling或做新的设备操作。

原首轮的INFRA_INTERRUPTED及随后约102MiB超限仍是真实历史结果，不被本轮PASS抹去。本轮说明原APK在连续采集条件下可以完成该压力检查，不能证明首轮失败一定由Codex重启造成，也不能用一次通过撤销或调大96MiB阈值。

剩余工作是让日志扫描容忍并标记损坏字节、确保finalize异常仍写结构化失败结果，并补对应主机回归；不能仅用errors=ignore隐藏证据损坏。本轮是Room/FGS/Activity应用资源工作负载，没有两小时连续Mobile Use、Qwen、Browser专用工作负载或24小时/OEM验收。

## 报告器修复与原证据重处理

Owner授权完成剩余工作后，日志扫描改为逐行读取字节、替换解码并记录损坏行数；关键fixture行损坏时不允许PASS。finalize处理异常写INCONCLUSIVE和独立report finalization归因，不虚构设备失败。新增三项主机回归覆盖非关键字节、关键证据损坏和结算异常，runner定向16项、逻辑26项及全仓source/host220项通过，ADR/i18n/secret等检查通过。

原logcat和instrumentation未修改，新报告在ignored目录`build/mobile-followup-20261012/soak-reviewed-2h-r2-reprocessed/result.json`，明确`reportMode=offline-reprocessing`。新管理器实际解析原9行损坏、0行关键证据损坏并判PASS；340轮、7,220,700毫秒、237/238采样及原资源门禁事实保持。没有重新运行设备或更改APK/数据/权限。原自动报告缺失和首次失败继续保留，报告器缺陷本切片已闭合。
