# Bug Fix: Activity 输入快照绑定当前会话

Status: fixed
Date: 2026-09-29
Related HXA: HXA-214, HXA-215
Affected modules: app composer UI

日期：2026-09-29。所有者授权在合并后的 main 上执行模拟器验证并修复问题，随后追加本机 SGLang 多协议测试。

## Problem

双渠道 API36 的切换会话测试间歇性读不到上一会话输入。

## Impact

Activity 恢复时可能将旧会话缓冲与当前页面绑定，导致后续缓存读取不符合用户正在编辑的会话身份。

## Root cause

双渠道 API36 回归中，切换新会话后保存上一会话输入的测试间歇性读到空缓存。检查发现 `rememberSaveable(sessionId, ...)` 的输入只控制当前 composition 的重建，Activity 保存状态恢复并不按该输入验证快照归属；旧 `ConversationDraftBuffer.Saver` 直接按快照内部 sessionId 恢复。

## Fix and invariants

新增 `saverFor(sessionId)`：只有快照会话与当前页面会话一致才恢复；不同会话返回空恢复值，重新创建当前会话缓冲，并从当前会话自己的缓存加载。不会把旧会话文本写入当前会话，也不改发送回执、缓存 I/O 或权限语义。新增单元测试同时验证同会话恢复保留内容、跨会话快照拒绝；设备发送测试增加输入期间当前会话不变的断言。

## Alternatives considered

不删除同会话旋转恢复，不增加等待时间掩盖绑定问题；校验快照身份后保留既有文件缓存与发送链路。

## 同轮测试维护

- 导航测试显式处理首次启动提示；系统权限页等待真实页面到达。developer 验证文件权限子页逐级返回，consumer 验证该专属入口不存在并正常返回。
- 跨会话清理测试补齐另一会话的实际输入，继续断言错误 requestId 不能删除它；不依赖“删除不存在文件返回 false”的旧 Room 语义。
- 最新消息修订夹具使用当前编辑时钟，避免把已消费的 revision 0 当作新的输入。
- 实际进程恢复的旧工具状态预期调整为 `NEEDS_REVIEW`，Turn 仍为 `INTERRUPTED`，未启动工具仍为 `CANCELLED`；无重放、无新调用、审计与重复恢复断言保留。
- 新的恢复脚本经当前“应用导航 → 全部会话”入口选择会话，不再假设默认启动页是历史列表。全部脚本只绑定独占模拟器 serial，不使用已连接真机。

## Regression verification

双渠道 app JVM tests、Debug APK 和 AndroidTest APK 编译已通过；最终 API36 双渠道功能76/76、大字体24/24、两个普通进程恢复闭环及三协议9/9通过，见[设备证据](../evidence/development/merged-api36-regression-2026-09-29.md)。初轮失败日志保留在 `build/merged-api36-*`，不将重跑覆盖为首轮通过。SGLang `30008` 的 OpenAPI 自报 `SGLang compatibility proxy`；协议结果只证明该实际部署链路，不外推原生服务端接口范围。

## Residual risk

此次定向模拟器回归不覆盖全部 OEM、低内存和所有时序；没有取得用户真机上的同类复现证据。图片测试不证明真实视觉模型识别质量。

## Related records

- [输入缓存与发送解耦](2026-09-29-conversation-input-cache.md)
- [本轮模拟器证据](../evidence/development/merged-api36-regression-2026-09-29.md)
