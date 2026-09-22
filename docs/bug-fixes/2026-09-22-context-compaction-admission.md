# Bug Fix: 压缩请求准入、用量校准与历史读取上限

Status: fixed
Date: 2026-09-22
Related HXA: HXA-174, HXA-176
Affected modules: app/agent, app/chat, app/provider, core/agent, core/storage

## Problem

原有逐轮压缩门禁能够重新触发未完成的压缩，但普通请求与摘要请求的容量检查不一致。摘要主要依靠规划时预留空间；实际用量高于估算时，规划仍可能选入过多历史。成功发布检查点后又清除校准比例，使后续请求重新低估输入。

请求组装在容量检查前读取全部消息元数据及活跃正文；规划每追加一组消息，又复制并编码整个已选前缀。长会话在发出请求前就可能产生不必要的内存与 CPU 开销。首个不可拆分工具批过大时，原来的“没有压缩计划”也不能准确说明原因。

## Impact

影响长会话、工具结果密集的连续任务以及 token 估算偏差较大的模型。可能出现压缩请求本身过大、重复低估、读取阶段资源消耗偏高或失败原因不清楚。它不意味着原始历史已损坏，也不证明曾经发生真实设备 OOM。

## Root cause

- 普通请求、摘要规划及 Goal 恢复使用不同的容量判断路径。
- 将检查点覆盖旧历史与同一模型的 token 比例校准混为一件事；缺少 usage 的响应也会冲掉已有比例。
- 历史查询和正文物化没有独立于模型 token 预算的端侧上限。
- 默认窗口与 Provider 上报的窗口没有在诊断和设置中清楚区分。

## Fix and invariants

1. 普通请求与摘要请求共用消息数、输入预算和输入加输出窗口检查。摘要按完整请求估算，包括提示与协议开销；数值比较避免输入加输出溢出。
2. 按已观察的输入用量比例缩小摘要候选前缀。检查点成功后保留当前 Turn 内同一模型的比例，移除旧绝对水位；后续缺少 usage 不丢弃已有比例。不把该内存比例描述成跨进程持久校准。
3. 每页最多读取 256 条消息元数据；只读取检查点仍需保留的正文，保留 SYSTEM 和显式保留的消息。活跃正文累计上限为 8 MiB，保留行上限为 8192，超限在读取正文前返回 `CONTEXT_MATERIALIZATION_LIMIT`。单次正文读取也有界。归档中已被检查点覆盖的大正文不消耗活跃预算。
4. UTF-8 长度按字符增量计算，不再为了计数分配整段字节数组；摘要前缀累计字节数，不反复复制完整前缀。保留原有 token 估算口径。
5. 不拆开工具调用与结果批。无法容纳首个候选批时给出 `CONTEXT_SEGMENT_LIMIT`；自动压缩仅达软阈值且原请求仍可准入时允许继续，手动压缩或已超硬容量则明确失败。UI 提供处理方向，原始历史不删除、不静默截断；Goal 沿现有容量阻塞路径结算。
6. 日志记录窗口来源 `provider`、`manual` 或 `fallback`。无上报值且无手工值时保留原有 200000 默认配置，但 UI 明确其为估算并提供手工设置。
7. 取消语义不变：检查点发布前取消不持久化半成品；后续 Turn 重新规划。发布后取消保留已完成的检查点。新增查询不修改 Room schema，不需要迁移。

这些是 accepted ADR-AGENT-002 现有约束的实现补强，不引入独立压缩服务或新的工作流状态机。

## Alternatives considered

- 静默截断超大结果或拆开工具批：会丢失语义或破坏工具协议，因此保留明确失败和大内容引用的处理方式。
- 直接降低所有模型的默认窗口：会改变既有配置行为，也不能保证等于真实模型限制；本轮先暴露来源并统一准入。
- 内置所有 Provider 的精确 tokenizer：增加端侧依赖和模型版本维护成本；本轮沿用估算并利用已观测 usage 校准。
- 一次把全会话读入再做 token 检查：检查发生太晚；改为正文读取之前独立检查端侧容量。

## Regression verification

独立分支基于 `c36d556b`，未修改并行 marketplace 工作。主机执行 `./scripts/check-all.sh --all`，以及 `./gradlew :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest`，均 exit 0。覆盖格式、静态分析、全模块 JVM、debug/release Lint、APK 组装与变体/Runtime 边界。日志保留在忽略目录 `build/compaction/full-gate-8.log`；前序格式、复杂度及英文数量词 Lint 失败均修正后重新执行，不算通过。

`ContextCapacityTest` 新增 4 项，consumer/developer debug 各 4/4：边界与溢出、摘要校准和软阈值回退、UTF-8 计数（含不配对代理字符）、窗口来源。

设备命令（JDK 17、已配置 Android SDK；所有构建及设备运行均串行持有共享 host slot）：

```sh
python3 scripts/debug/2026-09-18/with-host-slot.py -- \
  python3 scripts/debug/2026-09-22/accept-compaction.py \
  --output build/compaction/devices-1
```

API29/36 × consumer/developer 各 49/49，共 **196 PASS、0 FAIL、0 SKIP**，命令 exit 0。四个独占模拟器均有 owner 与 closed 记录。严格收集器校验预期方法集合、实际结果、进程生命周期及 APK SHA-256，四组均为 `DEVICE_BATCH_PASS`。

覆盖 `LongTurnCompactionDeviceTest`、`ContextCompactionDeviceTest`、`ContextHistoryDeviceTest`、`ChatCompactionFlowDeviceTest`、`GoalRunCoordinatorDeviceTest`、`GoalUsageReservationsDeviceTest`、`GoalTurnBindingDeviceTest`。新增设备回归 7 项验证：比例校准后选取更小摘要并在发布及缺失 usage 后保留比例、不可拆分工具批、发布前取消后新 Turn 重规划、发布后取消保留检查点、跨页保留消息、超大活跃正文拒绝、已覆盖大正文不计活跃预算。

原始日志、每组预期方法、完整报告及复制的 APK 保留于 `build/compaction/devices-1/`；矩阵汇总为 `batches.json`。验收 APK SHA-256：

| Flavor | 主 APK | 测试 APK |
| --- | --- | --- |
| consumer | `88a1dbee8b5856c2fbe35df26565980e07b8b70c2bab7f0599492ffac8c5c50c` | `216298fbe2bf63cfdf0977dc49cd7ee33cb333e47b615f0f92af81f222cf48d8` |
| developer | `91d680ad400f469711fa6d51196f82b6adce3f7360724e4ba8acd0336d82f9a5` | `ee6c5d9a90b6ce829bf757f2d03f1058462997990e07d8f4059958afed59410f` |

以上为本地定向压缩/Goal 回归，不冒充全产品设备验收或远端 CI。本轮未调用需要外部账号的 live Provider 测试。

## Residual risk

本地 token 估算与 Provider tokenizer、图片计费仍可能不同；200000 fallback 不是 Provider 真实窗口保证。门禁降低超窗和物化资源风险，不能承诺绝不出现 HTTP 400 或 OOM。8 MiB/8192 是应用活跃历史读取上限，不是全进程内存上限；分页仍扫描历史元数据，未声称超长归档查询耗时恒定。

本轮无真实外部模型账号压测、物理 OEM/热压或内存峰值测量。遇到单个不可压缩大批或活跃正文超限时，仍需调整输入/窗口或在新会话使用大内容引用；不会自动改写原始档案。已有摘要内容质量和跨模型语义保真不由容量测试证明。

## Related records

- [ADR-AGENT-002](../adr/agent/002-context-compaction.md)
- [HXA-174 完成记录](../completion-records/HXA-174.md)
- [HXA-176 完成记录](../completion-records/HXA-176.md)
