# Runtime / Provider 设备收口（2026-09-30）

## 范围

所有者要求继续完成剩余工作，并明确授权独占 API36 模拟器、本地 SGLang、定向检查后提交、clean 正式 P5 后推送。订阅真实账号暂缓；不使用真机或其他账号。起点为 `36807507`，保留主目录已有未跟踪调试脚本。

本次主要修复验证入口与当前产品契约之间的漂移；不改变生产权限、模型预算、UNKNOWN 或压缩收益判定。当前状态与开放工作仍由 [status](../../development/status.md)、[HXA-232](../../development/tasks/HXA-232.md) 和 [HXA-190](../../development/tasks/HXA-190.md) 管理。

## 修复

- SGLang UI smoke 从旧模型标签迁移到统一模型管理弹窗。继续验证真实目录、连接与能力探测、未保存选择不落盘，以及保存后重开仍选中模型。
- Provider 完整旅程提供可压缩的旧历史，保留最近一轮，并等待界面解除发送状态后发起手动压缩；增加持久 checkpoint 断言。原来的短文本返回 `CONTEXT_NO_GAIN` 是正确拒绝，未放宽生产检查。
- JavaScript 进程死亡夹具允许一次独立的只读恢复核查，断言原脚本仅请求一次、原调用身份/参数/审批不变、无新工具执行、原 Goal 明确失败且重开不重复核查。`INTERRUPTED` 与后续结算归类 `NEEDS_REVIEW` 都保留未知副作用，不能被核查结果改成成功。
- PRoot / CLI 所有者死亡脚本按当前集成应用的 `:proot` / `:subscriptions` 私有进程与共享 UID 取证，不再要求旧 companion APK。PRoot 夹具自行安装随包 RootFS，不依赖测试类执行顺序。
- CLI 本地结果场景只禁用 Runtime 服务组件，并恢复原组件状态；不禁用整个宿主应用。增加组件状态解析的三项主机回归，涵盖默认、显式启停、相邻区块与其他 Android user。

## 设备结果与历史失败

设备为所有者授权的 API36 / arm64 独占模拟器，4096 MiB、4 核；每次由 `scripts/run-owned-emulator.py` 创建并关闭，原始日志保留在忽略的 `build/remaining-closeout-*`。这些是定向回归，不是全量设备基线、OEM、真实用户内测或订阅账号通过。

| 轮次 | 结果与用途 |
| --- | --- |
| 初始 P5 准备 | SGLang 连接/能力探测通过，但旧模型标签断言失败；15-case P5 未启动，不能报为 P5 功能失败或通过 |
| device-r2 | 12 项中 11 通过；包括更新后的真实 SGLang UI、模型选择、PTY 绑定与四类订阅合成任务的取消/死亡。Provider 压缩旅程失败 |
| runtime-r4 | 20 项中 19 通过；Provider Room 集成与 QuickJS 原生权限/取消/超时通过。压缩夹具仍不满足保留最近历史的规则 |
| runtime-r6 | Provider 完整旅程 1/1 通过；旧 JavaScript 恢复预期失败，原始轨迹保留 |
| runtime-r7 / r8 | Pty 2/2；JavaScript 恢复审计等待与再次重开后的未结算状态断言依次发现漂移，均保留失败记录 |
| runtime-r9 | Pty 2/2；七组故障中前六组通过，最后本地结果场景的主机服务切换权限失败，未冒充产品通过 |
| runtime-r10 | 真实 SGLang UI 与 Provider 完整旅程 2/2；Runtime 服务禁用后的本地结果恢复及再次重开通过，服务恢复原 default 状态 |
| consumer-r11 | consumer 的 Provider 完整旅程、Room 集成、选择反馈和 QuickJS 原生执行共 23/23 通过，无跳过 |

故障执行入口：`scripts/debug/2026-09-30/run-closeout-owner-matrix.py`。默认七组；`HELIX_OWNER_CASES` 只选择指定子集并保存逐组退出码，子集不算全量。每组保留准备、SIGKILL、恢复、再次重开与安装包哈希。

runtime-r9 的六组通过结果：

1. 隔离 JavaScript 实际线程 CPU 活动后 SIGKILL：原执行器退出，原脚本请求 1 次，恢复模型请求 1 次，无启动重放。
2. PRoot guest 写入启动标记后 SIGKILL：按原 job/execution/input 身份查询，`ORPHANED` 保留；不是任务成功或完整子进程退出证明。
3. CLI 合成执行中 SIGKILL：保留原任务，仅取消/查询，不重新提交。
4. CLI fetch 后 SIGKILL：恢复结果并确认，模型调用记录仍为原调用。
5. CLI 本地持久化后 SIGKILL：原结果不丢失，不重复生成。
6. CLI ACK 后 SIGKILL：重复恢复不重复产出或确认其他任务。

第七组由 runtime-r10 定向复验通过：ACK 后终止所有者，再禁用 `CliRuntimeService`，应用仍可读取已验证本地结果；恢复结束后组件恢复原状态。r9 与 r10 使用同一 developer app/test APK（SHA-256 分别为 `297119685d9bd1e396e79ddf24517d1988f9e9387c2c292838618e0f00d97e39`、`aa68897464cab42ea11c132a2102772e0b6594eb4618a648bf110d95f3e50ccc`）。七组均有通过证据，但不声称一次七组全绿。

## 主机验证

修复后的联合门禁在 runtime-r9 前完成：`BUILD SUCCESSFUL`，989 tasks，23 executed / 966 up-to-date。包括根 `test`、detekt、spotlessCheck、双渠道 lint、Debug APK 与 AndroidTest APK。增量复用结果不等于全部测试强制重跑；后续修改需追加复验记录。

`python3 -m unittest discover -s scripts/tests -p test_cli_result_owner_runner.py`：3/3。文档收口后的 `check-all.sh --source` 通过（658 Markdown、215 HXA、35 ADR、1905 个三语言资源键），秘密扫描与 `git diff --check` 通过。

## 尚未完成的边界

本记录随定向验证修复提交；clean P5 与推送尚待后续执行，未声明完成。初始主目录包含历史未跟踪脚本，不能作为 clean P5。最终记录须引用实际干净检出、源码/数据集/制品身份和结果。

真实订阅登录/刷新/远端取消、真实模型恢复完成率统计、手机/OEM、设备重启/低内存/热压、物理 16 KiB、真实用户内测与发行签名/升级契约继续开放。有限进程死亡验证不关闭完整 32 项 Runtime 平台矩阵。
