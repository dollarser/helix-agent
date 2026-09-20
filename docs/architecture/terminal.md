# 终端、日志与后台命令

状态：设计已接受，分切片交付；不据本页宣称已有独立终端或多会话。决策见[执行域](../adr/runtime/001-execution-domains.md)、[终端与 Job](../adr/runtime/002-terminal-and-jobs.md)，实际进度见[实施状态](../development/status.md)。

## 三种对象

一次性 Job 有固定输入与最终结果；异步 Job 增加持久身份、Runtime owner、租期和预算；手动 PTY Session 面向用户交互，不是模型复用的隐藏 shell。UI 观察不启动工作，后台 accepted 不等于成功，结束事件不自行唤醒模型。

Runtime 管理进程组、PTY、日志和退出事实；应用服务管理来源、授权与 Workspace 绑定；UI 展示、attach/detach 和提交明确用户动作。身份与 generation 不依赖 PID，连接状态与执行状态分开。

## 日志与资源

日志读取绑定 Job/generation、stream 和游标，重复读取幂等，跨 Job 拒绝；缺口与截断可见。慢 UI 不阻塞进程 drain，UTF-8 支持跨批解码。初始限制为每批 32 KiB、每 Job UI 缓存 256 KiB、每 Job spool 4 MiB、总 spool 16 MiB，调整须压力证据。日志预览不替代最终产物哈希与退出状态。

一次性 PRoot Job 的实现通过新日志事务提供 8 KiB 数据分片，整个 Parcel 限制 32 KiB；每 Job 另有 16 分片队列与 4096 分片索引上限。详情页仅复用已批准提交获得的 Binder 观察，不冷启动 Runtime；进程死亡后预览失效，最终结果仍走原有对账。分片不进入模型消息或工具结果，consumer 不包含 Runtime 日志实现。具体实现及资源边界见[有界日志证据](../evidence/development/bounded-command-logs-2026-09-18.md)。

异步租期默认 5 分钟、最大 30 分钟并受其他剩余预算限制，不自动续租。主进程死亡能否继续取决于已有后台 owner 与系统允许的路径；否则取消或对账。重启不复建 shell 内存、不重放命令。

## 后台 Job 工具入口

developer 已注册下列入口，沿既有 Dispatcher 的 schema、能力、会话权限、限制与审计执行；注册不会冷绑定 Runtime。当前实现和仍待验收的产品边界见 [HXA-196](../development/tasks/HXA-196.md)。

| 工具 | 参数与行为 |
| --- | --- |
| `code.linux.job.start` | 复用 Linux 命令、输入及原 output；`leaseSeconds` 默认 300、最大 1800。返回 accepted 和 originalCallId，不代表执行成功。 |
| `code.linux.job.status` | 仅 originalCallId；只查询可信当前会话的原 Job，不续租、不导入。 |
| `code.linux.job.cancel` | 仅 originalCallId；幂等取消原 Job，终态仍需收取结算。 |
| `code.linux.job.collect` | 仅 originalCallId；取得原终态与归档，按原 output 及当前授权导入、结算预算与占用。失败重试此入口，不重新启动命令。 |

`collect` 与只读查询分开。其文件效果由宿主查询原绑定和持久 ToolCall 参数决定，不信任收取请求提供替代路径；新 DENY、原工具禁用及撤销的 scope 会阻止延后写入。成功导入回执持久化后，重复收取不覆盖后续用户修改。未知/orphan 仍需恢复审查，不能凭一次查询释放占用。旧同步 `bash`/`code.linux.run` 继续返回同步结果。

Runtime 中断留下 ORPHANED 时，先在 Tasks 收取原结果。若旧进程是否停止无法确认，页面会提示重启设备后再次收取。宿主在提交前记录 Android 启动次数；旧记录首次收取时保存启动观察，必须在此之后实际重启。只有系统启动次数严格增加才能证明旧进程不再执行：此时结算原预算并释放占用，不导入未知输出、不重跑命令，执行状态仍为 ORPHANED。系统证据不可读或倒退时不自动释放。原 Turn 已结束时未知租期只结算一次，不以停止证明缺失为由永久保留预算。

原执行记录丢失时，只有 Runtime 校验原绑定后明确返回 NOT_FOUND、原 Turn 已终态或停驻于 INTERRUPTED，且上述重启证据成立，人工收取才可结算原预算并解除占用。宿主保存独立的记录丢失处置审计，页面显示 UNKNOWN 与结果无法恢复，不生成 Runtime 终态、输出回执或 ACK。Runtime 不可用、绑定失效、同次开机及结算失败仍保留占用；不能把通信失败当作记录不存在。此出口不重放命令。

后台占用期间，宿主显式包装的 Goal、Plan、Todo 内置 executor 可继续处理绑定会话/Turn 的元数据。这个准入不按工具名称或自报 READ_ONLY/METADATA 自动授予，不修改 owner，也不绕过 Dispatcher 权限和各工具的绑定/取消校验。存在 pending 后台时间租期时，`goal.report` 与 `update_goal` 可以上报进度，不能提前上报 complete；先收取原 Job，再检查目标并报告完成。

后台 start 已进入现有聊天工具行与 Tasks 命令列表的详情入口。详情只读本地事实：启动回执显示“已提交，当前执行状态待查询”，不把旧 RUNNING 快照当成此刻仍在运行。显式 status/cancel/collect 核验原绑定后记录不可变终态；结果导入、预算结算与占用释放完成后另记结算凭据。成功、失败或取消的执行终态仍可能待结算，页面分别展示；仅有准备绑定不证明任务已启动。打开页面不冷绑定 Runtime、不查询、不 ACK、不重放。

Tasks 另有按原调用 ID 定位的后台命令行，链接原会话和命令详情；原 Turn 被收取或被 Goal 行合并后，Job 仍可见。待结算项独立于最近 Turn 窗口读取，已结算历史最多保留最近 100 个后台启动调用中的候选项。列表复用平台绑定/终态/结算凭据，准备状态及读取异常保持未知；不解析归档，也不参与 Turn 的 dataSync 前台服务状态。直接查询、取消和收取的用户操作入口已接入应用服务，复用原身份、授权和执行占用，写用户审计，不伪造模型 ToolCall。

启动回执正文文件缺失或无法校验时，列表和详情保持可打开；没有独立终态事实则显示未知，有已验证终态凭据则继续展示其执行及待结算状态。畸形本地 Job 身份在详情中显示读取失败，不因浏览触发重放。

## 手动终端与多会话

developer 用户主动开启可信 USER 入口，人工按键不逐字符出审批卡；模型、MCP、Skill、网页不能凭 session ID 写入 PTY。首片单 live PTY，后续最多两个；每 Session 同时仅一个写入连接，支持 detach/attach。共享 UID 与文件系统，手动执行和 Agent 本地代码/文件修改互斥；人工多会话不证明未知效果可并发。

PTY 字节流与一次性 Job 日志不共用截断策略。Runtime 内的近期输出缓冲最多 256 KiB，每次追加/读取最多 8 KiB，游标绑定 Session/generation；读端落后于保留窗口时明确返回缺口，UI 必须重建解析器/显示并提示丢失内容，不能把不完整转义序列直接拼接到旧状态。EOF 仅表示输出已排空，不证明进程组已停止，也不释放持久占用。主机实现见 `PtyOutputBuffer`；生产服务、Binder 和 developer 渲染页面已接线；页面遇到缺口会换用新的解析器并提示。

手动输入缓冲最多 32 KiB/64 块，每块最多 8 KiB，整块接受或返回拥塞，不截短粘贴。单个 native writer 另持有至多一个在途块并处理部分写入；接受只代表入队，通信不确定时不自动重发。关闭缓冲拒绝新输入并返回待丢弃字节数，不能据此证明在途输入或 shell 已停止。`PtyInputBuffer` 管理字节边界，写入连接校验与进程关闭由会话 owner 承担，不作为授权来源。

`PtyInputConnection` 在同一 Session/generation 内串行处理连接与输入准入，只允许一个写连接，不隐式抢占。detach 撤销之后的输入，不丢弃此前已接收的队列，也不结束 shell；重连使用新标识，旧连接的延迟 detach/输入不影响继任者。队列跨重连保留其总量限制。close 拒绝全部连接并清空待写队列，但不证明在途 native 输入或进程已停止。该连接标识只是进程内写资格，应用服务仍须校验可信 USER 与原 Session；当前已接入产品 Binder 与终端 UI；UI 另有最多四个 8 KiB 输入块的队列，拒绝或未知结果不自动重发。

生产 `ProotPtyProcess` 已提供私有 Runtime 的原生 PTY I/O、resize 和退出观察/回收。读写有界且串行处理 FD 生命周期；观察退出保留原 PID，完成对账再回收。初始组终止不是全部后台作业停止证明，不能据此释放持久 owner。真实 PRoot 及重复关闭证据见[原生 I/O 切片](../evidence/development/hxa-197-native-io-2026-09-20.md)；产品会话及渲染页面已调用该组件。

`PtySessionRecord`/`PtySessionStore` 已提供 Runtime 单写的持久身份和有界原子 CAS。启动意图先于 fork，未保存 PID 的中断也保持未知；停止证明与对账分开，未知/损坏记录不按空闲处理。生产会话 owner 已接线；记录本身不释放应用执行占用，宿主仍须按原身份对账。关闭方向复用锁定 PRoot 的 `--kill-on-exit` 与 SIGQUIT 清理 tracee，发送成功仍不等于停止；具体实现及设备边界见[生命周期切片](../evidence/development/hxa-197-session-lifecycle-2026-09-20.md)。

产品路径为 `AppContainer.manualTerminal` → developer 应用服务 → 私有 `ProotTerminalService` → `ProotPtySession`。开启前转移共享执行占用，consumer 不装配入口；原身份 ACK 后才释放占用。只直接映射应用 Workspace 中的实际目录，手动租期默认两小时、最大八小时，断开后三十分钟空闲回收。终端元数据独立于 Runtime 安装目录保存；修复/回滚/删除与执行互斥。服务接线与尚未完成的页面/恢复范围见[产品会话证据](../evidence/development/hxa-197-product-session-2026-09-20.md)。

手动终端独立规定租期与空闲回收，不套 Goal 预算。接线前验证 PTY/native/rendering 版本和许可证；前台 PTY 可独立验收，不等待后台 Job。

环境首次准备与修复归[HXA-205](../completion-records/HXA-205.md)，包内Runtime资产/升级收尾归[HXA-193](../completion-records/HXA-193.md)，不与命令详情混成一个任务。

## 实施与验收

| 顺序 | 任务 |
| --- | --- |
| 命令详情与结果入口 | [HXA-194](../completion-records/HXA-194.md) |
| 日志观察（一次性 Job 已交付） | [HXA-195](../completion-records/HXA-195.md) |
| 后台 Job | [HXA-196](../development/tasks/HXA-196.md) |
| 单手动终端 | [HXA-197](../development/tasks/HXA-197.md) |
| 多会话 | [HXA-198](../development/tasks/HXA-198.md) |
| 综合验收 | [HXA-199](../development/tasks/HXA-199.md) |

公共 G1～G4 命令在[验收规则](../development/verification-matrix.md)，具体失败、取消、日志边界与恢复用例在对应任务，不在多个计划里复制状态。


## 手动终端页面

developer 文件管理器的 Workspace 目录提供“打开终端”；打开页面只检查本地会话绑定，用户明确启动或连接才接触 Runtime。页面通过 `ManualTerminal` 应用接口使用私有会话，不直接持有 PTY、PID 或执行许可。已有会话必须先结算，不能因切换目录另开 shell。

`ManualTerminalActivity` 在主进程且不导出；ConnectBot termlib 0.2.1 经固定制品校验和显式 close 补丁后在 `:runtime:terminal-renderer` 构建，consumer 不包含组件/native/专用页面。许可证与修改说明在页面可查看，版本与构建决定见 [ADR-RUNTIME-002](../adr/runtime/002-terminal-and-jobs.md)。

页面显示起始 Workspace 目录、Runtime 阶段、停止原因和退出状态，提供键盘、Ctrl-C/Tab/Esc/Ctrl-D、停止和结算。连接期间单个只读观察循环更新状态；断开、终态或 UNKNOWN 后停止，不自动续租、重放输入或重启 shell。Activity 重建保留应用连接；离开页面撤销连接，原执行与持久占用由 Runtime/应用服务继续管理。视图卸载后在 callback looper 释放 emulator；输出和渲染不进入模型上下文。

中文 IME、真实 REPL、原目录写入、重建后的环境保留及停止结算已取得产品页面证据，见[页面接线验收](../evidence/development/hxa-197-terminal-page-2026-09-20.md)。生产进程死亡、长输出/粘贴压力、实际运行长命令的租期终止及 idle/OEM/长稳仍单列，不能从页面成功推导整个 197 完成。
