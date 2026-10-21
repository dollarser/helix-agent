# ADR-PERMISSIONS-003: 工具执行准入、精确审批与持久审计

Status: accepted
Date: 2026-09-16
HXA: HXA-020, HXA-028, HXA-033, HXA-066, HXA-068, HXA-200, HXA-201
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

免确认是一种用户授权来源，不是跳过执行准入、取消结算或恢复的理由。

## Decision

- 2026-09-29 HXA-232：已授权自动化会话取消每十次动作的人工确认。越出允许目标仍暂停、旧 token 失效；新快照通过原包范围/敏感目标规则后可恢复已有授权，不扩大包列表、不续期、不重置动作预算。工具反馈需要新授权或无法继续的事实，不要求用户到设置页做技术恢复。配置修改与模型请求前外发使用下述统一授权交互。

- 每个调用经 schema、可信工具身份、Capability、当前会话规则、执行限制和审计。系统权限只满足实际能力，不让模型或外部内容修改用户授权。
- 原生、别名、MCP、A2A 以平台登记的来源主体和准确工具身份识别，不能凭同名工具共享授权。持久用户权限/禁用意图按稳定来源、工具及用户批准的权限/目标范围解析；普通软件/包版本或安装 revision 变化不引入新的授权询问。权限扩大、来源替换、目标变化仍重新按当前事实解析。曝光过滤与执行时复检同时存在。
- 单次调用证明仍绑定本调用的规范参数、scope、执行目标、会话、准确合同及捕获的执行 binding；这是审批期间替换的并发保护，不是持久权限的版本键。ASK 每次调用只批准一次，ALLOW 不因版本更新额外弹卡；更新后的新调用仍经过原 schema、capability、policy、范围与准入检查。失效旧 binding 不转向新实现执行，不将一次批准扩展成未来批准。
- 需要确认的调用呈现实际参数、效果范围与原因；只消费对应调用的 APPROVED proof，DENIED 仅为拒绝事实。proof 在执行开始的线性化点一次性消费，不在出卡或点击时提前消费。精确批次为每个调用生成独立绑定，不能授予通配写权限。
- 等待卡不持有设置写锁。取消、工具禁用、scope/契约变化及当前操作 DENY 在执行开始前复检；迟到决定不重复执行，旧批准不能覆盖新禁止。
- 自动授权记录具体模式/规则来源，不能伪造用户点击。每个排队槽位均持久结算成功、失败、开始前取消/拒绝或未知副作用；一个调用的异常不能替代同批兄弟调用的结果。
- 进程死亡恢复历史、审计与实际结果。已失去 live wait 的卡不伪装成可继续执行；用户恢复必须经当前准入，不自动重放未知副作用。审批记录、执行 audit 与用户可见恢复摘要需可关联。
- UI 经应用服务读写设置，Room 写入在合适 dispatcher 串行执行；全局/Workspace/会话来源清楚显示。工具只有 ENABLED/DISABLED，ASK 只属于操作规则，不建立第二套有效偏好来源。

### 本机工具目录迁移

本机系统工具统一为 `device.*`，旧 `android.*`、`clipboard.*`、`notifications.query`、`calendar.*` 的对应注册入口直接移除，不保留别名、调用转发或旧历史调用重写。设备工具的 schema、Capability、当前用户规则、执行绑定和审计仍走原链路。一次性迁移同源工具的 ENABLED/DISABLED 用户选择，拆分时禁用意图覆盖对应入口，冲突时 DISABLED 优先；这不是旧协议兼容，不迁移单次批准或改写调用身份。MCP/插件的同名条目不受影响。

系统分享和外部备忘录保存只能报告 chooser-opened，不能报告已发送、已落库。文件分享/打开同时保留逐文件作用域读取分类和设备外部动作授权，受控快照经临时只读内容 URI 交付；打开本应用页面只请求导航，不授予登录或设置变更权限。云端连接器直接保存仍使用其独立受治理工具，未接入不能冒充支持或退化成分享。详细目录见 [设备工具接入说明](../../development/device-tools-integration.md)。

## Alternatives considered


仅隐藏 schema 不足以阻止历史调用；点击即消费 proof 无法处理停止竞态；给自动放行伪造 APPROVED 会破坏审计。均不采用。

## Consequences

同一主题使用一份有效契约，避免并行实现各自解释权限和生命周期。代价是实现、UI、数据与恢复需要一起验证；accepted 表示决定，不代表相关任务全部完成。

## Verification

复用已有 HXA-200/201 的身份、审计与恢复证据；HXA-209 已重新验证新授权矩阵与竞态（见 HXA-209 完成记录），未沿用工具三态断言作为新模式验收。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)

## Decision history

- 2026-10-10：HXA-255 将外发规则页面的精确目标/端点/固定期限校验与读写收敛到 EgressRuleService，界面消费窄读模型，不传递规则仓储。取消及致命错误传播；普通读取失败可重试，不冒充成功空列表或持续加载。已提交的授权写入不因随后读取/协程取消而声称回滚。Advanced入口、APP scope、固定TTL、PolicyEngine的live读取及逐次审批保持原owner和契约。

- 2026-09-30：外发确认收敛为数据外发审批，复用工具审批动作组件；实际 Provider/endpoint、内容与附件哈希仍由原边界重新校验。统一的是授权交互，不合并不同授权对象或静默放行；变更目标/内容不能复用旧批准。

- 2026-09-30：HXA-232 的 `helix.settings.apply` 将当前会话未来输入的模式、已有 Provider/模型及思考强度修改接入正常工具授权。它是 LOCAL_MUTATION，可信 footprint 为 DEVICE_SYSTEM_MUTATION，不使用 METADATA 豁免，Plan/Chat 不因此获得模式升级通路。执行时绑定当前 live Turn，重新检查取消、时限、配置可用性与排队输入；不能更改权限、QuickJS 原生开关、运行档位或凭据。旧 `propose` 仅保留页面导航；配置参数明确拒绝并指向 `apply`，不再工具返回成功后等待配置弹窗。数据外发保留原内容/目标绑定，和工具审批共用“本次批准/拒绝”交互。外发发生在模型调用前，继续由原外发准入服务负责，不伪造 ToolCall；两类授权 proof 不互换。

- 2026-09-29：所有者接受自主恢复分工，周期检查不再需要人工；已授权目标的有效新快照取代旧人工恢复确认。用户 Stop、到期、系统授权撤销及新目标授权保持原门控。

- 2026-09-28：owner 明确要求正式系统设置授权入口。Advanced Accessibility 权限中心在启动自动化会话前提供默认关闭的“系统设置和快捷设置”选择，只在该 process-local session 内允许 `com.android.settings` / `com.android.systemui` 越过默认 package 拒绝。仍须包含在用户目标 allowlist；停止、到期、服务断开或进程重建不继承授权。没有模型可调用的授权接口，不改变 Dispatcher、审批或 effect 分类。其他敏感包、密码/敏感节点、危险动作语义及锁屏检查仍生效。此选择允许设备设置更改，不声称能逐页识别 Settings 内全部安全设置；OEM 包名不做通配放行。

- 2026-09-28 后续修复：owner 要求解决已授权 Settings 任务的跨目标/暂停失败。正式授权选项列出本机已安装且标记为系统应用的精确 Settings/SystemUI/Settings Intelligence 包（`com.android.settings.intelligence`、`com.google.android.settings.intelligence`），用户勾选并启动时一并纳入 allowlist；不做包名前缀或任意 Intent 跟随授权。权限中心增加暂停确认：用户选择当前 session 已授权且非禁止的目标，确认只绑定当前 pause/session；返回该包后再次检查 live snapshot、敏感节点和锁屏，再消费确认。停止/到期/恢复会清除待恢复确认，不预存下次 checkpoint 授权。snapshot/find/wait 增加 pause/recovery metadata，wait 遇授权阻塞立即返回，不自动批准或盲目重试。

- 2026-09-28 同轮滑块修复：授权/恢复修复后的真实轨迹已到达滑块，但节点不支持 click/set-text/container-scroll。新增 `ui.set_progress(token,value)`，仅接受节点声明支持 `ACTION_SET_PROGRESS`、有限且在原生 range 内的数值；范围/能力纳入 token fingerprint，执行前重新核验。该动作按 EXTERNAL_ACTION 走原有 capability、scope、审批与敏感语义检查，不提供坐标、盲拖或系统 settings 写 API；执行返回成功仅表示平台动作接受，任务效果仍须独立观察。

- 2026-09-28：owner 授权按操作类型收敛权限。移除注册、Policy、审批与新审计中的 baseRisk/dynamicRisk/L0–L3；展示 READ_ONLY、METADATA、LOCAL_MUTATION、NETWORK、EXTERNAL_ACTION、CODE_EXECUTION、PRIVILEGED 对应操作及实际范围/询问原因。等级不再是权限来源或默认拒绝依据。已接入会话的调用按可信 effect footprint 与当前 ALLOW/ASK/DENY 解析；未接入会话时，非只读/闭合元数据操作要求精确批准。凭据、SSRF、Capability、模式、未知副作用与执行前复检不变。旧审计缺少 operationClass 时保留其他事实，不从旧等级推断新类别；工具契约 hash 变化使旧批准失效。

## Decision history — 2026-09-30：真实执行容量

超时/取消不证明执行已退出。宿主执行池使用有限线程和零排队饱和拒绝，拒绝须证明本次未提交执行；可信停止/对账包装保留独立有界容量，资格不从外部 metadata 推导。实际存活数量与超时/取消后未退出数量按 executor 进入/退出计数，effect owner 仍在真实执行线程中释放。本轮只是异常资源收口，不替代 HXA-231 原子绑定，见[审查记录](../../bug-fixes/2026-09-30-historical-correctness-audit.md)。

## Decision history — 2026-10-18：升级、持久权限与单次执行分离

Owner 明确普通工具版本变化不应导致同一权限重新批准。持久授权按稳定来源/工具、权限和目标范围解释；原生可用性来源移除包版本，已有明确禁用以一次性事务规范化保留。Mobile Use 的配置/选择授权身份不读取包版本或安装 revision。`ApprovalBinding` 的精确合同与原执行 ref 继续服务于本次调用，替换后不得执行，不能借本次批准扩大到下一次。Dispatcher 回归覆盖 ALLOW 下更新不弹新卡、旧 ref 不可执行；ASK/消耗/撤权与范围变化回归沿既有 suite 保留。

参考 [OpenCode 权限规则](https://docs.opencode.ai/docs/permissions/)按工具输入配置 allow/ask/deny，[Chrome 权限更新](https://developer.chrome.com/docs/extensions/develop/concepts/permission-warnings#update_permissions)按新增警告权限重新确认；采用的是以实际权限变化为界的取舍，不宣称竞品使用相同的内部 revision/fingerprint。实现和验收由 HXA-307 承接。

## Decision history — 2026-10-21：本机工具统一命名

Owner 授权参考 WorkBuddy 迁移且删除旧工具入口。采用 `device.*`、一次性保留用户禁用选择而不转授调用批准、系统面板交接与实际保存分开，以及文件读取/外发效果共同治理；第三方直存不以分享代替。详见本文本机工具目录迁移与接入说明。


## Decision history — 2026-10-21：成组工具与独立短名

Owner 进一步授权整理其他工具，并明确离散工具可无命名空间。Goal 使用 `goal.*`，设置使用 `settings.inspect/apply`，结果读取使用 `tools.read_result`；`ask_user/read/write/edit/bash/view_image` 保留短名。旧设置提案/页面弹窗移除，`settings.inspect` 为只读，实际配置修改保持原 DEVICE_SYSTEM_MUTATION 分类、当前 live 调用/Turn 校验与未来输入生效边界。一次性迁移用户可用性选择，不转授单次批准或重写审计。见 [工具契约整理](../../development/tool-contracts.md)。
