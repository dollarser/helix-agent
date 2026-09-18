# HXA-196 平台验证与核心实现计划

状态：平台与核心切片已通过下述验证，HXA-196 整体仍未完成。基线为 `3f5039e4`；该提交已推送 main，[远端 CI 35312238290](https://github.com/dollarser/helix-agent/actions/runs/35312238290) 全绿。平台切片在独立分支开发，未合并 main。

## 已确认的接缝

当前 `ProotJobClient.submit` 提交进程 Binder owner；`ProotJobOwners` 在其死亡时取消执行。现有普通通知不提供前台服务所有权。复用现有 Job journal、进程组终止、结果档案、哈希对账与有界日志，不改变同步 v2 工具语义。

## 本轮切片

1. 验证 developer 私有 `:proot` 进程的有界 `specialUse` 前台服务路径：前台启动、退后台、启动拒绝、owner 丢失、租期结束与正常停止。
2. 实现独立 detached 提交协议、持久 owner/租期绑定、单调时间限制、停止与完成竞态结算，以及原 Job 查询恢复。默认五分钟，最大三十分钟，剩余执行预算只能收紧。
3. 平台接线通过后才接入应用授权与任务投影；新启动不得改变同步 `bash`/`code.linux.run` 的最终结果语义，不自动唤醒模型。候选名称固定为 `code.linux.start`、`code.linux.status`、`code.linux.cancel`，仅在各自正式 schema 和授权接线完整后注册。

这次用户要求先推进平台与核心；HXA-196 整体完成仍须覆盖任务规定的产品接线与真机验收，不能用内核通过关闭整个任务。

## 核心实现边界

新增私有 `DetachedJob.v1` Binder 协议及 developer `:proot` 前台服务。提交前写入完整 session/turn/toolCall/job/execution/inputHash 绑定与不可续期租期；查询、取消要求原绑定，不提供枚举。重复提交复用原 Job，不延长租期。服务持有执行所有权，主进程死亡不直接取消 detached Job；Runtime 死亡仍按原 journal 标记 ORPHANED，禁止重放。

同一 Runtime 当前只接纳一个独立后台执行，已有普通 Job 或后台 Job 时拒绝并行启动。保留原同步工具及 Binder owner 语义。启动与取消共用原子接纳边界，取消和终态发布共用结算边界；租期使用单调时钟，剩余预算只能缩短租期。前台服务拒绝时不执行命令、不返回 accepted。

此层是应用内部执行契约，不是模型授权入口。`code.linux.start/status/cancel` 尚未注册；任务页投影、会话授权与预算适配、审批有效期、结果导入交互留到生产接线切片。日志与完成事件不自动唤醒模型。

调试验收 Activity 仅存在于 developerDebug，必须匹配 instrumentation 写入的单次私有令牌，只运行固定测试命令。它在宿主确认 Runtime PID 后仅对自身主进程发送 SIGKILL，避免 instrumentation 结束导致整包 force-stop。consumer 与 developerRelease 的 manifest/dex 均须排除此探针。

## 平台约束

Android 14+ 要求声明匹配的 FGS 类型；通用开发命令使用 `specialUse` 候选并说明用途，不借 `dataSync` 为任意计算保活。`shortService` 约三分钟的限制不满足已接受的五分钟默认租期。Android 12+ 后台启动仍受系统限制；禁止绕过拒绝或自动重试启动。

- [Android FGS 类型](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

核实日期：2026-09-18。平台可运行不等于渠道审核通过。只进入 developer；consumer 的最终 manifest/dex 排除必须验证。暂未连接真机，HOME/锁屏/Doze 的真机证据单列，不由模拟器替代。

## 验证要求

使用 HXA-196 的 G1～G4，新增 `ProotDetachedJobDeviceTest`，API29/36 各自新建独占模拟器。覆盖租期、重复提交、取消、启动拒绝、主进程/Runtime 死亡、结果读取不重放；保存制品哈希与退出记录。生产工具完整接线前不宣称模型可启动后台命令。

## 实现前基线

基线源码 `3f5039e4` 的完整主机与交互矩阵见[合并验证](merged-193-195-204-205-2026-09-18.md)。独立 worktree 另执行 `./gradlew :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest`，exit 0。

`bash scripts/debug/2026-09-18/run-196-baseline.sh` exit 0：API29 为 35/35（36.020 秒），API36 为 35/35（51.439 秒），两次独占进程均正常退出。日志及制品身份位于 `build/hxa196-baseline-api29/`、`build/hxa196-baseline-api36/`；这些是现有 Runtime 回归，不是 detached 新功能证据。

## 修改后的设备证据

下列命令均 exit 0，各自创建并关闭独占模拟器，未复用他人设备：

```sh
bash scripts/debug/2026-09-18/run-196-device.sh 29 5638 build/hxa196-core-api29-03
bash scripts/debug/2026-09-18/run-196-device.sh 36 5640 build/hxa196-core-api36-01
bash scripts/debug/2026-09-18/run-196-runtime-regression.sh
```

| API | 新增 instrumentation | 非 instrumentation 主进程死亡 | 原 Runtime 回归 |
| --- | --- | --- | --- |
| 29 | 7 项行为用例 + 1 项宿主探针准备，34.737 秒 | 主 PID 4849 消失，Runtime 4893 不变；原 Job SUCCEEDED | 35/35，80.497 秒 |
| 36 | 7 项行为用例 + 1 项宿主探针准备，70.758 秒 | 主 PID 4783 消失，Runtime 4833 不变；原 Job SUCCEEDED | 35/35，47.134 秒 |

七项行为覆盖 HOME 后继续及结果哈希、重复不续期/跨绑定拒绝、剩余预算收紧至三秒、幂等取消、预算不足/并发拒绝、实际 START_FOREGROUND app-op 拒绝和 Runtime 死亡不重放。探针准备本身不算进程死亡通过；必须同时核对宿主 follow-up 的 `owner-death.json` 和 `closed.json`。两台主进程死亡后的原执行均生成非空输出哈希与 terminalCommit，exitCode=0。

测试 APK SHA-256：`de675749aee99c49ff4a002b9b3a0bc0ca053cf6bdd0391e68cc0033d5125930`；developer debug：`867fe67f2cd8f89fe6d14147da0881dbcf55148a29c15325dd64613311382b1e`。每组 `artifacts.json` 保存现场制品身份。

完整构建后的 API36 旧 Runtime 回归使用 developer debug `f23576190f56dbe776eb5e66b1f5956fb1b9b78b11673a6c5c4d1fd1c32a5c9c`。`python3 scripts/debug/2026-09-18/compare-196-apks.py` 对保存的两个 APK 逐项比对：只有 `classes6.dex` 源码行号随格式化发生变化；去除行号后的完整反汇编及元数据相同，其余 ZIP 条目字节相同。未把两个不同 ZIP 哈希冒充同一制品。

首轮失败是 instrumentation 结束触发整包 force-stop，不能用于证明主进程单独死亡；第二轮 `run-as kill` 被设备拒绝。改用令牌限定的非 instrumentation 探针后，两 API 的真实 SIGKILL 链路通过。没有把这两轮失败记为通过或修改 Runtime 死亡语义来迎合测试。

## 主机及制品验证

局部单测、完整 lint、Debug/Release 构建、35 个依赖锁均已通过。新增租期/启动取消单测 5 项通过。最终 APK 检查修正了 SDK 解码差异：`apkanalyzer` 对 `specialUse` 输出 `0x40000000`，按同一精确位值校验，不放宽类型要求。Debug 与 Release 边界均通过，debug-only 探针未进入 consumer 或 developerRelease。

最终 `./scripts/check-all.sh --all` exit 0（`build/hxa196-final-gates.log`）；`python3 scripts/verify-integrated-runtime-apks.py --build-type release` exit 0。忽略目录另保留首次完整运行及 APK 枚举修正复验日志 `build/hxa196-full-gates.log`、`build/hxa196-artifact-gates.log`、`build/hxa196-release-boundaries.log`。未运行 196 新代码的远端 CI；前述 main 全绿只证明基线。
