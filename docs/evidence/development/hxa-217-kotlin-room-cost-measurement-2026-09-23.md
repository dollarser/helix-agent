# HXA-217 请求来源记录主机存储成本模拟

日期：2026-09-23。
依据：[HXA-217 完成记录](../../completion-records/HXA-217.md)与 [ADR-AGENT-005](../../adr/agent/005-session-jsonl-export.md)。
关联基线评估：[主机格式预评估](../research-history/hxa-217-request-context-cost-evaluation-2026-09-22.md)。

## 1. 测量目标与方法

本页保留 2026-09-23 的报告数字。复核所链测量脚本可确认：它在主机使用 Python `sqlite3`、`json` 和简化 schema 模拟请求来源存储，不运行 Android、Room 或 Kotlin 编解码器。每次同时写入 `model_calls.requestManifest` 和 `request_manifests`，不是两种策略各自独立的生产成本对照。本次只核对脚本，未重跑历史测量。

### 测量参数
- **模拟引擎参数**：脚本显式设置（`page_size = 4096`，`journal_mode = WAL`，`synchronous = NORMAL`，`foreign_keys = ON`）。
- **编码参照**：生产 Kotlin 类型为 [`CompactManifestCodec`](../../../core/model/src/main/kotlin/com/helix/core/model/RequestContextManifest.kt)；测量脚本独立实现 Python 紧凑元组与截断逻辑，不能用其输出证明 Kotlin 守卫或性能。
- **长任务模拟**：单会话内连续执行 **100 次模型调用**（每次调用保存当前完整请求来源上下文，包含随轮次累积的历史消息引用与 input IDs）。
- **执行脚本与测试**：
  - 当时的测试套件：[`RequestContextManifestBenchmarkTest.kt`](https://github.com/dollarser/helix-agent/blob/832020703108d6fad79fc1ccf55fc24bb1d554db/core/model/src/test/kotlin/com/helix/core/model/RequestContextManifestBenchmarkTest.kt)；重复基准在后续 CI 精简中移除，当前边界回归为 `RequestContextManifestTest`。
  - 测量脚本：[`measure-hxa217-kotlin-room-cost.py`](../../../scripts/debug/2026-09-23/measure-hxa217-kotlin-room-cost.py)
  - 原始产物：`build/hxa217-preparation/kotlin-room-cost-report.json`

---

## 2. 历史主机模拟报告

| 场景 | 消息与输入规模 | 单行 Bounded 字节 | 100次模拟调用 SQLite 磁盘占用 | 写入事务 P50 | 读取解析 P50 | 脚本比较线 (10 MiB，非产品安全阈值) |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: |
| **典型会话 (typical)** | 24 消息，4 个 UUID36 input ID | **1,195 B** (~1.17 KiB) | **0.29 MB** (299 KiB) | 0.032 ms | 0.004 ms | 低于脚本比较线 |
| **最大合法场景 (max_valid)** | 512 消息，512 个 UUID36 input ID | **41,015 B** (~40.05 KiB) | **4.22 MB** (4,222 KiB) | 0.066 ms | 0.004 ms | 低于脚本比较线 |
| **64字符标识压力 (ascii64)** | 512 消息，512 个 64字符 ASCII ID | **55,351 B** (~54.05 KiB) | **5.41 MB** | 0.075 ms | 0.005 ms | 低于脚本比较线 |
| **256字符标识极限 (ascii256)** | 512 消息，512 个 256字符 ASCII ID | **153,655 B** (~150 KiB) | **14.67 MB** | 0.142 ms | 0.006 ms | 超出 10MB，但远低于 64MB |
| **Unicode 极端压力 (unicode256)** | 512 消息，512 个 256字符 Unicode ID | **151,105 B** (受限截断) | **25.48 MB** | 0.281 ms | 0.007 ms | Python 模拟发生截断 |

---

## 3. 可支持的结论与限制

- 上表字节和延迟是原报告保留值，没有在本次重测。脚本使用主机 Python 的 JSON 编解码，写入事务同时包含两份清单；这些值不能标作 Kotlin 编码耗时、Android Room 延迟或单一生产表的存储成本。
- 脚本中 256 KiB 截断只证明该 Python 模拟的行为。生产 Kotlin 上限和畸形输入应由实际 `RequestContextManifestTest` 等回归证明，不能由模拟替代。原报告的 Kotlin 0.15 ms 未附独立结果绑定，本页不再据其宣称性能可忽略。
- 10 MiB/64 MiB 是脚本选用的比较线，不是已接受的产品配额或普遍安全线。100 次合成调用结果不能外推所有长会话；表中 MB/KiB 为历史显示口径，不作为精确换算依据。
- 脚本验证自建 schema 的级联删除后两表剩余行数为零；这不独立证明生产 Room 生命周期。`VACUUM` 后仅读取主数据库文件大小，没有再次合计 WAL，不能据此声称完整物理磁盘空间已回收。
- Android 端侧性能、真实生产 schema、单一存储策略比较、实际 WAL/文件回收与长稳均不由本报告验收。当前实现与任务结论仍查所链完成记录及 ADR；历史原始报告未在本轮重新核验。
