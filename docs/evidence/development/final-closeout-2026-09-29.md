# 2026-09-29 最终本地收口

## 范围

所有者授权整理并提交当前修复、最终双渠道门禁、冻结源码后的完整 P5 和手机实际流程验证。本轮不推送、不发布，不扩展数据库迁移、备份或恢复 UI。

## 开发期数据库

按所有者明确选择，同版本 Room identity 不兼容时自动删除数据库并按当前 v1 baseline 重建；兼容库重开不清空，数据库外文件保留，其他错误不触发清库。会话、Provider 配置、权限及产物索引不保留，不声称文件保留等于索引或会话保留。

PLC110 / API35 / developer：storage `FreshSchemaDeviceTest` 2/2，通过旧 identity 重建、兼容重开及文件保留断言。修复 APK 覆盖安装成功，MainActivity resumed、进程持续存活且对应进程无 AndroidRuntime 崩溃日志。日志为 `build/development-db-reset-device.log`；主机 build/static/source 日志为 `build/development-db-reset-final.log` 和 `build/development-db-reset-source.log`。

## 当前验收

- 输入恢复与 Provider 导航已有[双渠道 API36 定向证据](merged-api36-regression-2026-09-29.md)，不将其当成完整 P5。
- 最终完整 `check-all.sh --all` 通过：全量 JVM、双渠道 Debug/Release lint 与 APK、静态/源码/锁文件/制品边界；双渠道 AndroidTest APK 编译通过。日志 `build/final-closeout-host.log`、`build/final-closeout-test-apks.log`。
- 正式 P5：clean `8c7a95b45f6ba9071f68f63cf236635d1e1af3b4`、API36 developer、真实 SGLang 兼容代理 `Qwen3.8-27B`，准备 smoke 1/1，Files 4/4、JavaScript 4/4、Skills 4/4、Goal 3/3，完整 **15/15**；一次完整运行，无 case 重试。模拟器 owner PID 96320 已由 runner 正常关闭。
- P5 身份：dataset SHA `f27bf8b51e61be248a6e642c22cefc3e5045d0d35e37518377eb9b8cdf85e795`，source manifest `68d6a1e78c619f6cb5b7604c6d8ce0cf69ebaca4c4151c59dec7f069d879ba5a`；app APK `d357e9f46e41d20ac592b07b41f3e77bcdc56870041d3537beddbbd3b1d7e1f6`，test APK `67e2b80668cb5ff7a6fb53d68577d11a931c7a0b87805e765c4b20576413c021`。安装包由主目录构建，运行前核对全部相关源码与 clean 工作树 manifest 完全相同；独立工作树不含并行文档 WIP。
- P5 可测 12 项的端到端 median 3.871s、mean 5.683s、p95/max 14.942s；Goal 不混入同口径统计。不据此声称模型 tok/s、性能因果提升或普遍稳定性。skill-003 本次 0 工具调用；skill-001 仍有 12 次工具调用，不关闭多余调用边界。
- 原始 P5 记录及 APK 已保留于主目录 `build/final-p5-8c7a95b4/`；汇总 `p5-sglang-summary.json`、关闭证据 `closed.json`。生产协议为 OPENAI_CHAT_COMPLETIONS，不混同三协议专项。
- 手机实际流程：当前设备已断开，等待重新连接；此前启动成功不替代模型配置、工具执行和重开验收。
- 主目录另一端的 `feature-refactor-strategy.md` 与开发索引改动保持原样，不混入本轮提交。正式 P5 使用本轮提交的干净隔离工作树。

## 真实视觉补充

新增 opt-in `RealToolVisionDeviceTest`，仅在显式 `realToolVision=true` 时访问本机指定服务。API36 developer + `Qwen3.8-27B` 的实际 ChatService → Agent loop → Dispatcher → `view_image` → 持久图片绑定 → 显式合成数据披露 → 模型回答链路 **1/1 通过**。256×128 合成图片左红右绿，提示词不泄露颜色；结果为 `LEFT=red;RIGHT=green`、Turn `COMPLETED`、`view_image:COMPLETED`、TOOL_OBSERVATION 图片绑定 1 条。

原始记录 `build/final-real-tool-vision/real-tool-vision.json`，JUnit `instrumentation.txt`，owner PID 1624 已正常关闭。production app APK 与 P5 完全一致；新增测试后的 test APK SHA `2e05120ddf8c728fe9fc96456b189481153279813f1a99ac25d5f0467d8c831f`。测试编译及 detekt 通过（`build/final-closeout-vision-build-r2.log`）；首次检查仅 LongMethod 失败，拆分测试 helper 后通过，没有修改生产代码、放松 oracle 或重跑失败模型 case。

此专项不宣称浏览器截图、OCR、任意文档或所有视觉模型质量。新增测试在 P5 冻结提交之后，不把它写成原 P5 test APK 已包含的用例。

## 保留边界

模型截断、额外调用、通用视觉识别质量、OEM/JNI/Binder 长稳、账号与正式发行不由本记录关闭。R1 仍是后续实施任务。
