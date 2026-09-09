# Bug Fix: 自定义顶栏避让系统状态栏

Status: fixed
Date: 2026-09-09
Related HXA: HXA-175
Affected modules: app

## Problem

从会话切到文件、浏览器、扩展等页面，API36 顶栏从窗口 y=0 开始，文字和导航按钮与状态栏重叠；API34 真机未显现。

## Impact

页面标题与系统信息互相遮挡，顶部菜单难以辨认、点击。会话与非会话页面安全距离不一致。

## Root cause

targetSdk=36，在 Android15+ 强制边到边布局。Scaffold 提供 topBar 时依赖该组件处理顶部 Insets；CompactPageHeader 是普通 Row，不具备 Material TopAppBar 的默认 Insets。旧测试只比较聊天/非聊天顶栏高度以及页面内部控件是否可见，没有比较顶栏与系统栏的窗口坐标。低版本传统 decor 布局把内容放在状态栏下，掩盖缺口。

## Fix and invariants

ShellTopBar 给非会话页面统一应用 safeDrawing 顶部和水平 Insets。内部紧凑顶栏高度保持 48dp；会话保留空 topBar，由 Scaffold 原有内容 Insets 处理，避免重复留白。不得通过设备型号或“模拟器”条件分支规避。

## Alternatives considered

不使用固定状态栏高度：刘海、方向和系统设置会改变实际 Insets。不全局给聊天再加 statusBarsPadding：会重复计算已有安全距离。不关闭边到边布局：目标平台会强制执行，且无法解释或修复安全距离的组件责任。

## Regression verification

SystemBarInsetsDeviceTest 比较真实 WindowInsets 和语义节点 boundsInWindow；六个非会话目的地、重复导航和 Activity 重建。修复前 API36 文件页断言失败（0px < 136px），修复后相同用例通过。ConversationTopBarDeviceTest 同时守住顶栏同高及会话不重复显示标题的行为。最终批次证据见 HXA-175。

## Residual risk

API36 模拟器和 API34 真机证据不等于覆盖所有厂商 ROM、刘海和折叠屏形态；继续依赖系统动态 Insets，不对未测机型声称全覆盖。

## Related records

[HXA-175](../completion-records/HXA-175.md)、[HXA-170](../completion-records/HXA-170.md)、[Android 边到边布局](https://developer.android.com/develop/ui/compose/system/setup-e2e)。
