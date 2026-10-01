# ADR-RUNTIME-002: 命令日志、后台 Job 与手动终端

Status: accepted
Date: 2026-09-16
HXA: HXA-195, HXA-196, HXA-197, HXA-198
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

终端 UI、日志传输、执行所有权与授权不能混在一个 Session 对象里。

## Decision

- 一次性 Job、异步 Job、手动 PTY Session 分开建模；观察页面不启动执行。后台启动返回 accepted 不是完成，结束事件不自动唤醒模型。
- 日志批次绑定 Job/generation、游标、stream、字节和结束/截断标志；重复读取幂等，跨 Job 拒绝，缺口可见。慢读端不阻塞进程 drain，UTF-8 跨批解码。初始限额：IPC 每批 32 KiB、每 Job UI 缓存 256 KiB、spool 每 Job 4 MiB/总 16 MiB；参数变化需压力证据。日志预览不代替最终结果与产物哈希。
- Runtime 拥有进程组、PTY、租期、日志和退出事实；应用服务拥有任务来源、授权与 Workspace 关联。Session 身份/generation 不等同 PID，连接状态与执行状态分离。
- 异步 Job 有持久身份、显式 Runtime owner、租期和累计预算，初始默认 5 分钟/最大 30 分钟并受其他剩余预算限制，不自动续期。主进程死亡后继续必须已有有效后台路径，否则取消/对账。
- 手动终端为 developer/Advanced 用户主动开启的可信 USER 入口，人工按键不逐字符审批；模型/MCP/Skill/网页不能借会话 ID 获得写入 PTY 权限。当前设计不提供模型交互输入接口。
- 首个切片单 live PTY，后续最多两个手动会话；支持 detach/attach，单个会话仅一个写入连接。它们共享 UID/文件系统。手动执行与 Agent 本地代码/文件修改互斥，不据人工多会话推导 Agent 未知写效果可并发。
- 重启后不重建原 shell 内存、不重放命令。手动终端不套 Goal 预算，其租期/空闲回收、PTY/native/rendering 版本和许可证必须有接线前设备证据。前台 PTY 可以独立验收，不等待后台 Job 成功。
- 197 首片手动终端采用应用 Workspace 内实际目录直接映射 `/workspace`，不提前启用独立会话目录绑定。手动租期默认两小时、最大八小时，不自动续期；断开连接后三十分钟空闲回收。停止记录独立于可删除的 Runtime 安装目录，环境维护与执行互斥。页面、模型和 Agent Goal 不持有 Runtime 进程资源；具体交付与设备边界以 197 任务记录为准。
- 196/197 的宿主执行准入共用一个应用进程实例。普通执行许可随真实 executor 退出释放，不随超时/取消回执提前释放；异步/手动 owner 在提交前以原子写保存执行身份，调用返回不清除，只由原身份的终态对账释放。持久准入文件只保存身份，不复制 Runtime 的阶段、日志或结果，也不是新的授权来源；读取损坏/写入失败不能按空闲处理。生产控制入口仍须按原 session/turn/call/job 绑定校验，不能凭 owner 身份获得执行权限。

### HXA-197 渲染组件与构建兼容

采用 ConnectBot termlib **0.2.1**（固定 tag commit `27e024fccb2d722b47c57f5da1b4da9bca477b68`）作为 developer 专用显示/输入组件；它不拥有 PTY 或执行许可。Apache-2.0 与 bundled libvterm MIT 文本、归属及修改说明随应用打包，并在终端页面可查看。

`:runtime:terminal-renderer` 从校验过的发布 sources JAR 编译 Kotlin，应用已通过独立设备探针的显式 close 补丁；native 库与资源来自同版本 AAR，不同时打包其原始 classes.jar。构建校验固定 SHA-256，源码只生成到 build 目录。关闭顺序为停止输入/输出生产者、卸载视图、在 callback looper 释放 emulator；依赖版本更新必须复核补丁与制品。

组件要求 compileSdk 37；本次明确升级 compileSdk 至 37、Compose BOM 至 2026.09.00，保持 targetSdk 36 和 minSdk 29。CI 同时保留用于既有兼容探针的 API36 SDK。原生 ABI 为 arm64-v8a/x86_64；16 KiB ELF/ZIP 静态检查和真实 16 KiB 设备运行分别记账。OSC 自动剪贴板、图片及链接自动打开不启用，模型不获得终端输入能力。生产页面验收以 197 任务和证据为准，构建接线不代表验收完成。

当前抽屉开启的手动终端使用应用私有 `workspaces/app/terminal` 目录，不依赖选中会话；文件管理器显式传入 scope 路径时仍使用该目录。终端 detach/attach 与租期不变，关闭或切换聊天不停止终端，Advanced 及执行互斥约束仍适用。

## Alternatives considered

通用持久 shell 替代全部 Job 会破坏身份与结算；仅最终输出不足以支持交互；每个按键做 Tool Approval 无法形成可用终端。

## Consequences

同一主题使用一份有效契约，避免并行实现各自解释权限和生命周期。代价是实现、UI、数据与恢复需要一起验证；accepted 表示决定，不代表相关任务全部完成。

## Verification

按[终端开发计划](../../architecture/terminal.md)分别验收日志、后台 owner、PTY 与多会话。accepted 不代表这些切片全部已交付。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)

## Decision history

- 2026-10-01：HXA-237 按所有者要求将静态说明/组件许可证收进帮助，实时错误/只读/执行状态保留；可写连接点击正文显示 IME、显式收起，并增加终端原生方向/编辑键与有界符号输入。developer 内置未修改 Inconsolata Regular（OFL-1.1），固定上游提交与 SHA，许可证随帮助入口可见。不改变手动 USER 输入权限、原执行身份/租期，不因帮助关闭或软键盘隐藏停止终端。

- 2026-10-01：HXA-236/J1 的任务操作面采用一个有界查询槽与一个有界控制槽；查询/刷新等待不阻塞用户停止等待或取消原任务，取消与收取等控制仍互斥。晚到查询只释放自己的观察槽，不覆盖更新的控制回执。逻辑超时不释放未退出的物理 IPC，停止等待不等于停止执行；不新增第二执行管线。

- 2026-09-30 提交后复审修复：前台 Job 的 Stop 在轮询/查询中断时也要投递给原任务；纯观察预算耗尽不是用户取消。取消回执只说明投递/观察事实，未证明退出不释放 owner。Process 创建成功后即进入清理所有权，不等待 PID、日志线程或 watchdog 成功；任何后续初始化失败仍等待真实退出。未知效果与物理退出分别保留，主机反例不代替设备进程故障验收。

- 2026-09-30 Runtime 矩阵修复：同步前台 Job 的底层执行也可超过客户端等待，提交前同样保存原物理 owner。取消/超时/ORPHANED 不自动释放；完整身份匹配的有效终态或可信更新 boot count 仅用于证明旧物理占用结束，不宣告任务成功。损坏日志不等同 never submitted；输入传输使用现有归档上限。PTY 连接为一次性交付，拒绝/中断释放注册、迟到 callback 不复活连接。验收见[故障矩阵](../../evidence/development/runtime-fault-matrix-2026-09-30.md)。

- 2026-09-29：所有者要求终端独立于聊天；抽屉启动目录改为独立目录，显式目录入口保留原语义。
