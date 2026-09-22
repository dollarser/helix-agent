# 对话交互与工作台分支收敛

日期：2026-09-22。所有者授权整合其他分支的必要成果，并清理无独立内容的分支及worktree。本记录只写本轮实际结果；候选已完成联合验证，随后进行本地main整合与证据归档。

## 整合范围

- `codex/hxa-216-input-delivery`：214/215联合修复、216 Queue/Steer及恢复、129实施准备和217成本预评估；基线 `7a42bcd4`。
- `codex/ui-interaction-refactor`：218会话工作台与219产物预览；基线 `0fb77a2d`。合并提交 `3838d478` 保留持久草稿/Queue状态和产物预览两个插槽，合并三语言资源及fork状态等待，不用一侧覆盖另一侧。
- `codex/conversation-convergence`：`240e6cce`的代码修复已等价cherry-pick为`a99416b5`，`6d0c8b43`已整合为`7742a8f8`。核对恢复脚本字节完全一致，差异仅216后续决策/任务进度；`1e255708`只补齐已整合历史的祖先关系，不恢复旧状态文案。

main原有文档整理单独保全：最初29个路径的内容/删除状态、HEAD、原始patch和SHA放在忽略目录 `build/branch-convergence-20260922/main-wip/`。其中本轮生成的归档脚本随后移入集成分支提交；其余28路径逐字未变，第二份快照为 `main-wip-r2/`。原有stash不清理，未提交内容不被当作无用文件删除。

## 验证与修正

联合runner为 `scripts/debug/2026-09-22/accept-branch-convergence.py`：复用既有独占模拟器、动态方法发现和报告核验，合并214/215/216及218/219覆盖并去重，每象限212项功能，另跑214 composer、215 revision及216四类普通主进程恢复，存储仍独立验证。新增wrapper不修改生产执行逻辑。

合并后双flavor应用/设备测试APK、Spotless及detekt通过。`build/branch-convergence-20260922/host-all-r2.log`为完整主机门禁exit0，含Debug/Release Lint、全模块测试、构建和APK边界。首轮源码门禁发现UI研究仍链接已关闭的214任务，改为完成记录后重跑通过。

首轮API29 consumer功能212项中211通过，唯一失败为旧附件测试期待“忙时拒绝”；216已改为持久Queue。失败日志保留，测试按新契约补验持久附件归属、重复确认复用输入身份、当前Turn完成后恰好一次交付，不删除或跳过断言；本轮不因此修改生产逻辑。

最终 `matrix-r2` exit0。独立汇总器重读原始记录及APK校验值，`final-summary.json` 为PASS：30/30批、896项主测试加24项恢复验证，共920项，无失败或跳过。其中功能848项（212×4）、恢复seed/verify48项、存储24项（12×2）；24组普通进程PID变化，216的16组真实SIGKILL，30个自建模拟器均有退出记录。所有同flavor批次使用一致APK；此前单分支通过数不计入本轮。

实际命令（均在集成工作树，构建与设备命令由 `with-host-slot.py` 串行调度）：

```sh
./scripts/check-all.sh --all
./gradlew spotlessApply spotlessCheck detekt :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest
python3 scripts/debug/2026-09-22/accept-branch-convergence.py --scope 216 --first-port 5620 --output build/branch-convergence-20260922/matrix-r2
python3 scripts/debug/2026-09-22/summarize-branch-convergence.py build/branch-convergence-20260922/matrix-r2
./scripts/check-all.sh --source
```

对应通过日志为 `host-all-r2.log`、`compile-r3.log`、`matrix-r2.log`、`final-summary.json`、`source-integration.log`，均位于忽略目录 `build/branch-convergence-20260922/`。这不是物理设备或完整产品206重新验收，也未使用真实外部账号。

## 保留的独立分支

`BioHelix`包含独立产品材料及未跟踪文件，不属于本次Helix功能整合；保留分支及worktree。`v0.0.1`承载历史决策，不作为废弃分支删除。

`codex/browser-redesign`的`80c62295`、`39985854`暂不合并，保留源码与worktree。只读审查发现：

| 优先级 | 阻断或待修复问题 | 最小处理与验证 |
| --- | --- | --- |
| P1 | 新建标签从tryNewTab改为抛异常的newTab，按钮不限制容量 | 恢复非抛异常准入，覆盖满额普通/无痕新建 |
| P1 | 所谓无痕仅跳过history.json，仍共享默认Cookie/DOM storage/cache | 明确并验证隐私契约，或准确命名为不记录历史，不能声称站点数据隔离 |
| P2 | BrowserStorage吞掉IOException，UI先显示成功；rename失败后覆盖复制不原子 | 明确失败结果并保留旧文件，覆盖空间不足和提交失败 |
| P2 | 页面完成及书签/脚本动作在主线程同步写整个文件，字段无字节边界 | 串行IO和有界内容，覆盖大数据与写入失败 |
| P2 | 惰性WebView创建前设置无图模式不生效，桌面模式未在新宿主恢复 | 创建时应用逻辑配置，验证新标签和重建 |

分支中未新增Agent工具或特权JS bridge；脚本IIFE不是隔离执行世界，不能据此宣称Tampermonkey兼容或隔离。现有131个JVM测试报告未绑定最终提交，也没有新增设备验收记录，不能作为合并门禁通过证明。这些问题属于保留的候选分支，不描述成main已上线缺陷。

## 清理与证据保全

旧归档3391个文件逐一复核，源/目标缺失及SHA错配均为0。新增convergence归档1320文件、4,635,836,692字节，UI归档647文件、2,095,427,803字节；均验证文件集合与SHA。位置为main忽略目录 `build/worktree-evidence/` 和 `build/branch-convergence-20260922/`。connector-install-design无build，按祖先关系和干净状态清理，不伪造归档证据。

已提前清理main祖先worktree：acceptance-199-206、connector-install-design、compaction、hxa-126、session-fork、small-model-preparation；对应六分支及无worktree的marketplace-catalog已删除。机器配置另存忽略目录，生成缓存可重建。其余本轮分支在main整合、当前证据归档完成后再删除。

物理设备、真实账号及发行范围仍沿各任务保留；本轮未推送、未执行远端CI或发布。
