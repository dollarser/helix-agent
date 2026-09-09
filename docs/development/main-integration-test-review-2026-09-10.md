# main 合并与测试记录复核

日期：2026-09-10。本地 main 从 a73cb8b 快进至 81d60e6，包含 22d2481 的基线修复、193de8e 的 HXA-161～184 实现和 81d60e6 的归档空白清理；未推送。

## 合并保护与验证范围

- main 的未提交文件先保存路径、SHA、二进制 diff 和文件副本。五个重叠的 tracked 文件另存 Git stash（标记 `main integration: preserve overlapping premerge edits`），没有删除 stash。
- Composer 的 LocalConfiguration 修复已包含；旧 AdaptiveFileControls 已被新的文件 UI 替代，旧 Header 高度判断也已被新布局替代，不复活旧界面。AGENTS 的持续执行/独占模拟器要求等价保留。Autofill 单测保留已双 API 验证的有界 focus/input readiness 和 socket 取消处理，不用旧版无等待直调覆盖。
- main 独有的 BrowserOwnerDeviceTest、长稳夹具、runner、文档和测试记录保持未提交；同内容参考图与 Goal 指南现在由合入提交跟踪。备份在来源工作树忽略目录 `build/debug/2026-09-10/main-integration/`。
- 正在运行的 p2-api36-on-r4 与 emulator-5554 未中断、未安装新 APK；未在 main 执行 Gradle 构建，避免改写在用制品或与测试任务争用构建目录。其冻结 gitCommit=a73cb8b 及 APK SHA 仍是本轮身份，不能随 HEAD 改写。
- 产品代码的完整主机/双 API 证据见 [HXA-184](../completion-records/HXA-184.md)。新增未提交测试夹具尚未完成合并后的编译与设备验收，不能据此宣称整个 dirty main 全绿。

## 测试发现与建议

| 项目 | 原始证据与当前判断 | 下一步 |
| --- | --- | --- |
| EV-04 Goal 夹具不兼容 | MainAppCombinedSoakDeviceTest.driveGoal 仍调用已删除的 goalCriteria/bindGoalCriterion，以 LOCAL_TOOL_SUCCESS 自动完成；新实现采用 ADR-0040 的 goal.report | 必须先迁移脚本化模型和断言：成功发结构化报告；暂停显式触发用户暂停；blocked 单独断言。先编译与短 pilot，再开长稳，不能为旧夹具恢复旧产品机制 |
| Autofill 长稳失败 | p2-api36-on-r3 在33轮后报 Activity regained input focus after system Autofill UI；r2 为60秒内找不到 HTML EditText；API29-on 为 getAccessibilityInteractionController called when there is no mView | 短回归通过不足以覆盖周期性 recreate/前后台/系统 Autofill UI。失败时记录窗口 owner、焦点、生命周期阶段、节点来源与 WebView 版本；区分过期测试节点和产品交互失效，勿仅加重试或放宽超时。当前 r4 尚在执行，不预判结果 |
| 正常结束被判进程死亡 | p2-api36-off-3 instrumentation.log 为 OK (1 test)，result.json 为 process-death | 当前 runner 已有 done 阶段保护，属于已有修复候选，不能重复宣称未修。原失败仍保留；本次只跑无设备故障自测17/17通过，端到端修复以新轮正式结果为准 |
| WebView 身份记录错误 | r3 identity 的 webViewProvider=null、webViewVersion=33；代码匹配任意 Version 字段，可能取到最低 target SDK 等信息 | 按当前实际 provider/package 信息解析并保留原始 dumpsys；未知应显式 unavailable，不能用33作为可信 WebView 引擎版本。补典型输出解析回归 |
| EV-03 FD 超限 | pilot 128→137，门限+8；已有取证将新增描述符归类为 goldfish pipe | 节点类型证明是模拟器图形通道，不足以排除 App/夹具生命周期导致的持有，也不能单凭三台并行证明因果。保留 FAIL_RESOURCE，在独占设备做相同负载/空闲与页面释放对照，定位谁打开/关闭；不得直接排除这类FD或放宽门限 |
| 系统 Binder 采样 | 多轮 system UID-proxy 不可用，结果为 INCONCLUSIVE；API29-off-3 完整10805秒仍不是24小时验收 | 保留功能完成和资源证据不足的区别。采样接口不可用应记录能力缺口，不填零，不拼接中断轮次时长 |

原始证据都在 main 忽略目录 `build/emulator-verification/`；重点为 `ev02-autofill-soak/`、`ev03-app-soak/pilot/` 和 `ev03-app-soak/pilot-fd-forensics.md`。未编辑历史 result.json、冻结身份或当前测试的 handoff/run-index。

## 后续顺序

1. 当前长稳保持原制品完成；由执行任务独占其模拟器，合并任务不接管。
2. 适配 EV-04 Goal，修正版本采集并核实结束判定回归；将现有未归档测试脚本按日期整理时避免移动正在执行的脚本。
3. 新 main 与新增夹具一起编译、短测、重新冻结，再按失败影响范围复测；EV-05～08 按各自计划推进，不因这一轮 Autofill 阻塞全部独立工作。
4. 真实服务、Root/低内存/OEM、签名和商店验收仍保留原外部边界。
