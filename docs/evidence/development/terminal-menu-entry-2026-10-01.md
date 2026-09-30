# 终端入口与侧栏模式切换

2026-10-01 所有者报告终端入口不可发现，要求 Standard/Advanced 切换固定在菜单左下角，并明确授权 API36 定向验证。

## 变更与发现

- Developer 侧栏终端从 Work 折叠组移为独立入口。菜单主体滚动，底部当前模式及切换按钮固定；Advanced 使用原风险确认，Consumer 不显示不支持的升级按钮。
- 终端顶部增加 Runtime 设置入口，复用已有配置/安装/验证页面与返回栈；未创建终端时说明安装、验证、启动步骤。
- 现场 Advanced 已启用，但 Runtime 最初未验证；验证服务连接成功仍不能启动终端。Runtime 日志为 `Runtime requires preparation`，安装页确认 RootFS 尚未安装。通过现有安装界面准备后，终端成功建立。未将服务连接验证等同于 RootFS 安装，不删除会话/账号或改变授权策略。

## 验证

- 主机 Consumer Kotlin 编译、Developer lint、APK/AndroidTest APK 编译及格式通过，日志 `build/drawer-terminal-final.log`；后续终端指引增量 APK/AndroidTest 编译和 detekt 通过，`build/terminal-final-r2.log`。
- `emulator-5566` / API36 / Developer：GroupedNavigationDeviceTest 1/1，通过短屏大字体下全部入口可达、固定 footer 可见；`build/drawer-terminal-device.txt`。
- 实际菜单执行 Advanced → Standard → Advanced；确认提示出现，确认后恢复原 Advanced。终端页可进入 Runtime、返回终端、安装/验证环境并启动 RUNNING 会话。
- ProotTerminalUiDeviceTest#chineseInputReplAndRotationPreserveTheOriginalShell：1/1，7.877 秒；覆盖真实 shell 中文输入和输出、软键盘、Python REPL、旋转后环境变量保留及停止结算。`build/terminal-real-device.txt`。测试自己的工作目录与终端由 fixture 清理。
- 未使用真实模型或账号配额。本次仅覆盖所列 API36 定向路径，不代表所有设备/终端功能通过。没有提交或推送。
