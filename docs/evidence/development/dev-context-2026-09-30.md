# 开发上下文工具：历史实验与退役记录

日期：2026-09-30。来源：所有者在开发上下文成本分析后要求“帮我优化”。起始工作树为本地 main `b51687e0635dc33713ff86e52c49044049575668` 加既有未跟踪诊断文件；不覆盖并行工作，不更改 HXA/产品排期。

> **2026-09-30 退役说明：**所有者指出已有 CodeGraph，并授权清理重复建设。已核实本机 CodeGraph 1.6.0 可直接通过 CLI 使用，`DEFAULT_MAX_ACTIONS` 查询命中 Kotlin 常量并与当前源码一致。当前入口改为 [CodeGraph 导航约定](../../development/context-navigation.md)。下文保留原实验的实际测试与字节测量，不再描述当前可用工具，也不据此宣称独立工具有必要。
>
> 本轮移除 `scripts/dev-context.py`、`scripts/tests/test_dev_context.py`、`scripts/debug/2026-09-30/verify-dev-context.py` 及专属 source gate；不保留 pack/check、别名或包装器。仅删除退役辅助工具的 40 项自测，其他测试与生产门禁不变。原工具与测试当时未提交，不宣称可由起始 HEAD 恢复。
>
> 为保留未提交原型的来源，删除前三个脚本并替换旧使用说明前，已将这四份文件归档到本地忽略目录 `build/retired-dev-context-20260930-e9e56e3f.tar.gz`，SHA-256 为 `eb0505ad08d3bb489011e8b5023958030748ea281decb1acc06259cfc460ce04`。它不是新的运行入口或版本兼容层，不提交源码摘录；既有 `build/dev-context/` 样例仅作历史产物，不自动清理其他文件。CodeGraph 配置、索引与 daemon 未主动更改或重建。

## 退役后的本轮验证

删除重复实现并更新入口后，完整 `./scripts/check-all.sh --source` 返回 **exit 0**：653 份 Markdown / 215 项 HXA、35 份当前 ADR、显式状态、三语言 1,849 个资源键和秘密扫描均通过，其他现有脚本自测通过。`scripts/check-all.sh` 仅撤下已删除工具的自测，恢复到引入该工具前的内容；未把 CodeGraph 加为测试依赖。

定向核对：三个脚本均已不存在；当前 AGENTS/脚本/开发导航不再调用旧命令，残留名称只用于本历史记录及其导航链接；已跟踪改动的 `git diff --check` 通过。CodeGraph 常量查询及 `node --file ... --offset 241 --limit 12` 范围读取成功。指南由 94 行改为 49 行；移除脚本/测试共 936 行，不把代码量变化当作真实 token 收益。

本轮没有构建 APK、操作设备或调用真实模型，没有修改 CodeGraph 安装/MCP 配置、主动重建索引或停止 daemon，也没有更改生产代码、ADR、HXA 或提交/推送。首个多文件编辑因原文匹配失败而整体拒绝、无文件改动，随后重读并使用同一 SHA 守卫的整页改写与精确编辑完成；没有绕过并发校验。

## 原实验交付范围

当时实现 `scripts/dev-context.py` 的 find、outline、read、pack、check 五个命令，提供 Git-visible 文件的范围导航和显式原文任务包；加入 `scripts/tests/test_dev_context.py` 的 40 项测试并接入 source gate。全局入口改成任务定向读取。上述脚本与命令现在已退役，旧说明随本地原型归档；[context-navigation.md](../../development/context-navigation.md) 已改为 CodeGraph 使用约定。当时没有复制完整重构方案或启动向量库、服务端缓存、多 Agent 平台。

Kotlin 导航为注释/字符串屏蔽后的词法声明候选，非 LSP/类型解析；符号读取是有明确范围的邻近窗口，不保证完整函数体。包不自动装入 AGENTS 正文，调用者需要读取其所列规则。原文保留来源/完整性；历史材料默认不加载，需显式请求。必要的事务、权限及调用方资料不能因为初始包较短而省略。

所有输出受完整 JSON UTF-8 字节预算控制，超限有截断和续读位置；不将字节数当精确 token 数。包只写忽略目录 `build/dev-context/`，原子发布并拒绝覆盖；不执行包中的源码或测试命令。

## 原实验主机验证

`python3 -m unittest discover -s scripts/tests -p test_dev_context.py`：**40/40 passed**，无跳过；覆盖 Markdown 围栏/重名、Kotlin 注释/字符串/扩展函数/重载、Unicode/字节上限、未提交内容同大小变化、路径/链接/历史过滤、包格式/工作树/生成器识别、指令变化、文件删除、截断、增量基线和实际 CLI 退出码。

完整 `./scripts/check-all.sh --source` **passed，exit 0**；其中专项再次 40/40 passed。文档检查为 653 Markdown / 215 HXA，35 份当前 ADR 及显式状态检查通过，三语言 1,849 key 对齐，Secret scan 通过。源代码门禁中的其他既有脚本测试一并通过；没有执行 Gradle/Android 构建、设备或真实模型测试。

当时最终差异检查采用 `scripts/debug/2026-09-30/verify-dev-context.py`，同时覆盖已跟踪和新增文件的空白/结尾换行，并检查最终资料包新鲜度。中间一次 `git diff --no-index --check /dev/null <新增文件>` 组合命令在文档检查通过后以 exit 1 停止，未输出空白错误；该退出码不能直接作为新增文件的格式验收，已换成显式内容检查，不通过忽略退出码掩盖问题。该脚本当时不改源文件；现已随原型退役，当前工作区不再支持此复跑入口。

## 原实验仓库试跑

试跑材料是 `AutomationTools.action` 附近窗口及 Harness 方案 §15.6，验证 CLI 在真实源码/中文长文档中能够定位、打包和检查新鲜度；不是完成一次 Mobile Use 修复所需的全部上下文。

来源正文合计 **154,839 字节**，由 `AutomationTools.kt` 的 19,776 字节和 `harness-refactor-plan.md` 的 135,063 字节组成。最终同一任务/约束/选择的完整 JSON 包为 **7,386 字节**，引用该完整包的增量 JSON 为 **2,160 字节**；两包均 complete=true，新包新鲜度检查 exit 0。相较两文件全文，显式摘录包体积少约 95.23%；相较该完整包，增量体积少约 70.76%。这些不是同等信息量、实际模型用量或工程完成率的对比。

实际验证了指令变化：本任务继续精简 AGENTS 后，对旧包执行 check 返回 **exit 1 / fresh=false**，准确列出 AGENTS 旧/新 SHA。这是预期反例，不是未解决测试失败。随后生成 `verification-final-base.json` / `verification-final-delta.json` 并重新检查通过，未覆盖旧产物。

实际 ChatService 的 outline 找到 **205 个词法声明候选**，首批只返回 5 项，并提供下一页 offset；候选数不表示完整解析或真实调用图。源码与包均留在宿主，未调用模型。

## 原实验边界与复现条件

以下仅是退役前原型的使用条件，不适用于当前 CodeGraph 导航；复现旧实验需自行恢复归档版本及对应原始材料，当前命令入口已删除。

当时按照使用说明创建新文件名的包，随后运行 `check build/dev-context/<name>.json`。`--retained` 只用于完整原文仍在同一模型上下文的场景；本次增量是协议输出测试，不证明助手已自动感知或管理所有会话记忆。新会话/压缩后必须重新生成完整资料。HEAD 未变不阻止未提交文件或规则变化使包过期；不保存另一份长期全仓摘要。

“全文与片段包的字节差”仅说明选择性回传的体积，不是实测 token/计费/任务成功率提升。共享规则、调用方、测试、额外往返、缓存和实际模型成本未包含在该对比中。多文件读取不是原子仓库快照，进入编辑前仍按当前 SHA 再校验；工具不是秘密扫描器或对抗文件系统的执行沙箱。

本任务不修改 Helix 的运行模型、数据库、权限、恢复与设备逻辑，不更改 WebCodex 服务端 schema/回包或安装 Kotlin LSP；不提交/推送 Git、不创建持久开发 Goal。
