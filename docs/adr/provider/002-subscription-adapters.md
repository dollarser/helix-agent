# ADR-PROVIDER-002: 订阅适配、任务路由与流式结果

Status: accepted
Date: 2026-09-16
HXA: HXA-114, HXA-115, HXA-116, HXA-117, HXA-119, HXA-142, HXA-143, HXA-144, HXA-190
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

订阅账户和流式模型请求需要独立模块所有权、明确路由与可恢复结果，但不应引入另一套工具审批或独立 APK 安装流程。

## Decision

- 订阅模型是 ModelProvider，不是 Dispatcher 下的普通工具执行目标。链路为 Provider → 订阅 client → 私有 Binder/PFD → developer 的 :subscriptions → 服务端。
- 使用明确标注的第三方协议适配器，通过自身登录流程交换/刷新 OAuth；不导入浏览器 Cookie、其他 App/CLI 的凭据。正常主进程 API 仅接收公开目录与模型结果，token 留在订阅模块；共享 UID 不构成 token 隔离保证。
- consumer 不包含订阅实现，developer 包含完整模块。发行资格和第三方服务条款需要真实渠道证据，不能把技术可用当发布许可。
- Copilot 的 developer/Advanced 个人侧载 Device Flow 使用已明确接受的固定 client ID `Iv1.b507a08c87ecfe98` 与 endpoint，标明第三方、非官方及服务中断风险，不宣称注册者授权 Helix。不得搜索或轮换未知身份；Device Flow/entitlement 失败时不保留无效登录凭据。商店/官方发行仍需自有身份及可核验服务商授权。协议适配不等于采用官方 Copilot SDK。
- 每个模型 Job 显式携带平台/账号/目标路由，不从 prompt 或模型名前缀猜平台。认证、endpoint 与模型绑定变更时重新校验，不把凭据发给新 origin。
- Runtime 冷绑定仅由用户发起的连接、登录、修复或真实请求触发。被动 Registry 刷新不启动 Runtime。请求期间遵守 Android FGS 生命周期；解绑、取消和系统超时释放资源，不自动重启。
- 增量读取按 Job ID、generation 和偏移绑定，用有界单批传输与完整性校验。预览不是终态；成功结果与已交付前缀一致，完整 journal 为结算事实。Binder 丢失按原 Job 对账，不重发生成请求；工具片段不能提前触发执行。
- 不额外用固定总响应时长或累计字节数截断订阅长回复；单批传输保持有界。显式取消、I/O 失败、协议终态、系统限制、实际磁盘/内存不足及完整性校验仍生效，不承诺无限资源。
- 连接与能力验证遵循同主题模型决策，真实账号/配额调用不进入默认 hermetic 门禁。

## Alternatives considered

官方 CLI/SDK 只有在 Android 执行、依赖和权限边界获得证据后才可替换适配器，不保留独立的过时候选 ADR。把凭据转交主进程或靠回调代替持久结果都不能解决对账问题。

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
