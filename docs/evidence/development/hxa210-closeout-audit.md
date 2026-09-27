# HXA-210 收口审查

2026-09-27，本地实施范围逐项核对；全量 host gate exit 0，API29/API36 × consumer/developer bounded Workspace 专项 192/192 passed。见[设备证据](hxa210-emulator-verification-2026-09-27.md)与[主机证据](hxa210-host-verification-2026-09-27.md)。

| 要求 | 验收证据与边界 |
| --- | --- |
| Room v1、文件保留 | 51-table v1；无 migration；删除会话不删除文件；不承诺旧开发数据库行/索引兼容 |
| 默认目录、共享资源、身份 | WorkspaceRepositoryTest 19 项及四组合绑定测试；修改内容保持身份，重建拒绝继承，失联显式重绑定 |
| 请求挂起、切换、审批、当前 DENY | 四组合 loopback + Dispatcher/Room；旧响应写原目录，新请求加载新 AGENTS.md；旧审批不覆盖 DENY |
| Queue、Job、运行占用 | host 绑定/保留断言；developer 双 API 实际 PRoot/Job 输入快照、原输出、清理拒绝、DENY/工具禁用 |
| 旧产物 | 四组合 UI 验证原位置/hash；同名新文件不替代旧来源；Room fence 拒绝迟到引用 |
| Path/SAF | host DocumentWorkspaceTest；四组合可控 Provider 与系统 DocumentsUI/ExternalStorageProvider；持久授权、撤销写拒绝、重授；API36 Downloads 根拒选 |
| 生命周期 | host 创建中断、共享引用、备份保留、symlink、并发 fence；设备真实 kill/reopen 无自动重放，显式恢复可重复 |
| 备份 | host 不足/未知大小、部分写、版本/哈希冲突；设备实际删除/恢复边界三次 kill，冲突不覆盖 |
| UI | 四组合 320/360/412dp × 中文/英文/系统模式 × 1/2倍字号；72 张清理截图与可见性/操作断言，代表截图已查看 |
| Gate | 全量 check-all --all；两渠道 AndroidTest 编译；最终诊断 fixture lint/spotless/detekt；文档收口后 source/diff check |
| HXA-227 | 固定 baseline 保留；fresh host candidate 八组 PASS→PASS，不宣称设备全基线或效率收益 |

清理设备切点采用精确预置的测试库状态后真实杀进程，不冒称每个生产 CAS 被精确打断。创建中断由 host 注入验证，不宣称真断电。SAF 按系统授权及 Provider document identity 工作，不承诺识别提供者任意复用/伪造标识，也不将系统 Provider 验证推广至全部 OEM/云服务。

## Diff ownership

Dispatcher、tools/framework 的执行 owner、core/policy 主实现没有生产代码 diff。SessionToolEffectClassifier 仍是原有分类 owner；HXA-210 仅将当前 Workspace 判断从 scopeId 扩展为 resource + 子目录边界，并保留当前权限复检。ChatToolCalls 先 strip presentation intent，再固定 business args 的请求目录，随后沿原 hash/approval/dispatch 路径执行。

Runtime 的生产 diff 仅为既有 ProotTerminalLaunch 私有目录 allowlist 增加 managed Workspace 根；没有新执行域。应用层清理借用 ExecutionOwnership，不修改它的持久 owner、settlement 或取消语义。FileManagerService monitor 只串行化用户手动文件入口与显式清理。

设备追加审查修复 `DetachedJobOutput` 的旧式 scope ID 比较：复用 `SessionToolEffectClassifier.isWorkspacePath` 的资源/子目录判断，当前 DENY 和工具禁用仍由原权限服务处理。没有新增分类 owner 或绕过授权；双 API 实际 Job 反例分别对目录内 DENY、切换后的目录外 DENY、恢复授权及原工具禁用作验证。

工作树同时包含先前 HXA-227 eval/docs 工作；未 reset/stash，未提交或推送。最终审查已覆盖 tracked/untracked 生产和测试路径。
