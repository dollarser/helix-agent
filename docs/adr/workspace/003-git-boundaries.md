# ADR-WORKSPACE-003: Git 仓库一致性与产品边界

Status: accepted
Date: 2026-09-16
HXA: HXA-088
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

仓库一致性和隐式执行需要独立约束，不能以 Runtime 的安装或 UID 布局替代。

## Decision

- 明确区分 Runtime 中可用的 git 命令、单次 Job 的仓库副本、结构化 Git 产品。安装 git 不证明 Git UI、持久仓库或远程认证可用。
- 持久仓库有唯一权威位置；工作树、index、refs、objects 和锁一起协调。快照执行需要完整输入/输出验证和冲突处理，不将零散 .git 文件覆盖到权威仓库。
- 结构化 Git 操作必须限定仓库和真实操作效果，不隐式执行 hooks、filter、外部 diff/merge、pager、credential helper、submodule 或 worktree。仅禁用 hooks 不足以排除 filter 执行。
- 取消/崩溃后按实际仓库与操作记录对账，不通过重跑写操作猜结果；完整性诊断需读取结果语义，不只检查进程退出码。
- PRoot 具备联网能力；是否允许某次 git 网络/文件操作由执行域和当前授权决定，不宣称“PRoot 天生离线”。结构化远程 Git UI、凭据生命周期和额外后端接线须由对应 HXA 明确范围与验收，不能从 Shell 可联网推导已交付。
- 后端库版本由依赖清单与兼容性测试维护；本决策不把某次 Spike 的 JGit/libgit2 版本或仓库大小当永久限制。普通文件恢复不依赖用户配置 Git。

## Alternatives considered

原始 Shell 命令不能替代结构化 Git 产品；全量仓库往返有空间/冲突成本；库后端有 Android/许可证成本。选后端需实际任务证据，不保留过期的“接受前禁止”规则。

## Consequences

同一主题使用一份有效契约，避免并行实现各自解释权限和生命周期。代价是实现、UI、数据与恢复需要一起验证；accepted 表示决定，不代表相关任务全部完成。

## Verification

本次为现行决策整理，不新增功能通过结论。实现范围与实际命令结果以[实施状态](../../development/status.md)、对应 HXA 及完成记录为准；修改本契约后须覆盖成功、失败、取消、边界和恢复，不能用文档门禁代替设备/功能验收。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)
