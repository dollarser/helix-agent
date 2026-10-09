# 通用工具发现提示与验证（HXA-252）

2026-10-10，基于 `5d3f89e0` 工作树。本轮所有者要求按工具发现建议优化，并明确授权 API36 与现有 Qwen 有界对照。旧 HXA-251 的 candidate 3/3、baseline 1/3 不计入本轮结果。

最终采用通用提示：有效三类各两轮严格验收候选 6/6、基线 5/6，主机与源码门禁通过；较新历史数据未找回后依所有者明确授权重建开发库，日常原 APK/Qwen/拼音恢复核查通过。下文保留环境与夹具失败的历史，最终结果以末节为准。

## 实现候选与主机结果

候选在 `prompts/base.md` 和 `McpToolDiscovery` 的模型可见描述中说明：缺少能力时组合检索、精确名称仅返回单工具、命中替换会话窗口、不在同一批并行多次搜索、下一请求实际 schema 才是调用依据、未曝光拒绝先重新发现、发现不授予权限、UNKNOWN 副作用先核查。没有加入固定 `linux job time await` 查询或固定 limit；没有修改搜索算法、窗口大小、授权或运行所有权。

新增跨模式装配/权限边界断言和真实组合搜索/分别替换/跨会话回归。新增 opt-in 文件归档模型旅程，独立核查一次归档、最终 Turn、ZIP 成员和原始内容，cleanup 不吞掉主失败。原手动/AUTO Job 旅程及其严格一次 start、原句柄、产物/结算断言保留。

- `:app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest` 通过；发现类各 24、提示类各 9，均无失败/skip。
- 全仓 `test detekt spotlessCheck lintConsumerDebug lintDeveloperDebug :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest` 最终通过，990 tasks/27 executed。日志 `build/discovery-guidance-20261010/final-host-r2.log`。
- `scripts/check-all.sh --source` 在最终文档增补后通过；日志 `source-gates-final.log`，776 Markdown、235 HXA、36 ADR、国际化和秘密扫描通过；`git diff --check` 通过。
- 初次命令误用了不存在的 `:app:spotlessApply`，改用根任务；首次全仓 detekt 指出新增单次旅程的长度与 Throwable/finally 处理，补充与已有 live journey 一致、说明保留主失败与清理失败的局部 suppression 后重跑通过，没有删断言或吞异常。

## API36 模型对照准备及环境边界

冻结基线、候选 App 和同一 AndroidTest APK 到 `build/discovery-guidance-20261010/`，身份在 `variants.json`。比较计划是三类任务（手动 Job、AUTO Job、归档）各组各两轮，共 12 轮，交替顺序；普通用户任务不增加实验检索提示。脚本为 `scripts/debug/2026-10-10/discovery-guidance-comparison.py`，仅作为 stable owned runner 的 follow-up，检查进程/serial/API/Room 身份和唯一既有 Qwen 配置，不使用备用账号。成功/失败分别保留，基础设施失败不能算产品成功。

模型请求前的历史环境尝试：

1. `Helix_API_36` 日常实例已在运行且可写，模拟器拒绝同 AVD 的第二只读进程。新进程 exit=1，未执行模型任务。日志 `api36-comparison/emulator.log`，`closed.json` 记录退出。
2. 改用独立 `Helix_Main_Verify_API36` 自有只读进程（API36 Developer）；两项只读 inventory 通过，但没有 Qwen 配置，follow-up 在任何模型调用之前失败停止，未使用订阅默认账号。进程正常关闭，exit=0；日志 `api36-comparison-r2/`。初始只读 DB inventory 是旧测试 AVD 的 schema，不表示当前源码 schema 或日常 DB 变化。
3. 日常已有 inventory 只读核对：Room identity=`c34065205d0987bb3b495a2f4a663c43`，当前生成 Room/跟踪 schema 同身份，唯一 Qwen3.8-27B 配置存在，active Turn=0；没有安装日常主 APK、导出配置、停止日常实例或调用模型。

**当时模型对照 pending，尚无本轮 12 轮结果。** 所有者随后明确允许暂时关闭并恢复日常实例；复查 active Turn=0、Room/Qwen 后开展第三次尝试，下面记录恢复失败，不将旧 3/3 或 inventory 当成本轮模型效果通过。当时生产提示仍是工作树候选，未提交或安装日常 APK。

## 恢复事件与当前阻塞

第三次独立只读实例安装在模型调用前被拒绝：冻结测试 App versionCode=1，日常安装 versionCode=4，`INSTALL_FAILED_VERSION_DOWNGRADE`；没有卸载、清数据或使用 downgrade 标志。

恢复脚本沿用原启动参数 `-no-snapshot-save`，未先保存停机前 live checkpoint，因而加载旧 default_boot 状态。恢复校验及时发现日常 APK 哈希不等于停机前 `710694153ed29a362bb76858828201ee99f0f7cc4ca534fbf38c5014eeb93802`。冷启动也未恢复较新状态；旧测试 APK 不含 inventory 类，直接 SQLite 只读查询得到 Room identity=`6e44f782ee05c9919fbc756c6354cf68`，10 sessions/19 turns，Qwen 配置仍存在。这是恢复流程失误，不是工具发现策略失败，不宣称原用户数据未受影响或日常实例已恢复。

已停止原 AVD 继续写入，在忽略的 `build/discovery-guidance-20261010/avd-recovery/` 保留 userdata/key 镜像的本地 COW 副本和日志；没有导出会话正文或凭据到版本库/外部服务。原 APK 已从既有 `refactor-closeout-20261010/api36/app.apk` 找到，SHA256 与停机前相同，但没有直接重装：当前旧 DB 与它的 Room schema 不同，启动可能触发开发期重建，不能用重装 APK 代替数据恢复。

参考 [QEMU qcow2 格式](https://www.qemu.org/docs/master/interop/qcow2.html) 在本地副本上只读检查映射。既有 default_boot 磁盘映射转换后的隔离副本仍是旧状态；另一残留 metadata 表只修改了本地候选副本，未通过启动验证。两类候选均未写回原 AVD，自有试验进程已关闭。没有发现本机 Time Machine local snapshot；尚未找回停机前的较新数据，不能保证完整恢复。

当时原日常 AVD 保持停止以保护证据；曾询问所有者是否有 2026-10-05 之后的 Helix 导出或 AVD 备份，模型对照暂停，真实模型请求数为零。该阶段尚无数据库重建授权，未让新版打开旧数据库；后续授权与结果见下节。

恢复脚本已增加 fail-closed 前置条件：同哈希 APK、实际即时 inventory/无在途任务、冻结版本不得 downgrade、成功保存唯一具名 live checkpoint 后才能停止，恢复使用该具名 checkpoint，不再默认加载旧快照。较新历史数据未找回；只有所有者随后授权重建才继续，未将该授权当作数据恢复成功。

## 所有者授权重建与重新验证

所有者随后明确表示「可以直接重建数据库」「不用考虑兼容和数据保留」。据此在自有日常 API36 进程装回 SHA256=`710694153ed29a362bb76858828201ee99f0f7cc4ca534fbf38c5014eeb93802` 的原 APK。该制品实际 versionCode=1，旧快照包为 4，首次普通覆盖安装被拒绝；明确重建授权下使用同签名 debug `install -r -d`，未卸载或清除全部应用数据。Room 按既有开发契约重建为 `c34065205d0987bb3b495a2f4a663c43`。

在安装前只读取唯一既有 Qwen3.8-27B 配置行，在进程内临时保留并于重建后重新登记；没有导出密钥，原 SecretStore/AndroidKeystore 留在设备内。只读 inventory 2/2 通过，Qwen 配置存在、active Turn=0。日志 `daily-rebuilt-inventory.txt`；此结果证明数据库重建与配置登记，不证明较新历史数据恢复或模型请求成功。

第一次重建后隔离冷启动（r4）仍读到旧基底 package versionCode=4，稳定 runner 安装在模型前被拒绝；它没有卸载/清数据。finally 按刚保存的唯一具名快照恢复日常实例，同哈希原 APK、Room 身份、Qwen 与 active Turn=0 均复核通过；日志 `device-run-r4.log`、`daily-after.txt`。后续采用局部、忽略的 checkpoint adapter 复用 stable owned runner，只调整只读实例启动为该新快照并保留其虚拟硬件，安装/执行/所有权检查/最终关闭仍由稳定 runner 执行。实际启动命令独立记录；r5/r6 因 GLES renderer 差异未加载快照，回退冷启动后逐次安装/Room/Qwen 身份核查均通过，随后才调用模型，未将快照尝试写成快照加载成功。

## 最终多任务对照

设备为自有只读 `emulator-5598`、Helix_API_36、API36 arm64、Developer；模型 Qwen3.8-27B / OPENAI_CHAT_COMPLETIONS，既有配置通过正常 Provider 路径解析。测试仅创建合成会话/文件/短时 Job，不增加任务专用发现提示、不导出密钥、不使用其他账号。基线与候选 App 哈希同前述冻结记录；有效手动/AUTO Job 对照来自 r5，同一测试 APK SHA256=`19ee3b66efb3cc2ff08a6491d497c2f9451d58563d9bf0bb172d910da231d2fe`。有效归档对照来自 r6，两组同用修正后的测试 APK SHA256=`b25834ae12540a68e094d63a09064d0da1014775d041a3c6874871a4d68cb97b`。App、模型、用户任务、预算与生产策略在补测时未修改。

| 任务（每组两轮） | 基线严格断言 | 候选严格断言 | 基线模型调用 / 工具调用 | 候选模型调用 / 工具调用 | 基线 / 候选 UNKNOWN_TOOL |
| --- | --- | --- | --- | --- | --- |
| 手动 Job / 时间 / await / collect | 1/2 | 2/2 | 19、18 / 30、20 | 5、5 / 5、5 | 7、5 / 0、0 |
| AUTO Job | 2/2 | 2/2 | 3、3 / 2、2 | 3、3 / 2、2 | 0、0 / 0、0 |
| ZIP 归档 | 2/2 | 2/2 | 3、3 / 2、2 | 3、3 / 2、2 | 未独立采集 audit / 未独立采集 audit |

**有效严格验收：基线 5/6，候选 6/6。** 手动基线第一轮 Turn 最终为 COMPLETED，但 `start` 请求出现 3 次，只有 1 次通过执行；另外 2 次在执行前被 UNKNOWN_TOOL 拒绝，因此严格“一次 start 调用”断言失败。两轮基线与两轮候选均各有且仅有一次成功 start；没有证据表明脚本实际重复执行。基线第二轮最终通过严格断言，但仍有 5 次 UNKNOWN_TOOL，不能把它当作发现过程无问题。

手动任务基线两轮累计输入/输出 tokens 为 352399/10056，候选为 104998/2189；这是多次模型请求的累计量，不能解读为单轮上下文大小。对应墙钟为 47.41、42.45 秒与 13.90、15.28 秒，包含 instrumentation/执行/网络，不能当纯模型或上下文构建耗时。AUTO/归档没有观察到完成率回归，时间和 token 波动不主张确定收益。两处提示（base 与工具描述）是整体候选，不隔离归因于其中一处。

原 r5 四次归档任务均成功调用一次归档，但新增夹具误以为 ZIP 仅保存 basename。既有 `ArchiveToolsTest.archiveCreatesAZipAndRoundTripsThroughExtract` 明确核查 `work/src/...`，`archiveToolsCollectMembers` 也保留 scope-relative 路径。修正精确预期为 `work/<唯一标记>/source/marker.txt`，继续断言唯一成员、原始内容、一次调用与终态；没有弱化契约。原四次夹具失败保留并全部排除评分，只重跑四次归档，不把旧失败改写为通过。本轮实际发起 16 个合成模型任务，有效对照 12 行，另 4 行为夹具无效样本；此前环境失败均在模型前。

修正后 `spotlessApply detekt spotlessCheck :tools:files:test :app:assembleDeveloperDebugAndroidTest` 通过，426 tasks/23 executed，日志 `archive-fixture-host-r2.log`。首次受 sandbox 阻止访问 Gradle wrapper lock，在获准使用现有主机缓存后重跑通过。原完整主机门禁保持前述身份；本次仅测试预期修正，生产 App 不变。

原始日志在 `api36-comparison-r5/` 与 `api36-comparison-r6/`，严格结果 `comparison.json`，每轮 Room 身份、Job audit、usage 和归档独立 ZIP 断言均保留；合并有效数据 `valid-comparison-summary.json`。两次 owned runner 都在 finally 关闭，只读改动不写回日常磁盘。日常按各自唯一新快照恢复后，同哈希原 APK、当前 Room、Qwen 与 active Turn=0 复核通过；未安装候选日常 APK。这里的“恢复”指恢复可用实例/重建库，不指恢复丢失的较新会话数据。

采用通用生产提示：候选在本次三类、各两轮严格断言均通过，手动任务减少窗口误用，单工具场景未出现回归。保留搜索排名、16/64 上限、当前请求 schema、权限与执行前拒绝。小样本不是统计显著性或普遍成功率证明；更多模型、语义改写、跨 OEM 与长稳仍未覆盖。

日常恢复后的自有进程另跑 `PinyinComposerDeviceTest` 1/1 通过：真正输入拼音、出现候选、提交「你好」，同时核查 composer 草稿及零发送；日志 `daily-pinyin.txt`。`hw.keyboard=yes` 与 `show_ime_with_hard_keyboard=1` 已启用，没有把 UTF-8 注入当作拼音组合验证。该检查不调用模型，不表示所有 IME/OEM 输入通过。

完成记录/ADR/status 收敛后再次执行 `scripts/check-all.sh --source` exit=0；日志 `source-gates-closeout-r2.log`，776 Markdown、235 HXA、36 ADR、国际化及秘密扫描通过。`git diff --check` 通过，日常已返回 Helix 主界面。

真机、API29、其他模型、OEM、长稳和资源峰值未请求/未测。墙钟含 instrumentation，不能当纯模型或上下文构建耗时。独立 inventory 通过不等于模型效果通过。
