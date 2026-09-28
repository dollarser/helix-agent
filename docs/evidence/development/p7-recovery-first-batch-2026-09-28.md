# P7 联合恢复第一批

2026-09-28 所有者继续剩余路线，使用独占 API36 developer arm64 模拟器，4 GiB / 4 cores / 4 KiB page；仅合成数据，无真机或真实外部账号。app/test APK 与 clean `99b7bee7` 的 15/15 SGLang 基线相同：`a401ef07994bf7fbb08c36bd78e07cb8b1aaf434265102ac7fc92e38c5c40202` / `bd0ea1993945118610a7ef223561164bef8f0c844b9c00d49ec59324a831ff3d`。本批没有修改生产代码。

## 结果

普通批次 **26/26**：GoalContinuationDeviceTest、ChatServiceAttachmentRetryDeviceTest、SessionInputRecoveryDeviceTest、ModelCallRecoveryDeviceTest。覆盖用户插入任务后的 Goal 继续、显式停止、附件内容与出网目标重验、预算 continuation 不重放原始请求、持久输入恢复及模型调用使用量保留。不是实时网络服务验收。

专用两阶段 **7/7**：

- SessionExportRecoveryDeviceTest：真实导出第一块写入时进程死亡，重开 PID 不同；清理不完整文档、临时文件和持有的授权，128 条会话消息保留，不重新导出。
- WorkspaceProcessRecoveryDeviceTest 三切点：before-rename / purging / purged。先 seed cleanup 持久状态，再实际杀进程，重开核查；不把 seed 声称为生产执行过程中真实击中切点。
- WorkspaceBackupRecoveryDeviceTest 三切点：prepared / deleted / restoring，在实际备份协议边界进程死亡。保留备份和收据，冲突由显式 fixture 用户解决，恢复内容完整；启动不自动删除冲突目标或重放。

原始证据：`build/p7-recovery-phased-20260928/`，包括 instrumentation、export setup/verify、六组 cut/verify、APK hash、设备属性与 `closed.json`；runner exit 0，模拟器已关闭。复用 `scripts/run-owned-emulator.py`，普通批次的 after-script 为 `scripts/debug/2026-09-28/verify-p7-export-recovery.py`，后者再调用现有 Workspace cuts runner。

## 保留的失败与修正

首次 `build/p7-recovery-targeted-20260928/` 把两个专用恢复类误放入普通批次：28 项中 26 通过，导出提示 `Use the two-phase owned runner`，备份缺少 `recovery-device-pid`。属于本轮启动方式错误，原日志保留；后续按已有 setup/verify 协议执行，没有削弱断言、跳过失败分支或修改生产代码。

新增脚本及文档通过 source gate、secret scan 与 `git diff --check`。最终源码仍在本地分支，不推送。

## 剩余 P7 边界

本批不关闭整个 P7。下一批仍需按当前实现核对：网络/模型/扩展/Runtime 错误的 UI 恢复与输入保留；用户主动诊断导出的预览、脱敏和缺失材料说明；会话/产物/模型/Memory/临时下载的占用与删除边界；窄屏/TalkBack/中英文新手流程。物理硬件/OEM/Doze/热稳定性及真实账号依赖仍单列，不能用模拟器恢复通过替代。P6 仍保留历史截断、额外只读调用和模型随机性，不继续无边界重试或架构重构。
