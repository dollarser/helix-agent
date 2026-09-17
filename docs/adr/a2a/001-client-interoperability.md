# ADR-A2A-001: A2A Client 与远端任务对账

Status: accepted
Date: 2026-09-16
HXA: HXA-077
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

远端任务可以提高互操作性，但必须与本地执行权限及恢复责任隔离。

## Decision

- 仅连接用户配置并通过检查的 A2A v1.0 endpoint，不托管 Server/webhook/peer mesh。远端 Agent 是外部服务，不是 Helix 执行域或远程 Worker。
- 通过 Helix 自有 A2aClientFacade，以 OkHttp/SSE 与 kotlinx.serialization 实现所需 Client；外部 DTO 不进入 core。当前范围为 Agent Card、JSON-RPC/HTTP+JSON、消息发送/流式、Task 查询/取消/订阅，不静默降级 v0.3 或承诺 gRPC、OAuth/mTLS、push。
- 远端 Skill 注册为可信 A2A origin 的动态工具，走统一准入和审计。endpoint、Card、协议 binding/version 和 Skill hash 固定，变更使对应旧绑定失效；远端描述不能制造本地能力。
- 请求、消息、SSE 和 Artifact 有独立上限、严格解析，失败不返回部分成功。工具调用 ID、task/context ID、输入 hash 和进度游标落盘。
- 重连只查/订阅原 Task；发送是否送达未知时待核查，不创建新 Task 重发。取消结果如实对账，不声称已撤回外部副作用。
- 远端内容均不可信，不继承本地 proof、Secret、scope、系统能力或工具表；建议的本地操作必须由父 Turn 新建工具调用。Artifact 以有界副本导入并验证。
- Android/R8/体积、许可及最小设备集仍须验收；可选 JVM TLS provider 的定点处理不允许掩盖其他 missing class。

## Alternatives considered

直接暴露 SDK DTO 会耦合 core；采用不能满足 Android/R8 的完整 Java SDK 没有必要；远端拥有本地工具权限不是 Client 互操作所需。

## Consequences

同一主题使用一份有效契约，避免并行实现各自解释权限和生命周期。代价是实现、UI、数据与恢复需要一起验证；accepted 表示决定，不代表相关任务全部完成。

## Verification

本次为现行决策整理，不新增功能通过结论。实现范围与实际命令结果以[实施状态](../../development/status.md)、对应 HXA 及完成记录为准；修改本契约后须覆盖成功、失败、取消、边界和恢复，不能用文档门禁代替设备/功能验收。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)
