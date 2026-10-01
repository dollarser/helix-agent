# HXA-238：执行并发与 Provider 表单本地交付（2026-10-02）

## 基线与范围

在 `main@a174fe3a8764d8a0c4d16e82c862496fb47d261b` 加既有 HXA-238 未提交工作上继续。所有者明确要求：只为系统/引擎/内部状态稳定性保留必要同步，不为用户文件和业务结果正确性构造执行锁；业务结果由用户与 LLM 验收。追加 Provider 表单溢出提示和保存失败原因。保护 FFmpeg、设备调查和其他并行材料；本轮不提交、不推送、不发布。设备、模拟器和真实服务均 **not requested**。

## 实现与实际边界

| 入口 | 当前行为 |
| --- | --- |
| ExecutionOwnership / 持久 store | 原单 owner 改为原身份集合；普通调用不读取 owner 来决定空闲。终端、后台/未知身份和待收取结果不阻挡无关工具。相同 execution/generation 的控制与结算保持互斥，旧回执不能移除其他身份；提交前确定失败仅回滚自己。旧 v1 身份保留读取，不靠丢弃记录实现放行。 |
| Scheduler / Dispatcher | 写入、代码、Root/UI 类别、用户路径和网络 origin 不再形成全局屏障；仍校验 schema、权限、审批和固定绑定，结果按原模型调用顺序回填。依赖前一结果的操作由模型放到下一 batch。 |
| 原生与独立 JS | 原生 QuickJS 单服务的执行/死亡协议保持本引擎协调；独立 JS、Bash、time.now 不受它的持久身份阻挡。取消返回不等于物理线程已经退出。 |
| PRoot | 最多四个独立 Job 与两个终端并存；执行队列/日志有界、Job 日志独立排空、共享 journal 短同步、Runner 唯一实例。Runtime 环境修复/删除必须避开在用进程。 |
| Job 生命周期 | 每个后台任务各有 admission；终态元数据先到、物理清理尚未结束时，只保留该 Job 的物理槽与后台服务。其他空闲槽继续执行；完整结果收取与物理容量分离。 |
| UNKNOWN | 原未知事实与不重放约束保留；普通新用户请求按正常权限，不再因历史 UNKNOWN 全会话禁止写入或降级 Goal。自动恢复核查仍是原授权的只读请求。 |
| Provider 表单 | 溢出时常驻滚动条和“向下滚动查看更多设置”；保存只在保存/获取目录期间禁用。缺名称、地址、模型、半填请求头、HTTP 确认均显示对应原因，自动展开/定位字段；错误在滚动区外可见，重复保存会重新定位。API Key 可选。 |
| Provider 模型选择 | 手填模型与勾选项不一致、列表过多或非法 ID 有专门反馈，不再落为泛化存储错误；实际存储失败另报保存失败，不显示密钥或原始异常。 |

取消全局锁不是开放无限进程、改变权限或取消不可重入引擎约束。用户同时修改业务文件可能影响产物，Harness 不承诺替用户验证业务正确性；内容存储原子发布、审计/预算事务、IPC/原身份校验仍是系统完整性责任。

## 失败、修正和测试范围

延续日志保存在 `build/execution-concurrency/`。`focused-r1/r2/r3.log` 是旧中间候选的失败，不能删除或写成通过：包括格式、旧 guard 签名、旧全局锁测试预期、并发测试错误依赖审批回调顺序及非线程安全测试集合。修正契约预期时保留每调用独立审批、原固定绑定失效、取消和异常回填断言；未关闭测试或降低权限校验。

本次 `focused-r4.log` 退出 0，覆盖 Framework/PRoot Core/双渠道 App 单测。完整 `host-r1.log` 随后发现格式与 detekt 门禁，已做局部修正；generic catch 仅用于失败不开放准入或清理后重抛，不将异常报为成功。

复核另补：Runner 并发构造不生成竞争池；后台服务不能仅凭终态记录撤销；模型选择错误明确定位；重复保存重新滚动和聚焦。新增主机测试包括原生引擎/独立执行隔离、多 owner 控制独立性、四槽竞争、环境维护、日志并行排空、持久身份集合及旧身份读取、表单和滚动条边界。设备用例涵盖终端存活时 Agent 的启动/时间读取/等待/收取/哈希旅程、多后台任务取消不相互误伤和 Provider 错误定位；编译不能算设备执行。

### 追加主机门禁：浏览器测试服务器

第二轮完整主机 `host-r2.log` 中，原重定向循环测试实际返回 TIMED_OUT（30.020 秒），而非预期 REFUSED。生产 BrowserDownloader 未改动。检查到测试 MiniHttpServer 只有单个接收线程、已接受 socket 没有读取超时、只读请求首行便关闭连接。新增空闲连接反例在修复前稳定返回 TIMED_OUT（30.036 秒），见 `browser-fixture-before.log/.xml`；这是测试基础设施的可复现阻塞，不等同已取证原失败当时的每个 TCP 事件。

修复仅在测试 fixture：绑定 loopback，socket 读取有界，读完请求头才响应/关闭，关闭 fixture 时回收已接受 socket 并确认服务线程退出。没有增大生产超时、放宽重定向限制或跳过原断言。修复后与完整回归结果分别归档。

## 最终验证

完整主机命令：`bash scripts/debug/2026-10-02/validate-execution-concurrency.sh`，共享 host slot 中执行全仓 `test detekt spotlessCheck`、双渠道 lint/Debug APK/AndroidTest APK。完整源码指纹用现有 `scripts/run-agent-eval.py provenance` 生成，输入为 app/core/provider/runtime/tools/extensions/feature，不重复建设代码索引。

最终 `host.log` **退出 0**：`BUILD SUCCESSFUL`，989 tasks，36 executed / 953 up-to-date。全仓单测、detekt、spotlessCheck、双渠道 lint、Debug APK 与 AndroidTest APK 均在这组命令中；这是增量整合，不是全量强制重跑。原 AndroidTest 用例仅编译，未执行。

期间 `host-r3.log` 出现六个 lint 模块的 `com.intellij.ide.plugins.PluginEnabler` 初始化异常。验证脚本局部使用 Gradle 官方 `--no-parallel --max-workers=2 --stacktrace` 后完整通过；没有跳过 lint、改变生产并发、修改全局 Gradle 配置或终止他人的 daemon。确认的是此主机条件下可通过，未取得足以确定上游初始化异常根因的证据。

`source.log`：源码门禁通过，691 份 Markdown、221 项 HXA 索引、35 份 ADR、1941 个主资源键以及秘密扫描。`artifacts.log`：consumer/developer APK 的组件、进程/UID、载荷、启动入口、渠道和订阅排除边界通过。`git diff --check` 通过。另用 `summary.json` 保留实际测试报告、时间戳和制品 SHA，不合并为设备通过。

| 重点报告（共享 App 场景按一个渠道计） | tests / failures / errors / skipped |
| --- | --- |
| ExecutionConcurrency / ExecutionOwnership / Cancellation | 3 + 19 + 2 / 0 / 0 / 0 |
| EffectFootprint / ToolScheduler | 11 + 23 / 0 / 0 / 0 |
| RuntimeExecutionCapacity / JobLogSpool | 6 + 10 / 0 / 0 / 0 |
| ProviderFormValidation / ExecutionOwnershipStore / RuntimeOwnerDiskRecovery | 7 + 5 + 2 / 0 / 0 / 0；两渠道均通过 |
| TerminalStartTransaction / ForegroundProotOwnership | 9 + 2 / 0 / 0 / 0 |
| BrowserDownloader | 12 / 0 / 0 / 0 |

上述为重点报告，不把原测试改名/契约调整算作新增；全仓原有条件性跳过仍按报告保留。浏览器空闲连接反例修复后耗时 1.026 秒、原 redirect-loop 0.002 秒，未改生产下载超时。

源码前后清单一致：`6172850dd6d746edbe7a67350514c365a630d7c6c890c33ec4a97783e7e8a1ca`。四个 APK 保存在项目 `app/build/outputs/apk/`，不是发布件：

| 制品 | SHA-256 |
| --- | --- |
| consumer debug | `26c90a5600827c1b9b2ec83d08e8cdface589e883b7859d34b41a372004e1fa1` |
| consumer AndroidTest | `2908317121b016db823b54999e80fb2096a7c90826a229d9be0a4e19705afd04` |
| developer debug | `edef85ef39242e3f5c67ac615e02349f071cecea4844e1260517627d6e4f03d2` |
| developer AndroidTest | `b74e83fd16a2fa4028ad3792ba542c1e2bd49fc25631549f3b83e64b545234ae` |

## 剩余验收

HXA-238 已完成本地主机交付，转收尾验收。指定设备仍须运行：终端+JS/Bash+后台 Job 共存、取消/退出/容量回收、Runtime 进程死亡后的原身份恢复、窄屏/大字体/键盘情况下表单滚动条与缺项定位。真实模型验证其主动安排依赖与产物验收能力；这不是主机断言可证明的收益。资源容量为当前有界配置，不是已取得所有设备压力数据。

J2-1 AUTO、J2-2“继续在后台”及完整 Project Memory 仍属后续独立工作，本轮未实现；完整 OEM/故障矩阵、签名与发行同样未关闭。当前手机已安装 v0.0.4 不会因工作树修改自动更新。

## 实战参照

2026-10-02 重新核对 [Claude Code 后台 Bash 与交互](https://code.claude.com/docs/en/interactive-mode#background-bash-commands)：采用任务身份与继续交互分离，不复制其后台策略。[Android 输入校验](https://developer.android.com/develop/ui/compose/quick-guides/content/validate-input)采用错误状态及可纠正提示；字段定位使用项目既有 Compose `BringIntoViewRequester`/`FocusRequester`。不复制竞品代码、不新增依赖，也不把外部实践当作 Helix 设备验收。
