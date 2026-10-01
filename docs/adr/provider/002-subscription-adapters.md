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
- 账号订阅的固定展示顺序为 **Codex、Claude、Google Antigravity、GitHub Copilot、Grok (X Premium)**，不依赖数据库顺序或本地化名称。API Key 型套餐不进入此清单，仍属于普通 API 来源。
- Google Antigravity 作为 developer-only 适配器，以 `dsh-plugin-subscriptions` 的固定 MIT 源码版本作为协议依据：独立 Google OAuth code + PKCE、仅本机 loopback 回调、令牌刷新、`loadCodeAssist` 项目发现、认证模型目录以及 Gemini-shaped SSE。经所有者明确接受，Developer 实验性个人接入默认使用已核对的上游公开 installed-app 参数，允许构建者成对覆盖或同时置空禁用；该例外不代表 Google 授权 Helix 使用其客户端身份；界面必须披露非官方、资格、条款和额度风险。不得导入其他程序的用户令牌、轮换未授权 client 或绕过资格校验。
- 登录没有项目时要求用户先在官方客户端完成开通，不自动选择付费 tier、调用 onboarding 或购买套餐。普通 token 刷新保留登录 revision；过期刷新不能覆盖较新登录或复活退出。
- Antigravity 请求的原始签名 parts 留在订阅 Runtime 私有存储，绑定模型、登录 revision、消息和调用身份；工具回填保留原始函数 ID。签名不可放入业务参数，缺失/错绑/损坏不能用跳过校验的魔法值替代。工具仅在明确 STOP 且必要重放证据保存后交付；截断、断流及错误终态不释放待定调用。
- 回放不是 LRU 缓存。可信宿主在请求封装中绑定会话归属（探测明确为短期），以保留历史的实际引用保护分支和旧修订。清理使用有界元数据页及精确内容指纹，不将完整 parts 交给主进程；活跃 Runtime 或未确认结果阻止删除。清理候选后新发布/改变的记录保留；未知归属且仍有会话时保守保留。会话删除与私有证据清理不是跨进程原子事务，分别返回实际结果并支持再次清理，不用数据库删除成功冒充文件已清除。
- Google 生成使用现有增量事件与磁盘 spool，不设新的累计回复大小或总生成时长限制。OAuth/目录短请求、单 SSE 帧、工具扇出和未来请求/重放证据仍有资源边界；证据不可重放时报告失败，不伪造成功。当前新生成不进行不确定 POST 重放或跨 origin 自动回退。
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

## Decision history

- 2026-10-01：HXA-237 保留账号/连接/具体模型各自验证事实，增加高亮测试及会话选模修复入口，不把登录或填写版本视为连接通过。Codex 目录兼容版本默认 0.159.3，用户可在 Runtime 自有账号页面填写有界版本或恢复默认；只作用于下一次目录/连接请求的 client_version，不改 endpoint/凭据、不安装 CLI、不静默自动更新。版本更新不声称全部模型能力或服务商授权。

- 2026-10-01：所有者授权依次完成剩余工作；[HXA-233](../../development/tasks/HXA-233.md) 接受上述引用感知生命周期与清理边界。它是设计接受，不是实现或设备已通过。

- 2026-09-30：所有者授权参考 `V1ki/dsh-plugin-subscriptions` 增加 Antigravity、固定账号顺序并排除 consumer；后续明确 Key 型套餐仍归 API。实现与实际验证见[接入记录](../../evidence/development/subscription-antigravity-2026-09-30.md)。本决定不把真实账号、设备或发行授权记成通过。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)

## Decision history — 2026-09-30：Antigravity 构建配置

所有者授权移除内置 Google OAuth 客户端参数，不再复用上游应用身份。构建者须通过环境变量 `HELIX_ANTIGRAVITY_CLIENT_ID` 和 `HELIX_ANTIGRAVITY_CLIENT_SECRET` 同时提供自己获准使用的 installed-app 客户端参数；默认均为空，缺少配置时登录入口明确解释原因并禁用，其他 Provider 不受影响。不把这些参数当作移动端可保密的秘密，不使用 confidential-client 凭据；参数会进入构建产物，不记录到源码、日志、证据或版本历史。生成与刷新路径也校验配置，不通过隐藏按钮替代运行时边界。

## Decision history — 2026-09-30：公开客户端的有限恢复

所有者重新评估后接受 Developer 实验性接入恢复 `dsh-plugin-subscriptions` 已公开的固定客户端参数，保留成对环境变量覆盖，同时显式置空仍禁用登录。此决定替代上节默认空配置的要求，consumer 不打包订阅 Runtime。只对指定声明的两个精确值设置本地扫描例外，并针对 GitHub 对应命中申请误报放行；不关闭全局扫描，不编码或拆分参数规避识别。客户端参数不是用户令牌，也不构成上游注册人或 Google 对 Helix 的官方授权；不据此扩大商店或真实账号验收。PKCE、state、loopback 与私有进程边界保持有效。
