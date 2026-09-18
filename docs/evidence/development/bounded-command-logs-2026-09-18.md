# 有界命令日志实现与验收边界

日期：2026-09-18。对应 HXA-195、accepted [ADR-RUNTIME-002](../../adr/runtime/002-terminal-and-jobs.md)。基于既有一次性 PRoot Job 与 HXA-194 详情页，未增加 Agent 工具或新的执行状态机。

## 实现合同

- 新增 `TX_JOB_LOG_READ = FIRST_CALL_TRANSACTION + 9`，日志子协议 v1，旧事务号不变。请求携带 Job ID、输入 manifest hash、可空游标；响应携带版本、游标、stream、bytes、EOF/truncated。服务端保留 UID 验证，核对持久 Job 的输入 hash，再校验游标内 Job/generation/有效偏移。
- 游标只指向完整分片边界；重复读取同一边界返回相同字节和下一游标，EOF/截断事实可随执行推进。跨 Job、过期 generation、非法偏移拒绝。未知事务、不支持版本或已失去 Binder 时显式不可用，不提交、重放或降级执行。
- 数据分片最多 8 KiB，客户端限制整个 Parcel 为 32 KiB。每 Job 队列最多 16 个分片、索引最多 4096 个分片；磁盘含记录头最多 4 MiB，最多保留 4 个 Job，总计不超过 16 MiB。只淘汰已结束日志；无可淘汰项则预览不可用。
- drain 线程仅做有界复制与非阻塞入队，独立线程写 spool。队列/磁盘/索引配额、低存储或写错误只截断预览前缀，不终止真实命令。读取、取消与 EOF 通过已提交边界协调；最终输出仍走原有归档与 manifest hash 验证。
- stdout/stderr 各自增量 UTF-8 解码，处理跨分片字符。两流展示文本合计最多 256 KiB UTF-16；达到上限后不继续追加，也不截断代理对。页面显示截断或不可用说明。

## 应用接线与恢复边界

`ProotJobClient` 仅在已批准提交被接受/识别为同一 Job 后，保存最多四份 Binder 引用与输入 hash；不持有额外 ServiceConnection。`ProotLogClient` 只使用这些引用，不绑定服务或启动进程。

详情页通过应用观察入口收集日志，只在 RUNNING 时展示预览，并低频重读既有持久结果投影；终态始终以原有结果投影为准。离开页面取消收集，Activity 重建从同一 Job 的预览起点重新读取。main 或 Runtime 进程死亡会失去预览连接；Runtime 再启动时清理旧临时 spool，不冒充断点续传。已有最终归档继续走原有显式对账路径。

日志分片不写消息表，不新增模型请求，不重复写工具结果，不由观察触发 ACK。consumer 的观察入口为空流，Runtime 模块及其日志类仍只随 developer 打包。

## 测试设计与失败修正

`JobLogSpoolTest` 覆盖重复游标、双流、慢写端/队列背压、Job/generation/偏移校验、低存储、淘汰边界、分片 UTF-8、展示文本上限、磁盘/微小分片索引上限与进程代际过期。

`ProotLogStreamDeviceTest` 使用真实 PRoot Job 验证运行中输出、取消尾部、Binder 死亡、不重放、双流 6 MiB 压力、协议版本/Parcel 边界，以及真实详情页在命令结束前显示输出。UI 场景使用真实提交与持久绑定 fixture，未调用付费模型；模型调用行数和工具结果行断言证明观察本身不产生模型交互。Activity 重建与 Runtime 进程死亡是不同用例，不混称主进程恢复。

初始压力用例在 API36（`head /dev/zero`）和 API29（内建 `printf`）均失败。定位为既有 Job 记录把输出字节计数误限为 journal 的 1 MiB 大小；修正后按原有 64 MiB 执行上限校验计数与合计，不扩大 journal 文档大小。详见[缺陷记录](../../bug-fixes/2026-09-18-proot-output-counter.md)。初始失败日志保留于 `build/195/device-api36/`、`build/195/device-api29/`，不计为通过。

## 证据范围

设备 runner、主机 G2 入口分别为 `scripts/debug/2026-09-18/run-log-matrix.sh`、`verify-log-host.sh`；每次启动独占模拟器、记录制品 hash/进程身份，并关闭自有进程。最终实际结果与命令在交付记录中登记。

`bash scripts/debug/2026-09-18/run-log-matrix.sh` exit 0；四组均 arm64、4 核/4096 MiB，均 `closed.json` exit 0：

| API / AVD | 测试与实际结果 | 原始证据 |
| --- | --- | --- |
| 29 / HelixApkUpgrade_API29_20260918 | 新增日志 6/6，18.518s | `build/195/device-api29-final/` |
| 36 / HelixApkUpgrade_API36_20260918 | 新增日志 6/6，32.304s | `build/195/device-api36-final/` |
| 29 / 同上对应 AVD 的新进程 | 原有 Runtime 35/35，36.671s | `build/195/integrated-api29/` |
| 36 / 同上对应 AVD 的新进程 | 原有 Runtime 35/35，48.662s | `build/195/integrated-api36/` |

四组同一制品：developer debug APK SHA-256 `d4a94265d0fd9d71ecb3ddc3ff2da6bc8e08dd17574ffc240f932acd16c18ee0`；AndroidTest APK `5efaa18b3732d919fd1c4f779296dda8419a563bd5a46c6a97283919fdedb6b4`。每组文件夹各保留 APK、hash、owner、配置、instrumentation、logcat 与关闭记录，不复用旧进程。

Runtime 回归中的订阅测试使用既有确定性 fixture，不能计为真实账号登录或付费调用。日志压力断言验证的是 6 MiB 真实输出；64 MiB 计数上限只通过 codec 单元测试。

本任务只验收 developer 的一次性 Job 日志。未执行 OEM 真机、低内存/Doze/长稳、真实订阅账号或签名发行验收；4 核/4 GiB 模拟器通过也不能替代这些条件。没有实现后台 Job、PTY、手动终端或多终端会话。
