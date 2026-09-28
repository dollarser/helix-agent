# Prompt 与 skill-003 夹具 v2

所有者要求优先优化提示词与夹具，再继续 P7。旧 `99b7bee7` 的 15/15 及之前全部失败记录保留。

本次 base 明确在既有授权内修复可恢复问题、保留已完成工作，并保留必须的 Goal lifecycle report；files 的直接创建指导限定为允许写入的模式/已暴露工具/用户明确请求，补充 hash 冲突重新读取并协调而非撤掉前置条件；Plan 禁止用户文件和外部副作用，同时允许当前模式实际暴露的计划/任务元数据操作。README 更正装配入口，说明该目录并非完整 prompt。权限、Dispatcher、Goal owner 和工具 schema 未变。

`skill-003` v1 前文已告知导入拒绝，最后却再次要求导入。v2 将最终请求改为仅报告已观察到的拒绝，不重新导入或重建归档。ZIP 真实拒绝、Skill 集合不变、Turn 正常结束、回答含 traversal、所有后续调用均 READ_ONLY 的 oracle 保持不变。原数据集不改，额外保存 `datasetPromptSha256`，`promptSha256` 对实际发送请求计算，记录 `fixtureVersion=report-refused-import-v2`。这属于夹具修正，不可与旧分数差混称 prompt-only A/B。

提示装配、Plan/todo 主机测试通过；developer app/test APK、spotlessCheck、detekt 通过。新增 fixture 信息触发原 helper 长度/格式 gate，已将请求构造和 identity 提取为纯 helper 后通过，不禁用规则。日志 `build/prompt-fixture-v2-host.log`、`build/prompt-fixture-v2-gate.log`。

执行前约定：同 SGLang 服务与 API36 developer 设备配置完整执行 15 个 case 一次，保留失败，不循环至全绿。历史输出截断和模型偶发多余调用仍开放。

## 本轮结果

clean `fce488ce`、API36 developer arm64、4 GiB/4 cores、SGLang Qwen3.8-27B / OpenAI Chat：Provider smoke 1/1，完整 **15 PASS / 0 FAIL / 0 fixture ERROR**，runner 正常退出并关闭自己的模拟器。skill-003 v2 直接报告拒绝，0 工具调用；完整摘要及该项实际请求身份见 [summary](prompt-fixture-v2-2026-09-28/summary.json)、[skill-003](prompt-fixture-v2-2026-09-28/skill-003.json)。Goal 三项通过，未改变固定预算。

source manifest `a2f4213183f19ebe53761bd472bce1ab3081e139a5b4cc9962e638c937ef43c2`，app APK `6dddb60758e7810ec702123697791ec55d38e87ab31c265e86d728f081b0115d`，test APK `7f4e1522d217f57ba19a6e50dcd132e2dd743b955e727919af4ab7876122010a`。原始结果 `build/prompt-fixture-v2-baseline-20260928/`。12 个同口径 elapsed 的 mean 5,771.8 ms、median 4,264 ms、max/p95 15,586 ms；不是纯推理速度，跨夹具变化不构成提示词提速证明。

本次通过不关闭历史 OUTPUT_TOKEN_LIMIT 或额外工具调用的稳定性问题。随后 P7 UI 变更另有 APK 与设备验证，不把本基线身份自动扩展到后续制品。
