# Bug Fix: 工具设置范围、旧卡契约与保存反馈

Status: fixed
Date: 2026-09-16
Related HXA: HXA-201

## Problem

Slice 3 仅提供 GLOBAL 写入；审批卡保存 ALLOW 按最新注册描述符绑定，未验证用户看过的契约；保存失败或工具已移除时没有明确反馈。宽屏重组测试不能证明真实 Activity 重建。

## Impact

用户无法只限制一个会话或工作区，旧卡可能允许尚未审阅的新版本；保存动作没有可信结果。设置与实际分派的工作区上下文也需要一致。

## Root cause

设置模型缺少选定范围；生产偏好 source 未从会话解析持久目录。卡片回调只传工具身份，不传呈现时的 contractHash，UI 忽略返回结果。原停止回归直接构造 Turn，却没有 ChatService 所拥有的活跃运行。

## Fix and invariants

- 在应用服务中提供全局、会话和 Workspace 选择，写入与恢复默认只影响选定范围；读取复用现有 resolver，DENY > ASK > ALLOW 不变。生产查询通过当前持久会话目录解析 Workspace，UI 不直接访问 DAO。
- 切换范围立即移除旧行；异步结果只显示在匹配范围内。服务变更流刷新有效状态与来源，保存仍在 IO 上串行执行。
- 卡片携带呈现时契约；ALLOW 必须与当前契约相同。实际动态风险决定是否显示未来 ALLOW，高风险精确批准契约不变。
- 保存显示实际解析结果、契约变化、工具不可用或无法确认保存；取消继续抛出，不变成成功。本次批准与未来设置保持独立。
- 新增真实 ChatScreen 点击、Room/dispatcher 校验、Activity recreate 与保留数据的跨进程重启测试。停止回归复用真实 ChatService.send/stop，测试工具版本独立注册，避免同进程重复版本冲突。
- 组合设备运行会积累会话；范围菜单测试先滚动至目标再点击，不能把屏幕外节点的点击当作选择成功。
- 全套对照发现工具管线可接收来自独立会话库的上下文；缺少对应持久会话时不猜测默认 Workspace，也不因查询抛错阻断无关模型请求。真实存在的会话仍按其当前目录解析，存储读取失败不会被吞掉。设备断言覆盖缺失会话不会继承默认 Workspace 规则。

## Alternatives considered

不扩大 Capability，不调整 ADR-PERMISSIONS-003 优先级，不用最新契约替代用户审阅契约；不以宽度变化代替 Activity 重建，不删除失败测试。

## Regression verification

最终命令、设备阶段、主机结果和全套基线对照归入 HXA-201 验收记录；局部构建通过不代表完整产品设备套件或发布通过。

## Residual risk

偏好不是系统权限或高风险批准证明；卡片保存的是明确标示的全局设置。未扩展跨进程写设置协议、工具市场、账号验收或完整产品旅程。本地模拟器结果不代表真机长稳与发行验收。

## Related records

- [ADR-PERMISSIONS-003](../adr/permissions/003-dispatch-and-audit.md)
- [HXA-200 完成记录](../completion-records/HXA-200.md)
- [本次验收与全套对照](../evidence/development/hxa201-acceptance-2026-09-16.md)
