# HXA-197 手动终端产品会话接线

日期：2026-09-20。范围：应用服务、私有 Runtime 前台 owner、Binder 和实际工作目录；终端渲染页面尚未交付，197 不关闭。

## 执行路径

`AppContainer.manualTerminal` 在 developer 装配 `DeveloperManualTerminal`，consumer 为 null。构造与 `hasSession` 只涉及应用本地身份，不冷启动 Runtime。显式 USER 开启时校验 Advanced，解析应用 Workspace 内真实目录，先保存手动身份并转移既有 `ExecutionOwnership` 的排他许可，再通过 `PtySessionClient` 提交。身份使用 Session/generation/execution，不伪造 Agent Turn/ToolCall；未注册模型工具，输出不进入模型消息。

`ProotTerminalService` 非导出、在 `:proot`，仅显式 START 请求建立带停止动作的 specialUse 前台通知；查询、attach、页面观察不启动 shell。已准备的 PRoot 使用固定 `--kill-on-exit` 启动路径，选定应用目录直接绑定 `/workspace`，不制作一次性输入副本。该映射是工作目录选择，不是文件系统隔离；仍共享宿主 UID 和网络权限。首片仅支持应用 Workspace 实际目录，不提前实施 proposed 的独立会话目录方案。

租期由手动用户入口提供，默认两小时、最大八小时，不续期，也不使用 Goal 预算。断开连接后的三十分钟空闲回收独立于总租期；输出轮询不续命，attach 与已接受输入更新人工活动时间。前台 owner 创建失败或启动前租期到期时不 fork，分别记录 START_FAILED 或 LEASE_EXPIRED 和 NEVER_STARTED。用户取消仍保留 USER 原因。

## 连接、恢复与维护

- Binder 请求与回复限制 32 KiB；输入、输出单块最多 8 KiB。重用既有有界队列、generation 游标和输出缺口；通信不确定时不重发输入或启动。
- 同时仅一个写连接。每个连接带主进程 death token；断开或死亡撤销写连接，不结束 shell。旧连接操作和旧会话 ACK 不影响后继连接。页面协程已取消时，detach 仍执行释放；attach 结果因协程取消丢失时回收已建立的连接。
- Runtime 正常结束先记录停止事实；应用显式结算原身份，取得 ACK 后才释放宿主占用。未知、损坏记录或通信错误保留占用。重启后的停止证明使用严格增加的开机次数，不恢复 shell、不重放命令。
- 终端身份和停止记录保存在 `files/terminal-sessions`，独立于可删除的 Runtime 安装目录。最多 128 条；仅在需要空间时移除已对账的旧记录，未知或未对账记录不淘汰。ACK 留存，允许丢失回复后的幂等重试。
- 修复、回滚、删除 Runtime 与现有 Job/手动终端共用私有 Runtime 执行 reservation；手动会话未证明停止时拒绝维护。此入口复用现有维护页面结果，不增加审批对话框。

## 验证记录

初始定向 API29：`ProotTerminalSessionDeviceTest` 2/2，6.844 秒，owned emulator exit 0，目录 `build/hxa197-product-service-api29-v1`。这是前版定向证据，后续版本加入取消协程释放和启动前到期检查，以最终门禁为准。

新增产品用例经真实应用服务、私有 Binder、前台 Service 和 PRoot：

1. STANDARD 拒绝开启；Advanced 开启；持久 owner 阻止竞争的执行许可；重复开启/运行中结算拒绝；真实目录写入；单写连接互斥；取消页面协程后可重连；环境变量保持；resize；主动关闭与原 owner 结算。
2. 手动租期到期，记录 LEASE_EXPIRED；结束后身份仍待结算，结算后解除占用。

既有 worker 设备旅程增加启动前取消、前台 owner 不允许启动、租期已耗尽三种不 fork 场景；不把这些断言增加为新的测试数量。

最终门禁全部经 `python3 scripts/debug/2026-09-18/with-host-slot.py --` 串行持锁执行；两次设备运行各创建独占模拟器，拒绝既有 serial，finally 关闭自有进程。

| 命令 | 实际结果 | 证据 |
| --- | --- | --- |
| `./scripts/check-all.sh --all` 后 `./gradlew :app:assembleDeveloperDebugAndroidTest` | exit 0；格式/静态分析、主机测试、lint、双 flavor 构建、依赖锁与变体边界通过 | `build/hxa197-service-all-v2.log` |
| `python3 scripts/verify-integrated-runtime-apks.py --build-type release` | exit 0；两 flavor 的组件、私有进程、specialUse 声明与发行排除边界通过 | `build/hxa197-service-release-v2.log` |
| `python3 scripts/verify-integrated-runtimes.py --avd Helix191_API29 --port 5584 --output build/hxa197-service-runtime-api29-v2` | 46/46，51.874 秒，owned exit 0 | 同名目录 |
| `python3 scripts/verify-integrated-runtimes.py --avd Helix191_API36 --port 5586 --output build/hxa197-service-runtime-api36-v2` | 46/46，77.856 秒，owned exit 0 | 同名目录 |

两 API 的主 APK SHA-256 均为 `ab16c3b328077515188f3a87405b10d486abbc313502a4a91de502b2bb97f838`，测试 APK 为 `e4fad7455375cfebb636e2a2cd08e03d68147e0863eafb04337592b87e69232e`。每 API 的两项产品旅程包含于 46 项，不重复计数，也不把 92 项说成新增终端功能数量。

当前未验证正式渲染页面、Activity 重建、生产会话运行中 Runtime/主进程死亡、三十分钟 idle 真实等待、明确等待 shell 就绪再运行长命令的租期终止、OEM/Doze 或长稳，不能据上述专项关闭这些义务。
