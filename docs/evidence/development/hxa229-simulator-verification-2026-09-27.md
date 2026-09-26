# HXA-229 API 36 Simulator Verification

日期：2026-09-27。范围仅为 HXA-229 Model-authored Agent Activity Presentation 的本地 targeted simulator acceptance；不代表物理真机/OEM、release/channel、真实外部账号或完整 TalkBack 验收。

## 环境

- AVD：Helix_HXA229_Closeout_API36（本轮独立创建，read-only / no-snapshot）
- serial：App `emulator-5582`；storage `emulator-5584`
- API：36
- ABI：arm64-v8a
- 本地独占模拟器；未使用 GitHub/device CI。
- 4 GiB RAM / 2 cores；启动与退出由 owned runner 管理，未清除其它 AVD 或物理设备数据。

## App targeted regression

最终组合执行：

- SessionInputProtocolDeviceTest
- ToolTimelineLayoutDeviceTest
- ApprovalLayoutDeviceTest
- SessionInputApprovalDeviceTest
- TurnReviewResolutionDeviceTest
- ChatScreenProjectionDeviceTest

结果：19/19 passed / 0 failed / 0 skipped。最终日志：`build/hxa229-closeout/api36-app-final/instrumentation.txt`、`test-logcat.txt`；TestRunner 同时报告 `19 tests, 0 failed, 0 ignored`。

### Provider-neutral presentation boundary

SessionInputProtocolDeviceTest 使用真实 ChatService、provider adapter、loopback HTTP 与 safe built-in Tool，覆盖 OpenAI Chat Completions、OpenAI Responses、Anthropic Messages。

每种协议都验证：

1. model-facing request tool schema 包含 optional __helix_intent；
2. synthetic model ToolCall 返回 intent “Read current time”；
3. durable tool_calls.modelIntent 保存该 intent；
4. durable argsJson 等于 {}，reserved field 没有泄漏到业务参数；
5. Tool 实际正常完成；
6. 下一轮 provider history 保持 tool-call/result identity pairing；
7. history arguments 不再包含 __helix_intent。

### UI / mobile layout

ToolTimelineLayoutDeviceTest 验证 collapsed 第一层显示 model intent、Harness state badge 独立可见、expanded 显示 Intent / Tool / Arguments / Result / Status / Call ID、raw arguments/result 仍可展开，并覆盖 320dp / 360dp / 412dp + fontScale=2.0。

ApprovalLayoutDeviceTest 在带 model intent 的 Tool row 上继续验证 approval card layout/interaction，不由 intent 替代审批 UI。

### Approval / UNKNOWN

SessionInputApprovalDeviceTest 的同一 batch 同时包含 READ_ONLY safe Tool 与 LOCAL_MUTATION / L2 Tool，两者均带 model intent。结果仍为 safe call 执行完成、mutation call 进入 approval 并在 deny 后变为 DENIED，mutation executor 执行次数保持 0；下一轮历史 arguments 已 strip reserved presentation metadata。

新增 executor failure 用例返回明确失败并在 batch 结算后检查 durable FAILED 与原失败 result；intent 不改变结果。UI 分别断言待审批、已拒绝和执行失败文本。Projection reopen 用例确认 intent/args/result 恢复，并验证 live duration 不被合并丢失。

TurnReviewResolutionDeviceTest 给 NEEDS_REVIEW ToolCall 持久化 modelIntent，分别覆盖 deterministic review 和 ACKNOWLEDGED_UNKNOWN；intent 保留，但 ToolCall state / ToolResult / review decision 仍保持 Harness durable truth。

## Storage reopen / export

执行：

- ToolCallPresentationStorageDeviceTest
- SessionExportSnapshotDeviceTest

结果：9/9 passed / 0 failed / 0 skipped。

最终日志：`build/hxa229-closeout/api36-storage/instrumentation.txt`、`test-logcat.txt`。真实关闭并重新打开 Room 后，COMPLETED / AWAITING_APPROVAL / DENIED / FAILED / NEEDS_REVIEW 的 intent、args、state 保持一致；同一业务 args 的 hash 不随 intent/state 变化。此项是 database reopen，不是额外 OS kill/relaunch。Export 包含 durable intent 并经过 SessionExportSanitizer；synthetic secret 被标记为 redacted，输出只含 sanitizer 结果。

## 制品与可复跑入口

| 制品 | SHA-256 |
| --- | --- |
| consumer debug APK | `737617de4a87a76d723cfa6327792928a17e31213da4d428de11aeee3c561ed6` |
| consumer AndroidTest APK | `ddece2854932a66b14dbe4d367d7d028fb4cff1451fe013f993b31454778cf9e` |
| storage AndroidTest APK | `5560360b70d8c1c95c68664e9d002d37b20738e66dd6110c8049cf1560285a9e` |

最终使用 `scripts/debug/2026-09-27/run-hxa229-acceptance.py` 调用既有 owned runner。仅将启动前诊断 logcat 限制到 main buffer；instrumentation、skip 检测、失败判定及进程所有权清理均未改变。App runner 为 `com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner`；storage runner 为 `com.helix.core.storage.test/androidx.test.runner.AndroidJUnitRunner`。上列 class 以逗号连接传给 `--classes`；`--avd Helix_HXA229_Closeout_API36 --memory-mb 4096 --timeout 900`，输出目录必须全新。

## 本轮失败与修正

- 并发 host unit 输出目录导致 `EOFException`，停止并发写入后完整 gate 重跑通过；不将旧报告算本次结果。
- AVD 占用、启动退出、错误的默认 instrumentation runner、启动前 all-buffer logcat 退出 255、已用端口等失败均发生于测试前，日志保留在 `build/hxa229-closeout/` 各轮目录，不计通过。
- 首次实际 App suite 为 18 项 / 4 失败：3 项新增 Compose 断言误把 Surface 容器当作 Text；1 项把合法 Enqueued 回执强转 Accepted。修正节点选择与 durable batch settlement 等待，保留失败/审批断言，并加入 projection 用例后最终 19/19 通过。
- 库级 lint 的 Root alias `SdCardPath` 既有误报及局部标注详见完成记录；没有改变权限或 Root 行为。

## Host-only boundary tests

JVM tests 覆盖：

- ToolCallPresentation 长度/单行/control-char typed invariant；
- schema augmentation 不改变业务 required；
- reserved key 出现在 properties 或 required 时 fail closed；
- bash / mcp.* / skill.* representative schema 共用一套 metadata contract；
- valid intent strip；
- missing intent 不影响业务 args；
- oversized / multiline / secret-like / result-claim intent 降级 fallback；
- malformed business JSON 不被 presentation parser 掩盖或修复。

## 未覆盖

- 物理真机/OEM；
- TalkBack 实际播报与手势；
- 真实第三方 MCP/Skill 服务网络调用；
- release/channel/signing；
- activity grouping；
- per-result LLM summarizer；
- HXA-227 Eval baseline；
- HXA-230 Memory。
