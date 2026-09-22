# 2026-09-22 审查探针

当前复核基线 `9a9b25dd`，历史基线 `645fa680`。详见[深度报告](../../../../docs/research/execution-engine-deep-review-2026-09-22.md)。仅用于审查，不修改业务源码，不访问设备、真实账户或外部服务。

当前Admission探针断言取消清理期保留owner，**PASS表示R1修复不变量成立**；其他四项仍是诊断断言，PASS表示旧行为复现，其中Storage使用DAO替身，不代表当前Stop路径有同样竞态。修复后应将相应不变量转成生产回归；不能把本目录直接加入CI并永久要求缺陷存在。原Admission诊断和原报告已保存在本轮整理前的ignored快照及历史stash中。

- `prepare-review.py --output build/<新目录>`：记录HEAD、生产/测试/构建文件及探针SHA256；拒绝复用已有基线。将探针与byte-identical的SessionTurnAdmission副本放入该目录的sources。
- `review.init.gradle`：仅为一次Gradle调用追加测试源路径；`-PreviewOutput=build/<新目录>`必须与prepare一致。
- `AdmissionReviewProbe.kt`：真实准入类与可控 coroutine 清理。
- `SchedulerReviewProbe.kt`：真实 Scheduler/Dispatcher；屏障测试跑完整批次，漏唤醒测试通过反射构造组件交错，后者不等于端到端压力测试。
- `StorageReviewProbe.kt`：真实 TurnRepository + 无条件更新 DAO 替身，不声称运行 Room SQLite。
- `ProviderReviewProbe.kt`：真实 OpenAiChatProvider + 不发 EOF 的内存 WireBody。
- `verify-review.py --output build/<新目录>`：校验源码/探针快照、XML时间和数量、失败/错误/跳过并保存输出。只有对应Gradle任务成功结束且verify exit0才构成本次证据；旧build XML不是新验证。
- `architecture-scan.py`：清点显式模块依赖和 app 顶层包 import 强连通分量；不是 Gradle variant 解析或运行调用图。

运行命令、结果与未覆盖边界统一记录于深度报告。重新检验另一源码版本时，应使用新的独立输出目录/快照；不要覆盖原始基线伪装成“无代码变化”。

Probe sources are stored as `.kt.txt` diagnostic templates, outside normal production source scanning. `prepare-review.py` copies them byte-for-byte to `.kt` files in ignored build/ for the explicit probe test tasks; no diagnostic test is removed.
