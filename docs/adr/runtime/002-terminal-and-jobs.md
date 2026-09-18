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
- 196/197 的宿主执行准入共用一个应用进程实例。普通执行许可随真实 executor 退出释放，不随超时/取消回执提前释放；异步/手动 owner 在提交前以原子写保存执行身份，调用返回不清除，只由原身份的终态对账释放。持久准入文件只保存身份，不复制 Runtime 的阶段、日志或结果，也不是新的授权来源；读取损坏/写入失败不能按空闲处理。生产控制入口仍须按原 session/turn/call/job 绑定校验，不能凭 owner 身份获得执行权限。

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
