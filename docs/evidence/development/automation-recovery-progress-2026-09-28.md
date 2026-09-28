# 自动化搜索、暂停恢复与滑块修复

日期：2026-09-28。承接[首次系统设置授权](automation-settings-authorization-2026-09-28.md)，所有者要求查明并解决亮度复测失败。仍在隔离分支 `codex/tool-discovery-eval`，不覆盖主目录的 Plugin 工作。

## 根因与修复

1. 独占 API36 诊断实际点击 Settings 搜索，前台节点包与 Activity 都属于 `com.google.android.settings.intelligence`；不是 `com.android.settings`。原授权只包含 Settings/SystemUI，搜索跳转正确触发越界拒绝。诊断证据 `build/public-eval/settings-search-diagnostic-20260928/`，该脚本只定位问题，不作为任务成功证据。
2. 权限中心已有恢复 API，但没有正式恢复控件；模型看到重复失败后继续消耗预算。现增加明确恢复目标与确认按钮，确认绑定当前暂停 session 的已授权包。返回目标时重新核对 live snapshot/锁屏/敏感限制，停止/到期/恢复后清除确认。没有自动授权未知目标。
3. 授权选项明确展示系统 Settings/SystemUI/Settings Intelligence 的已安装系统包，勾选并启动时纳入 allowlist；仅精确四个候选包、系统应用标志、enabled 检查，没有包名前缀通配。
4. snapshot/find/wait 返回暂停原因、被拒绝目标包与用户恢复提示；wait 遇授权阻塞立即返回。描述明确每次 snapshot/find 会替换此前 token，暂停时应停止重复调用。
5. 修复以上问题后，第二轮两项都已到达滑块，但现有 click/set_text/container-scroll 都不支持该节点。新增 `ui.set_progress(token,value)`：只调用节点原生 `ACTION_SET_PROGRESS`，要求节点声明能力、有限 range 内数值，range/能力绑定 fingerprint，执行前重新检查。L2 EXTERNAL_ACTION、既有 capability/scope/审批/敏感语义/独占 effect 调度保持，不增加坐标或系统 settings 写 API。

新增工具仅在已有自动化会话时默认曝光；默认工具上界从30到31，组合上界从62到63，硬上限仍64，普通会话默认21不变。未改 Dispatcher 或 effect owner。

## 中间复测（保留失败）

`build/public-eval/androidworld-settings-recovery-20260928/`：API36 developer UI 授权/10次动作检查点/用户恢复/停止复位 **1/1**；亮度 **0 PASS / 2 FAIL / 0 ERROR**。min/max 都到达滑块，两项 `MODEL_CALL_LIMIT`，亮度分别255→255、1→1，Agent elapsed 82067/101620ms，工具调用32/35。

本次 fixture 逐次确认 `CHECKPOINT`，只对当前仍被授权且可成功 snapshot 的目标调用正式恢复 API，记录确认 package，不恢复 `TARGET_CHANGED`。原版任务文本、初始化、oracle、32模型调用/32步/240秒观察窗均保持。该轮不是最终验收，也没有将增大预算作为修复。

## 验证

- 滑块主机正负向验证：有限范围端点、越界/NaN/Infinity、未声明动作、无range、禁用、密码/支付语义、range变化后的旧token、schema数字类型及L2外部动作分类。
- 首次滑块 JVM 正向测试因直接构造 Android Bundle 返回 `UNSUPPORTED_UI`；将平台 Bundle 转换留在 AndroidSnapshotNode，纯执行器与 fake node 验证保持真实的范围/权限门禁，未降低断言。
- 原32调用滑块轮：`build/public-eval/androidworld-settings-progress-20260928/`，UI 1/1、oracle **2/2**，亮度255→1与1→255；min为`FAILED/MODEL_CALL_LIMIT`（66008ms），max为`COMPLETED`（34035ms）。min刚执行完set_progress即耗尽32次模型调用，未验证/回复；不能将oracle通过等同两个Turn正常完成。
- min轨迹开头有7次空工具搜索。base提示补充通用规则：优先现有可用工具，仅在确切缺能力时搜索，不重复等价空查询或遍历无关Skill。不包含亮度答案、导航步骤或fixture标识。
- 当时产品 `TurnBudgetBounds.DEFAULT.maxModelCalls` 为48；原fixture覆盖成32。随后候选轮引用当时产品48次默认调用预算，其余32步、输入/输出/总token、240秒观察窗与oracle保持，该候选未改变生产预算。它与原32调用轮不是严格A/B，不归因性能变化。
- 32工具轮/48调用候选（`build/public-eval/androidworld-settings-final-20260928/`）：UI1/1、oracle1/2；min在32工具轮用尽后`TOOL_STEP_LIMIT`，255→255，69334ms；max正常COMPLETED，1→255，28327ms。增加模型调用上限没有解除工具轮限制，不能记为已全部解决。
- 所有者进一步明确长程任务需要数百/上千次默认预算。撤回未验收的64/65短程候选，普通新Turn改为512工具轮/1024模型调用/3200万累计token，高级上界10000/20000/10亿；单次输入/输出与Goal默认不变。v3历史32/48及其他显式预算保留。host模拟1024次准入/结算，中途恢复计数且不退款，额外一次仍拒绝；不是1024次真实模型设备长稳证据。
- 最终亮度设备状态：passed。原始目录 `build/public-eval/androidworld-long-task-defaults-20260928/`。API36 arm64 developer，正式授权/恢复UI **1/1**，亮度oracle **2/2**，两个Turn均`COMPLETED`且无fixture错误。min为255→1、99627ms、42次工具调用；max为1→255、20053ms、11次工具调用。fixture引用新产品默认工具轮/模型调用/累计token，仍保留原4K单次输出、131072输入上限及240秒观察窗；原oracle未改。专属模拟器已关闭（closed.json exit0）。
- 该亮度轮app SHA256 `b46734893d31224c192b58d6c7111b6a99cf46c099b65ef63bc7a49c5a573089`，test APK `ba0b5944a6f43d1771f19cd0a7c579d266ecaa19ff62b7a54ddf4c5f471b1b15`。它在下述Plan入口修复之前构建，不能冒充Plan入口验收。
- 预算审查另发现Plan审阅执行入口写死32模型/64工具/10万token，与Goal设置不一致。已改为点击执行时读取当前会话Goal预算，并在实际UI执行闭环测试断言持久Goal预算与该配置一致；API36 arm64 developer专项 `PlanExecuteCloseLoopDeviceTest` **2/2通过**（执行持久预算、修改回DRAFT），目录 `build/public-eval/plan-configured-budget-20260928/`，制品摘要见该目录artifacts.json，专属模拟器已关闭。该测试使用本机loopback模型，没有真实账号调用。
- Plan修复后双渠道unit/lint/debug APK/test APK、detekt通过（`build/automation-plan-budget-gates.log`）；最终spotlessCheck、check-all.sh --source及git diff --check通过（`build/automation-final-spotless.log`、`build/automation-final-source.log`）。未提交、合并、推送；主目录并行Plugin工作未改动。
- 新长程默认的双渠道unit/lint/debug APK/test APK与detekt通过（`build/automation-long-task-gates.log`）；source gate通过（`build/automation-long-task-source.log`）。这是主机计数与小规模设备流程验证，不是千次真实调用长稳验收。
- Automation JVM **53/53**。双渠道unit/lint/debug APK/test APK、automation lint、detekt通过（`build/automation-progress-gates-r3.log`）；通用发现提示后双渠道gate通过（`build/automation-recovery-discovery-gates.log`），fixture预算引用变更后test APK与detekt通过（`build/automation-recovery-default-budget-build.log`）。spotless/source gate通过，收尾另验文档。

本轮只请求并使用专属模拟器；未使用物理真机、真实账号或付费服务，不作为官方 AndroidWorld 榜单分数。API36/Helix Accessibility 动作空间与官方推荐环境不同。历史15/15 P5属于旧clean源码，不挪作本轮完整P5通过。
