# Mobile Use 授权状态与恢复交互

日期：2026-10-11；范围：[HXA-271阶段7](../../development/tasks/HXA-271.md)。对应 Owner“帮我优化”授权/连接交互要求。与此前日常模拟器检查分开记录，本轮仅主机验证和制品准备。

## 发现与修正

DeviceAccess 原来将本应用无障碍配置集合和全局 ACCESSIBILITY_ENABLED 同时用于授权判定。AOSP 全局值反映正在处理事件/UiAutomation 的系统运行状态，不能证明本应用授权撤销。现在授权读取组件配置，连接读取 AccessibilityManager 的系统绑定服务列表；实际 Mobile Use 执行仍要求自身连接和操作级能力，不从授权存在推导执行就绪。

授权页区分未授权、已授权未连接、可用和检查失败；断连提供系统设置恢复入口和简短操作指导。无障碍、Shizuku 前台查询在工作线程执行，取消正常传播，失败清除旧状态。Root 已有缓存授权时点击连接已启用功能；当前拒绝改为显式请求授权，宿主连接不禁用其他消费者的修复入口。授权仍授予 Helix 应用，消费者保持各自服务和范围。

Mobile Use 展示包装器按真实会话范围、取消、期限、锁屏与对应操作能力提供具体拒绝原因。旧非空范围身份失配优先于断连，取消优先；隐藏浮窗后复查，被接管时释放隐藏租约且不执行。委托执行器失败继续传播，不假造成功或回滚；应用发现仍保持无需屏幕服务的部分能力。

## 验证

首轮格式/静态检查发现新增长行，已拆分并重新检查，未改门禁阈值或删减断言。中间一次源码检查与翻译文件写入重叠，报告英文缺键；三个语言文件补齐后冻结源码重新检查通过。原有工作树含其他插件/ANR研究改动，保留且不纳入本阶段提交；全仓验证针对当前工作树，不声称其他在途功能已验收。

本轮完整命令：

```sh
./gradlew spotlessApply
./gradlew spotlessCheck detekt test :app:lintConsumerDebug :app:lintDeveloperDebug :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest -PincludeSpikes=true
./scripts/check-all.sh --source
./scripts/check-docs.sh
```

最终全仓 JVM、spotless/detekt、双渠道 lint/APK、Developer AndroidTest APK编译全部通过（退出0，最后一次Gradle1m46s）。定向19/19，无skip：AccessibilityPresentation 4、RootGrantPresentation 5、MobileUseAdmission 4、MobileUsePresentedExecutor 6。覆盖授权但未绑定、部分授权、无服务、Root当前拒绝/旧缓存、宿主已连接但另一消费者可修复、准入取消/期限/锁屏/范围/后端、隐藏期间接管、执行器异常和租约释放。源码门禁退出0，206主机夹具通过，文档、ADR、2242键三语言一致和secret检查通过；文档专项检查退出0（813 Markdown、254 HXA）。源码门禁的负向夹具会打印 FAIL_FUNCTIONAL，按最终退出码及总汇判断，不当作设备执行。

原始日志保留 ignored `build/mobile-use-authorization-*.log`。Consumer/Developer APK及Developer设备测试APK按既有构建路径生成。设备、真实模型：not requested；未启动、操作或安装到模拟器/手机，未修改系统权限。仍需后续当前授权的真实后端恢复及工具任务验证，主机通过不能替代该验收或关闭历史真机黑屏/ANR边界。
