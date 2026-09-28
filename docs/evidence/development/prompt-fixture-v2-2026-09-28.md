# Prompt 与 skill-003 夹具 v2

所有者要求优先优化提示词与夹具，再继续 P7。旧 `99b7bee7` 的 15/15 及之前全部失败记录保留。

本次 base 明确在既有授权内修复可恢复问题、保留已完成工作，并保留必须的 Goal lifecycle report；files 的直接创建指导限定为允许写入的模式/已暴露工具/用户明确请求，补充 hash 冲突重新读取并协调而非撤掉前置条件；Plan 禁止用户文件和外部副作用，同时允许当前模式实际暴露的计划/任务元数据操作。README 更正装配入口，说明该目录并非完整 prompt。权限、Dispatcher、Goal owner 和工具 schema 未变。

`skill-003` v1 前文已告知导入拒绝，最后却再次要求导入。v2 将最终请求改为仅报告已观察到的拒绝，不重新导入或重建归档。ZIP 真实拒绝、Skill 集合不变、Turn 正常结束、回答含 traversal、所有后续调用均 READ_ONLY 的 oracle 保持不变。原数据集不改，额外保存 `datasetPromptSha256`，`promptSha256` 对实际发送请求计算，记录 `fixtureVersion=report-refused-import-v2`。这属于夹具修正，不可与旧分数差混称 prompt-only A/B。

提示装配、Plan/todo 主机测试通过；developer app/test APK、spotlessCheck、detekt 通过。新增 fixture 信息触发原 helper 长度/格式 gate，已将请求构造和 identity 提取为纯 helper 后通过，不禁用规则。日志 `build/prompt-fixture-v2-host.log`、`build/prompt-fixture-v2-gate.log`。

执行前约定：同 SGLang 服务与 API36 developer 设备配置完整执行 15 个 case 一次，保留失败，不循环至全绿。历史输出截断和模型偶发多余调用仍开放。
