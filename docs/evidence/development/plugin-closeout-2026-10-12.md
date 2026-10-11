# 插件收尾：日常 APK 保留数据更新与完整文件任务

日期：2026-10-12。范围：HXA-272～275 索引收口、Owner 指定日常 API36 更新，以及自有 API36/既有 Qwen 的合成文件任务。本记录不代表 HXA-276～279 已交付。

## 授权与制品

Owner 本轮允许模拟器和既有真实模型测试，要求日常更新保留数据；另明确允许本次备份后的非破坏性数据库转换。生产数据库仍使用现行开发 baseline，没有新增迁移、兼容 adapter 或自动保留旧库逻辑。未操作真机、第三方账号或外部通信。

日常目标经 ADB 核实为 `Helix_API_36` / `emulator-5554` / API36，包 `com.helix.agent.developer`。更新的是 HXA-275 已验收 Developer APK，SHA-256 `b59dcca32ed5800fad72f32ff649185a564944c25eeb71864b5aa130a436a8aa`；原 APK SHA-256 `d445bd832577c2d8be9aada1f1e16026183a0b5b9dace6dcf405af647d1a5e6a`。两包显示版本均为开发预览 `0.0.5`，以哈希区分安装身份。

## 日常更新与数据核验

安装前核对 5 个 Turn 均 `COMPLETED`，没有 Goal 或在途工具。保留原 APK 与 SQLite 一致备份；旧 identity `c34065205d0987bb3b495a2f4a663c43` 与当前 `eb439f0c60ced11912eaac8b50dada5b` 不同。

仅在本次模拟器副本新增 `session_inputs.skillActivationsJson`（`[]`）和 `skillActivationsRevoked`（`0`）。对当前 Room schema 的 52 张业务表核对列名/类型/非空/主键、外键、索引；转换前后所有原字段记录摘要一致，SQLite integrity 与外键检查保持。新增列没有激活任何历史输入。

首次采用 SQLite backup 回写并去除 Room metadata 后启动，应用出现空库，**该尝试失败**；原转换副本仍完整，首次启动库和相关文件保留在私有本地证据中。没有据此声称 Room 重建问题已修复或断言其根因。

随后停止应用，将已核验结构的副本补入当前 Room metadata，原库及 sidecar 移入本次本地恢复目录，再替换数据库文件。新 APK 启动后核验：

- 51 张表的原字段记录摘要逐表一致。
- `connector_installations` 的两条原生记录仅 `revision +1` 与 manifest 内 `revision/hash` 改变；身份、选择默认值及其他字段保持，属于新进程正常组件发布。
- 2 个会话、5 个 Turn、144 条消息、6 条 Provider 配置保留；新增输入字段均为空数组/false；`PRAGMA integrity_check=ok`。
- 从设备重新读取的 APK 哈希与上述验收包一致；MainActivity 已恢复前台，定向 AndroidRuntime 日志未出现崩溃。

原 APK、数据库副本、逐表摘要及验证结果仅在 ignored `build/closeout-2026-10-12/`，不入库。一次性转换脚本仅在 ignored 当日本地调试目录；它不是生产升级入口。

## 完整文件任务

新增可重复夹具 [PluginFileWorkflowModelDeviceTest](../../../app/src/androidTest/kotlin/com/helix/app/plugin/PluginFileWorkflowModelDeviceTest.kt)。只使用自有合成会话、Provider、插件与工作区，清理由夹具负责；没有读取日常会话或配置。生产标准包解析、安装、Skill enablement、会话选择和权限服务配置均实际执行，所有模型动作仍经过现有 Dispatcher。

固定任务：发现/读取区域汇总 Skill；先读取不存在的主订单文件，按用户提供的 fallback 恢复；读取订单与退货 CSV；写入 JSON 汇总；读回输出，最后由夹具独立解析数字。

冻结应用仍为上述 HXA-275 APK；自有 `Helix_HXA271_API36` / API36 Developer，既有 loopback `Qwen3.8-27B`，服务提供的窗口为 262144。使用 `run-owned-emulator.py`、仅转发指定服务端口、仪器参数明确指定模型；不启用生产双策略。通过轮输出 `build/closeout-2026-10-12/file-workflow-model-3/`，测试 APK SHA-256 `1b5f900f5cfa3b59b01d1553ee16f1159cdb9a4cc11b30ad6957f31b9543a5d1`。

结果：**1/1 passed，0 skipped**，28.792 秒。持久工具轨迹为 search 成功、主文件 read 失败、skills.list 成功、fallback read 成功、skills.read 成功、returns read 成功、write 成功、输出 read 成功，共 8 次。独立验收 `East=140, West=100, total=240`。最后的输出读取位于成功写入之后；没有以模型的完成文字代替文件验证。自有模拟器进程退出已记录（PID 33176，exit 0）。

失败尝试保留：第一轮测试清单漏 `$schema`，在解析阶段失败，尚未进行模型任务；第二轮夹具对工作区根路径错误添加前导斜线，模型恢复路径后测试断言失败。修正标准清单与合法 scope 引用后重跑，没有放宽输入读取、失败恢复或读回验收要求。该样本只证明本任务，不证明一般成功率或完整 Mobile Use/Browser 真实后端。

## 索引与边界

两份仍写“272～279 未开工”的索引已改为 272～275 有界本地交付，276～279 按当前授权推进。完整文件旅程和日常更新已完成；后续 Browser 贡献、资源停止/释放、上下文渐进装载与观察工具仍各自验收，不由本记录关闭真机/OEM、长稳或真实账号边界。
