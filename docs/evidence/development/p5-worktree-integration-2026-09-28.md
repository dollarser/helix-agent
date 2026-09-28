# P5 工作树本地整合（2026-09-28）

## 范围与现场

所有者要求检查主工作目录是否仍有进程工作，并合并隔离候选。目标是主工作目录当前分支 `refactor/clean-slate-engine`，不是另外 checkout 中的 `main` 分支。

写入前检查：应用会话列表仅当前 Helix 会话 active；未观察到其他 Helix agent、Gradle build client、P5 runner 或 emulator 执行。Gradle daemon 为 IDLE；CodeGraph、ADB、shell、C2C bridge 等常驻进程仍存在，不把进程存在当作正在修改源码，也未终止这些服务。两次 HEAD/文件 SHA 检查一致。

目标 HEAD 为 `541d7ba0`，隔离基点为 `4e634c67`。目标四个未提交文件中，三个与候选完全相同，Goal fixture 为此前的中间版本。写入前保存 HEAD、status、逐文件 SHA、完整文件、working/index patch，并重新校验未变化：

- `build/p5-merge-20260928/main-before/`：目标四个文件。
- `build/p5-merge-20260928/candidate-before/`：候选九个文件。

候选提交 `384f23f1`（分支 `codex/p5-harness-closeout`）以非快进 merge 整合，保留双方祖先。无文本冲突；不删除或覆盖 `541d7ba0` 的既有实现，不推送。隔离 checkout 保留其旧制品与日志，本轮不归档。

## 语义交叉与门禁修复

主工作目录新增 `541d7ba0 fix(quickjs): preserve known effect truth`，不能把隔离版本“所有 timeout/cancel 都 NEEDS_REVIEW”的具体 oracle 原样视为合并后契约。

- UNKNOWN/generic watchdog 仍要求 ToolCall、ToolResult、Turn 三者均 NEEDS_REVIEW，并匹配 TIMEOUT/CANCELLED_AFTER_START audit。
- 已知无外部副作用分支，仅在 ToolCall/ToolResult 均 FAILED、executor audit `executionDetail.isolated=true` 且 status 匹配时接纳：timeout 为 TIMEOUT；取消为 CANCELLED/INTERRUPTED 且 Turn CANCELLED。不凭摘要文本或错误码单独免除 review。原始 evidence 增加 executionDetail。
- 保留既有 Runtime/Dispatcher 判断。本轮仅将已有 audit-detail 分支抽为私有纯函数，以解决 `executeStage` complexity gate；长字符串及测试表达式作格式修正，不增加新执行分支、权限或工具上限。
- Goal 单轮提交、具体 Turn ID 等待、续跑真实 model-call 增量、工具版本清单和 runner 失败退出行为均合入。

## 合并后验证

主机验证通过：framework 203、QuickJS 85、app 两渠道合计 1,741 项，其中 8 条件跳过，合计 **2,021 pass / 8 skip / 0 fail/error**。跳过不计通过。双渠道 lint、debug APK、AndroidTest APK 编译、spotless/detekt 与源码 gate 通过。日志在 `build/p5-merge-20260928/`。

```bash
./gradlew :tools:framework:test :runtime:quickjs:testDebugUnitTest \
  :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest \
  spotlessCheck detekt
scripts/check-all.sh --source
git diff --check
```

设备状态 **not requested**：本轮合并请求没有授权新的设备验证，未启动模拟器或使用真机。此前 [P5 candidate](p5-sglang-candidate-2026-09-28.md) 的 14 PASS / 1 FAIL 是旧 source/APK 的历史证据，不代表包含 `541d7ba0` 的本次整合制品。剩余 goal-001 OUTPUT_TOKEN_LIMIT、合并后 JS 定向设备验证及正式 clean P5 基线继续开放。
