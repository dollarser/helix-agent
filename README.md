# Helix Android 本机 Agent

Helix 是一个 Android 优先、手机本地执行的个人 Agent。模型可通过网络 API 或用户配置的自建模型服务调用，但 Agent Runtime、权限判断、工具调用、浏览器、文件工作区、代码执行、审批和审计均在手机上运行。

当前目标是 Android 直接分发版本。最终用户只下载一个 Helix 主应用（工程内部为 `developer` 变体）：默认运行 `STANDARD`，需要时在同一安装内显式进入 `ADVANCED`；PRoot/CLI 是按需 companion Runtime，不是第二个主应用。`consumer` 保留为未来商店/企业等严格受限渠道产物。远程 Worker、云端沙箱、桌面配对与 HarmonyOS 客户端暂不实现，只保留稳定的执行目标接口。

## 文档入口

| 文档 | 用途 |
| --- | --- |
| [产品需求文档](docs/01-product-requirements.md) | 定义用户、场景、功能、边界和验收指标 |
| [总体技术方案](docs/02-architecture-design.md) | 定义模块、状态机、接口、数据和安全架构 |
| [本地代码执行方案](docs/03-local-code-execution.md) | 定义 QuickJS 沙箱与 PRoot Linux 开发者模式 |
| [技术路线与开发计划](docs/04-roadmap-and-backlog.md) | 按依赖顺序拆分小模型可执行的开发任务 |
| [开发环境与依赖基线](docs/05-development-environment.md) | 固定工具链、版本、设备和构建命令 |
| [开源依赖与参考仓库](docs/06-open-source-references.md) | 区分直接依赖、设计参考和禁止复制项目 |
| [安全、测试与发布门禁](docs/07-security-testing-release.md) | 定义威胁模型、测试矩阵和发布条件 |
| [小模型实施指南](docs/08-small-model-implementation-guide.md) | 规定 Qwen 等较小编码模型的工作方式与最小护栏 |
| [小模型继续开发交接](docs/small-model-handoff.md) | 当前代码事实、M1 长程 Goal、checkpoint 和可直接使用的启动提示 |
| [Android 平台能力](docs/09-android-platform-capabilities.md) | 浏览器、文件管理、Accessibility、Root 与 Android 基础工具 |
| [Provider/MCP/Skills/模式](docs/10-provider-mcp-skills-modes.md) | 多协议 Provider、自建模型、MCP、Skills、Plan/Goal 与订阅边界 |
| [手机端 Tool 编排](docs/11-mobile-tool-orchestration.md) | 确定性并发、安全管线、回放、受限委托与明确不采纳项 |
| [架构决策记录约定](docs/adr/README.md) | 定义 ADR 触发条件、状态、模板、取代关系和小模型权限边界 |
| [验收命令矩阵](docs/verification-matrix.md) | 每个 HXA 的真实 Gradle task、设备要求和预期证据 |
| [当前实施状态](docs/implementation-status.md) | 区分已完成文档、下一任务与尚未实现代码 |
| [M0 完成记录](docs/m0-completion-record.md) | HXA-001～003 的实际命令、结果、设备和限制 |
| [HXA 完成记录](docs/completion-records/README.md) | 逐 HXA 真实命令、exit code 与设备证据（M0 合并记录 + M1 HXA-010～016） |
| [文档复核记录](docs/documentation-review.md) | Baseline 1.3 的一致性检查、修正项和已知缺口 |

当前进展：M0 与 M1（HXA-001～016）已完成，M2 等待启动指令；详见[当前实施状态](docs/implementation-status.md)。

## 当前固定决策

- 平台：Android，`minSdk 29`、`compileSdk 36`、`targetSdk 36`。
- UI：Kotlin + Jetpack Compose，不采用 Flutter/React Native。
- 架构：多模块、单向依赖、手工依赖注入，不引入 Hilt。
- 模型：OpenAI Responses、OpenAI Chat Completions、Anthropic Messages；内置常见厂商模板并支持 SGLang/Ollama 等自建服务。
- Agent：自研有限状态机和 Tool Loop，不引入 LangChain 类大型框架。
- 扩展：官方 MCP Kotlin SDK Client；Agent Skills 开放规范，按需加载。
- 模式：Chat、Plan、Act、Goal；Goal 持久化但不绕过审批。
- 本地轻量代码：Zipline/QuickJS，运行于无权限 `isolatedProcess`。
- 本地完整代码环境：PRoot + Alpine 独立 Runtime APK，与主 App 使用不同 UID；主 App 的 `developer` 变体负责连接。
- 浏览器：Android System WebView + AndroidX WebKit；Agent 只通过受控 Browser Tools 操作。
- 文件：应用私有 Workspace、SAF 授权目录和用户可选的 All files access。
- 高级能力：用户显式开启 Accessibility 或 Root；系统权限和每次 Tool 授权相互独立。
- 安全配置：所有安装默认 `STANDARD`；consumer 只提供 Standard，developer 可显式进入 `ADVANCED`，但 Advanced 只扩大受控能力，不削弱 Policy、审批、隔离、敏感目标拒绝或审计。见 [ADR-0005](docs/adr/0005-standard-advanced-safety-profiles.md)。
- 分发角色：当前直接分发只提供一个用户主包（developer 构建、产品名仍为 Helix）；consumer 仅作未来受限渠道构建。见 [ADR-0006](docs/adr/0006-single-direct-main-package.md)。
- Runtime 生命周期：PRoot/CLI companion 只需安装，不要求用户先打开或保持常驻；Helix 只在用户点击验证/修复/登录或批准的 Job 上按需冷绑定，进程死亡后只查询/对账，不自动重放。见 [ADR-0007](docs/adr/0007-companion-runtime-lifecycle.md)。
- 执行位置：当前 E0/E1/E2/E2C 都使用手机本机 CPU 与 Android/Linux 内核；“本机执行”不等于都在主进程，也不等于虚拟机。QuickJS 依赖 isolated UID，PRoot/CLI 依赖独立 APK/UID；云端模型只做推理，不直接成为手机执行 Worker。
- 高风险动作：模型不能自行放行，必须经过 Policy Engine 和用户审批。
- 审批体验：通用 L2/L3 动作当前只设计“按精确参数每次询问”；不存在“模型帮我批准”“切换 Advanced 后自动批准”或全局“完全访问”。Advanced 是能力可见性与受控规则配置，不是绕过安全内核的超级权限。
- Git：PRoot 计划内置的 `git` 只承诺离线 Job 副本中的基础命令与 smoke，不代表 Helix 已有持久仓库管理；`clone/pull/push` 不可用，`.git` 的权威位置、原子导入和 hooks/config/credential 边界由 HXA-088 与 [ADR-0008](docs/adr/0008-git-workspace-management.md) 决策后才能实现。
- Tool 编排：首版采用单一安全管线、参数级 effect footprint、有界读并发、按模型顺序回填、持久回放和结构化用户输入；子 Agent/声明式 Workflow 仅作 HXA-105/ADR-0009 后期实验。云端任务舰队、自修改插件、可执行 Policy/Workflow DSL 和延迟网络审批不进入当前路线。
- 当前不实现：远程 Worker、云端沙箱、桌面配对、HarmonyOS、自动支付和无人确认的对外发送。
- 应用 ID：consumer `com.helix.agent`、developer `com.helix.agent.developer`、PRoot Runtime `com.helix.runtime.proot`、CLI Runtime `com.helix.runtime.cli`。
- 发布身份：上述 applicationId 暂保持不变；首次对外稳定发布前由 HXA-122 决定直接分发主应用的最终 applicationId、签名和升级路径。
- 变体：developer-only 模块通过 Gradle variant dependency/source set 裁剪，不只用 UI feature flag 隐藏。
- 项目源码许可证：Apache License 2.0；第三方依赖和运行时资产仍保留各自的许可证与 notice 义务，见根 `LICENSE` 和 `THIRD_PARTY_NOTICES.md`。

## 给实现者的第一条规则

不要直接从“聊天页面”开始堆功能。严格按 [技术路线](docs/04-roadmap-and-backlog.md) 的任务顺序实施，并在每个任务完成后执行该任务列出的验收命令。

## 调研快照

文档 Baseline 1.3，复核日期：2026-08-31。涉及 Android、依赖版本、服务登录方式和第三方仓库的内容会随时间变化；升级前必须重新核实并单独提交依赖升级变更。
