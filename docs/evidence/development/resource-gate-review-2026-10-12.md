# 长稳门禁复核与同机复测

日期：2026-10-12。Owner要求先评估门禁再复测OnePlus/API34两小时资源失败；沿用HXA-271阶段11，不操作日常模拟器、账号或Qwen，不重建数据库。[原失败](mobile-backend-closeout-2026-10-12.md)及重启影响保留。

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

首次独立后台启动在新增采集代码误置于制品预检时失败，未到instrumentation；日志保留，修正后补预检/采样回归。正式复测于18:26:58（北京时间）启动，ignored路径`build/mobile-followup-20261012/soak-reviewed-2h-r2/`。核实worker父进程为1、独立PGID，手机实际方法已开始、单轮预热完成；后续结果pending，按Owner先前要求不继续等待两小时。主APK仍为`8102cc38e48eae8903a07389e9b9b771542614b1992286304b7a24d681f4f916`，Room identity与完整性保持；没有更新包、重建库或改变系统权限。
