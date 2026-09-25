# ADR-AGENT-005: 会话执行追踪与 JSONL 导出

Status: accepted
Date: 2026-09-25
HXA: HXA-211, HXA-217
Deciders: Project owner

## Context

Helix 需要两类互相关联但职责不同的可追踪事实：

1. 用户主动导出的单会话 JSONL，用于调试、历史检索与评测；
2. 每次 ModelCall 的轻量 request context manifest，用于解释“这次请求实际看到了哪些已持久消息/来源”。

历史上两者分别记录为 ADR-AGENT-005 与 ADR-AGENT-005，但它们属于同一个“会话执行追踪”功能：Room/ContentStore 仍是运行事实源，manifest 是请求级元数据，JSONL 是用户触发的只读投影。二者都不能成为第二套执行状态机或 replay 入口。

## Decision

### 1. Room/ContentStore 保持唯一执行事实源

执行过程不实时双写 JSONL。JSONL 和 request manifest 都是现有 durable facts 的可追踪投影：

- 不参与 Turn recovery；
- 不授权或 replay ToolCall；
- 不补造不存在的历史事实；
- 不保存 Secret、OAuth token、cookie、Approval proof 或可重用凭据。

### 2. ModelCall 保存轻量 request context manifest

每个 ModelCall 可以保存紧凑的 request context manifest，用于说明该请求实际绑定的上下文来源。manifest 记录稳定 identity/sequence 与必要来源元数据，不复制大正文。

manifest 的目标是：

- 关联 request 与已持久 Message/Turn/context checkpoint；
- 支持 JSONL 导出、诊断和故障复现；
- 不保存完整 wire request/response；
- 不保存模型内部 reasoning；
- 不把当前 UI state 当历史事实。

旧 ModelCall 缺 manifest 时输出 unknown/unavailable，不根据时间邻近或当前配置猜造。

### 3. 用户主动导出单会话 JSONL

首版格式为 `helix.session-export` / `formatVersion=1`，UTF-8、LF、一行一个 JSON object。导出只覆盖用户选择 session 的 durable facts，并有显式 header/complete footer。

至少可以表达：

- session；
- message；
- Turn；
- ModelCall；
- ToolCall/ToolResult；
- context compaction/checkpoint；
- request context manifest；
- 已记录 usage、finish/error/review state；
- content identity/reference metadata。

JSONL 是关系快照，不是 event-sourcing 日志。导出 sequence 是导出文件顺序，不冒充执行先后。

### 4. identity 与关联

sessionId、turnId、messageId、modelCallId、toolCallId、artifact/content identity 原样保留。exportId 只标识一次导出。

无独立 ID 的派生记录使用版本化确定性复合键，并标记 derived。缺失引用必须显式表示，不静默关联到“看起来像”的对象。

### 5. 大内容与安全边界

小文本可以内联；大正文、工具输出、附件和二进制使用稳定 content/artifact reference，不一律 base64。

reference_only、missing、changed、redacted、omitted_limit 等状态必须可见。不得输出 App 私有绝对路径、带授权信息的临时 URI 或凭据 header。

脱敏后内容的 hash 不冒充原字节 hash。

### 6. snapshot 与失败语义

允许导出活动会话“截至某一致快照的 durable facts”。数据库读取、ContentStore 复核和 SAF 写入不得长时间占住 Room write transaction。

取消、空间不足、目标撤权、正文变化、进程死亡或输出关闭失败不能显示成功。只有规定记录与 footer 全部完成才标记成功。

首版 JSONL 不是完整备份：reference_only 可能只在原设备可解析；不包含导入、执行回放、跨设备恢复或自动上传。

### 7. UI 与工具边界

导出由用户在指定会话主动触发，是应用功能，不因为存在该入口就新增 Agent Tool。导出不启动 Provider、Tool、Goal、PRoot 或 recovery。

request manifest 的详情 UI 可以后续增加，但持久字段存在不等于必须建立独立诊断页面。

## Decision history

- **2026-09-17/18**：接受用户主动单会话 JSONL 导出，HXA-211 交付。
- **2026-09-23**：接受轻量 ModelCall request context manifest，HXA-217 交付；当时单独记录为 Agent 010，现并入本功能 ADR。
- **2026-09-25**：文档治理将 JSONL 与 request manifest 合并为一个长期“会话执行追踪”决策文件；没有改变已交付格式或运行时事实源。

## Alternatives considered

- 每次执行实时追加 JSONL并与 Room 双写：增加双写一致性与恢复复杂度，拒绝。
- 直接复制数据库/ContentStore：不是稳定用户格式，容易夹带内部配置与其他会话。
- 只导出 Markdown/UI 文本：丢失调用 identity、状态、usage 和 review/recovery 关系。
- 全量内联大正文与附件：资源成本不可控，采用有界内联与 reference。
- 用 manifest 保存完整 request 或 reasoning：扩大敏感数据和存储成本，不采用。
- JSONL 作为 replay/跨设备恢复输入：不属于当前功能。

## Consequences

会话执行具有稳定的只读分析接口，又不增加第二套运行时事实源。代价是需要维护格式版本、快照一致性、内容引用、缺失语义和 manifest 兼容性。

HXA-211/217 的完成记录保留当时实际验证；本 ADR 只维护当前功能契约。

## Verification

实际交付证据见 HXA-211、HXA-217。长期验收至少覆盖：

- 多 ModelCall 与并行 ToolCall 的 identity/顺序；
- review/unknown/failed/cancelled 状态；
- request manifest 与实际 context identity 对应；
- 重复导出稳定 record identity；
- Unicode/转义/每行合法 JSON；
- active session 一致快照；
- 大内容 reference、missing/changed/redacted；
- Secret/credential/proof 排除；
- SAF cancel/write/close failure；
- 大会话资源上限；
- 独立解析器无需 App 私有绝对路径。

设备验证遵守项目 owner-explicit 规则：只有项目所有者在当前任务明确要求时，AI 代理才运行模拟器/真机验证；未要求时记录 `not requested`，已要求但尚未完成时记录 `pending`。GitHub Actions 不执行设备测试。

## Reconsider when

需要自包含归档包、批量会话导出、导入/跨设备恢复、完整 wire request capture、长期事件日志或格式 major version 时重新评审。

## References

- [Agent 主题入口](README.md)
- [上下文压缩](002-context-compaction.md)
- [HXA-211](../../completion-records/HXA-211.md)
- [HXA-217](../../completion-records/HXA-217.md)
- [实施状态](../../development/status.md)
