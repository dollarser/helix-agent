# HXA-211 导出开发历史与最终验收

日期：2026-09-20。以下按阶段保留开发历史，早期“待验”描述不代表当前状态；最终结论见末节及[完成记录](../../completion-records/HXA-211.md)。

## 已接入的代码路径

- `HelixStorage.sessionExports` 在单次 Room 事务中暂存选定会话关系，再在事务外物化 JSONL；输出有版本、稳定记录 ID、连续序号和完成尾。
- Provider 只提取历史模型身份，不输出 endpoint 或配置。历史模型结束原因、工具到模型的关联缺失明确标注；预算审计按实际 `modelCallId` 关联。
- 小正文验证字节数与 SHA-256，明确缺失、变化、脱敏与超限。大正文仅引用，标注未读取验证。原始与导出正文 hash 分开。
- 消息历史和派生压缩记录分别输出；已有用量标明层级及来源未知边界，不将未知记为零或跨层重复相加。
- `PreparedSessionExport` 流式复制并验证暂存字节；目标关闭成功后才正常返回。应用 `SessionExportService` 接系统文档输出及中断清理，进程重启不自动重导出。
- 会话页接入导出弹窗、系统创建文件入口、进度、取消和结果。common 代码用于两个 flavor；三份语言资源同步。

## 当前验证

- `./gradlew :app:testConsumerDebugUnitTest --tests 'com.helix.app.export.*' :app:assembleConsumerDebug :core:storage:testDebugUnitTest :core:storage:assembleDebugAndroidTest detekt` 通过，日志 `build/hxa211-projection-build-v6.log`。新增 storage 导出单测 12 项、应用凭据处理单测 3 项通过。
- API29/36 的独占模拟器运行 `SessionExportSnapshotDeviceTest` 各 5/5：单会话隔离、旧字段缺失、取消与缺失会话清理、并发修改一致性、引用去重、压缩前后历史与最终投影。记录在 `build/hxa211-projection-v1-api29/` 和 `build/hxa211-projection-v1-api36/`；两个自有模拟器均退出 0，不冒充应用 UI/SAF 设备验收。
- 更早的正文/快照版本 API29/36 各 4/4 通过；这些旧结果不覆盖之后新增的完整投影。

## 继续实施与验收

- 格式规范、引用状态、合成样例和独立解析器已补齐，见下方专项证据；最终综合验证仍需覆盖新的应用制品，不能将旧四象限 APK 当作新增格式的验证。
- 旧系统文档与恢复专项四象限保留；实际 DocumentsUI 与窄屏/大字体已完成 consumer/API36，见下方。仍需更新后应用四象限，不把原 Activity result 测试冒充真实选择器操作。
- 大数据和生产默认总量/暂存上限已补双 API，见下方；内存优化后的应用回归、剩余边界审查及全量门禁继续进行。
- 完整主机门禁、双 flavor 边界、源码/文档及锁文件检查通过后，再提交任务完成记录。当前尚未完成 HXA-211，也未推送或合并 main。

依据：[HXA-211](hxa-211-task-specification-2026-09-20.md)、[ADR-AGENT-005](../../adr/agent/005-session-jsonl-export.md)。

## 系统文档、取消与真实进程恢复专项

新增 `SessionExportJourneyDeviceTest`（4 项）、`SessionExportUiDeviceTest`（1 项）、`SessionExportRecoveryDeviceTest`（1 项验证）。测试文档 Provider 仅在测试 APK 中：通过所属进程创建合成文档并授予具体 URI，生产导出使用普通应用权限，未放宽生产 Manifest。UI 驱动系统创建文件的返回边界，随后真实写入并回读 Provider 文件；选择器取消不启动导出。

专项发现并修正：

- 仅在输出块间检查取消不能中断阻塞写入。现在取消会关闭当前文件句柄；先记录取消原因，避免 API36 的传播竞态将 `InterruptedIOException` 上报成普通 I/O 失败。正常成功不会向 Provider 发送取消。
- 在目标支持时，仅为本次新建文件保留写权限，供进程死亡后的清理使用；完成/清理后释放，不释放原先已有的授权。临时授权不支持持久保留或目标撤权时，保留“不完整文件可能残留”的结果边界。
- 输出关闭前检查已报告的远端错误；写入/关闭失败、拒绝访问、ProxyFileDescriptor 实际返回 `ENOSPC` 均不报告成功。`ENOSPC` 是受控文件系统回调注入，不是把设备物理存储填满。
- 两阶段测试在首次写入块后调用真实 `Process.killProcess`，新 PID 检查半成品缺少完成尾，随后只清理输出/暂存/本次写授权。原会话 128 条消息保留，不重导出。

Android 行为参考：[ParcelFileDescriptor](https://developer.android.com/reference/android/os/ParcelFileDescriptor)、[DocumentsProvider](https://developer.android.com/reference/android/provider/DocumentsProvider)。实际可用性以上述设备证据为准。

构建命令：`./gradlew :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest :app:testConsumerDebugUnitTest --tests 'com.helix.app.export.*' detekt`，最终日志 `build/hxa211-journey-build-v7.log`，通过。

设备入口为 `scripts/debug/2026-09-20/run-session-export-matrix.py`，在 `with-host-slot.py` 下串行执行。每象限独占全新模拟器，先 setup/verify，再运行 5 项输出/UI 测试；总计恢复 4/4、输出/UI 20/20，另有 4 次预期进程中断。四个自有模拟器均正常退出 0。

| 象限 | 恢复 | 输出/UI | 证据目录 |
| --- | --- | --- | --- |
| consumer API29 | 1/1 | 5/5 | `build/hxa211-journey-matrix-v7/consumer-api29/` |
| consumer API36 | 1/1 | 5/5 | `build/hxa211-journey-matrix-v7/consumer-api36/` |
| developer API29 | 1/1 | 5/5 | `build/hxa211-journey-matrix-v7/developer-api29/` |
| developer API36 | 1/1 | 5/5 | `build/hxa211-journey-matrix-v7b/developer-api36/` |

v7 的最后象限在启动前被端口绑定检查拒绝，未执行测试；改用每象限独立端口后，仅补跑 developer/API36。v7b 使用同一批 APK，未把启动失败记成测试通过。

| 制品 | SHA-256 |
| --- | --- |
| consumer 主 APK | `62d38625cbc0325e7b340082d3a3e0897b45140429c4fc5fbbfa0cc78ee8fbf5` |
| consumer 测试 APK | `df12ea0387665b02790657863eda1f18552f6f1f6f1ec3d11737add17da449ac` |
| developer 主 APK | `049eb94e5f4444219a90fab30c3e8d7538ccd242820e87bb6906f5cc4d56d509` |
| developer 测试 APK | `1885b98276fb7ee766bced09fc6cadfa9add4cb7eae839aea985565fe2f258ea` |

这组证据不代表真实云盘/OEM 验收、128 MiB 压力验收或 HXA-211 完整交付。仍按上面的待办完成格式、引用、独立消费和全量门禁。

## 引用与独立解析专项

新增只针对固定快照的临时 SQLite 身份索引，与关系快照共用 128 MiB 暂存限额，不扫描其他会话，不建立执行事实源。关系保留目标 ID，并区分 included、not_in_selected_snapshot、not_recorded、omitted_limit。新增跨会话 Turn 引用、已删除压缩模型来源的真实 Room 检验；不输出其目标对象，也不猜测删除原因。缺失工具到模型的历史关联继续明确为空。

- `./gradlew :core:storage:testDebugUnitTest :core:storage:assembleDebugAndroidTest detekt` 通过，`build/hxa211-references-build-v3.log`。新增引用单测 3/3；存储导出 JVM 单测累计 15 项。首次静态检查的嵌套/行长问题已修复后重跑。
- API29/36 `SessionExportSnapshotDeviceTest` 各 6/6，分别在 `build/hxa211-references-api29-v2/`、`build/hxa211-references-api36-v1/`。测试 APK SHA-256 同为 `ab22b929be80b05d5864191a2c257fcf56e434ac956448a5d65b7d0ea2fc7611`，自有 PID 93621/91253 均退出 0。API29 v1 在安装阶段 120 秒超时，未进入测试，不计通过；其自有 PID 90662 退出 0。
- 仅增加合成导出制品留存后，`./gradlew :core:storage:assembleDebugAndroidTest detekt` 再次通过，`build/hxa211-consumer-build-v1.log`。这里的 consumer 指独立文件消费者，实际为无 flavor 的 storage 测试 APK，不是 consumer 应用验收。
- API36 实际导出并交给独立 Python 解析器，`build/hxa211-independent-api36-v1/`：存储 6/6、解析及消息/调用重建成功。APK SHA-256 `86b66cbd9bc6853d3b9c8100d9282f4d7cf35c7d931817727d447ce5b1a548a2`，自有 PID 94140 退出 0。14 条记录，原消息和 checkpoint 各 1 条；报告 2 项未记录关联、2 项脱敏、1 项省略，材料完整度为 false，不伪称完整评测数据。

[格式规范](../../architecture/session-export.md)、[合成样例](../../../scripts/fixtures/session-export-v1.jsonl)、[独立校验器](../../../scripts/validate-session-export.py) 已加入。校验器使用标准库临时 SQLite，退出关闭并删除，仅读取选定 JSONL。反例测试涵盖主版本、混合身份、序号、缺尾/假统计、引用缺失与错配、正文篡改、重复 JSON 键、BOM、末行 LF；新增真实缺失引用的有效但不完整分支。测试已接入 `check-all.sh --source/--all`。

本切片仍未提交、推送或合入 main；211 还需资源压力、实际选择器/窄屏、更新后四象限与完整主机门禁。所有者已更新 Goal：211 后只生成 198/199/206 任务计划，不直接实施。

## 实际选择器与窄屏

新增 `SessionExportPickerDeviceTest`，不拦截 Activity result：在实际 DocumentsUI 中修改合成文件名并点击保存，导出到新建 Downloads 文件；通过测试 shell 独立回读，仅删除本次 UUID 文件。回读 JSONL 再由独立解析器校验。原应用的数据访问权限未扩张。

导出弹窗正文增加滚动，按钮保持可见。consumer/API36 普通屏与 320dp 宽、150% 字体各 1/1 通过，`build/hxa211-picker-narrow-api36-v1/` 及其 `narrow/` 保存三阶段截图、配置、回读和校验结果。已人工查看普通/窄屏成功截图，说明与按钮未被裁切；不是其他 OEM 或语言布局的全覆盖。主 APK SHA-256 `809b9fe2919d656857c3d45b6cb675cc76e79f10d96a3de40ec63005bfb4e53e`，测试 APK `bae52721b37949e7ae89486d38183cd92be761cf043b3c3a3d7317332c46e16c`，自有 PID 98302 退出 0。之后存储查询发生优化，最终应用回归仍待运行。

## 大数据压力与查询内存修正

新增 `SessionExportResourceDeviceTest`，使用磁盘 Room 合成数据库：10,000 条消息与 3,000 个各约 24 KiB 参数的工具调用，导出 82,041,289 字节（约 78.24 MiB），实际写出并核对完成尾的消息/调用计数。另一场景用 3,000 个约 48 KiB 参数，触发生产默认 128 MiB 快照限额，确认失败、删除本次暂存且源调用仍存在。JVM `productionFileCapIsEnforcedWithoutAccumulatingOutputInMemory` 另验证默认 128 MiB 最终输出上限，失败后不能补写完成尾。

首次压力测量发现 SQL `DISTINCT` 与 `ORDER BY` 把大参数放进临时树。只去掉无必要的 DISTINCT 有收益，但仍按大正文整批排序；最终改为原顺序的 128 键分页，再逐条投影正文。整个读取继续处于同一个 Room 事务内，无 schema 迁移，未牺牲稳定排序或跨会话范围。保存的 EXPLAIN 计划区分原双临时树、仅去重移除、当前只排序有界键三种查询。

| API36 查询阶段 | 大导出 PSS 峰值 KiB | 大导出耗时 ms | 超限失败 PSS 峰值 KiB | 超限失败耗时 ms |
| --- | ---: | ---: | ---: | ---: |
| 原整批 DISTINCT + 排序 | 233942 | 29360 | 440820 | 14247 |
| 仅移除 DISTINCT | 164194 | 26157 | 278468 | 5913 |
| 128 键分页后逐条正文 | 79941 | 25929 | 37196 | 2849 |

最终源构建：`./gradlew :core:storage:testDebugUnitTest :core:storage:assembleDebugAndroidTest detekt`，`build/hxa211-resource-build-v4.log` 通过；导出相关存储 JVM 测试累计 16 项。

最终双 API 都运行 `SessionExportResourceDeviceTest,SessionExportSnapshotDeviceTest` 各 8/8。统一测试 APK SHA-256 `c1939b6b9123c1e9b30f43f56f6264ce32f7230b96910ec1804bb06ca03d92c2`：

| 场景 | API29 | API36 |
| --- | ---: | ---: |
| 大导出耗时 ms | 51576 | 25929 |
| 大导出 Java 堆基线 / 峰值 bytes | 5897288 / 15829048 | 29475600 / 75427824 |
| 大导出 PSS 基线 / 峰值 KiB | 32002 / 38207 | 58683 / 79941 |
| 大导出暂存采样峰值 bytes | 158857141 | 158857141 |
| 超限失败 PSS 基线 / 峰值 KiB | 28365 / 35497 | 34000 / 37196 |
| 超限失败耗时 ms | 1930 | 2849 |

API29 证据 `build/hxa211-resource-api29-v2/`，自有 PID 239 退出 0；API36 为 `build/hxa211-resource-api36-v3/`，自有 PID 99926 退出 0。前序版本和失败日志保留，不混写成同一制品。采样间隔 100 ms，峰值是观测值，不是数学上界；PSS 包含存储测试进程、数据库及运行时，不是完整产品 PSS。暂存不含原数据库或用户目标文件。API29 旧整批版本大导出 18964 ms，新版 51576 ms，不能宣称所有设备全面提速；保留内存收益和逐行查询开销的取舍。所有数据均为合成，未覆盖真实低端机/OEM/热压。

设备入口仍为 `run-owned-emulator-207.py`，经 `with-host-slot.py` 串行；资源报告由 `capture-session-export-resource.py` 仅从其拥有的设备取回。当前剩余：最终应用四象限/实际选择器覆盖、普通正文与打开目标期间取消边界复查、完整主机门禁及正式交付记录。

## 最终验收与交付

最终边界复查修正两处行为：自由文本以方括号/花括号开头但不是 JSON 时，保留普通业务说明；严格结构字段仍按结构规则处理。打开目标期间主动取消时，将 Android OperationCanceledException 按已记录取消原因结算；未主动取消的异常仍为失败。新增 slow-open Provider 旅程真实复现前者的取消路径，不跳过失败测试。

`python3 scripts/debug/2026-09-18/with-host-slot.py -- ./scripts/check-all.sh --all` 完成，`build/hxa211-final-host-v2.log` exit 0；包含存储导出 16 项、应用凭据 3 项、Python 7 项，以及全部主机门禁和 36 份锁。首次 lint 指出英文数量文案和 SharedPreferences KTX 建议：修正文案，并为必须检查 Boolean commit 结果的调用保留有说明的局部 lint 例外。

显式重建双 flavor 测试 APK（`build/hxa211-final-test-apks.log`，exit 0），再执行 `run-session-export-matrix.py --output build/hxa211-final-matrix-v3`，共享 host slot，exit 0。每象限恢复 1 项、旅程/返回边界/实际选择器 7 项、320dp/150% 字体实际选择器 1 项，共 36 项；另有 4 次预期进程死亡准备，不算通过测试。v1 新增打开阶段取消失败，修正后 v2/v3 通过；最终以 v3 制品为准。

| 最终制品 | SHA-256 |
| --- | --- |
| consumer 主 APK | `9e287c5c33601ddf45513808cb1b62f006a319bb01c23c9577d63118c5243198` |
| consumer 测试 APK | `3933e2fed42c64bb3160b693abe50c33a380beae309da2a11b5f1444de9fa829` |
| developer 主 APK | `8aed9716dcae5025d41e2dc86aad3bd139684a817fd579d255f097431c5e83e3` |
| developer 测试 APK | `3844a67bff71537f764b90647d235d9ad692b8df8b2d4b51f3e542bdff909c1f` |

证据根目录 `build/hxa211-final-matrix-v3/`：consumer API29/36 自有 PID 10411/10526、developer API29/36 自有 PID 10715/10832 均退出 0。各象限保存 owner/closed、制品哈希、恢复与旅程 instrumentation、普通/窄屏三阶段截图、实际回读 JSONL 和独立解析报告。上述存储资源双 API 各 8/8 为独立专项，不能与应用旅程重复计数。

HXA-211 本地交付完成，未推送或合并 main；不代表真实云盘、OEM、全产品验收。196 真机待验不因此关闭；后续仅生成 198／199／206 计划。
