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
- 最多两个手动会话各自支持 detach/attach、单写入连接；与最多四个 PRoot Job 可共存。终端、独立 JS 与普通工具不全局互斥；共享 UID/文件系统会造成业务结果竞争，由用户与 LLM 验收，不以同目录或写操作类别禁止执行。
- 重启后不重建原 shell 内存、不重放命令。手动终端不套 Goal 预算，其租期/空闲回收、PTY/native/rendering 版本和许可证必须有接线前设备证据。前台 PTY 可以独立验收，不等待后台 Job 成功。
- 197 首片手动终端采用应用 Workspace 内实际目录直接映射 `/workspace`，不提前启用独立会话目录绑定。手动租期默认两小时、最大八小时，不自动续期；断开连接后三十分钟空闲回收。停止记录独立于可删除的 Runtime 安装目录，环境维护与执行互斥。页面、模型和 Agent Goal 不持有 Runtime 进程资源；具体交付与设备边界以 197 任务记录为准。
- 宿主维护多条原执行身份，而不是单个全局排他 owner。每条 identity 只约束自己的提交/控制/结算；保留身份或未收取结果不占用其他任务准入。普通线程、Runtime 物理槽直到真实退出才释放；唯一原生 QuickJS 服务的存活身份只阻止该单实例引擎的新调用。identity 文件损坏不得伪造为空闲，普通无关工具仍可执行；控制入口校验原 session/turn/call/job，不凭身份授予权限。

### HXA-197 渲染组件与构建兼容

采用 ConnectBot termlib **0.2.1**（固定 tag commit `27e024fccb2d722b47c57f5da1b4da9bca477b68`）作为 developer 专用显示/输入组件；它不拥有 PTY 或执行许可。Apache-2.0 与 bundled libvterm MIT 文本、归属及修改说明随应用打包，并在终端页面可查看。

`:runtime:terminal-renderer` 从校验过的发布 sources JAR 编译 Kotlin，应用已通过独立设备探针的显式 close 补丁；native 库与资源来自同版本 AAR，不同时打包其原始 classes.jar。构建校验固定 SHA-256，源码只生成到 build 目录。关闭顺序为停止输入/输出生产者、卸载视图、在 callback looper 释放 emulator；依赖版本更新必须复核补丁与制品。

组件要求 compileSdk 37；本次明确升级 compileSdk 至 37、Compose BOM 至 2026.09.00，保持 targetSdk 36 和 minSdk 29。CI 同时保留用于既有兼容探针的 API36 SDK。原生 ABI 为 arm64-v8a/x86_64；16 KiB ELF/ZIP 静态检查和真实 16 KiB 设备运行分别记账。OSC 自动剪贴板、图片及链接自动打开不启用，模型不获得终端输入能力。生产页面验收以 197 任务和证据为准，构建接线不代表验收完成。

当前抽屉开启的手动终端使用应用私有 `workspaces/app/terminal` 目录，不依赖选中会话；文件管理器显式传入 scope 路径时仍使用该目录。终端 detach/attach 与租期不变，关闭或切换聊天不停止终端，Advanced、物理容量和运行环境维护约束仍适用，不再全局排斥 Agent 任务。

### HXA-240：FFmpeg 复用 Advanced Bash / Job

所有者选择复用既有执行通道，而非独立 `runtime/media` 或五种固定操作。固定 Ready-AV1 仅随 developer 的 PRoot 模块打包；guest `ffmpeg` / `ffprobe` 用原样 argv 调用 APK 内 Android/Bionic CLI。公开 Android 系统路径与 APK 原生目录的显式 PRoot 映射是 ABI 桥接，不把 Bionic 程序当成 Alpine/musl 包；不新增 Service、JNI 执行器、后台任务引擎或全局锁。

PRoot 已有共享 UID、原 Job 进程组/取消/预算/日志/恢复语义保持。多输入和滤镜/字幕/编码策略交给模型与 FFmpeg；额外资源仍受原执行权限。输入快照流式使用 Job 64 MiB/128 MiB 传输上限，stdout 和旧 result.txt 预览限额不因此扩大。成功原归档的 `output/` 文件按原 session/turn 幂等登记为产物；不覆盖被用户改动的已发布文件，不重跑命令补收取。

Standard 不因本裁决加入 PRoot/FFmpeg，已有图片与视觉功能不变。编译组件、设备能力、输出文件完整性和媒体任务正确性分别反馈；未经过当前设备验证的 MediaCodec/Bionic-in-PRoot 路径不写成已验收。正式制品继续核对全部依赖、来源许可和 25 MB 增量门槛。

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

- 2026-10-02：按所有者“复用高级版已有的 Bash／Linux Job”裁决，HXA-240 撤回未验收的独立媒体运行时接线，采用既有 PRoot 内 Bionic CLI 桥；生产状态以 HXA-240 当前证据为准，历史 Ready/Plus 设备记录不是本桥接的验收。

- 2026-10-02 复核：终态记录可能先于物理进程/输出泵清理，前台服务必须等该 Job 的物理槽结束才撤销；不能把 `state.isTerminal` 单独当作退出证明。并发 cold bind 的 Runner 构造保持单例。两项只维护运行稳定性，不恢复用户结果锁。

- 2026-10-02：按所有者新裁决取消终端/Job 全局排他与业务结果锁；应用身份集合、per-execution 控制、PRoot 四槽与多 admission 前台服务共同接线。日志四路独立排空；环境修复仍排除使用该 Runtime 的进程，单 PTY 写者和原生 QuickJS 单实例协调保留。旧 v1 身份读取后原样保留而非清锁；设备与实际容量测量按 HXA-238 单独验收。后文 2026-10-01 的全局互斥说明是历史决定，不再是当前行为。

- 2026-10-01：HXA-236 后续实施以实际可信观察 executor 的 Dispatcher 证据驱动进展判断，不用名称白名单；观测时间变化不是进展，健康等待仍告警并受原 lease/预算控制。未知、来源失败与终态重复查询不获得无限等待豁免。观察/终态/结算及 qualified disposition 事实沿原存储事务保存，旧回包不将 UNKNOWN 处置改成成功。
- 2026-10-01：所有者反馈 v0.0.4 JS/Bash EXECUTION_BUSY，确认手动终端 START 前连接异常遗留持久准入。仅在原活跃启动许可内且能证明尚未提交时，按精确 owner/generation 回滚保留及 binding；START 回执未知保持占用。活跃/待结算手动终端与代码执行继续互斥，升级不凭旧 UI 缺记录强制清除未知 owner；[缺陷证据](../../bug-fixes/2026-10-01-execution-busy-pre-submit.md)。

- 2026-10-01：HXA-237 按所有者要求将静态说明/组件许可证收进帮助，实时错误/只读/执行状态保留；可写连接点击正文显示 IME、显式收起，并增加终端原生方向/编辑键与有界符号输入。developer 内置未修改 Inconsolata Regular（OFL-1.1），固定上游提交与 SHA，许可证随帮助入口可见。不改变手动 USER 输入权限、原执行身份/租期，不因帮助关闭或软键盘隐藏停止终端。

- 2026-10-01：HXA-236/J1 的任务操作面采用一个有界查询槽与一个有界控制槽；查询/刷新等待不阻塞用户停止等待或取消原任务，取消与收取等控制仍互斥。晚到查询只释放自己的观察槽，不覆盖更新的控制回执。逻辑超时不释放未退出的物理 IPC，停止等待不等于停止执行；不新增第二执行管线。

- 2026-09-30 提交后复审修复：前台 Job 的 Stop 在轮询/查询中断时也要投递给原任务；纯观察预算耗尽不是用户取消。取消回执只说明投递/观察事实，未证明退出不释放 owner。Process 创建成功后即进入清理所有权，不等待 PID、日志线程或 watchdog 成功；任何后续初始化失败仍等待真实退出。未知效果与物理退出分别保留，主机反例不代替设备进程故障验收。

- 2026-09-30 Runtime 矩阵修复：同步前台 Job 的底层执行也可超过客户端等待，提交前同样保存原物理 owner。取消/超时/ORPHANED 不自动释放；完整身份匹配的有效终态或可信更新 boot count 仅用于证明旧物理占用结束，不宣告任务成功。损坏日志不等同 never submitted；输入传输使用现有归档上限。PTY 连接为一次性交付，拒绝/中断释放注册、迟到 callback 不复活连接。验收见[故障矩阵](../../evidence/development/runtime-fault-matrix-2026-09-30.md)。

- 2026-09-29：所有者要求终端独立于聊天；抽屉启动目录改为独立目录，显式目录入口保留原语义。
