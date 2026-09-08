# Bug Fix: 分享草稿继承上一会话的 Provider 标识

Status: fixed
Date: 2026-09-05
Related HXA: HXA-056
Affected modules: app

## Problem

真实 Ollama UI 聊天结束后再打开文字或图片分享草稿，输入和附件均正常，
但 `chat-unbound-provider` 入口消失，仍显示之前 Provider 的标识。

## Impact

草稿持久化的 `providerId` 实际为 null，没有自动发送或继承授权；
界面却误导用户认为已绑定，并隐藏显式绑定入口。

## Root cause

`ChatService.refreshScreen()` 使用 `badgeFor(sessionId) ?: current.badge`。
“当前会话没有 Provider”是有效结果，却被当成“没有更新”，保留前一会话状态。
空白初始化的单用例和没有成功建立 Provider 的 smoke 不会暴露该状态序列。

## Fix and invariants

badge 只从当前可解析会话计算；无 Provider 或无会话必须发布 null。
不改变 Room Provider 绑定、发送、授权和分享只落本地的契约。

## Alternatives considered

不通过清空所有 Provider、重启进程、改变测试顺序或移除分享 UI 断言掩盖缺陷。
不为分享草稿自动分配 Provider。

## Regression verification

`ChatSessionLifecycleDeviceTest.providerFreeSessionAndShareDraftNeverRetainThePreviousProviderBadge`
先在旧实现证明从有 Provider 会话切换至无 Provider 会话时 badge 错误非空；
修复后还验证分享草稿 badge 与存储 providerId 都为 null。
`ShareDraftUiDeviceTest` 保留文字、图片预览与未发送断言。
命令和最终 API 29/36 双 flavor 全量结果见 [main 验证报告](../development/main-merged-verification.md)。

## Residual risk

无已知剩余数据绑定风险；本轮不替代物理设备和发布验收。

## Related records

- [HXA-056](../completion-records/HXA-056.md)
- [ADR-0014](../adr/0014-session-attachment-materialization.md)
- [main 合并后全量验证](../development/main-merged-verification.md)
