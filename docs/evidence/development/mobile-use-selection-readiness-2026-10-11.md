# Mobile Use 开启前检查与日常模拟器证据

日期：2026-10-11。来源：Owner日常模拟器调试要求及追加开启前配置要求，HXA-271阶段6。当前工作树含其他在途功能；本记录只覆盖本阶段拥有的改动，不代表干净发布来源。

## 诊断

日常 Helix_API_36 / emulator-5554 / API36 arm64 / Developer，只读备份数据库后检查，四个Turn均COMPLETED，无在途任务。最近失败的 ui.launch/ui.snapshot 返回 MOBILE_USE_TASK_NOT_ACTIVE，确认来自工具结果。该拒绝还覆盖范围身份不符、锁屏、取消和任务接管，因此历史结果本身不能唯一证明后端原因。

本次被动 dumpsys accessibility 显示 Helix 无障碍在 Enabled services 与 Crashed services 中，Bound services 为空；Root执行服务未列出。权限开关与实际可用性确实不一致。crash buffer没有可对应的异常堆栈，不能据系统Crashed标记声称已经定位Java崩溃；先前测试的进程终止也可能导致该状态。不改系统权限来复现，不推断历史失败时刻的完整状态。

## 修复与边界

会话选择前需要全局范围和至少一个可用屏幕后端。沿用 PluginService 在持久选择写入前的原生检查，展示过期后断连也会拒绝写入。缺范围直达插件设置，缺连接直达系统授权与连接设置，失败保持未选中；保存和返回均不自动选择。对已选插件仍允许关闭，断连不删除持久选择或关闭不需要屏幕的已有能力。权限与每次执行范围校验保持，未改APK数据库schema。

手动开启“默认新会话启用”同样被拒绝并进入对应设置，不保存失败开关。会话错误入口使用具体的配置/授权标签，修正扩展卡旧“到会话设置选择范围并启动”的说明。

参考与取舍见[HXA-271](../../development/tasks/HXA-271.md#阶段6mobile-use-会话开启前检查)及[权限ADR](../../adr/permissions/001-session-authorization.md#decision-history--2026-10-11mobile-use-开启前就绪检查)。原始日志、只读数据库副本及制品置于ignored build/mobile-use-selection-20261011，不进入仓库。

## 本轮验证

主机 passed：`./gradlew spotlessApply spotlessCheck detekt test :app:lintConsumerDebug :app:lintDeveloperDebug :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest -PincludeSpikes=true`，最终host-r6退出0。新增MobileUseSelectionReadiness两项、PluginRegistry动态断连一项及App两渠道状态投影通过；全仓JVM/静态/双渠道Debug lint与APK、Developer测试APK编译通过。`./scripts/check-all.sh --source`最终退出0，其中206项脚本fixture通过，日志中的FAIL_FUNCTIONAL是既有失败解析反例，不是设备执行失败。初次复杂度门禁、默认开关参数数量/异常类型及随后复杂度门禁曾失败，提取路由/错误处理并修正catch边界后通过，没有放宽门禁或移除断言。

设备 passed：日常API36 Developer用原HelixAndroidJUnitRunner执行六套纯Compose夹具，首轮27/27、最终APK27/27，无skip。新增PluginSessionChoice五项检查缺配置、缺后端、返回后需再次点击、断连可关闭和busy时无操作；原过程文本、配置、工具时间线/滚动及审批布局一起回归。本次指定日常实例，按Owner要求覆盖安装、不启动或借用另一AVD；夹具不调用数据库重置、不访问用户数据库或修改系统权限，runner沿用既有ZH_CN，非中文输入测试。

最终APK实际页面检查：后端未就绪时点默认开关直接打开“授权管理”；现有会话仍选中并显示“屏幕操作需要授权或连接”，点“检查系统授权”直达同页。主服务断开状态保留，没有为测试改权限。就绪后首次选择路径由合成夹具检查，不声称真实后端任务已成功。

新旧APK均含当前Room identity后install -r。安装前后会话2、消息133、Turn4、工具调用60、会话插件选择3逐行摘要一致；全部配置、默认值和偏好保持，只有原生注册修订号及进程诊断记录随启动更新。最初将原生修订号也视为不变数据的比较失败；保留采集，按PluginRegistry.publish/RoomNativePluginCatalog的实际启动行为核对，仅内嵌和列revision变化，其余安装字段、默认值、用户记录仍严格比较一致，未修改产品或跳过数据比较。

安装包与最终构建SHA-256一致：`d445bd832577c2d8be9aada1f1e16026183a0b5b9dace6dcf405af647d1a5e6a`。日常实例保留运行并返回原会话，备份未回写，测试APK保留，未清数据/卸载/重置快照。临时UI助手曾使用不存在的导航标签，未点击控件，检查当前页面后按实际标签继续；不记作App功能失败。

真实模型 not requested，真机 not requested，不操作微信或其他真实账号。无障碍仍需用户在系统设置恢复连接；服务断开的具体历史成因和恢复后真实工具执行尚未验证，不把配置引导和合成UI通过当作该任务成功或所有后端/OEM验收。
