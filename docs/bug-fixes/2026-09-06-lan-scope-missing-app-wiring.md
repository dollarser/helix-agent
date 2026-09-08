# Bug Fix: Advanced LAN scope 缺少产品接线

Status: fixed
Date: 2026-09-06
Related HXA: HXA-068, HXA-100
Affected modules: app

## Problem

用户配置的局域网 MCP 服务在连接测试时返回 SCOPE_VIOLATION。实际模型固定评测因此无法开始。

## Impact

Advanced 用户无法通过产品设置授权精确局域网服务，配置服务本身不能解决 capability 拒绝。

## Root cause

底层 SSRF 策略支持显式 LAN host/port 范围，但 app 为 MCP、http.fetch 和 Dispatcher 长期传入空集合，
设置中也没有创建该范围的入口。高敏数据出网规则并不等于 LAN capability 范围。

## Fix and invariants

Advanced 设置提供精确 origin 新增、列表和撤销，规范化为 host/port 后交给现有策略。
同步持久化成功才发布新授权；撤销写入失败会显示失败，当前进程先撤销，不声称已持久撤销。
拒绝路径、凭据、查询、通配符和超过 64 个条目。MCP、http.fetch 及 Dispatcher 每次读取当前范围。
服务导入、模型和 MCP annotation 都不创建范围，也不创建 Tool Approval Proof。
Standard 与 metadata 地址仍遵循原策略。

## Alternatives considered

仅从 MCP 配置自动授予 LAN 范围会把服务导入误当作用户授权，因此保留独立显式范围入口。

## Regression verification

- 5 项 JVM 回归：持久化/撤销、输入与数量边界、Standard/metadata/端口不匹配、写入失败。
- API 34 设置页 1 项：实际新增后重建存储读取，再撤销并重读。
- 真实模型与 Android MCP SDK 的 4 项固定评测通过；合成服务执行计数为 0/1/1/1，取消后未重发。
- 当前源全量门禁记录见收尾检查点；早期语言定位与测试编译失败日志保留。

## Residual risk

此补充覆盖 MCP 与 http.fetch 的现有 LAN 策略入口，未宣告其他网络客户端全部覆盖。
长稳测试已经启动，其已安装 APK 早于这项接线，不能作为新增代码的 24 小时证明。

## Related records

- [HXA-068](../completion-records/HXA-068.md)
- [收尾检查点](../development/m10-closure-followup.md)
