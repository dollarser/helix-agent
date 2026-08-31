# Helix 技术路线与可执行开发任务

文档状态：Baseline 1.3
规则：一个 HXA 任务对应一个可审查的纵向切片；未通过本任务验收不得进入后续任务。

## 1. 路线总览

```text
M0 工程基线
  → M1 领域状态、Plan/Goal 与持久化
  → M2 三种模型协议与 Provider
  → M3 Tool/Policy/Approval/Capability
  → M4 Workspace 与文件管理器
  → M5 QuickJS 本地代码执行
  → M6 内置浏览器与 Android 基础工具
  → M7 MCP Client 与 Agent Skills
  → M8 PRoot Linux Runtime
  → M9 Accessibility 与 Root
  → M10 单机 Alpha/Beta 硬化
  → M11 官方 CLI 订阅后端实验
  → M12 直接分发 Release
```

远程 Worker、云端沙箱、桌面配对和 HarmonyOS 不属于当前路线。只在 M1 定义 `ExecutionTarget/ToolExecutor` 领域接口，不创建网络空模块。

## 2. 里程碑退出条件

| 里程碑 | 用户可见结果 | 退出条件 |
| --- | --- | --- |
| M0 | App 可安装，显示空壳页面 | CI、lint、unit test、debug build 通过 |
| M1 | 会话、Plan、Goal 可持久化 | 状态机、预算、Context Builder 裁剪/信任标记和 migration 测试通过 |
| M2 | 可选择官方 API 或自建模型流式聊天 | 三协议 fixture、能力探测、取消和错误分类通过 |
| M3 | 模型能提出工具并等待审批 | 参数/scope/schema 变化使审批失效 |
| M4 | 用户可管理和让 Agent 处理授权文件 | Workspace/SAF/All-files 攻击测试通过 |
| M5 | Agent 可安全生成并运行 JS | isolated process、超时、内存测试通过 |
| M6 | Agent 可研究网页并调用 Android 基础能力 | WebView token、权限拒绝和站点安全测试通过 |
| M7 | 用户可连接 MCP、导入和运行 Skill | 动态 schema、恶意 Skill 和渐进加载测试通过 |
| M8 | `bash` 可在本地 Linux Job 副本执行 | PRoot 安装、IPC、smoke、回滚、许可证通过 |
| M9 | 高级用户可开启跨 App 自动化、Android UI Skill 和 Root 只读工具 | 敏感界面、scope、停止、Skill 逐步复验和 Root 拒绝测试通过 |
| M10 | 固定场景可重复完成 | 指标、安全、恢复、资源和隐私门禁达标 |
| M11 | 可选官方 CLI 隔离会话 | 凭据隔离和工具拦截结论有证据；不合格则保持独立 CLI 模式 |
| M12 | Android 直接分发包可发布 | 全部门禁、SBOM、notice、权限说明和真机证据齐全 |

## 3. 所有任务的共同规则

- 开始前读取根 `AGENTS.md`、本任务、相关专项文档和前置接口。
- 一次只实现一个任务 ID；生产代码、测试和必要文档同一任务完成。
- 不在功能任务中升级依赖、改变风险等级或扩大权限。
- 不使用 TODO、空实现、catch-all 成功、删除测试或 `-x test`。
- 每个系统权限都测试 unavailable、denied、granted、revoked。
- 每个有副作用工具都测试取消、超时、恢复和“不明确结果不重试”。
- 验收报告给出真实命令、exit code、设备和剩余限制。
- 开始前按 [ADR 约定](adr/README.md) 检索同一机制的既有决定；触发 ADR 的任务必须在同一 HXA 中新增、更新或显式取代记录。普通契约内实现不强制制造 ADR。
- 小模型默认只能起草 `proposed`；`accepted` 不代表已实现，改变既有决定时必须停止并等待授权。

### 3.1 验收命令不得猜测

HXA-001 是唯一个可在 Gradle 工程存在前列出完整命令的任务。它必须在实际创建的 task 名称可查后产生 `docs/verification-matrix.md`，为 HXA-002 及后续每个任务列出可复制的 JVM/Android/fixture/真机验收命令、所需设备和预期产物。命令必须来自 `./gradlew tasks`、模块和 source set 的真实名称；尚不存在的 task 不得靠猜测写入矩阵。

HXA-002 以后，若矩阵中该任务的命令缺失或已失效，先更新矩阵并审查，不开始功能实现。每个 HXA 完成记录仍要复制当次实际命令、exit code 和结果，不得只链接 CI 页面。

## 4. M0：工程基线

### HXA-001 Gradle 多模块工程

创建 [总体方案](02-architecture-design.md) 的基础模块和 `consumer`/`developer` 主 App 变体、独立 `runtime:proot-app`、预留但不接业务的 `runtime:cli-app` Android application。固定工具链、version catalog、dependency lock、Java 17 和 UTF-8。

固定 application/namespace 基线：`consumer=com.helix.agent`、`developer=com.helix.agent.developer`、`proot-runtime=com.helix.runtime.proot`、`cli-runtime=com.helix.runtime.cli`，共享 Kotlin namespace 前缀 `com.helix`。发布所有者在首次对外发布前仍要验证 applicationId 唯一性。项目源码使用根 `LICENSE` 声明的 Apache License 2.0；第三方代码、依赖和运行时资产按 `THIRD_PARTY_NOTICES.md` 保留各自义务，不能因项目许可证而被重许可。

变体裁剪使用 Gradle variant-aware dependency：通用模块用 `implementation`，`files-allfiles`、`tools:automation`、`tools:root`、`runtime:proot-client`、`runtime:cli-client` 只能用 `developerImplementation` 及 `src/developer` manifest/navigation/DI 注册。`src/consumer` 不得引用这些类或资源；HXA-001 先用 fake marker 证明 consumer APK 扫描不包含 developer-only marker。

禁止：业务逻辑、Hilt、动态版本、远程 Worker/Harmony 模块。

验收：

```bash
./gradlew projects
./gradlew :app:assembleConsumerDebug :app:assembleDeveloperDebug
./gradlew :runtime:proot-app:assembleDebug :runtime:cli-app:assembleDebug
./gradlew test
```

同时验收 `docs/verification-matrix.md`、applicationId/manifest 扫描、consumer/developer dependency graph 和根 `LICENSE` 决策；任一项缺失则 HXA-001 不完成。

### HXA-002 质量和供应链门禁

配置 Spotless、Detekt、Android lint、Gradle Wrapper validation、dependency verification、lock diff、secret scan、`scripts/verify-adr.sh` 和 `git diff --check`。ADR 门禁检查稳定编号、状态/字段/章节、相对链接和双向取代关系，不用关键词猜测内容质量。JitPack 仅允许 libsu exclusive content，M9 前不加入依赖。

### HXA-003 AppContainer 与导航壳

手工 DI；会话、文件、浏览器、扩展、权限、设置、审计七个空状态 route。UI 只依赖接口/fake repository。

## 5. M1：领域状态、Plan/Goal 与存储

### HXA-010 领域 ID、错误和执行目标

实现 value classes、Turn/Tool/Execution/Goal 状态、RiskLevel、统一 `Capability`、`ToolOperationClass`、`TurnBudgets`、不含文件 I/O 的不透明 `ArtifactRef`、HelixError、Clock、IdGenerator、`ExecutionTargetDescriptor`、`ToolExecutionEnvelope`。纯 Kotlin 测试序列化和非法输入。

### HXA-011 Turn reducer

实现 `state + event -> state/effects`，覆盖完成、工具结果回填后重新进入 context/model 循环、审批拒绝、取消、step/token/模型调用预算超限、usage 缺失、模型失败、进程中断和副作用不明确。

### HXA-012 PlanArtifact 与模式策略

实现 Chat/Plan/Act/Goal；Chat 默认工具表为空，用户显式启用后仍只允许 `operationClass=READ_ONLY` 且动态风险为 L0；Plan 同时要求 `operationClass=READ_ONLY` 和动态风险 ≤ L1，operation class 是主判断，风险上限不能替代它，并生成版本化 artifact/hash。测试 Chat 默认无工具、Chat 下 `write` 被拒绝、Plan 允许 READ_ONLY/L0/L1、拒绝 READ_ONLY/L2，以及 `write/http.fetch/bash/browser.click/ui.click` 在 Plan 被拒绝。该安全边界由 [ADR-0003](adr/0003-plan-read-only-risk-ceiling.md)记录。

### HXA-013 Goal reducer 与预算

实现 Goal 状态、验收条件、模型/工具/token/时长/重试预算、checkpoint、`INPUT_REQUIRED`。首版只有用户显式继续创建新 run；WorkManager 只发可延迟提醒，不调模型/工具。测试 Doze/强停/提醒延迟。预算耗尽不得完成；只有 verifier evidence 可满足 criterion。

### HXA-014 Room schema 与 Repository

实现 architecture 文档全部基础表和 plan/goal 表；大型正文存文件。API key/token 只保存 alias。启用 foreign key、schema export 和 migration test。

### HXA-015 恢复协调器

遗留活动 Turn 标为 `INTERRUPTED`；Goal 可恢复，但不自动重放有副作用或结果不明确的 ToolCall。

### HXA-016 Context Builder

在 `core:agent` 实现可审计的上下文装配：`sourceType/sourceId/trust/contentHash`、Provider/token 预算、确定性裁剪、大 ToolResult 转 summary + `ArtifactRef`。本任务只使用 HXA-010 的不透明引用类型和 fake repository，不实现 Artifact 文件存储、hash 生命周期或 Workspace I/O；真实存储属于 HXA-041。当前工具参数、审批上下文和对应结果不得做字符级截断；Secret 和未选中文件不进入请求。测试不可信来源、超预算、稳定排序、多工具结果和 token usage 缺失。

## 6. M2：模型 Provider

### HXA-020 SecretStore 与 Provider 配置

Android Keystore 包装 secret；实现 protocol、endpoint、model、headers allowlist、secretAlias、删除和覆盖。日志/Room/SavedStateHandle 不含 secret。

### HXA-021 内部 ModelRequest/ModelEvent

纯 Kotlin contract 支持文本、图像引用、工具 schema、reasoning、tool arguments delta、usage、finish/refusal/error。Agent Core 不依赖厂商 DTO。

### HXA-022 OpenAI Responses adapter

实现 Responses 流和 function calls。fixture 覆盖任意字节拆包、UTF-8、多个工具、拒绝、usage、无终止和断流。

### HXA-023 OpenAI Chat Completions adapter

独立实现 Chat Completions SSE。不得失败后自动切 Responses；协议由配置确定。

### HXA-024 Anthropic Messages adapter

实现 Messages streaming、`tool_use/tool_result`、stop reason 和错误映射。测试 tool result 顺序约束。

### HXA-025 Provider 连接与能力探测

依次测试 transport/auth、models、最小文本流、最小 ToolCall；保存 capability snapshot 和来源。手工 override 必须标记。

### HXA-026 模板目录

实现 OpenAI、Anthropic、Generic OpenAI、Ollama、SGLang；再加 DeepSeek、DashScope/Qwen、OpenRouter、Moonshot/Kimi、Zhipu、MiniMax、xAI、Groq、vLLM、LM Studio 模板。模板只含协议/endpoint/header 规则，不写死模型名和 key。

### HXA-027 自建服务真机 smoke

使用开发者本地 Ollama 和 SGLang，各验证文本流和 ToolCall。明确记录不支持字段；局域网 HTTP 必须按 host:port 单独开启。

### HXA-028 聊天 UI

首次启动隐私说明、Provider 创建/编辑/连接测试、会话、模型选择、流式输出、停止、错误、重试、能力标签和当前 mode。UI 订阅持久化状态，不持有网络 Job；未完成连接测试不贬为“已可用”。

## 7. M3：Tool、Policy、Approval 与 Capability

### HXA-030 ToolDescriptor、Registry 和 ToolSource

实现 BuiltIn/MCP 工具来源、稳定 name/version/schema hash、`ToolOperationClass`、风险、限额、统一 `Capability`、execution target。Plan 只看 `READ_ONLY`；MCP annotation 不能降类。重复注册失败。

### HXA-031 JSON Schema 子集

实现项目需要的 object/array/string/number/integer/boolean/enum/required/additionalProperties/min/max/pattern；unknown keyword 拒绝注册。

### HXA-032 Capability Center

实现系统真实状态 resolver 和 Workspace/SAF/All-files/BrowserTab/Automation/Root scope 类型。缓存不代替执行时检查。

### HXA-033 Policy Engine

风险结合 mode、scope、数据敏感度、网络 origin、tool source、execution target 和参数。未知工具/Capability/L3 默认拒绝。

### HXA-034 Approval hash 与一次性消费

canonical JSON + tool/version/schema/scope/session/target hash；一次性、过期、拒绝和并发 consume。页面/UI token 也绑定 approval。

### HXA-035 Dispatcher 与审计

validate → capability → policy → approval → timeout/cancel → execute → bound result → verify → audit。先用 `time.now` 和 fake mutating tool 验证；用户拒绝后的完全相同高风险动作不得在同一 Turn 重复请求，参数/作用域实质变化才可生成新审批。

### HXA-036 Timeline 和审批卡

显示来源、目标、scope、参数、风险、代码/命令、网络 origin、预期影响和 verifier。参数变化重建审批。同任务交付审计日志页，可按会话、工具、风险和日期过滤，只展示脱敏记录/有界摘要。

## 8. M4：Workspace 与文件管理器

### HXA-040 WorkspacePath 和 FileScopePath

拒绝 absolute、`..`、NUL、separator 变体、越界 symlink；不同 scope adapter 不泄漏真实路径给模型。

### HXA-041 Artifact、配额和原子文件操作

实现目录布局、hash、临时写+fsync+replace、前置 hash、配额和 bounded MIME/encoding detection。

### HXA-042 Pi 风格基础工具

实现 `read`、`write`、`edit` 和 `files.list/search/stat/mkdir`。`read` 必须有 `offset/maxBytes`、编码边界和稳定 EOF 语义，覆盖 10 MiB 文件分块处理。短名称与 namespaced implementation 共用同一 Policy。

### HXA-043 Copy/Move/Delete/Trash

冲突策略显式；删除进 Helix trash，恢复与物理清空分开。跨 scope 和覆盖提升风险。

### HXA-044 SAF adapter

导入/导出、persisted tree grant、撤销检测、恶意 ContentProvider metadata 和大流取消。

### HXA-045 All files access

实现说明页、系统设置跳转、`Environment.isExternalStorageManager()` 验证和 Helix roots。即使获系统权限，scope 外路径仍拒绝。

### HXA-046 文件管理 UI

路径、来源标识、排序、多选、预览、冲突、长操作进度/取消、trash 和分享。无权限时可完整使用 Workspace。

### HXA-047 Archive 工具

实现受限 zip/tar 创建与解压；防 Zip Slip、symlink/device、文件数、总大小和膨胀比。

## 9. M5：QuickJS

### HXA-050 Zipline Spike

Android 29/34/36、arm64/x86_64 验证 evaluate、memoryLimit、InterruptHandler、`Function`/constructor 禁用、大于 6 MiB 的调用线程 stack、16 KiB page 及 `bindIsolatedService` 唯一实例回收。按 [ADR 约定](adr/README.md) 产出决定与证据；关键能力失败则停下重新选型，不能提前写成已实现。

### HXA-051 isolated Service/Binder

`isolatedProcess=true`、`exported=false`；API 29+ 每 execution 使用唯一 `bindIsolatedService` instance，instance name 固定为 `js_` + 32 位小写 hex，覆盖 Android 非法字符、长度和碰撞测试；Parcelable/PFD、interrupt、unbind/PID 回收、Binder death、崩溃、取消。不使用 `killProcess`/`System.exit` 当作正常控制面。

### HXA-052 JS ABI 与限制

JSON 输入输出、host 编码参数 + 局部 `const` input 的严格 IIFE wrapper、每任务新实例、64 MiB、10 s、bounded source/input/output；无 file/network/Android bridge。测试生成代码覆盖 input global、`eval`、`Function`、constructor 变体和包裹逃逸。

### HXA-053 `code.javascript.run`

完整代码审批、input summary、hash 和执行结果回填。

### HXA-054 攻击和端到端测试

无限循环、内存、输出洪泛、eval/fetch/require、进程崩溃、Binder 大输入、取消竞态和 verified artifact。

## 10. M6：浏览器与 Android 基础工具

### HXA-060 最小 WebView 浏览器

System WebView + AndroidX WebKit；标签、导航、错误页、下载 UI、生命周期和站点数据清除。禁用危险 file access/mixed content/永久 JS bridge。

### HXA-061 Browser snapshot

固定版本 JS 提取有界语义树；node token 绑定 tab、origin、navigation generation、fingerprint 和 TTL。网页内容标记不可信。

### HXA-062 Browser actions

实现 open/navigate/back/forward/reload/find/click/type/scroll/screenshot。导航或 DOM 变化使旧 token 失效；密码/验证码/支付字段拒绝。

### HXA-063 Browser download

URL/重定向/MIME/size/name/target policy，下载到 Workspace/SAF；不自动打开或执行 APK/DEX/JAR/SO。

### HXA-064 分享、Intent 和剪贴板

分享输入先预览；`android.open_uri` 只打开；clipboard read/write 按可见前台和风险限制。

### HXA-065 通知和日历

通知 app allowlist/time window；Calendar 先草稿再 commit。权限关闭返回 `PermissionMissing`。

### HXA-066 HTTP fetch 与前台任务

GET/HEAD、SSRF/redirect/size/timeout；前台服务只覆盖用户主动发起的 Provider/MCP 传输或本地文件处理，声明 `dataSync` + `FOREGROUND_SERVICE_DATA_SYNC`，等待审批/输入时停止，实现 Android 15+ `onTimeout()` 和 6 小时/24 小时限额测试。通知提供停止；WorkManager 只做可延期维护/提醒。

### HXA-067 语音输入与本地化

使用系统语音识别 Activity/Service 能力将用户主动录音转为可编辑草稿，不后台常驻监听、不自动发送给模型；覆盖 unavailable/denied/cancel/error 和前后台转换。UI 提供简体中文/英文资源并跟随系统，扫描用户可见硬编码字符串。

## 11. M7：MCP 与 Skills

### HXA-070 MCP Kotlin SDK Android Spike

固定 client artifact 和 Ktor OkHttp engine；API 29/36、R8、Streamable HTTP、取消、重连、大消息和后台。SDK 类型不得泄漏 core。

### HXA-071 MCP Server 配置和握手

disabled-by-default config、SecretStore bearer auth、initialize、protocol/capability snapshot、tools/resources/prompts 有界 metadata 列表。首版 resources/prompts 正文不进 Context，OAuth/PKCE 不实现。

### HXA-072 MCP 动态 Tool bridge

注册 `mcp.<server>.<tool>`；annotation 不能降风险；schema hash 变化撤销审批；结果块有界。sampling/elicitation/roots 关闭。

### HXA-073 MCP stdio bridge

PRoot Runtime 启动锁定 server command，严格 stdout JSON-RPC、bounded stderr、环境 allowlist 和进程取消。此任务依赖 HXA-084；可在 M8 后完成。

### HXA-074 Skill validator 和 catalog

按 Agent Skills 规范解析 frontmatter；catalog 只含 metadata/hash/source。使用官方 fixture，自行实现 Kotlin loader，不把 `skills-ref` 用作 production runtime。

### HXA-075 Skill 导入和快照

本地目录/zip 导入、防 traversal/symlink/zip bomb，展示 scripts/resources/hash；运行固定 snapshot，更新不静默替换。

### HXA-076 Skill 工具与首批内置 Skills

实现 list/read/read_resource/enable/disable/remove；脚本只通过已有执行工具。加入文件整理预览、网页研究、数据转换、repo inspection、notification digest 五个小 Skill 和端到端测试；`notification-digest` 依赖已验收的 HXA-065。`android-ui-task` 不在 M7 提前实现，延后到 HXA-097。

## 12. M8：PRoot Linux Runtime

### HXA-080 Runtime manifest/许可证 schema

component、version、ABI、URL、size、sha256、license、source、patches；唯一版本真相。

### HXA-081 构建期固定资产

固定 PRoot、Alpine、bash/git/python/node/ripgrep；验证 hash/ABI/license/ELF `LOAD` alignment，记录 APK 压缩、安装后和临时峰值体积，不在设备下载 executable。

### HXA-082 RootFS installer

`.partial`、防 Zip Slip、smoke、原子激活、保留一版 rollback。

### HXA-083 独立 Runtime APK/IPC

独立 applicationId/UID、signature permission、caller signature、protocol/version/ABI handshake、PFD manifest。

### HXA-084 PRoot runner

Job copy、argv/显式 shell script、cwd/env allowlist、timeout/process-group cancel、bounded stdout/stderr、output archive/hash。任务自定义 env 对名称 secret pattern、已知 SecretStore 值和认证来源做拒绝测试，Runtime 不获得 SecretStore 能力。

### HXA-085 `bash` Tool

每次审批；禁止 Android 主进程 shell、真实 Workspace mount、网络包安装和 secret inheritance。

### HXA-086 真机 smoke 与隔离

arm64 分别在 4 KiB/16 KiB 页真机执行 python/node/git/ripgrep；Runtime 不能读主 App dataDir、共享存储或联网。任一 ELF 或 smoke 不兼容时阻断对该设备分发并记录最低设备集。

### HXA-087 更新/卸载/法律页

同签名 APK 更新、rollback、完整删除、离线 notice/source URL/build manifest。

## 13. M9：Accessibility 与 Root

### HXA-090 Accessibility Service 与权限中心

用户主动系统设置开启；service state、包 allowlist、限时 AutomationSession、前台停止。先用自建测试 App，不接 Agent。

### HXA-091 UI snapshot/token

有界 AccessibilityNodeInfo tree，token 绑定 package/window/generation/fingerprint。处理 null root、secure/custom views 和节点回收。

### HXA-092 UI actions

find/click/long_click/set_text/scroll/back/home/wait。目标包变化暂停；无坐标 blind click；敏感包和语义拒绝。

### HXA-093 Accessibility 攻击/恢复测试

系统权限、安装器、Root 管理、支付/认证界面拒绝；锁屏、服务断开、强停、snapshot 过期和连续动作预算。

### HXA-094 libsu Spike 与依赖审计

使用 libsu 6.0.0 core/service；JitPack exclusive content + dependency verification。Root 不可用、拒绝、授权、丢失和 RootService crash 真机测试。

### HXA-095 Root 高层只读工具

status、file.read、package.info、process.list、bounded log.read；短时 RootSession，Provider secret 不进入 RootService。

### HXA-096 Root L3 控制台

`root.exec` 仅开发者 UI、默认不进入模型工具表。禁止 SELinux/boot/system/凭据/静默安装与授权。若无明确产品需要可保持未实现。

### HXA-097 Android UI Skill

依赖 HXA-091/092/093 已验收的 snapshot/token、UI actions 和敏感界面拒绝契约，再实现 `android-ui-task` 内置 Skill：先 snapshot、只用节点 token 动作、每步复验、目标包变化立即暂停。Skill 不新增权限或执行器，并用自建 fixture App 覆盖成功、过期 token、敏感界面和中途停止。

## 14. M10：单机硬化

### HXA-100 固定评测集

至少 40 条：聊天/Plan/Goal、三 Provider 协议、文件、JS、浏览器、MCP、Skill、Accessibility、Root 拒绝路径。记录 provider/model/protocol/prompt hash/tool versions/device/date。

### HXA-101 Prompt injection

网页、文件、MCP description/result、Skill、通知和文件名中的注入；验证不能扩大 scope、注册工具或绕过审批。

### HXA-102 恢复和副作用

模型流、审批、文件写、浏览器动作、UI 动作、MCP call、PRoot 运行各阶段 kill/restart；不明确结果进入 `NEEDS_REVIEW`。

### HXA-103 资源/稳定性

API 29/34/35/36、低内存、断网、Doze、锁屏、旋转、24 小时；WebView/Room/coroutine/Binder/process/file descriptor 无泄漏。

### HXA-104 隐私/诊断/删除

日志脱敏、用户预览诊断包、删除会话/Provider/MCP/Skill/Goal/Workspace/Runtime/站点数据和 Root session。API 30+ 用 `ApplicationExitInfo`，所有版本保存脱敏最后状态/heartbeat，uncaught handler 写有界摘要后继续交给系统；24 小时证据同时保留 instrumentation、logcat/ANR 和退出原因，不声称 App watchdog 能完整捕获 ANR。

## 15. M11：官方 CLI 订阅后端实验

### HXA-110 CLI Runtime manifest 与独立 UID

有 INTERNET，无 All-files/Accessibility/Root/主 App数据；固定 Node/official CLI/hash/license/terms URL。

### HXA-111 Codex app-server 登录 Spike

先验证官方 Codex CLI/app-server 是否存在可在 Android/Linux arm64 运行的受支持发布形态，并按 [ADR 约定](adr/README.md) 就原生执行与独立 PRoot/RootFS 记录决定、替代方案和证据；失败则停止打包路线。成立后使用官方 ChatGPT OAuth/device code；Codex 持有 token，主 App只看登录事件/plan type。验证 logout/uninstall 删除和限额错误。

### HXA-112 Claude Code stream-json/SDK Spike

先验证官方 Claude Code/Node bundle 在 Android/Linux arm64 的受支持运行形态并纳入同一 CLI 底座 ADR；成立后使用官方 Claude 登录和公开 non-interactive interface，验证凭据隔离、取消、输出限制。

### HXA-113 工具与审批兼容性结论

证明能禁用或代理 CLI 内置工具且不会绕过 Helix Policy，才实现 Agent backend adapter。否则明确保留“隔离 CLI 会话”，不进入 Act/Goal Provider 列表。

## 16. M12：直接分发发布

### HXA-120 变体和 APK 审计

consumer 不含 `files-allfiles`、`tools:automation`、`tools:root`、PRoot/CLI client/route/permission；developer 声明的 All-files/Accessibility 与 UI 功能一致；Runtime applicationId/UID/权限正确。核对 HXA-001 的四个 applicationId、variant dependency graph 和 source set marker，并根据 HXA-081 真实产物设定 Runtime 发布体积预算。

### HXA-121 发布门禁

执行 [安全文档](07-security-testing-release.md) 全部命令、真机矩阵、SBOM、notice、权限说明、数据流和已知限制。

### HXA-122 签名和更新

离线签名材料流程、主/Runtime 同签名关系、可回滚升级、安装顺序和来源校验。当前不做 App 内静默自更新。

### HXA-123 未来渠道评估

若决定进入 Google Play 或其他商店，重新评估 All-files、Accessibility、动态代码、Root 和 CLI Runtime，并新建受限 flavor；不得修改当前事实来“假装合规”。

## 17. 每任务完成记录

```markdown
## HXA-XXX 完成记录

- 目标：
- 前置任务/commit：
- 修改文件：
- 实现的契约：
- 未实现/明确排除：
- 自动测试：
- 手工验证设备：
- 验收命令、exit code 和结果：
- 权限/数据/许可证变化：
- 决策记录：ADR-NNNN（链接和状态），或“不适用：<未形成架构决定的原因>”
- 风险或后续任务：
```

“代码已写”“能够编译”“单次演示成功”都不等于完成。
