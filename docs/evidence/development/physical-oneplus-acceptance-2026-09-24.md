# OnePlus 6T 物理真机全面回归与功能验收记录

日期：2026-09-24。
执行环境：独立物理设备 OnePlus 6T（序列号：`561e3b15`）。
执行脚本：
- [`scripts/debug/2026-09-24/run-physical-tests.sh`](../../../scripts/debug/2026-09-24/run-physical-tests.sh)
- [`scripts/debug/2026-09-17/p0/run-device.py`](../../../scripts/debug/2026-09-17/p0/run-device.py)
- [`scripts/debug/2026-09-24/run-physical-acceptance-20260924.py`](../../../scripts/debug/2026-09-24/run-physical-acceptance-20260924.py)
- [`scripts/debug/2026-09-18/verify-196-owner-death.py`](../../../scripts/debug/2026-09-18/verify-196-owner-death.py)

原始产物：`build/physical-acceptance-20260924/` 与 `build/p0-*/`（受 `.gitignore` 保护）。

---

## 1. 物理设备硬件与基线状态

通过 USB 连接的真实物理手机，经 `ro.kernel.qemu != 1` 确认非模拟器：
- **设备型号**：OnePlus 6T（`ONEPLUS A6013`）
- **制造商**：`OnePlus`
- **系统版本**：Android 14（API 34）
- **CPU 架构**：`arm64-v8a`
- **内存页大小**：`4096` 字节（4 KiB 标准页面）
- **电池状态**：100%（充电状态），温度 29.9°C（安全温控范围内）
- **Root 环境**：已探测存在 `/system/bin/su`，支持开发版 Root 调度器与受控提权交互

---

## 2. 真实物理测试批次与执行证据

### 批次 A：Core Storage 完整闪存读写与数据库迁移（`core:storage`）
- **Runner**：`com.helix.core.storage.test/androidx.test.runner.AndroidJUnitRunner`
- **验证范围**：
  1. `RoomMigrationFixtureTest`：真实物理 UFS 闪存上的 Room 1..28 完整迁移链、Schema Export 校验及外键约束支持。
  2. `RequestContextManifestDeviceTest`：Schema 27→28 物理迁移（`requestManifest` 列与级联删除外键约束在真机 SQLite 上的生效）。
  3. `ConnectorMigrationDeviceTest`：Schema 26→27 连接器配置迁移。
  4. `SessionInputStorageDeviceTest`：并发 FIFO 队列、状态机与截断。
  5. `PrivacyDeletionDeviceTest`：物理删除与级联清理。
  6. `HelixDatabaseFkDeviceTest` / `ContentStoreDeviceTest`：全表外键完整性与大文件闪存存取。
- **执行结果**：**96 项测试全部通过（96/96 PASSED）**，耗时 12.378s，0 失败。

### 批次 B：P0 核心能力与端到端交互（`app:developer`）
- **Runner**：`com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner`
- **验证范围**：
  1. `GoalDeletionDeviceTest`：Goal 目标级联清理与会话解除。
  2. `GoalReminderNavigationDeviceTest`：Goal 提醒前台跳转与 Intent 校验。
  3. `GoalReminderPublicationDeviceTest`：Goal 通知管道与渠道物理投递。
  4. `GoalReminderTest`：提醒触发器。
  5. `SystemCapabilityResolverTest`：物理设备硬件能力与系统权限解析。
  6. `AttachmentE2eDeviceTest`：多附件真实存储路径解算、散列对账与读取。
- **执行结果**：**52 项测试全部通过（52/52 PASSED）**，耗时 54.120s，0 失败。

### 批次 C：Root 调度与生命周期（`app:developer`）
- **验证用例**：`com.helix.app.root.RootLifecycleDeviceTest#realAppDispatcherHonorsRootScopeAndToolDisable`
- **验证范围**：通过真实 UI 申请 Root 授权，启动受限 `/system/etc` scope 会话；验证 5 种系统只读与受控探针工具正常执行，越界读取被拒，会话禁用工具返回 `TOOL_DISABLED`，断开后旧调用失效。
- **执行结果**：**1 项测试通过（1/1 PASSED）**，耗时 6.182s，0 失败。

### 批次 D：存储 AppOps 权限生命周期（`MANAGE_EXTERNAL_STORAGE`）
- **测试类目**：`AllFilesDeviceTest`, `ManualSharedFileDeviceTest`, `SharedStorageDeviceTest`
- **验证范围**：
  - `granted` 阶段：真机授予 AppOp `allow`，验证所有文件范围读取、手动共享文件授权与隔离。
  - `revoked` 阶段：真机撤销 AppOp `ignore`，验证手动 Root 移除且 Agent 不越权。
- **执行结果**：
  - Granted Phase：**9 项全部通过（9/9 PASSED）**。
  - Revoked Phase：**1 项全部通过（1/1 PASSED）**。

### 批次 E：Connectors 生命周期与通道边界（HXA-129）
- **测试类目**：
  1. `ConnectorLifecycleDeviceTest`（多版本原子安装与替换，7项）
  2. `ConnectorSessionPanelDeviceTest`（会话面板物理交互，1项）
  3. `ConnectorCatalogMigrationDeviceTest`（目录迁移，3项）
  4. `ConnectorSendBoundaryDeviceTest`（调度边界，2项）
- **执行结果**：**13 项测试全部通过（13/13 PASSED）**，0 失败。

### 批次 F：PRoot 独立后台任务与主进程死亡存活（HXA-196）
- **测试类目**：
  1. `ProotDetachedJobDeviceTest`：真实 PRoot 后台 Job 执行（10项）：
     - 带租期后台 Job 运行与前台切换 HOME 后继续存活并返回验证输出；
     - 重复提交幂等防重放与跨会话绑定隔离；
     - 租期超时回收与强制终止进程；
     - 预算耗尽前置拒绝与绑定中取消；
     - Runtime 异常死亡对账。
  2. `ProotDetachedOwnerDeathDeviceTest` + `verify-196-owner-death.py`：
     - 在非 instrumentation 的真实主进程中启动带租期的 Detached Job；
     - 向主进程发送硬性进程终止（SIGKILL，模拟系统因内存压力强杀主进程）；
     - 宿主探针验证 `:proot` 独立进程 PID 未变、后台命令继续运行并在指定预算内成功完成（`SUCCEEDED`），最终持久化终态证明与输出摘要哈希（`outputManifestSha256`）。
- **执行结果**：**12 项测试全部通过（12/12 PASSED）**，0 失败。

---

## 3. 验收数据汇总

| 批次 | 测试套件 | 验证范围 / 关联 HXA | 通过数 | 失败数 | 状态 |
| :--- | :--- | :--- | :---: | :---: | :---: |
| **A** | `core:storage` | Room 1..28 迁移链、SQLite 外键、FIFO 队列、物理删除 | 96 | 0 | **PASSED** |
| **B** | P0 Focused | Goal 目标、系统能力解析、端到端附件 | 52 | 0 | **PASSED** |
| **C** | Root Lifecycle | 真实 Root 权限调度、分级 Scope、工具禁用 (HXA-095) | 1 | 0 | **PASSED** |
| **D** | Storage AppOp | `MANAGE_EXTERNAL_STORAGE` 授权/撤销动态切换 | 10 | 0 | **PASSED** |
| **E** | Connectors | 连接器多版本原子安装、面板交互、目录迁移 (HXA-129) | 13 | 0 | **PASSED** |
| **F** | Detached Jobs | PRoot 独立后台 Job、租期控制、主进程死亡存活 (HXA-196) | 12 | 0 | **PASSED** |
| **总计** | **全部物理真机批次** | **覆盖存储、能力、生命周期、Root 与后台运行时** | **184** | **0** | **ALL PASSED** |

---

## 4. 结论与后续计划

1. **物理硬件稳定性闭环**：OnePlus 6T 真机在最新代码基线上完成了全量 184 项硬件与系统集成测试，包括闪存读写、Room 28 数据库迁移、前后台生命周期切换、AppOp 授权撤销以及主进程强杀后的独立后台 Runtime 存活对账，无任何回归或崩溃。
2. **遗留问题修复**：修复了 `RequestContextManifestDeviceTest` 中的列名对齐与 SQLite 裸连接外键开启问题，以及 `RoomMigrationFixtureTest` 中对最新 live schema 28 的断言。
3. **后续计划**：
   - 物理真机 Doze 深度休眠长时间挂机巡检可在夜间由长耗时巡检脚本触发；
   - 16 KiB 页面验证仍待物理 16 KiB Android 15 设备上线接入。
