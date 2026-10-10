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

一次性源码/文档改写器完成后，以当前源码、稳定回归和Git历史承接，不继续当迁移入口。重复launcher/解析器合并到现有稳定入口并更新调用方；只有Mock/Pure host测试进入自动发现，设备/真实服务测试仍显式opt-in。依赖锁、vendor输入、许可证、唯一协议或未关闭问题的诊断不能按日期删除。

删除前逐路径核对实际用途、所有当前调用者及替代；tracked源码记录Git来源，未提交或本地唯一脚本先保存并校验恢复副本。原始失败日志、数据库及模型轨迹和脚本分开留存，本地tar不是长期备份。历史复现以删除清单中的Git提交和path/hash恢复，禁止直接在当前工作树重跑旧改写器。

本次具体删除、迁移与验证见[HXA-267记录](../completion-records/HXA-267.md)及[清理证据](../evidence/development/test-script-maintenance-2026-10-10.md)。

历史版本日志不必永久另存副本：经Owner明确授权，核对其归属及当前复现入口后，按“来源提交/版本与APK身份 → 当时成功和初轮失败机制 → 修正与验证边界 → 现行复现入口 → 删除路径/hash”汇总。删除后在原报告注明原始日志不可用；未经执行不填当前通过，不用hash冒充备份。示例见[历史版本整理](../evidence/development/historical-version-retirement-2026-10-10.md)。
