# Bug Fix: 下一HXA前的基线回归修复

Status: fixed
Date: 2026-09-16
Related HXA: HXA-201
Affected modules: app, core/workspace, config/jgit, dependency configuration

## Problem

基线：`1cd1c137` 加保留的并行工作树。所有者明确要求先清理已知必过失败再进入下一HXA，并允许依赖升级。原API29 consumer完整设备报告为355通过、26条件跳过、18失败（399项）；不再因失败不属于前一个切片而直接推进。

## Impact

图片发送会错误阻断或使用旧能力快照，会话切换可能被旧刷新覆盖；API29 Git不可用，手动存储的可用状态未覆盖AppOp撤销。过期测试契约与测试间状态影响掩盖真实产品失败。

## Root cause

生产读路径把异步UI快照当作执行依据，异步刷新缺少当前会话身份复查；JGit依赖的Java API集合超出API29，平台权限检查缺少AppOp层。设备测试又假定固定Prompt、旧Goal状态、全列表同时可见和跨用例无状态。

## Fix and invariants

| 原失败 | 处理 |
| --- | --- |
| 4个图片发送/历史/撤销测试 | 生产`ProviderService.capabilitiesFor(provider, model)`误用异步UI行快照；改从当前持久配置读取能力，保留模型匹配与模型元数据约束。发送前与确认发送时都经过既有检查；缓存未刷新不会误挡，旧测试状态也不覆盖新持久快照的撤销 |
| 请求剩余token预算 | 系统Prompt分段已经进入真实请求；旧测试仅按4字节用户文本估1token。扩大fixture总预算以容纳真实系统段，并从实际wire messages计算input，仍精确断言输出上限等于剩余额度，保留无输出余量不发请求用例 |
| Goal单wake超时与重复恢复 | 按ADR-GOAL-001区分预算耗尽BLOCKED与普通进程中断PAUSED；继续断言累计预算、未完成预留清零、禁止第三次绕过预算，不恢复旧语义 |
| LiveGoalModelReport | 默认缺外部profile应条件跳过，实际本地Goal由同文件loopback用例覆盖；只给一半参数或提供无效参数仍失败，不消耗真实账号配额 |
| Provider两项界面测试 | 连接检查现在只抓一次目录；第二次显式检查才触发fixture的401。保持失败撤销可选性与阶段/错误文案断言，对齐当前可解释文案 |
| Artifact列表 | 使用真实当前时间创建新任务fixture并满足结束时间约束，避免1970年的记录落在长LazyColumn可见范围之外；仍验证打开、分享和收集行为 |
| Goal大字体发送按钮 | 使用非空Goal输入验证可发送布局，空输入不应可执行 |
| Share两项 | 显式打开现有会话详情后验证未绑定Provider；分享内容导入与绝不自动发送断言保留 |
| Tasks两项 | 等待实际持久投影行，而不是把Compose idle当作异步Room查询完成；仍通过真实服务取消并观察行消失 |
| 手动共享文件 | 测试显式取得平台手动文件权限；API30+由宿主分阶段变更AppOp，API29恢复有效AppOp访问状态（直接撤销runtime permission会杀死instrumentation，安装由独占runner清理）。生产读取同时检查AppOp，撤销后不再展示可读源。继续验证手动权限不授予Agent scope及删除确认 |
| JGit API29 | 升级至7.8.0及其POM依赖；开启NIO desugaring2.1.5。新版仍调用API29缺失的Java输入流方法，增加有校验的局部调用适配和自有helper，保留TLS fail-closed修复。helper覆盖部分读取、EOF、零进度、边界和IO错误；实际Git stage/status/diff设备路径通过 |

首次完整复测得到364通过、27条件跳过、8失败，其中包含全套顺序才暴露的问题，继续修复而非豁免：

- 会话查询：旧无效ID解析失败不得清空新选择；原子比较清除，界面刷新发布前复查当前ID。新增连续无效/有效会话切换回归。
- 共享文件：临时shell身份能通过权限检查却不改变文件系统访问，实测mkdir失败，因此不采用它。最终使用真实runtime grant与AppOp有效状态恢复；新增真实AppOp撤销测试。
- Tasks/Artifacts：等待持久投影后滚动到目标行/按钮再操作，不能把LazyColumn未组合的行当作数据缺失。
- 导航/Provider/Connector：等待抽屉关闭与控件实际可见；Connector从确定性状态开场，并用ActivityScenario等待重建后的Activity恢复，避免滚动落在旧composition；同时等待上方异步审批列表的真实最后一行组合完成，再滚动到Connector，防止空列表随后加载改变滚动范围。Provider在真实打开的模型选择弹层中验证失败配置不可选，避免折叠菜单产生假阳性。会话重命名等待实际持久结果，保留有界超时。

没有删除失败场景，没有用ignore/assume隐藏本地失败。默认条件跳过仅用于明确的外部Goal、Ollama/sglang模型profile；它们不属于离线必过门禁，不能在未选择profile时探测宿主服务。

API29 developer首次完整矩阵又发现13项失败：PRoot/CLI结果fixture未解析v16 scope引用；订阅生命周期fixture误调用真实账号目录探测；权限和产物列表未滚动、Provider错误文案未限定当前行；sglang默认探测宿主网络。继续修复：scope解析保持真实损坏/清理断言，权限页面先进入文件权限子页，订阅只准备fixture连接状态并保留真实Runtime作业生命周期验证，Ollama/sglang统一要求`realSelfHosted=true`。

订阅专项随后实证两处生产遗漏并修复：会话删除与刷新之间的check/read竞态改为同一Room事务快照；`PrivacyDeletionService`按v16完整引用解析scope，不再拼到app根下。受管PRoot结果目录加入明确的私有产物清理集合，元数据、目录、相近前缀和符号链接越界仍拒绝。预算fixture保持零Runtime作业断言，错误码对齐更早发生的`CONTEXT_WINDOW_LIMIT`。

API36持续logcat进一步确认`MANAGE_EXTERNAL_STORAGE changed`会由系统结束instrumentation进程。完整本地门禁因此按平台拆分：默认阶段不调度3个需宿主授权的场景，随后强制运行授权阶段3项和撤权后新进程1项；是同一门禁的分阶段执行，不删除/跳过这些测试。撤权用例持久保存前一PID并校验新进程、真实权限和文件入口拒绝。另将居中选项行断言从顶部dp完全相等改为中心线的1物理像素舍入容差，保留滚动与触达断言。

API36 developer独立Runtime页面已实际启动且远端进程日志为`zh-CN`，旧fixture从缓存的英文instrumentation Context取标题，导致错误失败。并行未跟踪的`IntegratedRuntimeUiDeviceTest`已按当前`LocaleManager.applicationLocales`取资源（API29保持原路径）；只修期望语言，不放宽页面必须实际可见的断言，该文件随所属Runtime包保留，不夹带整个并行包提交。

API36全套顺序下，SessionDraft/SessionModel又暴露了默认1秒等待不足：Compose idle不等于Room/IO投影完成。异步界面测试统一使用有界10秒等待，条件成立即返回；未改产品执行预算、删除功能断言或把失败改成条件跳过。

## Alternatives considered

没有通过删除用例、忽略本地失败或放宽审批解决门禁。JGit升级本身不能补齐旧Android缺失的方法，因此采用当前稳定版加局部API适配；不降级至过时依赖、不放开TrustAll。shell身份不能提供实际文件系统访问，已由设备证据排除。API29直接撤销runtime permission会结束测试进程，因此测试恢复有效AppOp状态，独占安装随后清理。

## Regression verification

本轮脚本先保存后执行，输出位于忽略的`build/`。完整本地设备门禁命令（已配置JDK17和Android SDK环境变量）：

```bash
python3 scripts/debug/2026-09-16/run-pre-hxa-regressions.py --api29 --full
python3 scripts/debug/2026-09-16/run-isolated-api36-baseline.py
```

API36第二条包括常规套件与强制存储3+1阶段；原始`am instrument`单进程全类运行不能跨系统结束进程的权限变更。新建的AVD、模拟器进程均按所有权清理，既有其他设备不动。

证据：

- `build/pre-hxa-regression-20260916/initial.patch`：初始并行tracked快照；`initial-status.txt`保留未跟踪路径清单。
- API29首次专项：`build/hxa201-device-20260916-011101/`，42通过、4失败；修复后附件/Provider/产物35项全部通过：`build/hxa201-device-20260916-011338/`。
- JGit与手动共享文件API29专项：`build/hxa201-device-20260916-012006/`，2项通过。
- `build/pre-hxa-regression-20260916/jgit-artifact.json`：校验原始/变换hash，7个受影响class、1个新增helper，保留原许可证；未更改无关class，移除失效的原JAR签名。
- 主机：`./scripts/check-all.sh --build`通过（含全模块test、Debug/Release lint、构建与锁文件）；最终生产改动后再次执行双flavor app单测、四项app lint与Release构建，全部通过。XML汇总4407项：4399通过、8项既有外部条件跳过、0失败/错误。
- `./scripts/check-all.sh --artifacts`通过；最终`spotlessCheck detekt`与`./scripts/check-all.sh --source`通过。日志归档在`build/pre-hxa-regression-20260916/`。
- 最终API29矩阵`build/pre-hxa-device-20260916-023226/`：consumer完整401项，374通过、27条件跳过、0失败；developer完整531项，462通过、69条件跳过、0失败。API36最初因另一台设备占用原AVD而启动失败，改为新建独占AVD继续；未触碰另一台模拟器。设备runner为`scripts/debug/2026-09-16/run-pre-hxa-regressions.py`，拒绝借用已有设备，finally只结束自有进程。

- API36 consumer最终`build/pre-hxa-device-20260916-031722/`：常规374通过、25条件跳过、0失败，存储授权3通过与撤权1通过（同一撤权场景含准备阶段，不重复计算独立用例）。同AVD切换developer时ADB空输出255，未执行用例；单独新建AVD复测developer，不能将该中断计为测试通过。
- API36 developer最终`build/pre-hxa-device-20260916-035255/`：常规462通过、67条件跳过、0失败；存储授权3通过、撤权1通过，均无跳过。此前两次全套分别发现Runtime标题语言与SessionModel等待问题，修复后重新完整执行，未用专项替代失败的全套结果。
- 最新测试等待/布局调整后，API29双flavor补验9个相关测试类：`build/pre-hxa-device-20260916-040506/`，各19通过、1条件跳过、0失败。最新`spotlessCheck detekt :app:lintConsumerDebug :app:lintDeveloperDebug`通过（`async-ui-lint.log`）；全量主机构建后生产代码未再变化。

## Residual risk

四象限完整本地套件已通过；当时证据针对保留并行WIP的工作树，不能据此宣称整个产品包已验收。2026-09-16所有者随后授权接手剩余WIP，Runtime标题修正已随所属测试收口，见[接手记录](../evidence/development/wip-takeover-2026-09-16.md)。真实账号、物理设备、长稳和单独的重启协议仍按各自profile记录，条件跳过不代表通过。

## Related records

审批建议与新增工作的边界见[审批体验复核](../evidence/development/approval-experience-review-2026-09-16.md)。该方案不扩大ALLOW或Plan批准的执行授权。
