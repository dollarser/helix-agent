# Bug Fix: 输入恢复与 Runtime 维护状态

Status: fixed
Date: 2026-09-29
Related HXA: HXA-067, HXA-085, HXA-214, HXA-218
Affected modules: app, runtime/proot-app

## Problem

维护页删除入口仍显示安装按钮；语音与附件选择的目标会话在页面重建后丢失，后台附件任务也可能在切换会话后才读取归属。失效附件阻挡纯本地命令。维护操作结果被刷新覆盖，页面重开后丢失；移除模式按钮后当前模式缺少可见提示。

## Impact

用户可能误触删除、丢失输入或看到不属于当前操作的结果；无法用本地命令恢复编辑状态。

## Root cause

入口标签与执行分支不一致；外部结果使用临时状态和迟绑定会话；命令与消息共用附件可发送条件；维护结果仅保存在 Activity 控件中。

## Fix and invariants

- 删除入口隐藏安装与回滚，只保留明确命名的删除动作；安装按钮只执行安装。
- 语音、文件、照片与相机目标使用可恢复状态，结果只回填原会话；语音追加到最新编辑内容且不自动发送。附件服务在调用时固定目标会话，UI 显式传入目标。
- 纯本地命令独立于附件可发送条件；带任务的模式命令仍受消息发送条件约束。输入区只读显示当前模式，不恢复模式选择器。
- Runtime 维护结果单独持久化，重开页面仍显示；中断标记提示检查实际状态，不自动重试。安装事实仍来自运行时文件，界面回执不能替代执行事实。
- 维护页提供简明状态和可展开技术详情。重复删除已不存在的运行时视为达到目标，不影响相邻用户文件；无法打开维护/法律入口时显示失败信息。
- 相机准备或启动失败清理临时文件并给出提示。

## Alternatives considered

不引入新的后台安装框架、不改变权限或工具执行域，不以模型输出覆盖执行结果。外部选择器恢复采用现有 Android saved state；不增加云端语音依赖。

## Regression verification

主机 `build/review-fixes-host-r6.log`：consumer JVM 918 pass / 4 skipped，developer 966 pass / 4 skipped，0 failures；runtime 单元测试、双渠道 lint、Debug APK 与 AndroidTest APK、detekt 通过。最终测试编译与格式化见 `build/review-fixes-test-final.log`。初次设备运行 consumer 20/20、developer 22/22；初次截图拍到桌面，保留原证据，不作为视觉验收通过。

追加附件服务修复后的 API36 arm64 / 4 GiB 独占验证：

- `build/review-fixes-api36-r2-consumer/`：21/21，通过会话命令、缺失附件、本地命令、saved-state 恢复、语音/文件/照片/相机结果归属、会话模式、Runtime 入口与旧会话附件拒绝。
- `build/review-fixes-api36-r2-developer/`：同样的 21 项通过；2 项 Runtime 私有页面测试失败（页面不持续前台）。未记作整轮通过。
- Runtime 测试补用已有 `ForegroundDeviceTestHost`，确保从前台应用发起页面，与真实用户入口一致；等待退出动作完成并在截图前重新确认标题。`build/review-fixes-api36-r3-developer/` 定向 2/2 通过。已实际查看 `proot-repair-layout.png`，内容可读、顶部未遮挡、删除结果在重开后仍可见。该轮没有重新运行其他 21 项。
- 所有 owned emulator 的 `closed.json` 均记录退出 0。真实手机、真实录音转写和外部服务 not requested。
- 最终运行时测试编译与 detekt：`build/review-fixes-runtime-test-build.log`；格式：`build/review-fixes-format-final.log`；源码/文档/翻译/密钥检查：`build/review-fixes-source.log`。`git diff --check` 通过。
- 生产 APK SHA-256：consumer `94497617fdba0f9e18b62663635b836aca87e5d07a618450928cfbacc5ad5740`；developer `3991608bd4d18daa48b4fc17cbdd29f6e66ad65f66645dbe0935fd7c75f1dfc5`。证据目录包含实际安装制品摘要，测试 APK 的后续修改未改变生产 APK。

源码基线为 main `5c192d96` 加前轮及本轮未提交修改。当前文档另有并行整理，本轮保留其内容；未提交、未推送。


## Residual risk

本轮仅定向审查，并非全产品验收。语音与相机结果由测试夹具注入，不证明真实录音识别或相机硬件。未验证长时间安装中的进程死亡恢复，也未使用真实账号、模型服务或手机。

额外审查发现的旧问题：`ProotLegalActivity` 固定像素间距、缺少系统栏适配及滚动区域权重；`sharePlainText` 对无分享处理器静默返回，且未处理 SecurityException。文件/照片选择器的缺失处理器启动异常也尚未覆盖。本记录不声称这些问题已修复；后续修正单独见[系统 UI 恢复记录](2026-09-29-external-ui-recovery.md)。

## Related records

- [命令、Runtime 与语音入口](2026-09-29-slash-runtime-voice.md)
- [前轮交互收口](2026-09-29-interaction-settings.md)
