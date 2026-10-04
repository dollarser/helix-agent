# Mobile Use 插件优化验证（2026-10-04）

## 范围

所有者在插件标准/隔离分析后要求“帮我优化”。本次修复标准根与宿主清单共存、补工作区目录导入，按完整工具契约提供 Mobile Use 调用上下文，并澄清后端能力说明。沿用 [Connector 包契约](../../adr/connectors/001-portable-bundles.md)和[插件归属契约](../../adr/connectors/003-ownership-and-installation.md)，保留 Root → Shizuku → Accessibility 优先级与两个模块职责。

目录入口经过既有 Workspace 范围校验及私有快照；不允许模型传任意绝对路径。安装仍绑定内容 hash，不连接 MCP、不执行 hooks、不据 manifest 加载宿主代码。测试覆盖标准清单优先/无效根拒绝回退、目录与 ZIP 身份一致、变更后拒绝安装、符号链接、资源限额、取消清理，以及同名不同来源/契约不能获得 Mobile Use 路由。

## 当前验证

主机定向测试共 **242 项**，0 failures/errors/skipped：Plugin 51、Mobile Use 10、Skills 48、Automation 125、App 目录导入 consumer/developer 各 4。Gradle 按输入增量复用未变任务；这些是对应当前源码的主机报告，不是设备运行计数。新增 11 个测试方法（两个 flavor 的 App 测试各运行一次）。

运行任务：`:extensions:plugin:test`、`:extensions:mobile-use:testDebugUnitTest`、`:extensions:skills:test`、`:tools:automation:testDebugUnitTest`，以及两个 flavor 的 `:app:test*DebugUnitTest --tests com.helix.app.connector.ConnectorInstallationServiceTest`。consumer/developer debug APK 构建和 developer AndroidTest Kotlin 编译通过；`spotlessCheck detekt` 通过。`python3 -m unittest scripts.tests.test_mobile_use_contract` 的 10 项渠道契约测试通过。

`python3 scripts/verify-integrated-runtime-apks.py` 对新构建的双渠道 debug APK 检查通过，涵盖组件、进程/UID 声明、渠道隔离及载荷；它是制品静态检查，不是设备执行。`bash scripts/check-docs.sh` 通过（714 Markdown、228 HXA）；`git diff --check` 通过。日志位于 `build/mobile-use-plugin-optimization-2026-10-04/`：`verification.log` 保留首次静态检查失败及其余测试/构建结果，修复后的成功结果为 `static-final.log`、`build-final.log`、`contract.log`、`apk-contract.log` 与 `docs-final.log`。

首次主机运行的目录符号链接用例已正确被 Workspace 拒绝，但测试预期使用了错误的异常类型；调整为精确 `SymlinkInPath` 后重跑，不放宽拒绝边界。格式检查发现新测试长行，静态分析发现读取嵌套和描述长行，均已整理并通过复查。

## 边界

本轮设备验证为 **not requested**；未操作微信/抖音账号，也未测试平台风控或“不可检测”。此前 Root/Shizuku 设备通过结果见[历史专项证据](mobile-use-root-priority-2026-10-04.md)，不代替本次修改后的设备复测。

Mobile Use 仍是 APK 内可信原生插件，主体不具备独立 OS 身份隔离。App 设置接线和具体后端适配器尚未整体移入插件；目录支持限定工作区工具入口，文件选择器仍是 ZIP/JSON。没有宣称通过完整 Agent Plugins 标准认证、真机/OEM 验收或发布验证。未提交、推送。
