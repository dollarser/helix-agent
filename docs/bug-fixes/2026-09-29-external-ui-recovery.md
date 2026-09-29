# Bug Fix: 系统选择器失败提示与许可证页布局

Status: fixed
Date: 2026-09-29
Related HXA: HXA-067, HXA-085, HXA-218
Affected modules: app, runtime/proot-app

## Problem

前轮审查留下三处旧问题：许可证页缺少系统栏适配，长正文挤出关闭按钮；分享启动失败静默返回且未处理系统拒绝；文件/照片选择器启动异常未转成可恢复提示。

## Impact

部分设备或系统组件不可用时，用户点击后没有解释甚至退出当前流程；大字体下难以关闭许可证页。

## Root cause

原生页面使用固定像素和无权重滚动容器；系统 UI 启动路径将平台异常直接暴露给调用者，分享函数没有结果反馈。

## Fix and invariants

- 许可证页面使用 dp 间距、系统栏 insets、浅色/深色正文配色；正文独立滚动并可选择，关闭按钮保留在正文之外。
- 文件、照片和产物导出选择器统一捕获 ActivityNotFoundException / SecurityException，显示本地化提示。输入草稿不被清除，启动失败清除附件目标，迟到结果不能导入。
- 分享返回是否成功启动系统面板，文件产物与任务结果页面均显示失败提示，并保留重试按钮和原内容。面板启动成功不代表接收端已完成分享。
- 不吞掉其他编程异常、不增加权限、不自动选择外部应用、不改变实际文件导入/导出链路。

## Alternatives considered

不只依赖 resolveActivity 预检：检查和启动之间仍可能失效。保留启动点的准确异常处理，避免包可见性差异误阻挡可用处理器。错误直接显示在当前界面，不使用易错过的短暂 Toast。

## Regression verification

主机 `build/remaining-ui-host-r4.log` 通过：consumer JVM 918 pass / 4 skipped、developer 966 pass / 4 skipped，0 failure；runtime 单元测试、双渠道 lint、Debug APK 和 AndroidTest APK、detekt 通过。前轮主机日志保留了函数长度与格式失败，修正后重跑，未跳过检查。

所有者本轮明确授权独占 API36。AVD `Helix_HXA229_Closeout_API36`，arm64、4 GiB：

- `build/remaining-ui-api36-consumer/`：3/3；`build/remaining-ui-api36-developer/`：4/4。覆盖分享缺失/拒绝/成功启动与原内容重试、导出选择器错误状态与重试清除、文件/照片失败提示与迟到结果拒绝，以及既有结果恢复/跨会话隔离。
- developer 私有进程许可证页默认布局通过；追加 320dp、1.5 倍字体 **1/1**（`legal-large-font.txt`）。已实际查看 `legal-default.png` 与 `legal-320dp-large-font.png`，标题和关闭按钮在系统栏内，正文独立滚动。此处验证原生法律页；不把分享函数返回值测试宣称为真实外部接收验证。
- 两个证据目录均保存安装制品 SHA 与设备身份，`closed.json` 记录 emulator 退出 0；使用临时 AVD 副本，未操作用户手机。
- `build/remaining-ui-format.log` spotlessCheck 通过；`build/remaining-ui-source-final.log` 源码/文档/i18n/secrets 检查，`git diff --check` 通过。

源码基线为 main `5c192d96` 加此前及本轮未提交修改；保留并行文档整理，不提交或推送。

## Residual risk

异常由测试夹具注入，不修改系统应用或真实账号；系统分享面板成功启动仅代表交接，不验证第三方接收结果。本轮不使用真机，不代表全产品验收。

## Related records

- [前轮输入恢复与维护页审查](2026-09-29-interaction-recovery-audit.md)
