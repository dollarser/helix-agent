# Bug Fix: 工具偏好范围优先级与执行开始检查

Status: fixed
Date: 2026-09-15
Related HXA: HXA-200

## Problem

日期：2026-09-15。基线：`fa01dde6`。范围：HXA-200 收尾复核的 C1/C2；沿用 ADR-0052 第 5/7 条，不改变已接受决定。

代码/测试/复现脚本本地提交：`397608fc`，具名13文件，cached diff/check已核对，未push/merge；共享任务文档及本记录留在工作树，不夹带其他任务文档。

## Impact

用户的跨范围询问限制被忽略；等待审批时设置禁止也可能仍执行旧卡批准的调用。旧测试对此给出错误绿灯。

## Root cause

- `effectivePreference` 在外层 DENY 后采用最窄范围，导致 GLOBAL/WORKSPACE ASK 被 SESSION ALLOW 覆盖。旧主机和设备测试错误地要求免卡。
- Dispatcher 只在请求审批前读取偏好。等待期间改为 DENY 后，批准旧卡仍会消费证明并执行；旧测试以 source 只读一次为正确行为。

## Fix and invariants

- 适用范围内先选 DENY，再选 ASK，最后处理有效 ALLOW、失效与默认来源。旧测试改为验证外层 ASK 仍要求确认。
- 偏好 source 提供短时 `withExecutionStart` 边界，生产 service 的查询、设置、删除和可信基线写入使用同一 monitor。真实 service 是组合根中 Dispatcher 和曝光查询共用的单例；其他可变 source 实现也必须将写入纳入该边界。
- 请求审批前的检查继续用于呈现卡片；审批结束后，在执行开始边界重新检查注册契约、当前能力/Policy和偏好。DENY 拒绝并持久结算，不消费旧卡证明、不进入执行器。
- 最终偏好读取、证明消费与开始时间记录对偏好写入互斥。若设置先完成，新限制生效；若执行先承诺开始，之后的设置只影响后续调用。查询所用持久 revision 的写入也处于同一边界，不再发生同一 service 的并发 read/update 丢失。
- 用户审批等待、执行开始通知和实际执行器均在该锁外。若开始前出现新 ASK 且没有 proof，释放锁后获取确认，再次检查；已有精确 proof 不重复出卡。卡片呈现事实不被修改。
- 把纯审批解析与可信 PolicyInput 组装移出 Dispatcher；执行、取消和结算状态所有权不迁移，不增加执行器或任务状态源。

## Alternatives considered

不修改ADR来追认最窄范围优先或旧卡覆盖新DENY；不撤掉生产偏好接线；不只增加一次无同步查询；不在用户等待或长执行期间持有锁。选择现有单例service的短时monitor使设置和开始有明确顺序。

## Regression verification

本轮新增/修订范围：跨scope ASK的主机与真实Room设备断言；等待中DENY后批准旧卡的零执行/未消费/单卡断言；新ASK只获取一次proof且等待/执行在锁外；开始后DENY不取消当前调用；真实service写入与开始边界串行的线程测试。

精确设备入口：`scripts/debug/2026-09-15/verify-apref-closeout.py`，要求调用者提供 JDK17/Android SDK 环境变量。自建只读 AVD，拒绝已有设备（含offline），finally结束自有进程，归档XML并要求每组9项偏好+13项调度共22项、零fail/error/skip。

首次设备运行 API29 consumer 的22项通过，但脚本XML归档路径错误；已改为实际AGP目录，不将脚本异常当产品失败或完整设备验收通过。

最终源码（含纯函数提取）设备运行：`verify-apref-closeout.py` exit 0，API29/36 × consumer/developer各22/22（9偏好+13调度），合计88通过，零fail/error/skip。归档`build/hxa200-closeout-20260915-173545/`含每组XML、命令日志、summary.json、自有模拟器退出记录与空的最终adb设备列表。前一轮同矩阵也通过，但最终结论采用本轮归档，不累计成176个独立用例。

最终主机命令（JDK17）：`./gradlew spotlessApply spotlessCheck detekt :core:model:test :core:policy:test :core:agent:test :tools:framework:test :core:storage:testDebugUnitTest :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest`，exit 0。核心819项通过；consumer 508通过/4条件skip，developer 534通过/4条件skip，零失败。关键Dispatcher 64项、service 18项通过。日志：`build/hxa200-fix-final-verification.log`；XML汇总：`build/hxa200-fix-host-summary.json`，生成脚本`scripts/debug/2026-09-15/summarize-apref-host.py`。

单独执行`./gradlew :app:lintConsumerDebug :app:lintDeveloperDebug --continue --console=plain`，exit 1；两个flavor各报告同一锁定JGit jar的2项TrustAllX509TrustManager错误、0 warning、9 hint，没有本次改动新增错误。未suppress，未将P3记为全绿。日志`build/hxa200-fix-lint.log`。`./scripts/check-all.sh --source`与`git diff --check`通过。

## Residual risk

本修复不等于HXA-200全量完成：其他矩阵、取消PENDING的完整恢复路径、Room迁移跨API证据与独立JGit lint门禁仍按任务记录核对，不据两项修复关闭所有验收。同步范围是当前主进程组合根共用的service；不能将同一数据库的多个独立service实例或其他进程写偏好当成已支持的原子协议。ADR第8条逐调用偏好来源/revision的完整审计覆盖仍随HXA-200总矩阵核对。

## Related records

- [收尾复核与201交接](../development/hxa200-closeout-review-and-hxa201-handoff.md)
- [ADR-0052](../adr/0052-tool-approval-preferences.md)
- [产品任务包与验收命令](../development/product-completion-and-approval-plan.md)
