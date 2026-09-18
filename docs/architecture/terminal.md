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

后台占用期间，宿主显式包装的 Goal、Plan、Todo 内置 executor 可继续处理绑定会话/Turn 的元数据。这个准入不按工具名称或自报 READ_ONLY/METADATA 自动授予，不修改 owner，也不绕过 Dispatcher 权限和各工具的绑定/取消校验。存在 pending 后台时间租期时，`goal.report` 与 `update_goal` 可以上报进度，不能提前上报 complete；先收取原 Job，再检查目标并报告完成。

后台 start 已进入现有聊天工具行与 Tasks 命令列表的详情入口。详情只读本地事实：启动回执显示“已提交，当前执行状态待查询”，不把旧 RUNNING 快照当成此刻仍在运行。显式 status/cancel/collect 核验原绑定后记录不可变终态；结果导入、预算结算与占用释放完成后另记结算凭据。成功、失败或取消的执行终态仍可能待结算，页面分别展示；仅有准备绑定不证明任务已启动。打开页面不冷绑定 Runtime、不查询、不 ACK、不重放。独立 Job 的 Tasks 行与手动控制入口仍属 196 的剩余接线。

## 手动终端与多会话

developer 用户主动开启可信 USER 入口，人工按键不逐字符出审批卡；模型、MCP、Skill、网页不能凭 session ID 写入 PTY。首片单 live PTY，后续最多两个；每 Session 同时仅一个写入连接，支持 detach/attach。共享 UID 与文件系统，手动执行和 Agent 本地代码/文件修改互斥；人工多会话不证明未知效果可并发。

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
