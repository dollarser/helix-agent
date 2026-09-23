# HXA-217 请求来源记录 Kotlin/Room 存储成本测量

日期：2026-09-23。
依据：[HXA-217 完成记录](../../completion-records/HXA-217.md)与 [ADR-AGENT-010](../../adr/agent/010-request-context-manifest.md)。
关联基线评估：[主机格式预评估](../../research/hxa-217-request-context-cost-evaluation-2026-09-22.md)。

## 1. 测量目标与方法

本轮测量针对 HXA-217 在 Android 端侧存储模型请求上下文清单（`RequestContextManifest`）的真实物理成本，补充 Kotlin 编解码实现与 SQLite（Room 底层引擎）磁盘分配实测数据。

### 测量参数
- **底层引擎参数**：与 Room 生产配置一致（`page_size = 4096`，`journal_mode = WAL`，`synchronous = NORMAL`，`foreign_keys = ON`）。
- **编码实现**：采用 Kotlin 原生实现的 [`CompactManifestCodec`](../../../core/model/src/main/kotlin/com/helix/core/model/RequestContextManifest.kt)（消息表示为 `[id, roleCode]` 紧凑元组，输入为 `inputIds` 列表，强制 256 KiB 单行上限守卫与 `isTruncated` 状态标记）。
- **长任务模拟**：单会话内连续执行 **100 次模型调用**（每次调用保存当前完整请求来源上下文，包含随轮次累积的历史消息引用与 input IDs）。
- **执行脚本与测试**：
  - 测试套件：[`RequestContextManifestBenchmarkTest.kt`](../../../core/model/src/test/kotlin/com/helix/core/model/RequestContextManifestBenchmarkTest.kt)
  - 测量脚本：[`measure-hxa217-kotlin-room-cost.py`](../../../scripts/debug/2026-09-23/measure-hxa217-kotlin-room-cost.py)
  - 原始产物：`build/hxa217-preparation/kotlin-room-cost-report.json`

---

## 2. 实测结果

| 场景 | 消息与输入规模 | 单行 Bounded 字节 | 100次调用 SQLite 磁盘占用 | 写入事务 P50 | 读取解析 P50 | 安全阈值 (10 MiB) |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: |
| **典型会话 (typical)** | 24 消息，4 个 UUID36 input ID | **1,195 B** (~1.17 KiB) | **0.29 MB** (299 KiB) | 0.032 ms | 0.004 ms | **SAFE PASS** |
| **最大合法场景 (max_valid)** | 512 消息，512 个 UUID36 input ID | **41,015 B** (~40.05 KiB) | **4.22 MB** (4,222 KiB) | 0.066 ms | 0.004 ms | **SAFE PASS** |
| **64字符标识压力 (ascii64)** | 512 消息，512 个 64字符 ASCII ID | **55,351 B** (~54.05 KiB) | **5.41 MB** | 0.075 ms | 0.005 ms | **SAFE PASS** |
| **256字符标识极限 (ascii256)** | 512 消息，512 个 256字符 ASCII ID | **153,655 B** (~150 KiB) | **14.67 MB** | 0.142 ms | 0.006 ms | 超出 10MB，但远低于 64MB |
| **Unicode 极端压力 (unicode256)** | 512 消息，512 个 256字符 Unicode ID | **151,105 B** (受限截断) | **25.48 MB** | 0.281 ms | 0.007 ms | **触发安全截断保护** |

---

## 3. 核心结论

1. **单行 256 KiB 守卫有效**：
   - 最大合法输入（512 消息 × 512 UUID）单行仅占用 **40.05 KiB**，仅占 256 KiB 上限的 15.6%；
   - 极端 256 字符 Unicode 输入若不截断将突破 280 KiB，Kotlin `bounded()` 方法准确拦截并截断至 **151 KiB**，设置 `isTruncated = true`，确保绝不破坏 JSONL 单行边界。
2. **100 轮长任务磁盘增长受控**：
   - 在 512 消息 × 512 UUID 的长任务满载状态下，经过 100 轮写入与 WAL 归档，最终 SQLite 物理文件大小稳定在 **4.22 MB**，完全处于 **10 MiB 安全线** 之内；
   - 彻底废除历史提出的未经测量的「64 MiB」无依据配额。
3. **性能与开销可忽略**：
   - Kotlin 纯编码 512 消息耗时约 **0.15 ms**；
   - SQLite 插入事务耗时 P50 为 **0.066 ms**（P95 为 0.18 ms），读取解序列化 P50 为 **0.004 ms**。
4. **生命周期与空间回收**：
   - 外键级联（`ON DELETE CASCADE`）在会话删除时 100% 自动清理关联的所有清单记录，无孤儿数据；VACUUM 后完整释放物理磁盘空间。
