# HXA-228 API 36 Simulator Verification

日期：2026-09-27。范围仅为本轮 HXA-228 本地 targeted simulator acceptance；不代表物理真机、OEM、release/channel 或真实账号验收。

## 环境与 clean-slate 处理

- AVD：`Helix_API_36`
- serial：`emulator-5554`
- API：36
- ABI：arm64-v8a
- 由本轮任务启动的独立本地模拟器实例；未使用 GitHub/device CI。

首次 app instrumentation 在测试进程启动前失败，因为该复用 AVD 的 Helix App data 仍保存旧内部 Room schema v18，而当前 HXA-221 后代码是 pre-release clean-slate v1。此失败符合“不兼容旧 internal schema”的裁决；没有添加 18→1 migration。清除测试 AVD 中 Helix app data 后重新运行。

## Targeted app acceptance

执行 consumer debug instrumentation，目标类：

- `SessionRunControlDeviceTest`
- `SessionSettingsDeviceTest`
- `ConversationComposerDeviceTest`
- `ConversationReferenceDeviceTest`
- `ExpertAdmissionDeviceTest`
- `ConnectorSessionPanelDeviceTest`
- `SkillSessionPanelDeviceTest`
- `CameraFileProviderDeviceTest`

结果：**16 tests / 16 passed / 0 failed / 0 skipped**。

覆盖 Session RunControl 隔离、Session Settings materialization/permission、Composer `+`、permission compact chip、Expert future-Turn snapshot、Reference picker/chip、Skill/Connector Session override 与 Camera/FileProvider 基础路径。

## Reference storage acceptance

`SessionInputStorageDeviceTest`：**13/13 passed**。

新增契约包括：

- accepted Reference body 在 Queue 中为 content-addressed frozen bytes；
- 源会话后续追加消息不会改变 frozen snapshot；
- 删除源会话后目标输入仍可读取同一 snapshot；
- `message_reference_snapshots` 的 ContentStore body 受 GC reference accounting 保护；
- 删除目标会话后 frozen body 可回收。

## Export acceptance

`SessionExportSnapshotDeviceTest`：**8/8 passed**。

验证：

- target conversation export 包含 `conversation_reference` record；
- frozen reference content 被纳入 content records；
- target message edge 标记 included；
- source Session / source Message 不在 selected snapshot 时明确标记 `not_in_selected_snapshot`；
- Session Expert 作为 configuration 不进入 conversation export。

## Parked Reference resume 补强

最终 persistence review 发现 parked SessionInput 的 resume disclosure 需要显式重新携带 frozen conversation Reference。修正后在 clean-slate API36 AVD 上执行 `SessionInputQueueDeviceTest`：**8/8 passed**。新增用例验证 reference-only queued input 在 Stop 后进入 `NEEDS_ATTENTION`，用户发起 resume 时返回 `PendingConfirmation`，不会绕过 `HIGH_SENSITIVE_CONVERSATION_REFERENCE` 的 per-send disclosure；对应纯 JVM 测试还验证 frozen Reference 中的 credential shape 会被拒绝。

## 未覆盖

- 物理真机/OEM；
- 320/360/412dp × 1.0/1.3/2.0 完整视觉矩阵；
- TalkBack 实际播报/手势逐项验收；
- Camera 系统 Activity 的真实拍照成功/取消交互（本轮验证 FileProvider 与统一 staging 入口的基础 contract）；
- release 安装、签名、商店/channel；
- HXA-230 Memory（本任务明确不实现）。
