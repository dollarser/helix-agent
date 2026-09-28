# 主 worktree 集成：2026-09-28

所有者要求把 `codex/tool-discovery-eval` 合并到主 checkout 的 `refactor/clean-slate-engine`，未要求推送或设备测试。

## 归属与保全

- 主目录起点 `a6d84707`，包含 Mobile Use Plugin MVP `019699e0` 和插件路线文档；源分支起点 `8f26b704`，包含工具发现、精确文件提示和评测记录。
- 本轮已验证改动形成源提交 `79e0d6a8`：操作权限、无进展保护，连同此前尚未提交的系统设置授权、恢复、滑块和预算改动。
- 合并前对主目录 14 个非忽略 dirty 路径、源目录 186 个路径保存内容副本、SHA-256、HEAD、working/index patch；本地备份位于 `build/merge-operation-20260928/`。没有 stash/reset 另一端工作。
- 主目录 3 个文件与源版本相同，7 个匹配源历史提交，3 个评测文件经 diff 审阅确认为源侧后续修复/证据；旧文件仍在备份。独立 `isolate-public-eval-work.py` 保持未跟踪、内容不变。
- 归属复核另发现此前自动去重误删 CLI DNS 缓存测试的第二次相同查询：该重复调用是缓存断言的一部分，已经恢复。此文件最终与原版本相同，后续全工程测试包含它。

## 冲突与验证

在源 worktree 合入主分支，再把主目录快进到合并结果。AutomationTools 两个文件的冲突保留 PluginOrigin 与本轮版本/说明/滑块 schema；自动合并的 AutomationModule 保留插件注册和设置授权 UI。新增插件测试夹具的旧 RiskLevel/baseRisk 参数同步移除；不删测试断言，不改变插件权限来源。

集成版本执行通过：

```sh
./gradlew spotlessCheck test detekt \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest --continue
./scripts/check-all.sh --source
git diff --check
```

日志归档在 `build/merge-operation-20260928/`；首轮插件测试编译失败及修正后的完整门禁日志分别保留。包含新插件模块的 JVM 测试与双通道构建；AndroidTest 仅编译。

本轮设备状态 **not requested**，没有运行模型基线、真实账号或设备。旧 API36/P5 成绩保持历史身份，不声明为集成版本验收。仅本地合并，不推送，不删除或归档其他 worktree。
