# HXA-237：订阅引导、Codex 版本与终端交互

日期：2026-10-01。所有者要求先提交已收尾代码，再完成本页体验优化。上一阶段已提交 `aca92e11`（回放/OAuth/R2-A/R3/J1 基础 join 与阶段文档），没有推送；独立 FFmpeg/设备调查材料没有混入。当前追加修改以该提交为基线，设备/真实账号未请求，不宣称已发行。

## 实现范围

| 入口 | 当前行为 |
| --- | --- |
| 模型管理的订阅行 | 保留真实账号、连接与具体模型的准入事实；高亮连接测试并提前到账号按钮之后，明确账号→测试→选模型的下一步 |
| 会话/会话设置选模 | 未就绪来源及空选择仍显示配置说明与模型管理入口；具体未测试模型保持禁用，导航先保存草稿，不自动发起测试或生成 |
| Codex 账号页面 | 客户端兼容版本可填写/保存/恢复默认，默认 0.159.3；严格类型/长度/语法校验，保存失败可见，不把更改当连接或能力证明 |
| Codex 目录/连接测试 | 读取同一 Runtime 私有设置，在一次请求/刷新序列固定版本；只设置固定 HTTPS 目录的 client_version 参数，不更改凭据/endpoint、不下载安装官方 CLI |
| 终端帮助 | 静态 Runtime/共享 UID/租期说明和组件许可证进入“帮助”；实时错误、只读、状态与起始目录保留，帮助默认关闭 |
| 字体与输入 | developer 内置未修改 Inconsolata Regular，13sp 起始、支持缩放和系统缺字回退；单击可写正文请求 IME，顶部固定键盘显示/收起，读者不获写权限 |
| 终端补充键 | 默认 Esc/Tab/Ctrl-C/Ctrl-D/方向键；更多键含 Home/End/PgUp/PgDn/Del、Ctrl-A/E/L/R/U/W/Z 与常用 shell 符号；方向键走模拟器/libvterm 模式，文本进入原有界输入队列 |
| 设置→关于 Helix | 从已安装包读版本，显示项目及 dollarser 开发者主页；仅用户点击打开外部网页，无浏览器/被拒绝时反馈错误 |

## 来源与边界

当日核对 [OpenAI Codex 官方稳定版](https://github.com/openai/codex/releases/tag/rust-v0.159.3)，不默认跟随 alpha。该版本是适配器目录兼容参数，不是 Helix 版本或已下载 CLI 的版本。此次选择用户手动设置，而非增加静默检查/更新进程。

Inconsolata 来源 [googlefonts/Inconsolata](https://github.com/googlefonts/Inconsolata)，固定提交 `fc1fc21081558b39a2db43bfd9b65bf9acb50701`；原字体 SHA-256 `ab56ea18c5c24d2b909261f0c63a14f9576dfabaf2e9ebd353062aa4149cefc7`。OFL-1.1 与未修改说明位于 developer 的 terminal-licenses 资产并从帮助可读。没有新增 terminal-renderer 依赖版本或修改其生成源码。

Consumer 不包含订阅 Runtime 或手动终端字体/实现；关于入口和普通模型配置提示两渠道可用。键盘/帮助操作不启动/停止 shell、不改变租期、不自动输入命令；关闭软键盘不是取消原任务。

## 回归与修复过程

新增独立 JVM 用例 19 项：客户端版本设置 6、配置版本贯穿连接重试 1、订阅步骤 8、快捷键 4。保留原连接/刷新/权限回归。新增 UI/字体用例 8 项，扩展原真实 PRoot 旅程为单击正文显示 IME、收起及按需帮助；这些设备用例只编译、不运行。

第一轮发现项目未生成 App BuildConfig，关于页改为读取已安装包身份，与现有诊断相同。修复新 Composer 回调参数影响旧尾随可组合 lambda 的问题，保留既有测试断言；触屏测试使用当前测试 API 的显式 click 扩展。早期构建/格式失败日志保留于 `build/hxa237/targeted-r1.log`、`targeted-r2.log`。

定向主机复验已退出 0（369 tasks，16 executed / 353 up-to-date）：CLI Runtime 与双渠道 App 单测、双渠道 AndroidTest Kotlin 编译、格式化。随后布局小修和完整主机/制品门禁的最终结果以本页下节为准，不把中途绿色当最终验收。

整合复核补齐“连接已通过但全部所选模型有失败记录”的提示边界，不能显示就绪。拆分终端主体及选模查询判断以保持原复杂度门槛；字体使用配置感知的 LocalResources，避免配置变更后的陈旧读取。中间编译/静态与资源 lint 失败分别保留为 `host-r1.log`、`host-r2.log`，没有关闭或抑制对应规则。

## 最终验证

完整主机已退出 0：最后一次 989 Gradle tasks，25 executed / 964 up-to-date。包含全仓 test、detekt、spotlessCheck、双渠道 lint/Debug APK/AndroidTest APK。日志及退出码为 `build/hxa237/host.log` / `host.exit`，属于增量整合，不声称所有测试最后一次强制重跑。此前 49 executed / 940 up-to-date 的成功记录保存在 `host-before-license-format.log`。

`check-all.sh --source`、`--artifacts`、`git diff --check` 均通过。源码检查记录 687 Markdown、220 HXA、35 ADR 和秘密扫描；既有 i18n 检查报告 1941 strings.xml 键。此次额外执行 `check-hxa237-artifacts.py` 核验 31 个新增 XML 多语言键/格式占位符，以及 developer 字体/随包许可证的 APK 内 SHA；consumer APK 不含该字体或许可证。定向报告均无失败/错误/跳过，共享渠道测试不重复计作独立场景。

最终暂存检查发现上游 OFL.txt 的一处行尾空白。只规范该空白、保留全部许可证文字与原始上游摘要，并在 NOTICE 中说明；字体文件没有修改。随包许可证 SHA-256 为 `11527366bb615a5246481b961bcc1f4e51cf5d44294a0bea01f59b61f400e299`。此后重新执行完整主机及制品验证，不用规范前的 APK 指纹冒充最终制品。

代码来源由既有 provenance 入口冻结于 `source-before-host.json` / `source-after-host.json`，主机验证前后逐字相同；后续只同步文档。最终制品摘要保存在 `build/hxa237/artifact-summary.json`：Consumer Debug SHA-256 `6a10dd0155a4ea45ed28b806e9c4e13304892e8c5644a315911911e27bfc44be`，Developer Debug SHA-256 `2d38a3eecdf78f6793c18a80fb16335eaba32ce5b4e046ce61c6ed078d4de638`。

19 项新增 JVM 用例已通过，8 项新增 UI/字体用例及扩展的原 PRoot/IME 旅程仅编译；设备与真实服务 not requested。代码作为 HXA-237 独立提交保存，不推送/发布；原 J1 上下文/可信类型进展判定、J2、Project Memory 及设备/真实服务/发行仍按原任务开放。
