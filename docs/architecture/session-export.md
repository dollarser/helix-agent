# 会话 JSONL 格式 v1

格式名 `helix.session-export`，主版本 `1`。这是 [ADR-AGENT-005](../adr/agent/005-session-jsonl-export.md) 的外部投影格式；实现与验收证据见 [HXA-211](../completion-records/HXA-211.md)。本文定义格式，具体交付边界以完成记录为准。

## 文件与信封

UTF-8，无 BOM；每个物理行是一个 JSON object，以 LF 结束，包括最后一行。正文换行使用 JSON 转义。每行包含：

| 字段 | 类型与语义 |
| --- | --- |
| `format` | 固定字符串 `helix.session-export` |
| `formatVersion` | 整数 `1`；消费者拒绝不支持的主版本 |
| `exportId` | 本次快照 UUID，所有行一致；不同导出不能按源 ID 无条件覆盖 |
| `sequence` | 从 0 开始的连续整数，仅表示导出顺序 |
| `type` | 下表列出的类型 |
| `recordId` | 类型前缀加原有 ID；同一文件内唯一，重复导出保持稳定 |
| `sessionId` | 选定会话的原有 ID，所有行一致 |
| `data` | 该记录的公开字段对象 |

头必须在首行；尾必须在末行。未知可选字段可忽略，不能忽略不支持的主版本、未知记录类型或不完整文件。兼容新增字段不改变已有含义；关联或排序语义的破坏性变更必须升级主版本。

## 类型与排序

按以下表格顺序输出组，空组不输出。组内排序升序，字符串 ID 使用 SQLite 默认二进制排序。原消息 `data.sequence` 与信封 `sequence` 无关；工具组排序不表示调用、开始或完成顺序。

| 类型 / ID 前缀 | 组内顺序 | 字段与缺失语义 |
| --- | --- | --- |
| `header` | 唯一 | `snapshotId`、`capturedAt`、`appVersion`、快照/引用/排序/内容策略及固定上限 |
| `session` | 唯一 | `id,title,providerId,modelId,createdAt,archivedAt`；Provider/模型标识是身份，不导出配置对象 |
| `turn` | `startedAt,id` | `id,sessionId,state,stepCount,startedAt,endedAt,errorCode`；终止原因仅来自持久状态/错误码，不猜测活动 Turn 的未来结局 |
| `message` | 原 `sequence,id` | `id,sessionId,turnId,role,kind,contentId,sequence`；保留压缩前原历史和 checkpoint 消息 |
| `model_call` | `id` | `id,turnId,state,requestId,promptFingerprint,promptSections,provider,usage`；Provider 只含已保存的 `displayName/model`，无 endpoint；未保存时间/finishReason 明确 unknown |
| `tool_call` | `id` | `id,turnId,callId,name,version,argsJson,argsHash,state`；当前存储没有模型 FK/调用顺序，`modelCallId=null`，关联与顺序标记 `not_persisted` |
| `tool_result` | `id` | `id,toolCallId,status,summary,contentId,verified`；不把未结算/UNKNOWN 改成成功 |
| `execution` | `id` | `id,toolCallId,runtime,limitsJson,exitCode,signal` |
| `approval` | `id` | `id,toolCallId,decision,decidedAt,consumedAt,expiresAt`；不导出 bindingHash 或授权证明 |
| `artifact` | `id` | `id,sessionId,mediaType,size,sha256,turnId`；仅引用，未读取校验，不导出路径 |
| `attachment` | `messageId,ordinal,rowId` | `rowId,messageId,artifactId,ordinal,purpose,boundSha256` |
| `goal_run` | `startedAt,id` | 本会话 Turn 绑定的 run 身份、wakeReason/outcome、时间、已有累计用量 |
| `goal_binding` | `turnId` | `turnId,runId`；确定性派生 ID `goal_binding:<turnId>`，标记 `derived=true` |
| `usage` / `usage:goal` | `id` | 本会话关联 Goal 的状态、预算及已有累计值，不包含其他会话消息 |
| `usage` / `usage:audit` | `timestamp,id` | 仅本会话模型调用关联的 `budget.request/admitted/result/context.compaction` 诊断；`correlationId` 指向模型调用 |
| `compaction` | 对应 checkpoint 消息的 `sequence,id` | `compaction:<messageId>`；`derived=true,derivationVersion=1`；原消息、正文、coveredThrough、保留消息 IDs、sourceCallId、摘要、已保存估算输入量 |
| `content` | SHA-256 字符串 | `content:<sha256>`；同一 ContentStore 身份只输出一次 |
| `complete` | 唯一 | 完成标志、含自身的 `recordCount`、全部类型的 `counts`、内容统计 |

`header:<sessionId>`、`session:<sessionId>`、`complete:<sessionId>` 是固定身份。其余表记录使用 `<type>:<源 id>`；attachment 使用原 `rowId`。Goal 用量与审计用量通过额外前缀隔开。

模型 usage 的未知 input/output/total 为 null，不补零；已有 usage 无法区分报告与估算时明确说明。Turn 未存用量也是 unknown。ModelCall、GoalRun、Goal 与审计诊断重叠，不得跨层相加作为账单。

## 引用

关系记录的 `data.references` 是数组，每项包含 `field`、`targetRecordId` 和 `status`。`field` 对应原字段；保留消息数组使用 `preservedMessageIds[0]` 等路径。允许前向引用，读取完整文件后检查闭合。

| status | 含义与消费者行为 |
| --- | --- |
| `included` | 目标应当在本文件中存在；缺失视为格式损坏 |
| `not_in_selected_snapshot` | 保留目标 ID，但本快照未包含它；不推断是删除还是其他会话，不补查或伪造 |
| `not_recorded` | 源关联为空或未采集，目标 null；不从时间邻近猜测关联 |
| `omitted_limit` | 源关联字段因限额省略，目标 null；不能理解成原本不存在 |

会话的 Provider/模型配置 ID、Provider 请求 ID、工具提供方的 `callId`、执行 runtime 名是外部身份，不是本文件实体的外键。任意正文中的字符串也不自动解释为引用。附件 Artifact、消息 Turn、压缩模型来源等真实关联即使不能闭合也不能静默删除。

## 正文、资源与完整度

小文本描述含 `sourceBytes,sourceSha256,hashAlgorithm,availability`；内联或脱敏后还含 `text,exportedBytes,exportedSha256`。原始与导出 hash 不混用。ContentStore 描述另有稳定 `contentId`、`source=content_store`、`verification`，内联文本标明 UTF-8。

`inline` 表示已获取原文；`redacted` 表示变换后文本；`reference_only` 表示未获取全文；`missing/changed` 表示正文不存在或无法通过身份校验；`omitted_limit` 表示超限省略。数据库大字段无法当外部内容取回，因此输出 `omitted_limit`。原始列超过读取限额时在 `omittedFields` 保留字段名及字节数，原值为 null。

固定上限：内联 32 KiB UTF-8、单行含 LF 256 KiB、关系快照与临时引用索引合计 128 MiB、最终 JSONL 128 MiB、交付缓冲 32 KiB。关系索引只针对已固定快照，在本地临时 SQLite 中有界查询，结束或失败删除，不参与任何执行恢复。关系快照与最终文件可同时存在，不能把 128 MiB 描述成整个操作的总磁盘占用。

关系读取在同一个 Room 事务内按原排序键作 keyset 分页，每页最多 128 个键，然后按主键读取单条公开投影。不把大参数/摘要加入整批排序或 DISTINCT 临时树；分页不改变上述顺序或快照边界。每页、每行及字段写出均可检查取消。

完成尾 `contentStatistics` 统计各类内容描述出现次数，包含正文和字段描述；不是消息数，也不是唯一 hash 数。`omittedFields` 的每个字段计入 `omitted_limit`。完成尾只证明导出流程完成；存在非 inline 内容、缺失引用或 unknown 时，消费者应降低对应分析/评测的材料完整度。文件不是自包含备份，不能据此执行工具或恢复授权。

关系数据来自单个 Room 事务，正文在事务结束后校验。写到系统文档后，只有尾记录和输出关闭均成功才显示成功；云盘同步是否完成不属于这一保证。自由正文保留业务内容并沿用已知凭据净化规则，不保证匿名。不得输出私有路径、配置凭据、Secret、认证 header/cookie 或可复用授权证明。

## 独立消费

[独立 Python 校验器](../../scripts/validate-session-export.py) 只依赖 Python 标准库，不调用 Android 或 Kotlin 导出代码，不解析原设备路径、不下载正文、不执行记录中的工具。[合成样例](../../scripts/fixtures/session-export-v1.jsonl) 来自真实 Android 存储导出器的测试数据，包含原消息、压缩摘要、工具结果与审批，不包含用户数据。

```sh
python3 scripts/validate-session-export.py scripts/fixtures/session-export-v1.jsonl
python3 scripts/validate-session-export.py exported.jsonl --messages messages.jsonl --calls calls.jsonl
python3 -m unittest discover -s scripts/tests -p test_session_export_validator.py
```

首条命令校验信封、稳定身份、连续序号、分组、正文 hash、引用及尾统计。`--messages` 按原消息 sequence 重建正文；`--calls` 输出工具调用及其结果、执行、审批关联，仍保留未知模型关联。派生文件不得覆盖已有文件。`valid=true` 与 `materialsComplete=false` 可以同时出现；合成样例正是如此。校验器不把结构正确推论成历史事实完整或评测适用。
