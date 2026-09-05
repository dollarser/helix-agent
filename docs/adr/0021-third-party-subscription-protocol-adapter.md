# ADR-0021: 第三方订阅协议适配器候选边界

Status: accepted
Date: 2026-09-05
HXA: HXA-114, HXA-115, HXA-116, HXA-117
Deciders: Project owner（2026-09-05 明确要求修改“官方 CLI 持有凭据”边界并采用 HXA-114 方案）
Supersedes: none
Superseded by: none

## Context

M11 只允许官方 CLI 在独立 UID 中持有订阅凭据。`dsh-plugin-subscriptions` 提供另一条路线：
它不运行 Codex CLI 或 Claude Code，而是复用相应 OAuth client identity，自行交换、刷新并把
access/refresh token 写入 DSH 私有 `auth.json`，随后直接调用 ChatGPT Codex backend 或
Anthropic Messages subscription endpoint。Claude 路线还可读取并写回 Claude Code credential
store。插件自身声明 MIT，但代码许可证不构成服务端、OAuth client 或消费订阅接口授权。

HXA-114 审计时本地版本为 `0.6.0`；HXA-116 已将本地更新为 npm latest `0.7.0`，并与 registry
tarball 逐文件比对无差异。`0.7.0` 覆盖 Codex、Claude、Grok 与 GitHub Copilot，也增加多账号、
图片/视频和 X search 等能力；这些能力不是 Helix Tool Approval。该适配器仍没有 Android
Binder、持久 job journal 或按 `jobId` 对账契约。

OpenAI 当前官方资料描述由 Codex CLI 完成 ChatGPT 登录并在本地保存其凭据；Anthropic 官方
资料描述由 Claude Code 登录并安全保存凭据。尚未找到允许任意第三方产品复用这些 CLI client
identity、消费订阅 endpoint 并代表其他用户分发的官方文档。此处是“缺少可核验授权”，不是
法律结论。

## Decision

采用第三方订阅协议 adapter 路线，替代当前无法实现的 Android 官方 CLI 路线。adapter 必须
明确标注为非官方，只能在独立 CLI Runtime UID 内通过用户主动 OAuth 获得并持有自己的 token；
主 App 永不接收 token。禁止读取或写回浏览器、Claude Code 或其他 App/CLI 的 credential。

生产接入分 HXA 实施：独立 companion UID 内的 Android Keystore token ownership；不向主 App
返回 token；完整 Dispatcher/Policy/Approval/Verification/Audit 转换；以及 ADR-0007 的持久
jobId 查询、不明确结果停泊和绝不重放。

供应商可核验授权或公开支持文档仍是商店发布门禁。缺失时只允许 developer/Advanced 侧载实验，
不得宣称官方支持；真实登录必须由后续 HXA 的可见 UI、撤销和设备测试门禁后开放。

## Alternatives considered

1. 直接把 npm 包和 Node 打入 CLI Runtime：最快，但把未授权的 OAuth 身份、token store、
   DSH host 依赖与私有 endpoint 一起变成生产边界，未选择。
2. 只移植 protocol translation：可复用部分 MIT 思路，但仍不能解决服务端授权与 token owner，
   且必须独立实现 Helix 安全/恢复协议；仅可在后续有授权的 HXA 中评估。
3. 继续等待官方 Android SDK/CLI：满足原 M11 边界但当前不可用；仍是最低凭据风险路线。
4. 使用官方 API key Provider：仓库已有生产实现，不使用消费订阅额度；是当前受支持方案。

## Consequences

- HXA-114 可复现地记录第三方实现事实，但不处理或生成用户凭据。
- 本决定不修改 accepted ADR-0007；HXA-115 只实现独立 Runtime vault，尚未注册生产 Provider。
- 即使 ADR 被接受，服务商授权、依赖许可证闭包、Android Node/DSH 可运行性、撤销/删除、
  限额和账号封禁风险仍需独立证据。
- 本地 `0.6.0` 与 latest `0.7.0` 的漂移要求任何后续 Spike 固定 tarball integrity 和源码 commit。
- GitHub 已公开 Copilot SDK 的 GitHub OAuth App 认证路线，但 HXA-117 证明当前官方 runtime
  只发布 glibc/musl arm64 构件，不能直接在 Android/bionic 加载；当前打包路线依
  [ADR-0022](0022-github-copilot-sdk-android-base.md) 停止。项目所有者随后在
  [ADR-0023](0023-copilot-third-party-device-flow-identity.md) 接受仅限个人侧载实验的固定 Copilot
  client identity/internal token endpoint 例外；商店与官方发行限制不变。
- Grok consumer subscription 与 xAI developer API 是不同边界；缺少第三方消费订阅集成授权时，
  插件的 Grok CLI identity/proxy 路线不得升级为商店能力。

## Verification

已执行：

- `node --check <plugin-dir>/lib/index.js`：本地预构建入口语法通过。
- `npm view dsh-plugin-subscriptions version dist.tarball dist.integrity license repository --json`：
  2026-09-05 返回 latest `0.7.0`、MIT 与 registry integrity。
- `./scripts/audit-dsh-subscriptions-spike.sh <installed-plugin-dir>`：固定本地版本、关键文件 hash，
  并验证 plugin-owned token store、Claude credential 双向访问、直接 endpoint/client identity、
  CLI identity header，以及缺失 Helix Android job/IPC 信号。

HXA-115 acceptance：独立 UID/Keystore vault、删除与篡改 fail-closed 威胁模型，以及 API 29/36
arm64-v8a 设备测试。服务商对第三方消费订阅客户端的可核验授权、完整依赖许可证清单和不含
真实 token 的 Android adapter 启动 Spike 仍是后续 OAuth/模型调用与分发门禁。

## Reconsider when

- OpenAI 或 Anthropic 发布第三方应用可使用的订阅 OAuth/API 与分发条款。
- 上游改为调用受支持的官方 SDK/CLI，并不再导入或自行保存其 token。
- 项目所有者决定仅作个人侧载实验，并另行接受账号、协议漂移和停止服务风险。

## References

- [Provider 与订阅账号边界](../architecture/provider-mcp-skills-modes.md)
- [Companion Runtime 生命周期](0007-companion-runtime-lifecycle.md)
- [CLI Runtime 执行底座候选](0020-cli-runtime-execution-base.md)
- [GitHub Copilot SDK Android 底座](0022-github-copilot-sdk-android-base.md)
- [Copilot 第三方 Device Flow 身份侧载例外](0023-copilot-third-party-device-flow-identity.md)
- [dsh-plugin-subscriptions](https://github.com/V1ki/dsh-plugin-subscriptions)
- [OpenAI Codex authentication](https://help.openai.com/en/articles/11381614-api-codex-cli-and-sign-in-with-chatgpt)
- [OpenAI Terms of Use](https://openai.com/policies/terms-of-use/)
- [Claude Code authentication](https://docs.anthropic.com/en/docs/claude-code/getting-started)
- [Anthropic legal center](https://www.anthropic.com/legal)
- [GitHub Copilot SDK authentication](https://docs.github.com/en/copilot/how-tos/copilot-sdk/auth/authenticate)
- [GitHub Copilot SDK OAuth setup](https://docs.github.com/en/copilot/how-tos/copilot-sdk/setup/github-oauth)
- [xAI consumer terms](https://x.ai/legal/terms-of-service)
