# Bug Fix: 模拟器基线输入连接就绪、Compose lint 与测试证据汇总

Status: fixed
Date: 2026-09-09
Related HXA: HXA-160, HXA-147
Affected modules: app UI, browser androidTest, host test scripts

日期：2026-09-09。本修复由所有者根据EV-01报告明确授权；在隔离工作区与专用AVD执行，避免覆盖另一执行者的浏览器诊断。

## Problem

- 两处布局使用Configuration.screenHeightDp，App两flavor lint实际报错；locale测试读取LocalContext的Configuration也报错。改为LocalWindowInfo的高度经LocalDensity换成dp，保留640dp/fontScale判定；测试使用LocalConfiguration。
- Autofill夹具在DOM focus后立即要求native InputConnection非空，独立API36冷启动也复现失败。加入有界就绪等待后通过；真实commitText、dataset选择、表单成功页、实际onSaveRequest和撤销断言均保留。未改生产浏览器、未用JS赋值替代输入、未吞掉输入失败。
- 夹具HTTP响应在Activity重建/取消时可能Broken pipe，使后台线程异常终止整个测试进程。合并响应写入并仅处理SocketException，记录客户端断开；业务成功仍须页面和真实填充/保存断言，响应未交付不会返回假成功。
- 旧临时runner只匹配一层模块目录且忽略testsuites根节点，可能漏掉失败；隔离复验会覆盖原XML。新增严格解析工具及每attempt归档runner，拒绝空数据、重复case和计数不一致；失败/skip分别统计，未知skip不自动当PASS。设备排他锁防止本runner重复占用同一serial。

## Impact

现有布局lint阻断App质量门禁；Autofill存在输入就绪竞态和夹具socket异常，阻断可靠的浏览器长稳预检。错误汇总会把遗漏/跳过当成通过，或把已通过的历史用例误判从未成功。

## Root cause

历史HXA-160的API36 Autofill已通过完整套件和三次独立重复，“新测试从未通过”不成立。当前与历史WebView均为133.0.6943.137，连续失败只能描述该环境复现，不能推导平台不支持。此次仅等待native连接即可成功，支持夹具同步问题。

原EV-01浏览器38项应为35通过/1失败/2跳过，总336项为330通过/1失败/5跳过。8/9 Gradle任务包括构建，不是9组设备测试；00-install-app只assemble。原盘点只存在四个APK只读副本，不是七个，后续安装又直接使用Gradle输出。EV-00须重新冻结，EV-01仍未全矩阵验收。

HXA-160记录的根级lintDebug命令确实成功，但日志没有App的flavor lint任务，因此不能称完整App lint通过。此次必须显式验证:app:lintConsumerDebug与:app:lintDeveloperDebug。历史命令不篡改，覆盖范围按本勘误修正。

## Fix and invariants

修复仅更换窗口尺寸读取、等待真实输入连接并记录客户端响应取消。输入超时仍失败，真实系统填充/保存断言不降低；不改生产浏览器、数据库、权限或ADR。脚本归档每次XML并从case节点核对计数，不能以退出码或构建成功冒充设备通过。

## Alternatives considered

额外真实点击加等待的试验可通过，但仅等待原连接已经充分，最终不保留点击。逐键注入增加输入路径变化；JS赋值可能绕过Autofill通知，均未采用。未禁用lint或添加baseline/suppression；未放宽生产资源门限。

## Regression verification

- `python3 -m unittest discover -s scripts/tests -p test_summarize_android_tests.py`：6项通过，覆盖根suite/wrapper、失败和skip、空结果、计数矛盾、重复case。
- `scripts/run-android-test-attempt.py`：必须显式传serial、单一connectedAndroidTest task、新output，可选class。每次保留完整Gradle日志、新鲜XML副本及SHA-256，避免重跑覆盖。已知opt-in仍输出INCOMPLETE并保留case身份；调用者复核预期skip后报告已执行范围，不把skip写成通过。
- `JAVA_HOME=JDK17 ./gradlew :feature:browser:assembleDebugAndroidTest :app:lintConsumerDebug :app:lintDeveloperDebug :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest --max-workers=1`：通过；两flavor各0 errors/0 warnings/5 hints。
- `JAVA_HOME=JDK17 ./gradlew spotlessApply :feature:browser:assembleDebugAndroidTest :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest detekt --max-workers=1`：通过；Consumer336项、Developer360项，各4项既有条件skip，零failure/error。

最终设备结果见本文件后续验收补充。原始attempt位于隔离工作区忽略的`build/ev-repair/`；失败、仅等待对照、最终单例与完整套件分别保留。提前启动的UI构建在设备测试开始前取消，CANCELLED记录保留，随后独立串行重跑。API29首次AVD启动未建立console/ADB连接，停止自身进程改独立端口后复跑，记基础设施失败，不记产品失败。

这两个脚本仅修短测结果归档/判断，不是完整长稳runner，不宣称实现APK安装身份校验、72设备小时或EV-00～12验收。正式长稳仍需先按计划补齐夹具、冻结产物和pilot。

决策记录：不适用；不改变ADR-0033、生产浏览器生命周期、权限、依赖或外部协议。

最终设备验收：专用API29/WebView91与API36/WebView133各三次Autofill单例通过；完整浏览器各38项=36通过、2既有opt-in跳过、零失败；两API的ConversationComposer/ModeLayout/FilesScreen/MainActivity组合各11项通过、零skip。短测runner完整套件结果保留INCOMPLETE及精确skip身份，不伪造38项执行通过。

## Residual risk

本轮是短时修复与回归；系统JNI/Binder根因、长稳、真机和外部账号仍开放。通用短测runner不提供完整的长稳安装身份或系统设置恢复契约，取消/超时后须核实设备未有遗留测试，再启动下一轮。

## Related records

- [HXA-160](../completion-records/HXA-160.md)
- [模拟器总计划](../development/emulator-verification-master-plan.md)
- [浏览器专项](../development/browser-autofill-soak-plan.md)
