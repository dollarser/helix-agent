# 应用存储与内置插件入口澄清（2026-10-04）

## 当前边界

- 应用存储统计会话记录、托管工作目录和记忆等 Helix 自有数据，既不是手机文件管理器，也不等于应用总占用或可释放容量。Developer 的回放清理仅处理不再引用且符合保护条件的订阅回放记录。
- Helix 本地文件是稳定的应用私有文件区域；会话工作空间是会话绑定的工作目录，也可显式绑定外部目录。删除会话不会自动删除工作文件，文件浏览入口不授予 Agent 使用权限。
- 当前 JGit 页面只读，显示仓库状态和差异。仓库差异不等于某次 Agent 的修改记录；未来按任务恢复需要独立的基线、修改归属和冲突检查，本次未实现自动快照或回退。
- Mobile Use 已是原生插件，具备注册、持久启用状态和既有工具生命周期。此前安装记录位于管理标签的连接器列表，默认市场页缺少直达入口。

## 调整

- 设置入口统一命名为“应用存储”。
- 扩展页在市场/管理标签之前展示真实注册的内置插件；Mobile Use 使用既有启用和修复控件，并提供会话授权与后端设置入口。
- 扩展页连接器列表不再重复展示内置插件。其他调用方保持既有列表行为。
- Consumer 未注册 Mobile Use 时不显示虚构卡片。加载失败明确显示重试，取消不转为普通失败。插件启用不会创建设备授权或启动操作。

## 验证

Developer / Consumer Debug 构建、两种 AndroidTest Kotlin 编译、Spotless 和 detekt 通过。新增 Developer 导航用例覆盖默认扩展页发现 Mobile Use 并进入会话设置，仅编译，不声称执行通过。

主机命令使用 `:app:assembleDeveloperDebug`、`:app:assembleConsumerDebug`、`:app:compileDeveloperDebugAndroidTestKotlin`、`:app:compileConsumerDebugAndroidTestKotlin`、`spotlessCheck`、`detekt`，另运行 `bash scripts/check-docs.sh` 和 `git diff --check`。日志位于忽略的 `build/bundled-plugin-*-verification.log`。

设备状态：`not requested`。本轮没有启动、使用设备或安装 APK；此前模拟器安装不代表本轮修改已验证。未使用真实账号、提交或推送。
