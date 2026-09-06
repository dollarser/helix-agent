# ADR-0026: Copilot 第三方 Device Flow 身份侧载例外

Status: accepted
Date: 2026-09-05
HXA: HXA-119
Deciders: Project owner（2026-09-05 明确接受复用参考插件 client id 或其他已注册 Device Flow identity）
Supersedes: none
Superseded by: none

## Context

GitHub Device Flow 不需要 client secret，但仍要求一个已注册且启用 Device Flow 的 client id。
项目自有 GitHub OAuth App 尚未注册；参考插件 `dsh-plugin-subscriptions@0.7.0` 使用固定 client id
`Iv1.b507a08c87ecfe98`，并在取得 GitHub OAuth token 后调用非公开稳定契约
`https://api.github.com/copilot_internal/v2/token` 换取短期 Copilot token。

client id 是公开标识，不是 secret，也不表示其注册者或 GitHub/Microsoft 授权 Helix 使用。该身份、
internal endpoint、账号资格和风控策略都可能无通知变更。MIT 源码许可证同样不提供服务端身份授权。

## Decision

接受在 developer/Advanced 个人侧载实验中固定使用 `Iv1.b507a08c87ecfe98` 完成 GitHub Device
Flow，并用取得的 GitHub token 验证 Copilot entitlement、交换短期 Copilot token。界面和完成记录
必须明确标为第三方、非官方、可能导致账号或服务中断；不得宣称 GitHub、Microsoft、VS Code
Copilot Chat 或 client id 注册者授权 Helix。

两个 token 都只能进入独立 CLI Runtime UID 的 Android Keystore vault。主 App、Binder status、日志、
异常和 UI 不得返回 token。HXA-119 只实现登录、轮询、取消、超时、拒绝、资格交换与 logout；不调用
模型、不注册 Provider/Tool/Job，也不把订阅登录解释为 Helix Tool Approval。

实现必须固定 client id 和 endpoint，不搜索、轮换或借用其他未知 client id。若身份失效、Device Flow
被关闭、entitlement 拒绝或协议漂移，必须 fail closed 且不保存 GitHub token。商店或官方发行仍要求
项目自有身份及可核验服务商授权，不能由本例外推导。

## Alternatives considered

1. 注册项目自有 GitHub OAuth App：是正式路线，但普通自注册 App 当前不能证明可访问 internal token
   exchange；后续获得服务商支持时替换本例外。
2. 等待官方 Android/bionic Copilot SDK runtime：风险最低，但 HXA-117 已证明当前无对应构件。
3. 动态寻找其他公开 client id：身份和供应链不可审计，不选择。
4. 直接复制参考插件：不选择；仅依据官方 Device Flow 规范独立实现最小协议。

## Consequences

- 本决定仅修改 [ADR-0021](0021-third-party-subscription-protocol-adapter.md) 和 proposed
  [ADR-0022](0022-github-copilot-sdk-android-base.md) 中对 Copilot 固定身份/internal endpoint 的禁止，
  且只限个人侧载登录实验；其余隔离、发行和官方 SDK 结论不变。
- 免费 GitHub 账号可以验证授权与 entitlement 拒绝；只有具备 Copilot 资格且交换成功才写入 vault。
- 身份撤销、账号限制、endpoint 漂移和条款变化是已接受但不可消除的运行风险。

## Verification

- JVM 测试覆盖 device response、pending、`slow_down`、拒绝、过期、取消、超时、交换失败不落盘、
  成功落盘与 logout。
- API 29/36 arm64-v8a 验证网络权限、独立 UID vault、redacted status 和可见 Activity。
- 边界脚本拒绝模型 endpoint、外部 credential 导入及向主 App 暴露 token。

## Reconsider when

- GitHub/Microsoft 提供项目自有 OAuth identity 或官方 Android transport。
- 固定 client id、Device Flow 或 internal endpoint 停止工作或服务条款明确禁止该使用方式。
- 需要进入商店、官方发行或生产 Provider。

## References

- [GitHub OAuth Device Flow](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps)
- [GitHub OAuth App best practices](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/best-practices-for-creating-an-oauth-app)
- [GitHub Copilot SDK authentication](https://docs.github.com/en/copilot/how-tos/copilot-sdk/auth/authenticate)
- [dsh-plugin-subscriptions](https://github.com/V1ki/dsh-plugin-subscriptions)
