# 菜单与设置层级统一（2026-10-04）

## 范围

所有者要求：设置与工作、配置统一为点击展开子栏目，梳理分类、层级、风格及重复和分散的配置入口。
保留此前明确要求的模型独立入口及其位于工作之前的顺序，参见[模型入口记录](navigation-model-entry-2026-10-01.md)。配置归属同步到[授权 ADR](../../adr/permissions/001-session-authorization.md)。

## 最终结构

| 一级入口 | 子栏目或用途 |
| --- | --- |
| 新建、当前会话、全部会话 | 保留会话导航 |
| 模型 | 独立进入 API、订阅、本地模型配置 |
| 工作 | 任务、成果、Git、文件、浏览器 |
| 配置 | 扩展、准备与能力 |
| 设置 | 通用、Agent 默认值、权限与安全、存储与清理、诊断与审计 |
| 终端 | 独立打开终端 |

工作、配置、设置使用相同的展开交互、缩进、选择样式与展开状态说明；即使分组只有一个子栏目也不直接跳转。展开状态可恢复，进入嵌套页面自动展开对应分组；系统权限页只高亮权限与安全，避免同时选中通用。

## 重复与分散入口的处理

- 删除设置聚合页的二次跳转按钮，侧栏直接进入对应页面。通用集中语言与关于；Agent 默认值集中运行控制与 Goal 默认值。
- 存储与清理成为直接可滚动页面，取消先打开设置再弹窗的层级；保留统计失败、刷新和部分统计状态，清理仍需原有确认。
- 全局权限与安全保留新会话默认权限、全局工具可用性和系统安全设置。移除其中重复的当前会话授权编辑及 Mobile Use 控制，改为进入会话设置；原有会话设置保留完整操作。导航不改变授权或启动设备控制。
- 模型和扩展的全局安装配置，与会话中的选择/启用属于不同范围，继续保留。准备与能力展示就绪状态、能力状态并链接实际配置，未增加第二套配置数据；运行环境管理继续在其下。
- 抽屉底部高级配置开关是同一配置状态的快捷入口，保留原有风险确认。存储页面不合并模型、工作区等资源的所有删除操作，以保留各资源的引用和活动状态保护。

本轮统一导航容器和层级，不重做各功能页全部表单；配置与设置仍分别承担能力准备和应用偏好/安全管理。

## 验证

```text
./gradlew :app:testDeveloperDebugUnitTest --tests '*SettingsNavigationTest' --tests '*ShellRepositoryTest' --tests '*SessionPermissionEditServiceTest' --tests '*SessionPermissionServiceAvailabilityTest' :app:assembleDeveloperDebug :app:assembleConsumerDebug :app:compileDeveloperDebugAndroidTestKotlin :app:compileConsumerDebugAndroidTestKotlin spotlessCheck detekt --console=plain
bash scripts/check-docs.sh
git diff --check
```

- JVM 30 项通过：导航匹配 3、导航仓库 4、会话授权编辑 15、权限可用性 8；0 失败、0 跳过。
- developer / consumer Debug APK 构建、两种版本 AndroidTest Kotlin 编译、Spotless、detekt、文档及差异检查通过。
- 修复验证中发现的可空存储服务引用和新增文件命名问题，复跑通过。
- 补充设置只展开不导航、单子项分组仍展开、嵌套页面唯一选中用例；更新语言、存储、关于、返回导航及授权入口的界面用例。保留窄屏和大字体回归覆盖。

初次主机验证时设备状态为 `not requested`；随后所有者明确要求“帮我启动模拟器测试”，完成下述本次设备验证。

## 所有者授权后的模拟器验证

设备状态：`passed`（下列限定范围）。独立只读实例 `Helix_Main_Verify_API36` / `emulator-5576`，Android 16 API36、arm64-v8a、420 dpi，Developer Debug。使用项目 `HelixAndroidJUnitRunner`，通过 `scripts/run-owned-emulator.py` 运行：

| 用例类 | 通过数 |
| --- | --- |
| GroupedNavigationDeviceTest | 3 |
| IaAuthorityDeviceTest | 2 |
| HierarchicalNavigationDeviceTest | 5 |
| StorageUsageDeviceTest | 1 |
| StorageUsageNavigationDeviceTest | 1 |
| AboutHelixDeviceTest | 2 |
| AppLanguageDeviceTest | 6 |
| AutomationSettingsAuthorizationDeviceTest | 1 |

结果 `OK (21 tests)`，运行 60 秒。包含短窗口/大字体下子项可达、展开不跳转、嵌套页面选中、返回层级、存储错误后刷新、语言切换，以及经全局入口进入会话授权后的应用范围与全手机授权检查。执行前修正一条仍点击已移除模型来源返回按钮的旧断言，改为验证该按钮不存在；未削弱弹窗返回与分类保留断言。测试 APK 构建和 Spotless 复跑通过。

首次尝试复用日常 AVD 名称启动只读副本被模拟器拒绝，没有执行测试；改用专用验证 AVD 后通过。仅清空只读测试实例的应用数据，完成后该实例已退出。原始结果、APK SHA-256、设备身份和关闭记录位于 ignored `build/menu-device-2026-10-04-r2/`。

已备份日常 `Helix_API_36` / `emulator-5554` 的原 APK，再覆盖安装同一 Developer APK并打开，未清除日常数据。实机截图核对设置展开后的五个栏目及滚动显示，保持设置菜单展开供所有者体验。保留硬件键盘及已有中文输入法配置，开启硬件键盘下显示输入法。截图与安装前 APK 位于 ignored `build/menu-device-daily-2026-10-04/`。

边界：本次设备验收为 API36 Developer，不代表 Consumer 设备、API29、真机或所有页面验收；未使用真实外部账号或发送模型请求。
