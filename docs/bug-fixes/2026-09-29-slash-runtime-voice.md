# Bug Fix: 会话命令、Runtime 入口与系统语音输入

Status: fixed
Date: 2026-09-29
Related HXA: HXA-067, HXA-085, HXA-214, HXA-218
Affected modules: app, runtime/proot-app

## Problem

模式入口重复，斜杠候选点击即执行，且 `/plan`、`/act` 错误切到 CHAT。新会话沿用旧默认值。Runtime 重复暴露订阅账号/网络入口，Standard 下可进入无法使用的运行时页面。PRoot 维护页面缺少系统栏适配，安装结果被刷新覆盖。语音入口检查识别服务而非实际启动的 Activity，且缺少包可见性声明。

## Impact

用户选择候选即触发操作，无法先编辑；模式可能与意图不符。安装和语音失败缺少可操作说明，维护界面可能被状态栏遮挡。

## Root cause

命令补全与执行混在同一点击回调；新建会话直接使用历史默认快照。Runtime 入口绕过了已有 Advanced 条件展示，独立原生布局使用固定像素且未应用 window insets。系统语音预检与启动协议不一致。

## Fix and invariants

- 上一轮交互修复已单独本地提交为 `5c192d96`，未推送；本记录描述此后的追加修改。
- 模式选择器从输入区、加号菜单和会话设置移除；会话设置只显示当前模式和命令说明。补全只修改输入，命令首词高亮；发送后才执行。支持 `/chat`、`/plan`、`/act`、`/goal`、`/compact`、`/clear`、`/help`。
- 模式命令可附带任务，先完成会话绑定的模式修改，再发送任务文本；命令本身不发给模型。运行/待确认/会话切换时拒绝改模式，保留草稿。普通路径、未知命令及正文中的斜杠不作为本地命令执行。
- 新草稿、显式创建、分享新会话和 fork 默认 ACT；既有会话模式不重写，权限与审批规则不变。
- Runtime 只保留运行时管理入口；订阅账号与其网络配置仍由 Provider 管理。Standard 显示 Advanced 要求，developer 通过现有风险确认切换，consumer 提示版本不含 PRoot。切换不会安装或授权执行。
- PRoot 维护页按密度留白、系统栏 insets、滚动、圆角按钮和深浅色适配；安装/回滚结果在状态刷新后展示。
- 系统语音查验实际识别 Activity，声明必要的包可见性，处理启动时组件消失或权限拒绝。识别结果仅在原会话回填可编辑输入，不自动发送；无组件时说明启用系统识别或使用键盘麦克风。

## Alternatives considered

不为修复输入入口引入语音模型或云端转写服务。PRoot 保留当前私有进程及原生维护页面，不把安装与执行搬入主进程，也不为样式增加新的 UI 框架。普通命令选择不弹二次确认；发送就是执行意图。

## Regression verification

双渠道 app JVM、lint、Debug APK、AndroidTest APK、detekt 通过，日志 `build/slash-runtime-host-r5.log`；先前编译/静态检查失败及修正保留在 `slash-runtime-compile.log`、`slash-runtime-host.log` 与 r2–r4 日志。

- JVM：consumer 918 pass / 4 skipped，developer 966 pass / 4 skipped，0 failure。
- API36 arm64、4 GiB 独占 AVD `Helix_HXA229_Closeout_API36`：consumer **18/18**、developer **19/19**。覆盖输入布局、补全选择不执行、发送才切模式/压缩、模式附带任务、运行时模式锁、新会话 ACT 与旧会话隔离、Standard Runtime 说明与语音 Intent 结果。目录 `build/slash-runtime-api36-consumer/`、`build/slash-runtime-api36-developer/` 包含源码对应制品 SHA、设备身份、原始结果与关闭记录。
- Developer 增加私有进程维护页顶部坐标断言；截图首轮采到转场帧，追加等待平台转场后的视觉检查 **1/1**，目录 `build/slash-runtime-api36-layout-final/`。已查看 `proot-repair-layout.png`，确认文字、按钮可读且标题在状态栏下方。此前 r2 截图仍带淡入效果，保留原证据。最终测试 APK 编译、spotlessCheck/detekt 通过见 `build/slash-runtime-layout-final-build.log`。全部独占模拟器已关闭，不修改功能测试结果。
- 主机源码/文档/i18n/secrets：`build/slash-runtime-source-final.log`；format：`build/slash-runtime-format.log`。`git diff --check` 通过。
- consumer APK SHA-256 `942044be532f0aa367709c3488f1d5570a8d446824191d02a54ab7082c627443`；developer `22b9f5190e0f20535b1eaa03560a72b69e46b96e8f530e5caaf7c045e1fa9c6b`。当前 APK 与定向设备实际安装制品一致。

所有者已明确授权本轮独占 API36；真实手机、真实录音转写、账号与外部模型服务 not requested。

## Residual risk

无系统识别组件的手机仍无法由当前入口直接转写。后续独立语音方案：先调研目标手机 RecognitionService 可用性，再选择前台系统服务适配或可选本地 ASR；明确麦克风授权、录音生命周期、中文质量、下载/存储成本、取消与无网络降级。若采用云端服务，必须增加用户配置和音频外发同意，不能隐式上传。验收要求包含真机中文/英文、取消、无服务、切换会话、后台中断与草稿保留。本次不把模拟 Intent 结果当真实麦克风转写验收。

## Related records

- [上一轮交互修复](2026-09-29-interaction-settings.md)
- [模式与审批](../adr/permissions/002-review-modes.md)
- [执行域](../adr/runtime/001-execution-domains.md)
