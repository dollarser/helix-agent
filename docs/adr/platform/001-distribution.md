# ADR-PLATFORM-001: 产品完整性与渠道分发

Status: accepted
Date: 2026-09-16
HXA: HXA-120, HXA-121, HXA-122, HXA-123
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

能力优先的单设备产品需要最小渠道差异，同时如实表达当前制品与发布证据。

## Decision

Standard 是完整产品默认体验，不是只读或聊天试用版。consumer/developer 是构建能力集合，不能与审批模式混为一谈；会话授权预设两变体均可提供，实际能力仍以制品和系统授权为准。

developer 单 APK 包含私有进程 Runtime，consumer 排除对应组件。其他能力原则上保留在渠道允许范围，不因笼统“风险较高”删除。渠道差异落在 manifest、依赖、Capability 和 listing，不分叉 Agent Core、用户数据或主要 UI。

Google Play、国内商店与直接分发是产品目标；上架仅以真实审核/可下载证据声明。限制能力必须记录目标渠道、具体约束、受影响组件与不能通过披露/授权保留的理由；优先完成合法声明和审核，必要时保留 SAF/MediaStore 或确定性自动化替代。

脚本解释不等于可以下载 DEX/JAR/native 自更新或绕过渠道政策。applicationId 与数据升级由发布契约维护，不要求用户为切换会话权限重新安装应用。依赖版本可以为兼容性更新，须固定可复现版本并同步许可、锁文件与验证，不由 ADR 中的过期版本阻止修复。

## Alternatives considered

把 Standard 人为裁成弱产品不能满足目标；为所有渠道统一删除一个渠道不允许的能力扩大了限制；独立 Runtime 安装包增加使用成本。

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
