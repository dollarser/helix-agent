# 有界会话性能对照：夹具与执行准备

日期：2026-10-10。范围：[HXA-261](../../completion-records/HXA-261.md)。首阶段仅主机准备；随后Owner明确允许本轮模拟器和既有真实模型，以下追加实际性能结果。此对照不调用模型，不宣称加速或真机黑屏修复。

## 固定输入与口径

SessionProjectionLoadDeviceTest仅在明确projectionLoad=synthetic-owned-avd且硬件ranchu/goldfish时运行；缺可选profile为skip、错误profile/硬件失败。授权执行必须精确选择此类，并要求非空、零失败、零跳过，不能将未提供profile当设备通过。只创建固定600个合成会话及重会话1000条USER/TEXT，普通正文1024字节，末消息含5个搜索词；不绑定Provider、不发送模型请求、不删除已有数据。固定ID存在时拒绝，应使用fresh自有实例，不能清日常库。

各5次：目录刷新从一次新标题写入开始，直到600个合成行及当次标题投影、Compose空闲；会话先稳定导航并关闭旧选择，再从openSession到1000条完整消息投影、Compose空闲，断言首尾ID；搜索包括原200ms debounce，逐词等待正确message命中且无其他会话命中。原搜索默认500候选/256KiB上限不变，验证最近消息标记，不要求所有历史正文都扫描。不是只等待旧缓存数量；完整投影不等于Lazy列表全部行渲染。30秒是有界功能等待，不是产品时延阈值。

JSON仅含固定计数与三组5次毫秒耗时；collector拒绝空/缺失/负数/非整数或错误计数。随后采集定向gfxinfo/meminfo，以及5次真实主进程SIGKILL和am start -W；检查Status ok、LaunchState COLD、PID变化及TotalTime，热启动/缺时长拒绝。每个ADB动作前核对owner PID/serial/AVD，只操作允许的Helix包，无force-stop、清数据、安全权限修改或持久访问设置；稳定runner冻结SHA并finally关闭自有进程。

参考[Android启动时间](https://developer.android.com/topic/performance/issues/launch-time)及[dumpsys](https://developer.android.com/tools/dumpsys)，复用现有ADB和runner，不引入新框架。debug模拟器、Compose轮询/导航、写入/文件读取、JIT/OS缓存影响结果；am start -W是首次显示，不是fully-drawn、磁盘冷缓存或真实手机体验。原始gfxinfo/meminfo不直接证明整体内存泄漏或帧率改善。

## 执行步骤：仅本轮Owner明确要求后

先核对没有在途设备任务，选同一API36 arm64 AVD/4GiB，依次运行Consumer/Developer基线及候选四个fresh只读实例，不借用/关闭日常实例。基线可选HXA-256冻结APK，以原artifacts.json核对路径/SHA，不作为release来源。旧App与当前Test仅验证这两个精确类的公共接口兼容；不兼容则报告失败、准备匹配测试制品，不计作性能退化。

Developer候选示例（未执行；替换授权AVD、空闲端口及fresh输出）：

```sh
FIXTURE_PACKAGE=com.helix.agent.developer python3 scripts/with-host-slot.py -- \
  python3 scripts/run-owned-emulator.py \
  --avd YOUR_AUTHORIZED_INACTIVE_API36_AVD --port 5680 --memory-mb 4096 --raw-results \
  --apk app/build/outputs/apk/developer/debug/app-developer-debug.apk \
  --test-apk app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk \
  --runner com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner \
  --classes com.helix.app.ui.PinyinComposerDeviceTest,com.helix.app.chat.SessionProjectionLoadDeviceTest \
  --instrument-arg projectionLoad=synthetic-owned-avd --timeout 360 \
  --after-script scripts/debug/2026-10-10/projection-load-after.py \
  --output build/debug/2026-10-10/projection-developer-candidate-unique
```

Consumer对应consumer制品及com.helix.agent.test runner、FIXTURE_PACKAGE=com.helix.agent，另一授权空闲端口/fresh输出。基线只替换明确冻结的--apk，硬件/输入/采样/Test SHA一致。保留owner/artifacts/closed、原JUnit/进程/测量，报告失败/跳过；每渠道各项5个原值、median/范围，不合并渠道或删除失败。原PinyinComposer验证实际候选/中文commit，稳定runner应用键盘与IME偏好。

HXA-259页面补验可另选同渠道原ProjectLifecycleUiDeviceTest、ProjectSessionsUiDeviceTest、ProviderSettingsFormDeviceTest、ProviderContextRecoveryDeviceTest、ProviderModelSelectionFeedbackDeviceTest、GroupedNavigationDeviceTest及PinyinComposer，先核对渠道前提。完整取消/恢复/并发、其他IME/OEM/长稳及真实模型故障矩阵为独立边界；真机黑屏仍需实体设备与日志。

## 本轮主机结果

报告、冷启动解析及错误serial/死亡owner的7项host回归通过，没有ADB调用。首轮全仓验证因新夹具sample格式化后达到LongMethod边界失败；按三个真实测量阶段拆为helper，保留所有完整性断言，不增加豁免。

最终命令：`python3 scripts/with-host-slot.py -- ./gradlew spotlessApply test detekt spotlessCheck lintConsumerDebug lintDeveloperDebug :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest`，projection-load-host-final.log退出0，993任务（34执行、959up-to-date）。全仓JVM/静态、双渠道Debug lint/App/AndroidTest编译通过。`./scripts/check-all.sh --source`最终退出0，160项host及文档/ADR/国际化/秘密门禁通过（793 Markdown、244 HXA、36 ADR）；日志projection-load-source-final.log在ignored build/maintenance-followup-20261010/。设备not requested，不把AndroidTest编译写成pass。

已在同一ignored目录projection-load-ready冻结双渠道App/Test四份制品及artifacts.json，并逐份SHA核对；用于后续授权对照，避免其他构建覆盖当前输入。fixture源码SHA256 `254900ee4594c7e6600b26430f9765ce9ff81a8516709e3b1e5e74f7de625992`；Developer App/Test为`557dce87f27db98129f22bd3c4505608b05203e73f01379d75c8b72d69b84633`/`5869c11205ea222c5a3e78e463dfab0b7dae0f0d801f65d5757892b1f4327edf`，Consumer App/Test为`b6c8a9972e7278d7c3a4564494ce88fe6a7b4a8a6f7a86ce690efd9eca5ffbe5`/`9d6a68d75020f44072f84c9e8822b1ac563554a508a1f4970c4045fc0ca26639`。不是日常安装或release来源证明。

## 本轮授权实际测量

API36 arm64、同一 Helix_Main_Verify_API36、4096MiB/2核、swiftshader；四个fresh只读实例，各2/2通过、零skip，包含真正拼音预编辑/中文commit。四组各5次目录/长会话/搜索及新PID首次显示，共20次实际进程SIGKILL后启动。每组另一次未计样本的暖启动，用于instrumentation正常结束主进程后建立诊断起点；PSS/gfx是这个重新启动后的单点/累计诊断，不是负载测量过程中内存峰值或独立帧率对照。日常5554未关闭、未覆盖安装。

下面为median（min–max），每格5个样本：

| 渠道/版本 | 目录刷新 ms | 1000消息投影 ms | 搜索 ms（含debounce） | 新PID首次显示 ms |
| --- | --- | --- | --- | --- |
| consumer/baseline | 62 (51–72) | 213 (150–250) | 267 (266–272) | 614 (526–631) |
| consumer/candidate | 53 (49–70) | 248 (233–316) | 287 (262–310) | 569 (517–605) |
| developer/baseline | 35 (33–51) | 266 (217–445) | 288 (269–300) | 601 (534–641) |
| developer/candidate | 50 (49–69) | 217 (200–299) | 276 (267–322) | 625 (556–675) |

这些debug模拟器样本范围重叠，夹具耗时包含轮询、Room写入和原debounce，没有一致退化或普遍加速的证据；不能外推OEM、长稳、完整渲染/主线程及真机黑屏。原始样本、PID和单点诊断在ignored build/maintenance-followup-20261010/projection-{flavor}-{baseline|candidate}-r3/，汇总projection-summary.json。

失败尝试保留：首轮1/2负载失败，夹具在切换会话同时导航产生竞态；将导航和close完成移到计时前，首尾/完整性断言及全部5样本不变。第一次修正引用private helper导致编译失败，改用现有public导航入口；新全仓主机门禁projection-navigation-host-r2.log通过（993任务、33执行）。r2 instrumentation 2/2通过但collector因正常结束主进程而pidof失败，不能算完整采集通过；改为一次未计时启动，7项host验证仍通过。r3基线完成后下一组因ADB登记尚未消失被安全拒绝；第二次交接登记消失但端口尚未释放，仍安全拒绝。未借用、清理或重置设备；等待自然退出后，剩余Developer使用分别空闲的5660/5662完成fresh实例。

冻结制品（App来自原固定候选/基线，Test均为导航修正r2；基线混配仅证明上述两个公共接口测试兼容）：

- consumer baseline App `3b6eb120c045e3a248055671b327baa06bfa0053d4a3bea87e0d41ec4aff969e`；Test `98c2fa072314b6db2d12101d7da9be16d2c02e383ddef09abecef9a83e726d17`。
- consumer candidate App `b6c8a9972e7278d7c3a4564494ce88fe6a7b4a8a6f7a86ce690efd9eca5ffbe5`；Test `98c2fa072314b6db2d12101d7da9be16d2c02e383ddef09abecef9a83e726d17`。
- developer baseline App `6a3958f20442f00bf596afbedc001713b095f49949fb739682142643027f5edd`；Test `0c29e74102d72b5d600d86f1b8e101b5002d540749feae811393175d82493674`。
- developer candidate App `557dce87f27db98129f22bd3c4505608b05203e73f01379d75c8b72d69b84633`；Test `0c29e74102d72b5d600d86f1b8e101b5002d540749feae811393175d82493674`。

最终fixture源码SHA256 `85af4617675eacb13d674d44798e02ec8fe3a3d6e6673766fddfefca72164891`，不是发布制品身份。页面迁移及真实模型压缩在下一独立检查点承接，不以这些性能结果代替。
