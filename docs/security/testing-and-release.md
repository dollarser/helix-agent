# Helix 安全、测试与发布门禁

## 1. 安全目标

Helix 的核心风险不是“模型回答不够好”，而是模型、网页、通知或文件中的不可信文本诱导 App 使用真实手机权限。安全目标：

1. 模型和生成代码不能直接获得 Android 权限。
2. 未经审批不发生 L2/L3 动作。
3. 数据只在明确授权范围内读取、发送和修改。
4. 失败、取消、超时和进程重启不导致重复副作用。
5. 用户能知道发生了什么、由谁提出、执行了什么、结果是否验证。

### 1.1 两级安全边界与不可变内核

Helix 使用 [ADR-0012](../adr/0012-capability-first-advanced-grants.md)定义的 `STANDARD`/`ADVANCED`。`STANDARD` 面向普通用户并作为所有变体默认值；`ADVANCED` 只在 developer 变体中显式出现，并按能力分别启用。Advanced 可增加 Trusted Workspace、动态风险不高于 L1 的有界长期规则与精确批量批准，但不能关闭 schema/Policy/Approval、Secret 隔离、进程/UID 隔离、敏感目标拒绝、审计、取消、超时和变更后验证。

安全配置、Android 系统授权和 Helix scope 是三个独立门：进入 Advanced 不自动获得 All-files/Accessibility/Root，不安装 PRoot，不连接 LAN，也不把相关工具放入 Registry。按 [ADR-0013](../adr/0013-standard-store-capability-preserving-distribution.md)，Standard 是完整商店产品；渠道 artifact 只能因当前明确政策或真实审核做最小能力差异，不能把笼统“更安全”当作删除已允许核心能力的发布依据。

## 2. 信任边界

| 对象 | 默认信任 | 说明 |
| --- | --- | --- |
| 用户明确操作 | 有限信任 | 仍需参数校验，防误触和 UI 欺骗 |
| Helix 签名 APK 内代码 | 信任 | 仍需最小权限和模块边界 |
| LLM 输出 | 不信任 | 只能提出文本/ToolCall |
| child Agent 输出（若 HXA-105 后启用） | 不信任 | 只读 proposal/result；不授权、不直接作为 verifier evidence |
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
| 路径穿越/符号链接逃逸 | FileScopePath/PathSyntax、real-path/root 检查、默认不跟随 symlink |
| HTTP SSRF/DNS rebinding | 规范化 URL；校验全部 A/AAAA；仅连接本次已验证地址集合并复验 peer；逐跳重定向复验；拒绝越 scope/metadata；无模型自带 auth |
| 高敏数据静默出网 | 按实际 endpoint 分类 residence；发送前计算数据类别；Standard 逐次确认，Advanced 仅精确/限时/可撤销规则；凭据始终拒绝 |
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
- PRoot/CLI 只需安装，不要求用户先打开或保持进程；只有用户点击的零 Job 验证/修复/登录，或已批准 Job，才能由 `RuntimeSupervisor` 使用 explicit ComponentName + `BIND_AUTO_CREATE` 冷绑定。应用启动、切换 Advanced 和被动 Registry 刷新不得启动 Runtime。空闲进程允许回收，Binder death 后只按 jobId 查询/对账，未知结果进入 `INTERRUPTED`，不得自动重放或回退到主 App 执行。
- stopped/禁用状态只能显示为不可用；最小设置/修复 Activity 仅由用户点击打开，不能在启动、切换 Advanced 或后台恢复时自动拉起。
- developer 直接分发变体可以声明 `MANAGE_EXTERNAL_STORAGE` 和 Accessibility Service，但默认关闭、用户从系统设置开启、Helix scope 再限权。
- Root 没有普通 runtime permission；只能在用户明确点击后请求 `su`，不能启动时探测触发授权弹窗。
- ADB/Shizuku 不声明、不实现。
- Runtime permission 用到时再请求，拒绝后功能降级。
- Workspace/SAF 仍是默认；All-files 是文件管理核心能力的可选增强，不替代 scope/审批。
- developer 首次启动仍为 `STANDARD`；进入 `ADVANCED` 和启用 All-files、Accessibility、PRoot、Root、LAN origin 是互相独立的用户动作。
- 前台服务必须用户可感知并提供停止动作。基线只对用户主动发起的 Provider/MCP 传输或本地文件处理声明 `dataSync` 和 `FOREGROUND_SERVICE_DATA_SYNC`，等待审批/人工输入时停止；Android 15+ 实现 `Service.onTimeout()` 并测试 6 小时/24 小时共享限额。详见 [总体方案 §11](../architecture/overview.md#11-android-平台适配) 和 HXA-066。
- Runtime 的任意 Shell/CLI 计算不能为保活冒充 `dataSync`。找不到合法前台服务类型时保持前台有界并在退后台暂停/取消；如确需 wake lock，只能在用户可见 Runtime FGS 的 RUNNING 窗口限时持有，且所有 terminal/cancel/timeout 路径释放。详见 [ADR-0007](../adr/0007-companion-runtime-lifecycle.md)。

Android 官方说明动态加载 APK 外代码会显著增加风险，许多从远程来源动态加载代码的形式可能违反 Google Play 政策：[Dynamic Code Loading](https://developer.android.com/privacy-and-security/risks/dynamic-code-loading)。因此 consumer 变体不下载 DEX/JAR/APK/SO，developer PRoot 变体发布前单独审核。

Standard 明确以 Google Play、国内 Android 应用商店和官网为发布目标。发布门禁按 [ADR-0013](../adr/0013-standard-store-capability-preserving-distribution.md)执行：优先通过核心用途声明、披露、同意和审核保留能力；只有当前政策原文或真实审核反馈才能形成渠道差异，不能为了笼统“更安全”先把 Standard 裁成低能力版本。

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
- PRoot/CLI 未运行且未打开时的冷绑定、空闲回收再启动、Binder death 重连查询、同 jobId 防重复执行。
- WebView 生命周期、固定脚本、node token、下载和站点数据清理。
- MCP Streamable HTTP、认证、取消、schema update 和 R8。
- Accessibility service 连接/撤销、包切换、节点过期和停止。
- libsu Root denied/granted/lost、RootService crash 和 scope。
- CLI Runtime 登录/退出、凭据跨 UID 不可见和进程取消。
- WorkManager/前台 Service 基础生命周期。

### 6.3 真机系统测试

- API 34/35 和 API 36 arm64。
- 低内存、断网、后台、锁屏、旋转、Doze、进程回收。
- Runtime 强制停止/禁用、前台服务启动限制与类型、通知停止、`onTimeout()`、可选 wake lock 泄漏。
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
- Provider residence 由规范化实际 endpoint 得出；同一 Ollama/SGLang 模板分别指向 loopback、LAN、公网时分类和提示不同，手工标签不能降级。
- Standard 发送联系人、通知、位置、文件正文、浏览器/Accessibility 内容时逐次确认；Advanced 规则必须绑定 Provider ID/origin/数据类别/scope，期限只允许 1h/24h/7d/30d（默认 24h、最大 30d、不滑动续期），撤销、到期、时钟回拨或任一绑定字段变化后立即失效。
- API key、OAuth token、Cookie、密码、验证码和认证字段在两个 profile 下均不能进入 Provider 请求。

### 7.3 Tool/Approval

- 未注册工具拒绝。
- unknown schema keyword 拒绝注册。
- additional property 拒绝。
- L2 无 approval 拒绝。
- approval hash 重放到其他会话/Workspace/参数失败。
- approval consume 并发仅一次成功。
- `DENIED`/过期决定不能生成或消费 Approval Proof，不能仅凭非空 decision/consumedAt 放行。
- 存储仓库 API 只接受封闭 `APPROVED`/`DENIED`；pending 和 denied 在 DAO 原子条件层均不可写入 `consumedAt`，不能只依赖 UI/Dispatcher 预检。
- 切换 Standard/Advanced、授予 Android 权限、安装 Runtime 或获得 Root grant 都不会改变既有 approval decision，也不会产生通用批准凭证。
- 产品和测试夹具中不存在 `FULL_ACCESS`、`AUTO_APPROVE_MODEL` 或由模型/MCP/Skill 触发用户批准的路径；Advanced 高敏出网规则只能授权精确出网绑定，不能被 Dispatcher 当作任意 Tool Approval Proof。
- Tool timeout 返回稳定错误。
- Tool result 超限截断并保留 hash/Artifact 引用。
- 并发安全只由规范化参数生成的 effect footprint 决定；伪造的模型/MCP/Skill `isConcurrencySafe` 不生效。无冲突读取可并行，未知/写/代码/Root/UI/同 target lane 必须形成屏障。
- 并行调用以不同完成顺序重复运行，进入模型的结果仍按原始 call sequence 完全一致；真实 timing 只进入审计。
- cancel/kill 时未启动项持久 `CANCELLED_BEFORE_START`，已启动项有 terminal 或 unknown outcome；任何项都不消失、不盲重放。
- sandbox/target/网络失败不触发权限升级、scope 扩大、Root/All-files/Accessibility/LAN 请求或主进程 fallback。技术重试必须证明前一 attempt 零副作用且 envelope 不变。
- 网络连接和正文发送都发生在 origin/数据类别/scope/approval 完成后；禁止 deferred approval 或“先尝试再补批”。
- 结构化用户答案 receipt 在重复、迟到、已取消或 turn/version 变化后返回 `NOT_PENDING`，不能被消费为 Approval Proof。

### 7.4 Workspace

- `../`、absolute、NUL、encoded traversal、separator 变体。
- symlink 指向根外。
- rename race、目标已存在、磁盘满、权限撤销。
- 原子写进程中断后原文件或新文件完整，不能半写。
- Trash 恢复冲突。
- SAF provider 谎报 size/MIME/display name。

### 7.5 QuickJS/PRoot

详见 [本地代码执行方案](../architecture/local-code-execution.md) 第 10 节，全部是发布阻断测试。

- developer 从 Standard 切换到 Advanced 不自动安装或启动 PRoot；安装和每个 Job 仍需独立用户动作/审批。
- Advanced 下 PRoot Runtime 仍无 `INTERNET`；LAN scope、All-files 或 Root 授权不能传递给 Runtime。
- 安装但从未打开的 Runtime 可被批准 Job 冷绑定；未安装/禁用/强制停止/签名或协议不匹配 fail closed，不能回退到主 App shell。
- 空闲解绑并回收后再次执行成功；在 accepted 前、RUNNING、terminal commit 前后分别 kill 主 App/Runtime，恢复只查询同一 jobId，未知结果进入 `INTERRUPTED` 且无重复副作用。
- journal 覆盖 128 条/1 MiB metadata、额度满拒绝新 Job、已对账 payload 清理/7 天 tombstone、未对账 30 天 evidence-expired；active/未对账记录不会为腾空间被静默删除。
- 后台/锁屏/Doze 下只有用途匹配的用户可见 FGS 才继续；任意计算不冒充 `dataSync`。使用 wake lock 时硬 timeout 与全路径释放可证明。
- E1 验证 isolated UID、无 Android 权限/Host Bridge/网络，不能只检查进程名；E2 验证独立 applicationId/UID、无主 App dataDir/真实 Workspace/INTERNET。测试报告不得把 QuickJS 或 PRoot 称为 VM/内核沙箱。
- Git smoke 只在离线 Job 副本内进行；`clone/fetch/pull/push`、credential helper/SSH agent 均失败。HXA-088 覆盖恶意 hooks、alias、filter、external diff、submodule、symlink、对象膨胀/损坏、部分导入、并发修改和进程死亡，不允许零散 `.git` 输出直接覆盖 Workspace。

### 7.6 Browser/File/Accessibility/Root

- All-files granted 后 scope 外路径仍拒绝；撤销权限后立即失败。
- WebView 跨 origin、iframe、恶意下载、永久 JS bridge 检查、导航后旧 token。
- Browser/UI 动作的 token 和 approval 不能跨 tab/package/window 重放。
- 系统设置、安装器、Root 管理器、支付、密码、认证器和锁屏动作拒绝。
- RootService 输入只含结构化参数；Provider/MCP/CLI secret 不可见。
- `root.exec` 不出现在普通 Agent 工具表。
- Accessibility 检查点结合动作数、经过时间、目标 package/window 和敏感语义变化；连续快速批准不能升级为自动允许。Advanced 可在硬上限内调整预算，但不能关闭敏感界面拒绝。

### 7.7 MCP/Skills/Plan/Goal

- MCP server 名称碰撞、超大 schema/result、协议降级、schema hash 变化和恶意 annotation。
- Skill frontmatter、路径、symlink、zip bomb、超大 reference、脚本要求绕过审批。
- Plan mode 同时要求 `operationClass=READ_ONLY` 和动态风险 ≤ L1；L1 新建文件、HTTP 和页面动作仍因 operation class 被拒绝，READ_ONLY/L2 也被拒绝。
- Goal 预算、检查点、进程恢复、Skill snapshot 和副作用不明确处理。Doze/强制停止可延迟提醒，但不会在用户继续前自动调用模型/工具。
- MCP 使用会话级出网预算和数据类别摘要；endpoint、schema 或敏感数据类别变化时强制检查点，不能仅以固定“每 N 次”提示代替边界变化检查。

### 7.8 编排、委托与 Workflow

- `model-visible ⇔ persisted`：ToolResult、用户回答、compaction summary、委托结果、取消和恢复决定均能从持久事件 + content hash/ref 重建；只删内存状态后重放得到同一模型输入。
- scheduler 默认并发 2，并在低内存、热限制、后台时降为 1；不得因 Advanced 或用户设置突破设备实测硬上限。QuickJS/PRoot/Root/Accessibility lane 仍单并发。
- HXA-105 child 仅 developer/Advanced、深度 1、并发 2、每父 Turn 最多 4；所有 token/model/Tool/墙钟计入父预算。绕过父预算、child 自续期或递归派生均失败。
- child 不继承 pending approval、Approval Proof、Secret、Root/Automation session、UI token 或可写 capability；L2/L3/写请求只能形成 proposal，父 Turn 必须重建 ToolCall 并逐次审批。
- parent/child graph、状态、取消和 completion result 持久化；child 完成自述不能直接作为 verifier evidence，必须带真实 ToolResult/Artifact ref。
- JSON Workflow 拒绝未知 node、无界 fan-out/循环、动态插件/Policy、脚本节点和未注册工具。每个合法节点仍经过 Dispatcher、预算、审批、取消、验证和审计。
- 云端任务、remote diff apply、peer-to-peer Agent 消息、自修改插件和 JS/Starlark Workflow/Policy 不出现在 APK、Tool Registry 或恢复状态中。

### 7.9 URL Policy 与 DNS rebinding

- 只接受明确允许的 HTTP(../S) scheme，先做 URL/host 规范化，拒绝 userinfo、混淆编码和不允许的端口。
- 一次解析并检查全部 A/AAAA 候选地址，包括 IPv4-mapped IPv6；任一候选违反当前 `NetworkOriginScope` 时 fail closed。
- HTTP transport 的 DNS 结果只能返回本次已验证地址集合；不得在校验后由另一条系统解析路径重新解析。连接建立后复验 peer address，同时 TLS Host/SNI/证书仍绑定原 hostname。
- 每个 redirect 逐跳重新执行 scheme、origin、DNS/IP、Authorization 和 scope Policy；Authorization/Cookie 不跨 origin，redirect 不继承 LAN 例外到新 host。
- `STANDARD` 的通用 `http.fetch` 只允许公网地址。`ADVANCED` 访问 LAN/loopback 前，用户必须在设置中从字面 host + port 创建精确 scope；模型提供的 URL 不能创建或扩大该 scope。
- loopback/LAN 例外不包含云 metadata、平台保留地址或 scope 外的其他私网地址；DNS rebinding、连接复用和缓存命中仍须满足当前 scope。

实现和测试依据：[OWASP SSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html)。

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
- Provider/MCP 发送前可见 origin/residence、数据类别和 scope；Advanced 保存规则可查看、撤销和过期，诊断包不含发送正文。
- 浏览器 cookie/history/site data 可清除；默认不进模型或诊断包。
- All-files roots、Accessibility allowlist 和 RootSession 可查看/撤销。
- CLI Runtime 中的凭据存储、logout 和卸载删除方式明确。
- 没有默认开启的第三方 analytics；如未来引入需单独同意和数据声明。

无第三方 analytics 时，稳定性证据来自本地、脱敏且用户可预览的诊断记录：API 30+ 读取 `ApplicationExitInfo` 的 reason/timestamp，所有版本保留最后 Turn/correlation/state 与 heartbeat，异常处理器只写有界结构化摘要并继续交给系统 handler。不保存模型/通知/文件正文，不声称仅靠 App 内 watchdog 能完整捕获 ANR；24 小时验收同时保留 instrumentation、logcat/ANR 和退出原因证据。

## 12. 发布门禁

Standard 是 Google Play、国内 Android 应用商店和官网的完整产品形态，Advanced 在渠道允许时于同一产品身份内显式进入；PRoot/CLI 是可选 companion Runtime。consumer/developer 的构建和扫描继续作为当前编译边界证据，但不再预设哪个等于商店阉割版或官网完整版。最终 artifact、applicationId 与渠道能力矩阵由 HXA-120～123 验收。见 [ADR-0013](../adr/0013-standard-store-capability-preserving-distribution.md)。

### 12.1 所有渠道的 Standard 共同门禁

- 对应发布范围的 HXA 已完成，40 条核心场景和确定性工具指标达到产品需求；未完成能力不进入 listing。
- API 29、34/35、36 的适用真机矩阵、24 小时稳定性、恢复与副作用测试通过。
- Standard 完成 Provider、Workspace/文件、Browser、解释执行、MCP/Skills 和 Android 基础工具中该版本声明的核心任务，不得退化为聊天壳。
- unit/lint/instrumentation、SBOM、notice、签名、升级/回滚、权限、隐私、数据安全和诊断删除门禁通过。
- 每个渠道差异都有政策原文版本或真实审核反馈；consumer/developer flavor 名不能充当删减理由。

### 12.2 Google Play 门禁

- All-files 仅在文件/文档管理核心用途、SAF 不足证据、Permissions Declaration 和审核材料齐备时声明；否则使用 SAF/MediaStore 降级但保留文件工作台。
- Accessibility 只暴露当前政策允许的确定性、用户定义自动化；不把 Agent 自主发起、规划和执行 UI 操作打入或宣传为 Play 能力。
- 解释型脚本不得从 Play 外下载 DEX/JAR/`.so` 等 executable code，也不得借解释器绕过 Play 政策。
- target API、Data safety、显著披露、演示视频和商店 listing 与真实 artifact 一致。构建成功或“准备提交”不能写成已上架。

### 12.3 国内 Android 应用商店门禁

- 对每个目标商店分别记录提交日期、官方规则版本、权限/SDK/备案/隐私要求、artifact hash 和审核状态；不套用 Google Play 或其他商店结论。
- 能通过声明、披露、同意或审核保留的能力优先保留；拒审产生的差异只作用于该渠道，并记录替代路径。

### 12.4 官网与 Advanced 门禁

- 官网 Standard 继续通过共同门禁；Advanced/Root/PRoot/CLI/Agent UI 自动化按对应专项测试发布，不因官网分发跳过签名、权限、许可证或真实结果要求。
- PRoot/CLI Runtime 保持独立 UID；hash/source/license/smoke/rollback/uninstall 与 signature IPC 通过。
- 若无法证明 CLI 内置工具受 Helix Policy/Approval 控制，只能标记为隔离 CLI 会话，不进入 Act/Goal Provider。

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
