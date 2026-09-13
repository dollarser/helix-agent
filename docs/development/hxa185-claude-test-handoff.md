# HXA-185：合并后夹具修正与 Claude 测试交接

日期：2026-09-10。状态：实现已写入，**未编译、未运行测试、未完成验收**。所有者明确要求模拟器测试交给 Claude 小模型，本轮 Codex 不运行测试，不启动、安装或操作模拟器。当前 main 的其他未提交测试工作保留；未提交本轮修改。

## Claude 接手核实（2026-09-10，只读核查，未动在途 R4）

**核实结论：修改范围表（下表）7 项修改的关联文件 17/17 全部存在；预编辑备份 `build/debug/2026-09-10/hxa185-before/`（app/feature/scripts + sha256.json，02:51）在。** 已 grep 抽查确认在源的关键项：EV-04 `goalMechanism=model-report-user-pause-v1` 在 `MainAppCombinedSoakDeviceTest.kt:821` + `run-ev04-mainapp-soak.py:292`（拒绝旧机制），`goalCriteria`/`bindGoalCriterion` **无残留**；autofill 夹具改用独立 `AutofillInputProbe`（1 处）+ `AutofillFailureEvidence`（3 处）；`run-browser-autofill-soak.py:33` 依赖 `debug/2026-09-10/soak_evidence.py`。其余两项（夹具 PSS `Debug.getPss` 单位、WebView 身份 parser）文件在、未逐项代码抽查，留待编译/自测时验证。**仍未编译、未运行测试**（均为 `??` 未提交工作区文件）。

**⚠ 关键差异：工作区 autofill 夹具已被 HXA-185 更新，≠ 在途 R4 的制品。** `BrowserAutofillSoakDeviceTest.kt` 现为 `AutofillInputProbe`（10s 上限）+ `autofill-failure.json` 取证，其中 `focusSettle`/30s 预算计数为 **0**；而正在跑的 `p2-api36-on-r4` 用的是**旧制品** `f4df02c6`（`focusSettle` 30s 有界轮询版，02:34+08 启动时编译）。二者是**不同制品**，结果不可互相替代：R4 判别的是 api36 fill-dialog 焦点夺走是**瞬态还是持久**（系统行为，与用哪套 fixture 无关）；HXA-185 新轮次必须用重编的 `AutofillInputProbe` 版另起。

**时间线 + R4 实况**：R4 于 02:34+08 启动（focusSettle 版）→ 02:51 Codex 写入 HXA-185 源修改（`hxa185-before` 备份）→ 工作区现为 HXA-185 版。**R4 已结束 = FAIL_FUNCTIONAL**（2026-09-09T19:54Z / 03:54+08，elapsed 4788s ~80min，非预计的 ~05:34+08 全绿）：39 cycles ok（15 warmup + 24 active）→ active 第 25 轮/global 40 死在 `focusInput:597`（a11y EditText 节点满 100s 未暴露）。**R4 判别结论**：焦点竞态（R3 的 :542）= **瞬态**、focusSettle 修复**有效**（global 39 settle 21ms）；a11y 节点暴露 = **持久重尾（>100s）** = **API36 系统 WebView 稳定性特征**（非产品，autofill 39× 正常，OOM/泄漏/竞态全排除）——取证 `ev02-autofill-soak/p2-api36-on-r4-forensics.md`。**R4 结果属旧制品**（f4df02c6 focusSettle 版），**不使旧结果变成新 main 验收**；其判别结论（a11y 重尾 >100s）**直接支持** HXA-185 `AutofillInputProbe` "不放宽到 100s" 的方向——10s 预算在 api36 上会**更早**（10s）暴露同一 a11y 重尾，但 `autofill-failure.json` 会**确证**系统 WebView 现场，这正是新 fixture 的价值。**不再加宽 a11y 预算**。

## 修改范围

| 项目 | 已做修改 | 尚需验证 |
| --- | --- | --- |
| EV-04 Goal | 移除旧 goalCriteria/bindGoalCriterion；奇数轮经 write/read/goal.report complete 后核对 Goal 与 ToolCall；偶数轮等待真实模型请求，调用 stopTask(pause=true)，核对 CANCELLED Turn、pauseRequestedAt 与 PAUSED Goal | 双 flavor 编译、成功/暂停/取消语义与网络取消；blocked 使用既有 GoalModelReportDeviceTest.modelBlockerRequiresExplicitRepair 单独验证，不能计作暂停 |
| Goal 证据版本 | soak-done 增加 goalMechanism=model-report-user-pause-v1，host 拒绝旧机制；保持原奇偶轮及 goalSuccess/goalStop 字段 | 缺字段、旧值、奇偶计数错误必须失败；1块 pilot 只覆盖完成，暂停需要至少2块或专项 |
| 夹具 PSS | Debug.getPss 已返回 KB；删除综合夹具额外 /1024 | 核对 device KB 与 host PSS 同数量级，不回写旧记录 |
| Autofill | 独立 AutofillInputProbe 只取当前 View 所在窗口的新节点；节点/窗口显式释放；只对 no-mView 的窗口过渡异常重取，不吞掉任意异常；就绪等待每次最多10秒并受当前周期剩余时间限制 | 输入、填充、编辑、保存；前后台、recreate 后新节点；超时仍失败，不能延长到100秒以追求绿色 |
| Autofill 失败取证 | 失败时写 autofill-failure.json：run/cycle/stage、View identity/attach/focus/windowId、窗口焦点、真实 WebView 包/版本；原异常仍抛出，采集失败只附加异常。host 拉取该文件 | 文件关联本轮；字段缺失明确不可用；不输出正文、密码、URL；断言失败必须仍失败 |
| WebView 身份 | 只解析 dumpsys 的 Current WebView package (name, version) 行；保留 webviewupdate-start.log；缺失为 unavailable/null，禁止用通用 Version:33 或默认包代替 | API29/36真实输出及 parser 正反例，未选择 provider 时不可伪造版本 |
| FD 归因 | 新 opt-in DescriptorPhaseProbeDeviceTest：idle/storage/notification/activity/combined 五臂，子夹具前后、空闲与固定slot末尾采样；记录 FD编号、device/inode、targetHash、类型、errno和截断 | 同制品独占对照；完整记录所有描述符，包括goldfish；不排除、不放宽+8门限、不注入GC、不声称直接定位了创建栈 |

没有修改生产权限、数据库、Goal 决策机制或引入新依赖。依据 accepted ADR-0040/0004/0033；不需要新的架构决定。

## 接手前：保护在途轮次

1. **p2-api36-on-r4 已完成 = FAIL_FUNCTIONAL**（2026-09-09T19:54Z，elapsed 4788s，非预计全绿）。冻结 APK `f4df02c6`（`focusSettle` 30s + a11y 100s 版）。R4 判别结果：**焦点竞态（:542）= 瞬态、focusSettle 修复有效**（global 39 settle 21ms）；**a11y 节点暴露（:597）= 持久重尾 >100s = API36 系统 WebView 稳定性特征**（非产品，autofill 39× 正常，OOM/泄漏/竞态全排除）。取证 `ev02-autofill-soak/p2-api36-on-r4-forensics.md`。**结论**：**不再加宽 a11y 预算**（100s 已逼近 cycleMax 112s，加宽 = 假绿）；api36 ON 用旧加宽 fixture 无法达成。其结果属旧制品，**不充当新 main 验收**；但判别结论（a11y 重尾）**验证了** HXA-185 `AutofillInputProbe` "不放宽 + autofill-failure.json 确证" 的方向。HXA-185 新轮**必须重编后另起**（用 `AutofillInputProbe` 版，会更早 10s 暴露同一 a11y 重尾并取证确证）。设备现已空闲。
2. 新轮必须由 Claude 自己独立启动模拟器，记录PID/serial/AVD/bootId，拒绝借用其他任务的实例，finally 关闭自己启动的进程。不要因为旧 serial 还在线就接管它。
3. 完成旧轮之后再构建、冻结本轮 app/测试APK和runner源码哈希。新 runId/输出目录；保留所有失败记录，不拼接轮次时长。
4. `scripts/run-browser-autofill-soak.py` 新进程依赖日期目录的 `soak_evidence.py`，冻结脚本时必须一并保存。当前在途 Python 进程已经加载旧代码，不重启以换版本。

## 主机验证（由 Claude 执行，本轮均未运行）

```sh
python3 scripts/debug/2026-09-10/test_soak_evidence.py
python3 scripts/test-run-browser-autofill-soak-faults.py
python3 scripts/test-run-ev04-mainapp-soak-logic.py
python3 scripts/test-run-ev04-mainapp-soak-fixture-logic.py
./gradlew spotlessApply detekt test lintDebug :app:lintConsumerDebug :app:lintDeveloperDebug --continue --max-workers=1
./gradlew :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest :feature:browser:assembleDebugAndroidTest --max-workers=1
sh scripts/debug/2026-09-09/run-documentation-gates.sh
```

先检查格式/编译；新增文件尚未经过格式器或编译器。本轮给出了源码修正和回归用例，没有将人工阅读当测试结果。修复验证中发现的问题后再冻结，不能削减断言。故障脚本里的模拟进程测试不是实际设备验收。

## 模拟器验证顺序

- **Goal短项**：GoalModelReportDeviceTest 全类（包含 modelBlockerRequiresExplicitRepair、manualPauseOverridesCompletedReport、cancellationOverridesCompletedReport）；EV-04 至少覆盖奇数完成和偶数用户暂停，核对当前run的goalMechanism、实际Tool结果、暂停持久字段。保持 blocked 无法直接 Continue、修复确认后才可继续。
- **Autofill短项**：BrowserAutofillDeviceTest 与 BrowserOwnerDeviceTest，之后新runner selftest、pilot；on/off 均验证。新 probe 未通过设备验证前不要开24h。失败时同时读 instrumentation.log、device-autofill-failure.json、device-cycles.jsonl、progress、窗口阶段与 webviewupdate-start.log。
- **结束判定**：保留旧 off-3 的 FAIL_FUNCTIONAL；done 阶段正常退出修复已由原任务写入，必须用新冻结轮次核实，不能只因旧日志有 OK 就人工改 PASS。
- **FD对照**：每个workload使用独立进程和不同runId，固定相同APK/API/AVD内存、iterations=10、slotSeconds=30，其他模拟器负载尽量一致；先 idle，再 storage/notification/activity/combined。记录各臂耗时；超出slot即失败并重新审查配置，不能拼接或悄悄延长。测试方法为 `com.helix.app.diagnostics.DescriptorPhaseProbeDeviceTest#traceFixtureAndIdleDescriptorOwnership`，参数如下（仅由自己的独占runner传递）：

```text
-e helix.fd.phases true
-e helix.fd.workload idle|storage|notification|activity|combined
-e helix.fd.iterations 10
-e helix.fd.slotSeconds 30
-e helix.fd.runId <唯一且只含字母数字下划线或连字符>
```

取回 App external files 中 `fd-phases-<runId>.jsonl` 后执行：

```sh
python3 scripts/debug/2026-09-10/analyze-fd-phases.py <取回的JSONL文件>
```

FD编号/inode可能复用，采样只能定位增长阶段与存留模式，不能据此证明同一对象从未释放。goldfish 类型也不能单独证明纯模拟器问题；有阶段相关证据后才设计更细的创建/关闭路径诊断。这个诊断不替代原资源门禁。物理设备与系统 Binder 无权限采样仍是独立缺口。

## 收口要求

保留本轮源文件变更及预编辑备份（忽略目录 `build/debug/2026-09-10/hxa185-before/`）；一次性编辑脚本不要重跑。Claude 写明 actual command/result、制品hash、runId、通过/失败/条件跳过/外部阻塞和自建模拟器关闭记录；全部所需验证后才更新 HXA-185 完成记录。不能使用本轮“未运行测试”的状态替代验收，也不能误把写了诊断当系统问题已经修复。
