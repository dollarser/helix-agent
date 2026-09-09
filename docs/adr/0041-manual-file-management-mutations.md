# ADR-0041: 独立文件管理器的变更操作

Status: accepted
Date: 2026-09-09
HXA: HXA-180
Deciders: Project owner（明确要求完善独立文件管理能力，能力不必全部开放给 Agent）
Supersedes: none
Superseded by: none

## Context

ADR-0036 的首轮共享目录仅浏览，SAF 也仅浏览和导入导出，不能完成独立文件整理。本轮用户明确授权完善独立文件管理能力；手动操作不应被 Workspace region 或模型审批限制绑定。

## Decision

部分扩展 [ADR-0036](0036-manual-shared-storage-root.md) 的只读首轮：独立文件管理器增加共享目录和可写 SAF 的新建目录、重命名、复制、移动、删除。按实时系统权限和文档 Provider 实际能力执行；API29 写入需 WRITE_EXTERNAL_STORAGE。保持 consumer/developer 同一手动功能，不修改 Agent resolver、Tool 注册、风险或审批。

手动变更组件与工具存储分离。复制流式写入临时兄弟节点，重读哈希验证后发布，移动在目标确认后删除源；同名不默认覆盖，明确覆盖时先保留旧目标以便失败恢复。目录递归操作和逐项取消提供真实部分结果。共享/SAF 删除为明确确认的永久删除；Workspace 保留现有回收站。根目录和 Workspace 内部状态不允许作为变更对象。

## Alternatives considered

- 把共享根变为 Agent 全盘 scope：扩大权限，与用户要求不符。
- 继续只读并依赖导入导出：不足以独立整理文件。
- 把所有手动写操作加入 ToolPipeline：耦合模型审批、输出大小和手动操作，未采用。

## Consequences

手动界面可用能力可多于 Agent。SAF Provider 不支持的操作必须明确失败，不能承诺所有存储提供者功能相同。跨 Provider 和进程死亡不能承诺数据库级原子性；临时或备份文件保持可识别，源删除失败不得报完全移动成功。渠道审核边界仍按 ADR-0013/0036。

## Verification

HXA-180 验证复制/移动/同名/目录/取消/权限撤销/失败保源/范围隔离，独立 API29/36 模拟器验证 UI 和平台适配。实现与主机、双 API 验收已完成，见 [HXA-180](../completion-records/HXA-180.md)。

进程中断的显式恢复由 [ADR-0042](0042-manual-transfer-recovery-journal.md) 部分扩展；不改变本决策的手动/Agent 权限分离。

## References

- [DocumentsContract](https://developer.android.com/reference/android/provider/DocumentsContract)
- [Document capabilities](https://developer.android.com/reference/android/provider/DocumentsContract.Document)
- [ADR-0012](0012-capability-first-advanced-grants.md)

## Reconsider when

Provider 发布/恢复语义不能可靠验证，或用户要求跨操作事务、持久传输队列、共享回收站时重新评估。
