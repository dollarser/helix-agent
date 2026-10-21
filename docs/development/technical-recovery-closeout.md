# 技术恢复与业务动作边界收尾

## 授权与取舍

Owner 要求先完成知识库/分享，再优化重试；同时明确浅 harness 不等于零运行保障。第一阶段已独立完成，见[知识库/分享记录](knowledge-sharing-closeout.md)。本阶段仅代码和主机故障注入，不操作设备、真实服务/账号、分享目标或用户终端环境，不提交/推送。

采用小的共用 `TechnicalRecovery` 时间策略，而非业务恢复规划器。自有恢复最多两次额外尝试，约 1/2 秒指数退避、±20% 抖动；新重试须在十秒恢复准入窗口内且小于调用剩余时间。该窗口控制重试准入，不是普通模型生成的总时长上限；MCP 所有连接/业务等待受原 deadline 的 `withTimeout`，取消保留类型并传播；连接清理另最多一秒，退出前不释放实际执行槽位。

## 当前行为

| 边界 | 恢复/交付契约 |
| --- | --- |
| Dispatcher | 每个业务 ToolCall 单次 executor；无 maxAttempts 覆盖和审批退款重跑入口。保留 schema、权限、当前授权复核、未消费 admission proof、取消/期限、审计及原序结算；新模型动作仍走正常授权 |
| 自配直接推理 | Provider 能力由包装委托保留；仅空响应且可重试错误恢复。任何 text/reasoning/tool fragment 后不重启；最多两次额外 ModelCall，失败记录及用量保留；重试次数、退避和已用时间持久化 |
| 内置账号模型 | 保留原推理/配额不重放边界，不把其包装变成一般重试许可；401 在原账号、原 endpoint、响应体交付前最多刷新后再请求一次；换账号/退出和取消仍阻断迟到数据 |
| 自配 HTTP | 默认拒绝重定向；关闭隐式连接恢复，POST body 单次使用，503/Retry-After 不重发业务体。安全 GET 的服务端明确 503/Retry-After:0 跟随属于只读协议恢复，实测两次 GET 后成功；没有再叠加一层 GET 业务重跑 |
| MCP bootstrap | 只对已分类的连接丢失/超时等，且尚未 tools/call 的阶段重连；每次重核网络范围，固定原 endpoint/凭据，凭据变化停止。TLS/参数/凭据等错误不当作暂时网络失败 |
| MCP 已提交业务 | 接受一次 POST；回包丢失/超时报告 UNKNOWN 和原本地 call ID，要求核查，不制造服务端查询句柄。服务没给 operation 查询协议则无法保证核实远端是否完成/停止 |
| MCP 资源 | 初始化失败也关闭 HTTP engine，保留原异常；已收到有效结果时，清理失败不丢结果，另外返回 connectionClosed=false。重连次数、是否 tools/call 已提交进入受限审计及短错误事实 |
| 等待/读取 | 保留原身份的 jobs 状态/await/collect、终端 attach、Mobile Use 观察及 SDK 的 Last-Event-ID SSE 结果重连，不把它们当作动作重执行 |
| 模型错误投递 | 错误短摘要直接保留，增加 sideEffectFree/requiresReview/callRef。成功结果另报 executorPayloadTruncated 与原输出 hash，避免把模型预览截断与更早丢失混成同一件事；callRef 不冒充失败结果可读句柄 |

MCP 的 NOT_SENT 仅指尚未 tools/call，不能据此声称 OAuth 刷新、远端初始化资源等完全没有效果，所以失败的 sideEffectFree 仍保守为 false。远端 readOnly/idempotent hints 仍不是权限、幂等保护或重试证明。直接推理恢复可能产生额外远端计算/费用，不承诺网络 exactly-once；不会因此重执行已完成的本机工具。

## 发现并修正的问题

`provider is WireModelProvider` 无法识别应用的 `BoundModelProvider`；改为显式只读能力，默认关闭，HTTP 推理声明，账号/订阅边界保留。恢复分支的 `model.automatic_retry` 原不在 Room journal 诊断白名单，现同源常量接线。reasoning 曾不计 outputSizeBytes，补以 receiving 事实拒绝重启。审批重用只限尚未执行阶段的实时准入复核，不误删为退款重跑。

MCP/HTTP 的 one-shot 防护通过真实本机 socket 的丢回复、503/Retry-After:0、重定向场景核对，不以设置 retryOnConnectionFailure=false 就宣称绝无重发。MCP SDK 版本为锁定的 0.15.0；核对其本机编译产物中的 POST 与 SSE 重连分支，保留读取/观察重连。

## 验证

最终 `scripts/check-all.sh --all` 通过，日志 `build/technical-recovery-all.log`：283 项主机脚本测试、文档/ADR/国际化/秘密扫描、Spotless、Detekt、全部 debug/release lint、全量 JVM 测试、两渠道及独立 runtime debug/release 打包、release-smoke APK 编译/lint、依赖锁和 debug/release 渠道/runtime 边界。静态阶段 2m49s、测试/打包 1m26s、smoke 519ms。

针对性命令 `./gradlew :core:model:test :core:policy:test :core:agent:test :provider:api:test :extensions:mcp:test :tools:framework:test :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest detekt :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest` 通过，日志 `build/technical-recovery-regression-final.log`。app 两渠道分别 1452/1600 项，零失败/错误，各五项既有条件跳过；MCP 83、framework 259、core agent 301、provider API 121 项均零失败/错误/跳过。两渠道 AndroidTest 编译通过。

本轮新增或替换二十八项针对性 JVM 用例：共用时序/网络分类四项，模型策略/真实循环六项，HTTP 四项，MCP 连接/未知结果/截止时间/取消/清理与交付九项，应用 Provider 包装及模型事实三项，单次执行/审批消费替换两项。具体网络故障使用真实本机 HTTP sockets 与受控延时/失败注入，不访问真实服务或账号。旧通用自动重试行为测试按新契约替换，原权限、UNKNOWN、取消、排队、原调用身份和并发断言保留。

中间/最终证据位于 ignored `build/technical-recovery-*`；源输入摘要与六个 APK 摘要存本地 evidence JSON。设备 **not requested**；AndroidTest 编译不代表设备执行。未安装、提交或推送。

## 日志调研边界

本轮另行只读核对结果、Runtime journal、审计与 logcat 的存储/可达性。没有新建通用日志读取或搜索工具，没有开放整个审计库。`tools.read_result` 仍仅返回同会话已成功验证的保存结果；失败通常只有摘要，无法承诺存在完整失败日志。通用大结果前缀未保证优先保留每种业务关键字段，超出 Runtime/Dispatcher 上限的内容也不保证补取；这是后续结果投影/授权读取的独立改进范围，不冒充本轮已修复。

## 后续授权收尾

Owner 后续限定模拟器和真实模型验收。已执行检查、已知失败修复和仍未验证的范围统一记录在[结果日志收尾](result-log-closeout.md)，不将原阶段主机结果、健康服务请求或有限合成用例解释为全部设备与网络故障通过。
