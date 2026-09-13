# Bug Fix: Codex 系统上下文格式与重复重试输入

Status: fixed
Date: 2026-09-10
Related HXA: HXA-190
Affected modules: runtime/cli-app, app

## Problem

原会话在新增 Harness 系统上下文后连续出现 `HTTP_ERROR`；另一次重复点击重试出现 `the request must end with the user message`。

## Impact

正常的新用户消息被服务端拒绝；对失败的重试记录再次重试时，会在本地构建请求前失败。用户容易将两类故障误认为网络或上下文耗尽。

## Root cause

Codex 订阅适配器复用了公共 Responses 编码器，将 SYSTEM 作为 input message 发送。订阅端对此格式拒绝；新增系统上下文时未适配顶层 instructions。

重试新建 Turn 不持久化新的 USER 消息；再次重试将该重试 Turn 当作原始输入，导致历史无法补回用户消息。

## Fix and invariants

订阅编码器将全部 SYSTEM 文本按顺序合并到顶层 instructions，并从 input 移除。用户/模型/工具历史保留，工具名称只做一次别名转换，其他 Provider 编码不变。HTTP 拒绝只记录状态码，不记录响应正文或凭据。

重复重试在当前会话中寻找目标 Turn 之前最近的原始 USER Turn，复用其输入和附件检查；不使用后续新消息，Goal 绑定仍取目标 Turn。没有删除或改写旧会话。

## Alternatives considered

扩大上下文/网络超时不会修复 HTTP 参数拒绝。删除系统提示词会丢失工作区与模式指导；清空会话会丢失用户数据。故修复订阅边界和重试源定位。

## Regression verification

`scripts/debug/2026-09-10/verify-subscription-instructions.py`：4 项订阅编码/别名回归和 1 项重试源定位回归通过，两包和单项诊断 APK 构建通过。

`scripts/debug/2026-09-10/check-subscription-instructions-phone.py` 在当前 OnePlus 6T 上只执行 `systemInstructionCompatibility`：同一合成 HELIX_OK 输入，旧格式 HTTP 400（安全分类命中 system），新格式 HTTP 200，完整终态通过；共 1 项设备用例/2 次合成请求，耗时约 4.7 秒。未向模型发送用户原会话，未执行工具。

两包已覆盖安装，账号和会话保留；诊断测试 APK 已卸载并返回 Helix。

## Residual risk

该对照证明系统指令格式缺陷及修复，不代替原会话的完整人工验收。未来其他 HTTP 拒绝应按具体状态和受控诊断继续定位，不统一归因于网络。

## Related records

- [当前状态](../development/status.md)
- [文件上下文决策](../adr/0045-session-relative-file-tools.md)
