# 插件选择、任务停止与系统授权架构精简

日期：2026-10-05。按所有者“优化上述问题”的要求，在现有未提交工作树上收敛四项架构问题；不提交或推送，不提供旧接口/数据兼容层。

## 实现

- 会话启用只保存在 Room 通用插件选择中，增加选择身份；重复启用保持身份，关闭再开启生成新身份，默认选择也生成身份。删除 core/policy 的 Mobile Use 配置存储；插件只保存全局配置，执行范围从会话选择与配置版本推导。避免两个存储分别成功/失败造成开关与执行范围不一致。
- 通知停止通过 PluginTaskHost 停止绑定的任务，不更改会话插件开关。运行身份包含 Turn 区分；旧通知不影响同会话的新任务。原始执行、取消、结果未知和审计规则保持。
- Helix 的 Root 授权请求不再顺带绑定 Root 服务。Mobile Use 和 Root 诊断通过 DeviceAccess 复用已授权的 shell 建立各自连接；缺少现存授权通道则拒绝连接，不从插件连接入口启动授权请求。不同 consumer 的断连仍隔离。
- HelixPlugin 统一贡献内置指导、优先工具、数据来源、派发及图片范围和配置校验。聊天请求、工具排序及派发通过通用注册表解析，移除 Mobile Use 专用提示词与派发接线；仅选中且已发布、契约匹配的插件可贡献上下文。管理页只读查看仍可用。

系统授权仍位于“设置 → 权限与安全 → 授权管理”；插件全局范围和连接位于“扩展 → Mobile Use → 插件设置”；会话只选择是否使用插件。系统授权不是第二次模型授权，也不会自动选择所有插件。

## 验证与边界

最终联合 Gradle 检查通过（1 分 22 秒）：6 个相关模块共 1916 项单元测试，1912 通过、4 个既有条件跳过、0 失败；双渠道 debug APK/AndroidTest APK 及 Mobile Use AndroidTest APK 构建通过，双渠道 lint、detekt、spotless 通过。合同测试 11/11，双渠道 APK 运行时与渠道边界检查、文档检查通过。新增回归覆盖单一配置存储、关闭再开启的身份变化、失败写入拒绝继续使用、旧通知与新 Turn 隔离、Root 授权不绑定/连接不申请授权，以及未选中或停用插件不注入执行上下文。

Room 选择生命周期与实际系统界面测试仅编译。本轮设备 **not requested**，真实模型 **not requested**；没有启动/使用模拟器、安装 APK 或调用真实账号。缓存 Root 授权不能证明管理器实时状态，实际设备/OEM 撤权与界面行为不计为本轮通过。

## 复验

```sh
./gradlew :extensions:plugin:test :extensions:mobile-use:testDebugUnitTest :core:policy:test :core:storage:testDebugUnitTest :tools:root:testDebugUnitTest :app:testDeveloperDebugUnitTest :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest :extensions:mobile-use:assembleDebugAndroidTest :app:lintDeveloperDebug :app:lintConsumerDebug detekt spotlessCheck --offline --console=plain
python3 -m unittest scripts.tests.test_mobile_use_contract
python3 scripts/verify-integrated-runtime-apks.py
bash scripts/verify-variant-boundaries.sh
bash scripts/check-docs.sh
git diff --check
```

主机日志位于忽略的 `build/architecture-*.log`。首轮检查发现长查询格式、静态规则问题及清理方法后残留的能力摘要引用，已修复并重新执行完整检查；不把失败尝试记为通过。

## 追加设备验证（2026-10-05）

所有者随后明确要求“安装到模拟器测试”。已覆盖安装到日常 `Helix_API_36` / `emulator-5554` / Android API36 / developer debug；未使用真机或真实模型。

- 应用回归最终 **10/10**：插件注册/停用与系统权限独立、Room 选择身份的重复启用/关闭重开/会话删除、插件内容展开、全局设置及系统权限入口。
- 独立 Mobile Use 设备测试 **4/4**：透明覆盖窗、接管、返回原会话、控制窗隐藏时仍可通过停止接口取消绑定任务。任务宿主使用测试夹具，不声称验证真实模型运行中的取消。
- 首轮 10 项中的 1 项失败来自测试清理重复删除已删除的会话；改为仅在会话存在时清理，保留原业务断言，整组复跑通过。
- 更新测试 APK 构建及 detekt/spotless 通过；无障碍系统设置在测试后恢复为测试前的关闭状态。Root 管理器授权和真实 Root 服务连接未在这台设备执行，不将主机测试计为设备通过。

应用 APK SHA-256：`bb8920220611b19179694ccd7b2630e30526b58ae8884531f60e74225c05207b`。

本次数据库增加选择身份，触发现有开发版同版本结构不兼容重建逻辑。升级前有 14 个会话，升级后数据库重建；文件目录未清理。升级前应用 APK、数据库/WAL 和 shared_prefs 已备份至忽略的 `build/plugin-architecture-device-2026-10-05/before-install.apk` 与 `before-install.tar`，不提交其中用户数据。该备份可用于回退旧版本，不是新版本数据迁移通过。

原始结果位于同目录 `app-tests-final.log`、`overlay-tests.log`、`static.log`。执行使用 `adb -s emulator-5554 install -r`，随后以 `am instrument -w -r -e class` 指定上述应用测试和 `MobileUseOverlayDeviceTest`；未执行会删除模型配置的其他测试。
