"""Generate the reviewed responsibility map from the reproducible source inventory."""
import json
from pathlib import Path
rows=json.loads(Path('build/debug/2026-09-10/large-class-audit/inventory.json').read_text())
# Manual source-based assessments; priority is maintainability, not defect severity.
reviews={
'ChatService.kt':('A','单个服务类','草稿/会话、模型切换、附件/出网确认、发送重试、Turn 生命期','先抽会话/草稿管理和附件发送准备；沿用现有 StagedAttachmentProcessor、ChatRequestAssembler，不再造重叠组件。保留唯一准入和活动 Turn 状态。'),
'LinuxRunTool.kt':('A','Tool object＋嵌套生产执行器','schema、参数解析、快照、环境审查、提交/等待、结果导入','先分离 Tool 合约与 ProductionLinuxExecutor；再提取快照/导入。保持提交前持久关联、按原 jobId 对账及精确审批。'),
'ToolDispatcher.kt':('B','请求/结果类型＋调度类','校验、策略、审批、deadline、执行、结果绑定与审计','可提取 deadline 执行器、绑定/结果转换；保留可见的串行主流程，审批消费与拒绝记忆只能有一个所有者。'),
'ProotJobRunner.kt':('A','Runner＋输出捕获辅助类','输入解包、进程启动、输出捕获、终态、归档、进程组/孤儿清理','先抽输出捕获/归档和进程组控制，再抽输入准备；终态写入与取消/超时裁决仍集中，不能拆成互相竞争的 owner。'),
'WorkspaceArtifactStore.kt':('B','存储类＋结果类型','读写/查找、配额、复制移动、回收站、隐私删除','可抽回收站、隐私清除和检索；共用原路径约束/配额/原子写入，避免两套访问规则。'),
'ConversationRepositories.kt':('C','多个独立 Repository','Session、Message、Turn、审批、回执、执行、Artifact、Audit 仓储','按现有类型拆文件即可；已有职责边界，不需把多个 Repository 重新合成服务。'),
'ChatScreen.kt':('A','多个 Compose 函数','会话列表、会话主体、模式说明、工具行/恢复入口和回调接线','拆 SessionListSection、ConversationSection 和恢复/工具展示；复用已有 composer/timeline，不在 UI 新增执行状态机。'),
'ProviderScreen.kt':('A','多个 Compose 函数和表单类型','列表管理、模板选择、配置表单、行操作、状态文案、endpoint 解析','拆表单状态/校验、模板对话框、Provider 行和状态展示；保存/连接测试仍经 ProviderService。'),
'FileManagerServiceTransfers.kt':('A','传输合约＋FileManagerTransfers','单文件/目录导入、导出目标、冲突命名、拒绝与结果映射','拆导入、导出、结果映射；共用冲突命名规则。不能混淆原 picker 管线与新手动恢复日志。'),
'FileManagerService.kt':('B','facade＋公共结果类型','来源/目录、手动变更委托、批处理和结果汇总','预览/回收站已抽出；下一步仅抽 BatchOperations、来源/目录读取。facade 保持统一，不把每个委托包装成新类。'),
'AppContainer.kt':('C','接口＋装配根','构造依赖、连接服务和回调、持有单例','按文件/聊天/运行时等领域整理装配组件即可；先复用 AppFileServices 等现有装配，避免服务定位器或新 DI 框架。'),
'TurnReducer.kt':('D','状态转换 object','生命周期、模型、工具事件和状态不变量','已有事件域方法分组；暂不分散转换规则，先保证状态表可读。不要仅按行数切出相互递归 reducer。'),
'ArchiveTools.kt':('C','两个 Tool object＋辅助函数','归档/解压合约、成员收集、执行与错误映射','按 FilesArchiveTool、FilesExtractTool 分文件，共用最小格式/成员帮助函数；不是一个 694 行的单类。'),
'ChatToolCalls.kt':('A','工具调用编排类','审批动作、模型工具消息转换、调用准备、批调度/单调度','先抽模型工具消息编码和 DispatchRequest 准备；保留 dispatchFacts、结果顺序、取消/未执行项结算的唯一归属，避免刚拆出又长成新大类。'),
'JsExecutionClient.kt':('B','参数类型＋客户端','请求预检、PFD 准备、隔离服务绑定、执行等待、取消和结果分类','可抽请求预检/传输准备；绑定句柄和释放在一个生命周期组件，不能散到多个调用方。'),
'BrowserController.kt':('B','浏览器控制器','Tab 导航、snapshot、宿主绑定、下载/导出、清理','优先抽下载队列/保存；延续既有 TabController/Owner，暂不改变 Activity/WebView 归属或回调失效机制。'),
'JsExecutionService.kt':('B','Service 外壳＋嵌套 Binder','Binder 事务、请求验证、运行准备、执行限制和结果封送','实际热点是 ExecutionBinder；抽纯请求验证/执行辅助，不把每实例单次执行 slot 和 interrupt 标志拆散。'),
'A2aTaskRunner.kt':('B','持久远端任务编排类','提交/订阅/轮询/取消、持久更新、artifact 编解码/导入','先抽 artifact 与结果映射；远端任务恢复维持原 taskId，不在拆分后重新发送未决任务。'),
'ApprovalCardUi.kt':('C','UI 数据类型＋mapper','审批卡数据、风险/来源/状态文案、代码和限额显示','可按普通审批/代码执行/规则展示整理 mapper；无须改变审批机制，不是单个 UI 大类。'),
'BrowserToolBridgeImpl.kt':('B','工具到浏览器桥','动作调用、snapshot/result 映射、主线程调度和超时','可抽结果映射；主线程回调、超时与失效判定仍共用一个适配层。'),
'FilesMetaTools.kt':('C','多个文件 Tool object','文件元数据相关工具合约和执行','按已有 Tool 边界拆文件，避免为 schema 行数创造继承框架。'),
'NotificationsCalendarTools.kt':('C','多个 Android Tool object','通知和日历工具','按通知/日历拆文件；维持各自 Tool 描述、动态风险与 bridge。'),
'ProviderService.kt':('B','Provider 应用服务','配置/密钥写入、连接测试、能力/窗口发现、运行时 Provider 获取','先评估配置写入与探测协调分离；窗口已有 ProviderContextSettings，避免重复抽象；密钥和授权关联不得漂移。'),
'FilesMutateTools.kt':('C','多个文件 Tool object','文件变更工具合约、执行和注册','按 Tool object 整理；继续调用共同存储实现，无需新的通用操作 DSL。'),
'AndroidSystemTools.kt':('C','多个 Android Tool object','设备信息、剪贴板、分享等','按现有工具类型分文件即可，保持风险和权限归属。'),
'ConfigRepositories.kt':('C','配置类型＋多个 Repository','Provider、Runtime、MCP、Skill 配置仓储','按现有 Repository 文件化；属于聚合组织问题。'),
'HttpFetchBridgeImpl.kt':('B','策略/解析器类型＋HTTP 桥','DNS/SSRF 门、跳转、socket/TLS、HTTP 头/body/chunk 解析','先抽有界 HTTP 解析；连接仍使用审查过的地址，不能让重定向或新 HTTP 库绕过 DNS/SSRF 约束。'),
'ResponsesStreamDecoder.kt':('D','协议流状态机','Responses SSE、工具参数、终态/usage/协议错误','协议特定状态保持集中；只提取无状态字段/错误映射，不能因相似而强并不同协议 decoder。'),
'AutomationActions.kt':('C','查询/结果类型＋多个执行组件','查找、节点动作、等待','Finder、Executor、Waiter 已独立；可分文件，保持节点验证/代际绑定。'),
'WebViewTabHost.kt':('D','单 Tab 宿主','WebView 懒创建、客户端回调、JS 等待、暂停/销毁','有明确资源所有者；保持集中，只有无状态回调适配值得局部提取，不为行数改 Context/生命周期。'),
'AnthropicStreamDecoder.kt':('D','协议流状态机','message/block/thinking/tool 增量及终态','保持独立协议语义和事件顺序；局部可抽错误映射，非优先拆类。'),
'HelixDatabase.kt':('C','Room 数据库＋迁移声明','DAO 入口、数据库构建和迁移 SQL','可把 migrations 按版本整理为文件；保留版本顺序/注册单点，不改变 schema。'),
'EditTool.kt':('D','单 Tool object','edit schema、参数、定位替换和输出','单工具职责清楚；只有替换算法持续增长时再提纯算法对象，不为 schema 长度拆层。'),
'PolicyEngine.kt':('D','策略类型＋引擎','模式/默认拒绝、出网、动态风险裁决','保持决策优先级集中；纯 matcher 可按需要提取，不能分散 allow/deny 所有权。'),
'WireModelProvider.kt':('B','共享 Provider 基类','流式请求、配置检查、模型目录/上下文发现、凭据/网络失败映射','可提取 catalog 与 probe 请求支持；共享取消、凭据查找和连接释放保持一致，不复制网络底座。'),
'ArchiveCodec.kt':('D','归档类型＋有界 codec','ZIP/TAR 编解码与路径/大小限制','目前边界明确；格式继续增加时按 codec 分离，共享限制不变，非当前优先。'),
'SdkMcpClientFacade.kt':('C','多个 SDK 适配类＋转换函数','连接、协议协商 transport、session、content 映射','按已有 facade/transport/session/conversion 分文件；无需重新设计 MCP。'),
'TarStream.kt':('D','TAR 类型＋流式解析/提取','header 解析、成员校验、提取','有界解析契约集中有利；如拆，只抽纯 header codec，不分散路径/配额检查。'),
'SafImportPipeline.kt':('D','导入类型＋管线','源打开、目标定位、流式写入、取消、拒绝','单次导入管线职责清楚；保持清理与结果一致性，暂不为 407 行重构。'),
'AutomationSnapshotEngine.kt':('C','平台节点适配＋快照组件','节点封装、代际跟踪、指纹、遍历/回收','可按已存在的节点适配与捕获引擎分文件；节点 recycle 和 generation 验证不能丢失。'),
'CapabilityProbe.kt':('D','分阶段能力探测','配置/模型目录/文本/工具/视觉探测','阶段明确；优先改善阶段命名/可读性，暂不拆成大量单方法类。'),
'ConversationDaos.kt':('C','多个 DAO interface','会话、消息、Turn、审批等 SQL 接口','按现有 DAO 类型拆文件，保持 SQL 与事务合同；不是大类。'),
'SettingsScreen.kt':('C','多个 Compose 函数','设置页、PRoot 状态与修复、语言选择','可拆 RuntimeSection/LanguageSection；仍由服务执行业务，低风险组织清理。'),
}
assert len(reviews)==43
large=[r for r in rows if r['lines']>=400]
assert {Path(r['path']).name for r in large}==set(reviews)
head='''# 生产大文件与职责审查（2026-09-10）

## 范围与证据

快照来自 `codex/phone-chat-polish` 的当前未提交工作树（HXA-182 后），不是 main 的实现清单。本轮只盘点与提出建议，没有再次重构生产代码，也未提交、推送或合并。

扫描 main/consumer/developer 下 642 个 Kotlin/Java/C/C++ 源文件，排除 build、生成制品和测试；两份 debug-only 源文件不计入生产统计。完整枚举所有不少于 400 行的文件，共 43 份；同时扫描较短文件里的 LargeClass/TooManyFunctions/LongMethod 标记（80 份），选出值得关注的边界。400 行只是检索门槛，不是设计规则。

行数包括注释、schema、类型声明和空行，**不是类体行数或圈复杂度**。逐项结论来自声明、状态成员和关键执行路径的结构阅读；不冒充全仓逐行缺陷、安全或并发审计。未通过 AST 计算精确方法复杂度。

复现脚本：[inventory-large-production-files.py](../../scripts/debug/2026-09-10/inventory-large-production-files.py)。机器清单写入忽略目录 `build/debug/2026-09-10/large-class-audit/inventory.json`，保存文件路径、行数和声明锚点。审查建议由 [write-large-class-audit.py](../../scripts/debug/2026-09-10/write-large-class-audit.py) 与该清单生成，后续代码改变需重新审查，不应仅重跑脚本便把旧建议当成新结论。

上轮只看 main source set，漏掉 developer 的 LinuxRunTool；本轮补齐。多类聚合文件、Compose 函数文件和单一状态机分别评估，避免把“大文件”误诊成“大类”。

## 判定方法

- A：优先拆职责，已有明显独立变化原因或资源/协议辅助职责。
- B：适合进一步提取，但位于共享状态、安全或资源边界，需要专项验收。
- C：主要整理文件或装配，已有类型边界，不等于架构不合理。
- D：目前保持集中更合适，只在新增需求导致明显复杂化时局部提取。

A/B/C/D 是维护建议，不是缺陷严重度；没有仅凭体积判定的紧急 Bug。

'''
sections={'A':'优先拆职责','B':'谨慎提取独立职责','C':'整理文件或装配即可','D':'保留集中实现'}
body=head
for group,title in sections.items():
 subset=[r for r in large if reviews[Path(r['path']).name][0]==group]
 body+=f'## {group}：{title}（{len(subset)} 份）\n\n| 文件 / 行数 | 实际形态与职责 | 建议与不应改变的边界 |\n| --- | --- | --- |\n'
 for r in subset:
  name=Path(r['path']).name;_,kind,current,action=reviews[name]
  body+=f"| [{name}](../../{r['path']}) · {r['lines']} | {kind}：{current} | {action} |\n"
 body+='\n'
body+='''## 不满 400 行但需要关注

这些不是额外确诊的大类。标记只用于提醒：短类也可能同时承担多个变化原因，而 schema/协议分支较多也可能很合理。

| 组件 | 关注点 | 建议 |
| --- | --- | --- |
| FilesScreenActions · 374 行 | 导入、导出、批量、分享、权限撤销事件集中 | 跟随传输服务拆分事件处理，不让后台任务状态依赖页面存活；本轮不据此宣称存在 Bug |
| MainActivity · 367 行 | 导航、系统栏、Activity 结果和生命周期接线 | 可提取纯导航接线；Activity/浏览器资源归属保持 |
| GoalDialog · 354 行 | Goal 编辑与预算/状态展示 | 按表单区块整理，保持模型报告与 Harness 结算语义 |
| ConnectorService · 291 行 | 预览、安装、启用/禁用和配置更新 | 随 Connector 后续已授权工作评估；不借拆分类推进搁置的 OAuth/市场需求 |
| CodeJavascriptRunTool · 375 行 | Tool 合约与 QuickJS 适配 | 优先让客户端/服务侧职责明确，再决定是否单独提取合约 |
| SkillImportService · 372 行 | 校验、安装及文件发布 | 当前可保持一次安装管线；如提取，清理/发布必须同一所有者 |
| ProotRuntimeSupervisor · 331 行 | 绑定、握手、解绑/死亡处理 | 生命周期聚合有价值，不能为了缩短代码拆散句柄归属 |
| ToolScheduler · 344 行、GoalReducer · 371 行 | 顺序、取消、状态转换 | 保持核心状态裁决集中，优先无状态辅助函数 |
| Json · 397 行、ChatCompletionsStreamDecoder · 388 行 | 格式/协议状态解析 | 不因分支数多强行拆成通用框架；维持限制与协议特性 |

其余较短标记文件保留在机器清单，未仅凭 Suppress 注解列为重构任务。最近提取的 FileManagerPreview、ManualFileOperations、FilesRecoveryPanel 等不因仍有注解而再次机械拆分。

## 建议顺序与验收

1. **页面和原有文件传输**：ChatScreen、ProviderScreen、FileManagerTransfers。按界面区域/导入导出边界拆分；双 flavor JVM、UI/SAF/冲突/取消/导入导出回归。
2. **聊天应用层**：ChatService 的会话草稿和附件准备、ChatToolCalls 的编码与调用准备。先画出 draftLock、stagedLock、sessionTurnAdmission、turnCancels、dispatchFacts 的所有者；一个可变状态只能保留一个权威来源。验证草稿首次落库、切会话/模型、附件确认/重试、审批、Goal、压缩、后台结果和中断恢复。
3. **PRoot 两端**：LinuxRunTool / ProductionLinuxExecutor、ProotJobRunner。先做辅助类/合约提取，再拆执行阶段。必须验证双 APK 真实 guest、PFD 关闭、取消/超时/进程组、提交前关联、主进程和 companion 死亡、结果 ACK/归档；普通 app UI 测试不能替代。
4. **共享执行与适配**：ToolDispatcher、QuickJS、A2A、WorkspaceArtifactStore、HTTP 桥。各自独立检查点，保持授权/不重放/结果结算和资源释放规则；不在一个大 diff 同时更换多条底层管线。
5. **纯组织清理**：Repository/DAO/Tool 文件和装配根可独立完成，收益主要是导航和减少文件冲突。状态机和协议 decoder 不设置行数缩减目标。

重构交付的判据是：入口清楚、状态与资源归属单一、独立职责能单测、调用关系更短，而不是任意把所有文件压到 300 行以下。保持公开 facade、协议、schema、持久格式；如确需改变这些契约，先按 ADR 约定形成独立授权决定。

此顺序是建议，不是新增已接受 HXA 或自动执行授权。当前完成情况仍以 [实施状态](status.md) 和 [HXA-182](../completion-records/HXA-182.md) 为准。后续任务应先验证上述快照是否仍适用。
'''
Path('docs/development/large-class-responsibility-audit-2026-09-10.md').write_text(body)
print({g:sum(reviews[Path(r['path']).name][0]==g for r in large) for g in sections})
