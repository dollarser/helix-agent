# Bug Fix: 文件传输恢复入口常驻屏幕顶部破坏布局

Status: fixed
Date: 2026-09-24
Related HXA: HXA-182
Affected modules: app/ui, app/files

## Problem

在文件管理器所有界面（包括首页、存储位置选择、以及进入各级子目录浏览时），屏幕顶部均固定浮动着一个紫色文字按钮“传输恢复”。

## Impact

1. 顶层悬浮按钮占用了原本顶部导航栏和状态栏之间的正常边距与排版空间，阻碍用户浏览路径与操作栏；
2. 即使没有任何未决传输任务，按钮仍然常驻，给用户造成“应用处于异常状态”或“每次必须点恢复”的困惑；
3. 用户在深入子文件夹进行目录浏览时仍然受到该悬浮项干扰。

## Root cause

HXA-182 实现 ADR-WORKSPACE-002 手动传输恢复时，在 `FilesScreen.kt` 的 `FilesRecoverableLayout` 组合函数中，将 `FilesRecoveryPanel(actions)` 直接声明在最外层 `Column` 的首个元素位置。该外层布局覆盖了文件管理器的所有子界面。

## Fix and invariants

1. **从全局外层移除**：从 `FilesRecoverableLayout` 中移除 `FilesRecoveryPanel(actions)`，将该外层布局精简为透明的容器 `Box`。
2. **收敛至首页管理卡片**：将 `FilesRecoveryPanel(actions)` 移入 `FilesHome.kt` 底部的“添加与管理位置”卡片（`Card`）中，与“添加文件夹 (SAF)”、“导入”以及“请求 Root 权限”等管理功能统一归纳展示。
3. **测试兼容不变式**：保留原有测试标签 `files-recovery-open`。`FileTransferRecoveryDeviceTest` 在测试流程中通过 `compose.navigateTo("files")` 进入文件首页，并在首页点击该按钮触发恢复弹窗，测试行为与语义契约完全不受影响。

## Alternatives considered

- **直接删除传输恢复功能**：用户提出若无用可直接删除，但该功能承载了 ADR-WORKSPACE-002 所规定的“进程意外终止后原子回滚与未决传输手动恢复”架构契约，并在 `FileTransferRecoveryDeviceTest` 中有严格回归断言，故不可删除其核心逻辑，而应做合理的交互收敛与位置规整。

## Regression verification

1. 静态检查与规范：
   - `./gradlew spotlessApply spotlessCheck detekt` 全通过；
   - `./scripts/check-all.sh --source` 验证通过。
2. 单元与集成测试：
   - `FileTransferRecoveryDeviceTest` 的节点查找与点击路径兼容验证；
   - 手动真机操作确认：进入文件首页可在底部管理卡片中看到“传输恢复”操作，点击能正常拉起恢复弹窗；在进入任何子目录（如 `/`、`/data`、`/sdcard`）后，顶部恢复按钮彻底消失，界面干净整洁。

## Residual risk

无已知剩余风险。

## Related records

[ADR-WORKSPACE-002 独立文件管理与传输恢复](../../docs/adr/workspace/002-manual-files-and-recovery.md)、[HXA-182 完成记录](../../docs/completion-records/HXA-182.md)。
