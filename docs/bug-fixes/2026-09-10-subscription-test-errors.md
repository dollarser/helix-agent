# Bug Fix: 订阅测试误报与错误提示

Status: fixed
Date: 2026-09-10
Related HXA: HXA-190

## Problem

Subscriptions 极小测试失败只显示 `job-failed / HTTP none`，无法区分登录、网络和协议原因；网络异常又可能被描述为 Token exchange 失败。主应用把本地订阅组件绑定失败和远端网络失败都归入 TRANSPORT，原提示只提网络/TLS。

## Impact

用户可能误删有效登录，或把成功的极小回复判为失败。连接测试通过后还错误显示“能力已探测”。

## Root cause

- Smoke runner 只持久化失败终态，丢失当前调用的异常；UI 无法解释具体原因。
- Smoke stream 读取整个 HTTP body 直到 EOF，再检查完成事件；完成之后的连接关闭异常仍能使测试失败。
- 共用 TRANSPORT 类别覆盖网络与本地 Binder 连接，但提示没有说明这一边界。

## Fix and invariants

- 当前 Job 的异常只在 runner 内存中保留，Probe 在失败时交给已有安全错误映射；不把异常正文或凭据写入 journal，也不跨 UID 传递。
- 极小测试按行读取，在有效完成事件及精确预期文本均满足时立即成功，不等待连接关闭。未完成、失败终态、错误输出和原诊断上限仍拒绝。
- 网络错误显示 models/response 阶段及封闭类别；HTTP 401、403、429、5xx 给出不同处理建议。403 不直接断言登录过期。
- 主应用订阅 TRANSPORT 提示同时说明网络及本地组件连接的可能性；没有改变 IPC、认证隔离、DNS、自动重试或生产回复长度/超时。
- CONNECTION_ONLY 显示“连接已通过 · 能力未检测”，去掉极小测试成功提示中过时的 Provider 尚未注册说明。

## Alternatives considered

不删除登录或刷新所有请求；没有 401 证据时不能假定凭据过期。不自动重放可能已完成或含工具的生成。没有为改善文案引入新的跨 UID 错误协议。

## Regression verification

- `test-subscription-error-recovery.py` 执行 scoped Spotless、`:runtime:cli-app:testDebugUnitTest --tests '*CodexSmoke*' --tests '*CodexModelJobTest'`、两包构建及 app 测试 APK 构建：通过。32 用例，0 失败、0 跳过。
- 新增完成后连接异常不影响成功、网络失败传递且不持久化异常正文的回归；既有取消、刷新、失效凭据处理、协议和上限测试继续通过。
- `bash scripts/check-i18n.sh` 通过；`git diff --check` 通过。
- 独占 PLC110 真机：初始极小测试失败，恢复异常传递后确认 IOException；后续同一登录与 DNS 配置下极小测试两次成功，其中一次使用最终流读取修复。
- 主 App 的真实设置页面点击连接测试成功。仅验证连接，未运行能力检测或对话任务。
- 自动 instrumentation 连接检查遇到本地 BIND_REFUSED 并失败，未算通过；实际 App 前台界面随后通过。测试 APK 已删除，两个产品 APK 保留数据覆盖更新。

## Residual risk

最初暂时性 I/O 失败的底层网络原因没有足够证据确定，不能声称已定位为 DNS 或 EOF；EOF 修复是独立可复现的实现缺陷。真机测试成功证明当前凭据有效与链路可用，不代表网络长稳通过。本地绑定被拒的自动测试条件与 OEM 差异仍需独立调查。进程重启后仍只有持久化失败终态，当前异常详情不跨进程恢复。

## Related records

- [当前状态](../development/status.md)
- [DNS 设置](../development/subscription-dns-overrides.md)
- 脚本位于 `scripts/debug/2026-09-10/`；机器日志位于忽略的 `build/debug/2026-09-10/subscription-error-recovery/`。
