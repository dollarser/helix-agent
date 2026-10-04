# 抖音安装诊断与统一扩展交互

Status: host and bounded device checks passed; autonomous model attempt timed out
Date: 2026-10-04
Related HXA: HXA-244, HXA-129
Affected modules: app, tools/files, tools/automation

## 范围与事实

所有者要求修复安装抖音失败、在模拟器验证，并统一扩展安装和会话启用交互；明确允许一次使用当前模型的真实任务。工作树已有大量未提交工作，本记录只覆盖以下增量。

原失败记录包含三条不同边界：旧 APK 的 `ui.apps` 被 ACCESSIBILITY_AUTOMATION 能力门槛拒绝；随后模型转向 Linux；写入不存在的 `work/` 父目录触发 IllegalArgumentException，使调用进入未知结果核查。核查另有 FGS_SERVICE_LOST。不能从最终通用提示推断账户额度不足。

## 修复

- 写文件发布前检查父目录，缺失时给出 `files.mkdir` 或已有目录的可操作提示，不把尚未开始的发布误记为未知副作用。
- Mobile Use 默认工具曝光以部分可用的应用查询判断，并保留 `ui.apps` 与 `ui.find`；原有工具数量上限不扩大。
- 前台服务正常停止与新请求交错时重新建立通知，异常销毁仍中断任务；重新启动被拒绝也保持明确失败。此为源码确认的竞态修复，不声称已证明原设备每次中断均由此造成。
- 恢复失败文案不再无证据归因于模型、授权或额度。
- 语义观察不可用时明确提示独立检查 `ui.device`、截图和可用手势；授权拒绝、敏感界面不获得绕过提示。模型决定流程，不新增安装专用状态机。

## 统一扩展交互

沿用安装目录、组件所有权、会话选择和权限四个既有边界，见 [连接器 ADR](../../adr/connectors/003-ownership-and-installation.md)。

- 扩展首页默认“已添加”，另有“发现”；导入插件、MCP 配置和技能收进“添加扩展”，已安装项可搜索。
- 插件为管理单位，独立安装的技能单独显示，插件拥有的技能不重复展示。
- 安装后可进入“本次可用能力”，统一选择插件与独立技能；会话输入入口不再单列技能选择。
- 卡片提供配置状态、使用和管理；组件工具列表默认折叠。配置完成不等于获得执行权限。
- Mobile Use 保留内置插件的系统能力设置入口，实际操作仍逐次经过授权与执行管线。

## 设备证据与限制

本次使用 emulator-5554，API36 ARM64，Developer；未使用真机、未登录抖音或接受应用内协议。

一次 Qwen3.8-27B 真实任务使用了 Mobile Use，下载了约 360 MB 官方 APK，但 600 秒内没有完成，已停止并保留测试会话。**真实模型端到端安装失败/超时，不能宣称已通过。** 没有自动再次消费模型额度。

随后打开已下载 APK，在系统安装确认页观察到 Install。通过正式 Dispatcher 调用 `ui.device → ui.screenshot → ui.gesture`，以当前截图人工确认的坐标执行一次短按。工具报告 Shizuku READY，手势成功；系统包查询确认 `com.ss.android.ugc.aweme`、versionName 40.7.0、versionCode 400700，首次安装时间 2026-10-04 09:25:48。**此为实际安装路径通过，不是模型自主定位通过，也不是通过 ADB 安装 APK。**

该定向 fixture 的 `ui.snapshot` 返回 NO_ACTIVE_SESSION，因此不能把它作为“本次普通无障碍只遗漏 Install”的新证据。历史节点过滤复验见[原调研](../../research/topics/mobile-use-packageinstaller-visibility-vision-and-privileged-backends-2026-10-04.md)。当前截图、手势和包事实证明独立高权限路径可用。

临时会话授权已撤销；下载管理器的未知来源安装权限恢复 deny；临时无障碍服务设置删除。保留用户已有文件、已下载 APK、抖音和诊断会话。

## 验证记录

原始日志与截图位于忽略的 `build/douyin-diagnosis/`；含私人会话数据的数据库不归档到仓库。

最终主机命令通过（`closeout-host.log`）：

```bash
./gradlew :tools:automation:testDebugUnitTest :app:testDeveloperDebugUnitTest \
  :tools:files:test :extensions:plugin:test \
  :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest \
  :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest \
  :app:lintDeveloperDebug :app:lintConsumerDebug spotlessCheck detekt --console=plain
python3 -m unittest scripts.tests.test_mobile_use_contract
python3 scripts/verify-integrated-runtime-apks.py
```

Python 合同检查 10 项通过，双渠道制品合同检查通过。中间新增提示触发行长度检查，已分行修复；一次 Lint 在源文件更新期间触发 PSI 内部异常，停止编辑后的最终完整执行通过，无跳过门禁。

最终 APK 覆盖安装到上述模拟器，`UnifiedExtensionsDeviceTest`、`ConnectorSessionPanelDeviceTest`、`DataSyncForegroundServiceDeviceTest` 共 7 项通过（`device-final.log`）；包括已添加默认页、添加展开、发现切换、会话选择不影响新会话默认值、正常停止与新任务请求交错。两次确定坐标的手势 fixture 分别验证下载页操作和安装确认页，均通过；只有后者由包事实确认安装完成。

这些结果不覆盖其他 OEM、Root 本轮验证、真实账号登录、模型自主完成安装或正式发布验收。
