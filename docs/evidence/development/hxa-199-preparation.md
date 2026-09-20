# HXA-199 准备：终端专项证据汇总与报告契约

- **日期**：2026-09-20
- **基线**：main @ `d5645774`
- **执行角色**：小模型工作包 B（受控准备切片，不关闭整项 HXA-199 验收）

## 1. 终端专项场景映射（HXA-195 ～ HXA-198）

| 场景 | 对应模块 / 既有类 / 方法 | 状态 / 证据 | 测量维度与采样窗口 | 剩余依赖 / 待验条件 |
| :--- | :--- | :--- | :--- | :--- |
| **实时日志与停止** | `app/src/androidTestDeveloper/.../ProotLogStreamDeviceTest.kt` | HXA-195 完成记录 | 首包延迟 (ms)、取消延迟 (ms)，窗口 0~10s | - |
| **后台 Job 回收** | `app/src/androidTestDeveloper/.../ProotDetachedJobDeviceTest.kt` | HXA-196 完成记录 | 租期收紧 (ms)、退出码对账 | 物理真机长稳独立单列 |
| **单终端 REPL** | `app/src/androidTestDeveloper/.../TerminalHostJourneyDeviceTest.kt` | HXA-197 完成记录 | 冷启动 (ms)、1 MiB 缓冲区吞吐 | - |
| **双会话 PTY** | `app/src/androidTestDeveloper/.../ProotMultiSessionDeviceTest.kt` | **INCOMPLETE** | 独立 cwd/env、互斥单写入者 | 等待 HXA-198 双会话核心交付 |
| **切页与重连** | `app/src/androidTestDeveloper/.../ProotTerminalUiDeviceTest.kt` | HXA-197 完成记录 | 重连恢复延迟 (ms)、generation 校验 | - |
| **主进程 / Runtime 死亡** | `app/src/androidTestDeveloper/.../ProotOwnerProcessKillDeviceTest.kt` | HXA-197 完成记录 | 真实 PID 死亡、ORPHANED 标记、无重放 | - |
| **旧数据升级迁移** | `app/src/androidTestDeveloper/.../ApkReplacementUpgradeDeviceTest.kt` | HXA-193 完成记录 | 数据库 Room v22→v23 迁移、持久结果保留 | - |
| **Consumer 排除** | `app/src/androidTestDeveloper/.../IntegratedRuntimeDeviceTest.kt` | HXA-085 / check-all 完成记录 | Dex、Manifest、Asset 物理隔离断言 | - |

## 2. 硬件与长稳压力边界（严禁短测或静态对齐冒充）

以下专项场景必须显式跟踪，未进行对应物理运行时严格报告为 `skipped` / `pending`，严禁伪造通过：

1. **30 分钟 Detach Idle** (`detach_idle_30m`)：
   - 要求时长：`duration_seconds >= 1800`，记录 PID 存活与内存/FD 变化。
2. **2 小时完整租期** (`full_lease_2h`)：
   - 要求时长：`duration_seconds >= 7200`，记录单调时钟租期到期前后的精准终止。
3. **运行中到期终止** (`runtime_lease_expiration`)：
   - 验证到期瞬间进程组由 SIGTERM 渐进至 SIGKILL 的清理记录。
4. **OEM / Doze / 热压真机限制** (`oem_doze_thermal_pressure`)：
   - 记录机型、系统版本、Doze 唤醒锁与电池优化策略下的真实表现。
5. **真实 16 KiB 页大小设备** (`real_16k_pagesize`)：
   - 要求物理设备确认 `page_size_bytes == 16384`，严禁以 ELF 静态 16 KiB 编译对齐作为真实验收证据。

## 3. 验收脚本与契约执行

实现校验脚本：`scripts/verify-terminal-runtime.py`（复用 `scripts/acceptance_reports.py` 共享库）。
- 支持 `--help`（明确标明 owned-device 调度接线为后续集成职责）。
- 校验 8 大终端必跑场景及 5 大长稳硬件边界。
- 校验虚假时长或虚假页大小：若 `full_lease_2h` 时长不足 7200 秒、`detach_idle_30m` 不足 1800 秒或 `real_16k_pagesize` 不为 16384 字节，立即拒绝并报错。
- 双会话在当前阶段正确标记为 `skipped`（`WAITING_CORE`），生成 `FIXTURE_INCOMPLETE`，不为了合算 PASS 删除场景。

执行与自测命令：
```bash
python3 scripts/verify-terminal-runtime.py --help
python3 -m unittest discover -s scripts/tests -p 'test_terminal_reports.py'
python3 scripts/verify-terminal-runtime.py --manifest scripts/fixtures/acceptance/valid_terminal_fixture_manifest.json --output build/test_199_out
```

- 单元测试：8/8 PASS (0.042s)
- 合成夹具校验：Exit Code 0，正确标记 `FIXTURE_INCOMPLETE`。
- 本工作包完成仅代表报告契约就绪，不关闭 HXA-199 整体任务。
