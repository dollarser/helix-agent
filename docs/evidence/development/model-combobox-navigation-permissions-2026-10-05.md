# 模型组合框、工具菜单与权限页整理（2026-10-05）

## 所有者要求

服务目录在模型输入框内下拉选择，支持手输；将终端、文件管理器和浏览器合为扩展与工作之间的一级菜单；应用存储、诊断与审计移入工作；降低权限与安全页的理解成本。

## 实现

- 新建 API 来源与模型管理的添加输入共用可编辑下拉框。显式获取服务目录后在框内选择，输入文字筛选候选；手动输入不发网络请求。候选有界显示，继续输入可搜索整个已获取目录。没有列表时不显示空下拉箭头。
- 新建来源只保存当前输入框中的模型标识，删除原表单独立的多选状态与对应校验分支，避免输入值和隐式选中项冲突。已有模型管理仍可管理多项模型；显示名称与调用标识分离的规则保留。
- 抽屉顺序：会话、项目、模型、扩展、工具、工作、设置。工具按终端、文件管理器、浏览器排序；工作包含任务、成果、应用存储、诊断与审计。应用存储/审计仅移动导航归属，原路径保留；诊断子页面仍高亮唯一的审计入口。
- 权限页先解释系统授权和 Agent 操作规则的区别；系统授权使用独立卡片入口，新会话默认规则采用单选行并说明适用范围，当前会话保留跳转入口；全局工具按类别两级展开。
- 运行模式、局域网地址和敏感内容发送规则采用统一的折叠卡片，默认收起。系统授权子页的设备能力、通知/日历等授权采用同样卡片风格，Root 详情折叠。没有改变权限预设、服务调用、运行模式风险确认、执行准入或审计事实。

## 验证

主机日志 `build/combo-navigation-final.log`：

- `:app:testDeveloperDebugUnitTest`：通过，覆盖新建来源模型输入校验、已有显示名称与候选管理以及菜单路由唯一性等。
- `:app:assembleDeveloperDebug`、`:app:assembleConsumerDebug`：通过。
- `:app:compileDeveloperDebugAndroidTestKotlin`：通过；更新组合框选择/手输、菜单移动、折叠权限选项的设备用例前置操作。
- `detekt`、`spotlessCheck`：通过。首次完整门禁仅剩模型校验文件排版问题，修正后完整重跑通过。

本轮设备、真实服务 `not requested`。没有使用模拟器、安装新 APK、真实账号或模型请求；设备测试仅编译，不能记为设备通过。此前回合的模拟器结果不覆盖本次交互。未提交、未推送。

## 随后授权的模拟器回归与提交收尾

所有者随后明确要求安装、测试、修复并提交到 GitHub。本次仅使用已运行的日常 `emulator-5554`（API36、Developer、`com.helix.agent.developer`），没有启动第二台模拟器。覆盖安装前备份 databases/shared_prefs 到本地忽略目录 `build/ui-final-device/before-config.tar`；测试未清空数据库、删除来源或修改系统授权。

### 发现与修正

- 实际权限页的默认模式单选行高度仅约 24dp，选项拥挤；改为每行至少 48dp，并增加按钮与文字间距。最终 UI 层级中四行均为 126px（420dpi，即 48dp），见 `build/ui-final-device/touch-targets.log` 和 `final-permissions.png`。
- 移除“Mobile Use 在会话设置中管理”的旧提示，改为本会话操作规则可独立调整，避免混淆插件全局设置与会话选择。

### 最终结果

- `:app:testDeveloperDebugUnitTest`、`:runtime:cli-app:testDebugUnitTest`、`:app:assembleDeveloperDebug`、`:app:assembleConsumerDebug`、`:app:assembleDeveloperDebugAndroidTest`、`detekt`、`spotlessCheck` 全部通过，日志 `build/ui-final-corrected-host.log`。
- 使用 `adb -s emulator-5554 install -r` 更新应用和测试包。最终 APK SHA-256 为 `86fcea992168d90d1b2e5cde7a810ac6bf5dabca6ceda6b29f08da66efdf890a`；设备 base.apk 与本地产物一致。
- 运行 `adb -s emulator-5554 shell am instrument -w -r -e class com.helix.app.ui.ProviderSettingsFormDeviceTest,com.helix.app.ui.GroupedNavigationDeviceTest,com.helix.app.ui.ToolAvailabilityGroupsDeviceTest,com.helix.app.ui.ProviderModelPickerDeviceTest com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner`：最终 **16/16 通过**，日志 `build/ui-final-device/final-tests.log`。首轮 10 项及补充 6 项也通过；修正布局后在最终包上完整重跑。
- 覆盖模型手输/下拉选择、协议切换、可选 Key、错误定位、未登录订阅隐藏、模型身份选择、短屏大字体导航、权限二级折叠。均为本地 Compose 夹具，不使用真实服务。
- 实际应用人工操作核对工具顺序、工作下的应用存储/审计、设置目录、授权管理入口、任务清单空状态；最终权限页复核行距和文案。没有改变真实授权或发送消息。
- 文档、多语言及 `git diff --check` 通过。提交包含本轮之前尚未提交的会话更多、Provider 显示名称/表单、订阅登录提示及此次菜单/权限优化；临时脚本、设备备份与截图不提交。

设备状态为本次 API36 定向验证 **passed**；API29/34、真机、Consumer 设备运行、真实模型/账号登录及真实服务目录请求均未验证。本次不把界面夹具通过等同于这些外部链路通过。Git 提交及远端身份以实际 Git 历史为准。

## 追加调整：全局设置与当前会话入口分离

所有者随后要求权限与安全只保留全局设置和新会话默认设置。删除当前会话设置按钮、相关提示及无会话空状态；移除页面的 ChatService 和会话跳转回调参数，全局设置组件使用无会话的控制器，不读取当前会话配置。会话页面的设置入口与授权逻辑不变，三语言未使用提示同步删除。

主机 `:app:testDeveloperDebugUnitTest`、双渠道 APK、`detekt`、`spotlessCheck` 与 `:app:compileDeveloperDebugAndroidTestKotlin` 通过，日志 `build/global-permissions-only-host.log`、`build/global-permissions-only-followup.log`。现有页面归属回归新增默认权限和全局工具仍存在、当前会话入口不存在的断言，仅编译。本次设备验证 `not requested`，未更新模拟器、提交或推送；前述设备通过不覆盖这次追加调整。

## 追加调整：授权分类与可申请权限覆盖

所有者要求删除局域网/敏感内容规则展开后的重复标题，重新梳理授权管理，并支持应用列表等可申请权限。

| 分组 | 内容和边界 |
| --- | --- |
| 通知与日历 | 发送通知、写入日历、通知读取；日历实际只申请 WRITE_CALENDAR，纠正原“读取与写入”标题 |
| 文件访问 | API30+ 且声明该权限时展示所有文件访问状态和系统入口；文件管理器中的授权目录、Agent 全局文件目录分开，系统授权不扩大 Agent 范围 |
| 应用列表 | 展示包可见性状态；Developer 已声明 QUERY_ALL_PACKAGES，Consumer 遵循系统可见性过滤；提供系统设置检查厂商额外开关 |
| 设备控制 | 无障碍、Shizuku、Root 各用独立卡片；沿用宿主状态/申请机制，Shizuku 区分申请权限与打开服务管理器 |
| 其他系统权限 | 从安装包读取其余声明权限：dangerous 权限提供显式申请，自动授予/签名限制的权限默认折叠展示事实；过滤本应用内部接收器签名权限以及 API30+ 不适用的旧存储声明 |
| Root 只读诊断 | 仅 Advanced，默认折叠；说明受限软件包、进程、日志和授权目录读取用途，不充当系统 Root 总开关或 Mobile Use 开关 |

所有卡片统一标题、简短用途、实时状态和操作按钮；状态在返回页面/授权结果后更新。没有新增 Manifest 权限，不在页面进入时申请权限、启动诊断会话或启用插件。现有查询/Root 状态读取保持原行为。

Android 依据：[权限类型](https://developer.android.com/guide/topics/permissions/overview)、[特殊权限申请](https://developer.android.com/training/permissions/requesting-special)、[包可见性声明](https://developer.android.com/training/package-visibility/declaring)。QUERY_ALL_PACKAGES 通常不是运行时弹窗权限，不能把“可见性声明”写成保证读取所有应用；OEM 额外限制仍须真机核对。

回归增加文件子页返回、可选能力空入口隐藏、自动权限折叠清单和无自动授权断言；敏感内容规则展开后标题仅出现一次。设备测试仅编译，本次设备、真实服务 `not requested`，旧 API36 结果不覆盖这次改动。

最终主机日志 `build/permission-management-verified-host.log`：`:app:testDeveloperDebugUnitTest`、`:app:assembleDeveloperDebug`、`:app:assembleConsumerDebug`、`:app:compileDeveloperDebugAndroidTestKotlin`、`detekt` 和 `spotlessCheck` 全部通过。首次整套检查仅静态复杂度未通过，提取文件授权卡片组合并简化条件后完整重跑通过；格式检查的行长问题已修正。文档、多语言与差异检查通过，不外推为设备交互验收。
