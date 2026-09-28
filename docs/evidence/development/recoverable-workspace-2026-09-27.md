# 可恢复阻塞与会话目录变更

日期：2026-09-27。范围：所有者要求原目录不可用时使用空目录、会话目录可更换，并审查全产品可恢复阻塞。为 HXA-210/HXA-213 完成后的授权增量，不扩展执行域或权限。

## 实现

- fork 优先复用实时验证通过的当前 Workspace/子目录；不可用时创建独立空目录，不复制文件或授权。
- 打开会话、新的发送、草稿持久化检查目录；恢复仅改变后续会话绑定。旧 ModelCall、ToolCall、审批、产物与共享会话仍保留原身份。
- `workspace.recovered` 审计绑定 Workspace ID/revision，重开后仍显示非阻断提示；显式目录选择通过 `workspace.bound` 消除提示。
- 目录选择失败保留对话框，提供重试及独立目录入口。新目录也无法建立时才阻止新的发送；取消与存储异常不伪装成成功。
- 任务结果、产物结果读取失败提供只读重试。SAF 来源枚举异常不再展示成“无来源”，提供重试及重新授权入口；不声称修复此前设备偶发空列表的未知根因。

## 全产品静态审查

审查使用当前源码的错误状态、按钮可用条件和恢复入口；不是全产品故障注入或设备验收。

| 面向用户的路径 | 结果与处理 |
| --- | --- |
| fork / 打开 / 新发送 / 草稿保存 | 本次增加空目录恢复；文件与已有执行身份保留 |
| 会话设置与目录选择 | 可在运行时改绑未来请求；失败保留选择并可重试 |
| 任务及产物结果 | 本次增加重新读取，不重新执行任务 |
| 文件列表 / 来源 / 回收站 | 已有刷新、重新选择、显式恢复；本次补 SAF 枚举错误区分和重试 |
| 文件预览 / 导出 | 可关闭重开；导出失败可重新选目标。原文件缺失、版本冲突、备份完整性错误不能用其他文件替代 |
| Provider 设置 | 连接测试失败仍可编辑、再测试或更换；不自动使用其他账号或模型，不丢弃附件来绕过能力检查 |
| Connector / MCP / Skill / 市场 | 保留配置、重装/重试及禁用入口；来源签名、权限、工具描述校验不自动绕过 |
| Runtime | 保留验证/修复入口；不可用执行域不能降级到主 App 进程执行 |
| Goal / Turn 恢复 / 审批 | 保留显式恢复与处理入口；UNKNOWN、待审批、取消未收敛不能自动当作成功或重新执行 |
| 发送内容 / 附件 / 披露 | 空输入、超限、附件缺失/变更、未确认披露需要用户修正；保留输入，不静默删改内容 |

适用原则：有安全、保留数据且不改变授权的恢复路径时优先让用户继续，并说明实际结果；需要改变权限、重放副作用、破坏数据完整性或无法建立持久状态时保留阻断及可行动入口。不以 catch-all 返回成功实现“不阻塞”。

## 验证

- `./scripts/check-all.sh --all`：exit 0。包含全工程 JVM tests、debug/release lint、Spotless、detekt、两渠道 debug/release APK、依赖锁及制品边界检查。日志：`build/recoverable-workspace/check-all.log`。
- storage：203 tests / 0 failures / 0 errors / 0 skipped；consumer：824 / 0 / 0 / 4；developer：869 / 0 / 0 / 4。跳过不计为通过。
- `:app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest`：最终测试源码编译通过，日志 `build/recoverable-workspace/android-test-final.log`。
- 最终 `spotlessCheck detekt`：exit 0，日志 `build/recoverable-workspace/final-analysis.log`。
- 主机新增覆盖：可恢复异常与取消/未知异常区分、同一动作恢复丢失的默认目录、新空目录不修改共享文件/冻结请求。
- 设备用例已补并编译：fork 不可用回退、丢失子目录恢复、授权验证失败、取消不创建分支、空目录分配失败回滚、提示重开及手动选择消除、目录选择失败留在对话框并重试成功。当前增量设备状态：`not requested`，未执行这些新增用例，历史 HXA-227 模拟器结果不覆盖本轮代码。
- ownership review：本轮修改位于会话目录准入、存储绑定、UI 恢复入口及测试/文档；没有改动 Dispatcher、policy/effect owner 或既有请求执行路径。保留此前 HXA-227 未提交工作；本轮未 commit/push。
