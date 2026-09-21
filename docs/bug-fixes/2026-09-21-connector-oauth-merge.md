# Bug Fix: Connector OAuth 合并前缺陷修复

Status: fixed
Date: 2026-09-21
Related HXA: HXA-126
Affected modules: app Connector/MCP, extensions/mcp

日期：2026-09-21。所有者明确要求修复候选分支并合入 main。原候选 `7c618e0e`、main 同步 `dd40563d` 及失败探针 `a80bf832` 的事实见[合并前复核](../evidence/development/hxa-126-merge-review-2026-09-21.md)。本记录只覆盖当前切片，HXA-126 仍开放。

## Problem

早期候选直接使用外部 state 作为文件路径，已复现删除 attempt 目录外的 JSON；同时缺少回调/资源绑定、机密 verifier 存储及真实刷新接线。

## Impact

已安装候选存在私有 JSON 被破坏和凭据误绑风险，过期登录无法可靠恢复。未合入 main 的旧代码不构成当前主分支事故。

## Root cause

普通原子文件写入不等于一次性消费；UI 登录成功不等于 OAuth 凭据生命周期完成。旧 fixture 只覆盖顺序成功和简单失败，未覆盖路径、进程与并发边界。

## Fix and invariants

- 外部 state 先限定 ASCII 字符集和长度，再访问文件；拒绝 symlink 和内容 state 不一致。原子移动到独占 claimed 文件后消费，防止并发兑换；解析失败不会删除目录外的 JSON。
- code verifier 进入 SecretStore；普通 journal 使用版本 1 codec，只含元数据。回调校验安装包专属 scheme、authority、完整路径、重复参数及返回的 issuer；取消/替换登录通过持久 generation 拒绝迟到结果。
- OAuth token 与绑定及过期资料写入加密记录；MCP 实际 handshake 和 tools/call 前执行绑定验证与刷新。同一 owner 的并发请求重新读持久状态，成功只刷新一次；刷新前标记不明结果窗口，进程死亡/错误后不自动重试 rotation。原有 bearer 路径保留。
- 厂商识别采用精确 HTTPS 主机，移除未知归属的默认 Client ID。泛用 metadata 校验 resource/issuer，每个网络端点继续经过现有 SSRF/DNS gate；禁用重定向并有界读取响应。浏览器登录与 Device Flow 都是用户动作。
- 撤销结果与本地清除分开显示；取消保留取消语义；错误不回显原始 token response，成功事件不携带 token。i18n、精确 manifest 路径与 Compose 资源/URI lint 一并修复。

Slack 特有规则采用[官方 PKCE 说明](https://docs.slack.dev/authentication/using-pkce/)与 [auth.revoke](https://docs.slack.dev/reference/methods/auth.revoke/)，并没有把 `auth.test` 当撤销或把当前 App 注册配置当作已验。设计范围见 [ADR-CONNECTORS-002](../adr/connectors/002-oauth.md)。

## Alternatives considered

不通过关闭检查、禁用整个 OAuth 能力或增加后端代持来绕过问题；沿用现有 SecretStore、MCP transport 和 Policy。无需改变 Room、CLI Runtime 或模型工具权限。

## Regression verification

- `./scripts/check-all.sh --all`：exit 0；完整 source、spotless、detekt、各变体 lint、JVM、Debug/Release 组装和 APK 边界通过。最终日志保存在工作树 `build/oauth-merge-review/`。双 flavor 测试 APK 组装通过。
- JVM：consumer 707 项（4 skip）、developer 752 项（4 skip）、MCP 52 项（0 skip），均 0 failure/error。OAuth 子集为 app 每 flavor 22 项、MCP 15 项，全部通过；skip 没有计入通过数。
- `run-oauth-boundary-probe.py --output build/oauth-merge-review/probe-fixed`：exit 0；实际编译类验证 `consumeReturnedNull=true`、`unrelatedJsonSurvived=true`，保存源码/类 SHA256，原失败日志未覆盖。
- `accept-hxa-126-oauth.py --output build/oauth-device-acceptance-unique-ports`：exit 0；四组每组 OAuth 5 项 + Connector 5 项全部通过，包含真实 Android Keystore、杀进程后新 PID 恢复及一次性消费。每组均启动并关闭自有模拟器，原始 APK、测试 APK 和 PID/逐方法报告保留在该目录。

| 设备批次 | 通过 | App APK SHA256 |
| --- | --- | --- |
| consumer-api29 | 10/10，0 skip | `853e86f326a1e4174287cc1690346a34675f86958dbeb12bd0dbc6a49a344de8` |
| consumer-api36 | 10/10，0 skip | `853e86f326a1e4174287cc1690346a34675f86958dbeb12bd0dbc6a49a344de8` |
| developer-api29 | 10/10，0 skip | `844c5ab46618410a621e87c963ca2616235f20b3b5309039c95f1b3a1e3deeee` |
| developer-api36 | 10/10，0 skip | `844c5ab46618410a621e87c963ca2616235f20b3b5309039c95f1b3a1e3deeee` |

扩展回归独立运行 `run-acceptance-matrix.py --scope 206 --group extensions --group extensions-restart --output build/oauth-extension-regression`。普通批次 consumer 每 API 为 5 pass/7 skip，developer 每 API 为 10 pass/2 skip；0 failure。7 项 skip 包括 consumer 不允许 loopback 的 5 项及独立阶段的 2 项；后两项在各自 seed/recover 批次实际通过，四组均验证新 PID。严格汇总器保留 `DEVICE_BATCH_INCOMPLETE` / exit 1，不将条件 skip 改成通过；不据此重新声明整套 HXA-206 验收。

首次混合设备批次因上述 skip 标为 incomplete，随后独立分组。首次相邻批次复用端口被“拒绝现有设备”保护拦下；runner 改为每批独立端口，未借用现有模拟器，也未修改产品测试的断言或跳过条件。主机复核遇到一次并行 D8 `Java heap space`，保留 `merge-gate.log`；使用 `GRADLE_OPTS="-Dorg.gradle.workers.max=2 -Dorg.gradle.parallel=false -Dorg.gradle.daemon=false"` 重跑同一完整门禁，日志为 `merge-gate-bounded.log`，未跳过 Release 或测试。最终主机复核仅额外去掉 MCP bridge 中重复的同一条禁用检查；保留原有检查，设备 APK 身份以上表为准。

远端合并/CI 证据在推送后补录；尚不能用本地主机或设备结果代替远端 CI。

## Residual risk

两家真实服务、动态注册、App Link、自有 client metadata document 和发行项未完成。自定义回调必须与当前安装包的 URI 一致；早期试验分支的通用 `helix://` 和未绑定凭据不作为可自动迁移的授权，需重新登录。设备码登录进程死亡不自动重启网络轮询。OEM/Doze/热压仍不计通过。

## Related records

[HXA-126](../development/tasks/HXA-126.md)、[OAuth ADR](../adr/connectors/002-oauth.md)、[合并前复核](../evidence/development/hxa-126-merge-review-2026-09-21.md)。
