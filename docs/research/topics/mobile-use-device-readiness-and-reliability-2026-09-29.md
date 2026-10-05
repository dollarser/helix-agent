# Mobile Use：设备就绪、锁屏与后台边界及可靠性优化

日期：2026-09-29。性质：讨论固化与源码评审；优化项为候选设计，不表示已实现或自动获得实施授权。

> **2026-09-30 时效与职责说明：**下文保留原核验基线和当时建议，不是实时缺陷清单。后续 [HXA-232](../../development/tasks/HXA-232.md) 已取消每十次动作的周期确认，并支持新快照验证后恢复已授权目标；不能继续把这两项作为未修复现状。模型/Harness 职责以 [Harness §13.6](../../architecture/harness-refactor-plan.md#136-模型与-harness-的职责优化及减法准则) 的目标准则解释：先反馈并自主处理权限内问题，真实授权、业务信息或系统认证才需要用户介入；不把本页“接续”建议扩大成所有失败都必须等待人工，也不为每个错误新增恢复引擎。截图、自动亮屏和新许可策略仍须各自接受与验证，当前实现/验证范围看 status 和具体任务。

本文承接“Helix 能否协助钉钉打卡”“锁屏后能否自动解锁或后台进行”的讨论，并对 Mobile Use 做定向源码核验。**优先建设可解释的设备就绪、动作后核查和用户接续，而不是先做自动输入锁屏密码、无人值守打卡或第二套 Agent 引擎。**

核验起点：当前项目检出分支 `v0.0.3`，HEAD `53beeedfbad96bbe271380c73546111cdbf0c0e6`，另有既存 README 改动。本页描述所读取的源码，不推断手机安装包与工作树一致。本次未执行设备、模型、账号、钉钉业务操作或功能测试。

阅读关系：[当前范围](../../development/status.md)决定正在做什么；[开发原则](../../development/feature-refactor-strategy.md)决定如何取舍；[平台能力 §5](../../architecture/android-platform-capabilities.md#5-accessibility-自动化)和[权限 ADR](../../adr/permissions/003-dispatch-and-audit.md)规定现行规则；[Plugin 方案](../../architecture/plugin-platform-plan.md)负责扩展归属；[Harness 方案](../../architecture/harness-refactor-plan.md)负责公共执行边界。本文不复制这些完整契约，也不把候选变更写入 accepted ADR。

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

现阶段继续遵守锁屏结束许可；**先让用户看到“设备已锁定，当前操作许可已结束”而不是一个无法解释的失败**。后续接续见 §5，需要新的授权与窗口观察，不能复活已清空的许可。

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
| MU-C：用户解锁后接续 | §5；许可过期后重新准入、通知与旧回执失效 | 旧 token/旧许可不恢复，任务不丢、无后台模型轮询或重复动作 | 新生命周期行为先更新所属权限/Agent 决定；不作为 R1 完成条件 |
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
