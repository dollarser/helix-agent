# ADR-PERMISSIONS-002: Chat/Plan 审阅与内置元数据操作

Status: accepted
Date: 2026-09-16
HXA: HXA-012, HXA-192
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

任务模式规定工作职责，授权模式规定操作是否询问；二者不能互相冒充。

## Decision

Chat/Plan 以只读研究和计划审阅为职责，不因会话审批预设扩大用户文件或外部业务副作用。只读操作还要满足该模式的工具曝光及动态风险边界；工具名称或第三方 readOnlyHint 不能作为只读证明。

允许闭合代码白名单中的内置 METADATA 操作维护当前会话/Turn 的计划、Todo 或运行元数据；以可信来源、操作类别和当前绑定判定，不允许任意工具宣称 METADATA。用户文件、外部网络业务写入、安装和执行不借此豁免。

计划审阅固定计划版本与执行绑定，不一次性批准计划中所有工具。Act/Goal 执行仍由当前会话授权和精确审批决定。普通研究可以直接文本结束，不强制创建计划表单。

## Alternatives considered

全部禁止元数据写入会阻止保存计划；按工具自报放开 METADATA 又会扩大权限。采用闭合内置分类与当前会话绑定。

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
