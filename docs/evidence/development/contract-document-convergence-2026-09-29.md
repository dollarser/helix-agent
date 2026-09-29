# 现有契约与候选状态收敛

日期：2026-09-29。所有者要求依据外部审查收敛文档和代码，并明确“先收敛现有实现与文档，R1 保留为下一实施任务”。本次不接受或启用 J1/J2、完整 Project Memory、生产子 Agent、文档/视频解析或 Mobile Use 截图。

## 来源与所有权

基于 main `4a1443df` 加捕获时主目录 108 个未提交路径的稳定快照，在独立 `codex/contract-convergence` 工作树验证。继承内容包含会话输入和 HXA-225 视觉接线；不归入本轮交付，也不覆盖主目录。之前 `codex/provider-settings` 的手机 UI 修复仍独立保留，没有借本次任务自动合并。继承 patch、路径哈希及文件副本仅保存在忽略的 `build/contract-convergence/`，不提交用户数据或本机配置。

本轮代码仅修改 `ToolDescriptor.kt`、`ToolOrigin.kt` 及 `ContractHashGateTest.kt`；Dispatcher、权限解析、effect、Memory resolver、工具注册 owner 均未因本轮新增改动。

## 文档事实

- 新增[候选需求与待裁决索引](../../development/candidate-decisions.md)，由 status/roadmap/文档入口导航；只记状态、来源、实现边界与进入条件，不复制第二份实施计划。
- 35 份 ADR 中工具规范仍 proposed；其 contractHash 实现已存在不是“从零待开发”。本次没有将 proposed 自动改 accepted；R1 仍是已授权开放的 HXA-231。
- Global Memory / Project seam、只读委托 Spike、完整生产接线分开；OAuth/订阅/发行留在原开放任务。十项 FUT 留在产品需求，不制造 HXA。
- 修正 Workspace 尚未接受、本地模型尚未立项等过时的当前态叙述；历史研究和完成记录不改写成当前验收结果。
- 审查材料中无法解析的历史引用只登记为待核实线索，不将竞品能力或模糊产品建议变成 Helix 已接受承诺。

## 可复现代码缺陷与修复

旧 `origin.canonicalOf()` 以冒号拼接字段。MCP 的 `(server, x:y)` 和 `(server:x, y)`，A2A 的 `(agent, x:y)` 和 `(agent:x, y)` 可产生相同来源串；在其余 descriptor 字段相同的受控构造下，旧 contractHash 与审批绑定哈希也相同。这证明规范编码缺陷，不声称已构造远端生产越权利用。

先加入两个反例，`ContractHashGateTest` 9 项中恰有新增 2 项失败，日志 `build/contract-convergence/contract-before.log`。修复采用带 `helix-tool-contract-v2` 标记的固定顺序 JSON array；来源字段独立编码为末项 array，显示 hints 继续排除，schemaHash 不变。修复后原有字段变更、集合顺序、hints 和新增来源反例一起验证。

新编码让旧精确批准失效，需要重新确认。没有更换现有来源启停键，以免丢失已禁用设置；来源键的完整结构化身份迁移、双注册表消除和请求 BindingRef 仍属 R1，不用本修复冒充整个绑定重构完成。

## 验证

- `:tools:framework:test` **206/206**、`ContractHashGateTest` **9/9** 通过；首次新增两项反例失败留证，未删弱断言。
- `./gradlew -PincludeSpikes=true test` 通过（502 tasks），日志 `build/contract-convergence/all-jvm-tests.log`。
- 双渠道 app unit/debug APK/AndroidTest APK 构建通过（642 tasks），日志 `build/contract-convergence/host-build-tests.log`。
- 双渠道 debug lint、spotlessCheck、detekt 通过（698 tasks），日志 `build/contract-convergence/host-analysis.log`。task 数是 Gradle 工作项，不是用例数。
- 新工作树初次 developer 构建因缺少忽略的 Runtime 资产失败；复制主目录已存在的 PRoot/RootFS 构建输入，由原 Gradle SHA 校验验证，不下载可变依赖、不修改锁文件。
- 初次并行构建/Lint 命中了 CMake 临时解压目录消失的 `FileNotFoundException`，日志 `host-gates-r2.log`；先完成构建再运行 Lint 后通过。未跳过分析、未增加排除项或降低规则。
- `check-all.sh --source` 通过：626 Markdown、214 HXA 引用/记录、35 ADR、三语言 1803 键一致及秘密扫描；`git diff --check` 通过。最终证据更新后的 source 复核日志 `build/contract-convergence/source-final.log`。
- `owned-paths.json` 和 `owned.patch` 对比继承快照，明确分离本轮 18 个路径（含 13 份文档、2 个辅助脚本、2 个生产源码及 1 个测试）；继承的其他路径保持捕获时内容。
- 本轮设备 `not requested`，没有使用模拟器或手机；APK 编译不视为设备通过。真实服务/账号没有请求或使用。

实施验证完成时未提交、未合并、未推送。所有者随后要求合并到 main：只提取本轮改动，对三份重叠文档仅提取新增段落，不把继承的会话输入/视觉接线提交进来。主目录其余未提交内容按合并前哈希保留；主分支整合门禁另行运行，日志位于 `build/contract-convergence-merge/`。本操作不包含推送或设备验证。下一实施任务仍为 HXA-231 / R1，其他候选不因此启动。
