# HXA-222 Qwen3 真实模型定向实测（2026-09-28）

历史失败轮次；后续资源/UI 修复、1.7B 失败对照和 4B 完整通过见[收口证据](hxa222-closeout-2026-09-28.md)。不以最终成功覆盖本记录。

本轮所有者授权运行小型量化模型及稍长流程任务。仅使用独占 API36 arm64-v8a 模拟器（4 GiB / 4 vCPU），consumer debug；未使用真机、外部账号或付费接口。推理期间关闭模拟器 Wi-Fi / data，模型通过 ADB 拷入应用私有资产目录。

## 模型与任务

- `unsloth/Qwen3-0.6B-GGUF / Qwen3-0.6B-Q4_K_M.gguf`，396,705,472 bytes。
- SHA-256：`ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a`。
- 原 Hugging Face 与 hf-mirror 连接失败；从 [ModelScope 同名仓库](https://modelscope.cn/models/unsloth/Qwen3-0.6B-GGUF) revision `6091bc857fe0dffa19c581a7ccc7def1b126ff54` 下载，校验结果与原发布文件一致。
- GGUF v3，架构 qwen3，28 blocks，元数据 context length = 40,960。当前 Helix runtime cap = 32,768；初轮使用 32,768，失败后以 8,192 重跑。2 inference threads，greedy，reasoning OFF。
- 使用真实 `LocalModelProvider → Binder/JNI llama.cpp → ChatService/AgentLoop → Dispatcher → Room`。仅对新建合成数据会话启用 `read` / `write`，FULL_ACCESS 仅限该测试会话。工具能力为本次 eval 手动配置，**不冒充 capability probe 通过**。
- 输入 `orders.csv`（East 120+30，West 80+40）、`returns.csv`（East 10，West 20）。任务：读两表、分区汇总、写 `totals.csv` 与 `report.md`、回读两个结果并核验。期望净值 East 140 / West 100 / 总计 240，至少六次真实工具调用。

## 已确认结果与修复

1. r1：API36 安装失败，新增 `:model-runtime` 非法进程名触发系统 manifest 解析错误；改为 `:model_runtime`，随后安装通过。
2. r2：验证命令误用了默认 AndroidJUnitRunner；改为仓库 `HelixAndroidJUnitRunner`。该轮不是测试通过。
3. r3：Binder 边界用例通过，fake loop 用例清理阶段先取消 scope 再删除 Provider，触发 JobCancellationException；修正清理顺序。
4. r4：Binder 边界与 fake production loop **2/2 通过**。真实模型 32K load 失败，系统 lowmemorykiller 杀死 `:model_runtime`（日志报告 RSS 2,733,524 KiB / swap 85,248 KiB），客户端返回 `LOCAL_RUNTIME_CRASHED`。没有模型任务通过证据。
5. 模型 metadata 查询此前会将已加载 context 重置为 2048；已修正为保留同一模型当前 load request，真实模型测试断言 context 与 warm handle 不变。
6. r5：8K load 与 warm handle / metadata 断言通过，load + 校验约 7,163 ms；未优化 debug native 内核约 7 分钟仍未结束首个调用，主动 force-stop 测试应用结束该轮，不能算自然崩溃或任务通过。实际 Ninja flags 确认没有优化参数；CMake 为 Debug native 添加 `-O2`，保留 debug symbols / assertions，重新编译后进行 r6。该差异不能推导为真机性能比值。

## 优化构建 r6：多步任务未通过

基础设备 suite **2/2 通过**。真实模型用例 **0/1，通过条件未满足**：

| 项目 | 实测 |
| --- | --- |
| load + metadata / warm handle 校验 | 2,115 ms；同一 handle 复用，context 保持 8,192 |
| 整轮 elapsed（含 load/setup） | 204,000 ms |
| Room Turn | `FAILED`，`errorCode=PROTOCOL`，stepCount=4 |
| 模型调用 | 4 次；前三次 COMPLETED，第四次 FAILED |
| 工具执行 | `read → read → write → read`，4 次均 durable COMPLETED |
| 前三次模型调用耗时 | 15,278 / 19,914 / 22,110 ms，包含 prefill 与 decode，非首 token 延迟 |
| 前三次 usage | input 1,350 / 1,731 / 1,952；output 77 / 91 / 75；第四次没有 usage，不能按零计 |
| 10 秒采样峰值 | PSS 1,394,692 KiB（约 1.33 GiB），RSS 1,500,188 KiB；不是瞬时绝对峰值 |
| 产物 | `totals.csv` 存在但错误；`report.md` 未生成，未完成双产物回读 |

模型写出的内容：

```csv
region,orders,returns,net
East,120,10,20
West,80,20,60
```

正确结果应为 `East,150,10,140`、`West,120,20,100`，grand net = 240。任务既没有达到六次工具调用及报告要求，也没有通过数据正确性验收。前三次模型输出经过真实 Dispatcher；相对路径已规范化为当前 session scope，结果进入后续模型请求，Room request manifest 记录了回填链。

第四次调用以 Provider `PROTOCOL` 失败收口，原始事件记录在该次 START 后没有正常 terminal；现有证据尚不能单独区分截断工具输出与其它 codec 协议拒绝，不将其直接归因于模型质量。算术错误则已由实际文件独立证实。未放宽协议校验或修改预期值使测试通过。

32K 是较早未优化构建的配置容量测试，并没有填满 32K 输入；优化构建本轮仅跑 8K。模拟器表现不是手机性能或温升结论。真实链路已运行，不等于完整模型任务通过；本次手动工具能力也不是正式 capability probe 的替代。

## 本轮 host 验证与 APK

- `:provider:api:test :app:testConsumerDebugUnitTest spotlessCheck detekt` 通过。
- consumer/developer debug APK、AndroidTest APK、两渠道 debug lint 通过；native `-O2` 调整后两渠道 debug APK 重建通过，consumer 在 r6 实际运行。
- `check-all.sh --source` 与 `git diff --check` 通过。没有重跑历史完整 `--all`，历史 release 制品 hash 不代表本轮修改后的 APK。
- r6 consumer app SHA-256：`53063d6be72949f51b91c275ec4469899ef02a465c6139fd9589f6a2c32e8e3d`。
- r6 AndroidTest SHA-256：`332d22acf5faa99e9bf86e5d5801e84882588a7294ac832f642a2b2766092994`。
- API36 fingerprint：`google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`；r6 serial `emulator-5674`。runner 已关闭所有本轮实例，结束时 ADB inventory 为空。

## 复现与制品

- 下载与核验：`python3 scripts/debug/2026-09-28/prepare-local-model.py`。
- GGUF 元数据：`python3 scripts/debug/2026-09-28/inspect-gguf.py`。
- 设备入口：`scripts/run-owned-emulator.py`，主 suite 为 `LocalModelRuntimeDeviceTest,LocalProviderLoopDeviceTest`，`--after-script scripts/debug/2026-09-28/run-real-model-followup.py` 执行真实权重任务。
- follow-up 默认 context 8,192，可通过 `HXA222_CONTEXT=32768` 显式切换。结果摘要：`python3 scripts/debug/2026-09-28/summarize-real-model.py build/hxa222-real-model/api36-r6`。
- 真实模型用例 `LocalModelRealTaskDeviceTest` 需要显式 instrumentation 参数 `realModel=true`，可设置 `contextTokens`；普通 suite 不下载模型。固定资产缺失/损坏在显式执行时失败。
- 原始模型、APK hash、设备属性、instrumentation、模型事件、Room 轨迹及内存采样位于 ignored `build/hxa222-real-model/`。所有模拟器由 runner 独占启动并关闭，保留 owner / closed 记录。

HXA-222 仍开放：后续应定位第四轮 codec 拒绝的具体原因，评估采样/模型选择对同一任务的影响，再补正式 capability probe 与完整正确性轨迹。取消延迟、正常 unload 回收、真机热/长稳及 UI 验收未覆盖。本轮不是完整 HXA、完整 device baseline、真机或 release 验收；未提交或推送。
