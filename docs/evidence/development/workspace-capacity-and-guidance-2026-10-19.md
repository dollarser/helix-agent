# 工作区目标、容量与开发指导精简

实际验收日期为 2026-10-18 UTC；指定设备时区 Asia/Shanghai（UTC+8），本地日期 2026-10-19，文件沿用本地任务日期。

## 范围与实现

Owner 在终端布局验收后追加本轮范围：改写工作区重构目标、交付重构前小优化、报告并调整固定并发，以及精简开发约束。终端布局已由 `ef8fd10c` 交付，原结果见[终端记录](../../bug-fixes/2026-10-19-terminal-package-replacement.md#regression-verification)，不替代本轮候选验收。

- **目标契约**：默认操作选定真实工作区，独立终端无需聊天；URI-native 消费者直接使用 Provider，具体消费者需要 POSIX 才物化。worktree/snapshot 显式选择，远程不固有复制。Harness §23.3.1/23.7.3、HXA-286/287/288 和原 WORKSPACE-004/RUNTIME-002 同步；联合验收包含原位与显式快照。当前 `code.linux.run` 仍复制输入到独立 Job，完整契约落地前不切默认模式，不增加全局文件/结果锁或任意 Shell CAS 承诺。
- **小优化**：目录列表显示本地/系统文档来源；Linux 同步结果及后台 accepted 回执报告真实 snapshot 模式、环境、guest cwd、既有输入命名映射、输出目录/原批准导入目标及不自动回写。映射有字节上限并明确截断，不新建 manifest 或变更输入命名。文件 edit/write 复用原 hash 失败，给出 expected/observed 或 missing/unknown，要求重新读取，不新增读写或绕过。现有会话 Git 入口与 staged/unstaged diff 继续复用，没有复制 Git 页面或实现逐调用 diff/变更账本。
- **容量**：源码确认固定两个手动绑定与 Scheduler 默认两个调用是不同限制；PRoot Job 原本最多四个，没有另设每会话两个 Job。方案先向 Owner 报告再实现：应用工具健康前台按 CPU 最多四个普通调用，后台/低内存/热压降一；手动终端按 CPU 最多四个，Job 最多四个，共享原 4+2 合计六槽。预留计入容量，取消不提前释放，资源压力只影响新准入。旧文件名和所有高位绑定保留；新建仅按原退出证明、ACK、ownership/CAS 退休已停记录，UNKNOWN 无证明继续占位。PTY 退出证明移至输出、reap、清理完成后公布，原单次关闭边界保持。

容量取舍参考 [VS Code 终端管理](https://code.visualstudio.com/docs/terminal/basics)、[TermuxService 原始源码](https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/TermuxService.java) 的交互终端/后台任务分离，以及 Android [MemoryInfo](https://developer.android.com/reference/android/app/ActivityManager.MemoryInfo)/[热状态](https://developer.android.com/reference/android/os/PowerManager#getCurrentThermalStatus())。采用多独立实例和资源事实，不据文档缺少数字推导竞品无限并发；四核、约 2.4 GiB 模拟器不是手机容量或长稳实测。未复制参考源码。

## 指导精简与只读复核

删除/收窄固定文件阅读顺序、每次接续盘点/查史、机械切片步骤、重复交接字段及 status 页首历史流水；status 改为实施事实、当前工作、开放条件和未启动设计依赖，修复旧锚点入链。指南、两个开发 Skill 和直接引用参考沿用这一边界。当前用户请求和本次压缩摘要承接未完成工作，新指令只调整变化范围，不由旧历史或队列重选任务。

保留真实授权、用户数据、原执行身份/UNKNOWN、进程隔离、host-only CI、owner-explicit 设备/账号/发布和真实性要求；反复有效的主机/设备/结果模板、候选构建绑定、契约五类问题、发布推送核对仍为按需参考。具体测试事故经验缩为相关不变量并保留 HXA-281/282 原证据入口，没有机械按字数删除流程。

参考 [Claude Code 最佳实践](https://code.claude.com/docs/en/best-practices) 的精简项目指令、按需专业内容与小任务无需完整计划；采用减少冗余，未套用其固定执行步骤。文档噪声与歧义是可证明的风险，**未据此声称定位或修复了平台压缩因果**。

独立只读审查报告了旧 status 入链/选活措辞、navigation 接续固定盘点、旧仅只读并发规则、调试清理等待及发布参考遗漏，已逐项修正。审查未发现项目权限/数据/设备/发布边界被误删；保留完整 completion index 生成校验、任务库存与每份 HXA 决策记录检查，替换 check-docs 对旧 status 标题和指南固定字面的过时断言。

三场景为文档语义推理，非平台故障实验：A 历史写任务后最新只读要求限制当前操作，不恢复写入；B 明确继续同一任务则保留有效许可并续做；C 无新消息、摘要 A 剩余测试而 status 有旧 B，继续 A 测试，不选 B。没有把这三例再写成根指导的固定恢复流程。

## 当前验证与边界

本轮主机与日常 API36 ARM64 已获授权，保留当前数据/设置，本地提交；未授权推送、Release 或真实账号/模型调用。版本保持 0.0.6/code 1，声明版本保持 1。

- 文档/ADR 首轮失败暴露旧标题/字面与重复完成库存断言，已改为新状态结构和单一生成索引；后续检查通过 931 Markdown、290 HXA、37 ADR，独立审查也复跑通过。后续新增本记录仍需重跑。
- 初始容量 JVM 通过；新增执行信息/文件冲突及设备测试后，静态检查先后发现长行、复杂条件及测试类方法数，均保留失败日志并修正。两次 smoke 构建命令缺少实际启用属性导致任务选择失败，不作为成功构建。
- `./scripts/check-all.sh --all` 当前生产输入完整通过，Python 276 项、文档 932/任务 290、ADR 37、国际化、Secret、JVM/静态/lint、双渠道 Debug/Release/R8 和制品边界通过。随后为新增来源 UI 测试补做 spotless/detekt、双渠道 AndroidTest 与 release-smoke 编译，并逐任务 `--rerun` 强制重跑：Standard 1316、Advanced 1451、files 131、PRoot core 182、PRoot app 25 通过；两应用各 4 项外部输入条件跳过，不算通过。源实现已本地提交 `45e2f0d7`，指导/规划为 `40288cf8`，未推送。
- 新设备安装预检发现两个非本轮创建的 RUNNING 手动终端，没有退出证明，先暂停安装。Owner 随后明确允许关闭，两次旧 R8 界面单步关闭通过，原记录均 PROCESS_TREE_EXIT/ACK，数据库与偏好保持；之后建立新基线。Debug 候选安装后原保护断言被内部进程诊断心跳更新触发；仅名称/哈希核对确认全部 Room 表与用户偏好不变，修正为单独对待内部心跳，没有覆盖用户设置。最终 13 项为生命周期 10、实际平台前后台门禁 1、合成来源/搜索 UI 2，零失败/跳过，测试 APK 已移除；未将这些 Debug 结果算成 R8。
- 从 `45e2f0d7` 成功构建的 ARM64 双渠道 R8 冻结当前 Advanced 候选，签名匹配、版本 0.0.6/code 1、仅 ARM64 native、原数据保留，实际安装字节和最终源码输入核对通过。R8 共 24 次方法执行、零失败/跳过：单终端 4，四终端实际横竖屏 × 字体 1.0/2.0 共 16，所选终端单步关闭 4。各终端一次启动、身份/PID、环境和 `cd` 后目录通过新鲜唯一回写核验；关闭 E 的子进程消失而 A/B/C 仍活，后续关闭 B 后 A 仍能写新检查点，其余关闭逐一确认子进程退出。
- R8 热状态 MODERATE 覆盖验证了新建拒绝、原实例保留与无绑定泄漏，并在 finally 恢复覆盖。第四终端经真实 shell `exit` 自然退出后保持未 ACK 记录，页面仍可新建；新建按原退出证明/ACK/CAS 退休旧绑定，新的 E 与 A/B/C 同时存活。没有以清除 UNKNOWN 或改写日志冒充释放。
- 最终全部 Advanced Room 表、用户偏好和基线原终端记录保持；内部进程心跳正常更新。显示/旋转、字体、IME、语言和热覆盖恢复，所有本轮子进程退出、五个核实归属的合成目录及两个自有测试 APK 清理，主 R8 留在日常实例且无活动终端。旧两个用户终端的关闭依单独授权，发生于新基线前，未计入当前候选的 24 项。
- 真实模型/账号调用为零；未验实际低内存、物理手机、其他 API/OEM、长稳或一般重负载容量。Linux 执行信息有 JVM 映射/大小/Schema 相关证据，本轮未声称其已被真实模型消费；URI-native 原位/远程/通用 diff 后端仍是 286～288 的未来实现。指定设备验收为 `passed`，上述未执行范围不算通过。

最终文档更新后 `check-all.sh --source`、`git diff --check` 通过；最终设备测试源码再经 spotless/detekt、双渠道 Debug lint（含 AndroidTest）和 release-smoke lint 通过。source 输出中的故障/恢复日志是主机回归的合成失败输入，不是本轮设备失败；实际退出码和独立设备日志分别核对。两倍字体的实际横竖屏截图已目视复核，单行工具栏和终端视口无新增挤压。

原始日志、源码/候选指纹与有界设备记录保存在忽略的 `build/workspace-capacity-2026-10-19/`；最终结果只覆盖实际执行范围，不外推真实手机、其他 OEM、实际低内存、长稳或模型任务。
