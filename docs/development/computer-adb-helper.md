# 电脑 ADB helper

当前源码支持 Android 12／API31+ 的 Advanced Debug 和非 debuggable Release 单 APK，包名为 `com.helix.agent.developer`。已公开的 v0.0.10 不包含入口；新实现尚未发布，不要用它的附件尝试以下新路径。Standard 当前没有手机操作宿主适配，不提供 helper 入口。其他 API/OEM、工作资料和多用户场景须分别验证。

## 启动

电脑准备 Python 3 和 Android SDK Platform Tools，连接手机并完成 Android 的电脑 ADB 授权。确认要操作的设备，使用本仓库与候选 APK 对应的脚本：

```sh
adb devices -l
python3 scripts/start-adb-helper.py --serial SERIAL
```

将 `SERIAL` 替换为实际序列号。脚本从该设备查询当前安装 APK，使用 `app_process` 启动 helper，完成应用签发的一次性交接；不依赖 `run-as`、Root、Shizuku 或额外手机确认。成功输出 `HELIX_ADB_CONNECTED` 与本地 receipt 路径；失败不能当作已连接。

之后在 Helix 配置并选用手机操作插件。全手机/指定应用、会话选择、截图外发和调用审批保持各自的授权边界。连接 helper 不等于授予这些权限，也不启动任务屏或应用。信任来源是已授权电脑 ADB；nonce/PID/APK 绑定防止错接和重放，并不证明 shell 代码真实性。

## 停止与恢复

```sh
python3 scripts/start-adb-helper.py --serial SERIAL --stop
```

如果启动时使用 `--ready-file PATH`，停止时传入同一文件。脚本按设备 boot ID、PID 与进程 start ticks 核对，只停止该 receipt 对应的原进程；设备重启或 PID 复用时拒绝误杀其他进程。不要删除仍需用于停止的 receipt。

- USB 拔线或电脑 ADB 传输重连不等于 helper 死亡；已建立的连接由手机端进程/Binder 维持。
- 已有连接就绪时再次启动会明确拒绝替换；新启动失败不会停止原连接。
- helper 退出后需显式重新运行；不自动重放输入，旧节点和截图重新观察。Helix 仍存活时按既有授权尝试接管原屏或恢复原 task，不承诺目标 Activity 自身状态不变。
- Helix 进程退出、覆盖更新或设备重启后，重新运行脚本；不恢复跨 Helix 进程的任务关联，也不提供应用内无线配对或开机保活服务。
- Android 11 及以下在启动前明确拒绝；APK 未安装、拆分安装、交接过期、版本交错或系统接口拒绝均按实际错误处理，不用清除应用数据来重试。

启动失败时保留原错误；脚本尽力清理本次专用临时文件，清理或停止不确定会明确报告。重启后请先检查实际任务状态，再决定下一步。契约见[权限与任务屏 ADR](../adr/permissions/001-session-authorization.md)。
