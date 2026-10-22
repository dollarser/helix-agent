# ADR-RUNTIME-002: 命令日志、后台 Job 与手动终端

Status: accepted
Date: 2026-09-16; revised 2026-10-19
HXA: HXA-195, HXA-196, HXA-197, HXA-198, HXA-286, HXA-288
Deciders: Project owner（2026-10-15 要求按评审更新目标设计和任务；接受范围见 Decision，实施另计）

## Context

终端 UI、日志传输、执行所有权与授权不能混在一个 Session 对象里。

## Decision

- 一次性 Job、异步 Job、手动 PTY Session 分开建模；观察页面不启动执行。后台启动返回 accepted 不是完成，结束事件不自动唤醒模型。
- 日志批次绑定 Job/generation、游标、stream、字节和结束/截断标志；重复读取幂等，跨 Job 拒绝，缺口可见。慢读端不阻塞进程 drain，UTF-8 跨批解码。初始限额：IPC 每批 32 KiB、每 Job UI 缓存 256 KiB、spool 每 Job 4 MiB/总 16 MiB；参数变化需压力证据。日志预览不代替最终结果与产物哈希。
- Runtime 拥有进程组、PTY、租期、日志和退出事实；应用服务拥有任务来源、授权与 Workspace 关联。Session 身份/generation 不等同 PID，连接状态与执行状态分离。
- 异步 Job 有持久身份、显式 Runtime owner、租期和累计预算，初始默认 5 分钟/最大 30 分钟并受其他剩余预算限制，不自动续期。主进程死亡后继续必须已有有效后台路径，否则取消/对账。
- 手动终端为 developer/Advanced 用户主动开启的可信 USER 入口，人工按键不逐字符审批；模型/MCP/Skill/网页不能借会话 ID 获得写入 PTY 权限。当前设计不提供模型交互输入接口。
- 手动终端按 CPU 最多四个，PRoot Job 最多四个，共享原合计六槽预算；低内存/热压收紧新终端准入。各终端支持 detach/attach、单写入连接，终端、独立 JS 与普通工具不全局互斥。共享 UID/文件系统会造成业务结果竞争，由用户与 LLM 验收，不以同目录或写操作类别禁止执行。
- 重启后不重建原 shell 内存、不重放命令。手动终端不套 Goal 预算，其租期/空闲回收、PTY/native/rendering 版本和许可证必须有接线前设备证据。前台 PTY 可以独立验收，不等待后台 Job 成功。
- 197 首片手动终端采用应用 Workspace 内实际目录直接映射 `/workspace`，不提前启用独立会话目录绑定。手动租期默认两小时、最大八小时，不自动续期；断开连接后三十分钟空闲回收。停止记录独立于可删除的 Runtime 安装目录，环境维护与执行互斥。页面、模型和 Agent Goal 不持有 Runtime 进程资源；具体交付与设备边界以 197 任务记录为准。
- 宿主维护多条原执行身份，而不是单个全局排他 owner。每条 identity 只约束自己的提交/控制/结算；保留身份或未收取结果不占用其他任务准入。普通线程、Runtime 物理槽直到真实退出才释放；唯一原生 QuickJS 服务的存活身份只阻止该单实例引擎的新调用。identity 文件损坏不得伪造为空闲，普通无关工具仍可执行；控制入口校验原 session/turn/call/job，不凭身份授予权限。
- HXA-248：已获准 detached lifetime 的 Linux Job 可在初次提交后 AUTO 有界等待（最多 15 秒），快任务收取原结果，未完成返回同一原调用句柄。等待模式与 lifetime 正交；后台资格不足的 one-shot 保留原有限执行/取消契约。提交仍经过原 deadline runner 和 execution ownership，等待复用 J1 物理观察通道；释放业务等待不释放实际 Runtime 容量、不重放命令、不续租。DSH 取消累计任务额度门禁不取消 Runtime lease。用户提前释放等待为后续独立按钮检查点。

### HXA-197 渲染组件与构建兼容

采用 ConnectBot termlib **0.2.1**（固定 tag commit `27e024fccb2d722b47c57f5da1b4da9bca477b68`）作为 developer 专用显示/输入组件；它不拥有 PTY 或执行许可。Apache-2.0 与 bundled libvterm MIT 文本、归属及修改说明随应用打包，并在终端页面可查看。

`:runtime:terminal-renderer` 从校验过的发布 sources JAR 编译 Kotlin，应用已通过独立设备探针的显式 close 补丁；native 库与资源来自同版本 AAR，不同时打包其原始 classes.jar。构建校验固定 SHA-256，源码只生成到 build 目录。关闭顺序为停止输入/输出生产者、卸载视图、在 callback looper 释放 emulator；依赖版本更新必须复核补丁与制品。

组件要求 compileSdk 37；本次明确升级 compileSdk 至 37、Compose BOM 至 2026.09.00，保持 targetSdk 36 和 minSdk 29。CI 同时保留用于既有兼容探针的 API36 SDK。原生 ABI 为 arm64-v8a/x86_64；16 KiB ELF/ZIP 静态检查和真实 16 KiB 设备运行分别记账。OSC 自动剪贴板、图片及链接自动打开不启用，模型不获得终端输入能力。生产页面验收以 197 任务和证据为准，构建接线不代表验收完成。

当前抽屉开启的手动终端使用应用私有 `workspaces/app/terminal` 目录，不依赖选中会话；文件管理器显式传入 scope 路径时仍使用该目录。终端 detach/attach 与租期不变，关闭或切换聊天不停止终端，Advanced、物理容量和运行环境维护约束仍适用，不再全局排斥 Agent 任务。

人工“关闭终端”一次操作终止所选 PTY 及其进程树，内部等待退出证明后按原 identity/generation ACK 并释放名额；不要求用户先停止再结算，不影响其他终端/Job，不删除工作区或重发命令。租期、停止意图、缺失 PID、版本号或安装时间本身均不是退出证明。关闭未能确认进程结束时报告未完成，保留原身份以供重试。

覆盖安装后，Android 的原 `:proot` 包更新退出记录、晚于终端创建的 PackageManager 更新时间、同 boot 与一致的 wall/elapsed 时钟共同证明旧物理占用结束，允许显式关闭。API34+ 要求 `REASON_PACKAGE_UPDATED`；API30–33 的旧系统原因还需与安装时刻相符。无系统证据、API29 或时钟不一致时不猜测，仍可由严格递增 boot count 证明设备重启。恢复后原命令结果保持 UNKNOWN，不伪造退出码或恢复 shell 内存。旧 v1 PTY 日志严格读取后可写为包含更新证明的 v2，原 origin、CAS、授权和声明版本保持。

### HXA-240：FFmpeg 复用 Advanced Bash / Job

所有者选择复用既有执行通道，而非独立 `runtime/media` 或五种固定操作。固定 Ready-AV1 仅随 developer 的 PRoot 模块打包；guest `ffmpeg` / `ffprobe` 用原样 argv 调用 APK 内 Android/Bionic CLI。公开 Android 系统路径与 APK 原生目录的显式 PRoot 映射是 ABI 桥接，不把 Bionic 程序当成 Alpine/musl 包；不新增 Service、JNI 执行器、后台任务引擎或全局锁。

PRoot 已有共享 UID、原 Job 进程组/取消/预算/日志/恢复语义保持。多输入和滤镜/字幕/编码策略交给模型与 FFmpeg；额外资源仍受原执行权限。显式 snapshot 的输入快照使用 Job 64 MiB/128 MiB 传输上限，stdout 和旧 result.txt 预览限额不因此扩大。仅 snapshot 沿 `output/` 文件导入发布约定；默认原位模式登记原文件引用，不复制文件正文。两路均按原 session/turn 幂等结算，不覆盖被用户改动的文件，不重跑命令补收取。

Standard 不因本裁决加入 PRoot/FFmpeg，已有图片与视觉功能不变。编译组件、设备能力、输出文件完整性和媒体任务正确性分别反馈；未经过当前设备验证的 MediaCodec/Bionic-in-PRoot 路径不写成已验收。正式制品继续核对全部依赖、来源许可和 25 MB 增量门槛。

### Job 操作面与类型化结果（HXA-288）

启动、status/await、collect/ACK、cancel 原 Job 和停止等待按共同结果契约收敛，保留各自身份、effect 和生命周期。模型可发现的操作与 UI 观察共享原持久事实，不能凭 toolName/任意 JSON 猜 Job 已完成或已收取。

这些语义先在 HXA-286 冻结，HXA-288 执行核心实现后再与 287 的完整 Turn 最小纵切集成。原 jobId/executionId 与长期 ExecutionResource/resourceId、实例 generation、当前控制 callId 分开；调用返回、进程退出、输出收尽和结果落盘分别记录。进程退出不是输出 drain/EOF 或 collect/ACK 的完成证明，截断/缺口明确；cancel requested/confirmed 不冒充实际退出。按原身份重启 reconcile，迟到旧实例不改新实例，未知效果不得重发命令，原输出/资源 owner 完成其清理责任。

本任务整理既有 Job 能力，不启用模型写入手动 PTY、不新增持久 Shell 平台。原日志、租期、进程组、物理槽与未知效果保护继续适用；产物 publication 复用现有 Workspace 归属与幂等收取。

### 模型持久终端的后续接入边界

当前手动 PTY 已由 HXA-302 验证实际 R8 输入、页面重建后原实例重连和停止；非调试子进程 exec 前 procfs 暂不可见采用对原未 reap 子进程的有界等待。后续迁移保留该行为，不改变 dumpability、重启命令或把身份不可见当作已退出。它不授予模型输入权限，也不承诺进程死亡后 shell 内存继续存在。

HXA-288 不把 PTY/持久进程生命周期绑定到单次工具调用，也不将所有控制操作建成新 Job。未来模型终端需单独定义创建、输入、读取游标、关闭和人工接管，绑定原实例/generation、Workspace 及当前调用授权；复用 Runtime 能力不继承人工 USER 入口的免逐键审批资格。

输入写者协调只针对具体终端，原资源容量仍有界，不引入全局 Agent/终端锁。当前手动终端和 Job 行为不变，模型输入仍未启用；进程死亡后身份可对账、内存不可假定恢复，后续产品另定租期与失效体验。

### 工作目录、输入与产物（2026-10-17 目标契约）

手动 PTY 挂载 App/managed 中的实际目录，抽屉默认目录独立于聊天。2026-10-21 Owner 明确要求 Job 默认在已授权的真实工作区执行：`code.linux.run`/`code.linux.job.start` 默认 `in_place`，在支持的 App/managed POSIX 目录挂载原目录；`snapshot` 为显式选择，不静默回退。调用前冻结 Workspace 引用，Runtime 复核目录身份；单次命令的嵌套 cwd 不修改会话目录。恢复原活实例、创建新实例和重发命令分别表达，进程死亡不保留 shell 内存。原位/Binder/Room 与日志恢复的后续有界验收见[结果日志收尾](../../development/result-log-closeout.md)，不外推完整迁移矩阵或其他 OEM。

真实工作区支持时与原生文件/exec/终端共享资源。URI-native 消费者直接使用 Provider；只有不支持 URI、确需 POSIX 桥接的消费者才物化。worktree/snapshot 是明确模式，远程本身不要求复制；并发不增加全局文件/结果锁，不向任意 Shell 承诺原子 CAS。原位模式不收取文件内容，仅传日志和显式 `artifacts` 清单，核对原版本后登记原路径；无需 `output/` 约定。取消不回滚真实文件修改，结果恢复不重跑命令。287/288 的完整联合矩阵和类型化资源迁移仍独立验收。

原位模式输入沿真实目录层级使用，不复制或改名；引用必须在本次挂载工作区内。显式快照暂沿已有输入映射及 `output/` 发布约定，跨来源映射重构仍归 286/288，不据此声称完成整项迁移。输出核验、产物登记和回写源文件分别记录；登记失败按原 receipt 继续收取，不重新执行命令，不覆盖后来改动的文件。共用 [§23.7.3](../../architecture/harness-refactor-plan.md#2373-目录优化与工作副本迁移检查点) 的 Room/执行联合门槛，不新增模型 PTY 能力。

## Alternatives considered

通用持久 shell 替代全部 Job 会破坏身份与结算；仅最终输出不足以支持交互；每个按键做 Tool Approval 无法形成可用终端。

## Consequences

同一主题使用一份有效契约，避免并行实现各自解释权限和生命周期。代价是实现、UI、数据与恢复需要一起验证；accepted 表示决定，不代表相关任务全部完成。

## Verification

按[终端开发计划](../../architecture/terminal.md)分别验收日志、后台 owner、PTY 与多会话。accepted 不代表这些切片全部已交付。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## Decision history — 2026-10-15

顺序复核落地：Job/长期资源身份及返回、退出、收尽、落盘的判据前置到 286，288 分核心/集成/适配验证取消迟到与恢复；不因此启用模型 PTY 或给普通工具附加 Job。本次仅更新文档任务。

扩展性补充：澄清模型持久终端的未来接缝，人工权限、资源生命周期和每次控制调用分开；HXA-288 仅验证现有资源边界。 本次只更新目标约束，未实现四类后续能力。

将现有 Job 的结果/控制整理纳入 HXA-288；与模型持久终端候选分开，不再把二者混为同一个远期任务。 本轮只更新设计与任务，代码和验收由对应 HXA 记录。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)

## Decision history

- 2026-10-22：将产物发布说明明确限定到显式 snapshot，原位模式仍只登记原文件引用；移除正文中过时的“设备验收仍待”状态，以当前有界证据表达范围，不改变执行和授权契约。

- 2026-10-21：Owner 进一步明确默认真实工作区原位执行，取消 Job 默认工作副本与文件回传。按现有私有 PRoot 进程、目录解析和身份验证接线，保留显式快照；新增原路径产物登记及日志/元数据传输。协议版本随两端同步升级，不以主机编译冒充设备验收，不新增 SAF POSIX 仿真或自动写回。

- 2026-10-21：Owner 要求简化 PRoot 结果归档。当前快照 Job 只归档显式 `output/` 产物、既有 `result.txt` 输出和有界 stdout/stderr，不再回传全部输入副本及中间文件；拒绝产物符号链接。ZIP 保留为内部完整性校验与原作业恢复传输载体，普通产物仍按原格式独立发布，模型仍直接收到文本和文件引用。这不切换当前快照后端、不自动回写源文件，也不重跑命令补结果；真实工作区原位迁移继续按既有联合验收完成。

- 2026-10-19，容量取舍：Owner 要求评审两个手动终端及疑似两 Job 限制。源码确认手动绑定固定 2，工具 Scheduler 默认 2，PRoot Job 已有全局 4 槽且没有另设每会话 2 限额。采用现有四 worker 上限按 CPU 准入、资源压力降至一；手动终端单类最多四、Job 最多四，合计保持旧 4+2 的六槽预算。预留/实际退出边界、UNKNOWN、原身份 ACK/CAS 和环境维护保护保留；已证明退出的历史不再占新建名额。参考 VS Code 独立多终端和 Android MemoryInfo/热状态，四核模拟器不能证明物理手机容量或长稳；不声称竞品无限并发。

- 2026-10-19：Owner 将默认真实工作区原位、按消费者能力物化、独立终端和双路径验收纳入 286/287/288；当前快照 Job 保持至完整实现，近期小优化不提前切执行后端，工作区规则归原 WORKSPACE-004 §9。

- 2026-10-19：Owner 要求说明后台保留、聊天 Job 与手动终端的关系及起始目录，并继续压缩顶部占用。采用单行导航/终端选择/键盘/关闭/更多，普通目录和阶段移入按需信息，异常状态直接可见；“新建终端”随终端选择展开，不再为两个终端常驻第二行。参考 [VS Code 终端管理](https://code.visualstudio.com/docs/terminal/basics)、[Android app bar](https://developer.android.com/develop/ui/compose/components/app-bars) 和 [48dp 触控规范](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)，采用移动端单行信息层次和完整触控区，没有复制参考源码。帮助明确返回只 detach、关闭结束所选进程树（包括 `&` 后台子进程）、租期/空闲限制和 `cd` 与创建映射的区别；聊天 Job 身份和输入副本保持独立。布局调整不新增永久后台任务、模型 PTY 或自动续期。

- 2026-10-19 追加：Owner 报告手机覆盖安装后终端 UNKNOWN/无法新建，并明确关闭应一次终止终端中的全部进程。日常 API36 ARM64 相同版本/签名覆盖安装已复现；修复在原 PTY 生命周期中增加上述包更新证明和单步关闭，拒绝将失败 ATTACH 当只读连接，保留丢失 START 回执的原身份。Android 退出原因与更新流程参考 [ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo) 和 [AOSP InstallPackageHelper](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/pm/InstallPackageHelper.java)；没有复制上游代码或新增执行域。设备复现不外推手机/OEM 通过，修复验收单列。

- 2026-10-19：Owner 再次要求手动终端独立于聊天。源码核对侧栏入口不传聊天 ID，默认目录与 PTY 身份已独立，关闭/切换聊天不结束终端；本轮将 UI 的“启动/停止会话”和“结算并关闭”改为终端操作名称，帮助明确无需创建或选择聊天，并纠正架构页残留的旧启动步骤。没有新增模型 PTY、改变人工入口授权、Advanced 限制、租期或进程恢复语义；源码核对不作为新设备验收。

- 2026-10-17，HXA-304：真实模型验收发现 `job.collect` 只返回结算/产物元数据，模型无法读取已成功作业的标准输出。收取原已验证归档后返回有界 stdout/stderr（每流 4096 字符）及 available/truncated 标志，读取失败不伪造空输出；重复 collect 读取原本地归档，不重跑、不重复导入。没有 `output/` 产物时不提供不存在的 artifactDirectory。原授权、receipt、结算与 ACK 顺序保持；类型化结果终态仍由 288 继续替换。

- 2026-10-17，原则澄清：近期先展示实际映射、不切 Job 默认模式只是该补丁范围。重构按当前任务语义与扩展性重新选择 live/snapshot 及实现，不以旧输入命名、存储或默认行为作为兼容要求；每条替换路径验证后删除旧实现。

- 2026-10-17：按 Owner 目录研究区分 live PTY、snapshot Job、cwd 与产物/回写；由 286/287/288 完整路径迁移并验证源变化、旧实例、重复 collect 和发布后通知失败。本次仅规划，不继承已有手动 PTY/R8 证据为新映射通过。

- 2026-10-16：Owner 要求复核后续重构。将 HXA-302 手动 PTY/R8 修复纳入现有基线，288 迁完成/退出/输出/持久语义而不重做终端；模型 PTY 和常驻 stdio 仍需具体场景、输入/流式通道/账号/生命周期验收，不随资源接口自动启用。当前同 UID 私有进程约束保持，不新增执行域或虚构凭据隔离。

- 2026-10-16，HXA-302：人工打开终端页面即查询并连接保留实例，仅首次进入且无保留实例时启动。后台返回和旋转不重复启动；UNKNOWN、待结算及启动失败保留显式处理入口。菜单收纳帮助、运行环境、刷新与停止，实际 PTY 身份、租期、容量、单写者和模型未启用边界不变。

- 2026-10-09，HXA-248 J2-2：用户后台释放只对仍在 AUTO 原启动观察中的已批准 detached Job 可用；在原 Binding 检查后停止该观察，不停止其他 jobs.await，不取消进程，不续期或重放。UI 资格来自持久调用与当前观察注册的联合提示，服务仍复核；进程死亡不由 UI 布尔值恢复观察。

- 2026-10-09，HXA-248：所有者授权按竞品对照继续 J2-1→J2-2、模拟器/真实模型验证及逐任务提交。接受上述同身份 AUTO 等待契约；固定 Codex 源码和 Claude Code 文档的采用点/Android 取舍见 HXA-248，不复制参考代码。接受设计不等于实现和设备验收已完成。

- 2026-10-04：修复所有者报告的 guest FFmpeg linker/ICU 启动失败：直接映射可读的 `/linkerconfig/ld.config.txt`，不以其目录的可访问性为前提；只在媒体包装脚本内设置固定的 APK 与 ICU APEX 库搜索路径。仍复用原 Job/PTY、原样转发参数，不改 RootFS、不复制平台库、不新增执行域。

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

- 2026-10-21：Owner 要求不可恢复的旧终端有重置出口，并明确只限终端数据，禁止清除全应用数据。首次进入遇到不可连接的旧 shell 且有容量时创建独立空白 shell，不重放旧命令；重进不重试不确定的自动启动。清空显示只重建解析器并保留游标，不改 shell history/审计。停止并关闭先确认，再按原身份 STOP → 停止证明 → ACK/释放。显式“停止并重置所有终端”停止捕获到的旧会话，只移除有证明的入口并在全部结束后启动空白 shell；不能证明时明确重置未完成并保留原身份。聊天、账号、Provider、模型及工作区不删除，必要执行凭据保留。不调用系统全应用清除 API。

- 2026-10-21 追加恢复边界：日志缺失的原终端先持久记录精确 execution/generation 与当前系统启动次数；严格更晚的设备启动后，且原日志仍 NOT_FOUND，才可释放原宿主绑定并保留独立恢复凭据，结果仍 UNKNOWN，不伪造 Runtime ACK。首次观察已经在重启之后还需后续一次重启，读取不到启动次数时不猜测。开启终端时会自动回收有证明的旧绑定，避免只提供文档建议。维护页另有“删除并重新准备”，沿原停止证明与共享维护准入，只重建 `filesDir/runtime`（展开环境、环境内软件/文件及未收取 Job 结果），保留目录外的数据。未知占满时先观察/刷新、由用户重启设备，再打开终端按启动证明恢复；同一开机内不能可靠停止的身份不靠删除元数据冒充重置成功，也不擅自清除其他数据。正常新建不要求重建环境。本轮只主机验证，不执行清除或设备操作。

- 2026-10-21：Owner 授权补齐失败终态日志。已有归档与原 Job 证据路径支持失败/取消的有捕获终态，只登记内部证据，不把失败输出发布为成功文件；同会话 `code.linux.job.read_log` 复用既有实时日志分页与持久归档，读取不启动/重放/ACK，预览 EOF 与业务终态分开。关键词全局搜索不在本轮。
