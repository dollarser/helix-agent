> 证据快照：本页的状态、分工、命令结果和设备仅对应记录时点，不作为当前开发指令；当前入口为[实施状态](../../development/status.md)。

# Harness 合入 main 与工作树退役

日期：2026-09-16。所有者授权合并当前Harness分支到main，冲突以Harness为准，保留必要未跟踪材料后删除重复工作树。不包含推送或发布授权。

## 合并边界

- main起点：`3b808a68`；Harness起点：`69182f53`。开始时两个工作树均无未提交源码。
- 使用`git merge --no-ff --no-commit -X theirs worktree-harness-2.0`，自动合并成功，无遗留冲突。
- 提交前比对合并索引与Harness：应用、Runtime、依赖、测试和产品文档一致；仅保留main独有的两个历史脚本`organize-harness-research.py`、`register-harness-handoff.py`。另外新增本记录与保留脚本。
- 本地合并不表示远端main已更新，不关闭Plan用户闭环、远端CI资产、账号、真机后台或发行验收。

## 本地材料保留

保留目录：`build/worktree-archives/harness-2.0-69182f53/`（忽略目录，不进Git）。

- 保留原顶层`build/`中的测试证据、故障取证与调试档案，以及各模块的reports、test-results、outputs、原local.properties和Runtime资产。
- 共32016个记录，文件内容合计2972180265字节。`manifest.json`列出原相对路径、目标路径、大小与SHA-256；复制后逐文件读取校验。历史证据中的旧`build/...`现在位于该归档的`build/...`下；模块XML/APK也保持其相对层级。
- 锁定PRoot/RootFS资产同时复制到main的实际构建位置，原main资产另存`previous-main/`；按源文件hash再次校验，不重写锁文件。
- Gradle、CXX和Python缓存及可重建中间文件不迁移。原工作树无其他未跟踪源码、账号文件或签名材料。
- 保留脚本：`scripts/debug/2026-09-16/preserve-harness-worktree.py`。原始材料保留在本机，不作公开发布。

## 验证与退役顺序

在main执行`./scripts/check-all.sh --all`并通过，日志`build/harness-main-merge-check-all.log`；源码、主机、lint、Debug/Release构建、35个锁文件与最终制品边界全部通过。新增记录再次通过源码门禁，日志`build/harness-main-merge-source-final.log`。设备源码与已验证Harness一致，本次不重复设备矩阵；原结果可从上述归档追溯。

合并提交后确认Harness HEAD是main祖先、源工作树仍干净且HEAD未变化、归档完整，然后使用`git worktree remove`移除原工作树。保留`worktree-harness-2.0`本地分支引用用于追溯，不删除其他工作树。最终结果以实际Git历史和交付回复为准。
