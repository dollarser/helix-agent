# 输入区验收与 Antigravity OAuth 配置收口

## 范围与授权

所有者要求完善输入布局、上下文占用与遗留问题，并明确授权独占 API36 定向验证，以及移除内置 OAuth 参数、改为显式构建配置和清理未推送历史。本轮不使用真实订阅账号，不安装到手机，不把旧 P5 或主机通过扩大为全产品验收。

## 实现

- 输入布局与上下文显示见[修复记录](../../bug-fixes/2026-09-30-composer-layout.md)。追加修复模型切换延迟/失败后的回退入口：点击实际当前模型恢复推理选择，不重新提交该模型或重置推理值。
- Antigravity 不再内置上游应用 OAuth 参数。构建者通过环境变量 `HELIX_ANTIGRAVITY_CLIENT_ID` 与 `HELIX_ANTIGRAVITY_CLIENT_SECRET` 同时配置获准使用的 installed-app 身份；默认空配置，缺失/不完整配置不能启动登录或刷新。
- 未配置安装包提供明确说明，登录按钮禁用，退出已有账号仍可用；其他 Provider 正常可用。网络层增加缺配置时零请求断言，合成 HTTP 测试显式注入夹具参数。
- 参数会进入 APK，不能视作可保密的 confidential-client 凭据。构建缓存/制品也应按其配置管理；源码、日志、文档和提交中不得包含真实值。
- 本地秘密扫描增加 Google OAuth 参数格式规则，与远端推送保护保持独立，不关闭或绕过 GitHub 防护。

## 验证记录

最终主机：`build/composer-oauth-host-r2.log` 双渠道 unit/lint、APK/AndroidTest APK、Runtime unit 与 detekt 通过（875 tasks）。追加 Runtime 零网络反例后 `build/oauth-final-host.log` 再通过，Runtime 171 tests、0 failure/error/skip；最终 Developer 夹具编译、detekt/Spotless 见 `build/oauth-device-build-r2.log`。

API36 / arm64，4096 MiB、4 cores：Consumer `build/composer-api36-r2` **24/24**；Developer `build/composer-api36-developer-r2` **19/19**，均无 skipped，具名源码改动未由此扩大到真实账号。Developer 捕获三张界面截图，已查看会话输入布局；两个 runner 均有 `closed.json`。制品 SHA-256 留在各目录 `artifacts.json`，新 fixture 的 test APK 与上一轮不同。

本地 SGLang `127.0.0.1:30008/v1/models` 连通检查在 5 秒内超时，未开始真实服务上下文复验；当前上下文验证使用合成服务/持久化与 UI 夹具，不能声称本轮真实 SGLang 已通过。

Consumer r2 已 24/24；Developer r1 为 18 PASS / 1 fixture failure，OAuth 登录页属于 `:subscriptions` 私有进程，`ActivityScenario` 不支持跨进程启动；改为系统无障碍节点验证真实界面，不改变生产进程。中间失败保留：API36 r1 指定了默认运行器，而本工程实际使用 `com.helix.app.HelixAndroidJUnitRunner`，测试未启动；修正命令后运行 r2。主机中发现模型菜单复杂度与 Gradle 格式问题，已通过提取小型展示函数和修正表达式解决，不放宽门禁。

## 历史清理

清理前保存本地受限目录中的 Git bundle 和工作区差异；只重写未推送的 main 后代，远端已有提交与其他分支/工作树保持原样。计划在独立临时仓库中替换旧提交的两个字面量，核对最终 tree 完全一致后再原子更新 main。旧证据内的提交号保留历史含义，不改写为新提交已通过相同验证。

## 保留边界

真实订阅登录/刷新、真实模型恢复完成率、完整 Runtime/OEM/低内存矩阵及正式签名升级仍需外部条件或另行授权；本轮不将其标记为完成。原始 P5 15/15 只对应原始源码/制品。
