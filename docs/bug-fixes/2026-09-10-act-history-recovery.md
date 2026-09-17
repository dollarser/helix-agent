# Bug Fix: Act 空参数、历史恢复与消息呈现

Status: fixed
Date: 2026-09-10
Related HXA: HXA-190, HXA-191

范围：HXA-190；2026-09-10 所有者授权优先修复，真机对话交由人工验证。

## Problem

只读检查最新会话发现一次 `write` 调用的模型参数长度为 0。工具层按既有规则将空参数视为 `{}`，以 `INVALID_ARGUMENTS` 拒绝，不执行写入；但消息历史保存原始空字符串。后续回填和新消息构建 `AssistantToolCall` 时触发 `tool call arguments must not be blank`，连续三轮以 `INTERNAL` 结束。这是历史构建失败，不能归因于网络。

Responses 解码器另有明确缺口：只处理参数 delta，忽略 `response.function_call_arguments.done.arguments`。未保留该次服务端原始 SSE，因此不能断言当次服务端是否仅发送最终参数。

## Impact

文件任务失败与历史构建错误会阻断后续对话；过多默认展开的信息影响审批和阅读。

## Root cause

见 Problem 中的当前设备证据：模型参数/工作区引用与 Harness 历史/工具契约不一致，不能将其统一归因于网络。

## Fix and invariants

- 参数完成事件补发尚未收到的后缀；完整值与已有前缀冲突时返回协议错误，不执行矛盾的工具调用。
- 工具步骤持久化及历史读取都沿用执行层的空参数 → `{}` 语义。旧记录无需数据库迁移即可读取，调用 ID、原始审计与拒绝结果均保留；必填参数仍由 schema 拒绝，模型可以据此修正下一次调用。
- 未启用“忽略所有错误轮次”：这会丢失已执行工具的结果。当前修复保留完整调用/结果对；其他无法解释的损坏 JSON 仍失败，不伪造成功。
- 模型回复增加气泡；双方复制按钮位于气泡外下方。当前和历史错误均在气泡外，使用主题错误色，开始新回复不会恢复成默认文字色。
- 网络及超时提示增加网络、DNS、代理排查与恢复后继续发送说明。


### 网络行为

Codex 模型请求保留连接 20 秒、写入 20 秒；读取与总调用时间无限制。OkHttp 使用现有默认连接故障恢复；Helix 不在收到部分回复或执行工具后自动重新发送整个请求。显式认证拒绝可刷新凭据重试，普通模型失败由用户选择重试或发送新消息。此次未增加回复时长或总正文长度限制，也未用重复请求掩盖错误 DNS。

## Alternatives considered

清空会话、盲目重试或仅扩大预算不能修复错误契约；保留历史结果并提供一致的解析与反馈。

## Regression verification

执行入口：`scripts/debug/2026-09-10/verify-act-recovery.py`。限定文件格式化通过；`:provider:openai-responses:test --tests '*ResponsesStreamDecoderTest'` 28/28、`:app:testDeveloperDebugUnitTest --tests '*ChatHistoryBuilderTest'` 14/14；`:app:assembleDeveloperDebug :runtime:cli-app:assembleDebug` 通过。未启动或借用模拟器，未发送真实模型对话；构建成功不等于真实 Act 验收。

两包已覆盖安装至当前 OnePlus 6T，保留登录、网络设置和会话数据，启动成功。安装记录位于忽略目录 `build/debug/2026-09-10/act-recovery/install/`；未提交或推送共享工作树。

## Residual risk

真实模型完成率与设备交互仍由所有者人工验收。后续 ADR-WORKSPACE-001 已将文件路径进一步改为会话相对路径，三目录前缀不再是普通文件的强制要求；本记录中的旧批次描述只代表当时交付。

## Related records

- [ADR-WORKSPACE-001](../adr/workspace/001-session-paths.md)
- [当前状态](../development/status.md)
