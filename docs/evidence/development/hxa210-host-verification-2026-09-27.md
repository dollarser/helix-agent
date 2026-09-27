# HXA-210 Workspace 主机实现与验证

日期：2026-09-27。基准 HEAD `7a2f47290f6f995f64847be6a203a0f83c6d5a3b`；dirty=true，包含前一阶段尚未提交的 HXA-227 工作。本记录仅证明 host 范围，完整本地验收见 HXA-210 完成记录。下文早期设备 not requested 是历史状态；所有者随后授权的本次运行见[模拟器证据](hxa210-emulator-verification-2026-09-27.md)。真实 Provider 仍 not requested。

## 模拟器修复后的最终 host gate

完成记录/status/roadmap/index 收口后的 `build/hxa210/final-source-gate.log` exit 0，文档、ADR、i18n 与 secret scan 通过；`git diff --check` exit 0。工作树保留未提交的 HXA-210/HXA-227 变更，不称为 clean checkout 或远端 CI。

最终 `host-candidate-final` exitCode=0、sourceUnchanged=true；`baseline-comparison-final.json` comparable=true，8 组 PASS→PASS。源码清单为 `9f40734c0c3c6e8fb5d58af76320854d7097ab697592f500adbe0dc896d53b99`。最终诊断 fixture 的 `final-diagnostic-fixture-gate.log` exit 0，覆盖两渠道 AndroidTest 编译、lint、spotlessCheck 与 detekt。下文较早 candidate 为历史记录。

追加系统 SAF 修复后的 `workspace-final-host-gate.log` exit 0；随后 `workspace-final-revalidated-host-gate.log` 完整脚本再次 exit 0，包含 JVM/lint/static、debug/release build、unit、依赖锁和 APK 边界检查。完整计数已保存 `verification-workspace-final-revalidated.json`，仍为下列总数、0 failure/error、两渠道各 4 项既有条件 skip。AndroidTest 的请求同步/诊断变更另外重新编译与验证。

最新已执行 candidate 为 `host-candidate-workspace-closeout`，`run.json` exitCode=0 / sourceUnchanged=true，源码清单 `09cf00175274656e90831f73ddc020164535e7aaf44b50d2663817ad37fd8b96`；`baseline-comparison-workspace-closeout.json` comparable=true，8 组 PASS→PASS。后续仅设备诊断 fixture 修改时仍需另记源码身份，不将本清单当作更晚源码的证明。

`./scripts/check-all.sh --all` 在 `build/hxa210/emulator-closeout-host-gate.log` exit 0；包含原生目录 allocation identity 与 Job 输出权限分类修复。consumer/developer AndroidTest APK 编译通过，见 `job-boundary-build.log` 与 `job-boundary-final-fixture.log`。未替换基线、删除断言或跳过失败项。

完整 JVM 计数已在 filtered eval 执行前保存于 `build/hxa210/verification-emulator-final.json`：model 148、agent 204、workspace 97、storage 199、consumer 821（4 skipped）、developer 866（4 skipped），failure/error 均为 0。保留既有条件跳过，不计为通过；文件同时记录六种 APK SHA-256。设备源码/制品与结果以每轮独立证据为准。

最终 `host-candidate-emulator-final` 8/8 PASS、`run.json` exitCode=0 / sourceUnchanged=true；`baseline-comparison-emulator-final.json` comparable=true、八组 PASS→PASS。源码清单 SHA-256 为 `6d18aa523de6a1f8064a0d7791749d8388bc29a94a36aa1acf873c420a553552`。不将此对比解释为设备效率收益。

## 存储决定与实现范围

所有者明确选择开发期 Room v1 baseline，仅保留文件。schema 仍为 version 1，增加 workspace resource、session binding、model-request binding 三张表，共 51 tables；没有 v1→v2 migration。旧共享文件不迁移、不删除，也不承诺保留旧开发数据库的会话行与产物索引。

本次实现覆盖：独立默认目录、资源身份和可用状态、规范等价 Path 去重、请求绑定与 revision、文件与 Linux Job 输入/输出参数冻结、项目指令来源、绑定后的 Git/手动终端来源、SAF 原位文件后端、私有备份回收站、会话设置和目录能力说明。model intent 仍在业务边界 strip，没有更改 Dispatcher、approval proof 或 permission resolver 的所有权。

创建中断留下无 witness 目录时保留其文件，以新身份创建默认目录；观察到资源失效后拒绝旧引用，显式重新选择才登记新资源。SAF witness 使用 Provider document ID 与已登记 grant 时间，不猜测真实路径，也不证明提供者不可观察的物理文件替换。自动读取项目指令只在当前文件读取规则允许时发生。

文件后端的版本检查是乐观检查，不宣称跨 Provider 原子 CAS。已进入写入/重命名/删除阶段的失败按可能存在部分效果处理。可恢复删除仅接收可完整备份的普通文件（32 MiB 上限），记录备份哈希及阶段；恢复前重验哈希，同名冲突不覆盖，中断不自动重放删除。备份 receipt 独立于用户文件树及回收站内容列表。

## 已执行的命令

```bash
./scripts/check-all.sh --all
./gradlew spotlessApply :app:assembleConsumerDebugAndroidTest \
  :app:assembleDeveloperDebugAndroidTest detekt --console=plain
git diff --check
```

全量 gate：`build/hxa210/host-gate-final2.log`，exit 0。包括 source/doc/ADR/i18n/secret、spotless、detekt、debug/release 和 consumer/developer lint、全工程 JVM tests、debug/release APK、36 个依赖锁文件检查，以及集成 Runtime APK/process/UID/channel boundary 检查。

补充 AndroidTest 编译：`build/hxa210/android-test-final.log`，BUILD SUCCESSFUL。包含 Workspace Room 关闭重开 fixture、可控 DocumentsContract Provider 的 delete-only 反例与 Workspace 备份恢复 fixture。仅编译，不是设备通过。

| 测试任务 | tests | failures/errors | skipped |
| --- | ---: | ---: | ---: |
| core:model:test | 148 | 0 | 0 |
| core:agent:test | 204 | 0 | 0 |
| core:workspace:test | 97 | 0 | 0 |
| core:storage:testDebugUnitTest | 187 | 0 | 0 |
| app:testConsumerDebugUnitTest | 816 | 0 | 4 |
| app:testDeveloperDebugUnitTest | 861 | 0 | 4 |

计数在执行 HXA-227 选定类 candidate 之前保存，避免把后续过滤运行的 XML 误当作全量结果。4 skip 是既有可选外部 profile/输入条件，不计通过。完整汇总与六个 APK SHA-256：`build/hxa210/verification-summary.json`。

## HXA-227 baseline / candidate

```bash
python3 scripts/run-agent-eval-host.py --output build/hxa210/host-candidate
python3 scripts/run-agent-eval.py compare \
  --baseline build/hxa227/host-baseline-final/envelopes.json \
  --baseline-root build/hxa227/host-baseline-final \
  --candidate build/hxa210/host-candidate/envelopes.json \
  --candidate-root build/hxa210/host-candidate \
  --output build/hxa210/baseline-comparison.json
```

fresh candidate 8/8 PASS，sourceUnchanged=true；比较器 comparable=true，八组均 PASS→PASS。fixture/environment/verifier 控制条件保持一致；只证明选定 host 边界没有回归，不证明设备任务成功率、token 成本或 Harness 收益提升。

- candidate sourceManifestSha：`4379d8bb2449e93f654cf0c0da4e7df39c4ef3b24f7524b9a886e90243f5024b`
- fixtureSha：`79a54bbaaf4905fd6455e82eb083ca7d0ed684888985f95332b3cba471589920`
- environmentSha：`2e059e25a783ce4fe952ecd2a38dc24e040930d3b81b9a2c2941ca133cb413a7`

## Gate 失败与修复

- tools/framework 旧测试仍引用已删除的 `SessionPermissionMode.WORKSPACE`，以及旧 configVersion/mode 含义；更新为现有 WORKSPACE_TRUSTED/version 2，需出现审批的用例使用 APPROVAL_REQUIRED。没有恢复旧权限行为、删除或跳过测试。
- Room baseline 重新导出后，契约测试中的 tool_calls 列顺序与当前实体不一致；同步现有 state/modelIntent 顺序及三张新表。
- SAF 只读 facade 回归要求在没有可恢复后端时继续明确拒绝；补能力检查，保留原断言。
- 锁文件第一次检查刷新两条旧 Room migration lint classpath 记录；版本不变，第二次 36 文件检查通过。
- 实施过程的编译/格式/静态检查错误已修复；最终命令另存，失败日志不作为通过证据。

## 尚未闭合的范围

HXA-210 保持实施中，不生成整项完成记录。仍需完成私有目录清理对运行任务、共享引用、产物和备份的完整生命周期验收；当前删除会话保留文件，不做自动资源清理。还需设备上的运行中切换/审批/Job、真实进程中断恢复、系统 picker 与 grant 撤销、SAF Provider 失联，以及 320/360/412dp 和大字体 UI 专项。

这些设备项当前 not requested；没有启动或使用模拟器/真机，也没有调用真实服务或账号。Host fixture 与 APK 编译不能替代上述验收。

## 持续 Goal：持久引用补强

在上述固定 candidate 之后，补充 `WorkspaceReferences` 及 DAO 单次查询，分别统计拥有者会话、当前绑定、历史模型请求和跨会话产物。返回值明确是保留事实，不是删除许可；清理执行者仍须取得准入并在持久资源 fence 的事务内复检。历史请求在切换后保留旧资源的 JVM 反例已加入；真实 Room fixture 增加跨会话产物在原会话删除后仍保留资源的断言。

本增量 `:core:storage:testDebugUnitTest`、detekt 通过（`build/hxa210/retention-gate.log`）；consumer/developer AndroidTest APK 编译通过（`build/hxa210/retention-fixture.log`）；`git diff --check` 通过。设备仍 not requested。此前 sourceManifest/APK 身份仅对应此前记录，不代表本增量；完整最终 gate 与 candidate 将在生命周期实现收口后刷新。清理准入、状态转换及执行尚未完成，HXA-210 和持续 Goal 保持 active。

## 持续 Goal：显式清理与 UI 增量

后续增量实现应用私有目录显式清理：复用 execution ownership 准入，与手动文件操作互斥；事务内复检引用和备份，持久化 `CLEANUP_FENCED`，原子移入隔离目录，再经 `CLEANUP_QUARANTINED` / `CLEANUP_PURGING` 到 `DELETED`。排队调用的显式资源引用也参与保留判断。清理不自动重放、不跟随子符号链接、不处理外部目录；身份变化停止。

Files 增加三语言永久删除确认，确认目标固定为当时的资源，完成后移除来源；清理阶段允许显式重试。来源读取移到 IO，避免 Compose 初始化触发 Room 主线程查询。晚到的读取失败只允许将 READY 标记为 UNAVAILABLE，不能覆盖清理阶段。

`build/hxa210/cleanup-ui-final.log` 记录 storage、consumer/developer 单测、两种 AndroidTest APK 编译、spotlessApply 和 detekt 全部通过；`git diff --check` 通过。新增 JVM 反例覆盖引用/备份拒绝、fence 后禁止绑定/请求、符号链接目标保留、rename 后中断、隔离目录身份变化、缺失源不能伪报完成、purge 中断显式恢复、目录删除后的状态恢复，以及晚到的失败不覆盖阶段。UI 初始化测试确认不读取 live sources。

这些是增量 host 证据，不替代最终全量 gate、稳定 candidate 或设备验收；设备仍为 not requested。完整生命周期的集成边界审查继续进行，HXA-210 保持实施中。

## 持续 Goal：引用写入与清理 fence

集成审查补上两个入口：重新选择来源仅允许重建 UNAVAILABLE 身份，不能 retire 清理中的资源；产物新增、刷新和引用复制通过 Room 事务检查注册 Workspace 仍为 READY，再写入引用，和清理 fence 使用同一数据库串行边界。

`build/hxa210/reference-fence-gate.log` 的 storage 单测、spotlessApply、detekt 通过。新增“显式选择不能覆盖 fence”的 JVM 反例；真实 Room fixture 增加清理 fence 后 close/reopen，迟到的产物注册被拒绝且文件仍保留。该 fixture 只编译，consumer/developer AndroidTest APK 与 detekt 通过见 `build/hxa210/reference-fence-fixture.log`，不宣称设备执行通过。

## 持续 Goal：完整 host gate 与请求切换 fixture

`build/hxa210/cleanup-full-host-gate.log`：`./scripts/check-all.sh --all` exit 0，包含完整 host 测试、静态检查、两渠道 debug/release lint/APK、36 个依赖锁文件和 Runtime/process/UID/channel 制品检查。六组全量计数为 model 148、agent 204、workspace 97、storage 198、consumer 817（skip 4）、developer 862（skip 4），failures/errors 全为 0。汇总保存于 `build/hxa210/verification-summary.json`，此前版本保存在 `verification-summary-before-cleanup.json`；这些计数在新的过滤 candidate 运行前归档。

随后增加 `WorkspaceRequestBindingDeviceTest`，通过真实 loopback provider、Dispatcher 和 Room 准备“请求挂起时切换、原路径进入审批、当前 DENY 阻止旧审批写入、成功产物仍定位旧来源”两条端到端反例。`build/hxa210/request-binding-fixture-final.log` 记录 consumer/developer AndroidTest APK 编译、spotlessApply 和 detekt 通过；测试未在设备执行。实际进程重启、Job、系统 SAF 和窄屏大字体的边界列入 [设备验收计划](hxa210-device-acceptance-plan.md)。

## 持续 Goal：baseline 比较与 Runtime 准入测试

`build/hxa210/host-candidate-cleanup/run.json` 记录 exitCode 0、sourceUnchanged=true；和固定 `build/hxa227/host-baseline-final` 比较的 `build/hxa210/baseline-comparison-cleanup.json` 为 comparable=true，8 组均 PASS→PASS。无 token/turn/model-call 等指标配对，不能据此声称效率收益。

随后将应用清理入口的既有 owner 准入提取为 `WorkspaceCleanupAdmission`，不修改 Dispatcher、ExecutionOwnership 或 Runtime 状态机。直接测试运行中调用拒绝清理、retained Job 在调用结束及 host 对象重建后继续阻止清理、settle 后才允许、清理期间拒绝新启动、清理异常释放临时 permit。`build/hxa210/cleanup-admission-gate.log` 中 consumer/developer 全部单测、spotlessApply、detekt 通过。这个增量晚于上述 candidate；最终源码冻结时仍需刷新制品/candidate 身份。

## 持续 Goal：UI 边界与手动文件互斥

来源不可用时 Files 首页显示不可用，不把它仅描述成只读；即使旧 capability 为可写，UI 也不启用 mutation。会话设置加载来源失败显示不可用，取消继续传播；长清理说明可滚动。

`WorkspaceLayoutDeviceTest` 准备 320/360/412dp、中文/英文 fontScale 2，以及默认字号的长目录、不可用状态和选择按钮可达断言。`build/hxa210/workspace-ui-boundary-final.log` 记录 consumer/developer 单测、两种 AndroidTest APK、spotlessApply、detekt 通过；设备没有执行。

之后增加清理与手动读取的并发反例：清理持有 facade monitor 时读取不能完成，释放后两者均正常结束。`build/hxa210/manual-cleanup-exclusion.log` 中两渠道单测、spotlessApply、detekt 通过，`git diff --check` 通过。最终完整 gate/candidate 仍需覆盖这些新增代码。

## 最新 host 收口 gate

`build/hxa210/host-gate-closeout.log`：完整 `check-all.sh --all` exit 0。期间审查修正 managedDirectory 失效更新，使它复用 READY→UNAVAILABLE 条件更新，避免覆盖并发 fence；新增确定性交错测试已包含在成功的 storage XML 中。随后 `build/hxa210/final-race-fixture-gate.log` 的 spotlessCheck、detekt、storage 单测、两渠道 AndroidTest APK 编译再次通过。

过滤 candidate 前保存 `build/hxa210/verification-closeout.json`：model 148、agent 204、workspace 97、storage 199、consumer 821（skip 4）、developer 866（skip 4），failures/errors 全为 0。该文件保存六个 APK SHA-256，与此前 verification-summary 分开，不覆盖历史制品身份。设备和真实 Provider 仍为 not requested；完整完成记录尚不能签发。

最终 `build/hxa210/host-candidate-closeout`：exitCode 0、sourceUnchanged=true，sourceManifestSha `8f4328fd2b47ffdd4ad7210141567fdfb8165b8bcd60a335629fa28fd917d1f2`。`build/hxa210/baseline-comparison-closeout.json` 与固定 HXA-227 baseline 比较为 comparable=true、8 组 PASS→PASS；fixture/environment 身份与固定 baseline 相同。无可支持效率提升的 token/turn 等配对，不报告效率收益。
