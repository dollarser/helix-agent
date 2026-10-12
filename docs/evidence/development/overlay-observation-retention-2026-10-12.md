# 悬浮窗观察保留与隐藏确认：实现与有界验证

日期：2026-10-12。Owner要求尝试纯节点观察保留浮窗，并分析截图/坐标手势的隐藏延迟及频繁显隐；本轮单独授权同一OnePlus API34合成界面的临时无障碍/悬浮窗验证。未授权新增真实模型测试，本轮没有调用Qwen、Root或真实账号，也没有使用日常模拟器。

## 当前实现

注册期根据受信任工具名允许ui.snapshot/find/wait/device/apps保留浮窗，其余工具默认仍隐藏。保留原许可、取消、deadline、原任务及资源租约检查；窗口选择排除自有浮窗，后台只读取缓存的窗口ID，不创建主线程节点查询。三次有界快照重新采集继续保留。

截图及动作继续移除浮窗、等待两帧，并在主线程确认自有View均已脱离窗口；失败/超时拒绝执行。恢复显示采用150毫秒合并窗口，减少紧邻操作之间闪现；审批绕过显示延迟，但不能绕过真实物理操作的隐藏租约。实际手势仍持有隐藏租约至Android最终回调，不因等待取消提前释放。

对照[agent-device观察/截图分层](https://github.com/callstack/agent-device)和[AOSP AccessibilityWindowInfo](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/view/accessibility/AccessibilityWindowInfo.java)的无障碍覆盖窗及窗口焦点语义，采用按实际观察/物理操作需求分类、验证目标窗口和独立核验结果的原则。读取日期2026-10-12；独立实现，不复制竞品代码、不引入框架。Root/Shizuku观察仍服从其原物理资源约束，不承诺所有后端永不隐藏。

## 验证、失败尝试与边界

ignored目录`build/overlay-observation-20261012/`保留本轮原始日志。最终`host-final-r4.txt`全仓test/spotlessCheck/detekt、双渠道lint/APK及App/SDK测试APK通过，1m03秒；新版包装/显示状态JVM合计20项通过，其中新增4项覆盖纯观察分类、取消/撤销、恢复合并边界和审批不绕过物理抑制。此前格式/方法长度门禁失败已通过提取测试辅助函数解决，原门禁保留。source最终检查另记录本轮文档/ADR/三语言/secret结果。

SDK首次9项中2项失败：隐藏像素断言及尚未准备好的窗口ID断言。准备窗口ID后两项定向通过、三次单独像素测试通过，随后`sdk-diagnostic-full.txt`9/9通过；十次原生观察保留相同浮窗窗口、没有选择浮窗节点，嵌套隐藏期间四次整屏截图无浮窗像素，生产无障碍窗口截图SAVED及100毫秒真实手势成功，独立click计数增加1。手机show_touches=1；首个像素失败尚未稳定复现，不证明它一定是触摸指示或一定是浮窗残留。最终夹具将像素检查放在触摸注入前，保留原像素和穿透断言，仅在失败时保存合成截图；最终制品`sdk-final-device.txt`9/9、15.527秒通过，未skip。包括物理回调前不释放、停止、仅导航、会话可见性及普通悬浮窗撤权回归。

App新增实际Dispatcher输入测试，准备阶段曾后端不可用、单独重跑绑定超时，均未到输入动作，不归类新STALE_TOKEN。将夹具权限设置/恢复改为读取shell输出直至命令完成，避免关闭描述符后立即开始下一步后，两项18.343秒通过；最终主APK安装后`app-final-device.txt`2/2、18.476秒通过，两个原合成Turn各十次Dispatcher观察成功读回中文，真实Dispatcher set_text=SUCCEEDED；没有使用真实模型或放宽过期节点判断。完整instrumentation状态由原严格解析器核实：SDK9方法、App2方法，不据shell退出或单独OK字样推断通过。

更新前已另存当前Developer APK和库，旧主APK SHA256为350f69d8157821b9df76d2de35c0bae5de6b3bf199aed8ff2aabed7d5100acaa。最终安装工作树Developer APK为4f206788a9d1de79fb561e0c4cd1129e56841a783f08a31bf37b59df7fe10ce7，从手机读取匹配；App测试APK为96d8d8b0e4094039561d2ce4c4576ed2ef3cfcddc79a90fd0d36dfc10816a987，SDK测试APK为af6edf879e92e19640ec7c5af5444c53da94acfb68cb7e29efc5f5ed05f17881。中间安装的1ddc385f制品及失败日志保留，不作为最终安装身份。更新前后Room identity均为eb439f0c60ced11912eaac8b50dada5b，integrity=ok，1会话/0Turn；未重建数据库，未删除非夹具数据。

中途执行服务曾断连、自动恢复超时，留下进行中记录；连接恢复后读回构建成功、完成上述最终复测。最终无障碍组件null/开关0，Main悬浮窗AppOps恢复default，无新ADB reverse；返回Helix，合成模型/会话按夹具清理。未修改show_touches、Root管理器或日常模拟器。本轮改动只交付上述同机有界范围。

两帧及View脱离确认不等于Android公开API提供了所有OEM的合成器/输入队列全局完成证明；不能从本次截图/短按成功外推永不出现延迟。后续若有新的像素残留，保留合成截图和窗口时间线再定位，不放宽安全准入。
