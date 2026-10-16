# 系统提示一致性与文件工具收尾

Date: 2026-10-16
Scope: [HXA-296](../../development/tasks/HXA-296.md)，Owner 要求当前完成可独立修复的问题

## 起点与取舍

从 `main` / `6bbff42d` 的未提交工作继续；会话工具首版和前次文件提示审查完整保留，起始 diff 保存在忽略的本轮构建目录。未切换分支、提交、推送或发布；没有启动设备或调用真实模型。

本轮检查 13 个随包行为模板、提示目录说明、真实装配及原生插件指导。Mobile Use、Browser、Documents、Chat Reply 的领域流程继续由各自插件提供，未搬入宿主通用提示，也未更改插件的授权/截图/输入行为。

| 一手参考 | 采用的原则 | Helix 取舍 |
| --- | --- | --- |
| [Codex Prompting Guide](https://developers.openai.com/cookbook/examples/gpt-5/codex_prompting_guide) | 完成授权工作、优先适用工具、独立操作可并行、以实际验证收尾 | 保留本机 Dispatcher 与物理并发边界，依赖调用分轮；不照搬桌面 Shell、固定输出截断阈值或特定模型的进度消息要求 |
| [Claude Code Best Practices](https://code.claude.com/docs/en/best-practices#write-an-effective-claude-md) | 指导具体、只保留必要规则、提供可检查结果、按需加载领域知识 | 基础提示负责共享规则，模式/恢复补充约束，插件拥有领域指导；不靠提示替代权限和结果事实 |
| [OpenHands Context Condenser](https://docs.openhands.dev/sdk/guides/context-condenser) | 保留近期信息，压缩旧历史并保留目标与关键信息 | 继续使用现有上下文编译器；加强来源、未决状态、用户纠正和历史数据边界，不采用其事件计数阈值或另建摘要引擎 |

以上为文档/源码设计参考，没有复制第三方代码或提示原文，没有竞品运行对照。提示字数不是收益判据，不声称已经降低 token、提高完成率或通过模型 A/B。

## 已实施修复

- **提示职责**：统一 `base/plan/goal-*` 的当前任务、按需发现、依赖顺序、收尾与模式界限；Goal 指导使用现行主入口，不再教授兼容别名；工具耗尽时不要求调用已经不可用的 Goal 工具。
- **澄清与授权**：基础提示不再将需要授权的情况导向 `ask_user`。反问负责缺失信息，实际权限仍由宿主审批；保留待回答时继续独立工作，禁止把默认选项/等待时间当答案。
- **压缩**：`ContextSummaryFormat` 的随包压缩指令从 USER 改为 SYSTEM，历史分块仍为 USER 数据。返回摘要仍以历史而非新授权回填；保留用户约束/纠正与来源，区分计划、执行、结果和可复用秘密。
- **文件身份**：`read(includeSha256=true)` 在同一条读取流中计算完整文件 SHA-256 并返回窗口。显式扫描上限 32 MiB、内存仍受窗口限制；普通预览不做全文哈希。不把 hash 当作文件锁、多页一致快照或跨进程原子性保证。
- **编辑正确性**：在解码/替换前校验实际读取字节的哈希，写前继续沿原 precondition；防止先读到另一版本、随后文件恢复旧版本时把错误文本写回。编辑上限对齐底层既有 32 MiB 全文读取上限。发布前重新检查取消/期限。
- **结果真实性**：发布前读取失败明确没有尝试编辑；发布后文件/产物登记或 Document Provider 异常保留副作用不确定性，不再一概声称“edit was not performed”。没有自动重放。
- **窗口边界**：UTF-8 字符宽于请求窗口时返回有进度的原始字节；文件末尾不完整编码返回不透明字节，避免零进度或 EOF 丢字节。使用严格解码处理过长编码、代理码点和超过 Unicode 上限的序列，返回原字节而非替换字符。

## 改动定位

- 系统模板：`app/src/main/resources/prompts/` 的 13 个行为模板与 `README.md`。
- 压缩装配与回归：`core/agent/.../ContextSummaryFormat.kt`、`ContextCompilerTest.kt`。
- 文件读取：`core/workspace/.../ReadWindow.kt`、`FileReadHash.kt`、`WorkspaceArtifactStore.kt`、`DocumentWorkspaceOperations.kt`；`tools/files/.../ReadTool.kt`。
- 编辑与说明：`tools/files/.../EditTool.kt`、`WriteTool.kt`；回归见 `FileReadIdentityTest.kt`、`ReadWindowProgressTest.kt`。
- 当前状态与后续承接：HXA-296、status/roadmap、Agent/Workspace ADR；HXA-286 的旧哈希缺口改为接续已实施行为。

## 验证

最终 `./scripts/check-all.sh --all` 退出 0。日志保存在忽略目录 `build/prompt-alignment-2026-10-16/`：`targeted-4.log` 为最终定向 JVM/Detekt 检查，`host-all-final.log` 为冻结生产源码后的完整门禁，`test-counts.txt` 为最终 JUnit XML 计数。局部格式化通过本地 init script 严格列举本轮 Kotlin 路径，未批量格式化既有改动。

| 检查 | 实际结果 |
| --- | --- |
| `:tools:files:test` | 130/130，包含新增 `FileReadIdentityTest` 9/9 |
| `:core:workspace:test` | 110/110，包含新增 `ReadWindowProgressTest` 4/4 |
| `:core:agent:test` | 291/291，`ContextCompilerTest` 14/14，其中新增压缩角色边界 1 项 |
| App 提示/请求相关 6 个套件 | Consumer/Developer Debug 各 25/25：提示选择、环境、反问、展示元数据、压缩请求及连续性 |
| 完整 App JVM | Consumer：1,247 项中 1,243 passed、4 skipped；Developer：1,368 项中 1,364 passed、4 skipped；均 0 failure/error |
| 主机 Python 回归 | 254/254；设备 runner 的失败/恢复日志来自 mock 用例，不是本轮操作设备 |
| 文档、ADR、国际化、秘密检查、Spotless、Detekt、双渠道 Debug/Release Lint | 通过；最终文档回填后再次执行文档/ADR 门禁与 `git diff --check` |
| APK 与制品 | 双渠道 Debug/Release（R8）、Runtime APK、release-smoke AndroidTest APK 编译通过；依赖锁、渠道、进程/UID 与内置 Runtime 边界检查通过。测试 APK 未安装或运行 |

上述 App 每渠道 4 项 skip 均为现有外部 Connector 验收目录/用户样本归档的可选输入未提供；未新增跳过条件、删除用例或降低断言。新增 14 项回归均实际执行，没有跳过。Document 后端接口通过 JVM 内存夹具验证，未操作真实文档提供方。

保留的失败与修复：第二次定向检查发现 `runEdit` 长度及 `ReadWindow` 条件/返回数量超限，拆出辅助方法后通过，没有更改静态阈值。第一次完整门禁中，双渠道 `CompactionContinuityTest` 因提示改写后缺少既有精确短语而失败；恢复用户事实归属与权限边界的明确表述，原测试未修改，定向及完整复验均通过。追加严格 UTF-8 反例后冻结生产源码，完整门禁重新运行；原日志均保留。

设备、真实 Provider、长稳：not requested。主机通过不能证明提示遵循率或实际 Document Provider/OEM 行为，也不证明读取期间的外部文件系统具有快照语义。现行架构重组顺序与暂不推送发布决定不变。
