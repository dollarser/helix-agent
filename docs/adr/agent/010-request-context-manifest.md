# ADR-AGENT-010: 模型请求上下文清单

Status: proposed
Date: 2026-09-22
HXA: HXA-217
Deciders: Project owner（授权本轮调试与可观察性需求设计）

## Context

聊天气泡、持久历史和模型请求不等价。当前 ModelCall 保存模型配置、用量、请求 ID 和系统提示指纹，不能解释某条用户输入何时被使用，也不能完整重建历史 wire request。复制全部正文会增加手机存储和敏感数据副本。

## Decision

保留一个持久事实源及独立 UI/模型投影。在模型请求最终组装、压缩和容量准入之后、Provider 调用之前，为 modelCallId 写入版本化逻辑上下文清单。覆盖普通生成与摘要调用；摘要请求标明用途、来源与覆盖边界，不伪装成用户回复。

最小字段：formatVersion、sessionId/turnId/modelCallId、请求用途、Provider/模型身份及有界非机密参数、adapter 标识/版本、按请求顺序的消息来源 ID 和 role、生成段来源及内容 hash、压缩 checkpoint/保留边界、system prompt 与工具 schema 指纹、附件 ID/hash、估算分项与上限、该请求使用的 input IDs。不得只拿“当时消息总数”冒充完整来源顺序。

来源与最终请求项非一一对应时记录映射，例如压缩摘要、工具结果投影、附件物化和系统生成段；不伪造 messageId。input 的历史追加和请求使用分别记录，同一 input 可以在后续历史中被多次引用，首次接入标记唯一。新加入的 steer 经过重新压缩后，来源也必须可追踪。

清单正文以独立 ContentRef 保存，ModelCall 关联身份/版本/引用；512 字符有界审计只存 ID、hash 与状态，不能塞入完整清单。禁止认证头、token、含凭据 URL、Secret 明文和图片/正文副本。工具名/schema 哈希可能透露使用信息，跟随会话隐私删除，不作为无敏感性的遥测上传。

区分 prepared（清单已落盘）、started（应用已进入 Provider 调用，不证明服务器收到）、finished（获得本地终局）、unknown/interrupted（网络/进程中断）。状态与既有 ModelCall 生命周期关联，不建立第二套执行状态机。读详情从持久事实投影，不通过重组当前上下文冒充当年证据。

清单默认每次最多 1 MiB、全局独立清单正文最多 64 MiB；实际实现须测试含 8192 个来源 ID 的最坏边界。超限保存明确的 incomplete/omitted 原因和统计，不静默截断成“完整”；超过总量优先淘汰最旧已终结调用的清单正文，保留可识别 tombstone，不淘汰活跃调用且不删除消息/检查点/附件。存储失败不应仅因附加诊断阻断原本合法的模型请求；沿已有 ModelCall 状态记录可获得的缺失原因，不能承诺磁盘完全不可写时仍有日志。

调用详情按需展示；正常聊天不展开协议。扩展 HXA-211 的 JSONL 记录类型及 schema 版本/兼容规则，旧记录无清单保持缺失，不回填成虚构快照。导出区分完整、被淘汰、不完整和旧版本未采集。清理/导出遵循快照与引用计数，不因清单引用错误地永久保留所有历史。

本期清单是逻辑 ModelRequest 证据，不是 Provider 最终 wire bytes；hash 可比较但不可逆。精确网络抓包或完整请求正文存档不在本期，不新增设置开关、上传服务或运行时插件。

## Alternatives considered

- 保存每次完整 HTTP 请求：大量重复内容及敏感数据，不作为默认方案。
- 只读 UI 截图或 JSONL 推断：缺少系统/工具/压缩和精确请求边界，不足以诊断。
- 任意诊断缺失就拒绝模型调用：将观测故障变成任务故障，不采用；原有强制审计与安全门禁仍保留。

## Consequences

新增有界诊断制品及引用关系，不改变工具准入和预算。可解释“哪些来源用于此次请求”，不能证明模型已理解/执行，也不承诺永远完整保存或原样网络重放。

## Verification

[HXA-217](../../development/tasks/HXA-217.md) 用 loopback 核对三类 Provider 的实际请求和逻辑清单，覆盖摘要、steer、附件、缺失/淘汰、Secret 过滤、旧数据/JSONL 兼容和真实进程中断。当前无新功能验收。

## Reconsider when

精确 wire 排错有明确需求时，再评估用户主动启用、脱敏、配额和保留期限；不得把本清单对外宣称为逐字节请求备份。

## References

- [JSONL 导出](005-session-jsonl-export.md)
- [模型结果和预算](006-model-data-budget-boundaries.md)
- [输入交付](008-user-input-delivery.md)
- [研究记录](../../research/conversation-context-and-steering.md)
