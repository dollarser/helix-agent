# ADR-0035: 会话内显式模型选择

Status: accepted
Date: 2026-09-09
HXA: HXA-166
Deciders: Project owner（明确要求 Provider 模型下拉及会话中切换，先模型后推理强度）
Supersedes: none
Superseded by: none

## Context

现有首次绑定仅允许空 Provider 会话，不能满足用户要求的会话内模型切换。Provider 已有连接测试返回的模型目录；会话已有 providerId/modelId 列，不需要 schema 迁移。

## Decision

保持首次绑定的空值保护，新增用户选择专用接口，在无活动轮次、无待确认发送时修改未归档会话的未来模型目标。选择来自通过连接测试的 Provider 及其目录或默认模型，不自动发送。保持历史消息、工具结果、授权管线，下一次发送重新走目标出网检查。选择不改 Provider 的默认模型或目录。

菜单显示模型与 Provider，先模型后推理。切换重置推理强度为默认，其他目录模型不沿用该 Provider 默认模型的推理探测结论和能力徽标。活动模型调用保持目标稳定；底层 UPDATE 拒绝存在非终态轮次的会话。草稿只改内存，首次发送才落库。

## Alternatives considered

不通过修改全局 Provider model 实现：会改变其他会话。不复用首次绑定并移除其空值保护：保留分享草稿既有边界。无需创建新会话，因为用户明确要求保留当前对话继续切换。

## Consequences

会话目标可能变化，历史调用快照记录各轮模型。模型目录是可选择性证据，不是每个模型均完成能力探测的证据；未探测模型使用默认推理，不显示已验证能力。

## Verification

本决定的交互范围由所有者明确授权；实现验收见 [HXA-166 完成记录](../completion-records/HXA-166.md)：API36 fixture 已覆盖选择、重新打开、历史保留、非终态拒绝、推理重置和实际 HTTP 请求模型标识；真实多模型服务验收未在本轮执行。

## Reconsider when

需要动态拉取目录、每模型能力探测、正在生成时切换或为模型持久化不同推理偏好时单独评估。

## References

- [路线](../development/roadmap.md)
- [Provider 与模式](../architecture/provider-mcp-skills-modes.md)
