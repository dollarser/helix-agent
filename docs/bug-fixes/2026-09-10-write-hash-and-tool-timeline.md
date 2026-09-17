# Bug Fix: 可选写入 hash 与会话工具摘要

Status: fixed
Date: 2026-09-10
Related HXA: HXA-190, HXA-191

## Problem

创建同一个新文件前两次携带空 expectedSha256 被拒绝，第三次伪造 64 位 hash 却成功。工具记录展开后默认展示多行完整参数及结果，阅读成本高。

## Impact

简单创建多花两次模型调用，模型得到错误的纠错反馈。长正文参数挤占会话空间。

## Root cause

schema 的可选字符串允许空值，执行解析却拒绝；通用错误没有指出坏 hash。写入逻辑在文件不存在时丢弃非空前置 hash，绕过了 AtomicFileWriter 已有的“目标缺失也必须失败”校验。

工具行把参数和结果直接交给 ExpandableSummary；所谓折叠仍占三行加五行，不是隐藏详情。

## Fix and invariants

write v3 将空白可选 hash 当作未提供；非空非法值返回字段专属错误，不猜测替换值；合法非空值原样进入原子写入校验，文件不存在或版本不同都失败。新建文件不要求 hash，覆盖仍要求 overwrite，edit 的必需 hash 不放宽。版本变更使旧精确契约批准不能被复用；沿用 ADR-WORKSPACE-001 的变更契约版本策略。

会话直接展示简短工具行：名称、从操作和目标提取的用途、真实状态。参数/结果默认隐藏，点击查看详情；不展示文件正文作为用途，也不虚构模型意图。待审批与需恢复的操作保留。完整审计和模型返回不受 UI 改动影响。

## Alternatives considered

只加提示词不能消除 schema/执行不一致；无条件忽略非空 hash 会破坏并发覆盖防护。完全移除详情减少排错能力，复用现有展开组件即可保留低成本入口。

## Regression verification

scripts/debug/2026-09-10/verify-write-timeline.py 执行 WriteToolTest、ToolPurposeTest 和 developer 构建；覆盖空 hash 新建、已有文件无覆盖许可拒绝、伪造 hash 不创建文件、原有匹配/冲突、取消/配额/发布失败及用途不暴露正文。设备布局用例同步增加默认隐藏与展开步骤，本轮未执行，由所有者人工验收 UI。实际主机计数与安装见 status.md。

## Residual risk

hash 是读取时文件内容的版本指纹，不能保证生成的内容在业务上正确。用途是工具操作摘要，不是模型未提交的任务理由。详情沿用现有参数和有界结果摘要，不新增任意历史输出读取接口。

## Related records

- [ADR-WORKSPACE-001](../adr/workspace/001-session-paths.md)
- [工具结果清单](../evidence/development/builtin-tool-result-review-2026-09-10.md)
