# Bug Fix: 产物弹窗操作按钮在窄屏溢出

Status: fixed
Date: 2026-09-22
Related HXA: HXA-203, HXA-219
Affected modules: app/ui, app/androidTest

## Problem

API29 consumer 的产物中心回归中，删除文件后再次点击产物，等待缺失提示超时。增加可见性断言后发现“关闭”按钮不在可见区域，前一次关闭实际上未生效。

## Impact

用户在窄屏中同时看到外部打开、任务、会话等操作时，可能无法通过关闭按钮退出文件弹窗。文件变化后不会重新读取，因为旧弹窗没有被关闭。单纯存在节点或调用点击，不足以证明操作可达。

## Root cause

`ArtifactFileSecondaryActions` 使用不可换行的 Row 容纳多个 TextButton，超过对话框宽度后末尾按钮越界。原测试未断言关闭按钮可见，也未确认弹窗确实消失，最终表现为重新读取超时。列表节点已组合但未完全显示，是独立的测试定位问题。

## Fix and invariants

主、次操作区域均改用 FlowRow，保持原有操作、启用条件和回调。测试先滚动到产物行，确认关闭按钮可见，再确认弹窗消失，随后删除并重新打开；缺失提示与禁用分享断言保留。

## Alternatives considered

延长等待、删除缺失断言或绕过可见点击无法证明真实交互可用，因此不采用。没有改变文件校验、交付或权限契约。

## Regression verification

`ArtifactCenterDeviceTest.aToolWrittenFileListsPreviewsAndInvalidatesWhenGone` 在修复前能稳定失败，修复后与五项会话预览和另一项产物中心测试一起通过。定向命令：`python3 scripts/debug/2026-09-22/accept-ui-refactor.py --output build/ui-artifact-focus2 --artifacts-only --api 29 --flavor consumer --capture --first-port 5678`，在共享host slot中执行。7/7通过、0失败/跳过/未完成，自有模拟器退出码0。完整矩阵以HXA-219交付记录为准。

## Residual risk

未以本次模拟器测试替代所有OEM、大字体组合或TalkBack专项验收。无已知同机制未解决的本批次失败。

## Related records

[原产物交付](../completion-records/HXA-203.md)、[本批交付](../completion-records/HXA-219.md)、[第一批UI交付](../completion-records/HXA-218.md)。
