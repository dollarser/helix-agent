# ADR-0021: 第三方订阅协议适配器候选边界

Status: proposed
Date: 2026-09-05
HXA: HXA-114
Deciders: pending
Supersedes: none
Superseded by: none

## Context

M11 只允许官方 CLI 在独立 UID 中持有订阅凭据。`dsh-plugin-subscriptions` 提供另一条路线：
它不运行 Codex CLI 或 Claude Code，而是复用相应 OAuth client identity，自行交换、刷新并把
access/refresh token 写入 DSH 私有 `auth.json`，随后直接调用 ChatGPT Codex backend 或
Anthropic Messages subscription endpoint。Claude 路线还可读取并写回 Claude Code credential
store。插件自身声明 MIT，但代码许可证不构成服务端、OAuth client 或消费订阅接口授权。

本地已安装版本为 `0.6.0`；2026-09-05 查询 npm registry 的 latest 为 `0.7.0`，说明协议与
实现仍会变化。上游 README 明确把 approval policy 交给另一个 DSH 插件，并且该适配器没有
Android Binder、持久 job journal 或按 `jobId` 对账契约。

OpenAI 当前官方资料描述由 Codex CLI 完成 ChatGPT 登录并在本地保存其凭据；Anthropic 官方
资料描述由 Claude Code 登录并安全保存凭据。尚未找到允许任意第三方产品复用这些 CLI client
identity、消费订阅 endpoint 并代表其他用户分发的官方文档。此处是“缺少可核验授权”，不是
法律结论。

## Decision

提议把该项目限定为参考实现与研究 fixture，不直接成为 Helix 生产依赖，也不复制其 OAuth
client identity、CLI impersonation header、token import/write-back 或私有 endpoint 调用。

若继续研究生产路线，必须新建后续 HXA，并在实现前同时取得：供应商可核验的第三方客户端
授权或公开支持文档；项目所有者接受对现行凭据边界的明确变更；独立 companion UID 内的
Android Keystore token ownership；不向主 App 返回 token；完整 Dispatcher/Policy/Approval/
Verification/Audit 转换；以及 ADR-0007 的持久 jobId 查询、不明确结果停泊和绝不重放。

在这些前置条件成立前，M11A 不注册 Provider、不进行真实登录、不请求 subscription endpoint。

## Alternatives considered

1. 直接把 npm 包和 Node 打入 CLI Runtime：最快，但把未授权的 OAuth 身份、token store、
   DSH host 依赖与私有 endpoint 一起变成生产边界，未选择。
2. 只移植 protocol translation：可复用部分 MIT 思路，但仍不能解决服务端授权与 token owner，
   且必须独立实现 Helix 安全/恢复协议；仅可在后续有授权的 HXA 中评估。
3. 继续等待官方 Android SDK/CLI：满足原 M11 边界但当前不可用；仍是最低凭据风险路线。
4. 使用官方 API key Provider：仓库已有生产实现，不使用消费订阅额度；是当前受支持方案。

## Consequences

- HXA-114 可复现地记录第三方实现事实，但不处理或生成用户凭据。
- 本提议不修改 accepted ADR-0007，也不推翻当前 Provider 架构；未经所有者接受不产生生产代码。
- 即使 ADR 被接受，服务商授权、依赖许可证闭包、Android Node/DSH 可运行性、撤销/删除、
  限额和账号封禁风险仍需独立证据。
- 本地 `0.6.0` 与 latest `0.7.0` 的漂移要求任何后续 Spike 固定 tarball integrity 和源码 commit。

## Verification

已执行：

- `node --check <plugin-dir>/lib/index.js`：本地预构建入口语法通过。
- `npm view dsh-plugin-subscriptions version dist.tarball dist.integrity license repository --json`：
  2026-09-05 返回 latest `0.7.0`、MIT 与 registry integrity。
- `./scripts/audit-dsh-subscriptions-spike.sh <installed-plugin-dir>`：固定本地版本、关键文件 hash，
  并验证 plugin-owned token store、Claude credential 双向访问、直接 endpoint/client identity、
  CLI identity header，以及缺失 Helix Android job/IPC 信号。

Required before acceptance：服务商对第三方消费订阅客户端的可核验授权；完整依赖许可证清单；
不含真实 token 的 Android arm64 Node/DSH 启动 Spike；独立 UID/Keystore/删除威胁模型；项目
所有者明确接受凭据边界变化。

## Reconsider when

- OpenAI 或 Anthropic 发布第三方应用可使用的订阅 OAuth/API 与分发条款。
- 上游改为调用受支持的官方 SDK/CLI，并不再导入或自行保存其 token。
- 项目所有者决定仅作个人侧载实验，并另行接受账号、协议漂移和停止服务风险。

## References

- [Provider 与订阅账号边界](../architecture/provider-mcp-skills-modes.md)
- [Companion Runtime 生命周期](0007-companion-runtime-lifecycle.md)
- [CLI Runtime 执行底座候选](0020-cli-runtime-execution-base.md)
- [dsh-plugin-subscriptions](https://github.com/V1ki/dsh-plugin-subscriptions)
- [OpenAI Codex authentication](https://help.openai.com/en/articles/11381614-api-codex-cli-and-sign-in-with-chatgpt)
- [OpenAI Terms of Use](https://openai.com/policies/terms-of-use/)
- [Claude Code authentication](https://docs.anthropic.com/en/docs/claude-code/getting-started)
- [Anthropic legal center](https://www.anthropic.com/legal)
