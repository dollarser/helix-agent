# HXA-209 Phase A：执行域效果边界与证据矩阵

日期：2026-09-16。源码起点：`446f0cf6`。本文是 HXA-209 第一切片（Phase A）的四个执行域“可识别效果 / 可约束执行 / 无法保证”矩阵与证据索引，并记录 rm 命令规则切片的实现与测试。Phase B 的单一授权配置与统一 resolver 尚未实现；本文不是新模式功能的验收记录，也不是禁网功能的验收。

## 结论一览

| 执行域 | 可识别效果 | 可约束（有证据） | 无法保证 |
| --- | --- | --- | --- |
| 原生文件工具 | 读/写/重命名/删除/创建；复制/移动/解压的源+目标合并效果 | 范围包含性逐操作实时重解析、写门禁 requireWrite、符号链接 fail-closed | 只约束本 lane，不能阻止 Shell/JS lane 写同一文件 |
| Shell/PRoot（developer） | argv/script 命令、cwd、环境（掩码后）、输入/输出引用、deadline | 环境机密掩码、输入/输出工件边界、job deadline 强杀、flavor 边界 | cwd 不是包含边界；无联网隔离（所有者已撤回统一禁网要求）；process_vm_writev 与同 UID /proc 残余缺口 |
| QuickJS | code + input JSON → 结果；执行限额 | 执行限额、取消、输出契约、seam 不重试不伪造成功 | 生产引擎暴露给脚本的宿主能力面（B 阶段逐项枚举） |
| 远端 MCP/A2A | schema/元数据（含 readOnlyHint）、agent card 能力快照 | 曝光过滤、schema 适配保约束保 hash、origin 固定、禁止重定向 | 远端业务副作用不可由 HTTP 方法或 readOnlyHint 判定 |

## 1. 原生文件工具（tools/files + core/workspace + app/files）

- **可识别效果**：全部操作经由 ManualFileBackend 面（stat/children/read/create/write/rename/delete）。读与写可区分：写路径走 `requireWrite`，写流每次写入复检 `requireWrite` 并检查 `WorkspaceQuota`；复制/移动/解压合并源读取与目标修改效果，移动另含源修改。
- **可约束**：`NioManualFileBackend.path()` 对每个操作实时重解析 scope root 与权限；`PathResolution.resolveWithinRoot` + `checkNoSymlinks` fail-closed（任一路径段为符号链接即抛 `SymlinkInPath`，越界即 FileScopePath 错误）。证据：本轮 `:core:workspace:test` 90/0（PathResolutionTest、WorkspaceFileOpsTest、WorkspaceMutateOpsTest 等）与 `:tools:files:test` 116/0（ReadToolTest、WriteToolTest、EditToolTest、ArchiveToolsTest）。
- **无法保证**：本 lane 的约束不覆盖其他 lane。CUSTOM 文件修改 DENY 的跨工具效果必须在每个执行入口分别强制（Phase B/C 接线）。
- **对 Phase B 的含义**：文件工具 lane 的 DENY 可执行（在 requireWrite seam 拒绝），只读可用性不受影响（读路径不经过 requireWrite）。

## 2. Shell/PRoot（developer-only，code.linux.run）

- **可识别效果**：`ParsedLinuxCall` 承载 argv 或 script（二者恰好其一）、cwd、environment（原始值经 `ProotEnvScreen` 掩码且已知机密值在工具边界被拒）、输入文件引用（条数上限）、输出引用、deadline（不超 per-call timeout）；输出在 `MAX_IMPORT_BYTES` 上限内经工件校验导回并持久化。
- **可约束**：环境机密掩码（ProotEnvScreen + knownSecretValues）、输入/输出经 WorkspaceArtifactStore 工件边界、job deadline 强杀、执行入口仅存在于 developer flavor（consumer 不含 PRoot/LinuxRunTool）、descriptor 契约（风险级、审批绑定 argsHash）。
- **无法保证（如实列出）**：
  1. **cwd 不是包含边界**：PRoot 以 App 共享 UID 运行，job 的 cwd 不阻止同 UID 进程读写 App 可见文件；Shell lane 无法保证工作目录限制。
  2. **联网**：同 APK isolated UID 无法承载现有 PRoot 的 RootFS/工作目录（[可行性验证](isolated-proot-feasibility-2026-09-16.md)），所有者已按回退条件撤回统一禁网要求；PRoot 保留 App 的联网能力。不声明离线或隔离。
  3. **seccomp 探针残余缺口**：process_vm_writev 与同 UID /proc 委托缺口、继承 FD；[复核记录](../../../scripts/debug/2026-09-16/hxa209-seccomp-poc/REVIEW.md) 已更正早期错误结论，该线已放弃、不接入生产。
  4. **脚本内容**：rm 命令规则是有限提醒规则（[ADR-PERMISSIONS-001 §3](../../adr/permissions/001-session-authorization.md)），不是沙箱；等价删除不承诺拦截。
- **rm 命令规则切片（本轮实现）**：`core/policy` 新增 `RmCommandRule`（argv/script 两形态），`RmCommandRuleTest` 45/45——含 ADR 要求的路径引号、空白、多个目标、复合命令子命令，以及 echo/注释/heredoc 字符串不误报；括号子 shell 与命令替换被检查（其命令确实执行）。命中结果为至少精确一次性审批，任何模式不得豁免。
- **对 Phase B 的含义**：Shell lane 的 CUSTOM 文件修改 DENY 在当前执行域不能技术保证写隔离，按 ADR fail-closed 契约：效果无法完整判定为不触犯 DENY 的调用直接拒绝并给出“当前执行环境无法保证所选限制”的具体原因，不转 ASK 放行；仅效果可完整判定的调用（可明确判定的只读命令类）放行。rm 规则是独立提醒层，不替代该判定。
- **设备证据（本地产物，2026-09-16 生成，gitignored）**：`build/hxa209-poc/`（proot-verification.log、owned-verification.log、proot-hardened-api29/36.log、app-and-proot-api29/36.log、netprobe/filterprobe/guard/diag/delegation-probe/fdprobe）、`build/hxa209-network-verification/app-observations-*.txt`。复现入口：[isolated-proot-spike](../../../scripts/debug/2026-09-16/isolated-proot-spike/README.md)。

## 3. QuickJS（code.javascript.run）

- **可识别效果**：`JsExecuteParams` 只携带 code、input JSON 与 limits（`JsExecutionLimits.DEFAULTS`）；结果为状态 + 输出契约（JsExecutionResult / 输出工件契约）；契约面没有宿主文件/网络通道。
- **可约束**：执行限额、取消（INTERRUPTED 仅在调用方取消信号下结算为 Stop）、seam 永不重试永不伪造成功；执行引擎是注入式 `fun interface JsExecutor`（生产为 QuickJS native，测试为 fake）。
- **无法保证**：生产引擎实际暴露给脚本的宿主能力面需要在 Phase B 逐项枚举——桥接面就是效果边界；解释器与 App 同进程，不存在进程级隔离。
- **证据**：本轮 `:runtime:quickjs:test` 85/0（JsAbiAssemblyTest、JsExecutionLimitsTest、JsOutputContractTest、CodeJavascriptRunToolTest 等）。

## 4. 远端 MCP/A2A

- **可识别效果**：MCP 工具的 schema + 元数据（McpToolMetadata，含 readOnlyHint）；A2A 的 agent card 能力快照（公开/扩展两级）；A2aTaskClient 的任务调用。远端侧业务副作用对本端不透明。
- **可约束**：曝光与发现过滤（McpToolDiscovery；本轮双 flavor 各 8/8）、schema 方言适配保约束保源 hash（McpToolSchemaAdapter）、origin 固定 + 禁止重定向的 OkHttp 客户端、凭据按别名查取。
- **无法保证**：远端真实业务副作用（发帖/修改/删除等）不能由 HTTP 方法或 readOnlyHint 判定。按 ADR：可信适配器契约分类效果；效果不明且可能违反 DENY 时直接拒绝。
- **证据**：本轮 McpToolDiscoveryTest 8/8（consumer/developer 双 flavor 新跑）。

## 5. 跨域共同判定座与证据标准

- **单一判定座**：`ToolDispatcher.commitExecutionStart` + `resolveToolApproval`（与 Registry 曝光路径共用同一 resolver），`ApprovalBinding` 绑定 toolCallId/contractHash/argsHash，deniedByTurn 缓存。Phase B 的新模式规则与工具二态在此接线，不建立四条执行链。
- **证据标准**：cwd、命令名、MCP readOnlyHint、提示词文本都不构成执行隔离证据（HXA-209 Phase A 与 ADR §3）。可作证据的只有：逐操作重解析的代码 seam + 断言实际副作用的测试，以及真实执行的设备转录。
- **历史测试只作线索**：本文所有主机套件均为 2026-09-16 当日新执行；设备转录为当日本地产物，历史设备测试结果不作为当前通过证据。

## 6. 本轮证据执行清单（2026-09-16，全部新跑）

| 命令 | 结果 |
| --- | --- |
| `./gradlew :core:policy:test` | 1222 tests，0 failures，0 skipped（含新增 RmCommandRuleTest 45） |
| `./gradlew :core:workspace:test` | 90，0，0 |
| `./gradlew :runtime:quickjs:test` | 85，0，0 |
| `./gradlew :tools:files:test` | 116，0，0 |
| `./gradlew :app:testConsumerDebug` | 544，0 failures，4 opt-in skip |
| `./gradlew :app:testDeveloperDebug` | 570，0 failures，4 opt-in skip |

4 个 skip 均为既有 assumeTrue opt-in，非伪装：ConnectorSuppliedArchiveTest 与 WorkBuddySuppliedArchiveTest 各 1 项需要本地样本，ConnectorExternalAcceptanceTest 2 项为 HXA-125 外部验收 opt-in。

## Phase A 结论

- **工作目录限制**：文件工具 lane 证明生效（代码 seam + 副作用断言测试）；Shell lane 证明 cwd 不构成保证——边界明确，fail-closed 契约可执行。
- **CUSTOM 文件修改 DENY**：文件工具 lane 可强制；QuickJS lane 效果边界 = 生产桥接面（B 阶段枚举）；Shell lane 按 fail-closed 拒绝 + rm 提醒层；远端 lane 按效果分类拒绝。
- **可用性**：文件工具与 QuickJS lane 的只读 + 正常任务可用（本轮套件全绿），不是一律拒绝。
- **rm 命令规则**：规则与 45 个契约测试已实现并通过；与统一 resolver 的接线（“至少精确一次性审批，模式不得豁免”）在 Phase B 完成。
- **遗留**：PRoot 写隔离缺口是已知边界，按 fail-closed 产品语义处理；不阻塞 Phase B 开始。
