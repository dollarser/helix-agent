# ADR-CONNECTORS-001: Connector 能力包

Status: accepted
Date: 2026-09-16
HXA: HXA-124
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

用户希望以一个包发现和安装组合能力，但每个组件仍保有来源、凭据和调用边界。

## Decision

Connector 是 MCP endpoint 与 Skill snapshot 的产品组合，通过可迁移 ZIP/JSON 清单复用现有执行层，不新增执行引擎。持久化源格式、内容 hash、端点和 Skill 引用，不导出 Secret。

导入后新 endpoint 默认禁用，用户单独配置 SecretStore bearer、测试连接并选择工具后启用。修改认证别名同时禁用，不能自动向新目标发送旧凭据。安装失败如实显示部分结果/未启用快照，不假装跨文件与数据库的原子事务。

OAuth、版本所有权/会话 scope、签名索引各有独立 proposed 决策；能力包导入不自动批准这些扩展，也不宣称兼容所有平台包。

## Alternatives considered

把包做成新的执行 Runtime 或把 token 随包搬迁都扩大不必要的信任面；只复制提示词也不能形成发现、安装、配置和使用闭环。

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
