# Bug Fix: 动态工具描述导致会话持续失败

Date: 2026-10-05
Status: fixed
Related HXA: HXA-231

## Problem

工具搜索成功后，同一会话的后续消息持续在本地请求组装阶段失败。

## Impact

已加载的工具描述影响后续请求，不只是当前工具调用；未证明所有新会话都受影响。

## Root cause

2026-10-05 模拟器最新会话在 `tools.search` 找到 `code.linux.run` 后，请求组装抛出 `tool description exceeds 1024 chars`。原描述为 900 字符，`FileToolArguments.modelSchema` 追加路径说明后变成 1124 字符，超过 Helix 自身的 1024 字符限制。后续请求继续曝光已发现工具，因而重复失败；失败发生在模型请求发出之前，不是服务端额度错误。

## Fix and invariants

- 工具用途与参数约束分开：路径帮助放入 `inputSchema.properties`，不再追加到工具描述。
- 保留 Linux 原描述、参数原说明、required 和其余 schema 约束；补全 `files`、`sources` 数组的路径帮助。运行环境内的 `cwd`、`script` 不改写。
- 保持描述上限及权限验证；不截断安全说明、不清空会话、不自动重放失败操作。
- 模型 schema 长度校验错误包含具体工具名和实际长度，便于诊断。

设计参考 [Claude 动态工具发现](https://www.anthropic.com/engineering/advanced-tool-use) 与 [MCP 工具结构](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)：保留按需加载，参数规则放在参数 schema。1024 是 Helix 限制，不是这些产品或标准的统一限制。

## Alternatives considered

清缓存仅暂时绕开触发条件；截断描述会丢失工具语义；提高全局上限掩盖重复拼接。因此修复模型投影职责，并保留有界验证。

## Regression verification

主机已通过 `:app:testDeveloperDebugUnitTest`、`:app:testConsumerDebugUnitTest`、`:core:model:test`、双渠道 `assemble*Debug`、`detekt` 和格式化。构建日志位于忽略目录 `build/tool-schema-fix-check.log`、`build/tool-schema-fix-final.log`。

回归覆盖实际 Linux 工具与后台 Job 的模型投影、参数说明保留，以及合法 1024 字符工具经搜索加载后连续三次曝光且不泄漏到其他会话。

## Residual risk

本轮仅主机验证；未安装模拟器、未调用真实模型，不能据此声明原会话已在设备恢复。原先的 `content_omitted` 非法参数和 QuickJS 权限拒绝是独立的工具执行问题，不由本修复绕过。

## Related records

- [工具描述契约](../adr/tools/001-descriptor-contract.md)
- [当前状态](../development/status.md)
