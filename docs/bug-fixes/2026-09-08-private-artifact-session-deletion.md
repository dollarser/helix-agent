# Bug Fix: 删除会话拒绝清理订阅结果与 Goal 私有证据

Status: fixed
Date: 2026-09-08
Related HXA: HXA-102, HXA-104
Affected modules: core/workspace, app

## Problem

完整订阅 Chat 回归在清理已完成会话时触发 `privacy deletion is limited to workspace data regions`。数据库的删除清单包含 `.helix/subscription-results/` 文件，但文件删除接口仅允许 input/work/output。Goal 证据文件使用同样不被允许的 `.helix/goal-evidence/` 路径。

## Impact

删除会话可能先删除数据库记录，随后文件清理抛错，留下私有结果文件。旧记录证明文件路径进入删除清单，未证明文件本体实际删除；本次补齐这层验收。缺陷不涉及 Runtime 凭据读取或删除。

## Root cause

新增私有结果存储接入了 artifact 删除清单，未同步用户专用的 Workspace 文件隐私删除接口。普通文件操作与内部产物使用不同目录边界，组件测试没有串起真实结果落盘和会话删除。

## Fix and invariants

`deletePermanentlyForPrivacy` 仅额外允许 `.helix/subscription-results/` 与 `.helix/goal-evidence/` 两个固定产物目录。仍通过原有 scope/规范路径/符号链接检查，只删除单个普通文件；不存在时可重复调用。元数据、其他内部目录、目录本身和越界符号链接不获准删除。普通模型 Tool 的路径能力保持原实现。

完整 Chat 测试在清理前保存自身 artifact 路径，调用生产会话隐私删除后逐文件断言不存在；测试配置恢复放在独立 finally，清理失败也会执行配置恢复。

## Alternatives considered

没有将整个 `.helix` 开放给单文件删除，没有在清理错误中吞异常，也没有绕开生产删除接口仅删除测试文件。调整存储目录会影响已有结果回读，不能解决当前已登记的内部产物路径。

## Regression verification

JVM 新增三项：正常结果与证据的幂等删除、保留元数据和目录、拒绝越界符号链接。修复前第一项失败，基线 XML/日志保存在 `build/main-verification/subscription-private-delete-baseline.*`；修复后 Workspace 90/90、零失败/错误/跳过。

API29/36 的四平台完整 Chat 测试每 API 4 个方法，各遍历四个平台，覆盖预算拒绝、停止取消、Runtime 死亡和跨 Provider 会话切换；会话清理同时检查实际文件删除。结果 `build/main-verification/subscription-chat-boundary-final-result.json`，精确命令、APK 指纹和原始日志在相应双 API 目录。首次设备失败及随后测试同步/有效预算修正的失败记录分别保留，未冒充首轮全过。

构建、Detekt、Consumer/Developer Debug Lint 通过，见 `subscription-private-delete-verified-build.log`；Lint 同轮发现的六条 Developer 专用 PRoot 资源已从公共资源迁移至 Developer，三语言内容不变，i18n 检查通过。

## Residual risk

这是显式删除路径的修复，不构成数据库与文件系统的跨介质原子事务。其他 I/O 故障和进程在删除期间死亡的恢复仍按原产品边界处理；本次不宣称扫描或删除所有历史孤儿文件，也未操作账号凭据。

## Related records

- [HXA-102 完成记录](../completion-records/HXA-102.md)
- [CLI 结果恢复专题](../development/cli-result-durable-recovery-gap.md)
- [当前收口待办](../development/main-optimization-todo.md)
