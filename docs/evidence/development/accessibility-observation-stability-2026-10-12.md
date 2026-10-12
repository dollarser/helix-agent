# 真机无障碍观察失效：定位、修复与回归

日期：2026-10-12。Owner授权修复同一OnePlus/API34的无障碍输入后连续TARGET_CHANGED，并使用现有本机Qwen合成表单回归。沿用已批准的临时无障碍、悬浮窗及已有Root范围；没有新增权限、安装Shizuku、操作真实账号或再次重建数据库。代码提交 `78afcad3`；APK在提交前从包含这些改动的工作树编译，不是发布包。

## 原因与失败对照

直接无障碍端口的中文输入及后续观察正常。进入实际Dispatcher及运行中悬浮窗包装后，隐藏浮窗并等待两帧，仍会收到延迟窗口层级事件。日志记录后台Helix窗口 `TYPE_WINDOWS_CHANGED=4194304`、`WINDOWS_CHANGE_LAYER=16`，活动合成表单窗口未变，generation在遍历期间增加。例如事件窗口729、活动窗口735、generation135；随后捕获135与当前136不匹配。自有浮窗事件另有正确own标记，不能归因于所有浮窗都未识别。

安全拒绝正确，遍历完成的节点已经过期。原实现直接把过期读数交给模型，下一次调用又隐藏浮窗，引发新的层级事件，容易反复TARGET_CHANGED。两帧渲染完成不能保证无障碍事件队列稳定。

保留最初Qwen两轮业务失败/240秒超时；后续诊断轮次曾通过截图与手势兜底完成一次（69.155秒），第二次仍超时，不描述为始终失败。初版严格夹具遇到输入前STALE_TOKEN，因此最终回归将原生输入与观察包装分开，完整模型旅程另验实际Dispatcher输入。同一最终严格夹具修复前十次读取仅两次SUCCESS，其余TARGET_CHANGED，断言失败；修复后十次均SUCCESS且读回“你好 Helix”。没有用ANY成功断言结算修复。

## 修复与参考

只对遍历期间失效的只读观察最多重新采集三次：清除过期token，再从当前节点树重新读取。每次前后检查同一服务、原自动化会话、范围及系统设置授权；真实工具还检查原Conversation授权、取消及deadline。锁屏或会话失效退出，最终保留generation检查。敏感界面、范围拒绝、无服务等原始失败不重试；事件风暴仍返回TARGET_CHANGED，不发布过期节点。点击/输入没有自动重放，不跨后端恢复未知结果。

保留所有窗口事件失效逻辑，不忽略后台窗口/层级事件；没有新增主线程节点查询、框架或执行域。Root路径不修改，黑屏结算保持关闭。

参考[Android AccessibilityEvent](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent)的窗口层级定义及[AccessibilityNodeInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo)的更新/失效语义，对照[agent-device inspect-act-verify](https://github.com/callstack/agent-device)及[移动端示例](https://www.callstack.com/blog/agent-device-ai-native-mobile-automation-for-ios-android)。采用新观察及独立核验原则，独立实现，不沿用缓存坐标或复制竞品代码。读取日期2026-10-12；竞品原则不是本机原因的证明，原因来自真实日志对照。

## 当前验证

| 检查 | 结果 | 边界 |
| --- | --- | --- |
| 稳定观察JVM回归 | 8/8，无skip | 稳定读、失效后重采、三次事件风暴边界、前置/读取中/两次间撤销、策略失败、异常传播 |
| OnePlus API34 Developer原生输入及Dispatcher观察 | 1/1，12.45秒 | 实际运行中loopback SSE及浮窗包装，十次成功读回中文；不是IME拼音验收 |
| 同一手机Qwen3.8-27B无障碍任务 | JUnit1/1，58.051秒；业务2/2 | 两轮22.077/21.881秒，实际set_text/snapshot/scroll/click，独立ContentProvider核实TEST_DONE及点击次数1 |
| 上述两轮HOME/返回实际会话 | 4/4 | 会话身份保留、截图颜色及窗口核验；不是长后台验收 |
| 全仓主机门禁 | passed，1m51秒 | 含可选模块test、spotlessCheck、detekt、双渠道lint/APK和Developer测试APK；source夹具208项及文档/ADR/三语言/secret检查通过 |

Developer APK SHA256 `350f69d8157821b9df76d2de35c0bae5de6b3bf199aed8ff2aabed7d5100acaa`，从手机安装路径读取匹配；测试APK `f63d2024db5383a5bacdee48247e54df8ea7acdac8bb7f8c93baaa20785eacee`。本次覆盖更新保持Room identity `eb439f0c60ced11912eaac8b50dada5b`、integrity_check=ok；最终1会话/0Turn，没有再次重建或删除非夹具记录。原旧包/旧库备份继续在私有ignored目录保留。

主机命令：`ORG_GRADLE_PROJECT_includeSpikes=true ./gradlew --no-daemon --offline test spotlessCheck detekt :app:lintConsumerDebug :app:lintDeveloperDebug :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest` 及 `./scripts/check-all.sh --source`。两次模块级任务名错误及一次格式/复杂度门禁失败保留，改用既有根任务、提取授权检查后通过，未删门禁。source日志中的FAIL_FUNCTIONAL来自解析器负向主机夹具，不是失败设备测试；以完整命令退出及最终结果为准。

实体命令始终显式指定唯一OnePlus。以下serial为占位符：

```sh
adb -s "$ONEPLUS_SERIAL" shell am instrument -w -r \
  -e class com.helix.app.eval.AccessibilityInputObservationDeviceTest \
  -e helixAccessibilityInput true \
  com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner
adb -s "$ONEPLUS_SERIAL" shell am instrument -w -r \
  -e class com.helix.app.plugin.ProgressiveSkillModelDeviceTest#mobileSyntheticForm \
  -e pluginSkillPort 30008 -e pluginSkillModel Qwen3.8-27B \
  com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner
```

第二条默认accessibility，需Owner当轮授权真实服务用量。原始日志在ignored `build/physical-followup-20261012/`：`input-observation-strict-native-before.txt`、`input-observation-fixed.txt`及logcat、`as-qwen-fixed.txt`/logcat、`snapshot-fix-host-final.txt`、`snapshot-fix-source.txt`。完整instrumentation状态经原严格解析器核实，不根据shell退出或单独OK字样判通过。

无障碍恢复null/0，悬浮窗AppOps恢复default，自有30008 reverse移除；无Root执行服务，未改Root manager。合成会话/模型清理后返回Helix，未操作日常模拟器。当前模型App PID过滤日志无FATAL。本轮观察缺陷及同机有界回归完成；其他OEM、Shizuku、真实账号、整屏视觉及2h/24h长稳仍独立保留，不由两轮成功外推。
