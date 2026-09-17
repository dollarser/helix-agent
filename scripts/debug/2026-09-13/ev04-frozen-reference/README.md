# EV04 冻结夹具参考归档

来源：退役的 `helix-ev04-wt`，基线提交 `0bcd9d3` 上三个未跟踪文件；2026-09-13 保存原始字节。它们不在 Gradle source set 内，不参与默认测试，也不是当前通过的验收证据。

- `MainAppCombinedSoakDeviceTest.kt.txt`：独有的 block 时间窗/idle heartbeat、ACT 工具任务、PRoot anchor 准备、CLI 先探测后创建会话及 consumer MCP 拒绝记录。
- `ProotAnchorFixture.kt.txt`：developer 的修复入口与真实握手准备辅助。
- `ProotRootfsSoakInstallDeviceTest.kt.txt`：故意保留安装状态的 RootFS 长稳准备夹具，只适合独占、可丢弃测试设备。

保留为参考而不直接覆盖 main：文件依赖旧冻结产品行为，引用已 superseded 的 ADR-PERMISSIONS-001，consumer MCP 的拒绝用例不等于正向功能验收；准备阶段修改 profile/scope 的恢复和超时 worker 生命周期也需要重新核对。后续移植须遵循当前 ADR-PERMISSIONS-003/0013、现有连接检测及独占设备约束，不能直接执行历史夹具。

同目录来源的 `InAppMcpServer.kt`、`ScriptedTaskModelServer.kt` 与 main 对应源码逐字节相同，没有再复制。M8 的 `HXA-083-fix-handoff.md` 已被 main 的 `docs/completion-records/HXA-083.md` 覆盖，其“尚未修复”及共享模拟器操作建议过时，不作为当前指导保留。

退役前的未跟踪原文件和本地测试证据保存在忽略目录 `build/worktree-retirement/2026-09-13/`，含 manifest；该目录不提交 Git。
