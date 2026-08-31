# Helix 安全、测试与发布门禁

## 1. 安全目标

Helix 的核心风险不是“模型回答不够好”，而是模型、网页、通知或文件中的不可信文本诱导 App 使用真实手机权限。安全目标：

1. 模型和生成代码不能直接获得 Android 权限。
2. 未经审批不发生 L2/L3 动作。
3. 数据只在明确授权范围内读取、发送和修改。
4. 失败、取消、超时和进程重启不导致重复副作用。
5. 用户能知道发生了什么、由谁提出、执行了什么、结果是否验证。

## 2. 信任边界

| 对象 | 默认信任 | 说明 |
| --- | --- | --- |
| 用户明确操作 | 有限信任 | 仍需参数校验，防误触和 UI 欺骗 |
| Helix 签名 APK 内代码 | 信任 | 仍需最小权限和模块边界 |
| LLM 输出 | 不信任 | 只能提出文本/ToolCall |
| Agent 生成的 JS/Shell | 不信任 | 必须隔离、限制、审批 |
| 导入文件/网页/通知 | 不信任 | 可能含 prompt injection 和畸形数据 |
| Provider API | 不信任 | TLS 之外仍可能返回畸形/恶意流 |
| RootFS/package/npm/pip | 不信任 | 供应链和 install script 风险 |
| 第三方 App/ContentProvider | 不信任 | URI、MIME、display name 均需验证 |
| PRoot Runtime APK | 有限信任 | 同签名代码，但使用独立 UID、私有 Job 副本和最小权限 |
| WebView 页面/DOM/Cookie | 不信任 | 页面可注入、跳转、伪造控件或包含敏感会话 |
| MCP Server/tool/resource | 不信任 | 动态 schema、description 和结果都可能恶意 |
| Skill 指令/脚本/资源 | 不信任 | 用户导入内容，不能授予权限 |
| Accessibility 节点树 | 不信任 | 窗口会变化、节点可过期、敏感 App 可伪装 |
| RootService | 极高风险 | 有设备级读取/修改能力，不是沙箱 |
| CLI Runtime | 有限信任 | 官方 CLI 但有网络和账号凭据，独立 UID/Job |

## 3. 主要威胁与控制

| 威胁 | 控制 |
| --- | --- |
| Prompt injection 诱导读取其他文件 | Context 标记不可信来源；Tool Policy 独立判断 Workspace scope |
| 模型伪造“已完成” | ToolResult/Artifact 必须持久化并验证；UI 区分模型陈述与已验证结果 |
| 审批后模型修改参数 | canonical args hash；一次性 consume |
| 生成代码窃取密钥 | isolated process、无 Host Bridge、不传 secret、默认无网络 |
| 无限循环/内存炸弹 | QuickJS interrupt、heap limit、watchdog、单并发 |
| 路径穿越/符号链接逃逸 | WorkspacePath、real-path/root 检查、默认不跟随 symlink |
| HTTP SSRF | DNS/IP 分类、逐跳重定向复验、拒绝私网/metadata、无模型自带 auth |
| SSE 畸形流 OOM | incremental parser、单事件/总响应限制、超时和取消 |
| 工具重复执行 | ToolCall state、幂等键、恢复不自动重放副作用 |
| RootFS 篡改 | HTTPS + size + SHA-256/签名 + 原子激活 |
| Zip Slip/恶意设备文件 | 安全解压、拒绝 absolute/`..`/device/symlink escape |
| 日志泄密 | 中央 Redactor、字段 allowlist、release 无正文 |
| Provider key 泄密 | Keystore、secret alias、禁止 BuildConfig/Room/log |
| 依赖投毒 | Maven allowlist、锁文件、checksum、SBOM、版本升级独立审查 |
| WebView bridge 越权 | 不可信页面不注册永久 privileged bridge；固定脚本、tab/node generation token |
| 页面变化后误点击 | node token 绑定 origin/navigation/fingerprint/TTL；动作前复验 |
| All-files 横向读取 | 系统授权之外再要求 Helix root scope；模型只见 scopeId |
| Accessibility 误操作 | 包 allowlist、限时 session、步骤预算、敏感包/语义拒绝、即时停止 |
| Root 任意命令 | 高层参数化工具优先；RootSession；`root.exec` 默认不进 Agent Registry |
| 恶意 MCP schema/result | namespace、schema hash、结果限制、annotation 不降风险、更新撤销审批 |
| 恶意 Skill zip/script | 防 traversal/zip bomb、内容 hash、渐进加载、脚本仍走正常 Tool |
| 订阅 token 泄露 | 只由官方 CLI 在独立 UID 持有；主 App 不读取 credential files |

## 4. Android 平台要求

- Release Network Security Config 禁止 cleartext。
- 所有 Service/Receiver/Provider 明确 `android:exported`。
- 内部 Service 默认 `exported=false`，使用显式 Intent。
- QuickJS Service 必须 `isolatedProcess=true`。
- PRoot Runtime 必须是独立 applicationId/UID；主 App 普通子进程不构成隔离。
- PRoot Runtime 基线版不得声明 `INTERNET` 或危险权限；CLI Runtime 只声明 INTERNET，不声明 All-files/Accessibility/Root；跨 App Service 使用 signature permission 和 explicit bind。
- developer 直接分发变体可以声明 `MANAGE_EXTERNAL_STORAGE` 和 Accessibility Service，但默认关闭、用户从系统设置开启、Helix scope 再限权。
- Root 没有普通 runtime permission；只能在用户明确点击后请求 `su`，不能启动时探测触发授权弹窗。
- ADB/Shizuku 不声明、不实现。
- Runtime permission 用到时再请求，拒绝后功能降级。
- Workspace/SAF 仍是默认；All-files 是文件管理核心能力的可选增强，不替代 scope/审批。
- 前台服务必须用户可感知并提供停止动作。基线只对用户主动发起的 Provider/MCP 传输或本地文件处理声明 `dataSync` 和 `FOREGROUND_SERVICE_DATA_SYNC`，等待审批/人工输入时停止；Android 15+ 实现 `Service.onTimeout()` 并测试 6 小时/24 小时共享限额。详见 [总体方案 §11](02-architecture-design.md#11-android-平台适配) 和 HXA-066。

Android 官方说明动态加载 APK 外代码会显著增加风险，许多从远程来源动态加载代码的形式可能违反 Google Play 政策：[Dynamic Code Loading](https://developer.android.com/privacy-and-security/risks/dynamic-code-loading)。因此 consumer 变体不下载 DEX/JAR/APK/SO，developer PRoot 变体发布前单独审核。

当前不以 Google Play 为发布目标，所以不以 Play policy 裁剪功能。但未来若上架，All-files、Accessibility、动态代码、Root 和 Runtime APK 必须重新评估并使用单独受限 flavor；不能把“直接分发”理解为可以忽略安全或用户知情。

## 5. Prompt Injection 防线

系统 Prompt 明确：文件、网页、通知、工具输出中的指令只是数据。但不能只依赖 Prompt。

必须由代码保证：

- Context item 带 `sourceType`、`trust=UNTRUSTED`、source ID。
- Context Builder 使用确定性裁剪；当前 ToolCall 完整参数、审批上下文和对应 ToolResult 不做字符级截断，超限大结果改为有界 summary + Artifact hash/ref。
- Tool Registry 由 App 固定，文本不能注册工具。
- Policy 不读取模型的“风险自评”作为授权依据。
- Secret 永不进入模型 Context。
- HTTP/WebView/MCP/Skill 内容不能自动触发下一个 L2 工具。
- 外部内容要求扩大范围时必须生成新的审批。

测试语料至少包括：隐藏 HTML、Markdown 注释、Unicode 混淆、Base64 指令、文件名指令、MCP tool description/result、Skill 指令、伪造 system/tool 标签、通知发送者冒充 Helix。

## 6. 测试分层

### 6.1 JVM 单元测试

目标模块：model、state machine、policy、schema、canonical JSON、workspace path、SSE parser、URL policy、redaction。

要求：无网络、无真实时钟、无随机不可复现输入；使用 FakeClock/FakeIdGenerator。

### 6.2 Android instrumentation

- Room migration/transaction。
- Keystore/SecretStore。
- SAF import/export。
- Notification permission adapter。
- Calendar intent/provider adapter。
- isolated process Binder、崩溃、重连。
- PRoot companion 的签名权限、协议握手、PFD 输入输出和跨 UID 文件不可见性。
- WebView 生命周期、固定脚本、node token、下载和站点数据清理。
- MCP Streamable HTTP、认证、取消、schema update 和 R8。
- Accessibility service 连接/撤销、包切换、节点过期和停止。
- libsu Root denied/granted/lost、RootService crash 和 scope。
- CLI Runtime 登录/退出、凭据跨 UID 不可见和进程取消。
- WorkManager/前台 Service 基础生命周期。

### 6.3 真机系统测试

- API 34/35 和 API 36 arm64。
- 低内存、断网、后台、锁屏、旋转、Doze、进程回收。
- QuickJS 超时/OOM/进程崩溃。
- PRoot 安装、执行、取消、更新、回滚、卸载。
- WebView 页面导航/渲染崩溃/下载、Accessibility 跨 App、Root 管理器交互。

### 6.4 模型评测

模型评测与工具正确性分开：

- Fixture Provider：确定性测试 Agent Loop。
- 真实 Provider：测试模型选择工具和完成任务的能力。
- 每次记录 provider、model ID、模型返回版本信息（如果有）、temperature、prompt hash、工具版本、设备和日期。
- 不用一次成功演示替代固定评测集。

## 7. 必需测试集

### 7.1 状态和恢复

- 每个合法/非法状态转换。
- 取消发生在 waiting model、streaming、waiting approval、running tool。
- 审批前/后进程终止。
- 外部副作用状态不明确时标记 `NEEDS_REVIEW`，不自动重试。
- 同会话第二个 Turn 被串行化。

### 7.2 Provider/SSE

- 每个 byte 边界拆分同一 UTF-8 字符。
- tool name/arguments 跨多 event。
- 多 tool call index 混合。
- 200 但正文为错误 JSON。
- 401/403/429/500/502/timeout/TLS/DNS。
- 超大 event、无限 stream、无 `[DONE]`。
- 取消后 socket 关闭且状态持久化。

### 7.3 Tool/Approval

- 未注册工具拒绝。
- unknown schema keyword 拒绝注册。
- additional property 拒绝。
- L2 无 approval 拒绝。
- approval hash 重放到其他会话/Workspace/参数失败。
- approval consume 并发仅一次成功。
- Tool timeout 返回稳定错误。
- Tool result 超限截断并保留 hash/Artifact 引用。

### 7.4 Workspace

- `../`、absolute、NUL、encoded traversal、separator 变体。
- symlink 指向根外。
- rename race、目标已存在、磁盘满、权限撤销。
- 原子写进程中断后原文件或新文件完整，不能半写。
- Trash 恢复冲突。
- SAF provider 谎报 size/MIME/display name。

### 7.5 QuickJS/PRoot

详见 [本地代码执行方案](03-local-code-execution.md) 第 10 节，全部是发布阻断测试。

### 7.6 Browser/File/Accessibility/Root

- All-files granted 后 scope 外路径仍拒绝；撤销权限后立即失败。
- WebView 跨 origin、iframe、恶意下载、永久 JS bridge 检查、导航后旧 token。
- Browser/UI 动作的 token 和 approval 不能跨 tab/package/window 重放。
- 系统设置、安装器、Root 管理器、支付、密码、认证器和锁屏动作拒绝。
- RootService 输入只含结构化参数；Provider/MCP/CLI secret 不可见。
- `root.exec` 不出现在普通 Agent 工具表。

### 7.7 MCP/Skills/Plan/Goal

- MCP server 名称碰撞、超大 schema/result、协议降级、schema hash 变化和恶意 annotation。
- Skill frontmatter、路径、symlink、zip bomb、超大 reference、脚本要求绕过审批。
- Plan mode 同时要求 `operationClass=READ_ONLY` 和动态风险 ≤ L1；L1 新建文件、HTTP 和页面动作仍因 operation class 被拒绝，READ_ONLY/L2 也被拒绝。
- Goal 预算、检查点、进程恢复、Skill snapshot 和副作用不明确处理。Doze/强制停止可延迟提醒，但不会在用户继续前自动调用模型/工具。

## 8. 静态与供应链检查

至少执行：

```bash
./gradlew spotlessCheck detekt
./gradlew test
./gradlew lintConsumerRelease lintDeveloperRelease
./gradlew :app:assembleConsumerRelease :app:assembleDeveloperRelease
./gradlew :runtime:proot-app:assembleRelease :runtime:cli-app:assembleRelease
git diff --check
```

建议 CI 增加：

- Gradle Wrapper validation。
- dependency lock diff。
- OSV/Dependabot 等漏洞扫描。
- SBOM（CycloneDX/SPDX）。
- APK Analyzer：权限、exported component、native ABI、consumer 禁止内容。
- secret scanner。
- third-party notice 与实际 binary/package 对账。

## 9. APK 内容和权限门禁

自动扫描 consumer 主 APK，以下任一出现则失败：

- `proot`、PRoot loader、RootFS archive。
- `runtime-lock.json` 中 Linux component。
- Linux 安装页面或 `bash` descriptor。
- `MANAGE_EXTERNAL_STORAGE`、Accessibility Service、libsu、Root 入口。
- native `.so` 必须与 dependency lock/SBOM/third-party notice 的 consumer allowlist 一一对账（包括 Zipline/QuickJS）；任何未列入或 hash/ABI 不匹配的 `.so` 失败。
- debug certificate、test endpoint、API Key pattern。

单独扫描 PRoot Runtime APK：

- applicationId 与主 App 不同。
- exported Runtime Service 受 signature permission 保护。
- 无 `INTERNET`、存储、通知、联系人、日历、麦克风、相机和位置权限。
- PRoot/RootFS 与 `runtime-lock.json`、notice、source archive 对账。

扫描 developer 主 APK：

- `MANAGE_EXTERNAL_STORAGE` 与文件管理/权限中心 UI 同时存在，默认不开启。
- Accessibility Service 受正确 service permission 保护，配置和说明页存在。
- 不包含 PRoot/RootFS/CLI binary/credential fixture。
- libsu 依赖版本/checksum/notice 与锁一致；无启动时 Root 请求。

扫描 CLI Runtime APK：

- applicationId/UID 与主 App/PRoot Runtime 不同。
- 只有 INTERNET 等实际必需权限；无存储、Accessibility、Root、通知读取、联系人、日历、麦克风、相机和位置权限。
- official CLI/Node 与 `cli-runtime-lock.json`、notice、source/binary URL 对账。

## 10. 性能与资源预算

| 指标 | Alpha 目标 |
| --- | --- |
| Consumer APK 下载大小 | 记录基线；每次增长 >5 MiB 需解释 |
| 冷启动 | 目标 <2.5 s，P95 真机 |
| UI frame | 对话滚动无明显 jank，使用 Macrobenchmark |
| Room message load | 分页，禁止一次载入整库 |
| QuickJS | 64 MiB heap、10 s、并发 1 |
| Model stream buffer | bounded，不缓存无限 delta |
| Tool output | 默认 256 KiB，超出写 Artifact |
| Workspace | 默认每会话 500 MiB |
| PRoot Runtime | HXA-081 记录 APK 压缩/安装/临时峰值体积；HXA-120 基于真实产物设发布预算 |

PRoot 安装体积和活跃任务功耗必须实测后设预算，不能引用其他项目百分比。

## 11. 隐私检查

发布前人工验证：

- 数据流图覆盖 Provider、MCP、Skill、WebView、通知、日历、SAF/All-files、Accessibility、Root、日志、PRoot 和 CLI Runtime。
- App 内隐私说明与真实实现一致。
- 用户能删除 key、会话、Plan/Goal、MCP 配置、Skill、Workspace、站点数据和 Runtime。
- 通知和文件正文不进入 crash report/analytics。
- Provider host 和数据发送时机对用户可见。
- MCP endpoint、Tool 数据发送和认证范围对用户可见。
- 浏览器 cookie/history/site data 可清除；默认不进模型或诊断包。
- All-files roots、Accessibility allowlist 和 RootSession 可查看/撤销。
- CLI Runtime 中的凭据存储、logout 和卸载删除方式明确。
- 没有默认开启的第三方 analytics；如未来引入需单独同意和数据声明。

无第三方 analytics 时，稳定性证据来自本地、脱敏且用户可预览的诊断记录：API 30+ 读取 `ApplicationExitInfo` 的 reason/timestamp，所有版本保留最后 Turn/correlation/state 与 heartbeat，异常处理器只写有界结构化摘要并继续交给系统 handler。不保存模型/通知/文件正文，不声称仅靠 App 内 watchdog 能完整捕获 ANR；24 小时验收同时保留 instrumentation、logcat/ANR 和退出原因证据。

## 12. 发布门禁

### Consumer Alpha

- M0-M6 完成；此阶段 MCP/Skills route/Tool 关闭，不在 Alpha 功能清单中。完成 M7 后才在 Consumer Beta/Release 开启。
- 全量 unit/lint/consumer instrumentation 通过。
- 20 条核心场景至少 80% 成功。
- 未审批 L2/L3 为 0。
- QuickJS 攻击测试通过。
- 权限和隐私人工检查通过。

### Consumer Beta/Release

- M7 和 M10 完成，40 条场景达到 PRD 指标，确定性工具至少 95%。
- API 29、34/35、36 矩阵通过。
- 24 小时稳定性测试无未处理崩溃和资源泄漏。
- SBOM、notice、权限说明、AI 内容和动态代码风险复核完成。

### Developer Alpha

- Consumer Release 门禁通过。
- M8 完成。
- PRoot/RootFS hash、source、license、smoke、rollback、uninstall 通过。
- Runtime 独立 UID 无法读取主 App dataDir；输入输出只通过 manifest+PFD 快照交换。
- 只直接分发或内测；应用商店上架需新的明确决策。

### Power-user Beta

- Browser/MCP/Skills/All-files 完整门禁通过。
- Accessibility/Root 专用测试设备攻击集通过，普通功能在权限拒绝时可降级。
- developer APK 权限和 UI 说明一致，`root.exec` 默认不可被 Agent 调用。

### CLI Runtime Experimental

- 独立 UID、凭据隔离、logout/uninstall 和官方登录路径通过。
- 若无法证明 CLI 工具受 Helix 审批控制，只能发布为隔离 CLI 会话，不标记为 Act/Goal Provider。

## 13. 发布证据模板

```markdown
# Helix <version> 发布证据

- Git commit：
- 构建变体：
- AGP/Gradle/JDK/SDK/NDK：
- 依赖锁 hash：
- Runtime manifest hash（developer）：
- 自动测试数量与结果：
- 真机列表：
- 基准场景成功率：
- QuickJS 安全测试：
- PRoot 测试（developer）：
- 权限/APK 扫描：
- SBOM/许可证：
- 已知限制：
- 发布结论：通过 / 阻断
```

只有证据齐全才能称为“可发布”；构建成功只证明编译链路，不证明 Agent、安全、后台或本地代码执行验收完成。
