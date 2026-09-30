# Antigravity、订阅顺序与 Standard 渠道边界

## 授权和基线

2026-09-30 所有者要求先收尾并提交此前修改，再参考 `dsh-plugin-subscriptions` 支持 Google Antigravity，调整账号顺序，排除标准版的订阅能力。随后明确：使用订阅 Key 但仍通过 endpoint/API Key 调用的方案不要放在订阅页。

此前 Runtime F1～F6、模型管理与 Provider 完整链路已经通过联合主机门禁、源码门禁和差异检查，具名提交为 `15b89830`，100 个文件；不推送。一次性 debug 脚本不纳入。此前证据见[链路收尾](provider-chain-closeout-2026-09-30.md)。本接入使用该提交为基线，不把旧测试数字作为本次结果。

本轮设备、模拟器、真实账号、配额消耗均为 **not requested**。测试使用合成输入、内存服务与受控 HTTP interceptor；不使用浏览器 Cookie、不导入本机用户 token、不访问账户。

## 来源与采用范围

| 来源 | 核实内容 | 本次取舍 |
| --- | --- | --- |
| [V1ki/dsh-plugin-subscriptions](https://github.com/V1ki/dsh-plugin-subscriptions) `d8ab13e91fd6747e419a1f5e965bce42bdfc3ad8`，MIT | 独立 Antigravity OAuth、项目发现、模型目录、Gemini-shaped 请求和签名重放 | 自行实现 Android 适配；仅复用协议事实与公开安装应用身份，保留 MIT notice。不复制其自动 onboarding、账号池、资格回避或不确定请求重试策略 |
| [Google thinking 文档](https://ai.google.dev/gemini-api/docs/thinking) | thought signature 属于服务端返回的 opaque 数据，须随相关上下文保留 | 绑定模型与登录生命周期，原始签名不进入业务工具参数；不使用伪造或跳过验证签名 |
| [Kimi Code / Claude Code 官方说明](https://www.kimi.com/code/docs/en/third-party-tools/claude-code.html) | Kimi Code Console 签发 API Key，Anthropic-compatible API | 新增普通 API 模板 `kimi-code`；Helix 追加 `/messages`，模板根路径为 `/coding/v1`。不登记账号登录 |
| [MiniMax Token Plan 中国站](https://platform.minimax.cn/docs/token-plan/quickstart)、[国际站](https://platform.minimax.io/docs/token-plan/quickstart) | 套餐 Key 与按量 Key 不互通，Anthropic SDK 通过 API Key 调用 | 新增两个地区 API 模板；明确密钥/地区匹配，无自动 PAYG 回退，保留原按量 API 模板 |

原参考仓库只下载到忽略的 `build/reference/` 用于阅读，未安装或执行其代码。公开 OAuth client 参数不是用户凭据，不代表 Google 官方授权 Helix；应用内提示与发行边界继续有效。

## 产品与生产接线

- Standard/consumer：只有本地模型、API / 自建服务两个分类；受管记录不删除，但不显示、不解析执行，缺少受管适配器不能回退成普通 API。Runtime 依赖继续仅在 `developerImplementation`。
- Advanced/developer：订阅页与 Runtime 首页保持 Codex、Claude、Google Antigravity、GitHub Copilot、Grok (X Premium) 顺序。保留现有模型偏好与历史；不自动把新增目录模型加入候选。
- Kimi/MiniMax 的 API Key 套餐留在普通 API 表单，两渠道共用目录、显式勾选、模型默认与逐模型检测。新增模板不内置用户密钥或模型名，未检测能力不冒充可用。
- Antigravity 复用 Provider → CLI Job → 私有 Runtime → 回执/ACK。账号状态、取消与迟到资源、恢复、工具权限沿用原有契约；图片使用带平台身份的私有 IPC payload，不能误路由到 Codex。
- Google 登录使用浏览器 code + S256 PKCE，绑定 state，回调只监听 127.0.0.1，并处理取消、销毁与迟到结果。token 仅进入 Runtime vault。无法取得 project 时指引官方开通，不调用自动 onboarding。
- 认证目录使用 `fetchAvailableModels`；生成使用固定 daily-cloudcode origin 的 `streamGenerateContent`。缺少元数据保持未知，不猜 1M 上下文、图像或工具能力。
- SSE 文本/思考使用现有磁盘事件 spool；不施加新的总回复字节数/时长截断。单帧 2 MiB，工具扇出和后续 ModelMessage 仍遵守既有边界。工具只在 STOP 且签名证据保存后产生可结算事件；EOF、长度截断、未知函数、落盘失败不能假成功。
- 签名重放私有记录绑定精确模型、登录 revision、消息和调用；内容有摘要校验及函数参数比对。保留服务端 function-call ID 供结果相关联，不采用 skip-thought-signature 魔法值。重放证据有 128 条/单条 8 MiB 的留存边界；失效后明确拒绝，不重跑原动作。

### 原生结果与 Helix 历史重组

复核真实 `ChatHistoryBuilder` 后确认：同一次模型回复的可见 TEXT 与 TOOL_CALLS 分别持久化。Google 私有签名记录绑定原调用身份、模型和登录版本，不依赖 UI 消息是否合并；回填重组完整签名 parts，并仅移除与原回复可见文本精确匹配的重复相邻 TEXT，避免第二轮签名错绑或文本重复。实际 HTTP interceptor 双轮测试同时验证说明文字、函数参数、原始函数 ID 与签名保留。工具历史参数或账号身份不匹配仍拒绝。

推理选项的显式请求按参考协议的模型家族映射到 internal `thinkingBudget`，不误发 public Gemini `thinkingLevel`；此映射不用于发现、推荐或认定模型支持。未知方言、无效选项和不足的输出预算拒绝；未设置时沿用上游默认。文本和 JSON 参数的增量切分保留 UTF-16 surrogate pair，防止独立 UTF-8 spool 写入损坏 emoji。

## 验证记录

最终联合命令退出码 **0、BUILD SUCCESSFUL**：1004 个任务中 54 executed、950 up-to-date。常规根 test、detekt、Spotless、consumer/developer lint、App 双渠道与 CLI Runtime/Client 的 AndroidTest Kotlin 编译、双渠道 Debug APK/AndroidTest APK 均通过。增量复用兼容测试结果，不表述为全部最后强制重跑；AndroidTest 仅编译，未运行设备。

```bash
python3 scripts/with-host-slot.py -- ./gradlew test detekt spotlessCheck \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:compileConsumerDebugAndroidTestKotlin :app:compileDeveloperDebugAndroidTestKotlin \
  :runtime:cli-app:compileDebugAndroidTestKotlin :runtime:cli-client:compileDebugAndroidTestKotlin \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest \
  --continue --configure-on-demand --no-configuration-cache --console=plain
```

| 当前精确测试目录 | tests（含 skipped） | failures / errors | skipped |
| --- | ---: | ---: | ---: |
| App consumer debug | 1028 | 0 / 0 | 4 |
| App developer debug | 1081 | 0 / 0 | 4 |
| CLI Runtime | 169 | 0 / 0 | 0 |
| CLI Client | 59 | 0 / 0 | 0 |
| Provider catalog | 17 | 0 / 0 | 0 |

新增独立回归 **31 项**：Antigravity protocol 9、stream 8、thinking 2、transport 5、公共 channel 3、developer 顺序 1、图片/账号 IPC 2、API 套餐模板 1；全部执行通过。共享 channel 测试不因双渠道重复执行而翻倍计数。其中特别覆盖 9.6M 字符连续回复不受重放留存上限截断、EOF/长度截断不释放工具、签名持久化失败、文字/调用分开持久化后的回填、emoji 边界、迟到登录刷新、取消后零请求、401 不重发、图片错平台拒绝。

`./scripts/check-cli-runtime-boundary.sh` 实际检查两份 APK 后退出码 0：组件、非导出/进程、runtime class、资源、launcher 与 consumer 排除全部通过。新增 Google activity 与 MIT notice 只出现在 developer；consumer APK 不含 CLI runtime classes。源码测试不是这项二进制结论的替代。

`./scripts/check-all.sh --source` 退出码 0：657 Markdown、215 HXA、35 当前 ADR、857 生产 Kotlin 源文件、1905 个 base/en/zh-rCN 资源键一致；秘密扫描通过。中途发现的新提供商公共状态枚举遗漏、图片测试目标绑定错误、类型/格式/复杂度问题均已修正并复验；未放宽全局门禁、删除失败用例或新增跳过。

当前统计、重点测试时间戳及四份 APK SHA-256 留在 `build/subscription-antigravity-2026-09-30/host-summary.json`，由现有 `summarize-subscription-closeout.py subscription-antigravity` 生成，不扫描历史归档。最终文档修改后另跑文档检查与 `git diff --check`；提交保留源码/测试/契约和可复用门禁，一次性 formatter debug 脚本不纳入。

## 提交范围

前一收尾提交 `15b89830` 已独立完成；本记录对应其后的 Antigravity/渠道/API 模板增量。提交后的精确 commit 以 Git log 为准，避免在提交内自引用尚不存在的 hash。本轮仅本地提交，不推送或发布 APK。

## 未宣称完成的事项

本轮不声称 Google 授权第三方发行、所有地区/账号可用、X Premium 的具体套餐权益、真实订阅生成质量、Android OAuth 浏览器旅程或所有 OEM 可靠性已验收。Google 端点/协议与安装应用身份可能变化；遇到资格或授权拒绝正常返回错误，不搜索新身份绕过。

已受理的外部请求不能因为本地取消或账号变化而被描述为撤销；SSE 展示也不等于工具执行。实际设备、真实账户、签名发行和商店合规分别按当前所有者授权验证。
