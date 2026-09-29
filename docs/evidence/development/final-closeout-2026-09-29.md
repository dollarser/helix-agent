# 2026-09-29 最终本地收口

## 范围

所有者授权整理并提交当前修复、最终双渠道门禁、冻结源码后的完整 P5 和手机实际流程验证。本轮不推送、不发布，不扩展数据库迁移、备份或恢复 UI。

## 开发期数据库

按所有者明确选择，同版本 Room identity 不兼容时自动删除数据库并按当前 v1 baseline 重建；兼容库重开不清空，数据库外文件保留，其他错误不触发清库。会话、Provider 配置、权限及产物索引不保留，不声称文件保留等于索引或会话保留。

PLC110 / API35 / developer：storage `FreshSchemaDeviceTest` 2/2，通过旧 identity 重建、兼容重开及文件保留断言。修复 APK 覆盖安装成功，MainActivity resumed、进程持续存活且对应进程无 AndroidRuntime 崩溃日志。日志为 `build/development-db-reset-device.log`；主机 build/static/source 日志为 `build/development-db-reset-final.log` 和 `build/development-db-reset-source.log`。

## 当前验收

- 输入恢复与 Provider 导航已有[双渠道 API36 定向证据](merged-api36-regression-2026-09-29.md)，不将其当成完整 P5。
- 最终完整 `check-all.sh --all` 通过：全量 JVM、双渠道 Debug/Release lint 与 APK、静态/源码/锁文件/制品边界；双渠道 AndroidTest APK 编译通过。日志 `build/final-closeout-host.log`、`build/final-closeout-test-apks.log`。
- 正式 P5 待源码提交后运行，严格保留各失败结果，不以重试替代首轮分数。
- 手机实际流程：当前设备已断开，等待重新连接；此前启动成功不替代模型配置、工具执行和重开验收。
- 主目录另一端的 `feature-refactor-strategy.md` 与开发索引改动保持原样，不混入本轮提交。正式 P5 使用本轮提交的干净隔离工作树。

## 保留边界

模型截断、额外调用、真实视觉识别质量、OEM/JNI/Binder 长稳、账号与正式发行不由本记录关闭。R1 仍是后续实施任务。
