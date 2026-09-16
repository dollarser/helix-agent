# 当前实施状态

更新：2026-09-16。此页只维护当前结论、下一步和未闭合边界。具体测试数字与过程见证据，不从历史 commit/设备推断当前现场。

## Completed

所有已交付 HXA 见[完成记录索引](../completion-records/index.md)，M0 见[工程基线](../completion-records/M0.md)。记录的完成只限各自范围。

最近完成：HXA-200 审批审计/恢复，HXA-201 设置/UI，HXA-208 完整 Goal 工具与前后台连续运行。基线回归及 thinking/startup 修复见[基线修复](../bug-fixes/2026-09-16-pre-hxa-baseline-regressions.md)与[连接/启动修复](../bug-fixes/2026-09-16-main-thinking-and-startup.md)。不重做这些任务，不将旧三态验收用作新授权方案证明。

收尾补验：SGLang UI smoke已修复，在API29/36 developer真实端点各1项通过，本地表单各4项通过；默认profile各1项明确跳过，见[修复与证据](../bug-fixes/2026-09-16-sglang-smoke-synchronization.md)。这不关闭192/193的其他范围。

## In progress

本轮已完成文档治理与SGLang基线收尾，并验证 HXA-209 网络执行域候选：同 APK isolated UID 不直接支持现有 PRoot 的 RootFS/工作目录，按所有者选择保留当前 Runtime、撤下统一禁网要求；不是禁网功能完成。见[验证记录](../evidence/development/isolated-proot-feasibility-2026-09-16.md)。HXA-209 A 切片：四个执行域的效果边界矩阵与证据已建立，rm 命令规则与 45 个契约测试实现并当日全绿，宿主侧套件当日新跑通过，见[执行域效果边界与证据矩阵](../evidence/development/2026-09-16-hxa209-execution-domain-matrix.md)；B–E（单一授权配置/接线/UI/文档收口）尚未实现。27项分为：6项收尾验收、12项待实现、2项集成验收、3项待决策、4项发行队列，分类与依赖见[任务索引](roadmap.md)。有代码无完整验收不等于从零待做；本轮没有补写任何功能完成记录。

## Next task

大型开发按[统一交接Prompt](implementation-guide.md)交接；具名切片可本地提交，不push/合并/发布。

- **首个实施切片：HXA-209 A（执行域可行性）**。先证明工作目录/CUSTOM禁写可约束且正常任务可用，再铺配置/UI。不得为了209提前把全部终端功能一起实现。
- **随后收口192并推进核心体验**：192补Plan审阅到执行及209联动；202补已有任务页的跨页面路径，203产物、204恢复、205首次准备、207扩展闭环；191独立做深色主题与会话搜索，206汇总核心验收。
- **执行环境独立链**：193补干净资产/升级/CI；194命令详情→195实时日志，之后196后台Job与197手动PTY→198多会话，199验收终端。前台PTY不等待后台Job。
- **条件允许时收尾**：094/095 Root、190真实订阅、125受保护Connector；其外部设备/账号项不阻塞无依赖的本地功能。发行按120→122→121→123，不自动开始外部提交。
- 会话独立目录仍为 proposed [ADR-WORKSPACE-004](../adr/workspace/004-workspace-binding.md)，不自动启动HXA-210；Connector候选及工具descriptor候选不因整理而接受。

## Blocked

| 项目 | 缺少条件 / 决策 |
| --- | --- |
| HXA-125 受保护 Connector 服务验收 | WorkBuddy 来源样本已补齐；仍需独立测试账号验证凭据无效、权限拒绝、厂商撤销和重连；匿名服务与 fixture 不替代这些证据 |
| HXA-126 / HXA-129 | [ADR-CONNECTORS-002](../adr/connectors/002-oauth.md) / [ADR-CONNECTORS-003](../adr/connectors/003-ownership-and-installation.md) 待审查；HXA-126 还需两家独立服务账号与 redirect 条件 |
| HXA-130 | [ADR-CONNECTORS-004](../adr/connectors/004-signed-index.md) 待审查；生产安装集成依赖129，纯离线格式fixture可准备，市场运行时未授权 |
| Claude / Grok 真实付费调用 | 账号不可用，按所有者决定暂缓；本地与设备夹具通过不代表真实账号验收 |
| 发布验收 | HXA-122 尚待稳定 applicationId、渠道命名、升级路径与签名发行决策 |

## Current interfaces

- **聊天与装配**：`ChatService` 保留应用入口及运行协调，草稿、请求组装、附件重试、界面投影、工具调用与结算已有独立组件；`AppContainer` 接口与 `DefaultAppContainer` 组合根分离。资源与可变状态仍由原所有者管理，拆文件不产生第二套状态源。详见 HXA-179/183/184。
- **Goal 与后台任务**：Goal 采用 ADR-GOAL-001；暂停可由用户继续，blocked 表示不能主动继续。后台能力是有界工具任务及结果回收，生产子 Agent、Agent graph 和声明式 Workflow 尚未实现；ADR-AGENT-004 的只读 Spike 不等于产品启用。
- **手动文件管理与 Agent 工具**：两者范围分离。手动界面支持 Workspace、共享存储和可写 SAF 的新建、改名、同来源复制/移动与删除；跨来源继续使用导入/导出。手动授权不扩大 Agent resolver，不能把手动共享根视为 Agent 已获授权的文件范围。边界以 HXA-180/182 与对应 ADR 为准。
- **执行与工具**：QuickJS 使用非导出的 isolated Service；当前实现的 PRoot/CLI 按 ADR-RUNTIME-001 内置于 developer APK，在私有进程共享主 UID，通过私有 Binder/PFD 通信，consumer 排除。`read`、`write`、`edit`、`files.*`、`bash` 继续经过既有 scope、Policy、审批、限制、验证和审计路径；PRoot/CLI 按需冷绑定，不因被动刷新启动。
- **扩展与 Provider**：MCP、Skill、A2A Client 已按 M7 矩阵验收；A2A 是用户配置的外部服务，不是本地子 Agent。订阅凭据由订阅模块持有，正常 API 不返回 token；共享 UID 不构成凭据隔离。developer/Advanced 接线不代表 consumer/store 开放或厂商官方支持。
- **浏览器**：WebView 由浏览器功能持有并绑定 Activity owner；下载队列和结果映射已分离。真实 AutofillService 的 fill/save 回归通过，不能据此关闭系统 JNI/Binder 累积问题。

## Known limitations

这里只列仍有效的范围限制和验收缺口；已修复缺陷、旧测试数量和机制演进保留在完成记录，不再列为当前故障。

- **系统与长稳**：应用侧释放路径和短回归已有证据。模拟器侧 24 小时长稳已按可采维度跑完：EV-02 两臂完成（API36 a11y 重尾归因 + API29 功能满绿，system-Binder 模拟器不可采）；EV-03 应用 FD 门禁为模拟器固有 goldfish 节点（X 类，非应用泄漏）。但**系统 JNI/Binder 根因仍 open，权威资源/Binder 门禁需真机**（模拟器无法关闭 goldfish FD 与 UID-proxy Binder 两维）。见 [释放路径调查](../evidence/development/native-reference-release-trace.md)、[浏览器引用验证](../evidence/development/browser-controller-reference-verification.md) 与 [优化待办](../evidence/development/main-optimization-todo.md)。
- **Root 真机**：HXA-188的授权/新请求拒绝/服务死亡与有界读取已有证据；094负责依赖/授权生命周期，095负责高层工具scope与失权，真机/OEM长稳另随发行矩阵验证。历史可用设备不等于当前可占用，开跑前核实所有权。
- **设备覆盖**：API29/36 模拟器及历史 API34/35、16 KiB 模拟器证据不替代物理低内存、OEM、热压力、Doze、安全锁屏和 Root grant/revoke/loss 验收；x86_64 静态制品证据也不等于实际运行。
- **文件恢复**：HXA-182 实现显式对账恢复，不承诺字节偏移续传、断电事务、自动后台队列或跨 Provider 原子事务；既有 picker 导入/导出及旧版无日志暂存不在该恢复管线内。目标/备份变化时保留人工核查，云盘厂商与全部中断阶段仍需外部设备验收。
- **模型与附件**：导入成功不等于模型理解。图片受实际模型视觉能力与端上预算约束；既有文本/图片管线不代表任意文档、音视频解析或 OCR 已实现。真实服务可用性与连接参数需要按服务当前状态验证。
- **后续功能**：已有只读 Git 状态/diff 界面；完整持久仓库写操作、remote Git/凭据，以及生产子 Agent/Workflow 尚未据此交付；接受 ADR 不构成实现证据。Connector OAuth、版本管理与市场扩展也不因本轮整理自动启动。
- **发行**：Standard 完整产品形态是 ADR-PLATFORM-001 的决定；当前 consumer/developer 构建与 CI debug APK 不是签名 release、完整渠道权限申报或商店审核证据。
- **测试条件跳过**：HXA-184 的 8 项 JVM 跳过需要 supplied Connector/WorkBuddy 与外部验收材料；每台设备的 2 项浏览器跳过需要显式长稳/诊断参数。均未计为通过，具体条件见 HXA-184。
- **外部依赖边界**：默认门禁不依赖真实业务服务、账号或付费调用；显式 profile 的缺参跳过不算通过，启用后失败如实记录。构建下载、本地测试服务器与外部 smoke 的边界统一见[公共验收规则](verification-matrix.md)。
