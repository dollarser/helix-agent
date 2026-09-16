# 三态审批与产品闭环开发任务包

> 2026-09-16 接手更新：所有者已授权本任务接手并提交本工作树剩余WIP，不再等待原并行所有方。完整本地主机/构建/lint/制品门禁已通过；原27 detekt、12 lint、2项JGit阻断均为历史结果。HXA-200/201已完成；其他任务按各自剩余验收判断。依赖允许为兼容性与维护升级，须同步版本锁、验证材料和设备证据。当前证据与提交范围见[WIP接手记录](wip-takeover-2026-09-16.md)，下文旧基线/未提交/失败数字只保留追溯用途。

日期：2026-09-14。状态：已授权计划；HXA-200/201已验收，其余条目按status推进。适用 `worktree-harness-2.0`，读取时 HEAD 为 `0d52eae7` 加大量未提交并行修改；每次开工重新核对，不能覆盖他人改动。决策见 accepted [ADR-0052](../adr/0052-tool-approval-preferences.md)。

## 1. 本次审查结论

源码已存在 AgentRuntime、TasksScreen、ArtifactsScreen、StorageApprovalBroker、CapabilitiesScreen 和恢复适配，不重建第二套任务/产物/授权数据库。当前发现的是入口和契约需要补齐，不是所有功能都没有。

| 项目 | 当前证据 | 后续动作 |
| --- | --- | --- |
| HXA-192 | `0d52eae7` 已提交 plan/storage 切片；当前交接记录双 API 各 Room 28、Plan 工具管线4通过 | 不重复旧迁移修复；仍补 UI 级闭环、计划审阅不生成工具批准、全量门禁 |
| 全量门禁 | 2026-09-16完整本地门禁通过，原JGit/detekt问题已修复 | 见WIP接手与基线修复记录；真实账号、远端CI与发行仍独立验收 |
| HXA-193 | 单 APK 与双 API 专项已有，仍有 CI/账号/发行条件 | 保留已有实现，分别补证，不因本任务重做 Runtime |
| 审批 | 单次批准/拒绝、持久 proof、审计已有；未见统一三态工具偏好入口 | HXA-200/201 增量落地 |
| Tasks/Artifacts/恢复 | 已有查询、打开、取消和局部恢复路径 | 统一导航、状态表达与错误动作，不复制事实状态 |
| 终端 | HXA-194～199 已规划、ADR-0050/0051 accepted，尚待实现 | 日志/PTY 沿原包，不在本包重复创建 |

以上是当前源码与记录审查，不是新设备测试结果；构建、记录数量和页面存在不能证明端到端体验通过。

## 2. 排期与权限

当前 HXA-192/193 相关收尾先行。之后优先200→201，202→203→204→205；终端194～199按各自依赖穿插，每次仅一个实现 checkpoint。206为本包综合验收；终端未完成时明确未覆盖，不谎称全产品闭环。独立功能不要求等待账号/发布或所有终端功能。

本包允许小模型验证后本地 commit，沿用 [Git 提交纪律](harness-2.0-next-work.md)：具名路径/hunk、检查 cached diff、不夹带无关变更、不 push/合并/发布。扩大审批到未来任意 L2/L3、远程 Worker、自动外发、插件市场/Workflow 或新平台不在此授权内。

## 3. 用户应看到的行为

具体跨页面导航、环境修复、多会话、扩展添加到使用及用户文案见 [工作区与能力体验方案](workspace-and-capability-experience-plan.md)，作为201～206的补充验收，不重复建页面或后端。新增HXA-207只串联现有扩展来源，未授权在线市场/OAuth/版本回滚。206还需验证扩展完整使用路径；207适用本包本地commit纪律。

工具设置显示三个选项：允许、询问、禁止；同时显示来源、可用性、实际生效范围及失效原因。允许的解释是“范围内自动执行，高风险操作仍需确认”。已取得的系统授权不反复请求；系统权限和工具偏好分开呈现。一次调用的按钮仍是“本次允许/拒绝”，保存未来偏好是明确的另一项用户动作，不能由一次批准偷偷推导。

配置只需回答：用哪个模型、在哪里处理文件、需要哪些能力。任务页回答：正在做什么、为何等待、可以停止什么。结果页回答：生成了什么、在哪里、能否打开/导出。恢复页回答：已经做了什么、哪些结果未知、下一步能做什么。

## 4. 任务与验收

### HXA-200 三态偏好与执行解析

- 允许：core/model、core/policy、core/storage、tools/framework、app审批/注册表应用服务及测试；不改 Runtime 执行或 Goal 语义。
- 按ADR-0052实现持久偏好、来源身份、作用域、revision及版本失效；确认现有数据模型可复用后再决定新表，迁移只用下一真实版本，不写死当前schema号。
- Registry曝光和Dispatcher开始前解析同一契约。DENY优先；ASK可覆盖低风险自动路径；ALLOW不替代能力/scope/精确高风险proof。保留原未设置用户的有效行为，新工具默认ASK。
- 单次proof与偏好分开；范围外/过期规则回退询问，模式或能力不允许则拒绝，不能询问后越过硬限制。已批准精确批次不重复弹窗。
- 测试：三态×风险×模式，规则失效，外部同名工具，重启/旧库迁移，审批等待和排队时禁止，取消/重复提交，跨会话不串偏好。用真实Room/Registry/Dispatcher设备集成，不用纯Fake替代。
- 验收：P1+P2；新增 `ToolApprovalPreferenceDeviceTest` 双API、双flavor，执行数量必须大于0。

### HXA-201 工具设置与审批卡

- 依赖200。允许app/UI/审批/能力应用服务、资源与测试；UI不访问DAO。
- 设置中可按工具名称/提供方搜索、查看三态和来源，允许选定会话/Workspace限制、恢复默认；禁止重复创建另一套Capability开关。不可用工具可解释，但不能被“允许”激活。
- 审批卡首屏给出动作、目标、范围、关键变更/外发摘要；完整参数可展开。提供本次允许/拒绝，以及明确保存三态偏好的入口。高风险不出现含糊的“永远允许”。
- 保存后展示实际生效结果，重启保持；等待中修改需刷新实际状态；过期卡不能批准新调用。工具描述等外部文本是内容，不是指令。
- 验收：P1+P2；新增 `ToolApprovalSettingsDeviceTest`，覆盖三语言、深色/大字体、小屏、旋转、失效恢复，截图与实际执行配对。

### HXA-202 任务过程与操作入口

- 允许app/UI/chat/query/runcontrol和必要只读投影，不增新执行器或任务状态枚举。
- 复用Tasks：统一进入对应会话、命令详情、计划审阅、产物和恢复；同一任务采用稳定ID，不按标题合并。
- 状态区分运行、等用户审批、等外部结果、取消中、已结束及待核查；Turn结束不自动标整个Goal完成。不伪造百分比，未知总量用当前动作/已完成步骤。
- 精确停止并显示结算过程；观察和打开页面不启动/重试任务。实时日志接195，未接时说明结束后可见，不冒充streaming。
- 验收：P1+P3；新增 `TaskJourneyDeviceTest`，跨会话、后台返回、旋转、取消竞态、进程恢复以及快捷入口。

### HXA-203 可用产物与文件交付

- 允许app产物/UI/files、tools/files、core/workspace/storage相关引用与测试，不扩展Git远程操作。
- 复用真实scope、Artifact ID、写入Turn、hash及现有打开/导出；首屏展示文件名、类型、来源和可用性。支持返回产生它的任务。
- 缺文件/权限撤销/内容变化分别显示；只在实际操作成功后报告已打开/已导出。用户导出走已有SAF/分享授权，不扩大Agent范围。
- 大文件有界预览、格式不支持明确回退下载/外部打开；文档提取只有实际解析成功才报告成功，保留解析失败/截断原因，不将文件名当正文。
- 验收：P1+P3；新增 `ArtifactDeliveryDeviceTest`，同名跨scope、旧数据、替换、撤权、Unicode、大文件、取消导出和外部查看器不存在。

### HXA-204 错误与恢复动作

- 允许app恢复/文件/Provider适配、必要现有repository及测试，保持各执行域对账协议。
- 展示已完成动作、可用产物、待核查副作用、阻塞原因和下一动作。操作区分重新连接/查询结果/补充权限/继续Goal/新调用重试，不能统一叫resume。
- 错误按认证、网络、能力缺失、用户拒绝、预算、执行失败、结果未知分类；不解析任意错误字符串猜状态，使用现有结构化结果，必要时局部补类型。
- 认证修复或网络恢复不自动重放写入；旧任务结果查询不重新提交。已完成文件动作不重做，用户取消后不自动继续。
- 验收：P1+P3；新增 `RecoveryJourneyDeviceTest`，主/Runtime死亡、断网、过期认证fixture、权限撤销、恢复按钮重复点击和未知结果。真实账号另记，不借fixture宣称账号验收。

### HXA-205 能力准备与首次任务

- 与HXA-190/191对照，已有引导/主题/搜索不重做。允许app配置/能力/Workspace/UI、developer初始化入口及测试。
- 一个准备视图聚合模型连接、Workspace、所需系统能力与Runtime状态，使用现有服务，按用户当前目标给出下一动作。
- 被动进入页面不拉起Runtime或登录；用户点击初始化/检查/修复才冷绑定。安装中、损坏、无网络、取消、重开状态可恢复。
- consumer不出现无法兑现的Runtime入口，developer不再显示独立APK安装流程。无模型配置时文件管理/浏览器仍可用。
- 验收：P1+P3；新增 `CapabilityReadinessDeviceTest`，新安装/旧配置/离线/取消/旋转/双flavor及实际Runtime初始化复用193脚本。

### HXA-206 完整场景与可用性验收

- 允许前述范围的局部修复、测试/scripts/docs，不顺带开发市场/定时任务/新推理引擎。
- 固定任务：读取资料形成结果；修改测试文件并打开产物；工具ASK后批准；DENY阻止执行；ALLOW范围内不弹窗且高风险不越权；取消长任务；重启后对账；修复失效能力后由用户继续。
- 每项记录用户步骤数、重复弹窗数、结果能否打开、恢复是否重复副作用、实际成功/执行/跳过场景、设备API。比较同fixture前后，不用主观评分或测试数量替代任务成功率。
- P1/P2/P3+完整 `./scripts/check-all.sh`；设备证据必须来自独占runner，新增可复用 `scripts/verify-product-journeys.py`（本HXA创建）验证失败与跳过计数，保存owned进程退出记录。所有新增设备类均实际执行。
- 文档/源码/主机/设备/真实账号/发行分别记账；门禁未通过不写完成记录。交付状态同步status/roadmap/matrix，commit与push分开报告。

## 5. 精确验证命令

仓库根执行，先配置仓库要求的JDK17/Android SDK。下列是待实施任务的命令，不是本轮已通过结果。

P1（每任务）：

```bash
./scripts/check-all.sh --source
./gradlew spotlessCheck detekt
git diff --check
```

P2（审批与存储）：

```bash
./gradlew :core:model:test :core:policy:test :core:agent:test :tools:framework:test :core:storage:testDebugUnitTest
./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest
./gradlew :core:storage:assembleDebugAndroidTest :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest
```

P3（产品UI/构建，200/201也需执行）：

```bash
./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest
./gradlew :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest
./gradlew :app:lintConsumerDebug :app:lintDeveloperDebug
python3 scripts/debug/2026-09-09/run-owned-emulator.py --help
```

新增设备类放标准app androidTest source set；涉及Runtime的developer专属子集分开。实现者按runner实际参数保存日期脚本，API29/36分别启动新独占模拟器，不借已有serial；finally只结束自有进程。测试名称不存在/零执行/跳过不能算通过。数据库变更还要运行真实新迁移类；脚本写出最终命令、测试数、exit code与日志路径。

## 6. 小模型启动 Prompt

本节仅用于已选中产品专项；首次接手使用[统一交接 Prompt](harness-implementation-handoff.md)。206的扩展路径依赖207，终端完整路径复用199证据；可先验证独立子集，不将未执行路径记为通过。

```text
继续 Helix：先核实 HEAD、dirty/staged paths、AGENTS、README、status、roadmap，保留并行改动。当前核心切片已有0d52eae7，不重复旧迁移/Plan修复；重新读取最新证据，不硬编码旧lint或测试数。
先收口当前HXA相关门禁，再按 product-completion-and-approval-plan.md 推进200～206，与终端194～199按依赖安排，每次一个checkpoint。ADR-0048/0050/0051/0052已接受，不重复请求架构接受；已授权实现自主完成。
三态偏好必须在真实Dispatcher执行生效，不只是三个按钮。允许不创造能力/scope，不永久放行未来L2/L3；询问尊重用户设置，禁止覆盖未开始的排队调用。单次批准与未来偏好明确分开。
复用Tasks、Artifacts、Capability和恢复服务，不复制执行状态。入口可用、过程可见、结果可打开、错误可恢复均用实际用户场景验收。保持独立文件/浏览器入口。
按P1～P3和任务设备矩阵测试，写真实完成记录；缺外部条件只阻塞相关部分。验证通过后具名路径/hunk本地commit，报告hash及剩余dirty，不夹带无关修改、不push/合并/发布。
```

## 7. 更广能力覆盖的边界

本轮优先交付已有能力闭环。完整备份恢复、更多文档格式、Git远程/提交UI、扩展市场、Workflow/Schedule、Shizuku/ADB、本地模型引擎分别需要真实需求、允许模块及平台/数据验收，不因Operit拥有就一起塞入P0。下一轮以本包场景失败和用户需求选项立项，沿已有ADR/HXA，避免重复或预留空模块。
