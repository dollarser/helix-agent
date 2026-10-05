# Mobile Use 插件配置、内置 skill 与内容查看

日期：2026-10-05。范围：所有者要求插件全局配置、会话仅启停、集中授权、内置 skill 自动注入及插件内容展开查看。对应 [ADR-CONNECTORS-003](../../adr/connectors/003-ownership-and-installation.md) 与 [ADR-PERMISSIONS-001](../../adr/permissions/001-session-authorization.md) 同日决定。

## 实现

- Mobile Use 自带 `skills/android-ui-task/SKILL.md`，独立技能目录删除重复项。实际模型工具绑定属于 Mobile Use 才注入该指南；指南作为外部 SKILL 内容，不能授予权限。平台执行仍复用 `tools/automation`，不增加独立任务执行器。
- 扩展管理中点击插件展开工具和 skill；再点击查看工具说明或 skill 正文。禁用原生插件仍可查看内容，不重新发布工具。可移植插件展示已发现的连接器工具名称和本地 skill 正文，未发现的远端工具不凭空填充，也不为查看自动联网。
- Mobile Use 全局应用范围在插件设置保存，会话仅开关。保存不选择会话；安装不再默认加入所有会话。全局范围变化使已启用会话的旧授权身份失效，跨会话和关闭后重启保持独立身份。失败的全局持久写入在当前进程拒绝授权读取。
- 授权管理集中放无障碍、Shizuku、Root、通知/日历等系统入口，并链接文件管理器的目录管理。独立 Root 诊断许可放在次级展开；系统许可与 Agent 应用范围仍分离。

## 验证及边界

主机命令：`:core:policy:test`、`:extensions:plugin:test`、`:extensions:skills:test`、`:extensions:mobile-use:testDebugUnitTest`、`:app:testDeveloperDebugUnitTest`、双渠道 `assemble*Debug` / `assemble*DebugAndroidTest` / `lint*Debug`、`detekt`、`spotlessCheck`。最终日志：忽略目录 `build/plugin-inspection-final-pass.log`，BUILD SUCCESSFUL（2m34s），上述主机测试、双渠道构建、测试 APK 编译与静态检查全部通过。Python Mobile Use 合同 10 项通过；文档检查通过（738 Markdown 文件、230 HXA），`git diff --check` 通过。

新增测试覆盖全局范围变更、失败关闭、会话身份隔离、查看禁用 skill 不获得执行资格、禁用插件查看不发布工具及插件所属指南资产。更新并编译全局设置和会话启停界面测试，新增插件展开后工具说明与 skill 正文出现的界面测试。

初次检查发现独立 skill 数量断言需随移除同步、格式错误与静态函数数量/返回数量/未用参数问题；已调整清单断言、提取授权身份计算和合并只读内容投影，移除失去调用者的参数。并行检查期间有格式中间态失败，最终固定源码后重新检查；不以中间日志代替最终结果。

设备与真实模型：not requested。界面测试 APK 编译不等于界面实机通过；未启动模拟器、未安装 APK、未调用真实模型，不对安装任务自主完成率作新结论。

## 后续优化：授权状态与执行连接

同日所有者继续要求优化权限复用与状态管理。Root 区域现在分列应用授权缓存、Mobile Use 服务连接，区分请求中、连接中、已连接、断开/丢失；已有授权时显示连接服务，连接期间禁用重复操作。Root 诊断入口更名为连接诊断服务并说明独立生命周期。Shizuku 文案纠正为已授权、执行时连接；插件设置和授权管理均显示 Root → Shizuku → 无障碍的实时优先路径摘要，无屏幕后端时说明仍可使用部分查询。

只增加被动授权缓存观察与展示，不自动建立 Root 连接、不增加新的权限来源，不将 `ui.*` 与平台执行模块机械合并。新增单测验证被动观察不会申请/绑定服务，授权缓存不等于连接、撤销不会显示就绪、连接期间防重复操作和后端优先/部分可用状态。初次静态检查发现状态投影函数复杂度超限，已提取授权文案投影且未放宽门禁。最终 `build/backend-status-host-final.log` 为 BUILD SUCCESSFUL（1m1s）：Root、Automation、App Developer 单测，双渠道 APK、测试 APK 编译、Lint、Detekt 与 spotlessCheck 通过；`git diff --check` 通过。本次设备与真实模型仍为 not requested。

## 后续修复：已注册不等于可勾选

所有者报告“本次可用能力”中的 Mobile Use 显示“已有可用组件”却无法勾选。原因是列表只展示工具注册状态，而选择写入路径还检查全局应用范围；没有保存范围时抛出 `MOBILE_USE_NOT_CONFIGURED`。

列表现在通过插件注册表的只读 `selectionError` 查询与写入路径相同的配置条件。缺少范围时提前显示具体配置路径，关闭首次勾选并提供该行的扩展配置入口；已有选择仍允许取消，不因配置缺失将用户锁在启用状态。可用状态改为“可用于本会话，具体操作按授权范围执行”。首任务准备入口复用同一缺失配置事实。保存范围不自动选择插件，也不授予系统权限；实际选择时仍再次验证，不能用界面预检查代替执行边界。

主机覆盖配置错误优先于注册就绪、禁用/缺失状态及注册表查询不修改选择。新增设备回归用例覆盖缺少范围时按钮不可选、配置入口可用、保存并重新打开后可选且未自动选中。设备用例本次仅编译，设备和真实模型 not requested。初次静态检查指出面板复杂度超限，提取单行选择组件后通过，未放宽阈值；最终 `build/mobile-use-selection-final.log` 为 BUILD SUCCESSFUL：App Developer、Plugin 单测，双渠道 APK、Developer AndroidTest 编译、Detekt 和格式检查通过。文档、多语言和 `git diff --check` 通过。

### 随后授权覆盖安装与提交

所有者随后要求将最新版安装到模拟器并提交代码。已在日常 `Helix_API_36` / `emulator-5554` 覆盖安装 Developer APK，并通过冷启动与首页截图检查；未调用真实模型，未将启动检查视为新一轮完整设备回归。APK SHA-256：`9f5afdfbbc35d17d4e207c26a5f92c86508a55699cc9b43c8d6b7135a916d8ca`。

升级前已停止应用并保存原 APK、数据库（含 WAL）、SharedPreferences、应用可读文件及 no_backup。备份归档已可列出并提取数据库；Runtime 内指向系统分区的不可读路径未归档，具体见忽略目录 `build/install-latest-2026-10-05/backup.log`。开发数据库 identity 从 `030d485375e875718e59a97dc6ed31f5` 变为 `c34065205d0987bb3b495a2f4a663c43`，触发既定重建流程，持久会话数从 1 变为 0；数据库配置清空，数据库外文件保留。备份位于同目录的 `before.apk`、`before-data-verified.tar`，仅本地保留，不提交用户数据。
