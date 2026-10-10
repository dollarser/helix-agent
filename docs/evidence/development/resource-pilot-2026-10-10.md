# 双渠道有界资源 pilot

日期：2026-10-10；Owner本轮明确授权模拟器及既有模型；范围：[HXA-265](../../completion-records/HXA-265.md)。使用现有ContinuousAppResourceDeviceTest及scripts/run-hxa103-browser-soak.py，不新建资源框架。依据[Android内存诊断](https://developer.android.com/topic/performance/memory)和[Debug.getPss](https://developer.android.com/reference/android/os/Debug#getPss())，保持生命周期资源观测；不以强制GC或系统干预替代验收。

修正三个测试工具缺陷：现有runner的finalize虽然按证据返回失败，run却固定return0；现已传播真实结果。app workload显式支持Consumer/Developer包名与instrumentation component，不将独立browser库误标为Developer。当前Room一项、前台服务六项、Activity资源一项共8项，而旧长稳入口仍断言7项；修正计数和诊断信息，完整保留所有用例及零ignore/assumption要求。预热失败不能计成成功的8项；独立browser loop不再被计成app嵌套JUnit套件。资源阈值FD+8、线程+16、PSS+96MiB不变，observe=false，无强制GC。

第一次两渠道5668/5670，各实际拼音1/1通过；资源测试均在预热阶段因旧7项断言失败，FAIL_FUNCTIONAL及退出1正确传给owned runner，正常关闭。尚未测量资源，不算资源门禁失败。旧首轮JSON中的推导预热计数不是实际成功检查，不纳入验收总数；后续host回归确保失败预热记0。failed原始记录保留。

主机：新增7项身份/不可变共享配置/非法渠道与库归属/真实run退出码/失败预热计数回归通过，原26项分类与固定门禁检查保持通过。修正后的全仓test/detekt/spotless/双渠道lint/App和AndroidTest编译退出0，46秒、993任务（33执行）。source门禁172项host及文档/ADR/i18n/secret通过；对应resource-closeout-fix-host.log、resource-classification-host.log、resource-closeout-source-r2.log。没有生产源码变化。

最终冻结基础396000c6；fixture SHA-256 72dbd6245ddd1d6f88d0b99f0498d9e125eecb88272f971b3d5fa271e8574e8a；runner SHA-256 c0542a55cf348a5fbc6b6b7a4a54f5269f531b65e35736c2596043756dd575ce。

| 制品 | SHA-256 |
| --- | --- |
| Consumer App | b6c8a9972e7278d7c3a4564494ce88fe6a7b4a8a6f7a86ce690efd9eca5ffbe5 |
| Consumer Test | 5edf641137d6f52258981c4dde05bdf7e7312640ddd1c40a58832d71269ae6a1 |
| Developer App | 557dce87f27db98129f22bd3c4505608b05203e73f01379d75c8b72d69b84633 |
| Developer Test | e65b7ae92fe19cb20b60c033b339258782fee0372b7d383f08301adc24419839 |

命令：scripts/with-host-slot.py包围scripts/run-owned-emulator.py，Helix_Main_Verify_API36只读、API36 arm64、4096MiB/2核；main原Pinyin，上限1200秒。after-script核查owner/AVD，再运行既有soak SERIAL --workload app --flavor CHANNEL --preset pilot --seconds 900 --output FRESH_PATH，1120秒上限；成功后再次核查owner及PASS/完整时长/同PID。最终Consumer5672、Developer5674；root finally只关闭本轮自有进程，不覆盖日常5554、不清数据。

原始证据在ignored build/maintenance-followup-20261010/resource-closeout-CHANNEL-r2/：owner/artifacts/closed、主拼音JUnit和实时日志；resource下manifest、result、单次instrumentation、全程logcat及每30秒samples。每周期是实际Room/前台服务/前后台资源旅程；900秒pilot不是2h/24h或模型、浏览器/JNI、所有Runtime业务验收，也不关闭真机卡顿黑屏、OEM或历史Binder/FD根因。

## 本轮最终结果

两个实际900秒pilot均PASS，每实例拼音1/1及单次连续资源instrumentation 1/1、零skip，两个owned实例正常关闭。

| 渠道 | 测量循环 | 含预热嵌套项 | 同一PID | FD峰值 | 线程峰值 | PSS峰值KiB | 主机采样成功/总数 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| consumer | 41 | 336 | 3980 | 131 | 45 | 136388 | 31/32 |
| developer | 41 | 336 | 4054 | 131 | 50 | 129286 | 30/31 |

Consumer最后一次hostSec967.6、Developer最后一次hostSec937.5采样在instrumentation已结束后无App PID、没有可解析TOTAL(PSS)，原记录保留null及note；不伪造0，不算成功采样。host采样仅旁证，原设备每周期峰值断言是门禁。完整时长、原阈值和同PID已经实际验证，不升级为2h/24h、全部业务或历史JNI/Binder根因关闭。
