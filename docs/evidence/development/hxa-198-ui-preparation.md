# HXA-198 多会话终端 UI 设计、状态/操作表与测试映射

本文档为小模型工作包 D（`docs/development/small-model-handoff.md` 第 6 节）的交付物。
当前状态：**WAITING_CORE**（核心接口与实现尚未交付，严禁添加伪装双会话的占位代码或未接线 UI）。

---

## 1. 现状审查：单会话假设与代码入口审计

经审查当前终端相关生产代码，系统硬编码了单会话（Single-Session）假设，分布在以下关键组件中：

| 文件路径 | 现有实现模式 | 存在的单会话假设 |
| :--- | :--- | :--- |
| `app/src/main/kotlin/com/helix/app/terminal/ManualTerminal.kt` | 单例无参接口 | `hasSession()`、`start()`、`query()`、`stop()`、`settle()`、`attach()` 均无 `sessionId` 参数，假定全局仅存在一个手动终端会话。 |
| `app/src/developer/kotlin/com/helix/app/terminal/DeveloperManualTerminal.kt` | 单文件所有权绑定 | 使用单个 `ExecutionOwnershipStore(File(context.filesDir, "execution-admission/manual-terminal"))`；`start()` 显式断言 `check(binding.read() == null)`，存在活跃会话时直接拒绝创建新会话。 |
| `app/src/developer/kotlin/com/helix/app/terminal/ManualTerminalViewModel.kt` | 单一页面状态 | `TerminalPageState` 仅维护单一 `hasSession: Boolean`、`session: ManualTerminal.State?` 与 `connection: ManualTerminal.Connection?`，无会话列表与多标签切换逻辑。 |
| `app/src/developer/kotlin/com/helix/app/terminal/ManualTerminalConnection.kt` | 单一连接生命周期 | 每次 `attach` 获取单一 `token`，未区分读写连接权限（只读观察者 vs 单写连接）。 |
| `app/src/developer/kotlin/com/helix/app/terminal/ManualTerminalScreen.kt` | 单会话操作按钮栏 | 顶部与控制栏仅提供一组针对单会话的 Start / Connect / Stop / Settle 按钮，缺乏多会话切换卡/Tab。 |
| `app/src/developer/kotlin/com/helix/app/terminal/ManualTerminalViewport.kt` | 单一虚拟终端仿真 | Viewport 与 Feed 绑定单一 connection；切页即 Dispose 仿真器。 |

---

## 2. 状态与操作表（State & Operation Specification）

根据 HXA-198 规格与 ADR-RUNTIME-001 约束，双会话终端必须满足以下状态转移与并发边界：

### 2.1 会话容量与生命周期状态表

| 场景 / 当前状态 | 操作 / 触发事件 | 目标状态 | 行为与约束 |
| :--- | :--- | :--- | :--- |
| **0 会话活跃** | 用户点击“新建终端（Start）” | 1 会话活跃（Session A: RUNNING） | 分配席位 1，生成新 `sessionId` 与 `generation`，持有独立租期与工作区。 |
| **1 会话活跃** | 用户点击“新建终端（Start）” | 2 会话活跃（Session A: RUNNING, Session B: RUNNING） | 分配席位 2，生成新 `sessionId`，互不干扰，互不共享 PTY 文件描述符。 |
| **2 会话活跃** | 用户尝试创建第 3 会话 | 保持 2 会话活跃，操作被明确拒绝 | **核心拒绝**：返回 `CAPACITY_EXHAUSTED`，UI 弹出或展示明确的“已达最大并发终端数（2 个）”提示，禁止静默失败。 |
| **创建失败** | 启动过程中 Runtime 报错或超限 | 释放占用的席位配额 | 必须在 `finally` 中释放未提交的 `ExecutionOwnership` 与席位计数，不得泄漏席位导致无法再建。 |
| **Session A 运行中** | 用户在 UI 切换到 Session B | Session A 保持后台运行，Session B 成为当前前台视口 | 切换操作只进行 UI 连接的 Detach/Attach，**绝对不得销毁或重启 Session A 的后台 Shell 进程**。 |
| **Session A 终止** | 用户对 Session A 执行 Stop / exit | Session A: STOPPED (`canSettle=true`), Session B: RUNNING | Session A 停止完全隔离，**不得影响 Session B 的执行状态与屏幕输出**。 |
| **Session A 结算** | 用户对 Session A 执行 Settle | 1 会话活跃（Session B: RUNNING） | 仅移除 Session A 的持久化记录与席位占用，空出一个并发席位，允许新建下一会话。 |

### 2.2 连接角色与安全性约束表

| 维度 | 规则与契约要求 |
| :--- | :--- |
| **单写连接（Single Writer）** | 每个会话（Session）在任意时刻最多只允许一个客户端持有写权限（Token）。同一会话已有写连接时，后续连接自动降级为只读观察者（Observer）。 |
| **只读观察者（Read-Only Observer）** | 只读连接可以持续读取增量输出（`READ`）并更新屏幕视口，但无写权限（`WRITE` 返回拒绝，软键盘与快捷键禁用）。 |
| **代数失效（Generation Invalidation）** | 会话进程死亡、Crash 或 Runtime 重启后，旧的 `generation` 立即失效。持有旧凭据的连接发起任何写操作均返回 `GENERATION_MISMATCH`，防止野指针重放或串线。 |
| **会话身份隔离** | `sessionId` 仅作为内部寻址键，禁止当作权限凭证传递；禁止根据操作系统复用的 PID 进行跨会话操作。 |

---

## 3. 核心接口与协议需求清单（需协调者提供）

在协调者未交付核心提交前，UI 层不得进行硬编码。UI 层的实际接线依赖协调者在 `runtime/proot-*` 和 `core` 模块中交付以下已验证接口：

### 3.1 `ManualTerminal` 接口扩展需求

协调者需将 `ManualTerminal` 从单会话升级为多会话感知接口：

```kotlin
interface ManualTerminal {
    // 查询当前所有活跃与待结算的终端会话列表（最多 2 项）
    suspend fun listSessions(): List<State>

    // 启动指定目录的新会话，若容量超限抛出 CapacityExhaustedException
    suspend fun startSession(
        relativeDirectory: String = ".",
        leaseMs: Long = 7_200_000,
    ): State

    // 针对特定会话的查询、停止与结算
    suspend fun querySession(sessionId: String): State
    suspend fun stopSession(sessionId: String): State
    suspend fun settleSession(sessionId: String)

    // 连接指定会话，writeRequested 指定是否请求写连接
    suspend fun attachSession(
        sessionId: String,
        writeRequested: Boolean = true,
    ): ConnectionResult

    data class ConnectionResult(
        val connection: Connection,
        val isWriter: Boolean, // true 为写连接，false 为只读观察端
    )
}
```

### 3.2 IPC 与协议需求 (`PtySessionProtocol` & `PtySessionClient`)

1. **容量保护**：`START` 事务在 Runtime 侧严格校验并发会话数 $\le 2$，超限返回 `OUTCOME_CAPACITY_EXHAUSTED`。
2. **连接协商**：`ATTACH` 事务在返回 payload 中标明连接模式（`WRITER` vs `OBSERVER`）。
3. **会话多路复用**：`PtySessionClient` 必须能够根据 `PtySessionKey(sessionId, generation, executionId)` 分发请求到正确的 PTY 实例，严禁输出流或输入队列串线。

### 3.3 核心依赖验收门槛（Prerequisites for UI Wiring）

UI 接线启动的前提是协调者提供包含以下验证的核心提交：
1. 双会话并发启动测试（API29/36 各通过）。
2. 第 3 会话拒绝且不破坏已有 2 个会话的验证。
3. 会话 A 异常 Crash 时会话 B 存活并正常运行的隔离性验证。
4. 单写连接互斥与只读降级验证。

---

## 4. UI / ViewModel 架构设计

### 4.1 ViewModel 设计 (`ManualTerminalViewModel`)

```kotlin
internal data class MultiTerminalUiState(
    val sessions: List<ManualTerminal.State> = emptyList(),
    val activeSessionId: String? = null,
    val activeConnection: ManualTerminal.Connection? = null,
    val isWriter: Boolean = false,
    val isFull: Boolean = false, // sessions.size >= 2
    val busy: Boolean = false,
    val errorMessage: String? = null,
)
```

- **切换会话（Switch Tab）**：
  1. 调用当前 `activeConnection.detach()`（断开 UI 订阅，后台继续运行）；
  2. 切换 `activeSessionId` 为目标会话；
  3. 调用 `attachSession(targetId)` 接入目标会话视口，保留终端输出历史；
  4. 绝不重建 Shell 进程，保持零进程扰动。
- **新建会话（New Tab）**：
  1. 检查 `sessions.size < 2`；若已满则阻断并提示；
  2. 调用 `startSession(...)`；
  3. 自动将新建会话设为当前活跃会话。
- **关闭会话（Close / Stop Tab）**：
  1. 发起 `stopSession(sessionId)`；
  2. 待终态确认后调用 `settleSession(sessionId)`；
  3. 自动将前台视口切至剩余的另一个活跃会话，或恢复空态界面。

### 4.2 UI 交互设计 (`ManualTerminalScreen`)

1. **顶部多标签栏（Session Tab Bar）**：
   - 展现两个 Tab：`终端 1` 与 `终端 2`（标明各自所在相对路径及运行状态圆点）。
   - 右侧提供 `+`（新建）按钮；当活跃数已达 2 时置灰或隐藏，点击满额时弹出清晰文案提示。
2. **状态与只读标识**：
   - 若当前连接为只读观察者（`isWriter == false`），在视口顶部显示“只读观察模式”横幅，并禁用虚拟控制按键与软键盘输入。
3. **独立操作栏**：
   - Stop / Settle / Refresh 操作仅针对当前 Tab 选中的 `activeSessionId`，防止误触非活跃会话。

---

## 5. 测试映射计划（Test Mapping）

待核心代码与 UI 接线具备后，需执行以下完整的测试矩阵：

### 5.1 单元测试 (`testDeveloperDebugUnitTest`)

- `ManualTerminalViewModelTest`：
  - 双会话列表获取与状态派发；
  - 切换 Tab 时连接的 Detach 与 Attach 触发顺序；
  - 达到 2 个会话时 `isFull` 状态与 `+` 操作拦截；
  - 只读观察端状态下拒绝写调用的行为。

### 5.2 设备集成测试 (`androidTestDeveloper`) 映射至 `ProotMultiSessionDeviceTest`

| 测试用例名称 | 测试目标与验证点 |
| :--- | :--- |
| `dualSessionIsolationAndOutputNoCrosstalk` | 同时启动两个终端，分别执行不同脚本循环输出特征字符串，验证两个视口的输出严格隔离，绝无串线。 |
| `closeSessionADoesNotAffectSessionB` | Session A 正在编译/sleep，Session B 执行指令；关闭并 Settle Session A，验证 Session B 进程存活且不受影响。 |
| `rapidTabSwitchingPreservesOriginalShell` | 在两终端之间进行 20 次快速交替切换，验证底层 Shell PID 与环境变量保持不变，无重复创建进程。 |
| `thirdSessionRejectionWithDistinctFeedback` | 已有 2 个活跃终端时尝试启动第 3 个，断言返回满额错误，界面呈现明确提示，原有两会话稳定运行。 |
| `readOnlyObserverModeDisablesInput` | 同一会话发起第二个连接，断言其获得只读连接；验证写输入被拒绝，屏幕保持同步刷新。 |
| `processCrashRecoveryAndReconciliation` | 模拟后台 Runtime 异常终止，重启应用后进入终端，验证两会话均正确对账进入可清理状态，Settle 后可重新创建。 |
| `themeAndConfigurationParity` | 验证多会话 Tab 栏与视口在深色/浅色模式、系统日夜切换、旋转屏幕、大字体及中文/英文语言环境下的视觉适配与无崩溃。 |

---

## 6. 状态结论与交接说明

- **本工作包完成结论**：HXA-198 UI 准备、状态/操作表、核心接口需求及测试映射已全部完成并冻结。
- **当前登记状态**：**WAITING_CORE**。
- **后续接线条件**：协调者提供已在 dual-API 验证通过的核心提交后，再在干净工作树中开展具体 UI 代码编写，不得提前编造本地实现。
