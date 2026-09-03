# Helix 开发路线与可执行任务

文档状态：Baseline 1.5
规则：一个 HXA 任务对应一个可审查的纵向切片；未通过本任务验收不得进入后续任务。

## 1. 路线总览

```text
M0 工程基线
  → M1 领域状态、Plan/Goal 与持久化
  → M2 三种模型协议与 Provider
  → M3 Tool/Policy/Approval/Capability
  → M4 Workspace 与文件管理器
  → M5 QuickJS 本地代码执行
  → M6 内置浏览器、Android 基础工具与国际化
  → M7 MCP Client、A2A Client 与 Agent Skills
  → M8 PRoot Linux Runtime
  → M9 Accessibility 与 Root
  → M10 单机 Alpha/Beta 硬化
  → M11 官方 CLI 订阅后端实验
  → M12 直接分发 Release
```

远程 Worker、云端沙箱、桌面配对和 HarmonyOS 不属于当前路线。M7 的 A2A Client 是用户配置的外部 Agent 连接器，不是远程 `ExecutionTarget`/Worker；只在 HXA-077 Spike 通过并形成协议决定后创建真实模块，不提前创建网络空模块。

## 2. 里程碑退出条件

| 里程碑 | 用户可见结果 | 退出条件 |
| --- | --- | --- |
| M0 | App 可安装，显示空壳页面 | CI、lint、unit test、debug build 通过 |
| M1 | 会话、Plan、Goal 可持久化 | 状态机、预算、Context Builder 裁剪/信任标记和 migration 测试通过 |
| M2 | 可选择官方 API 或自建模型流式聊天 | 三协议 fixture、能力探测、取消和错误分类通过 |
| M3 | 模型能提出工具并等待审批 | 参数/scope/schema 变化使审批失效；并发读取、排他屏障、取消与固定顺序回填可确定重放 |
| M4 | 用户可管理和让 Agent 处理授权文件 | Workspace/SAF/All-files 攻击测试通过 |
| M5 | Agent 可安全生成并运行 JS | isolated process、超时、内存测试通过 |
| M6 | Agent 可研究网页并调用 Android 基础能力，用户可使用中英文 UI | WebView token、权限拒绝、站点安全与 API 29/36 语言切换/重启测试通过，用户可见字符串扫描无违规 |
| M7 | 用户可连接 MCP/A2A、导入和运行 Skill | MCP 动态 schema、A2A Task 恢复、恶意远端内容/Skill 和渐进加载测试通过 |
| M8 | `bash` 可在本地 Linux Job 副本执行 | PRoot 安装、IPC、smoke、回滚、许可证通过 |
| M9 | 高级用户可开启跨 App 自动化、Android UI Skill 和 Root 只读工具 | 敏感界面、scope、停止、Skill 逐步复验和 Root 拒绝测试通过 |
| M10 | 固定场景可重复完成 | 指标、安全、恢复、资源和隐私门禁达标；HXA-105 以接受或有证据拒绝的 ADR 收口，不留半实现编排入口 |
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
- HXA-069 验收后，所有新增用户可见文案必须使用资源键并同步补齐简体中文/英文；Tool 名、协议字段、审计类型和稳定错误码不翻译。
- 开始前按 [ADR 约定](../adr/README.md) 检索同一机制的既有决定；触发 ADR 的任务必须在同一 HXA 中新增、更新或显式取代记录。普通契约内实现不强制制造 ADR。
- 小模型默认只能起草 `proposed`；`accepted` 不代表已实现，改变既有决定时必须停止并等待授权。

### 3.1 验收命令不得猜测

HXA-001 是唯一个可在 Gradle 工程存在前列出完整命令的任务。它必须在实际创建的 task 名称可查后产生 `docs/development/verification-matrix.md`，为 HXA-002 及后续每个任务列出可复制的 JVM/Android/fixture/真机验收命令、所需设备和预期产物。命令必须来自 `./gradlew tasks`、模块和 source set 的真实名称；尚不存在的 task 不得靠猜测写入矩阵。

HXA-002 以后，若矩阵中该任务的命令缺失或已失效，先更新矩阵并审查，不开始功能实现。每个 HXA 完成记录仍要复制当次实际命令、exit code 和结果，不得只链接 CI 页面。

## 4. M0：工程基线

### HXA-001 Gradle 多模块工程

创建 [总体方案](../architecture/overview.md) 的基础模块和 `consumer`/`developer` 主 App 变体、独立 `runtime:proot-app`、预留但不接业务的 `runtime:cli-app` Android application。固定工具链、version catalog、dependency lock、Java 17 和 UTF-8。

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

同时验收 `docs/development/verification-matrix.md`、applicationId/manifest 扫描、consumer/developer dependency graph 和根 `LICENSE` 决策；任一项缺失则 HXA-001 不完成。

### HXA-002 质量和供应链门禁

配置 Spotless、Detekt、Android lint、Gradle Wrapper validation、dependency verification、lock diff、secret scan、`scripts/verify-adr.sh` 和 `git diff --check`。ADR 门禁检查稳定编号、状态/字段/章节、相对链接和双向取代关系，不用关键词猜测内容质量。JitPack 仅允许 libsu exclusive content，M9 前不加入依赖。

### HXA-003 AppContainer 与导航壳

手工 DI；会话、文件、浏览器、扩展、权限、设置、审计七个空状态 route。UI 只依赖接口/fake repository。

## 5. M1：领域状态、Plan/Goal 与存储

M1 已完成（2026-08-31）：各任务完成证据见[完成记录](../completion-records/README.md)中的 HXA-010～016 记录，状态以[实施状态](status.md)为准。

### HXA-010 领域 ID、错误和执行目标

实现 value classes、Turn/Tool/Execution/Goal 状态、RiskLevel、统一 `Capability`、`ToolOperationClass`、`TurnBudgets`、不含文件 I/O 的不透明 `ArtifactRef`、HelixError、Clock、IdGenerator、`ExecutionTargetDescriptor`、`ToolExecutionEnvelope`。纯 Kotlin 测试序列化和非法输入。

### HXA-011 Turn reducer

实现 `state + event -> state/effects`，覆盖完成、工具结果回填后重新进入 context/model 循环、审批拒绝、取消、step/token/模型调用预算超限、usage 缺失、模型失败、进程中断和副作用不明确。

### HXA-012 PlanArtifact 与模式策略

实现 Chat/Plan/Act/Goal；Chat 默认工具表为空，用户显式启用后仍只允许 `operationClass=READ_ONLY` 且动态风险为 L0；Plan 同时要求 `operationClass=READ_ONLY` 和动态风险 ≤ L1，operation class 是主判断，风险上限不能替代它，并生成版本化 artifact/hash。测试 Chat 默认无工具、Chat 下 `write` 被拒绝、Plan 允许 READ_ONLY/L0/L1、拒绝 READ_ONLY/L2，以及 `write/http.fetch/bash/browser.click/ui.click` 在 Plan 被拒绝。该安全边界由 [ADR-0003](../adr/0003-plan-read-only-risk-ceiling.md)记录。

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

Android Keystore 包装 secret；实现 protocol、endpoint、model、headers allowlist、secretAlias、删除和覆盖。规范化实际 endpoint 并得出 `ON_DEVICE_LOOPBACK/USER_AUTHORIZED_LAN/PUBLIC_CLOUD/CUSTOM_REMOTE_UNKNOWN` residence，不能按模板名或手工标签猜测。日志/Room/SavedStateHandle 不含 secret。若新增持久字段，必须更新 Room schema/migration fixture，不能只改文档表。

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

首次启动隐私说明、Provider 创建/编辑/连接测试、会话、模型选择、流式输出、停止、错误、重试、能力标签和当前 mode。consumer 固定 Standard；developer 首次启动仍为 Standard，并提供不产生权限/网络副作用的 Advanced 风险说明与显式切换。直接分发 UI 只显示产品名 Helix，不显示 developer flavor 或要求选择 APK。显示 Provider 规范 origin/residence；发送高敏 Context 前展示数据类别 + scope，Standard 不提供永久允许。UI 订阅持久化状态，不持有网络 Job；未完成连接测试不贬为“已可用”。本任务只交付 M2 可见性，不提前实现 HXA-033 的通用 Policy 或伪造“已门控”状态。

## 7. M3：Tool、Policy、Approval 与 Capability

### HXA-030 ToolDescriptor、Registry 和 ToolSource

实现 BuiltIn/MCP 工具来源、稳定 name/version/schema hash、`ToolOperationClass`、风险、限额、统一 `Capability`、execution target。Plan 只看 `READ_ONLY`；MCP annotation 不能降类。重复注册失败。

### HXA-031 JSON Schema 子集

实现项目需要的 object/array/string/number/integer/boolean/enum/required/additionalProperties/min/max/pattern；unknown keyword 拒绝注册。

### HXA-032 Capability Center

实现系统真实状态 resolver 和 Workspace/SAF/All-files/BrowserTab/Automation/Root scope 类型。缓存不代替执行时检查。

### HXA-033 Policy Engine

风险结合 mode、Safety Profile、scope、数据敏感度、规范网络 origin/residence、tool source、execution target 和参数。实现当时 accepted ADR-0005、现由 [ADR-0012](../adr/0012-capability-first-advanced-grants.md)保留的出网边界：Standard 高敏出网逐次确认；Advanced 规则精确绑定 Provider/MCP ID + origin + 数据类别 + scope，可撤销，期限只允许 1h/24h/7d/30d（默认 24h、最大 30d、不得滑动续期）；`createdAt/expiresAt` 到期或时钟回拨 fail closed。Secret/凭据和未知工具/Capability/L3 默认拒绝。模型、MCP 或 Skill 不能切换 Profile、创建 LAN scope 或降低 residence。测试默认/上限、重启、撤销、到期、时钟回拨和绑定字段变化。

### HXA-034 Approval hash 与一次性消费

canonical JSON + tool/version/schema/scope/session/target hash；一次性、过期、拒绝和并发 consume。复用存储层封闭 `ApprovalDecision(../APPROVED, DENIED)` 和 DAO 的 `decision = 'APPROVED'` 原子守卫，禁止重新开放自由字符串。明确区分“决定记录已处理”和“批准凭证被消费”：只有类型化 `APPROVED` 能生成/消费 Approval Proof，`DENIED` 即使已记录/已查看也不能授权执行，禁止用 `decision != null` 或 `consumedAt != null` 单独判断批准。页面/UI token 也绑定 approval；测试 pending/denied/过期不可消费、任意字符串无法进入仓库 API、并发只有一个批准消费成功。现有守卫只是 HXA-014 后加固，不等于本任务的 hash、expiry、Proof 与 Dispatcher 已实现。

### HXA-035 Dispatcher 与审计

validate → capability → policy → approval → timeout/cancel → execute → bound result → verify → audit。先用 `time.now` 和 fake mutating tool 验证；用户拒绝后的完全相同高风险动作不得在同一 Turn 重复请求，参数/作用域实质变化才可生成新审批。

### HXA-036 Timeline 和审批卡

显示来源、目标、scope、参数、风险、Safety Profile、Provider/MCP ID、网络 origin/residence、数据类别、规则有效期、代码/命令、预期影响和 verifier。参数、origin、数据类别、scope、profile 或 target 变化重建审批。通用 L2/L3 只提供“本次批准/拒绝”，不得提供“模型帮我批准”“此后全部允许”或把 Advanced 显示为完全访问；高敏出网规则单独标为有界 Policy 规则。测试切换 Profile 不改变待审批决定，拒绝后同动作不重复弹卡。同任务交付审计日志页，可按会话、工具、风险和日期过滤，只展示脱敏记录/有界摘要。

### HXA-037 确定性 Tool Scheduler 与交互 receipt

落实 [11 手机端编排方案](../architecture/mobile-tool-orchestration.md)：从规范化参数生成平台所有的 `EffectFootprint`，仅并行无冲突 `READ_ONLY`；未知效应、写/删、代码、Root、Accessibility、同 tab/Runtime lane 默认排他。默认总并发 2，真机证据前不超过 4，QuickJS/PRoot/UI 等保持各自单并发；低内存/后台/热限制只降并发。执行完成可乱序，但模型回填固定按 call sequence；queue/approval/execution/verification timing、decision source 和 attemptId 持久审计。取消为未启动项写 `CANCELLED_BEFORE_START`，已启动项 cancel 后保存 terminal/unknown outcome。结构化用户问题使用一次性 receipt，迟到/重复/已取消答复返回 `NOT_PENDING`，且不能代替 Approval Proof。只允许确认零副作用、相同 envelope、同/更强隔离的有界技术重试；target/scope/origin/权限变化新建 ToolCall/approval，禁止自动权限/网络/sandbox escalation 和主进程 fallback。测试确定性顺序、屏障、公平性、预算、取消、进程恢复和 `model-visible ⇔ persisted`。

### HXA-038 模型流状态合同与 ChatService 第一阶段拆分

把 Provider-neutral `ModelEvent` 的文本累积、usage null 语义、工具参数总量上限、拒绝/错误/取消优先级和截断工具流失败关闭抽成纯 JVM 状态对象；`ChatService` 只执行 Room/UI 副作用。用 characterization tests 锁定现有行为，不改变 Dispatcher、Scheduler、审批或回填顺序。这是渐进拆分，不宣称已经解决 Turn 状态的双重语义。

### HXA-039 批量语义 Turn Coordinator

在首个非 `time.now` 业务工具注册到生产聊天工具表前，消除 M1 串行 `TurnReducer` 与 HXA-037 批量并发 Tool Round 的语义冲突。先按 ADR 约定决定“演进现有 reducer”还是“以兼容恢复合同的新 reducer 取代”，明确一批多个 pending/running/unknown outcome、逐调用审批、固定顺序持久化、取消和进程死亡；未获接受不得把旧 reducer 直接接入生产。随后以唯一 application-level coordinator 驱动 Turn/ModelCall/ToolCall 状态与 effect，保留 `ToolDispatcher.dispatch` 单入口，并用聊天、工具乱序结算、拒绝、取消、失败和恢复 fixture 证明与已验收行为等价。

## 8. M4：Workspace 与文件管理器

### HXA-040 WorkspacePath 和 FileScopePath

拒绝 absolute、`..`、NUL、separator 变体、越界 symlink；不同 scope adapter 不泄漏真实路径给模型。

### HXA-041 Artifact、配额和原子文件操作

实现目录布局、hash、临时写+fsync+replace、前置 hash、配额和 bounded MIME/encoding detection。

### HXA-042 Pi 风格基础工具

实现 `read`、`write`、`edit` 和 `files.list/search/stat/mkdir`。`read` 必须有 `offset/maxBytes`、编码边界和稳定 EOF 语义，覆盖 10 MiB 文件分块处理。短名称与 namespaced implementation 共用同一 Policy。

本任务是首个非 `time.now` 业务工具进入生产工具表的门槛，注册前必须关闭 descriptor 变更的审批失效缺口：要么以机械门禁/合同测试强制 `timeout/maxOutputBytes/requiredCapabilities/operationClass/baseRisk/idempotency/origin` 等未直接绑定的安全契约字段变化必提升 `toolVersion`，要么先以 proposed ADR 决定并实现覆盖完整安全 descriptor 的 contract hash。仅修改这些字段但保持 `(../name, version, schemaHash)` 不变必须测试为拒绝；不得只靠 KDoc 约定或声称 timeout 已直接进入现有九字段 `ApprovalBinding`。`executionTarget` 已是现有 binding 的直接字段，仍按 HXA-034/035 精确绑定。

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

### HXA-048 全项目审查后续收敛

M4 收尾的审查后续任务（非新功能、不扩大权限、不升级依赖）：收敛 2026-09 全项目审查发现、但刻意未在缺陷修复提交中改动的项，逐项补回归测试。

- ChatService turn 模型：当前同时持有全局 `activeTurnJob`/`turnGate` 与按 turn 的 `turnCancels`，而 `turnGate` 的 KDoc 声称每会话只有一个 active turn。核实并发真相并统一为明确的每会话模型；补上目前缺失的 ChatService 单元测试骨架（当前只有仪器测试覆盖 turn 行为）。
- `WorkspaceQuota.usageBytes` 每次文件操作都全量 walk scope 目录来报告当前用量；仅当目录规模成为实际瓶颈时改有界/增量统计，否则记录并维持现状。
- 删除生产无引用的 `WorkspacePath`（连同 `WorkspacePathTest`、`FileScopePathTest` 中镜像它的 oracle 与 `PathSyntax` KDoc 引用），保留 `FileScopePath` 为唯一模型可见路径类型。
- `files.listDir` 先物化并排序整个目录再分页，app 层 `list` 的 TIME/SIZE 排序只作用于按名截断的前缀；仅当大目录浏览成为实际场景时修正，否则记录并维持现状。

明确不改动（审查判定为有意设计，不进入本任务范围，避免后续实现者误删）：`RecoveryCoordinator.canResumeTurn`/`wakeAllowed` 是有测试覆盖、为未实现的恢复/继续 UI 预留的门禁 seam；`tools/android`、`tools/browser` 空子项目是 M6 的占位模块。

### HXA-049 会话附件导入、持久化与文本输入

按 [ADR-0014（accepted）](../adr/0014-session-attachment-materialization.md)完成附件基础闭环；ADR 接受只确定架构和范围，不是实现证据。聊天页增加系统文件选择器/Photo Picker、待发送附件卡片、移除、导入进度和失败恢复；复用 HXA-044 的 `SafImportPipeline` 把一次性 URI 复制到当前会话 app-private Workspace，单文件导入**不要求** persisted SAF tree grant。增加 Artifact 与消息附件关系、schema migration、hash 快照、重启恢复、取消和孤儿清理；每条用户消息最多四个附件，单文件仍受既有 10 MiB 上限约束。

首批只把经 MIME、扩展名和有界字节 probe 一致确认的 UTF-8 txt/md/csv/json 作为文本附件，以带来源、`UNTRUSTED` 标记和哈希的有界 context item 进入模型请求；超限内容保留 Artifact 引用并由 `read(offset,maxBytes)` 分块。UTF-16、PDF、PPT/PPTX、DOC/DOCX、音频、视频及其他未支持类型统一返回 `UNSUPPORTED_ATTACHMENT_TYPE` + 封闭 category，不把二进制 base64 解释为文档、音频或视频理解，也不实现解析/渲染/OCR、媒体解码/抽帧/音轨/转码、Provider upload 或模型请求物化。原始 `content://` URI 只在 import adapter 内短暂使用，不进入消息、Context、审计或诊断。发送前扩展 egress disclosure/binding，绑定精确 Provider/origin/message/Artifact hashes；用户只选择附件或系统分享进入会话时不得自动发送。验收覆盖恶意 ContentProvider、MIME/扩展名/字节不一致、UTF-16、截断/超大流、同名冲突、进程死亡、hash 变化、无 Provider、unsupported 分类与发送取消。

## 9. M5：QuickJS

### HXA-050 Zipline Spike

Android 29/34/36、arm64/x86_64 验证 evaluate、memoryLimit、InterruptHandler、`Function`/constructor 禁用、大于 6 MiB 的调用线程 stack、16 KiB page 及 `bindIsolatedService` 唯一实例回收。按 [ADR 约定](../adr/README.md) 产出决定与证据；关键能力失败则停下重新选型，不能提前写成已实现。

### HXA-051 isolated Service/Binder

`isolatedProcess=true`、`exported=false`；API 29+ 每 execution 使用唯一 `bindIsolatedService` instance，instance name 固定为 `js_` + 32 位小写 hex，覆盖 Android 非法字符、长度和碰撞测试；Parcelable/PFD、interrupt、unbind/PID 回收、Binder death、崩溃、取消。不使用 `killProcess`/`System.exit` 当作正常控制面。

### HXA-052 JS ABI 与限制

JSON 输入输出、host 编码参数 + 局部 `const` input 的严格 IIFE wrapper、每任务新实例、64 MiB、10 s、bounded source/input/output；无 file/network/Android bridge。测试生成代码覆盖 input global、`eval`、`Function`、constructor 变体和包裹逃逸。

### HXA-053 `code.javascript.run`

完整代码审批、input summary、hash 和执行结果回填。

### HXA-054 攻击和端到端测试

无限循环、内存、输出洪泛、eval/fetch/require、进程崩溃、Binder 大输入、取消竞态和 verified artifact。

## 9A. M5A：多模态附件闭环

该增量在 HXA-049 的可恢复附件基础和 M5 的代码处理闭环之后执行。任务编号使用尚未分配的 HXA-055～056；执行顺序以本文档顺序为准，不改变既有 HXA-060 及后续编号。PDF、PPT/PPTX、DOC/DOCX、音频和视频读取没有分配实现任务，只有统一稳定 unsupported 边界。

### HXA-055 图片输入与 Provider 视觉能力

不要重写已存在的三协议 image encoder；实现 production `ArtifactImageResolver`，只解析与当前会话消息绑定、hash 复核通过的 app-private Artifact，并接通 `ModelRequest.ImageReference`。端上执行 bounds-only probe、MIME/签名一致性、解码像素与尺寸限制、方向修正、元数据剥离、归一化和总请求字节预算；任何解码失败、Artifact 变化或超限均失败关闭，不回退为裸 base64 文本。

为 Provider 增加可审计的 vision probe/精确配置与 Turn capability snapshot；不支持或未知时允许本地保存/预览，但发送必须给出可操作错误，禁止静默丢图或猜测换协议。扩展历史重建、重试、取消和三协议集成测试；出网摘要必须显示图片数量、类型、归一化后大小和绑定 hashes。具体像素、边长与请求字节上限在 API 29/36 和低内存真机证据后固化为集中配置，并取 Helix 与 Provider 限制中更严者。

### HXA-056 文本/图片附件端到端硬化与发布边界

用固定 fixture 完成纯文本与图片附件消息的发送、流式回复、Tool Loop、历史恢复和诊断脱敏回归；接入 `ACTION_SEND`/`ACTION_SEND_MULTIPLE` 的文字/图片草稿，所有分享输入先本地导入/预览且绝不自动发送。覆盖 picker/share grant 立即失效、Artifact 缺失/篡改、重复发送、Provider/origin 切换、能力快照变化、请求过大、图片解压炸弹、取消与进程回收。UTF-16、PDF、PPT/PPTX、DOC/DOCX、音频、视频和未知二进制只验证统一稳定分类与拒绝，断言没有文档解析器、页面渲染、OCR、媒体解码/抽帧/音轨/转码、Provider file upload、模型 context/base64 或派生 Artifact。验证日志、Room、audit 和用户可见错误不泄露原始 URI、绝对路径、正文或 base64；三套 adapter 都通过 golden request，至少一个真实 vision endpoint 只作为明确记录环境的 smoke，不能替代另外两套协议 fixture。更新用户说明，区分文本上下文、图片 vision、未支持附件与长期 SAF tree scope，不把本地导入成功写成模型已理解。

## 9B. M5B：文件工作台剩余闭环

本增量承接 HXA-044 已落位、HXA-046 明确推迟但此前没有后续任务认领的 SAF tree scope 与文件管理导入/导出。它不改变附件媒体范围，也不把系统 URI、Android 权限或 Advanced 状态解释为 Tool Approval。

### HXA-057 persisted SAF tree scope 接线

把现有 `SafGrantStore`/persistable URI grant 接入统一 `FileScope` resolver 和文件管理来源列表：用户通过 `ACTION_OPEN_DOCUMENT_TREE` 明确选择 root，平台 adapter 保存 grant，模型和 Tool 只看到稳定 `scopeId` 与相对路径。每次使用实时复核 grant、provider identity、root document 与读写 mode；撤销、provider 消失、重启、只读 grant 和 URI 变化均 fail closed，并提供可见的重新授权/移除入口。所有 `read`/`write`/`edit`/`files.*` 仍走相同 Tool Registry、Policy、Approval、quota/output limit 和审计，禁止 UI/DAO 直接操作外部文件。测试恶意 ContentProvider、路径/文档 ID 欺骗、撤销竞态、跨 scope、进程死亡和 grant 泄漏；原始 URI 不进入模型或诊断。

### HXA-058 文件管理器导入/导出入口

在文件管理器接通 HXA-044 的受限 import/export pipeline：导入使用 `ACTION_OPEN_DOCUMENT`/`ACTION_OPEN_DOCUMENT_TREE` 的用户选择复制到 Workspace，导出使用 `ACTION_CREATE_DOCUMENT` 或用户已授权 tree，把 Workspace 快照流式写出。UI 必须展示来源、目标、名称、大小、冲突策略、进度、取消和最终结果；导入/导出是明确的文件管理动作，不自动创建聊天消息、不自动发给 Provider，也不扩大 Agent scope。覆盖同名冲突、部分流、大小谎报、磁盘满、目标撤销、取消、进程回收、原子性和临时文件回收；导出后重新读取/校验可得证据时才显示 verified，否则只报告平台确认的实际结果。

## 10. M6：浏览器、Android 基础工具与国际化

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

GET/HEAD、SSRF/redirect/size/timeout；URL Policy 检查全部 A/AAAA/IPv4-mapped IPv6，只把本次已验证地址集合交给 transport，保持原 hostname 的 TLS Host/SNI/证书验证并复验 peer，每跳重做 origin/DNS/IP/credential/scope 检查。测试 DNS rebinding、连接复用、编码地址、metadata 和 redirect 逃逸。Standard 仅公网；Advanced 只能使用用户预建的精确 LAN/loopback `NetworkOriginScope`，模型 URL 不能创建 scope。前台服务只覆盖用户主动发起的 Provider/MCP 传输或本地文件处理，声明 `dataSync` + `FOREGROUND_SERVICE_DATA_SYNC`，等待审批/输入时停止，实现 Android 15+ `onTimeout()` 和 6 小时/24 小时限额测试。通知提供停止；WorkManager 只做可延期维护/提醒。

### HXA-067 语音输入

使用系统语音识别 Activity/Service 能力将用户主动录音转为可编辑草稿，不后台常驻监听、不自动发送给模型；覆盖 unavailable/denied/cancel/error 和前后台转换。本任务先按系统 locale 启动识别，用户仍可在系统识别 UI 中修改；后续由 HXA-069 统一资源化，并把识别默认语言接到当前 App locale。

### HXA-068 Advanced 有界出网规则管理

为 [ADR-0012](../adr/0012-capability-first-advanced-grants.md)保留的高敏出网规则提供类型化持久化、列表、创建、到期和显式撤销 UI；仅 developer/Advanced 可创建，consumer/Standard 永远不提供入口。规则严格绑定 Provider/MCP ID、规范 origin、数据类别、scope 与固定期限（1h/24h/7d/30d），不能包含通配符、滑动续期、Tool Approval Proof 或“全部允许”。接入 Dispatcher 的 `ruleProvider`，覆盖进程重启、到期、时钟回拨、撤销、切回 Standard、Provider/MCP/schema/origin/scope 变化和并发读写；现有逐次审批在 store/UI 不可用时保持 fail closed。HXA-072 复用本任务的 store/UI，不另建 MCP 专用规则体系。

### HXA-069 国际化与 App 语言切换

将 `app` 及已接入的 `feature` UI 中所有用户可见文案、无障碍描述、通知和可操作错误迁移到 Android string/plurals 资源，提供完整 fallback、`values-en` 和 `values-zh-rCN`，不通过拼接可翻译句子组装 UI。仅资源化真正的用户文案；Tool/schema 名、Provider 模型 ID、URL、协议字段、审计类型、稳定错误码与 `Locale.ROOT` 规范化保持语言无关。

在设置中增加“跟随系统 / 简体中文 / English”，使用 Android 官方 per-app language 机制或经 API 29/36 验证的 AndroidX 兼容层；选择在 Activity 重建、进程死亡、App 更新及 Standard/Advanced 切换后保持，“跟随系统”不留存旧的 App locale override。在 API 33+ 与系统“App languages”保持双向一致，API 29–32 使用等价持久化；consumer/developer 共用同一套文案和选择语义。

新增可在 CI 执行的用户可见硬编码字符串扫描和缺失/多余翻译键检查，为现有 Compose UI 和后续 HXA 建立回归门禁。单元测试覆盖 locale 选择/清除与格式化；API 29/36 仪器测试覆盖系统默认、中英文即时切换、Activity/进程重建、通知与关键界面文案，并验证切换 locale 不改变 Provider 请求、Tool schema、持久化 enum/错误码或审计字段。本任务不翻译用户、模型、网页、MCP/A2A 或 Skill 提供的内容。

## 11. M7：MCP、A2A 与 Skills

### HXA-070 MCP Kotlin SDK Android Spike

固定 client artifact 和 Ktor OkHttp engine；API 29/36、R8、Streamable HTTP、取消、重连、大消息和后台。SDK 类型不得泄漏 core。

### HXA-071 MCP Server 配置和握手

disabled-by-default config、SecretStore bearer auth、initialize、protocol/capability snapshot、规范 origin/residence、tools/resources/prompts 有界 metadata 列表。首版 resources/prompts 正文不进 Context，OAuth/PKCE 不实现；bearer 已配置不代表允许发送高敏数据。

### HXA-072 MCP 动态 Tool bridge

注册 `mcp.<server>.<tool>`；annotation 不能降风险；schema hash 变化撤销审批；结果块有界。网络调用生成有界 EgressSummary 和会话发送摘要，endpoint/schema/敏感数据类别变化时强制检查点；Advanced 规则精确绑定 server/origin/类别/scope/有效期。sampling/elicitation/roots 关闭。

### HXA-073 MCP stdio bridge

PRoot Runtime 启动锁定 server command，严格 stdout JSON-RPC、bounded stderr、环境 allowlist 和进程取消。此任务依赖 HXA-084；可在 M8 后完成。

### HXA-074 Skill validator 和 catalog

按 Agent Skills 规范解析 frontmatter；catalog 只含 metadata/hash/source。使用官方 fixture，自行实现 Kotlin loader，不把 `skills-ref` 用作 production runtime。

### HXA-075 Skill 导入和快照

本地目录/zip 导入、防 traversal/symlink/zip bomb，展示 scripts/resources/hash；运行固定 snapshot，更新不静默替换。

### HXA-076 Skill 工具与首批内置 Skills

实现 list/read/read_resource/enable/disable/remove；脚本只通过已有执行工具。加入文件整理预览、网页研究、数据转换、repo inspection、notification digest 五个小 Skill 和端到端测试；`notification-digest` 依赖已验收的 HXA-065。`android-ui-task` 不在 M7 提前实现，延后到 HXA-097。

### HXA-077 A2A v1.0 Android Client Spike

在 accepted [ADR-0016](../adr/0016-a2a-client-interoperability.md) 的产品、协议和信任边界内，验证官方 Java SDK client/Android HTTP adapter 在 API 29/36、R8、JSON-RPC/HTTP+JSON、SSE、取消、重连、大消息、Java record/serialization 和 APK/方法数上的可用性。若官方 SDK 不合格，验证稳定 `A2aClientFacade` 后用 OkHttp + kotlinx.serialization 实现最小 v1.0 Client 的成本；不实现业务 UI/Tool bridge。Spike 必须产出 SDK/transport/版本兼容与依赖许可证决定，更新验收矩阵为实际 Gradle task。在选出有证据的可行方案前，不得启动 HXA-078/079、新增 production A2A 依赖或声称兼容；候选均失败时必须按 ADR 约定重新评估。

### HXA-078 A2A Agent 配置、发现与快照

创建 `extensions:a2a`（仅在 HXA-077 接受方案后），实现 disabled-by-default endpoint、SecretStore auth alias、公开/扩展 Agent Card 获取、`supportedInterfaces`/版本协商、provider/capability/input-output mode/Skill 的有界展示与 content hash 快照。用户逐项启用远端 Skill；Agent Card、endpoint、binding、protocol version 或 Skill hash 变化后旧 Registry 条目、审批和长期规则失效。首版不实现 A2A Server、push notification webhook、OAuth/mTLS、gRPC、custom binding 或 v0.3 静默降级。

### HXA-079 A2A Task Tool bridge 与恢复

在 `tools:framework` 增加可信的 A2A `ToolSource`/origin 类型（不能冒充 MCP source），把已启用的远端 Skill 注册为 `a2a.<agent>.<skill>`，使用固定任务 schema，并经 Dispatcher 的 NETWORK/egress/approval/audit 管线执行 SendMessage/SendStreamingMessage、GetTask、CancelTask 与 SubscribeToTask。本地持久化 toolCallId ↔ taskId/contextId/snapshot/input hash/事件序号；断线、取消、进程死亡只对账原 Task，送达不明确时进入 `NEEDS_REVIEW`，不新建 Task 重发。首版支持有界 text、structured data 与 hash 复核后的 Workspace Artifact 副本；远端内容/Artifact 标记 `UNTRUSTED_A2A_CONTENT`，不能反向调用本机 Tool、继承 Capability/Approval/Secret 或直接满足本机 verifier。

M7 的 Client-only 是依赖顺序，不是永久能力上限。后续若由项目所有者排期，优先单独验证 Advanced 前台局域网 Server，再验证用户自备 VPN/隧道/relay 的远程可达性；远端到本机先只生成结构化 Tool proposal。直接远端 Tool scope、手机 webhook、平台 relay 或递归/peer 编排分别需要新的 HXA/ADR，不并入 HXA-077～079，也不因 Client Spike 通过自动视为获批。

## 12. M8：PRoot Linux Runtime

### HXA-080 Runtime manifest/许可证 schema

component、version、ABI、URL、size、sha256、license、source、patches；唯一版本真相。

### HXA-081 构建期固定资产

固定 PRoot、Alpine、bash/git/python/node/ripgrep；验证 hash/ABI/license/ELF `LOAD` alignment，记录 APK 压缩、安装后和临时峰值体积，不在设备下载 executable。这里的 Git 交付仅是离线 Runtime 组件与版本/smoke 基线，不包含持久 `.git` 仓库、结构化 Git UI 或 remote transport。

### HXA-082 RootFS installer

`.partial`、防 Zip Slip、smoke、原子激活、保留一版 rollback。

### HXA-083 独立 Runtime APK/IPC

独立 applicationId/UID、signature permission、caller signature、protocol/version/ABI handshake、PFD manifest；落实 ADR-0007：explicit ComponentName + `BIND_AUTO_CREATE` 冷绑定，不要求用户打开/常驻 Runtime。应用启动、切换 Advanced 和被动 Registry 刷新不启动进程；只允许用户点击的零 Job 验证/修复，或批准并固定输入后的真实 Job 建立绑定。提供仅由用户点击进入的最小设置/修复 Activity。覆盖未安装、禁用、强制停止、签名/协议/ABI 不符、空闲解绑重启、Binder death/`DeadObjectException` 和稳定不可用状态；任何失败都不得回退到主 App shell。

### HXA-084 PRoot runner

Job copy、argv/显式 shell script、cwd/env allowlist、timeout/process-group cancel、bounded stdout/stderr、output archive/hash。任务自定义 env 对名称 secret pattern、已知 SecretStore 值和认证来源做拒绝测试，Runtime 不获得 SecretStore 能力。以 `executionId/jobId` 原子维护有界 journal/terminal commit；重复 jobId 不重复启动。journal 首版上限 128 条/1 MiB metadata：active 与未对账 terminal 不因额度静默删除，额度满则拒绝新 Job；已对账 payload 立即删除、tombstone 最多留 7 天；未对账 terminal 最多保留 30 天，之后只留 evidence-expired marker 并使主 App 停泊 `INTERRUPTED`。Binder 断连后只查询/对账，匹配 input hash 的 terminal proof 才可恢复结果，否则停泊 `INTERRUPTED`。实现前后台状态机：任意计算不得冒充 `dataSync`；没有合法 FGS 类型时退后台暂停/取消。若真机证明确需 wake lock，只在用户可见 FGS 的 RUNNING 窗口限时持有并覆盖全路径释放。

### HXA-085 `bash` Tool

每次审批；仅 Advanced 且 Runtime 已单独安装、启用并由用户完成过签名/协议/ABI 零 Job 验证时进入工具表，进程预先存活或用户手动打开不是条件，被动 Registry 刷新不得重新绑定。每次执行在批准后重新握手。禁止 Android 主进程 shell、真实 Workspace mount、网络包安装和 secret inheritance；Advanced 或 LAN scope 均不能给 PRoot 增加 INTERNET。UI 区分未安装、未验证、需更新、被禁用/强制停止、连接失败和执行中断；“修复 Runtime”只在用户点击后打开 companion 恢复入口，恢复后重试创建新的 ToolCall/approval/jobId。

### HXA-086 真机 smoke 与隔离

arm64 分别在 4 KiB/16 KiB 页真机执行 python/node/git/ripgrep；Runtime 不能读主 App dataDir、共享存储或联网。增加从未打开的 Runtime 冷绑定、空闲回收再启动、后台/锁屏/Doze/低内存、强制停止、主 App/Runtime 分阶段 kill、同 jobId 不重复执行、通知停止和可选 wake lock 泄漏测试。任一 ELF、生命周期或 smoke 不兼容时阻断对该设备分发并记录最低设备集。

### HXA-087 更新/卸载/法律页

同签名 APK 更新、rollback、完整删除、离线 notice/source URL/build manifest。

### HXA-088 Git Workspace 语义 Spike 与 ADR

在 PRoot Job/snapshot 真机链路完成后，比较三条路径：主 App Workspace 持有权威仓库并原子交换完整仓库状态、Runtime 私有目录持有权威仓库并提供受限协议、主 App 引入 Android Git 库。测量含 `.git` 的归档体积/耗时、部分传输、进程死亡、并发修改、symlink、对象膨胀与损坏恢复；定义 hooks/alias/filter/external diff/submodule/worktree/config/credential helper 的拒绝或禁用策略。Standard 首版只需要 Helix 原生历史/diff/回收站；Advanced 候选只限结构化离线 `status/diff/log/init/add/commit`，`reset --hard`/`clean` 等破坏操作默认不进入首版。产出并由所有者决定 [ADR-0008](../adr/0008-git-workspace-management.md)；在 accepted 前不得把 Job-local Git 描述成持久仓库管理。`clone/fetch/pull/push` 与凭据不在本任务范围，未来需新的联网执行域和 ADR。

## 13. M9：Accessibility 与 Root

### HXA-090 Accessibility Service 与权限中心

用户主动系统设置开启；service state、包 allowlist、限时 AutomationSession、前台停止。先用自建测试 App，不接 Agent。

### HXA-091 UI snapshot/token

有界 AccessibilityNodeInfo tree，token 绑定 package/window/generation/fingerprint。处理 null root、secure/custom views 和节点回收。

### HXA-092 UI actions

find/click/long_click/set_text/scroll/back/home/wait。目标包变化暂停；无坐标 blind click；敏感包和语义拒绝。

### HXA-093 Accessibility 攻击/恢复测试

系统权限、安装器、Root 管理、支付/认证界面拒绝；锁屏、服务断开、强停、snapshot 过期和连续动作预算。检查点覆盖动作数、时间、package/window 和敏感语义变化；连续快速批准不形成自动允许。Advanced 可在硬上限内调整预算，但不能关闭拒绝清单。

### HXA-094 libsu Spike 与依赖审计

使用 libsu 6.0.0 core/service；计划路径为 JitPack exclusive content + dependency verification，M9 前不加仓库。Spike 核对固定 tag/artifact/checksum/notice 并产出依赖 ADR；若项目所有者拒绝 JitPack，先获授权再改为审查过的源码镜像，不能临场换库。Root 只在 Advanced 中由用户主动请求；测试 Profile 切换不触发 `su`，以及不可用、拒绝、授权、丢失和 RootService crash。

### HXA-095 Root 高层只读工具

status、file.read、package.info、process.list、bounded log.read；短时 RootSession，Provider secret 不进入 RootService。

### HXA-096 Root L3 控制台

`root.exec` 仅开发者 UI、默认不进入模型工具表。禁止 SELinux/boot/system/凭据/静默安装与授权。若无明确产品需要可保持未实现。

### HXA-097 Android UI Skill

依赖 HXA-091/092/093 已验收的 snapshot/token、UI actions 和敏感界面拒绝契约，再实现 `android-ui-task` 内置 Skill：先 snapshot、只用节点 token 动作、每步复验、目标包变化立即暂停。Skill 不新增权限或执行器，并用自建 fixture App 覆盖成功、过期 token、敏感界面和中途停止。

## 14. M10：单机硬化

### HXA-099 模式、预算与资源降级运行控制

在固定评测前补齐现有无主运行控制：提供 Chat/Plan/Act/Goal 的可解释入口与有界 `TurnBudgets` 配置，不能让 UI 降低 ModePolicy、Policy、Approval 或 Goal budget accounting；把低内存、后台和热状态的 Android 真实信号接入 HXA-037 `resourceGate`，只允许把总并发降到 1/2，绝不提高构造期硬上限 4、改变 call sequence 或取消已获批准调用。覆盖配置重启、非法/极值预算、Profile/Mode 切换、低内存/后台/热状态恢复、并发只降不升，以及 Plan/Goal 工具入口仍经过同一 Dispatcher。资源长稳矩阵继续由 HXA-103 验收。

### HXA-100 固定评测集

至少 40 条：聊天/Plan/Goal、三 Provider 协议、文件、JS、浏览器、MCP、A2A、Skill、Accessibility、Root 拒绝路径。记录 provider/model/protocol/prompt hash/tool versions/device/date。

### HXA-101 Prompt injection

网页、文件、MCP description/result、A2A Agent Card/Skill/message/Artifact、Skill、通知和文件名中的注入；验证不能扩大 scope、注册工具或绕过审批。

### HXA-102 恢复和副作用

模型流、审批、文件写、浏览器动作、UI 动作、MCP call、A2A Task、PRoot 运行各阶段 kill/restart；不明确结果进入 `NEEDS_REVIEW`。按 ADR-0004 收口 Goal 恢复：PAUSED 原因以稳定 outcome + audit 原子持久化；模型/工具/副作用边界写入有界间隔的 durable usage checkpoint，确定并测试最大未记账窗口；反复 kill/restart、长时间离线和墙钟回拨均不得持续增加可用预算，也不得自动重放 open run 或不明确副作用。

### HXA-103 资源/稳定性

API 29/34/35/36、低内存、断网、Doze、锁屏、旋转、24 小时；WebView/Room/coroutine/Binder/process/file descriptor 无泄漏。PRoot/CLI 覆盖冷绑定、空闲回收、强制停止、前台服务类型/timeout/停止通知和可选 wake lock 全路径释放，不把 FGS 当作不会被杀的保证。

### HXA-104 隐私/诊断/删除

日志脱敏、用户预览诊断包、删除会话/Provider/MCP/A2A/Skill/Goal/Workspace/Runtime/站点数据和 Root session。API 30+ 用 `ApplicationExitInfo`，所有版本保存脱敏最后状态/heartbeat，uncaught handler 写有界摘要后继续交给系统；24 小时证据同时保留 instrumentation、logcat/ANR 和退出原因，不声称 App watchdog 能完整捕获 ANR。

### HXA-105 有界只读委托与声明式 Workflow Spike

根据 [11 手机端编排方案](../architecture/mobile-tool-orchestration.md)产出并由所有者决定 proposed [ADR-0009](../adr/0009-bounded-local-orchestration.md)。只评估 developer/Advanced：child 深度 1、并发 2、每父 Turn 总数 4，模型/token/Tool/墙钟全部计入父预算；child 只得到最小 snapshot，只注册 `READ_ONLY` 且动态风险 ≤ L1 的工具，不继承 pending approval、Secret、UI token、Root/Automation session 或可写 scope。通信只允许 parent→child task/cancel 与 child→parent structured result，Agent graph/状态/预算/completion 持久化；写入需求只返回 proposal，由父 Turn 新建 ToolCall 并审批。并行收益、费用、温升、内存、取消、恢复和 prompt-injection 证据不达标则保持单 Agent。可选 Workflow 只做版本化 JSON DAG（封闭 node type、静态上限、无循环或有硬界），节点全部编译回 Dispatcher；不执行 JS/Starlark 编排、不自挂插件、不增加云端任务/Remote Worker。ADR 未 accepted 前不进入产品工具表。

## 15. M11：官方 CLI 订阅后端实验

### HXA-110 CLI Runtime manifest 与独立 UID

有 INTERNET，无 All-files/Accessibility/Root/主 App数据；固定 Node/official CLI/hash/license/terms URL。跨 App Service 按 ADR-0007 使用 signature permission、显式冷绑定、状态查询和空闲解绑；正常会话不要求 companion Activity/进程常驻，只有登录/重认证/退出使用可见 UI。

### HXA-111 Codex app-server 登录 Spike

先验证官方 Codex CLI/app-server 是否存在可在 Android/Linux arm64 运行的受支持发布形态，并按 [ADR 约定](../adr/README.md) 就原生执行与独立 PRoot/RootFS 记录决定、替代方案和证据；失败则停止打包路线。成立后使用官方 ChatGPT OAuth/device code；Codex 持有 token，主 App只看登录事件/plan type。验证 logout/uninstall 删除和限额错误。

### HXA-112 Claude Code stream-json/SDK Spike

先验证官方 Claude Code/Node bundle 在 Android/Linux arm64 的受支持运行形态并纳入同一 CLI 底座 ADR；成立后使用官方 Claude 登录和公开 non-interactive interface，验证凭据隔离、取消、输出限制。

### HXA-113 工具与审批兼容性结论

证明能禁用或代理 CLI 内置工具且不会绕过 Helix Policy，才实现 Agent backend adapter；同时证明断连后可按 jobId 查询/对账，未知状态不会重放 CLI 命令。否则明确保留“隔离 CLI 会话”，不进入 Act/Goal Provider 列表。

## 16. M12：商店与官网多渠道发布

### HXA-120 变体和 APK 审计

按 [ADR-0013](../adr/0013-standard-store-capability-preserving-distribution.md)生成 Google Play、选定国内商店与官网的渠道矩阵：每个 artifact 列出 applicationId、manifest 权限、Capability、companion、SDK/依赖、listing、披露和降级。Standard 在所有渠道保持完整核心任务矩阵；能力差异必须指向当前政策原文或真实审核反馈，不能以 consumer/developer 名称或抽象安全偏好为理由。核对现有四个 applicationId、variant dependency graph/source set marker，并提出 HXA-122 的稳定主 ID 与 channel/flavor 命名方案。

### HXA-121 发布门禁

执行 [安全文档](../security/testing-and-release.md) 全部命令、Standard/Advanced 组合、每渠道核心任务矩阵、companion 标识、真机矩阵、SBOM、notice、权限/数据披露和已知限制。Google Play 单独验证 All-files 申报材料、Accessibility 仅确定性用户自动化、解释脚本不下载 DEX/JAR/`.so`；国内目标商店按提交当日官方规则分别验收。未提交或未通过不得声称已上架。

### HXA-122 签名和更新

离线签名材料流程、主/Runtime 同签名关系、可回滚升级、安装顺序和来源校验。决定跨商店/官网的稳定主 applicationId、flavor/channel 命名与升级路径：优先让同一产品身份覆盖完整 Standard，并在同 ID 内切换 Standard/Advanced；若渠道必须不同 ID，记录数据迁移与能力差异。不得只改文档、交换 ID 或声称不同 applicationId 可原地升级。当前不做 App 内静默自更新。

### HXA-123 渠道提交准备与审核证据

完成 Google Play 与首批国内 Android 应用商店的真实提交准备和渠道决定。优先通过核心用途声明、显著披露、用户同意和审核保留 All-files、Accessibility、解释脚本等能力；只有明确条款或真实拒审才做最小渠道裁剪，并记录对应模块与替代路径。真实审核通过前状态只能是“准备提交/已提交/审核中/被拒”，不能写“可上架”或“已上架”。

### 未排期未来能力候选（不属于 M0～M12）

- Tasker 官方插件互操作，以及 Auto.js/AutoJs6 任意来源脚本导入、兼容诊断与独立 Runtime；“任意来源可导入”不等于“任意脚本零差异执行”。
- Shizuku 与 Android 11+ 无线 ADB 本机配对/client；仍在当前实现范围之外。
- 候选进入路线前必须形成独立里程碑/HXA，完成许可证与供应链审计、Android/OEM 真机矩阵、生命周期/断连恢复、Policy/Approval 接入和发布渠道评估。
- [ADR-0012](../adr/0012-capability-first-advanced-grants.md)记录可行性与授权边界；该 accepted 决定不构成实现证据。

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
