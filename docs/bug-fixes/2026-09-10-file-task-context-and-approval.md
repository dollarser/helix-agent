# Bug Fix: 文件任务上下文、审批摘要与工具预算

Status: fixed
Date: 2026-09-10
Related HXA: HXA-190, HXA-191

HXA-190/191 所有者授权：精简审批、工具详情默认收起、改善文件任务完成能力并提高默认工具轮次。

## Problem

真机最新写文件轮次执行 8 轮后以 `TOOL_STEP_LIMIT` 结束。已不是空参数或网络中断：调用收到文本参数，但混淆裸路径、模型引用和 scope ID；错误包括 `invalid 'write' arguments`、`invalid 'files.list' arguments`、把 `work` 当 scope，以及将文件写在工作区三目录之外。此前失败会话已经成功追加一次普通回复，历史空参数读取修复与本次路径问题需要区分。

## Impact

文件任务失败与历史构建错误会阻断后续对话；过多默认展开的信息影响审批和阅读。

## Root cause

见 Problem 中的当前设备证据：模型参数/工作区引用与 Harness 历史/工具契约不一致，不能将其统一归因于网络。

## Fix and invariants

- 每次模型请求与工具回填加入实时工作区引用（来自现有 Workspace 绑定），解释 `scope:<id>:<relativePath>`、三个可写目录及简单新文件调用方法。该上下文参与输入预算估计，不构成权限或审批，不注入真实 Android 主机路径。
- 提示模型按具体错误改正参数，不重复同一失败操作；写入需以工具成功结果为依据，结果不确定先对账，不能绕过拒绝。`write`/`files.list` 参数解析失败返回公共路径格式和参数纠错信息。
- 审批默认显示来源/目标、作用域、参数目标摘要、风险和外发域名；完整披露保留在展开面板。批准/拒绝仍绑定同一精确调用，未变更权限、审批或工具执行规则。
- 工具区域默认收起，包括活动轮次；待审批和恢复操作仍可直接处理。展开后查看完整工具列表。
- 默认工具轮次 8→32、模型调用 9→33，可设置上限同步为 64/65；Token 预算不变。仅旧版完整默认配置迁移，自定义配置保留；新增持久标记确保用户之后明确设置回 8/9 也能保留。Goal 总预算不自动扩大。

## Alternatives considered

清空会话、盲目重试或仅扩大预算不能修复错误契约；保留历史结果并提供一致的解析与反馈。

## Regression verification

`scripts/debug/2026-09-10/verify-agent-file-ux.py`：限定文件格式、运行配置迁移/上下文引用与文件工具 JVM 回归、developer 构建。审批设备用例已更新为先检查默认折叠再展开验证完整参数；未运行模拟器或真实模型任务。真实文件写入和审批体验由所有者人工验收。

实际结果：RunControlStoreTest 6/6、ChatEnvironmentContextTest 1/1、WriteToolTest 15/15、FilesMetaToolsTest 19/19，共 41 项通过；限定格式化、developer 构建及 `git diff --check` 通过。主 App 已覆盖安装当前真机，保留登录和会话数据；未修改订阅组件或提交推送。

## Residual risk

真实模型完成率与设备交互仍由所有者人工验收。后续 ADR-WORKSPACE-001 已将文件路径进一步改为会话相对路径，三目录前缀不再是普通文件的强制要求；本记录中的旧批次描述只代表当时交付。

## Related records

- [ADR-WORKSPACE-001](../adr/workspace/001-session-paths.md)
- [当前状态](../development/status.md)
