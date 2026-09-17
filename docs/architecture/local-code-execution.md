# 本地执行域与恢复

唯一决策入口：[执行域](../adr/runtime/001-execution-domains.md)、[QuickJS](../adr/runtime/003-quickjs.md)、[Root](../adr/runtime/004-root-service.md)。

| 执行域 | 打包与进程 | 真实边界 |
| --- | --- | --- |
| QuickJS | 非导出 isolated process | isolated UID；无特权主机桥接；生成代码不在主进程运行 |
| PRoot | developer 内置模块、私有 :proot 进程 | 共享应用 UID；可信开发者环境，不是内核沙箱，不承诺离线或只读隔离 |
| Subscriptions | developer 内置模块、私有 :subscriptions 进程 | 正常 API 隐藏 token；同 UID 不构成凭据隔离 |
| Root | 用户显式授予的可选能力 | 系统授权只满足能力条件，不能替代工具授权 |

consumer 排除 PRoot/Subscriptions 的依赖、manifest、dex 和 assets/native 制品；不得用仅隐藏入口冒充排除。Runtime 进程不重复启动主应用容器或 Room 恢复。

## 工作所有权

应用服务持有 Job、会话、scope、授权和预算；Runtime 持有进程组、执行状态和退出事实。按用户验证/修复/登录或获准 Job 冷绑定，不在启动、切换设置或被动发现时执行工作。

Binder/PFD 使用有界消息与快照，校验 Job ID、generation、owner、长度与完整性。安装与修复先验证来源和制品，再提交可用环境，失败保留可解释状态；不能因依赖升级后还能编译就认为环境可执行。

输入输出绑定真实 scope 与相对路径，拒绝路径逃逸和身份错配。导回文件验证哈希、冲突与写入边界，产物入口指向实际可读取结果。不能以同 UID 模拟沙箱证明网络或文件写禁令；无法约束的执行按策略拒绝或要求合适模式。

## 取消与恢复

停止传播到排队、审批等待和进程组；所有槽位最终持久结算。Binder 断开、EOF、超时与进程死亡先查询原 Job，明确成功、失败、已取消或未知副作用。未知不得自动重试；用户核查与继续是独立动作。

持久后台 Job 必须有明确 Runtime owner、有效租期和可用 Android 后台路径，不能把普通同步命令自动升级为后台任务。日志、手动 PTY 与多会话的设计和分期见[终端](terminal.md)。[验收规则](../development/verification-matrix.md)包含真实进程重启、设备所有权和双 flavor 边界。
