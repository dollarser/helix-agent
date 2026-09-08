# Bug Fix: 模型工具回填误用时间线短摘要

Status: fixed
Date: 2026-09-06
Related HXA: HXA-037, HXA-062, HXA-100
Affected modules: app

## Problem

真实模型读取浏览器标题/标题节点时，browser.snapshot 已成功返回完整结构，模型仍反复查询，
直至 MODEL_CALL_LIMIT。实际回填 JSON 在第一个 node token 中间被截断，标题字段完全丢失。

## Impact

大于 512 字的文件、浏览器和其他工具结果无法完整进入后续模型上下文，结构字段、可操作 token
和正文尾部丢失，导致任务失败或无效重复调用。

## Root cause

ChatService.toolResultDraft 将完整成功 payload 通过 boundedSummary 截为时间线预览。
工具持久化结果本身完整，但 TOOL 消息与后续模型请求只使用了短摘要。

## Fix and invariants

成功 TOOL 消息保留 Dispatcher 已限制大小并验证的完整 payload；时间线仍使用短摘要。
模型可见内容先写入消息存储，历史重建读取同一持久化正文。既有错误/取消状态和模型 token
预算保持不变；过大的后续模型请求仍由预算拒绝，不靠破坏结构的字符截断维持运行。

## Alternatives considered

没有增加浏览器重复查询预算或按某个工具特判补标题，这不能修复一般结构化结果丢失。

## Regression verification

- 原真实浏览器固定用例 8 次调用后 MODEL_CALL_LIMIT；修复后一次快照读取即完成标题和标题节点。
- 新增离线生产 ChatService/真实 adapter/文件工具回归，验证超过 512 字的尾标记进入下一次请求，
  且持久化 TOOL 消息仍包含尾标记。API 34 实际执行 1/1 通过，无跳过。
- 完整 JVM/Lint 回归按最新源在收尾记录中单独更新。

## Residual risk

旧历史中已被截掉的内容无法凭空恢复，需要显式重新读取；不自动重放旧工具。
保留完整结果增加模型输入，仍受既有 Turn token 预算限制。

## Related records

- [M10 收尾跟进](../development/m10-closure-followup.md)
- [HXA-037](../completion-records/HXA-037.md)
