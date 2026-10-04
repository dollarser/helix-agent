# Mobile Use 默认高权限语义观察与抖音自主安装复验

Status: host and bounded semantic device checks passed; autonomous installation failed
Date: 2026-10-05
Related HXA: HXA-244
Affected modules: app, tools/automation

## 范围与实现

所有者要求默认使用可用高权限后端，并卸载抖音，让 Helix 自主安装。沿用[权限 ADR](../../adr/permissions/001-session-authorization.md)的原会话授权、封闭协议、物理资源租约及 UNKNOWN 不重放边界。

此前独立高权限路径覆盖截图、手势和精确条件点击，默认 snapshot/find 和节点动作仍固定普通无障碍。现在语义观察选择支持该能力且 READY 的 Root → Shizuku → Accessibility；节点动作绑定观察时的后端、窗口、树版本和原会话授权，不在失败后跨后端重放。宿主签发临时不透明节点标识，远程执行复用现有语义引擎和敏感字段遮蔽。

同时修复每次 RPC 重建私有服务/UiAutomation 连接造成的绑定超时和窗口身份变化。连接可复用，授权仍逐次复核。默认快照省略无标签且不可操作的空布局容器，完整树仍用于查找和校验；视觉手势说明普通短按为 80–120 ms，500 ms 以上为有意长按，不擅自修改提交参数。应用启动及全局返回/主页仍保留普通无障碍实现，本次没有声称迁移这些操作。

## 当前验证

设备 emulator-5554，API36 ARM64，Developer。先卸载 `com.ss.android.ugc.aweme`，保留此前官方来源 APK、微信和用户数据。新版应用及测试 APK 覆盖安装成功。

- 关闭 Helix 普通无障碍后，通过正式 Dispatcher 调用 `ui.device → ui.snapshot`，返回 Shizuku、SUCCESS 和 Settings 控件。最终候选定向测试 1 项通过，日志 `build/douyin-diagnosis/semantic-compact-device.log`。
- 第一轮 Qwen3.8-27B 使用 Shizuku 语义观察、点击和输入，在文件管理器中找到 APK，但反复读取界面并曾使用 1000 ms 长按进入多选，600 秒超时，测试收尾停止任务。
- 精简快照及增加短按说明后第二轮任务仍失败：成功启动文件管理器、取得 Shizuku 快照后，模型以“文件管理器显示 Downloads 有 6 个文件但截断了，我截图确认文件名。”结束，没有调用后续截图或安装。Turn 的 COMPLETED 只代表回复结束；测试按包事实判定安装失败。
- 两轮均未由测试者点击安装或通过 ADB 安装抖音。最终包查询为空，抖音仍未安装。不能宣称自主安装完成，也没有本轮安装确认页的语义识别通过证据。

真实模型记录保存在测试会话，日志分别为 `semantic-model.log`、`semantic-compact-model.log`。初始一次测试启动因普通无障碍服务重连等待失败，未发出模型请求。设备日期仍为 2026-10-04；本记录按主机当前日期归档。含私人会话数据的数据库、截图仅留在忽略的 build 目录。

临时会话授权在 fixture finally 撤销并恢复原配置；普通无障碍服务列表删除、accessibility_enabled 恢复 0，下载管理器 REQUEST_INSTALL_PACKAGES 恢复 deny。未登录抖音、未接受应用内协议、未消费真实应用账号。Root 路径仅主机协议测试，未进行本轮 Root 设备验收。

## 主机门禁

最终命令通过，日志 `build/douyin-diagnosis/semantic-final-host.log`：

```bash
./gradlew :tools:automation:testDebugUnitTest :extensions:mobile-use:testDebugUnitTest \
  :app:testDeveloperDebugUnitTest :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest \
  :app:assembleConsumerDebug :app:assembleConsumerDebugAndroidTest \
  :app:lintDeveloperDebug :app:lintConsumerDebug spotlessCheck detekt
python3 -m unittest scripts.tests.test_mobile_use_contract
python3 scripts/verify-integrated-runtime-apks.py
bash scripts/check-docs.sh
git diff --check
```

automation 141 项、mobile-use 10 项通过；覆盖跨会话/撤权/后端丢失、节点消费与不重放、快照容器过滤和点击父节点保留。双渠道构建与静态门禁通过。这些结果不等于当前模型自主任务通过、其他 OEM、Root 设备或发布验收。
