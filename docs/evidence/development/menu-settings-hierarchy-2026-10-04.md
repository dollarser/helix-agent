# 菜单与设置层级统一（2026-10-04）

## 范围

所有者要求：设置与工作、配置统一为点击展开子栏目，梳理分类、层级、风格及重复和分散的配置入口。
保留此前明确要求的模型独立入口及其位于工作之前的顺序，参见[模型入口记录](historical-client-ui-validation.md#会话模型与导航)。配置归属同步到[授权 ADR](../../adr/permissions/001-session-authorization.md)。

## 初次结构（同日后续调整见文末）

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

已备份日常 `Helix_API_36` / `emulator-5554` 的原 APK，再覆盖安装同一 Developer APK并打开，未清除日常数据。模拟器截图核对设置展开后的五个栏目及滚动显示，保持设置菜单展开供所有者体验。保留硬件键盘及已有中文输入法配置，开启硬件键盘下显示输入法。截图与安装前 APK 位于 ignored `build/menu-device-daily-2026-10-04/`。

边界：本次设备验收为 API36 Developer，不代表 Consumer 设备、API29、真机或所有页面验收；未使用真实外部账号或发送模型请求。

<a id="history-bundled-plugin-discovery-2026-10-04"></a>

## 应用存储与内置插件入口澄清（2026-10-04）

应用存储只统计 Helix 自有数据，不是手机文件管理、应用总占用或可释放空间；会话删除不自动删除工作文件，浏览入口不授予 Agent 权限。JGit 当时只读，差异不等于 Agent 修改归属，没有实现自动快照/回退。Mobile Use 已有注册和持久启用，问题是默认市场页不可直接发现。扩展页因此在标签前显示真实内置插件，移除连接器列表重复卡片，沿用启用/修复及会话后端设置；未注册的渠道不造虚构卡，错误可重试、取消仍传播，启用不授权或执行。

两渠道 Debug APK、AndroidTest Kotlin、Spotless/detekt、文档/差异检查通过；新增默认扩展页导航用例仅编译。设备 `not requested`，无账号、安装或模型验收，不能继承此前安装。原日志 `build/bundled-plugin-*-verification.log`。

<a id="history-model-source-navigation-2026-10-04"></a>

## 模型入口与配置导航优化（2026-10-04）

空 Provider 列表及搜索无结果时仍提供 API/本地模型配置入口；订阅按渠道能力显示。导航参数直达分类，非法/不支持分类回退 API，离开会话前保存草稿。导航不会选择模型、登录、下载或探测。名称统一“模型”，去掉重复 Provider 标题、分类首页及无效返回层级；保留三类空状态与主操作、滚动来源入口和可换行分类按钮，连接验证/账号授权不变。设计归 [Provider ADR](../../adr/provider/001-models-and-connection.md)。

26 项 JVM（渠道3/模型选择11/配置8/导航4）零失败跳过；两渠道 Debug/AndroidTest 编译和静态/文档检查通过。首轮长行、格式和导航复杂度失败经修正复跑；工作树并行 Mobile Use 不计本片。新增 `ModelSourceNavigationDeviceTest` 四项仅编译，既有未测试订阅禁用用例保留，设备 `not requested`，不能称点击/视觉通过。

<a id="history-session-work-navigation-2026-10-04"></a>

## 会话任务、成果与 Git 入口优化（2026-10-04）

会话任务原先混入全局 backgroundTasks，现按 sessionId 筛选，缺失身份返回空而不回退全局；切会话重置弹窗，打开刷新持久事实。本会话成果零数量仍有入口，附件不是成果。全局改名“全部任务/全部成果”；只有 COMPLETED 执行算完成结果，失败/取消/待核查仍在全部任务，已写文件继续独立保留来源。Goal/计划不是全部有会话归属，不能整体套会话过滤。

Git 使用内置 JGit，读取分支/暂存/未暂存/未跟踪及差异，不调用 PRoot/git CLI，不含 clone/提交/推送/分支管理/自动备份；当时发现根仓库或首个直接子仓库，不是完整多仓库选择器。入口移到会话更多菜单“工作目录 Git（只读）”，经草稿保存固定进入时会话和目录，刷新不随他会话换仓库。见 [Git ADR](../../adr/workspace/003-git-boundaries.md)。不改变权限、执行归属或文件。

44 项 JVM（会话2/面板23/Git15/差异4）零失败跳过，两渠道 Debug/AndroidTest 编译和静态/文档检查通过；长行及函数限制修正后复跑，未放宽断言。设备路径/零成果断言仅编译，设备 `not requested`；没有远程 Git、模型、账号或安装验收。

<a id="history-settings-navigation-simplification-2026-10-04"></a>

## 设置导航归位（2026-10-04）

后续同日经 Owner 确认，将上文初次“配置”分组移除，模型/扩展/终端成为直接入口，工作/设置仍展开。“准备与能力”中转页移除，准备检查/能力状态进入诊断与审计的故障排查，运行环境为设置独立子入口；诊断和操作记录分区，保留脱敏预览与有界查询。历史 setup/readiness/capabilities/runtime 路由当时保持可达、选中真实归属，准备可重复检查并走既有修复入口；不新增启动拦截、强制首次流程、后台采集、上传或授权，会话 Git/全部任务/成果/文件归属不变。

3 项导航 JVM 零失败跳过，两渠道 Debug/AndroidTest 编译、Spotless/detekt、文档/差异检查通过。导航复杂度、审计函数长度和前轮插件格式失败经简化/归并修正，未改全局规则。设备路径用例仅编译，`not requested`，无安装或真实账号服务结果。日志 `build/settings-navigation-final.log`、`build/settings-navigation-docs.log`。
