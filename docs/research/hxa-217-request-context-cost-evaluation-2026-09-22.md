# HXA-217 请求来源记录格式与成本预评估

日期：2026-09-22。本文是 HXA-217 格式切片的主机预评估，不接受 ADR-AGENT-010，不修改任务状态，也不代表 Android Room 或设备成本已经通过。

## 现有边界

- `ModelRequest.MAX_MESSAGES` 当前为 **512**；请求消息必须非空，末条只能是 `USER` 或 `TOOL`。[`ModelRequest.kt`](../../core/model/src/main/kotlin/com/helix/core/model/ModelRequest.kt#L229-L234) [`ModelRequest.kt`](../../core/model/src/main/kotlin/com/helix/core/model/ModelRequest.kt#L256-L262)
- 生产 `RandomIdGenerator` 产生 **32 个小写十六进制字符**；`ChatSubmission.clientRequestId` 只是非空字符串，实际路径还会使用常见的 **36 字符 UUID**。[`Identifier.kt`](../../core/model/src/main/kotlin/com/helix/core/model/Identifier.kt#L30-L46) [`ChatSubmission.kt`](../../app/src/main/kotlin/com/helix/app/chat/ChatSubmission.kt#L3-L18)
- `SessionInputValidation` 对 `inputId` 等输入标识允许最多 **256 个字符**；本次没有把 256 字符当成生产典型，而是额外用 64 字符做通用标识压力场景。[`SessionInputValidation.kt`](../../core/storage/src/main/kotlin/com/helix/core/storage/repository/SessionInputValidation.kt#L6-L16) [`SessionInputValidation.kt`](../../core/storage/src/main/kotlin/com/helix/core/storage/repository/SessionInputValidation.kt#L71-L73)
- 既有 JSONL 是 `helix.session-export` 版本 1；单行上限 **256 KiB**，文件上限 **128 MiB**。[`SessionExportFormat.kt`](../../core/storage/src/main/kotlin/com/helix/core/storage/export/SessionExportFormat.kt#L8-L14)
- 现有导出行包含 `format`、`formatVersion`、`sequence`、`type`、`recordId`、`sessionId` 和 `data`，并要求 record id 带类型前缀。[`SessionExportFormat.kt`](../../core/storage/src/main/kotlin/com/helix/core/storage/export/SessionExportFormat.kt#L55-L64) [`SessionExportFormat.kt`](../../core/storage/src/main/kotlin/com/helix/core/storage/export/SessionExportFormat.kt#L88-L95)
- 消息本身还可能携带工具调用 ID、工具名、附件引用等结构，因此本测量只估算来源 ID、角色和检查点元数据；它没有把正文、工具参数或附件字节复制进清单。[`ModelRequest.kt`](../../core/model/src/main/kotlin/com/helix/core/model/ModelRequest.kt#L112-L150)

## 测量方法

脚本已先落盘，再执行：[`measure-hxa217-request-context-cost.py`](../../scripts/debug/2026-09-22/measure-hxa217-request-context-cost.py)。

```bash
python3 scripts/debug/2026-09-22/measure-hxa217-request-context-cost.py \
  --output build/hxa217-preparation/request-context-cost.json \
  --model-request core/model/src/main/kotlin/com/helix/core/model/ModelRequest.kt \
  --identifiers core/model/src/main/kotlin/com/helix/core/model/Identifier.kt \
  --session-input-validation core/storage/src/main/kotlin/com/helix/core/storage/repository/SessionInputValidation.kt \
  --export-format core/storage/src/main/kotlin/com/helix/core/storage/export/SessionExportFormat.kt
```

脚本使用合成 ID（ASCII ID 与 `é` Unicode 压力值），不读取会话正文、Room、Provider 或附件。每个场景编码和解析 100 次，统计 Python 主机 `json.dumps/json.loads` 的中位数与 P95；行字节数包含现有 JSONL 外层记录和换行。结果原文保存在 `build/hxa217-preparation/request-context-cost.json`，该目录属于 ignored 构建证据。

候选编码如下：

- `verbose`：每条消息为 `{id, role}`，顶层字段使用完整名称。
- `compact`：每条消息为 `[id, roleCode]`，使用短顶层键；角色码为 `s/u/a/t`，分别代表 `SYSTEM/USER/ASSISTANT/TOOL`。

## 结果

运行环境为 Python 3.14.7、macOS 26.5.1 arm64。消息/会话/Turn 等生产 ID 取 32，输入 ID 取实际常见 UUID 长度 36；另测 64 字符 ASCII 压力，以及依据 `SessionInputValidation` 的 256 字符 ASCII/Unicode 输入 ID 压力。256 字符 Unicode 场景使用合成 `é`，只用于字节边界压力，不代表真实用户内容。数值是格式估算，不是 Android 性能承诺。

| 场景 | verbose 单行 | compact 单行 | compact 节省 |
| --- | ---: | ---: | ---: |
| 24 条消息、4 个 36 字符 input ID、消息 ID 32 字符 | 2,170 B | 1,769 B | 18.5% |
| 512 条消息、512 个 36 字符 input ID、消息 ID 32 字符 | 47,846 B | 41,589 B | 13.1% |
| 512 条消息、512 个 64 字符 ASCII 压力 ID | 78,918 B | 72,661 B | 7.9% |
| 512 条消息、512 个 256 字符 ASCII input ID | 160,486 B | 154,229 B | 3.9% |
| 512 条消息、512 个 256 字符 Unicode input ID | 287,974 B | 281,717 B | 2.2% |

`SessionInputRepository.appendedForMessages` 明确按 `ModelRequest.MAX_MESSAGES` 接受最多 512 个来源消息，并按 32 条分块查询；因此这次最大场景使用 512 条消息和 512 个 input ID，而不是把 216 的 pending 队列容量误当作请求来源上限。[`SessionInputRepository.kt`](../../core/storage/src/main/kotlin/com/helix/core/storage/repository/SessionInputRepository.kt#L55-L65)

512×512、UUID36 组合仍只有约 40.6 KiB（compact）或 46.7 KiB（verbose），明显低于既有 256 KiB 单行上限。256 字符 ASCII input ID 压力也低于单行上限；256 字符 Unicode input ID 压力约 275.1 KiB（compact）或 281.2 KiB（verbose），会触发既有单行界限，不能无条件宣称“完整”。100 次重复历史 ID 的累计 JSONL 行字节按实际 `sequence=0..99` 逐行求和：

- UUID36：verbose **4,784,690 B**，约 **4.56 MiB**；compact **4,158,990 B**，约 **3.97 MiB**。
- 256 字符 ASCII input ID：verbose **16,048,690 B**，约 **15.31 MiB**；compact **15,422,990 B**，约 **14.71 MiB**。
- 256 字符 Unicode input ID：verbose **28,797,490 B**，约 **27.46 MiB**；compact **28,171,790 B**，约 **26.87 MiB**。

UUID36 最大列表的主机编码/解析中位数分别为 verbose **142.58/99.40 µs**、compact **116.08/65.71 µs**；P95 分别为 verbose **192.40/131.13 µs**、compact **185.95/106.71 µs**。256 字符 Unicode 场景的 compact 行已超过单行上限，计时只是序列化压力数据，不能当作可写 JSONL 的成功路径。

典型 24 条消息场景的 100 次累计为 verbose **217,090 B**、compact **176,990 B**。64 字符 ASCII 压力组合的 100 次累计为 verbose **7,891,890 B**、compact **7,266,190 B**。这说明短格式仍有可测收益，但这些累计值没有测 Room 行、SQLite 索引、事务日志、内容存储、文件同步或 Android JSON 实现，所以不能据此批准固定配额。

## 最小格式建议（候选）

若 ADR-AGENT-010 后续接受，建议先把清单作为现有 `model_call` 记录的紧凑嵌套元数据候选，不新增独立大制品或正文副本。测量中的 `verbose` 版本只作为可读性对照，不把 session/turn/modelCall 冗余字段当作推荐持久格式：

```json
{
  "v": 1,
  "p": "generation",
  "m": [["<messageId>", "u"]],
  "i": ["<inputId>"],
  "k": ["<checkpointId>", "<coveredThrough>", ["<preservedMessageId>"]],
  "q": "complete"
}
```

`m` 保留请求顺序和角色，`i` 保留实际使用的输入 ID，`k` 表示压缩 checkpoint、覆盖边界和保留消息，`q` 记录采集时的 `complete/incomplete/omitted`。旧版未采集由记录缺失及版本判断，源内容不可用由读取时解析判断，不把二者伪装成采集时状态。session/turn/modelCall 关联优先复用外层 ModelCall 或 JSONL 记录，避免再次存储。UUID36 场景使用完整字段名仍有余量，但 Unicode 压力场景已超限，必须明确省略状态，不能因为用了短键就取消字节边界。

这只是编码候选：角色码映射、checkpoint 字段、未知版本行为、缺失源语义和是否嵌入 `model_call` 仍需 ADR/实现评审确认。清单只表示本地逻辑请求选用了哪些来源，不表示 Provider 收到、模型理解或最终 wire 字节等价。

## 仍需 Android/实现验证

1. 在最终来源组装、压缩、附件物化和准入之后、Provider 调用之前采集；用真实 Queue/Steer、修订、fork、工具批、附件和摘要检查点核对顺序，不用合成 ID 冒充交付证据。来源列表最多按 `ModelRequest.MAX_MESSAGES=512` 评估，不能把 pending 队列 32 条当成请求来源上限。
2. 用 Kotlin JSON 实现和 Room 事务测量单行、100 次重复历史及长期累计成本；覆盖 512 条边界、256 字符 input ID、UTF-8 字节边界、超限标记、写失败不阻断合法请求，以及 `model_call`/关联行的迁移和删除清理。
3. 在 API29/36 × consumer/developer 四象限验证普通进程中断：采集前、落盘后、Provider 前、Provider 返回后分别核对 ModelCall 终局；清单落盘不能被解释为远端已收到，也不能触发重放。
4. 复用既有 JSONL parser 做旧版无清单、未知版本、缺失消息/checkpoint/附件和导出中删除/取消的兼容测试；不导出正文副本、认证头、Secret、含凭据 URL 或附件字节。
5. Provider 对照仍需按 OpenAI Responses、Chat Completions、Anthropic 的真实序列验证；逻辑来源清单不要求与三类 wire JSON 字节相同，但不能丢消息、工具结果或 input ID 的顺序关系。

本轮实际执行的只有上述主机脚本；未运行 Gradle、设备测试、Room 写入或 Provider 请求。
