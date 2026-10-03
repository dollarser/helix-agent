# HXA-245 Mobile Use 插件悬浮首版：主机审查记录

日期：2026-10-03。范围：[HXA-245](../../development/tasks/HXA-245.md)。基线 `main / 0619409dd1d7`；原有 Provider/恢复审查及文档改动保留。本记录生成时未提交、未推送；后续提交状态见任务页和模拟器收尾记录。起始工作树与差异保存在本地忽略目录 `build/hxa245/baseline-status.txt`、`baseline.diff`，不能把整个 dirty checkout 都归为本次悬浮功能。

本文保留追加设备请求之前的主机交付结果；所有者随后要求全量模拟器测试，当前执行与修复见[后续模拟器记录](hxa245-emulator-validation-2026-10-03.md)。下文 `not requested` 仅指当时的验收边界。

## 实现与审查

- 插件所有权：`extensions/mobile-use` 的 `MobileUseOverlay`、`MobileUseOverlayWindows`、`MobileUseOverlayState` 和三语言资源拥有展示及交互。`extensions/plugin/PluginTaskHost` 是可信本机 UI 的通用端口；App adapter 复用现有 ChatService，按 Conversation 和 Turn 双重匹配，不读 DAO、不新增循环。
- 通道与权限：仅 Developer 原有 Mobile Use 注册处注入宿主端口；Consumer 保持空实现。使用既有 AccessibilityService 的 `TYPE_ACCESSIBILITY_OVERLAY`，不增加 `SYSTEM_ALERT_WINDOW` 请求。状态窗半透明、NOT_TOUCHABLE/NOT_FOCUSABLE；控制窗局部可点击且不抢焦点。
- 接管：原任务进入现有停止协议，Goal 复用暂停；进程内 fence 拒绝被接管任务重新绑定，不修改持久 Conversation grant。迟到/其他会话按钮不控制当前任务。已发出的动作不宣称回滚。
- 避让：截图/手势先隐藏两个窗口并有界等待两个渲染帧。隐藏超时/取消拒绝本次执行并释放隐藏 ticket；重叠操作引用独立 ticket。截图结束恢复展示；手势占用只由实际回调或明确未发出路径释放，超时 UNKNOWN 不提前宣告物理完成。关闭服务后迟到回调不能重新显示窗口。
- 生命周期：服务初始化与调用绑定同步，Android View 延迟到主线程创建，避免首个截图与异步创建悬浮窗口竞态。锁屏/运行态停止隐藏；新运行态不继承旧节点/frame。插件可用性从已发布工具的内存投影读取，避免 UI 轮询 Room。该投影只影响展示，实际授权仍由原链路检查。
- 修正过程中处理 SDK 不可用属性，改用公开节点 windowId；合并资源释放/抽出准入复核满足现有静态检查，不降低规则或删除用例。没有修改并行任务的既有文件内容。

## 主机验证

最终命令、结果及报告汇总在本地 `build/hxa245/`。运行入口为项目 Gradle、`scripts/check-all.sh` 和已有 `scripts/summarize-android-tests.py`；设备与真实 Provider 没有运行。

联合调用的任务列表：

```text
./gradlew :extensions:mobile-use:testDebugUnitTest :extensions:plugin:test :tools:automation:testDebugUnitTest :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest spotlessCheck detekt :extensions:mobile-use:lintDebug :tools:automation:lintDebug :app:lintConsumerDebug :app:lintDeveloperDebug :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest :tools:automation:assembleDebugAndroidTest
scripts/check-all.sh --source
scripts/check-all.sh --artifacts
```

五份最终 APK 的 SHA-256 位于 `build/hxa245/apk-sha256.txt`。JVM XML 使用已有汇总器按模块分别写入 `*-final.json`，未混合模块或设备结果。

| 范围 | 测试报告 |
| --- | --- |
| Mobile Use | 8 个新状态用例：身份匹配、接管、连续接管、重叠隐藏、切换 owner、服务关闭、终止状态 |
| Native Plugin | 44 个用例，含新增发布投影启停回归 |
| Automation | 105 个用例 |
| App Consumer | 1,077 通过、4 跳过 |
| App Developer | 1,174 通过、4 跳过 |

以上合计 2,408 次通过、0 失败、8 次跳过；两渠道重复方法按实际运行次数统计，不称为不同方法总数。跳过为各渠道 2 项 opt-in 外部 Connector 验收及 2 项缺少本地 Connector/WorkBuddy 样本。汇总器将含跳过的 App 报告标为 `INCOMPLETE`，不把它们改写为全量通过。

联合 Gradle 命令包含五组 JVM 测试、`spotlessCheck detekt`、Mobile Use/Automation 与 App 两渠道 Debug lint、两渠道 App/AndroidTest APK 和 Automation AndroidTest APK 构建，最终退出 0（`host-final-pass.log`）。`scripts/check-all.sh --source` 退出 0，覆盖文档、ADR、国际化和秘密扫描；`--artifacts` 退出 0（`artifacts-final.log`），验证实际制品渠道/运行时边界。初次制品检查受沙箱阻止读取 Gradle 缓存，已使用获准的主机权限重跑通过，不视为产品缺陷。

## 剩余验证边界

设备 **not requested**，AndroidTest 仅编译。实际透视/点击穿透、截图无悬浮像素、控制按钮返回前台、旋转/分屏/大字体、锁屏/服务重建与 OEM 后台回收需获授权后按任务清单验证。两帧隐藏是实现策略，不是当前设备通过证据；真实 Provider、FFmpeg/PRoot 媒体端到端亦未运行。前台服务降低后台回收风险，不保证永久存活。首版没有完整悬浮聊天输入或拖拽布局。
