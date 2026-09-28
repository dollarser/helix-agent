# Fork 复用 Workspace

> 后续所有者调整了不可用目录行为：改为新空目录恢复，见[后续变更与审查](recoverable-workspace-2026-09-27.md)。本文测试计数为此前快照，不代表后续变更验收。

日期：2026-09-27。所有者明确要求 fork 复用来源 Workspace；按当前 [ADR-AGENT-007](../../adr/agent/007-session-fork.md) 与 [ADR-WORKSPACE-004](../../adr/workspace/004-workspace-binding.md) 实施。保留此前 HXA-227 未提交改动，不把其旧设备通过结果外推到本次新行为。

## 行为与边界

- fork 在原有 Room 事务内读取来源绑定，用相同 workspaceId 与 relativePath 创建新会话；不额外分配默认目录，不复制文件或创建 worktree。
- source directoryRef 与绑定必须一致，绑定缺失、不一致或 Workspace 非 READY 时失败并回滚；实际 Path/SAF 能力与系统授权在使用时照常检查。
- 两会话共享实时文件，原始 owner 不变；独立 binding revision，之后切换目录互不跟随。新会话权限/RunControl 使用已有默认规则，不复制授权、审批、执行状态或 Goal。
- 既有会话、产物和请求引用继续保护共享目录，删除来源不能因 owner 消失清理分支仍引用的资源。默认新会话仍创建独立目录；既有 fork 的绑定不批量改写。
- 不修改 Room v1 schema、Dispatcher、permission/effect owner 或 cleanup owner。

## 验证

- `:core:storage:test` 通过；SessionBindProviderTest 6/6，其中新增两项验证显式共享目录不分配默认目录、仍快照权限，以及普通创建仍分配默认目录。
- `SessionForkDeviceTest` 增补共享文件修改、删除原会话后的引用保留、主动切回独立目录、Path/SAF 子目录绑定、来源后续切换、不可用拒绝，并强化取消回滚、嵌套 fork 与数据库重开绑定断言。Path/SAF 用例验证存储绑定，不能解释为真实外部 Provider I/O 已通过。
- 两渠道 AndroidTest APK 编译与 detekt 通过，日志 `build/fork-workspace/build-r2.log`。首次 detekt 的 LongMethod 已拆 helper，未放宽规则或断言。
- `./scripts/check-all.sh --all` exit 0，日志 `build/fork-workspace/host-gate.log`：source、spotlessCheck、detekt、双渠道 lint、unit、debug/release APK、36 项依赖锁及 Runtime/variant 边界通过。storage 201/201；consumer unit 821 total / 4 skip，developer unit 866 total / 4 skip，均 0 failure/error。正常增量 gate，不宣称全部未变 task 强制重跑。

设备状态：`not requested`。本次功能变更未重新请求设备执行，不启动/使用模拟器或真机；此前 HXA-227 的设备授权与结果不替代本次验收。未使用真实账号、提交、推送或发布。
