# Bug Fix: HXA-200 审计与取消恢复

Status: fixed
Date: 2026-09-15
Related HXA: HXA-200

## Problem

ADR-PERMISSIONS-003第8条所需的偏好、来源和规则修订没有进入持久审计；停止后的迟到批准还能改变旧记录。验收还发现Broker单独取消时，调用结算CANCELLED而审计记录TOOL_FAILED。

## Impact

旧审批卡、执行开始前的新偏好与最终结果难以对账；过期界面事件可能把已经停止的PENDING记录变成APPROVED。不能据六个gap历史提交直接关闭HXA-200。

## Root cause

Repository映射丢弃规则ID、revision和scopeRef，Dispatcher只保留折叠后的偏好值。Broker先写决定再查等待者；Dispatcher仅凭Turn取消信号识别审批等待中止，没有共享的取消类型。

## Fix and invariants

- `snapshotFor`在同一同步读中返回有效偏好及适用规则元数据。更新/删除偏好不改写已捕获快照。
- 审计分别保存最近评估、呈现审批时、承诺执行开始时的快照；未到达阶段为null。字段显式白名单、版本1，来源/范围引用哈希化，无参数、输出或Policy正文。历史审计缺字段仍可读取，不推断为ALLOW。
- Broker将活跃等待者检查、取消检查、持久决定写入串行化；取消/重启后的旧卡被拒绝，PENDING仍表示用户没有决定，不伪写DENIED。用户先完成决定、随后停止的历史决定仍保留，未开始的证明不消费。
- 共享`ApprovalWaitCancelledException`使Broker取消与Turn取消得到一致的CANCELLED_BEFORE_START审计；其他异常继续失败并保留原异常，不重放未知副作用。
- 验收本地模型夹具时发现连接检查误拒绝`Completed`后合法的Usage统计；修为检查最后一个非Usage事件，仍要求非空文本和Completed且没有Error。对应主机反例覆盖缺少Completed。

## Alternatives considered

不把PENDING统一改为DENIED或删除审计历史；不把末尾Usage当任务失败；不增加Room schema，只扩展现有redactedPayload。

## Regression verification

最终命令、数量和设备进程证据收录于HXA-200验收记录。真实用户路径通过`ChatService.send/stop`、本地HTTP模型夹具、生产Dispatcher/Broker/Room验证。进程恢复使用一次安装、两次直接instrumentation，中间force-stop并校验PID变化；不使用会重置应用数据的两个独立Gradle安装任务冒充恢复。

## Residual risk

HXA-201工具设置与审批卡产品界面仍属于独立任务；本次不宣称相关UI或全部产品重构验收完成。真实账号、外部服务、长稳与发布也不由本次本地fixture替代。

## Related records

- [ADR-PERMISSIONS-003](../adr/permissions/003-dispatch-and-audit.md)
- [先前C1/C2修复](2026-09-15-tool-preference-start-boundary.md)
- [整体验收复核](../evidence/development/hxa200-acceptance-2026-09-15.md)
- [JGit构建补丁](../../config/jgit/README.md)
