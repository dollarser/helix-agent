# Mobile Use：设备就绪、锁屏与后台边界及可靠性优化

日期：2026-09-29。性质：讨论固化与源码评审；优化项为候选设计，不表示已实现或自动获得实施授权。

> **2026-10-14 承接导航：**本页原文保留早期设备许可和截图判断。后续 [HXA-243](../../completion-records/HXA-243.md)接入原生截图/手势，[HXA-244](../../completion-records/HXA-244.md)将会话授权与运行状态分开，[HXA-245](../../completion-records/HXA-245.md)承接插件浮窗；当前规则查[权限 ADR](../../adr/permissions/001-session-authorization.md)，交付范围查 [status](../../development/status.md)。不要从原文恢复锁屏/断连自动撤权、重复截图确认或“截图尚未接入”的旧判断。跨轮脉络见[演进索引](../../evidence/research-history/architecture-evolution-2026-10-14.md)，未接受的自动解锁等候选没有因整理而启用。

> **2026-09-30 时效与职责说明：**下文保留原核验基线和当时建议，不是实时缺陷清单。后续 [HXA-232](../../development/tasks/HXA-232.md) 已取消每十次动作的周期确认，并支持新快照验证后恢复已授权目标；不能继续把这两项作为未修复现状。模型/Harness 职责以 [Harness 第 13.6 节](../../architecture/harness-refactor-plan.md#136-模型与-harness-的职责优化及减法准则) 的目标准则解释：先反馈并自主处理权限内问题，真实授权、业务信息或系统认证才需要用户介入；不把本页“接续”建议扩大成所有失败都必须等待人工，也不为每个错误新增恢复引擎。截图、自动亮屏和新许可策略仍须各自接受与验证，当前实现/验证范围看 status 和具体任务。

本文承接“Helix 能否协助钉钉打卡”“锁屏后能否自动解锁或后台进行”的讨论，并对 Mobile Use 做定向源码核验。**优先建设可解释的设备就绪、动作后核查和用户接续，而不是先做自动输入锁屏密码、无人值守打卡或第二套 Agent 引擎。**

核验起点：当前项目检出分支 `v0.0.3`，HEAD `53beeedfbad96bbe271380c73546111cdbf0c0e6`，另有既存 README 改动。本页描述所读取的源码，不推断手机安装包与工作树一致。本次未执行设备、模型、账号、钉钉业务操作或功能测试。

阅读关系：[当前范围](../../development/status.md)决定正在做什么；[开发原则](../../development/feature-refactor-strategy.md)决定如何取舍；[平台能力 第 5 节](../../architecture/android-platform-capabilities.md#5-accessibility-自动化)和[权限 ADR](../../adr/permissions/003-dispatch-and-audit.md)规定现行规则；[Plugin 方案](../../architecture/plugin-platform-plan.md)负责扩展归属；[Harness 方案](../../architecture/harness-refactor-plan.md)负责公共执行边界。本文不复制这些完整契约，也不把候选变更写入 accepted ADR。

## 1. 证据口径与讨论结论

| 类型 | 本页如何使用 |
| --- | --- |
| 对话确认的需求 | 用户关心真实手机上的跨应用操作、锁屏后的可用性与后台执行，不仅是工具存在 |
| 当前源码事实 | 由下文 C01–C14 的文件与符号支持；静态核验不等于设备复现 |
| 官方平台事实 | 由 A01–A07 支持；不由竞品演示推导 Android 通用保证 |
| 推导风险 | 明确触发条件与待补反例，不把可能性写成已发生事故 |
| 优化建议 | 给出边界、接线与验收；不自动变成当前 HXA 或修改默认权限 |

### 1.1 固化的产品结论

1. Helix 已有 Mobile Use 的语义树、节点查找与受限动作，可作为**有人监督的界面辅助**基础；钉钉考勤页面、账号及最终业务效果尚未做本次专项验证，不能宣传为已交付的自动打卡功能。
2. “Helix 聊天界面退后台、目标应用在前台”不同于“手机安全锁定、目标应用也在后台”。前者符合跨应用辅助的形态，后者不是给现有点击工具加一个保活服务就能实现。
3. 当前源码在收到 `ACTION_SCREEN_OFF` 时结束自动化会话，安全锁定检查也会结束会话。它是 Helix 当前保守策略，不代表 Android 要求所有计算、文件、网络和 Runtime 工作同时结束。[C01–C03]
4. 解锁资格来自 Android 的认证状态，不来自模型、插件或旧审批。普通公共 API 的“请求解除 Keyguard”不是无凭据解开安全锁。[A01]
5. 非界面工作是否能继续，由原执行 owner、授权、租期、资源及系统运行条件决定；已有任务继续与到点自动启动新任务分别设计。[A05–A06]
6. 后续优先“保留任务进度 → 提示用户恢复设备条件 → 重新观察并继续”；无须认证时的有条件亮屏/解除遮挡，可作为独立增量，不作为后台任务的统一前置。

### 1.2 对上一轮表达的精确限定

“熄屏”不能只用像素是否亮着判断；`PowerManager.isInteractive()`描述设备交互状态，息屏显示或距离传感器会让亮度与交互状态不同。[A02]

“不支持后台界面操作”指当前 Mobile Use 不能把任意隐藏应用窗口当可操作目标，并不意味着所有 Android 组件在锁屏时都无法运行。“可以请求亮屏”也不代表任何后台时刻均能成功启动 Activity。[A03–A05]

当前 `ui.wait` **已经存在**，不能重新登记为“从零增加 UI 等待”。它与尚待实施的通用 `jobs.await` 不同；真正缺的是条件表达、完成原因、控制响应和生产路径验证。[C05–C06]

## 2. 设备状态与运行位置必须分开

### 2.1 设备状态不是一个 locked 布尔值

| 观察维度 | 含义 | 不能据此推导 |
| --- | --- | --- |
| interactive | 设备当前可与用户交互，通常屏幕亮，但不是屏幕像素状态的等价物 | 当前应用已经解锁或目标页面存在 |
| keyguardShowing | 锁屏界面正在显示 | 一定需要密码；显示遮挡与安全认证不是同一件事 |
| deviceLocked | 当前用户是否处于需要认证才能访问应用的锁定状态 | 所有工作资料/目标用户配置也同时就绪 |
| deviceSecure | 配置了安全锁屏方式 | 设备此刻一定已锁定 |
| targetReady | 精确目标应用/窗口可见且可观察，来源和授权匹配 | 按钮点击后业务就会成功 |
| capabilityReady | 插件、服务、会话授权、预算均可用 | 可越过 Keyguard、受保护窗口或业务认证 |

前四项的 API 语义见 A01–A02；后两项是 Helix 建议采用的能力判断。未知、查询失败和不支持不能默认为已解锁。首版以当前受支持的 Android 用户与显示范围为限，不暗示跨工作资料或多用户控制已支持。

### 2.2 四种“后台”

| 运行形态 | 当前/目标边界 |
| --- | --- |
| Helix UI 不在前台，目标 App 已解锁并可交互 | 当前 Mobile Use 的适用方向；仍需有效会话、可观察节点和权限 |
| Helix 活着，目标 App 隐藏在其他窗口后 | 不等于目标窗口可交互；须合法进入目标并重新观察，不能使用缓存 token 盲点 |
| 手机非交互或安全锁定，任务需要 GUI | 当前结束 Mobile Use；候选是保留进度、撤销旧动作资格并等待用户接续 |
| 手机锁定，任务完全不需要 GUI | 可以评估按既有 Runtime 合同继续；不是所有任务已经有后台 owner，更不保证系统永不回收 |

Accessibility 的窗口接口面向可交互窗口，不提供任意后台页面的隐藏遥控；被模态窗口遮挡、输入焦点等情况须按实际窗口处理。[A04]

### 2.3 系统亮屏、解锁请求与业务认证

`setShowWhenLocked()`用于自己的 Activity 显示在 Keyguard 上方；`setTurnScreenOn()`在相应 Activity 可见/resumed 等条件下亮屏。这些不是控制其他应用锁屏界面的通行证。[A03]

`requestDismissKeyguard()`在非安全锁或系统认定可信的状态下可直接解除；否则展示用户认证 UI。请求 Activity 不满足可见条件会失败，Activity 销毁等情况下不能假定回调必达。[A01]

候选恢复流程应处理请求被拒、用户取消、回调缺失、重新锁屏和原任务已结束。通知点击或认证成功只触发**重新准入检查**，不携带新的无限授权。新型锁状态 listener 还需核对 API 等级和所需权限，不能只看方法存在就注册。

不把手机 PIN、图案、密码或验证码交给模型、Skill、日志或记忆；不以 Root/ADB、锁屏覆盖或降低系统锁屏强度作为默认产品方案。重启首次解锁、目标应用登录、人脸/工作资料认证等条件分别处理，不声称解开设备锁就完成了全部业务认证。

## 3. 当前 Mobile Use 的实际结构与已有能力

```text
开发者版应用的显式用户入口
    → AutomationPermissionCenter
    → process-local AutomationSessionManager / ServiceController
    → HelixAccessibilityService

Agent ToolCall
    → 既有 Dispatcher / Policy / Approval / EffectFootprint
    → MobileUsePlugin 的类型化工具
    → PermissionCenterAutomationToolPort
    → 同一平台服务
```

MobileUsePlugin 当前只负责 manifest、PluginOrigin 和工具绑定，不拥有新的 Turn/Goal/规划循环。实际注册 10 个工具：`ui.snapshot/find/click/long_click/set_text/set_progress/scroll/back/home/wait`。[C04–C05]

值得保留的基础：精确允许包、服务授权实时检查、节点 token 的 package/window/generation/fingerprint 校验、动作前重新取节点、30 秒 token TTL、有界节点和文字、敏感目标拒绝、显式停止及取消、统一工具结算。这里的 **AutomationSession 是短时设备操作许可，不是用户的聊天 Session**。[C02–C10]

现行限制是最多 5 分钟/30 次动作尝试，每 10 次要求检查点确认；拒绝和失败的动作尝试也消耗该计数。它不是 Agent 总工具轮数或 Linux Job 租期，`ui.snapshot/find/wait` 的观察调用数也不能直接等同于动作计数。[C02]

本页不把这些数字当行业标准，更不把“按当前设计停止”报告成违反当前契约的 bug。是否调整默认值、检查点和锁屏生命周期，需要独立的产品/权限决定。

## 4. 定向评审发现与优化优先级

优先级是本页建议，不改变现行排期。P0 指先处理执行事实错误风险；P1 指任务可用性；P2 指新增能力或基于测量的策略改进。

| ID | 级别 | 发现 | 性质与建议 |
| --- | --- | --- | --- |
| MU-01 | P0 | 非成功动作一律返回 `sideEffectFree=true`，包括平台动作可能已发出的异常路径 | 源码可确认的过强分类；副作用反例未实测。先补调用前/发出后故障注入与正确 outcome |
| MU-02 | P1 | 熄屏、安全锁定、到期等原因最终可能只显示 `NO_ACTIVE_SESSION` | 缺少可行动诊断；先解释为何停止，再讨论锁屏后接续 |
| MU-03 | P1 | `ui.wait` 是同步 sleep 轮询，仅表达找到匹配节点；到期无独立 reason | 已实现但不足；统一生产等待与测试，区分条件满足、到期、取消、授权阻塞 |
| MU-04 | P1 | 查找会重新抓取并替换全部 token；find/wait 缺失截断与快照来源元数据 | 可能导致旧 token 无效和漏查误判；明确 observation identity，避免只靠 Prompt 补救 |
| MU-05 | P1 | 配置要输入包名，启动结果未被 UI 消费，显示偏底层状态 | 可用性和错误反馈缺口；应用选择、就地修复、停止原因与恢复入口优先 |
| MU-06 | P1 | controller 的全局 monitor 覆盖节点遍历/动作调用；动作 port 不传取消上下文 | 潜在停止延迟，未证明设备死锁；缩短锁范围并保留准入与失效栅栏 |
| MU-07 | P1 | 动作成功只返回 `SUCCEEDED`，未携带动作后的任务证据 | 能力缺口，不是所有点击工具都必须自带业务 verifier；补按需复查及证据引用 |
| MU-08 | P2 | 当前没有 Mobile Use 屏幕截图入口；部分架构文字容易让人误以为已有截图兜底 | 新能力候选；复用 HXA-225，不另建视觉 Provider 或跨 App 截图旁路 |
| MU-09 | P2 | 固定时间/动作上限、检查点与语义词拦截可能给正常任务增加摩擦 | 需测量与裁决；不能以去掉拒绝规则代替产品设计 |

### 4.1 MU-01：失败不等于没有执行

当前链路：[C09、C11]

```text
executeValidated → performAction / setProgress
    → 返回 false，或抛出 RuntimeException
    → ACTION_FAILED / UNSUPPORTED_UI
    → AutomationTools.action(...): Failed(..., sideEffectFree=true)
```

`ToolExecution` 对该字段的契约是“确认本次没有副作用”；Dispatcher 在还有 `maxAttempts` 额度时会据此允许技术重试。异常可能发生在动作进入平台之后，单凭异常类别或没有收到成功回包，不能证明外部操作没有发生。本次没有证明钉钉发生了重复提交，也不声称默认所有调用都会自动重试。

建议按**执行边界**而非简单 status 集合分类：发出前的 token/参数/权限拒绝可以确认未执行；已发出且回包不明时保留未知效果及原调用身份；收到平台成功只代表该动作层结果，不代表业务目标完成。复用既有 `Failed/CancelledWithEffectTruth/TimedOutWithEffectTruth`、audit 与 review 语义，不新增第二个 UNKNOWN 引擎。

动作后异常、取消、超时不得自动重放。先读取可得结果，无法确认则清楚说明；真正重试要基于新观察及正常授权。新测试必须覆盖“fake node 已记下一次动作后抛错”，证明不会被报告为 confirmed side-effect-free。

### 4.2 MU-02：先提供就绪原因，再改变锁屏行为

C01 的非交互事件直接结束 session，C03 的查询返回 `NO_ACTIVE_SESSION`；`lastStopReason` 已有但没有贯穿所有模型输出与 UI。恢复提示主要针对目标变化/检查点，不能准确引导锁屏、过期或服务失联。[C01–C05、C10]

建议复用现有结果模型增加有界诊断投影：服务状态、是否有有效短时授权、设备交互/认证状态、目标窗口、停止/暂停原因、下一步用户动作。只读状态查询不得主动启动服务、申请系统权限或解锁。名称可采用现有工具结果扩展或发现元数据，不为展示状态再创建平行全局 readiness 服务。

现阶段继续遵守锁屏结束许可；**先让用户看到“设备已锁定，当前操作许可已结束”而不是一个无法解释的失败**。后续接续见 第 5 节，需要新的授权与窗口观察，不能复活已清空的许可。

### 4.3 MU-03：完善已经存在的等待，不再另造第二套

生产入口是 `AutomationTools.wait()`：默认 2 秒、100 毫秒轮询，schema 上限 10 秒，受 ToolCall deadline 限制，每轮检查取消；授权/暂停阻塞会立即返回。它到期时仍返回最后一次 `findJson`，通常为 `NOT_FOUND`，没有单列 `WAIT_EXPIRED`，空/无效查询也未在循环前统一退出。[C05]

另一个 `AutomationWaiter` 提供独立枚举和可注入时钟，但本次搜索仅发现其自身及测试引用，生产 `ui.wait` 没有调用它。**不能用这个 helper 的通过情况替代生产等待验收。**[C06、C12]

建议逐步支持节点出现/消失、enabled、字段值匹配、目标窗口到达等封闭条件；先从失败样本需要的条件开始，不一次建设任意表达式 DSL。返回 condition、reason、elapsed、最近有效 observation ref；截断搜索不证明不存在，无法读取界面也不证明元素消失。

优先事件通知加合并/去抖、必要时有界低频探测；事件到达后仍读取和验证事实，不把事件本身当完成证明。使用适合测量经过时间的时钟，避免墙钟调整延长等待，持久授权到期继续遵守其原合同。

当前 `ACCESSIBILITY_AUTOMATION` 在 EffectFootprint 中即使 READ_ONLY 也保守排他。因此不能声称 `ui.wait` 期间其他工具天然可以并行；也不能直接去掉排他标志，因为观察会替换 token。[C11] 如引入 completion 接口，复用 Harness 的相关设计，不要求所有工具先全部异步化。

普通 UI 条件等待仍应短时、有界；等待用户解锁不是把 10 秒改成几小时。不得长时间占用业务 executor、控制许可或全局锁；实际 Binder/节点调用未返回时，不把 coroutine/Future 取消当作底层已结束，不无界补线程。

### 4.4 MU-04：观察身份、查找范围与 token 生命周期

当前 `ui.find` 每次调用 `port.snapshot()`，并不是查询先前模型已看过的不可变树。每次 snapshot 清空旧 token；每次成功节点动作也清空 token。现有工具描述已经提醒用最新 token，但连续多个 find 的结果中，早先 token 仍可能被模型误用。[C05、C07、C09]

同时，snapshot 的结构有 `createdAt` 和 bounds，但模型投影未传出；find/wait 的输出主要是 status/nodes/recovery，没有 package/window/generation/truncated。遍历最多 200 节点、深度 16、字段 256 字符与总 16,384 字符，`NOT_FOUND` 可能只是预算内未找到。[C05、C07–C08]

建议先补 observation identity、有效范围、截断原因与可用字段；不为“看得多”无限增加节点和全文。可以评估在同一有效快照上做有界查找，返回相同 observation ref，避免无必要地重建 token。窗口/来源/内容变化仍须失效，不能静默把旧 token 指到同名新节点。

已有非活动窗口内容事件过滤不应重写；先用弹窗、输入法、动态时钟和局部列表更新验证失效过粗/过细的实际影响。当前 exclusive 调度已限制重叠，不将潜在 token 干扰误报为已经确认的并发越权。

### 4.5 MU-05：由“开发者控制台”变成可使用的能力入口

当前 UI 输入逗号分隔包名；暂停后还需要输入恢复目标。`startSession(...)` 的返回结果未用于显示具体失败，`replaceAllowlist` 与前台启动异常也缺少该按钮内的完整结果反馈；页面循环每 250 毫秒查询，主要显示枚举及 ACTIVE/INACTIVE。[C10]

建议在原应用服务之上提供允许目标的可见 App 名称/身份选择，受 Android 包可见性和已有授权范围约束，保留包名高级输入，而不是要求所有用户知道钉钉包名。选择应用不自动开始任务或授予全部页面权限。

显示服务未开启、已开启未连接、设备未就绪、授权到期、待用户确认及启动被系统拒绝，并提供一个相应入口；保留通知/页面“立即停止”。使用生命周期感知的状态投影，避免单纯高频轮询。不得因 UI 正常刷新而重新开始 Mobile Use 或隐式延长许可。

恢复请求绑定原任务与当前短时许可/暂停版本；到期后重新授权，跨聊天会话不得借用旧任务的接续意图。当前 port/短时 session 不含完整聊天域，这是分层优点；绑定责任应放在可信应用装配/治理，不把整个 ChatService 注入插件。此处是必须补验的边界，不是本次已经证明跨会话越权。

### 4.6 MU-06：停止响应需要独立检查

C03 的 `snapshot/performNodeAction/performGlobalAction/stop` 共用对象 monitor，前几项在锁内调用平台服务。节点遍历或平台调用变慢时，停止和状态查询可能排队。`ui.wait` 的 sleep 本身不在这个 monitor 内，不能误写成“等待全程持锁”。[C03、C05]

建议短临界区捕获有效 session/取消 epoch，平台调用在受限通道执行，发布前复核 owner；关闭新动作准入与等待在途调用返回分别报告。必须保留精确目标和取消线性化点，不能为了减少锁就允许 stop 之后启动新动作。迟到事件不能恢复旧 session。

本次未测得 ANR、死锁或具体取消延迟，因此以注入慢节点/晚到回包的测试先证伪，再决定改造范围；不先承诺固定毫秒数。

### 4.7 MU-07：观察—动作—复查，而不是点击结束即任务完成

`SUCCEEDED` 对应平台操作返回；现行权限 ADR 已明确滑块动作成功也需要独立效果观察。[C09、C14] 建议复用这一原则：写入表单后读取字段、提交后检查业务回执/记录、页面跳转后核对目标；不要求每个动作都强制再调用一次 LLM 或获取整屏截图。

按需支持相同观察契约的后置条件，结果分别表达动作是否发出、是否被平台接受、是否观察到预期变化。任务目标仍由模型结合证据判断；不恢复所有 Goal 强制 verifier，不读取评测隐藏答案。

钉钉“按钮可点击”“动作已接受”“考勤记录成功”是三个不同结论。收到失败或网络超时时，先核查记录，再决定下一步，不能仅凭没看到成功提示重复提交。

### 4.8 MU-08 / MU-09：截图与授权摩擦是不同工作

目前 XML 未声明 `canTakeScreenshot`，工具列表没有截图入口；已有 `view_image` 与 browser.screenshot 不等于获得手机屏幕。[C04–C05、C13]

截图候选优先走 Android 官方能力：API 30+ 的屏幕截图和 API 34+ 的窗口截图各有支持条件。窗口模式可减少无关界面及覆盖层影响；不支持或受保护窗口明确返回原因，不旁路 `FLAG_SECURE`。API29 不因此获得同等能力，MediaProjection 如另行采用必须独立处理其授权和生命周期。[A04]

树与图像不是原子一致的天然快照；应关联 window/generation、采集区间与内容 hash，发生变化就拒绝错误配对。复用 HXA-225 的 typed VisualArtifact、来源/披露、归一化与预算；截图不用 Base64 普通文本，不长期保存全部屏幕或用户认证内容。

固定 5 分钟/30 动作/10 次确认能限制运行，但可能在长任务和模型延迟下过早中断。先测到期、checkpoint、stale token、拒绝原因及人工步骤，再评估用户可选的有界任务许可、独立持续时长/动作预算与更有意义的检查点。不把参数增大等于可靠性提升。

敏感词检查中的 send/publish 等也可能挡住合法用户目标；同时换个文案又不构成完整安全识别。后续可评估“确定高敏硬拒绝、其他明确业务效果按现有会话规则处理”的分类，不由模型自报低风险，不一刀切删除所有限制。**这些都是权限设计变化，本文不修改现行规则。**

## 5. 建议的设备接续合同

### 5.1 复用现有领域，不新增锁屏版 Turn 状态机

```text
已有任务需要 GUI
  → 查询即时设备/服务/授权/目标状态
  → 就绪：按原工具入口执行
  → 设备未就绪：记录阻碍与进度，停止 GUI 新动作准入
      → 释放观察/等待资源，撤销旧节点与旧操作许可
      → 以已有用户交互/结果入口提示
      → 用户恢复设备条件并明确继续
      → 当前任务仍有效？重新授权/准入 → 新观察 → 模型按事实继续
```

若原 Turn 仍存活，只能在其仍合法的交互/预算边界继续；若已终结或进程死亡，使用 existing successor Turn admission。两种情况都不复活旧 coroutine、旧 token 或过期审批。缺少合适的交互接线时保留为明确的待续任务，不临时添加隐藏模型轮询。

### 5.2 持久事实与可丢状态

| 保存/复用的领域事实 | 不恢复为有效权限的旧运行内容 |
| --- | --- |
| 会话/任务身份、目标、截止或有效时间、已知进度、最后确认的动作/业务回执 | AccessibilityNodeInfo、节点 token、旧窗口句柄 |
| 阻碍类型、原调用与不确定效果、产物引用 | 旧 AutomationSession 的启动资格、缓存系统权限 |
| 用户继续/取消的回执与归属 | 旧 Activity、Future/coroutine、预存下一个检查点确认 |

优先复用已有 Turn/消息/Goal/用户输入及审计存储，只为无法表达的真实新事实增加最小字段；不能因为要提示“解锁后继续”就新建一套 Job、恢复和工作流表。

### 5.3 等待、过期与触发

“设备恢复事件”只刷新就绪事实，默认不调用模型、不重新创建许可。通知点按也要核对任务是否被用户取消、替代或已完成，并拒绝旧通知重放。锁屏通知默认不展示账号、页面文字和考勤明细，解锁后再显示相关内容。

待续意图必须有有效范围与到期处理。考勤时间已过时，不在几个小时后自动执行原“现在打卡”；向用户说明并重新确认有效目标。有效期由对应产品任务决定，不在本页虚构统一时长。

无安全认证需求时的主动亮屏/解除遮挡，需要用户事先开启相应行为，并通过当前系统准入；失败则回到用户接续，不循环申请、不无限持 wakelock。用户恢复认证不是对新包、新业务动作或新截图披露的一揽子批准。

### 5.4 非 GUI 任务继续的边界

Mobile Use 许可结束不应被定义为取消所有无关执行；但依赖它的后续步骤也不能伪装可继续。是否允许其他工作运行，还要满足既有调度、资源、授权和预算规则。

既有 Runtime Job 可以按原 owner 合同继续、到期或结算；没有后台 owner 的普通同步工作不能因锁屏被临时“升级”。前台服务只是系统运行机制之一，既不保证常驻，也不保证网络/传感器始终可用。[A05–A06]

定时触发、Job 完成唤醒、设备解锁接续与 GUI 等待分四项设计。新增定时能力继续归候选索引中的 Schedule/Channel，不借本页自动立项。

## 6. 钉钉场景的能力与验收边界

### 6.1 首先验证有人监督的一次任务

建议最小场景：用户已登录并打开目标考勤页面，明确允许读取；先检查能否观察账号上下文、今日状态、正确按钮及可操作性，不提交。只读成功后，另按实际任务指令与会话授权评估写入操作。

如页面要求定位、指定网络、拍照、人脸验证或其他业务资格，照常满足。模型不能替用户取得身份资格，也不能将企业数据查询/上传接口未经核实地当成员工手机打卡接口。本次没有重新验证钉钉企业配置、API 或业务条款，不作接口可用性承诺。

不预装一个把任意考勤页面视为相同按钮的脚本。可在真实轨迹证明有复用价值后使用 Skill 描述流程和核查点；Skill 不扩大权限，不包含密码，也不把同名按钮当相同业务目标。

### 6.2 三种输出必须区分

| 结果 | 可以对用户说什么 |
| --- | --- |
| 已定位按钮，尚未执行 | 找到候选操作，尚未提交 |
| 平台接受动作，但没有确认业务记录 | 操作已发出，结果待核查；不盲重试 |
| 读取到可信的本次成功回执/状态 | 在证据覆盖范围内确认本次结果 |

打卡次数多、工具调用多、Turn COMPLETED 不能替代业务 oracle。后续真实测试应限定指定账号/企业/设备与用户授权，不在文档任务中操作真实记录。

## 7. 与当前重构的关系及建议实施顺序

| 切片 | 建议范围 | 退出条件 | 与主线关系 |
| --- | --- | --- | --- |
| MU-A：事实正确性 | MU-01，启动失败与停止原因反馈，生产 wait 测试缺口 | 未发出/已发出/未知不混淆，等待分类准确；关键反例有测试 | 可按独立缺陷任务收口，不要求先完成全部 R1/R2；不借此重写 Engine |
| MU-B：可用观察闭环 | MU-03～07 中最小范围：就绪投影、查找元数据、目标恢复、动作后核查 | 完成一个合成跨 App 任务及锁屏阻碍提示，不再靠猜 token/包名/状态 | 使用 R1 绑定与既有平台 adapter；具体接线避免与 R2 并行冲突 |
| MU-C：用户解锁后接续 | 第 5 节；许可过期后重新准入、通知与旧回执失效 | 旧 token/旧许可不恢复，任务不丢、无后台模型轮询或重复动作 | 新生命周期行为先更新所属权限/Agent 决定；不作为 R1 完成条件 |
| MU-D：按需截图 | MU-08，限定支持 API/窗口/隐私与图像预算 | 合成像素任务及受保护窗口拒绝分别通过 | 复用 HXA-225，无须先做通用 Observation 平台 |
| MU-E：策略和高级自动化 | 预算/checkpoint 调优、有条件亮屏、定时/脚本等 | 有真实收益与明确权限/平台范围 | 独立接受，不是所有前述工作的前置 |

R1 继续是已有授权的下一实施任务；本页提出的缺陷修复建议和功能切片并未自动进入执行。依赖满足、文件不冲突的局部工作可以独立开展，不能让所有优化都等待一轮更大的架构工程。

本专题停止条件：受监督的语义自动化能解释就绪状态、正确发出动作并核查结果；设备条件失效后不丢任务意图、不重复执行；当前边界有测试。达到后转入真实任务改善，不无限扩展多设备、通用脚本、自动登录或独立 Runner。

## 8. 验证矩阵与度量

下表是后续验收要求，本次未执行。复用 C12 的现有测试与合成 App，旧测试继续覆盖当前契约；新策略接受后再替换相应断言，不为“通过”删除保护。

| 组 | 需要验证的边界 | 独立判据 |
| --- | --- | --- |
| V01 | interactive、仅 Keyguard、安全锁定、状态读取失败 | 原因准确；不把屏幕亮等于已解锁，不把 unknown 放行 |
| V02 | 熄屏/锁屏与准备执行竞争 | 新动作准入关闭；在途调用如实结算，不报未发生 |
| V03 | 点击前失败、发出后异常/超时、平台接受但业务失败 | 仅真正未发出可 sideEffectFree；无重复提交 |
| V04 | 慢 snapshot/Binder，用户停止与迟到回包 | 停止入口可响应；旧回包不能重新启用或发布新 token |
| V05 | 生产 ui.wait 找到/到期/取消/无效条件/授权暂停 | 用生产 executor 验证；无 sleep 盲等用户，无错误成功 |
| V06 | 新快照替换、并列查找、同名节点、截断列表 | token 归属准确；截断 NOT_FOUND 不被解释为绝对不存在 |
| V07 | 用户接管、目标跳转、检查点确认 | 不扩大允许包；旧确认/不同任务/会话拒绝复用 |
| V08 | 解锁通知被重复点击，原任务已取消/过期 | 不执行旧任务；重新观察和准入 |
| V09 | 进程死亡、重启、旧 AutomationSession 丢失 | 复用原事实并经 successor admission；不恢复旧运行句柄 |
| V10 | GUI 受阻时原后台 Job 仍在运行 | 许可边界互不伪造；Job 仍按原租期/owner/结果结算 |
| V11 | 截图不支持、secure window、窗口/像素不一致 | 明确拒绝/不支持，不发送混配图片或旁路保护 |
| V12 | 屏幕采集到模型发送间撤权/换会话/切模型 | 来源和披露重检，无缓存批准跨目标复用 |
| V13 | 系统设置启动失败、服务 enabled-disconnected、非法包名 | 就地可解释、当前输入保留、不抛异常退出页面 |
| V14 | consumer/developer 与未就绪插件发现 | 支持范围准确；只读查看状态不启动服务或模型 |
| V15 | 合成考勤/表单任务与后续指定真实场景 | 动作接受、业务结果、人工介入分别记录；不使用隐藏判分答案 |

设备需当前明确授权，API/渠道/用户配置与模型能力分别记账。官方 UI Automator 的等待和状态断言可作为测试机制参考，不把 instrumentation/ADB 的能力当成普通生产 App 天然拥有的权限。[A07]

优先度量：业务成功率、假成功声明、首次故障位置、stale-token 比例、等待到期/无效轮询、人工恢复步骤、许可过期与 checkpoint 触发数、停止响应及快照时延、节点/图像输入量。对同模型、同任务、同预算做配对比较；不预设“加截图/加预算就提升 X%”，也不把已有 Benchmark 的环境 ERROR 直接归因为 Mobile Use 缺陷。

## 9. 本次证据目录

### 9.1 仓库源码与现行记录

行号是本次读取位置，后续以符号检索为准。下列链接不是宣称所有相关测试已经运行。

| 编号 | 文件与定位 | 本次支持的事实 |
| --- | --- | --- |
| C01 | [HelixAccessibilityService.kt](../../../extensions/mobile-use/src/main/kotlin/com/helix/extensions/mobileuse/automation/HelixAccessibilityService.kt)，31–64、94–125、197–226 | 非交互即停；安全锁检查；FGS、expiry 和停止通知 |
| C02 | [AutomationSessionManager.kt](../../../extensions/mobile-use/src/main/kotlin/com/helix/extensions/mobileuse/automation/AutomationSessionManager.kt)，65–70、160–238、244–249 | process-local 许可、到期/停止/检查点、5 分钟/30 动作 |
| C03 | [AutomationServiceController.kt](../../../extensions/mobile-use/src/main/kotlin/com/helix/extensions/mobileuse/automation/AutomationServiceController.kt)，65–170、194–273 | 同步锁、启动回滚、恢复、停止与结果投影 |
| C04 | [MobileUsePlugin.kt](../../../extensions/mobile-use/src/main/kotlin/com/helix/extensions/mobileuse/MobileUsePlugin.kt)，14–46 | host-native 插件装配，无独立 AgentLoop |
| C05 | [AutomationTools.kt](../../../extensions/mobile-use/src/main/kotlin/com/helix/extensions/mobileuse/tools/AutomationTools.kt)，52–64、76–242、279–302、332–418 | 生产工具、wait、元数据投影、失败分类与 schema |
| C06 | [AutomationWaiter.kt](../../../extensions/mobile-use/src/main/kotlin/com/helix/extensions/mobileuse/automation/AutomationWaiter.kt)，7–49 | 独立轮询 helper；不等同生产 wait |
| C07 | [AutomationSnapshot.kt](../../../extensions/mobile-use/src/main/kotlin/com/helix/extensions/mobileuse/automation/AutomationSnapshot.kt)，37–77、120–185 | observation 字段、token 替换/失效与 TTL |
| C08 | [AutomationSnapshotEngine.kt](../../../extensions/mobile-use/src/main/kotlin/com/helix/extensions/mobileuse/automation/AutomationSnapshotEngine.kt)，109–170、184–261、303–307；[AutomationFinder.kt](../../../extensions/mobile-use/src/main/kotlin/com/helix/extensions/mobileuse/automation/AutomationFinder.kt)，4–37 | 截断、敏感/无树拒绝与查找范围 |
| C09 | [AutomationNodeActionExecutor.kt](../../../extensions/mobile-use/src/main/kotlin/com/helix/extensions/mobileuse/automation/AutomationNodeActionExecutor.kt)，12–49、86–116；[AutomationActions.kt](../../../extensions/mobile-use/src/main/kotlin/com/helix/extensions/mobileuse/automation/AutomationActions.kt)，83–185 | 动作前复核、发出后异常折叠与敏感词规则 |
| C10 | [AutomationModule.kt](../../../app/src/developer/kotlin/com/helix/app/automation/AutomationModule.kt)，54–79、92–179；[AutomationPermissionCenter.kt](../../../extensions/mobile-use/src/main/kotlin/com/helix/extensions/mobileuse/automation/AutomationPermissionCenter.kt)，27–107 | 手动包名、轮询状态、启动/恢复 UI 与实时系统授权 |
| C11 | [ToolExecution.kt](../../../tools/framework/src/main/kotlin/com/helix/tools/framework/ToolExecution.kt)，83–107；[ToolDispatcher.kt](../../../tools/framework/src/main/kotlin/com/helix/tools/framework/ToolDispatcher.kt)，285–323；[EffectFootprint.kt](../../../tools/framework/src/main/kotlin/com/helix/tools/framework/EffectFootprint.kt)，71–118 | 零副作用语义、条件性重试与 Accessibility 排他 |
| C12 | [AutomationToolsTest.kt](../../../extensions/mobile-use/src/test/kotlin/com/helix/extensions/mobileuse/tools/AutomationToolsTest.kt)，49–60、128–136；[AutomationActionsTest.kt](../../../extensions/mobile-use/src/test/kotlin/com/helix/extensions/mobileuse/automation/AutomationActionsTest.kt)，404–455；[AutomationServiceDeviceTest.kt](../../../extensions/mobile-use/src/androidTest/kotlin/com/helix/extensions/mobileuse/automation/AutomationServiceDeviceTest.kt)，364–448 | 暂停恢复、helper 等待、预算和息屏停止已有测试入口 |
| C13 | [无障碍服务 XML](../../../extensions/mobile-use/src/main/res/xml/helix_accessibility_service.xml)，1–8；[视觉使用](../../product/image-reading.md) | 当前未声明屏幕采集能力；HXA-225 是可复用视觉通道 |
| C14 | [权限 ADR](../../adr/permissions/003-dispatch-and-audit.md)，14–20、46–52；[候选索引](../../development/candidate-decisions.md) | 现行权限/恢复决定与未排期范围 |

关键文件 SHA-256：

```text
HelixAccessibilityService.kt  1974da9d6aa46a9e12f3e86ba884c052e90f50a6915f3795e1050fc43a232af2
AutomationServiceController.kt  0c5c215fe1c5cde8bf4bffd04fe67ba62683db559f3bf78e1f2ee67faa85a6de
AutomationTools.kt  07d49381ada8bf90a2341fc9030a33faabeaf7de413ba09c9d5a89dd2de86c46
AutomationNodeActionExecutor.kt  db9fe5ce714343e808e65637d779b6c03d53d56a79c1829ea62d30bc97d5b15f
AutomationSessionManager.kt  3669ad260e36a9a50f309a177a562eb9cd0863f0917eca17592aa3f84fd35815
```

### 9.2 本次外部核验

访问日期均为 2026-09-29，只引用官方平台与测试资料。本次没有重新评价各竞品版本或钉钉企业业务资格。

| 编号 | 一手来源 | 支持范围 |
| --- | --- | --- |
| A01 | [Android KeyguardManager](https://developer.android.com/reference/android/app/KeyguardManager) | 锁定/安全配置/Keyguard 区别，解除请求与用户认证，Activity 前提和 listener 权限 |
| A02 | [Android PowerManager](https://developer.android.com/reference/android/os/PowerManager) | interactive 与屏幕状态的区别；息屏事件和省电限制 |
| A03 | [Android Activity](https://developer.android.com/reference/android/app/Activity) | setShowWhenLocked、setTurnScreenOn 的自身 Activity 条件 |
| A04 | [Android AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) | 可交互窗口、节点可能过期；API30/34 截图、capability 声明与 secure window 错误 |
| A05 | [Activity security / BAL](https://developer.android.com/guide/components/activities/secure-bal)、[FGS 后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) | 运行服务和启动可见界面是分别受限的动作；例外要按实际场景判断 |
| A06 | [Doze / App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby) | 非界面后台任务仍受系统调度、网络及资源限制 |
| A07 | [UI Automator](https://developer.android.com/training/testing/other-components/ui-automator) | 测试侧等待、跨应用操作及状态核验；不是生产权限授权 |

**结论：Mobile Use 需要优化，但重点是把现有能力做成可解释、可恢复、可核查的任务闭环。默认自动解锁、全天候保活和无人值守业务提交不是当前缺陷修复的同义词。**

<a id="mika-automation-review-2026-11-01"></a>
## Mika 自动化源码复核（2026-11-01）

本轮由 Owner 授权调研并针对性优化。只读检出 [Mika 5f630d9765038fa5178f3d7687a3cf5c22fbcfb4](https://github.com/wangzan101/Mika/tree/5f630d9765038fa5178f3d7687a3cf5c22fbcfb4)，未运行其脚本、安装 APK 或授予系统能力。根 LICENSE 为 Apache-2.0，NOTICE 保留作者归属；第三方运行时有各自许可，不从参考仓库搬运代码或二进制。本轮独立调整 Helix 已有观察逻辑。

| 维度与 Mika 源码证据 | Helix 当前实现与取舍 |
| --- | --- |
| [ChangeWait](https://github.com/wangzan101/Mika/blob/5f630d9765038fa5178f3d7687a3cf5c22fbcfb4/app/src/main/java/com/wangzan101/mika/cap/phone/a11y/ChangeWait.kt)：事件安静、树变化及帧稳定，400ms quiet、2.5s cap，避免过早判无变化 | `AutomationPostActionObservation` 已通过 `AutomationWaiter` 对 set_text/scroll 在原 deadline 内最多观察 1 秒，200ms 稳定再附状态。真实差距是持续动画/源截断时丢弃最后一次有效观察。本轮保留有界新状态并明确 UNSETTLED，模型据此选择等待、定位或继续；不加固定动作延迟、自动重试或像素采集权限。 |
| [ActionEffect](https://github.com/wangzan101/Mika/blob/5f630d9765038fa5178f3d7687a3cf5c22fbcfb4/core/src/main/kotlin/com/wangzan101/mika/core/uistate/ActionEffect.kt)：结构差异、输入读回、切应用、treeBlind 与加载信号 | Helix `AutomationToolOutcome` 已保留 DISPATCHED/UNVERIFIED 与不确定效果，不能把 Mika 的 done 启发式当业务成功。本轮保留该事实语义。输入去掉全部空白再比较不适用于任意文本，不照搬。全面动作前后采样增加调用与 token 生命周期复杂度，未引入。 |
| [AndroidScreen.resolve](https://github.com/wangzan101/Mika/blob/5f630d9765038fa5178f3d7687a3cf5c22fbcfb4/app/src/main/java/com/wangzan101/mika/cap/phone/a11y/AndroidScreen.kt#L1516)、同目录 NodeIds 与 core/uistate/RefStabilizer：snapshotId、节点刷新、特征唯一重定位 | Helix `AutomationNodeActionExecutor.validateObservedTarget` 已核验包、窗口、generation、fingerprint；新观察替换 token，动作失效旧 token；`AutomationClickMatchExecutor` 支持当前唯一 selector 原子定位。已有安全边界与恢复路径，不为复用旧编号引入另一套自动重定位。 |
| [TaskRun](https://github.com/wangzan101/Mika/blob/5f630d9765038fa5178f3d7687a3cf5c22fbcfb4/app/src/main/java/com/wangzan101/mika/task/TaskRun.kt#L838)：finally 释放显示/监听资源，NonCancellable 清理；664、717 行显式 reviewFinalAnswer=false | Helix Durable Turn、UNKNOWN、原调用身份及取消后容量释放已有独立契约。本轮观察取消不得抹去派发事实或发布迟到 token，不迁移第二套任务循环，也不声称 Mika 已启用最终独立核查。 |
| [ApprovalPolicy](https://github.com/wangzan101/Mika/blob/5f630d9765038fa5178f3d7687a3cf5c22fbcfb4/core/src/main/kotlin/com/wangzan101/mika/core/action/Approval.kt)：payment 决定 Ask/Deny，其余 Allow | 与 Helix 用户会话授权不是等价模型，不能作为授权来源。保留用户控制的能力与范围，不添加任意业务流程限制。 |
| 同目录 AndroidScreen、shell/display 及 TaskRun 的后台显示 owner | 无线调试与虚拟屏有额外权限、设备/焦点/生命周期成本，不作为小型可靠性优化引入。本轮不新增 root、Shizuku、ADB 网络或无障碍授权。 |

采用的改动限于 `AutomationPostActionObservation`、模型工具说明及回归覆盖：只有最后一次读取成功、快照存在且未暂停，调用未取消且原 deadline 未过期，才附 UNSETTLED 状态。输出仍有节点数/字节预算及 truncated 标记；缺失节点不能证明目标不存在。其 token 与普通快照一样，在执行时重新核验，连续变化仍可能使之过期，模型可使用 fresh selector 或再观察。UNSETTLED 不证明输入无效，不证明稳定或业务成功，也不触发重放。当前窄改动不解决纯视觉定位、云端模型效果或任意后台执行；验证原始收据保存在忽略的 `build/mika-automation-2026-11-01/`。

### 2026-11-01：Owner 追加后台屏与 sandbox 接入实现授权

上一节是先行窄改动的边界；Owner 随后明确要求实现后台虚拟屏，再补脚本统一工具桥、Skill 脚本入口和实际生命周期差距。仍以同一固定 commit 只读源码作证据，不运行 Mika、不搬二进制或源码。

| Mika 实现证据 | Helix 实际差距与采用方式 |
| --- | --- |
| [BuiltinShellManager](https://github.com/wangzan101/Mika/blob/5f630d9765038fa5178f3d7687a3cf5c22fbcfb4/app/src/main/java/com/wangzan101/mika/cap/phone/setup/BuiltinShellManager.kt)、[AgentDisplay](https://github.com/wangzan101/Mika/blob/5f630d9765038fa5178f3d7687a3cf5c22fbcfb4/app/src/main/java/com/wangzan101/mika/cap/phone/display/AgentDisplay.kt)、[ShellService](https://github.com/wangzan101/Mika/blob/5f630d9765038fa5178f3d7687a3cf5c22fbcfb4/app/src/main/java/com/wangzan101/mika/cap/phone/shell/ShellService.kt)：shell helper、Surface/ImageReader、display launch/input | Helix 已有 Shizuku UserService 和特权语义后端，复用而非新增无线 ADB 协议。用户选主屏/后台屏；`ShellBackgroundDisplays` 绑定会话 owner 和随机 displaySession，帧/窗口/节点/坐标一致路由。旧 token 不跨 Turn/屏幕复用，后台能力失败不退到主屏。Android 14+ 读取实际焦点 flags，旧版本报告降级；同 App 数据共享，主屏占用由显式用户选择移动。无 ALWAYS_UNLOCKED 或锁屏认证绕过。 |
| [SandboxTools](https://github.com/wangzan101/Mika/blob/5f630d9765038fa5178f3d7687a3cf5c22fbcfb4/core/src/main/kotlin/com/wangzan101/mika/core/cap/sandbox/SandboxTools.kt)、[SandboxBridge](https://github.com/wangzan101/Mika/blob/5f630d9765038fa5178f3d7687a3cf5c22fbcfb4/app/src/main/java/com/wangzan101/mika/cap/sandbox/SandboxBridge.kt)：随机 socket/token 与同任务 SubActions | Helix 原有 PRoot/QuickJS、Jobs/PTY、流日志/恢复和产物，无须重写。实际缺口是脚本回调统一工具。新增 `HostToolBridge` 与执行期 socket/Binder 接口，经原 ApplicationToolGateway/Dispatcher/批准/审计；注册表决定工具集合，不硬编码 Mobile Use。原 session/Turn、取消、deadline、display 选择仍由宿主绑定；脚本身份字段不采信。默认 isolated JS 不转 native。 |
| [ScriptManifest](https://github.com/wangzan101/Mika/blob/5f630d9765038fa5178f3d7687a3cf5c22fbcfb4/core/src/main/kotlin/com/wangzan101/mika/core/knowledge/ScriptManifest.kt)：脚本发现和 needs | Helix 原可发现 scripts 文件但资源读取只含 references/assets，也无运行入口。补 `skills.scripts`、有界脚本读取、固定快照检查及 `skills.run` 现有运行器入口；运行前真实依赖探测，清单声明不是已满足。Skill 资源复制到该 Job 私有暂存，真实工作区不复制，不将源快照变成可写运行目录。 |
| Mika 取消/进程处理与输出机制 | Helix 已有 process group、持久 Jobs 与 UNKNOWN 恢复。针对实际启动竞态复用 `awaitPtyProcessIdentity` 等待原 unreaped PID 身份，先启动 stdout/stderr drain；仅补桥凭证跨块/截断脱敏。取消后保留已发动作真值，断连/进程死亡不自动重放，不声称 PRoot 提供强隔离。 |

设计变更分别记入 Permissions、Runtime/QuickJS、Terminal/Jobs、Skills 现有 ADR。后台屏与脚本桥/Skill 的主机及设备结果分别保留在忽略的 `build/background-display-2026-11-01/` 和 `build/script-tool-bridge-2026-11-01/`；最终收据才可证明实际通过范围，编译或中间失败不代表设备验收。

### 2026-11-01：现有模型的合成闭环验收

使用已配置的 `Qwen3.8-27B`，只发送自有 CSV、随机颜色/位置界面和随机文本，不读取凭据、不接触真实第三方账号。实际模型分别完成了文件汇总、两次随机后台屏视觉定位、两次 Python/Node/隔离 JS 统一工具桥与固定快照 Skill 文件交付，以及两次停止原 Turn、人工处理后新 Turn 继续。断言检查实际文件、原显示截图帧、画面目标计数、持久子调用与产物登记，不以最终文字或 Turn 完成替代效果。

两次有效视觉任务各耗时约 82 / 95 秒，界面动作各只生效一次。脚本任务首次出现 QuickJS 顶层 `await` 语法试错，纠正后成功；明确同步工具契约后的新任务首个 JS 调用即成功。总耗时和请求数仍受模型规划及运行器准备影响，单次差异不足以归因为整体提速。接管验收确认原 Turn 取消且没有工具动作，后继 Turn 重新读取人工修改后的文件完成交付，旧按钮重复点击被拒绝。

保留两次无效夹具尝试：独立测试进程缺少 Kotlin 可变捕获运行时、Skill 名称不符合父目录契约；修复夹具后复验，不计入有效模型能力结论，也不隐藏已经发生的模型请求成本。样本只证明上述合成场景，不推断全设备或产品成功率。

由实际观察采用的改进是显式人工交回、持久停止事实摘要、同步 QuickJS 调用说明，以及写入前置拒绝的无副作用标记；发布后失败仍保留不确定性。后台屏三个新路由创建/取消/释放及实际原 helper 断连、新 Turn 重连均通过，断连不伪报正常释放或自动重放。实现入口为 [人工交接](../../../app/src/main/kotlin/com/helix/app/chat/HumanHandoff.kt)、[停止事实](../../../feature/conversation/src/main/kotlin/com/helix/feature/conversation/presentation/TurnStopFacts.kt) 和[真实模型夹具](../../../app/src/androidTestDeveloper/kotlin/com/helix/app/eval/ClosedLoopRealModelDeviceTest.kt)。详细构建、失败、请求与设备收据位于忽略目录 `build/real-effect-closeout-2026-11-01/`。浏览器保留、经验转 Skill、真实资料备份与语音不属于本轮。

### 2026-11-02：有界长会话与后台生命周期复验

API 36 合成渲染验收使用 200 轮、400 条消息、1000 次工具调用、25 张不同图像和 200 个产物元数据卡片，覆盖搜索定位、分组折叠、30 次确实移动的历史滚动、返回最新及保存状态恢复。真实 Activity 旋转替换后仍停留原阅读位置。测试发现关闭搜索会重置跟随状态并跳到最新；现将临时暂停跟随与会话／历史窗口替换分开，保存阅读选择，并以真实末尾锚点判断恢复完成。入口为 [ConversationTimeline](../../../feature/conversation/src/main/kotlin/com/helix/feature/conversation/presentation/ConversationTimeline.kt)、[容量夹具](../../../app/src/androidTest/kotlin/com/helix/app/ui/ConversationVolumeDeviceTest.kt) 和[旋转夹具](../../../app/src/androidTest/kotlin/com/helix/app/ui/ConversationVolumeRotationDeviceTest.kt)。指标属于固定模拟器上的有界渲染样本，不代表大数据库冷启动、长期电量或全 OEM 表现。

后台屏十次创建／操作／取消／释放通过；在独立夹具确认原显示上的实际 DOWN 后终止原 helper，结果保留为动作效果未知、连接断开，原显示消失且没有自动重放，随后新身份重连操作通过。主进程终止后的原 Job 两阶段恢复保留 ORPHANED 与未确认状态，不以拒绝 ACK 误判生产故障或清除未知执行载荷。API 29 隔离包验证后台后端明确不可用、没有退到主屏。并发输出、子进程取消与 MCP 协议回归还发现命令证明文件被输出白名单排除；[MCP 桥](../../../app/src/developer/kotlin/com/helix/app/mcp/McpStdioJobBridge.kt) 现将该证明放入明确输出目录，保留校验且不扩大任意输入导出。

逐项日志、实际触控与 kill 记录、构建候选、性能原始值和截图保留在忽略目录 `build/stability-release-2026-11-02/`，以最终收据区分当前通过项、失败夹具与尚未覆盖的边界。产物卡片压力样本只有元数据，不等同实际文件交付。

同轮隔离 Developer Release 使用实际 R8 产物，保持非 debuggable，只替换包名和测试签名，不增加反射保留规则。通过公共原生界面配置后，本地确定性服务驱动真实 Dispatcher：后台屏启动、同显示截图与节点、按钮只生效一次、隔离 JS／Python／Node 调用统一工具，以及已审阅脚本的 Skill 快照预览／安装／启用／执行、文件内容和哈希读回。独立公共框架断言先确认原显示已释放，再清理空闲测试 helper；这不是由 helper 被杀间接证明正常释放。该轮是收缩后执行链验收，不是新增真实模型效果样本，也不等同正式发布签名或 Standard 全部设备矩阵。保留输入法改写标记、SSE 分隔符及跨 Turn 结果解析等失败夹具记录，未把它们记为生产通过。

实际文件预览检查还发现，最近 Turn 在终态后仍被服务保留，回答旁入口却按“它是 activeTurn”无条件隐藏产物。现仅在该 Turn 确实未终止时隐藏，容量夹具也保留最新已完成 Turn，避免只用 `activeTurn = null` 的样本漏掉此问题。入口随后实测暴露错误接线：点击打开了回答文字，而非文件；现由 `TurnArtifactEntry` 直接复用已有 `TaskArtifactsDialog`，文字结果入口保持独立。文件所有权、哈希与可用性仍走既有产物查询和预览，不从模型文字生成假附件。

最终收缩包的原生文件列表、实际内容预览、保留会话的进程重启和按钮仍仅生效一次均通过；最终日常 Debug 上增强后的组件测试也直接检查原 Turn 文件、排除其他所有者文件并打开内容。三次保数据进程冷启动的 `am start -W` TotalTime 为 546／503／520 毫秒，样本是小型合成会话，不是上述大历史数据库或首帧完全绘制基准。空间不足的安装没有算作成功：经 Owner 对具体包批准，仅临时移除测试包和隔离包的代码并保留数据，更新后两包均已恢复；日常包未卸载或清数据。原始失败、最终门禁、不同候选的证据继承和安装字节读回统一记入本轮最终收据。

### 2026-11-02：真实设备与持久化历史

本次 Owner 明确授权后才使用 USB 连接的 ONEPLUS A6013（Android 14／API 34）；原 Standard 包保持不变，独立 Advanced 测试包使用 0.0.9／code 1。官方 Shizuku 经来源、版本和 APK 校验安装，并按具体批准通过现有 USB ADB 启动和授予该包访问，没有新增无线配对、Root 或无障碍。基础启动／文件预览三项、后台屏同显示操作／截图／取消释放通过；实际确认 DOWN 后终止原 helper 的用例保留 UNKNOWN 与原显示释放，新 helper 重连也通过，不自动重放。

[持久化夹具](../../../app/src/androidTest/kotlin/com/helix/app/ui/PersistentConversationData.kt) 在真实 Room 中生成一个含 2,000 轮、16,000 条消息、10,000 条工具记录和 100 张真实 PNG 的合成会话。前一次无效夹具的记录保留，因此实际数据库至少包含双倍消息／工具数量；无效样本不计为通过。有效数据暴露 [ToolCallDao](../../../infrastructure/storage/src/main/kotlin/com/helix/core/storage/dao/ToolCallDao.kt) 的查询缺陷：已结束但无模型调用 ID 的旧工具行越过消息窗口，挤占最新回答尾部。现将这些历史行绑定原 Turn 的消息窗口，同时保留非终态、待核查和完全无消息的直接调用入口；真实 Room 两项及原有 SQLite 边界测试覆盖该区别，没有改执行权限、容量或旧记录。

最终候选在同一已落盘会话上重启后通过三页历史、十次跨历史搜索定位、真实图像解码和返回最新。三次独立进程冷启动的 Activity 显示时间为 1306／1297／1295 ms；原生内容就绪采样上界为 9112／9096／9056 ms，包含 UIAutomator 开销，不是 `reportFullyDrawn` 指标。首轮查询修复候选另完成 20 分 23 秒、39 次 Home／恢复，其中一次后台停留五分钟，单一进程、FD 稳定为 150，线程稳定段为 38；PSS 热身后约 139 MB。该长窗口早于最终保留无消息直接调用的补充条件，最终候选重新执行持久化／查询和断连重绑定测试；不能将整个长窗口称为最终 APK 新跑结果。USB 供电且临时保持亮屏，原设置已恢复，不外推 Doze、耗电、24 小时或其他 OEM。

按单独批准，真机指定合成目录的系统 SAF 选择／持久读写、撤销与重新授权通过；All Files 原生写入后从同一 SAF 文件读回也通过，未复制执行。但该 Provider 未提供可验证的原目录 FD，`verifiedLocalDirectory` 返回不可用，正确保留 SAF，不将 URI 字符串拼成路径或声称 SAF 自动转真实路径。指定目录和原始合成源文件保留；夹具结束释放自身 SAF 授权，All Files 保持已批准状态。

本阶段原始失败、各候选、三次冷启动、长窗口和逐项测试日志位于忽略目录 `build/device-persistence-2026-11-02/`。其中 `phase2-installed-identity.json` 记录真机和日常模拟器实际 APK 字节读回；`physical-saf-final.log`、`physical-storage-window-final.log`、`physical-persistent-final.log` 与 `physical-background-rebind-final.log` 是不同效果的证据，不能互相替代。浏览器、备份恢复和经验 Skill 是后续独立阶段，不由本节宣称完成。

### 2026-11-02：浏览器、隔离恢复与 Skill 入口

浏览器任务新标签保留用户当前选择；未选中页面使用 Activity 的真实窗口尺寸建立独立视口。API36 与上述真机实际读取未选中页面的预期像素，并经真实 Activity 重建确认仅保留逻辑 URL／来源、不自动加载或重放；UI 提供返回原会话及明确的重新打开地址入口。来源只用于导航，不代替原会话或目标范围授权。契约与入口见 [Browser ADR](../../adr/platform/002-browser-lifecycle.md)；进程死亡后的完整标签持久恢复没有由此新增。

全新备份专用包实际导出合成资料、卸载／重装、排队恢复并在两个不同冷进程中核验；正文、附件哈希、专家、记忆、工作区草稿和新本机别名下的加密假凭据均恢复，未知调用保留、账号会话不恢复，也不重放工具。草稿处于新的恢复内容根，旧路径明确不可用；初次夹具误查旧路径的失败保留并按现行身份契约纠正，没有添加兼容映射或改写恢复实现。范围与本机文件流／系统选择器的区别见 [备份 ADR](../../adr/workspace/008-content-backup.md)。

最终真机回归另发现，异步附属入口出现会增加回答行高，却没有进入时间线内容版本，返回最新后可能丢失真实末尾锚点。撤下任务草稿入口后仍保留已验证的附属入口跟随修复；异步产物入口在读出变化时通知现有跟随版本。原手工任务草稿方案已由 Owner 后续的 Agent 创建／导入／公开源安装方向替换，其旧 UI 日志仅作为历史过程记录，不作为新三入口方案的验收证据。没有添加每帧尺寸轮询，也不把用户展开详情／编辑草稿当作强制跳到底部。原持久化末尾断言未改，修复后真机大历史、浏览器像素、后台屏、草稿四项及 API36 草稿、五项跟随阅读、容量和实际旋转八项通过。截图只保留本地，原失败及最后源码门禁、安装身份以忽略目录 `build/task-skill-draft-2026-11-02/` 的对应候选收据为准；替换 Skill 方向后的最终代码需独立门禁与安装读回。

本轮没有新增真实模型样本或声称未来 Skill 的业务成功率；之前有界模型证据与本轮确定性正确性验收分开。Release/R8 主机构建、测试签名收缩包设备证据和 Debug／真机证据也分别记账，不外推正式发布签名、全 OEM、真实账号迁移或长期耗电。


Owner 随后明确以 Agent 直接创建、导入已有内容及公开来源下载安装替换任务草稿按钮。当前入口复用正常文件工具、`skills.preview/install/list/enable/read/read_resource/run`：源路径在原请求中固定，支持已授权工作区的普通目录；单 Skill ZIP 的仓库修订外层名不再被误当作 Skill 名，内容仍按实际 manifest 与 tree hash 固定。统一 Dispatcher 的源读取效果区分工作区与外部，安装不因此产生读取权限，安装也不自动执行脚本。已有管理编辑器仅作可选入口，不是 Agent 创建前置步骤。

API36 使用自有合成文件、本地 ZIP 和回环 HTTP ZIP，实际走过创建／下载、固定 hash 安装、立即发现、会话启用、正文及资源读取；安装阶段没有执行脚本，显式 `skills.run` 后才生成预期文件。该样本验证公开下载链路和仓库 ZIP 布局，不代表实际访问或信任任何第三方 GitHub 仓库，也不是新真实模型样本。原始夹具状态大小写失败、浏览器异步提交等待修正、最终日志、源码输入和安装读回保留在忽略目录 `build/skill-agent-paths-2026-11-02/`；真机最终更新及预置选择后的持久会话冷恢复仍为 pending；Owner 随后要求暂停全部真机操作，模拟器优先，不以先前候选替代。


### 2026-11-02：模拟器优先补验

Owner 暂停全部真机操作后，仅在 Helix_API_36 补验。原模拟器没有本轮持久化夹具，明确建立新的 2,000 轮／16,000 条消息／10,000 条工具记录／100 张真实 PNG 的 Room 样本；prepare 选定后强停，在不同 PID 中恢复同一会话，三页历史、十次搜索和图像预览通过。该样本不修复或替代真机原会话待验；准备选定发生在强停之前，verify 不自行打开目标或重新播种。

现有 Provider 就绪目录上的 Qwen3.8-27B 完成一个约 119 秒、13 次模型请求、16 次工具调用的真实合成样本：Agent 在正常会话工作区直接创建 Skill，校验、固定 hash 安装、发现与会话启用，读取正文及参考资料后生成结果文件并读回；独立随机输入的内容检查通过。最初夹具只识别独立 Key HTTPS 配置的假设被纠正，未新增或提取凭据。单个成功样本不是成功率、其他模型表现或 GitHub 来源质量的结论；公开下载安装继续使用前节自有 HTTP ZIP，不执行未知仓库代码。

最终源码的隔离 Developer R8 使用测试签名且保持非 debuggable，由独立测试 APK 仅通过公共 UI 和框架操作，实际检查用户手动页选择不被任务页占用、旋转后明确重新打开、返回原会话、Skill 安装和读取／操作、对应产物预览及进程重启后的结果恢复。确定性服务以独立 runId 隔离旧 Turn／旧回答，记录实际工具结果；旧夹具折叠菜单遗漏和未建输出目录的失败保留，未放宽产品规则。没有使用目标 App 私有 API、反射或 Root；Standard Release 主机构建与本次 Developer 设备结果分别记账，不等同正式发布签名。

同一模拟器另通过真实系统选择器的合成目录持久授权、撤销、重新授权与受限根拒绝，结束释放本轮授权；它补充目录生命周期，不能被称为重新执行完整系统选择器备份／换机恢复。前节隔离备份真实卸载／重装的证据继续按原候选继承。持久化与生命周期路径未变的 Debug 另实跑 420 秒、17 次 Home／恢复，包含 120 秒实际 Asleep 停留；单一 PID，热身后 FD 149–150、线程 36–38，临时电源设置已恢复。窗口与其后导入暂存目录补修的候选身份分别记录；供电模拟器不代表真机锁屏、Doze、长期耗电或全 OEM。新的日志、失败、候选和安装字节读回位于忽略目录 `build/emulator-closeout-2026-11-02/`；所有真机后续操作保持 Owner 暂停状态。


导入收尾边界测试还复现了合法 Skill 名 `incoming` 与内部暂存目录重名：目录、ZIP 归一化可能触发 `FileAlreadyExistsException`。内部暂存改为合法 Skill 名不可能使用的点前缀，目录、平铺 ZIP、仓库修订 ZIP 的相同内容得到相同快照并可幂等提交；原越界、链接、重复项与体积边界测试保留。该修复不改变已安装内容身份、版本或授权，也不要求保留旧暂存格式；最终 Debug 与隔离 R8 接入复验按新候选独立记录。


### 2026-11-03：真实 Skill 补样本失败与离线停止修复

Owner 明确批准自建内置网关接收本次正常 Agent 上下文后，API36 最终 Debug 的补样本未通过：原始 Turn 有 13 条模型调用、19 次工具调用。模型先写出缺少 YAML frontmatter 的 SKILL.md，连续四次预览仅收到泛化错误，补齐后同源预览成功；未完成安装、`skills.run` 和独立文件结果。加载器原本有准确格式错误，但导入层包装异常后工具未识别；现在只提取已知校验异常的有界信息，其他 I/O 错误仍隐藏内部路径，实际工具执行器单测覆盖此差异。

测试收尾未留下有效停止意图，随后诊断启动触发产品既有的只读恢复检查，额外出现两条模型尝试记录（一条完成、一条未结算）；客户端记录不能证明未结算请求的网关实际消费，也没有证据将其称为原写操作重放。通过测试 Runner 的现有持有启动模式，在账户、AppContainer 和恢复调度之前直接打开 Room，仅为该合成任务及后继写停止意图；不清数据、不删除失败历史、不修改用户 provider 或记忆配置。测试专用发送前门禁按同一持久账本限制目的地、模型、输出预算、请求数和时间；取消作用域中的停止、证据保存与恢复各自有 finally 保证。隔离假 provider 回归覆盖超时后证据失败、停止持久化、新 PID 恢复零后继请求及显式新用户 Turn 仍可入场。该离线修复不是新的真实模型通过样本；原始失败和修复日志分别保存在忽略目录 `build/real-model-final-2026-11-03/` 与 `build/skill-timeout-repair-2026-11-03/`。真机继续暂停。


Owner 再次授权最多 24 次请求、240 秒的真实补测后，准备阶段先在 Composer 异步配置链超时：该会话已为 ACT，但预算仍是默认值，发送账本为零且关闭。真实模型夹具随后与隔离假 provider 共用仅作用于自有会话的持久配置准备入口；模式与预算读回后才入场，不把该夹具当作普通 UI 配置链验收。相关编译、静态及假 provider 新 PID 零恢复检查通过。

修复准备入口后的唯一实际补样本仍未完成：账本准入 12 次，11 条模型调用完成、1 条取消，17 次工具调用；模型创建了 manifest、参考资料和 Python 脚本，合法预览成功，但尚未安装、启用或运行。一次资源读取遗漏 `references/` 前缀，被准确拒绝后模型自行修正；没有重复出现缺 YAML 头的预览错误。整体 240 秒窗口中约 53 秒发生在 Turn 创建之前；模型调用生命周期中有约 35 秒与 56 秒的长区间，不能将这些客户端区间直接当作服务端推理耗时。到期后 Turn 为 CANCELLED、预算关闭；正常 Application 启动及 Activity 重建后仍只有原 12 条调用、无后继 Turn，随后保持停止。失败不是新模型成功率证据，没有人工替模型完成文件结果，也没有继续多 case 消耗。当前需定位准备／入场延迟及文档发现开销，再由 Owner 决定下一次补测；不自动加时。原始准备失败、实际模型失败、关闭账本与重启审计位于忽略目录 `build/real-model-supplement-2026-11-03/`，真机未操作。


### 2026-11-03：后台任务投影的入场阻塞与帮助资源路径

只用内存 WireClient 的正常容器诊断，将配置、会话切换、提交至 Room、Room 入场和上下文构建分段计时，没有追加真实模型请求。约 2027 个候选任务的大库上，未采样首个本地任务为 14.903 秒，其中提交至 Room 之前 14.004 秒、上下文构建 0.369 秒。采样运行在同一路径出现 53.808 秒入场等待，方法链明确为 `SessionWorkScheduler.requestDrain → refreshScreen/refreshBackgroundTasks → TaskDashboardProjection → BackgroundTaskQuery → ToolCallDao.listByTurn`，同时存在逐 Turn 会话/Goal 查询及重复投影刷新。采样本身有开销，不能拿它与未采样结果直接计算加速比；原真实调用的 35/56 秒生命周期也仍不能当作服务端推理时间。

后台投影现在按原候选集合批量读取会话/Goal 标量和审批 `EXISTS`，避免逐项加载工具参数；500 仅为 SQLite 分批绑定大小，不裁剪任务数量。沿用同一事务、排序、领取/审批/停止事实与原授权路径；排队刷新复用 `refreshScreen` 已有的后台刷新。保留原库更新后，候选任务为 2030，未采样首个本地任务为 5.451 秒、入场前等待 4.054 秒；同进程后两次为 1.293/1.095 秒，基线后两次为 1.117/0.960 秒。结果证明本次首任务瓶颈改善，不代表暖态全面提速、所有规模或 OEM 性能。

帮助 manifest 和中英文索引此前给相对文件目录的路径，实际资源 API 却要求 Skill 根路径；现统一给出含 `references/` 的可直接使用值，回归直接读取每个公开路径，不在测试端偷偷补前缀。预览说明明确返回安装 hash，发现说明区分草稿与已注册快照；原搜索词的八项结果现在包含 `skills.preview`。没有强制模型执行固定业务步骤、自动安装或运行脚本。本轮完整双渠道 Debug/Release R8 门禁、10 项 Room/帮助设备回归及假 provider 停止后新 PID 零恢复检查通过；日常 API36 保数据更新，真机仍暂停。数据、候选与日志保存在忽略目录 `build/skill-admission-profile-2026-11-03/`。本轮本地诊断不以假 provider 替代真实模型结果；后续授权与实际复验见下。

### 2026-11-03：按需模型调用约定与真实 Skill 闭环通过

Owner 明确取消开发测试的人为请求次数和时长上限，允许按需使用项目已配置模型服务；该约定写入项目 `AGENTS.md`，不创建自定义 rule，也不改变产品权限或泄露凭据。真实模型夹具取消专项次数、时长和工具总数限制；发送前仍校验固定服务与本任务身份，结束后关闭持久门禁并停止任务。仅离线超时回归保留有界模式，用于验证超时、停止和进程恢复边界；新增回归确认无界模式跨过旧阈值后仍可发送、关闭后仍拒绝。

API36 日常最终 Debug 上，内置 `builtin-helix-qwen`／`Qwen3.8-27B` 经已有自建网关完成一个真实样本：12 次模型请求、18 次工具调用，case 含收尾约 164.5 秒。模型自行创建 manifest、参考和 Python 脚本，完成 `skills.preview → install → enable → list → read/read_resource → run`，工具均完成；模型读取结果，测试另行比较工作区输出与本次 52 字节 ASCII 随机文本全文，且执行产物 hash 与输入一致。该样本没有覆盖二进制或 CRLF 字节保真。没有人工替模型补文件或执行脚本；这是一个闭环成功样本，不能外推整体成功率、任意公开仓库安装或真机表现。原始工具轨迹、实际测试 APK 身份、运行与停止审计保存在忽略目录 `build/skill-model-unbounded-2026-11-03/`；该阶段真机暂停。

### 2026-11-03：真机原会话收尾、空恢复排队与 Standard R8

Owner 重新授权真机测试后，实际设备确认为 ONEPLUS A6013、Android 14/API34；同签名保数据更新 Developer，原合成会话未重建。选定原会话后强停、新 PID 恢复，2000 个 Turn、16000 条消息、10000 次工具记录与 100 张图片保持一致；该阶段候选三页加载约 1.8 秒，十次搜索和图片预览约 7.6 秒，测试过程图片 PSS 峰值约 325 MiB。另三次 `am start -W` Activity 冷启动为 1302/1309/1306 毫秒；内容出现上界包含 UIAutomator 抓取耗时，不当作 Android fully-drawn 或黑屏时长。后台屏创建、输入、截图、取消释放，以及既有批准目录的系统 SAF 撤销/再授权与原位路径检查在该阶段候选上通过，没有新增系统授权。

测试侧观察排队投影回调进一步解释了剩余入场等待：启动恢复为全部历史未归档会话排空队列，54 次回调即使没有待处理输入仍刷新投影，其中 4190 毫秒落在首任务 4636 毫秒的入场前等待内。现按 Room 的 `PENDING/NEEDS_ATTENTION` 输入选取未归档会话；调度器仍重读停止、输入状态和原执行身份，不自动恢复旧 Goal 激活。保留同库未采样本地任务从 6058 毫秒降至 1717 毫秒，入场前等待从 4636 降至 87 毫秒；暖态没有一致提速。此结果解释本次冷等待，不能倒推原真实样本 53 秒的全部构成；独立查询耗时也不相加冒充完整路径。21 项恢复/停止/会话/后台存储回归通过，新查询覆盖重复、停驻、失败、撤回与归档状态；生命周期夹具显式构造未绑定来源，不再假定设备没有保存默认模型。

Standard/consumer 非 debuggable R8 隔离包在 API36 完成同 ID、同本地测试签名覆盖安装；独立只读检查确认原合成会话和产物保留且未新增 provider POST，不把重新创建配置算作保留；公共 UI 验证浏览器手动页/任务页归属、文本 Skill 安装启用与读取、实际指令文件结果、产物预览、旋转及进程恢复，并通过三项基础入口检查。驱动是本地确定性 provider，不是新的真实模型成功率样本。按现有渠道契约 consumer 不含 Mobile Use/PRoot，不将 Developer 的后台屏或 Python `skills.run` 归入 Standard 验收；公共夹具已移除该误用，并优先匹配操作的 contentDescription，避免把同名会话标题当按钮。签名使用已有 debug key，仅隔离包升级通过，不代表正式发行证书或商店验收。

模拟器原合成加密备份恢复到真机全新隔离包，连续两次冷恢复确认会话、附件与 hash、专家/记忆/Skill 文本和假凭据正确，账户会话不恢复、运行中动作保持未知且未重放；没有覆盖日常 profile，也没有授予隔离包所有文件访问。证据继承原合成归档，当前恢复执行与候选独立记录。持续窗口的旧裸 `am start` 试验因逐轮新建 Activity，不适合作为正常恢复内存/FD 基准；原始数据保留，改用 Launcher Intent 并逐轮核对单 Activity。SAF 夹具也补齐关闭系统选择器的后置断言，避免遗留外部 Activity 干扰后续恢复。所有候选、成功/失败日志和窗口条件在忽略目录 `build/device-followup-2026-11-03/`，不以短时 USB 供电观察代替续航、Doze 或全部 OEM 结论。

启动优化候选采用真实 Launcher Intent 的有效窗口中，真机约 354.8 秒/10 次循环、模拟器约 431.7 秒/16 次循环，均逐轮核验单 Activity；各含一次 120 秒后台停留，模拟器该次实际熄屏。末段 FD 均为 150，PSS 分别约 136.5/130.9 MiB，没有旧脚本的逐 Activity 累加现象，但不据此宣称长期无泄漏。随后真机另作 300 秒熄屏/Dozing 观察，Owner 正常手动解锁后同 PID 恢复原合成答案；USB/ADB 连接、未强制系统 Doze，等待解锁计入的总时间不能当性能指标。原电源设置均恢复；该锁屏窗口检验闲置持久会话，不代表活动长 Job 在深度休眠或断电下的验收。

Owner 随后明确指出真机工具入口是被键盘遮挡，并非仅缺少滚动。真实系统 IME 公共测试在旧 R8 上确认打开导航后键盘仍存在；生产修复在紧凑模态抽屉进入打开状态时清理焦点并隐藏 IME，常驻侧栏不主动打断输入。真机 API34 与模拟器 API36 的新 R8 均通过同一正向测试，含工具可达和返回后草稿文字保留。公共夹具另补视口变化等待及产物项滚入可见区域，保留原失败。最终两设备各完整通过浏览器/文本 Skill/产物/旋转/重启旅程、三项基础入口和同签名覆盖后只读检查；各自本地确定性 provider 的 POST 计数在覆盖检查前后保持 19，未续跑旧任务。上述较早的历史/后台屏/文件/备份/窗口证据绑定其实际启动优化候选，继承未改动路径；新键盘路径与最终 APK 身份另在 `navigation-ime/` 收据区分，不把旧窗口冒充新 UI 候选重测。完整双渠道 Debug/Release R8 主机门禁通过；隔离 ID 与现有测试签名仍不代表正式分发身份。

### 2026-11-03：默认 UI 文案与跨模型回复的工具分组

Owner 指出问题不限会话工具：新功能反复附带自明说明、泛化提醒和重复状态。根 AGENTS.md 现将默认文案约束放在全局规则，现有 acceptance-closeout Skill 补设计、实现和验收时的逐条判断与正反例；不另建专项技能。此次代表性清理覆盖工具卡、结果检查器、停止摘要、插件选择、模型目录/配置和记忆页，保留具体错误、权限后果、外发记忆说明和未知效果；未逐页审计整个产品。

工具分组此前以 ModelCall 消息序号为边界，常见每次回复只调用一个工具的轨迹无法合并。现允许同一 Turn 的相邻已完成调用跨模型回复折叠，仍保留可见消息与需关注状态，展开顺序和原调用身份不变；常规完成不再逐条附加业务核验标语。真实 Qwen 自有合成历史的只读复核与本地确定性 Engine/Dispatcher 事件验收分开记录，后者不算新真实模型样本。原始日志、截图和候选身份在忽略目录 `build/conversation-noise-2026-11-03/`。

同一段 18 次工具调用的真实 Qwen 历史，在旧候选为 4 个折叠组（2/2/3/4 次）加 7 条单列，在新候选为 3 个折叠组（4/5/5 次）加 4 条单列；保留模型可见进度消息，因此不是整轮强行合成一个组。另一个不联网的实际 Engine/Dispatcher 合成流程验证 4 次与 2 次连续完成调用折叠，运行、审批、失败和 NEEDS_REVIEW 保持可见，搜索展开定位和历史重开通过；9 次内存模型响应不能算外部模型样本。既有 19 项布局/审批回归、完整双渠道 Debug/Release R8 主机门禁、本地化与 Skill 校验通过，日常 API36 覆盖安装后读回一致；本轮未新增真机验收。初次滚动夹具错误及截图跟随异步观察后继的无效取景保留，最终截图在后继结束后重新定位工具区域。

### 2026-11-04：后台屏可见预览补齐

重新核查 Mika commit `0091d2d67b541a417df913a23fefbb8e3a04e4a7` 的 [AgentPip](https://github.com/wangzan101/Mika/blob/0091d2d67b541a417df913a23fefbb8e3a04e4a7/app/src/main/java/com/wangzan101/mika/ui/AgentPip.kt) 与 [AgentDisplay](https://github.com/wangzan101/Mika/blob/0091d2d67b541a417df913a23fefbb8e3a04e4a7/app/src/main/java/com/wangzan101/mika/cap/phone/display/AgentDisplay.kt)：聊天页可拖动、点开查看的只读预览与后台 VirtualDisplay 是两层实现。Helix 此前仅有后台执行、生命周期文字与工具截图，不能据此声称已有实时小窗。本次独立实现聊天页预览，复用 Shell 帧缓存、原 owner 和授权校验；未复制参考项目代码或引入其运行时。权限及生命周期见 [ADR](../../adr/permissions/001-session-authorization.md#decision-history--2026-11-04聊天页后台屏实时预览)。设备效果以本轮候选实测为准，不继承此前无预览的酷安样本作为该 UI 验收。


### 2026-11-04：手机操作工具契约与后台屏生命周期复核

对照 Mika `0091d2d67b541a417df913a23fefbb8e3a04e4a7`，本轮采用其观察、执行、效果分层思路，复用 Helix 既有工具发现、脚本桥和 `skills.run`，不复制另一套 Agent 循环或短序列执行器。此前真实酷安轨迹未出现 `tools.search`，不足以认定发现缓存丢失；多屏收集与新的批操作工具暂未引入，未声称性能收益。

实际差距是工具可见契约不一致：`ui.find(scrollable=true)` 已有实现，离屏提示却否认该参数；现与 schema/解析/帮助统一，并补可编辑过滤。节点公布实际支持的滚动方向，工具支持明确上下左右，避免将泛化 forward 当作垂直滚动而切换横向分页。点击前的同窗口内容变化与遮挡分别反馈，不再统称目标切换；DOWN 后失去确认仍为 UNKNOWN，不自动重放。成功派发保留未验证效果的结构化事实，但移除重复说明。

Android 公开 VirtualDisplay 不设置 DESTROY_CONTENT_ON_REMOVAL 时，正常释放可将标准任务迁回主屏；迁移也可能改变焦点。实现检查迁移与恢复原焦点，边界见 [授权 ADR](../../adr/permissions/001-session-authorization.md#decision-history--2026-11-04后台屏初始模式与任务保留)。首次设备夹具发现独立测试 Activity 引用宿主 Kotlin 运行库导致迁移时崩溃，改用 Java 独立 Activity；第二次滚动失败中系统独立记录 DOWN 接受、scrollY=220，说明仅比较首行裁剪后 top 的断言不能判定滚动效果。原失败保留，最终使用可见行变化、截图及原任务重新打开验证，不将派发成功等同业务效果。最终实测与候选记录于忽略目录 `build/mobile-use-followup-2026-11-04/`。


本轮最终 API36 日常 Debug 的三个设备测试通过，含实际可见行从 0 变为 3、同屏输入/截图、取消后释放、预览紧凑标题和中英文插件名。首个真实 Qwen 样本在酷安启动时遇 Android 的 FocusEvent ANR；测试夹具仅授权酷安，系统弹窗被正确拒绝，但设备观察错误采用了默认“Shizuku 不可用”。现无帧的范围拒绝也保留真实后端就绪状态，仍不签发操作凭据。保留该失败，不以模型完成 Turn 代替任务成功。

修正后的真实样本沿用 Owner 日常已保存的范围，不由夹具另行缩小：约 457 秒、32 次模型请求、37 次工具调用，在同一后台显示完成首页截图、实际纵向内容变化、搜索“文件管理器”并读取前三项、返回首页及截图复核。包含一次过时 token 拒绝，模型重新查找后恢复；没有 UNKNOWN 副作用。正常释放后同一酷安任务迁回主屏并保持同一 PID，从最近任务卡重新打开成功；迁移后首次卡片缩略图为空白，不承诺缩略图即时刷新。模型最终额外列出的热度/讨论数与搜索截图不一致，未计入已验证结果，也不能把整段生成文案都视为准确。前一 ANR 样本的进程后来被系统按 `user request after error` 结束并重建，不能称原进程持续存活；旧虚拟屏遗留的损坏任务卡也不冒充已修复。标准任务迁移依据 [AOSP DisplayManager 的公开显示移除语义](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/display/DisplayManager.java)，不是 force-stop 后重新 launch。

完整双渠道 Debug/Release R8 主机门禁与最终候选设备复验通过；本轮未新增真机、其他 API/OEM 或异常 helper 死亡后的保留验收，没有将当前单例与旧样本不同流程比较为性能提升。截图、原始失败、实际模型轨迹、最终 APK 身份及分项边界见本轮忽略收据目录。


### 2026-11-04：酷安 FocusEvent ANR 的进一步归因

系统 DropBox 实际仍保存原 ANR 线程栈，纠正前次“没有保存主线程堆栈”的取证结论：酷安主线程在 `HardwareRenderer.setStopped → RenderProxy::setStopped → future<void>::get` 等待；RenderThread 在 `libEGL_emulation → QemuPipeStream::commitBufferAndReadFully → glGetProgramiv_enc` 的着色器程序链接查询路径。该时段 guest CPU 总使用约 94%，内存 PSI some/full 的 avg10 约 33.97/10.40，酷安 VmSwap 约 482 MiB。由此确认当时等待链进入模拟器图形通道并伴随资源压力，不能再笼统称 Mobile Use 连接故障；仍不能仅据单份栈证明宿主 GPU 永久挂死、Gradle 是原因，或排除应用渲染工作量的影响。[Android ANR 诊断指南](https://developer.android.com/topic/performance/anrs/diagnose-and-fix-anrs)同样要求区分应用阻塞、系统负载和 GPU hang。

本轮以独立 opt-in 设备夹具走原 Dispatcher/授权/后台屏启动、观察、截图与持久取消释放，不调用模型、不操作账号，不修改生产焦点或超时策略。原失败时序中 ANR 在启动后、正常显示释放前，线程栈没有呈现等待 Helix Binder 的主线程链；这不构成对全部 Helix/图形驱动路径的排除证明。冷/热态与主屏对照、压力/窗口/调度记录及精确候选归属保存在忽略目录 `build/mobile-use-anr-2026-11-04/`。采样器因冷启动瞬间找不到 PID 的一次中断单独保留，不作为产品 ANR 或完整轨迹；完整复验与未确认边界以最终收据为准。


进一步的完整冷启动调度样本未复现系统 ANR，却实际命中 Helix `PrivilegedNavigationExecution` 的独立 10 秒等待，返回 `ACTION_OUTCOME_UNKNOWN`，测试如实失败并停止，没有重放。此前约 11.5 秒的冷态工具调用和该次失败不能与主屏直接 ADB 启动耗时当作同口径性能比较。按 [Android am 文档](https://developer.android.com/tools/adb#am)，`-W` 等待启动完成；它与现有工具只确认派发、后续另行观察的语义不一致。此次最小产品修复分离系统启动确认与首帧等待，保留明确错误否定、原 deadline/取消、原显示/组件绑定及 UNKNOWN 边界；不将其当作历史 GPU 通道 ANR 的修复。具体复验结果与剩余风险见本轮收据。


修复后的 API36 Debug 通过三轮原 Dispatcher 后台启动、观察截图与正常释放。冷态系统 START 到命令退出约 91 毫秒，首帧约 10.2 秒后出现；整个工具调用还包含调度/连接等开销，不能把该 91 毫秒称为完整启动耗时。另一次冷启动真实 Qwen 样本中，该间隔约 103 毫秒、首帧约 13.3 秒；模型经历暂无可用界面、等待、节点恢复后，完成浏览/滚动/搜索/返回首页，共 45 次模型请求、47 次工具调用，只有一次启动，UNKNOWN 为 0。该约 699 秒样本包含模型及工具生命周期，不是服务端推理耗时或成功率结论。

本轮系统 DropBox 没有新增酷安 ANR；历史图形通道阻塞仍未复现，不能宣称根治。主屏直接 ADB 对照、较早冷/热态样本与新候选分别记账；长轨迹的环形缓冲缺失起点、一次 PID 采样中断和 Shell 无权读取实时 PSI 的缺项均保留，不以空值当零压力。补齐新的有界起点轨迹后确认了独立等待窗口问题。最终双渠道 Debug 主机门禁与设备候选读回通过；本轮没有真机、其他 OEM/API 或新的 Release/R8 验收，未更改模拟器 GPU/内存设置、系统 ANR 超时或系统授权。

### 2026-11-04：有序收尾的断连与历史耗时复核

最新整合候选在 API 36 日常模拟器重新执行正常释放及原 helper 的真实进程死亡。异常死亡后显示消失、宿主状态为 `CONNECTION_LOST`、旧预览不再返回图像，原合成应用任务身份保留；系统同时将该任务迁入主屏并抢占焦点。接着请求后台启动，被 `MAIN_DISPLAY_IN_USE` 如实拒绝而没有静默搬走主屏应用。回到 Home 后的新连接执行成功。正常释放的焦点恢复实现不覆盖已死亡 helper 的清理，该基线的异常死亡后主屏焦点恢复仍是缺口，不以资源释放或预览消失冒充全体验通过。另一次用旧观察滚动的尝试在派发前被 `TARGET_CHANGED` 拒绝，原失败留存，不把其当成断连已经发生。

设备合成操作与 Compose 截图测试分别运行；合批曾触发两个 UiAutomation 持有方的连接冲突，拆批后的界面回归单独记账。测试不清除日常数据，不触碰 Shizuku 主服务或其他应用进程。

对既有酷安模型任务只读提取 Room 的模型调用时间及 Dispatcher 审计，没有重跑账号或请求。该历史 Turn 约 696.9 秒，45 个模型调用的客户端区间合计约 645.4 秒（92.6%），47 个工具派发区间合计约 29.6 秒、时间并集约 27.6 秒（4.0%），未归属区间约 23.8 秒。模型区间中位数约 7.05 秒、最长约 186.4 秒；单次 `ui.launch` 派发约 0.437 秒，三次 `ui.snapshot` 合计约 2.22 秒，显式 `ui.wait` 为 1 秒。模型区间含客户端请求、网络及流式生成，不能拆称纯推理/等待；页面首帧就绪也没有独立计时。该单例支持优先检查模型回合与等待来源，不证明任何新候选提速。

先前生成文案额外列出的热度/讨论数仍未被观察证据支持。本轮插件指引要求逐项核对所报名称、数字和单位、保留原文并省略未请求的数字；这是结果核验指导，不是 Harness 能保证任意最终自然语言事实正确的新能力。历史 ANR 的图形通道等待与资源压力结论保留，深层原因未证实，不宣称本轮修复了该历史 ANR。原始分项日志、失败和时间摘要保留于 ignored 的 `build/ordered-closeout-*` 证据中。


后续修复由原资源所有者记录有界任务检查点，在原 helper Binder 死亡后最多五秒内尝试一次焦点清理；重新核验原授权、解锁、原显示已消失及当前任务顺序，仅恢复仍适用的原主屏任务，不重放旧工具或创建后台屏。五项主机测试覆盖死亡、正常释放、过时检查点、权限/锁屏/超时与资源仍存在时拒绝。API36 的正常释放、真实 helper 死亡及新连接三项设备复验通过；异常死亡后独立 ADB 核验 Home 原任务重新成为前台，清理日志为 `FOCUS_RESTORED`，原应用任务仍保留。原宿主进程必须存活；一次夹具提前结束导致 instrumentation 强停宿主的失败保留，不能将宿主与 helper 同时死亡误判为 helper 单独死亡。最终夹具使用有界握手保持宿主存活，并在独立焦点核验后退出。证据为 ignored 的 `build/ordered-closeout-focus-recovery-ready-host/`。该结果不覆盖宿主死亡、全部 OEM/并行焦点竞争，也不修复历史空白最近任务缩略图或 GPU 通道 ANR。

### 2026-11-04：推送后的数字读回与双所有者退出边界

在日常 API36 模拟器以配置的真实 Qwen 模型读取自有合成页面，数字仅存在于页面，不放进请求。首个主屏单应用范围样本读对三个指标，但截图不可用，且额外复述未请求日期；严格图像消费断言如实失败。没有扩大为全屏截图权限以绕过范围限制。插件指引补充区分节点与像素证据，以及不要在解释“未请求”时重复额外字段；这不保证任意模型回复都正确。

随后同一测试应用在已授权后台显示中完成实际截图，模型请求审计确认图像进入真实请求，最终仅输出“下载：12.3万次、评论：47条、评分：4.8分”。该样本约 56 秒、七次模型请求；截图像素另经人工查看核对。只验证这三个受控字段，不冒充历史酷安内容准确性复验或新的总体成功率。正反样本分别保留在 ignored 的 `build/post-push-number-readback/` 与 `build/post-push-number-readback-visual/`。

另一独立夹具在原 Dispatcher 完成十四次操作后，于一次有界信号序列终止原宿主和原 helper，两个信号均成功。原后台显示消失，原测试应用任务保留并迁入主屏，但抢占 Home 焦点；宿主也已退出，现有单 helper 死亡后的宿主清理不覆盖这个边界。重启后旧 Turn 为 `INTERRUPTED`，十四份原派发审计各一份且结束时间均早于死亡前取证，没有新增模型或工具重放，旧显示和投影未复活。仅清理该合成会话，原范围和显示偏好逐字节恢复。首次恢复检查误把直接 Dispatcher 操作当作 Engine ToolCall 行，失败留存；修正为审计核验后，仅被动检查同一案例，没有重复死亡操作。收据为 ignored 的 `build/post-push-owner-helper-loss/`。资源释放与恢复通过不等于焦点体验通过；未引入新的常驻监控执行域。

双退出之后的最近任务截图显示测试应用标题、输入框和滚动内容，未出现全空缩略图；这个受控应用样本不能证明历史酷安空白缩略图已经修复。本轮没有真机、其他 API/OEM 或历史图形通道 ANR 的新增验收。

### 2026-11-04：按当前可复现证据继续优化

Owner 接受宿主与原 helper 同时退出后，Android 将原应用迁回主屏并改变焦点的表现为合理情况；该边界不再列为待修复缺陷，不为此增加常驻监控。历史 ANR 和旧空白缩略图没有新的复现证据，本轮停止追查，不宣称根治。

最新浏览器整合代码以同一本地页面、交错次序执行三组进程冷态／异步预热对照，保留系统和 Provider 缓存，不清数据。六个样本均核验 DOM 与实际画面像素。打开调用的主线程区间中位数约 152／29 毫秒；预热本身约 114 毫秒，预热加导航至 WebView 视觉就绪回调的区间中位数约 224 毫秒，对照约 228 毫秒。结果支持预热转移首次打开的主线程工作，不支持固定端到端提速、全部网页或其他设备的性能承诺。首次夹具仅等视觉回调的白色截图保留，未作为完整画面验证；补像素断言后重新采集。证据为 ignored 的 `build/current-optimization-browser-verified/`，未再改写已验证的浏览器生命周期实现。

Owner 单独批准模拟器专用目录 `Documents/Helix-validation-6d36a7f2` 的临时测试授权。真实 DocumentsUI 与 ExternalStorageProvider 验证持久只读拒写、写权限升级和降级、完全撤权并结束旧进程后拒绝旧来源读写、重新授权恢复。仅撤销本次授权，原授权集合与来源集合核对不变，独立 Shell 读回确认文件效果，最后删除合成文件及空目录；不撤销已有目录、不清除应用数据。初次夹具遗漏可持久授权标志，被生产入口正确拒绝；修正后两阶段通过，失败日志保留。证据为 ignored 的 `build/current-real-saf-retry/`；本轮没有发现需要修改的 SAF 生产实现。

Mobile Use 新样本先命中内置模型用途未知的准入保护，未调用模型；按真实配置刷新目录后恢复为 CHAT，没有猜用途或绕过校验。随后真实模型样本虽准确读回三个指标，却只读取节点就结束，未完成请求中的截图，严格图像消费断言如实失败。优化指引明确目标尚未出现时的能力标志不等于永久截图不可用，已指定包的显式打开不必先查安装列表，打开后获取新 frame 并取得实际截图；纯截图阅读不强制增加重复节点查询。权限拒绝、原 frame、范围和后端边界保持不变。Skill 入口保留原 1 KiB 上限，详细解释放入原参考文件，不放宽提示预算测试。后续实际回归结果按候选分别记账，不把失败基线的较少调用冒充提速。

新指引在同一受控页面连续两个真实 Qwen 样本通过：各五次模型请求，分别四／六次工具调用，约 24／26 秒；均在打开目标后取得新 frame、保存截图，并由请求审计确认像素进入模型，最终仅输出页面原文的下载、评论和评分。没有改写模型回答或降低图像消费断言。失败基线为四次模型请求、四次工具调用，数字正确但缺少请求的截图，不能当作完整任务成功或速度优势；这两次成功也不代表任意模型、页面和网络条件的成功率。来源与图像留在 ignored 的 `build/current-mobile-optimized/`、`build/current-mobile-optimized-repeat/`。完整双渠道 Debug 主机门禁通过；本轮没有真机、其他 API/OEM 或新的 Release/R8 验收，没有推送或发布。

### 2026-11-04：项目衔接界面的精简规则漏验与修正

Owner 再次指出聊天页常驻“不属于项目”、目录及会话成果汇总不需要。此前 AGENTS 和验收 Skill 已有默认 UI 精简规则；实现者仍把“入口存在、数据显示正确、点击有效”当作验收完成，未将菜单关闭时的视觉层级作为独立验收条件，还把常驻展示写入项目 ADR。这是实现和设计验收的判断失误，不归因模型或构造样例不足。现修订原 ADR 并追加 Decision history，项目选择／创建和目录统一由会话设置负责，顶部详情不重复目录及其操作，空成果汇总不占位。未知上下文用量从问号占位改为底部输入选项按需查看；实际用量、当前权限和具体异常恢复仍按其持续决策需要保留。实际完成文件的单轮结果入口仍属于那次请求的交付结果，不新增全局元数据行。

全局规则补充可执行下一步、优先现有设置／详情／三点菜单、适用时使用短暂提示或弹窗，同时保留持续风险和恢复的可发现性。现有验收 Skill 补充默认页与展开页分别评审、按组件角色检查不存在或位置归属，以及新草稿、已有对话、实际完成工具成果的产品路径；不增加机械文案黑名单或另一套 UI 解析器。新增本地确定性模型经真实 Engine／Dispatcher 写文件的界面测试，检查会话级成果汇总按需打开并读取实际内容；项目测试改走顶部设置，覆盖取消、选择、创建、目录切换、重建和输入附件保留。

输入回归初次完整批次有一项超时，运行中截图与系统 IME 状态证明当前中文输入法为浮动收起状态，IME 可见而底部 inset 为零。旧夹具将正底部 inset 当作唯一可见条件，现检查系统 IME 可见状态，保留全部多宽度、字体、触摸目标和视口断言；未改输入法配置，原失败日志保留。当前验证范围是 API36 Developer Debug，确定性本地模型不代表新增真实模型或 Release／真机验收。候选身份、通过与失败日志及截图保留于 ignored 的 `build/chat-disclosure-evidence/` 和 `build/chat-disclosure-device/`，最终结果按本轮实际执行记录，不继承未执行条件。

最终同一生产 Debug 候选完成 26 项设备检查：实际会话／Dispatcher 成果 1 项、项目设置与目录边界 2 项、成果查询／预览／变更或缺失文件 6 项、输入与上下文／字体布局 17 项。完整 Debug 主机门禁通过，IME 夹具修正后另编译双渠道测试、重打测试 APK 并通过格式／静态检查；生产源码和已安装主 APK 未变。新草稿、普通已有对话、实际文件输出和按需预览的截图已复核。测试保留数据覆盖安装，批次前安全 profile 已恢复，未改输入法设置；本次未执行底部停靠键盘、其他 API／OEM、真机或新 Release 验收，不把浮动 IME 样本外推。浏览器并行生产源码保持原样，未提交、推送或发布本轮修改。

### 2026-11-04：统一会话管理、设置层级与稳定成果入口

Owner 指出成果入口随空列表消失，并要求统一优化。成果导航固定留在会话管理，空草稿也能进入；打开时切换到独立面板，避免管理抽屉与成果抽屉叠放，读取中、空列表和失败／重试分别呈现。设置按会话、工作上下文、权限与扩展组织，统一摘要行；模式可直接选择，权限一次进入完整编辑器，目录技术说明按需查看，具体失效仍可见。模型管理只保留选择器入口，重命名统一到管理菜单并保留草稿改名；任务进度归入本会话任务。输入区原有发送前快捷操作保留，不往聊天页增加元数据或设置按钮。

复跑确认一个实际持久化边界：草稿的模型偏好仍可能引用已删除来源，UI 只能显示模型失效，插入会话却因外键失败而无法打开权限配置。修复在同一元数据事务中按已存在的会话 FK 删除语义处理：来源已删除时保存空 providerId，保留原 modelId 以继续报告 SOURCE_DELETED；现存但未测试／暂不可用的来源不被清空，不猜测替代模型。会话设置可以保存，生成仍走既有绑定校验并要求重新选择来源。新增真实 Room 回归分别覆盖现存来源保留与删除来源后的元数据、控制配置和错误事实。没有新增模型请求或权限绕过。原失败、诊断及最终候选结果分别保留于 ignored 的 `build/session-settings-unify-*`；最终验证范围按本轮实际通过记录填写。

最终同一生产候选在 Helix_API_36／API36 Developer Debug 通过 56 项检查：统一设置 2、原设置 4、会话持久化与来源删除 8、项目草稿 2、成果预览 6、实际 Engine／Dispatcher 文件输出 1、导航／任务进度／长路径与中英大字体 16、输入与上下文布局 17。完整双渠道 Debug 主机门禁通过；最后仅补专家选择弹窗交接的测试等待，另完成双渠道测试编译、打包、格式和静态检查，生产源码与主 APK 不变。早期空列表容器断言、弹窗转换取景、编译与静态检查失败均保留，没有作为通过证据；静态视觉验收另在测试结束后通过公共入口重新取景，确认固定成果、单层空状态、三组设置和返回路径。安全 profile 与本轮模型回归前的草稿模型偏好已按单个字段恢复，未清数据、卸载、使用真机或推送／发布。范围不含其他 API／OEM、新 Release/R8、停靠键盘或新的外部真实模型样本。


### 2026-11-05：消息添加入口与能力作用域

Owner 要求删除会话模型选择器重复的“管理对话模型”，保留未配置来源的首次使用快捷入口；图片和文件合并，连续转写移到麦克风模式选择。后续明确保留加号中的专家、插件入口，删除会话设置里的对应重复项。技能两处实际用途不同：加号准备当前消息的显式激活，设置开关控制会话可用性，因此保留并分别命名“使用技能”“会话技能开关”。菜单总标题改为“添加”，持续选择归入“本会话能力”，避免把插件或专家误述为当前消息的一次执行。官方竞品依据和采用边界见 [Skill ADR 的本次决策](../../adr/skills/001-authoring-and-installation.md#decision-history--2026-11-05调用与持续可用性分层)。未新增生产子 Agent 或一次性专家角色覆盖。

最终候选通过双渠道完整 Debug 主机门禁、双渠道 AndroidTest 编译及 Developer 测试包打包；Helix_API_36／API36 Developer Debug 实际通过 40 项设备检查：入口、附件取消／重建／跨会话迟到、首次来源配置、技能一次调用与会话隔离、插件会话／默认集合隔离共 12，设置／专家与项目草稿 8，输入／上下文／语音控件 19，确定性本地模型经真实 Engine／Dispatcher 文件输出 1。检查实际菜单取景、系统附件选择器及语音模式取消，无新增录音或第三方模型任务。最终标题修正前的候选结果独立保留，未混用安装身份；最终源码、APK 与已安装字节收据位于 ignored 的 `build/capability-scopes-*`。测试后恢复本轮前的安全 profile 和草稿模型偏好。范围不含真实音频质量、其他 API／OEM、真机或新的 Release/R8；未提交、推送或发布本轮修改。


### 2026-11-05：覆盖更新后的模型恢复与错误入口精简

Owner 报告更新后登录不可用时误报来源删除，重新选择又被拒绝为模型／思考配置变化，并指出错误旁重复新增模型入口。隔离 Room 用例复现：来源存在但展示行为空时被误判删除；已退出登录的内置来源也被误判删除。投影改用持久配置判断存在性，认证与真实删除保持区分。保存原先用可显示模型构造预期快照，丢失已删除来源后保留的 modelId 或不可显示的 providerId，导致真实原绑定与空 UI 值比较失败；现会话状态单独投影原始绑定与 reasoning，两处选择器据此保存，保留真实并发变更、会话切换、运行中、目录资格与思考强度检查。错误文本保留，重复按钮与其专用回调、资源删除，原模型入口仍可打开。具体契约见 [Provider ADR](../../adr/provider/001-models-and-connection.md#decision-history--2026-11-05恢复后模型选择使用原始绑定事实)。

同一 Developer Debug 应用候选在 Helix_API_36／API36 完成 53 项相关设备检查：专用目录合成凭据覆盖安装后恢复 1，模型编辑／原始绑定／思考配置／真实模拟请求消费／来源诊断 11，消息入口和作用域 13，设置与项目 8，输入和语音控件 19，确定性本地模型经实际 Engine／Dispatcher 文件输出 1。覆盖安装前已准备合成登录，更新后仍能通过 Keystore 读取且保持 SIGNED_IN，随后删除专用合成凭据；没有读取、替换或登录真实账号。该实验不证明用户此前掉登录的具体原因，也不把真实删除的来源改成认证问题或猜测自动替换模型。实际界面取景确认错误旁重复按钮已移除，现有模型入口可用。

完整双渠道 Debug 门禁通过。新增基线第三项最初因旧夹具缺少合成登录和模型用途事实而超时；补齐后两项既有生成测试暴露目录 GET 误消费生成 SSE 脚本，修正目录／生成响应路由和失败诊断，保留全部业务断言，最终完整复跑模型状态 11 项通过。最后仅变更该测试夹具，生产源码和已安装应用字节不变；其余 42 项沿用同候选已通过证据，最终另通过双渠道 AndroidTest 编译、Developer 测试包打包、格式和静态检查，不将旧失败日志记为通过。当前与基线、安装身份及最终合并收据保存在 ignored 的 `build/model-recovery-*`。安全 profile、草稿模型偏好按原字段恢复；未清数据、卸载、使用真机或推送／发布，范围不含其他 API／OEM、实际音频质量、新 Release 或真实账号认证失效原因。


### 2026-11-05：会话工作目录的作用域与选择体验

Owner 要求参考主流 Agent 优化会话、项目和工作目录的关系。沿用工作目录、项目归属和文件权限各自独立的契约；没有项目也可使用独立或已有目录，项目只提供新会话的默认目录，已有会话可显式更改后续请求的起点，不移动旧文件、不改项目归属、不自动扩权。选择器从当前目录打开并保存浏览位置，增加项目目录快捷选择；项目目录与独立目录可以互切，项目编辑也从已选目录开始。设置中以可读的会话／项目目录标签替代常态内部资源 ID，资源身份、用途和切换后果放在现有位置详情。来源根层集中本地权限管理，具体目录保留已观察到的访问能力。草稿准备持久化时拒绝目录修改并返回未保存，避免无效更新被报告为成功。竞品依据和采用边界见 [工作目录 ADR](../../adr/workspace/004-workspace-binding.md#decision-history--2026-11-05工作目录的用途与显式切换)。

同一 Developer Debug 应用候选在 Helix_API_36／API36 完成 29 项设备检查：目录选择、恢复、取消、无效引用、共享目录标签与中英大字体布局 15，项目／设置 8，原请求、审批和文件绑定身份 5，真实 Engine／Dispatcher 的本地确定性模型成果流程 1。项目用例验证互切后项目归属、输入附件和重建恢复；在途审批验证更改后仍按原目录及当前权限判定，不重定位调用。无新增外部目录持久授权，不试写用户文件。实际设置、原目录选择器和位置详情按加载完成状态取景，取消后返回原会话；第一次加载中取景单独保留，不作为稳态视觉证据。

完整双渠道 Debug 主机门禁通过。首次门禁发现既有 SocketWireTest 在 connect 返回前收到超大首帧，使预期 receive 异常提前到 connect；夹具补握手同步和实际发送断言，不改 Socket 生产策略，双渠道及完整门禁复验通过。首次设备批次两项 UI 测试抢在异步项目／权限页面出现前点击，保留业务断言并等待真实目标页面，重跑完整项目／设置组 8 项通过；应用源码与安装字节不变，其余 21 项沿用同候选通过证据。最终测试包另完成双渠道编译、Developer 打包、格式与静态检查。当前／失败日志、源码与制品身份、合并收据和取景保留于 ignored 的 `build/working-directory-*`。测试前的安全 profile、草稿模型和会话启动目标均按原字段恢复，保留 Owner 新报的酷安下载安装浏览器会话供下一阶段分析；未清数据、卸载、使用真机或提交／推送／发布。范围不含新 Release、实际外部 SAF 授权或其他 API／OEM 验收。


### 2026-11-05：酷安安装 X 浏览器的原始调用复盘

Owner 要求先完成工作目录优化，再分析日常模拟器最新酷安下载安装 X 浏览器任务。只读提取该会话的 49 次原始调用、结果和对应截图；安装器节点确认“X浏览器／已安装应用。”，最终列表给出 `com.mmbox.xbrowser`。共 4 次失败，属于 3 类：两次手势在 DOWN 前因 FRAME_CHANGED 拒绝，取帧到手势创建约 13／16 秒，首张是自动结束的开屏广告、后续已是首页；第二次具体哪个节点变化未证明。一次横向滚动节点只公布 forward/right，模型却传 left；原工具描述按横纵方向推荐 left/right 容易混淆内容导航与手指轨迹。最后一次尝试把来源 Helix 启动到后台屏，被主屏可见冲突保护正确拒绝；旧启动描述及通用结束交接指引未统一区分主屏和后台屏。另有两次猜测错误包名的精确安装查询，随后列表发现真实包名。

据此判断为动态页面观察时机、模型参数选择与工具指引共同作用，不能据单个成功任务归结模型能力不足或量化模型能力差异。保留现有 18 个工具名称及授权／帧身份／不重放／主屏冲突保护，未发现工具名或未知参数导致的失败。统一 scroll 的描述、方向参数、恢复提示及技能参考，要求精确采用节点公布的方向；统一后台完成不启动宿主到虚拟屏。`ui.apps(query)` 提供按已知名称或包名子串发现候选，保留歧义、截断和原状态，空结果不冒充未安装；不能与精确包名查询混用。相关决策记入权限 ADR 既有 Decision history，不引入新的执行域、授权或版本。

双渠道完整 Debug 门禁、手机操作模块 345 项 JVM 检查、双渠道设备测试源码编译通过，覆盖新增查询、歧义、截断、非法参数边界及既有动作保护。候选保留数据覆盖安装到 Helix_API_36／API36，安装 APK 与主机字节核对；首次安装因存储不足失败，确认三个旧微信安装包完全同哈希后仅删两份重复副本、保留原件和所有应用数据，重试成功。已配置真实模型完成 3 个合成观察规划场景：选择公布的滚动方向、按名称查询包名、后台结束不启动来源 Helix；只记录模型选择，执行工具调用数为 0，不等于真实触摸或完整安装任务重跑。安全 profile、草稿模型及会话启动目标按原字段核对未变。原始失败、源码／制品身份、规划调用及收据保留于 ignored 的 `build/mobile-use-xbrowser-*`。未清数据、重做下载／安装任务、使用真机、推送或发布；不宣称其他应用、模型或动态页面普遍通过。


### 2026-11-05：后台预览闪动与标题栏对齐

Owner 反馈后台应用切页时预览闪动、关闭按钮与应用名不齐且顶部留白偏大。对已安装旧候选增加同一组 UI 对照检查：一次无帧让悬浮窗总高度由 606px 缩为 190px，标题和关闭按钮中心相差 5px。根因是每 250ms 的读取直接把空结果写入画面状态，无帧分支用文字替换整块图片布局，使按尺寸计算的拖动位置也跳变；旧标题有垂直 padding 且标题行没有居中对齐。

预览现在使用稳定比例的画面容器，无帧仍保留几何但清除像素，不增加越权缓存；标题去掉额外上下 padding，应用名与关闭按钮垂直居中，常态标题栏从 32dp 收紧至 28dp，大字体允许自然增高。Shell 对暂时无目标或读取期间窗口变化做最多三次只读采样，两次间隔各 16ms，每次复核原 owner、显示和应用范围；拒绝立即退出，不延长显示生命周期、不创建显示、不改变输入及截图工具。持续无帧、授权失效或断连仍清除图像；Android 自身转场／启动黑帧不承诺被消除。决策见权限 ADR 既有 Decision history。

完整双渠道 Debug 门禁、手机操作模块 350 项 JVM 测试通过，包含新增 5 项稳定读取、过渡恢复、持续无目标、有界采样及拒绝退出边界。Developer Debug 在 Helix_API_36／API36 保留数据覆盖安装，3 项 UI 检查确认无帧尺寸不变、清除后恢复、标题切换、正常／双倍字体对齐，以及拖动、放大、隐藏、恢复、重建和释放。真实 Dispatcher／Shell 路径在专用测试应用完成 3 轮 Activity 往返，核对新页面像素实际进入本地预览，再验证文本、滚动、截图、错误身份拒绝及持久取消后的显示释放。首次真实切页组在第二轮返回点击时被 TARGET_CHANGED 拒绝；夹具补上实际稳定观察条件后重跑整条后台路径通过，保留拒绝及失败证据，未重放未知动作或放宽生产校验。生产 APK 字节未变，原 3 项 UI 通过证据仍适用；最终夹具补充双渠道编译与 detekt 检查。

原始对照、首次失败、最终安装身份、合并检查记录和实际 UI 取景保留于 ignored 的 `build/background-preview-*`。原安全 profile、草稿模型、会话启动目标和手机操作范围／显示字段按原值恢复，测试会话由夹具清理；未操作酷安或 X 浏览器、消费模型服务、清除日常数据、使用真机、推送或发布。以上验证不等于所有第三方应用转场的无闪烁保证。


### 2026-11-05：移除完成回复下的重复成果入口

Owner 指出会话完成后回复下仍有成果按钮，与已统一的会话管理重复。根因是旧逐轮成果组件仍在终态助手回复下加载并显示；之前真实成果流程的默认页验收只排除项目、目录和会话级成果标签，没有覆盖这个逐轮标签。删除旧组件、回调、专用时间线刷新状态和未再使用的三语言文案，不删除产物或原任务归属；会话管理的固定成果入口、空状态、列表、预览／交付及任务管理的任务成果仍保留。

实际 Engine／Dispatcher 通过本地确定性模型生成文件的回归增加逐轮入口不存在断言，并继续从会话管理打开该产物及核对预览内容；大历史夹具不再人为注入已删除的 200 行成果捷径，移除失实的基准计数字段，旋转后检查实际末条回复且无旧成果行。双渠道完整 Debug 门禁和测试 APK 打包通过，Developer Debug 保留数据安装到 Helix_API_36／API36；设备验收覆盖真实产物闭环、成果管理六项原位置／变化／丢失／撤权／空状态／长列表边界，以及大历史滚动恢复和实际 Activity 旋转。首次旋转检查在默认合并语义树中定位正文未命中，改为同时检查原消息气泡和未合并正文后复验通过；生产 APK 字节未变，其余八项沿用同候选通过证据，最终测试夹具另完成双渠道编译和 detekt。未减弱文件权限、内容哈希或原任务身份检查。

原会话启动目标、草稿模型和安全 profile 已按原字段恢复；实际源码与安装字节、各组逐项日志、完成产物默认页及按需预览取景保留于 ignored 的 `build/reply-artifact-*`。未消费真实模型服务、使用真机、清数据、提交、推送或发布。该验证不替代其他设备／OEM 或 Release 验收。


### 2026-11-05：酷安打开后后台屏退出的只读核查

本轮核查 Helix_API_36／API36 Developer 的用户报告酷安启动任务，没有重放原任务、启动新手机操作或更改授权。工具记录依次为查找应用、启动酷安、语义快照不支持、设备信息和成功截图，没有返回/关闭应用工具。模拟器日志显示酷安 Activity 于 18:27:31 启动；原 Turn 在 18:27:58.770 以 COMPLETED 结束；18:27:59.532 开始释放 display 61，18:27:59.587 明确记录 TASKS_PRESERVED，检查时酷安进程仍存在。

证据对应现有生命周期：AutomationPermissionCenter 的后台 route 仍以原 session/Turn 的 backgroundTaskActive 为存活条件；Shell UserService 每秒 sweep，授权或原任务失效即释放屏；ShellTaskRetention 保留既有任务并在必要时恢复主屏原焦点。约 0.77 秒的结束后释放符合该路径，未见本次崩溃或模型主动关闭证据。语义快照的 UNSUPPORTED_UI 与随后正常截图、最终资源回收分别看待，不归因于“快照失败导致酷安退出”。本轮不修改后台屏寿命或续授操作权限；原日志和调用收据保存在 ignored 本地证据中。
