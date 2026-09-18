# 终端、日志与后台命令

状态：设计已接受，分切片交付；不据本页宣称已有独立终端或多会话。决策见[执行域](../adr/runtime/001-execution-domains.md)、[终端与 Job](../adr/runtime/002-terminal-and-jobs.md)，实际进度见[实施状态](../development/status.md)。

## 三种对象

一次性 Job 有固定输入与最终结果；异步 Job 增加持久身份、Runtime owner、租期和预算；手动 PTY Session 面向用户交互，不是模型复用的隐藏 shell。UI 观察不启动工作，后台 accepted 不等于成功，结束事件不自行唤醒模型。

Runtime 管理进程组、PTY、日志和退出事实；应用服务管理来源、授权与 Workspace 绑定；UI 展示、attach/detach 和提交明确用户动作。身份与 generation 不依赖 PID，连接状态与执行状态分开。

## 日志与资源

日志读取绑定 Job/generation、stream 和游标，重复读取幂等，跨 Job 拒绝；缺口与截断可见。慢 UI 不阻塞进程 drain，UTF-8 支持跨批解码。初始限制为每批 32 KiB、每 Job UI 缓存 256 KiB、每 Job spool 4 MiB、总 spool 16 MiB，调整须压力证据。日志预览不替代最终产物哈希与退出状态。

异步租期默认 5 分钟、最大 30 分钟并受其他剩余预算限制，不自动续租。主进程死亡能否继续取决于已有后台 owner 与系统允许的路径；否则取消或对账。重启不复建 shell 内存、不重放命令。

## 手动终端与多会话

developer 用户主动开启可信 USER 入口，人工按键不逐字符出审批卡；模型、MCP、Skill、网页不能凭 session ID 写入 PTY。首片单 live PTY，后续最多两个；每 Session 同时仅一个写入连接，支持 detach/attach。共享 UID 与文件系统，手动执行和 Agent 本地代码/文件修改互斥；人工多会话不证明未知效果可并发。

手动终端独立规定租期与空闲回收，不套 Goal 预算。接线前验证 PTY/native/rendering 版本和许可证；前台 PTY 可独立验收，不等待后台 Job。

环境首次准备与修复归[HXA-205](../completion-records/HXA-205.md)，包内Runtime资产/升级收尾归[HXA-193](../development/tasks/HXA-193.md)，不与命令详情混成一个任务。

## 实施与验收

| 顺序 | 任务 |
| --- | --- |
| 命令详情与结果入口 | [HXA-194](../completion-records/HXA-194.md) |
| 日志观察 | [HXA-195](../development/tasks/HXA-195.md) |
| 后台 Job | [HXA-196](../development/tasks/HXA-196.md) |
| 单手动终端 | [HXA-197](../development/tasks/HXA-197.md) |
| 多会话 | [HXA-198](../development/tasks/HXA-198.md) |
| 综合验收 | [HXA-199](../development/tasks/HXA-199.md) |

公共 G1～G4 命令在[验收规则](../development/verification-matrix.md)，具体失败、取消、日志边界与恢复用例在对应任务，不在多个计划里复制状态。
