# Bug Fix: MCP 搜索入口和结果被模型工具上限截断

Status: fixed
Date: 2026-09-08
Related HXA: HXA-157
Affected modules: app

## Problem

tools.search 搜索成功后，下一次模型请求仍可能没有对应工具 schema；注册较晚的搜索入口自身也可能不可见。

## Impact

当其他已准入工具占满模型 64 项预算时，用户已启用的 MCP 工具无法通过既有渐进发现流程使用。小目录也受影响。

## Root cause

McpToolDiscovery.visible 将非 MCP 工具全部排在 MCP 之前；ChatService.modelTools 随后 take(MAX_TOOLS)。原回归使用很少的非 MCP 工具，未覆盖两种列表上限的组合。

## Fix and invariants

先排入已准入搜索入口与当前有效发现结果，再补充原顺序工具，按名称去重。最多 16 项发现窗口在 64 项预算内可达；准入已过滤、schema 已失效或已移除的工具不能被重新排入。实际 Dispatcher/Policy/Approval 不变。

## Alternatives considered

不提高 ModelRequest.MAX_TOOLS 或无限装入 catalog；这会改变模型请求边界且不能解决持续增长。仅把搜索入口前置也不足以保证搜索结果可达。

## Regression verification

McpToolDiscoveryTest 的 discoveryAndLoadedWindowSurviveTheModelToolLimit、searchedSmallCatalogToolAlsoSurvivesTheModelToolLimit 均在旧实现失败，在修复后通过。覆盖搜索入口、16 项结果、去重、小目录和停用。实际命令及结果见 HXA-157 完成记录。

## Residual risk

其他未搜索工具仍受既有 64 项预算限制；本修复不引入全类型工具搜索。未新增设备或真实服务验收；平台 JNI/Binder 开放问题与本修复无关。

## Related records

- [HXA-157](../completion-records/HXA-157.md)
- [HXA-127](../completion-records/HXA-127.md)
