# OnePlus 6T 物理真机回归验收记录

日期：2026-09-23。
执行脚本：[`run-physical-oneplus-regression.py`](../../../scripts/debug/2026-09-23/run-physical-oneplus-regression.py)。
原始产物：`build/physical-oneplus-2026-09-23/`（属于 ignored 构建目录）。

## 1. 物理设备硬件与环境信息

通过 USB 连接的真实物理手机，并经 `ro.kernel.qemu != 1` 确认非模拟器：
- **设备型号**：OnePlus 6T（`ONEPLUS_A6013`）
- **系统版本**：Android 14（API 34）
- **CPU 架构**：`arm64-v8a`
- **内存页大小**：`4096` 字节（4 KiB 页面）
- **设备状态**：电量 17%（充电中，温度 27.8°C 安全受控），非 Root

---

## 2. 物理真机测试执行与结果

### 批次 A：Core Storage 真实闪存读写与数据迁移（`core:storage`）
- **Runner**：`com.helix.core.storage.test/androidx.test.runner.AndroidJUnitRunner`
- **测试类目**：
  1. `ConnectorMigrationDeviceTest`（Room Schema 26→27 物理迁移验收）
  2. `SessionInputMigrationDeviceTest`（输入队列模式迁移）
  3. `SessionInputStorageDeviceTest`（真机 UFS 闪存上的并发 FIFO 队列、状态机与截断）
  4. `PrivacyDeletionDeviceTest`（物理删除与级联清理）
- **执行结果**：**OK (15 tests)**，耗时 3.656s。

### 批次 B：App Developer 独立后台任务与连接器生命周期（`app:developer`）
- **Runner**：`com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner`
- **涉及任务**：HXA-129（Connector 生命周期与会话启停）与 HXA-196（带租期的独立后台命令 Job）
- **测试类目**：
  1. `ConnectorLifecycleDeviceTest`（多版本原子安装与替换，7项）
  2. `ConnectorSessionPanelDeviceTest`（会话面板物理交互，1项）
  3. `ConnectorCatalogMigrationDeviceTest`（目录迁移，3项）
  4. `ConnectorSendBoundaryDeviceTest`（调度边界，2项）
  5. `ProotDetachedJobDeviceTest`（**HXA-196 核心用例**：带租期后台 Job 运行、超时取消、主进程中断对账、所有权校验，10项）
- **执行结果**：**OK (23 tests)**，耗时 49.701s。

---

## 3. 验收结论与剩余真机边界

- **通过项**：总计 **38 项真机测试**（15 项存储 + 23 项连接器与后台 Job）在 OnePlus 6T 上全部一次性通过（`ALL PASSED`），证实了 Room 27 迁移与 Detached Job 在真实 ARM64 物理硬件上的稳定性。
- **仍待物理验收的边界**：
  1. **Doze 深度休眠长时间挂机**：需在手机灭屏放置数小时后，验证系统的深度 Doze 维护窗口与租期恢复；
  2. **真实 16 KiB 内存设备**：本台 OnePlus 6T 为 4 KiB 页面，真实 16 KiB 硬件设备需在搭载 Android 15+ 的 16 KiB 编译内核设备上单独执行；
  3. **Root 提权控制台**：本台 OnePlus 6T 无 Root 权限，Root 相关失权测试需在拥有 Magisk/KernelSU 的已 Root 手机上进行。
