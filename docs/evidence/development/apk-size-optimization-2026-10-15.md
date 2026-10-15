# APK 体积优化与主机验证

日期：2026-10-15。范围：Owner 要求落实 APK 优化并测试；本轮不提交、发布或安装应用。遵循 [分发契约](../../adr/platform/001-distribution.md)，不推进并行的 HXA-286～294 架构重组。

## 实现与取舍

- [应用构建](../../../app/build.gradle.kts)启用当前 AGP 的 Release R8 代码和资源优化；规则放在 `app/src/*/keepRules/`，保留平台默认规则，只对已核实的可选 TLS、桌面 JMX 和未提供的 ZIP 解码器精确处理。
- 默认构建保留 arm64-v8a/x86_64 开发覆盖；[分发脚本](../../../scripts/build-release-apks.sh)分别生成两个 ABI、两个渠道的 unsigned Release APK，保存各自 mapping、元数据及校验值，并逐包运行制品门禁。x86_64 的 PRoot/FFmpeg 限制保持，不能把打包通过解释为这些能力可执行。
- [构建编码器](../../../scripts/prepare-rootfs-payload.py)校验锁定 tar，生成确定性单流 XZ 并验证往返；APK 只携带压缩资产。[运行时解码器](../../../runtime/proot-core/src/main/kotlin/com/helix/runtime/proot/core/RootFsCompression.kt)限制输入、输出及字典内存，拒绝损坏、截断、拼接流和尾随数据，响应线程中断并关闭流。安装器继续校验原 tar 身份、原子激活与失败清理。
- XZ for Java 固定版本及校验元数据已纳入依赖管理，来源和许可补入随包说明。未删除 Python、Node、Git、媒体能力或许可证材料，未引入下载式 Runtime。RootFS 包裁剪的收益较小且会改变已锁定运行环境，本轮保留。
- [最终 APK 门禁](../../../scripts/verify-integrated-runtime-apks.py)验证实际 ABI、必需 native 库、R8 mapping 对应的 DEX 定义、进程/权限/渠道以及解码后的 RootFS。门禁不靠保留无用类获得可扫描名称。

## 回归与修复记录

- 初次接线遇到 AGP 新资源目录 API 和 R8 首次解析的未缓存依赖；修正目录生成接线并刷新固定依赖锁与验证元数据。
- R8 发现 JGit 桌面 JMX 与 Commons Compress 可选 codec 引用。Android 不启用 JMX；Skill/Connector ZIP 入口对未提供的 Zstandard/XZ 方法明确返回验证失败，避免类链接错误，普通 ZIP 路径保持测试覆盖。
- 完整 Skill 测试暴露已有 `helix-docs` 加入后两个目录/分页断言仍按八项计算；按当前九项目录修正，保留完整目录、schema、权限与分页断言。
- 完整静态门禁暴露两个既有 Helix Docs 设备测试的格式问题；只整理这些文件，并为完整场景方法记录长度例外，未删除断言或运行真实模型。新增 ZIP 测试拆出夹具助手，解码错误补充说明。原 PRoot 设备测试改为验证解码后的锁定 tar，保留结构、哈希与损坏测试；本轮仅编译。

## 验证

基线为修改构建参数前重新生成的两个 Release APK；比较分别报告默认和单 ABI 产物，是三项配置及必要解码接线的组合效果，不宣称 R8 单变量收益，也不把旧发布包或 Debug 与 Release 混比。并行的文档/归档提交不改变该轮生产源码基线。

| 检查 | 当前执行结果 |
| --- | --- |
| `./scripts/check-all.sh --source` | 242 项 Python 测试通过；文档、ADR、i18n、secret 门禁通过 |
| `./scripts/check-all.sh --build` | 全仓 Spotless、detekt、Debug/Release lint、JVM 测试及两渠道四 APK 构建通过；41 份依赖锁稳定 |
| JVM 报告汇总 | 5,921 项（包含变体重复），5,913 通过、8 条件跳过、0 失败；跳过项为未启用的外部验收和未提供的本地归档样本，没有新增跳过 |
| 改动相关测试 | 解码 8/8、安装 20/20、Skill ZIP 10/10、Connector ZIP 10/10、Skill 工具 4/4；均未跳过 |
| `./scripts/check-all.sh --artifacts` / `--release-artifacts` | 两渠道默认 Debug/优化 Release 最终制品边界通过 |
| AndroidTest APK | `:app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest :runtime:proot-app:assembleDebugAndroidTest` 三包编译通过；没有运行设备 |
| `./scripts/build-release-apks.sh all` | arm64-v8a/x86_64 × consumer/developer 四个 unsigned Release 构建及逐包边界通过；默认双 ABI 输出随后恢复 |

压缩率、条目分布、SHA-256、原始日志和工作区快照保存在本机忽略目录 `build/apk-optimization-2026-10-15/`。实际 APK 的完整压缩资产另用生产 JVM 解码器在受限堆中读取，并核对原始锁定身份；Java 8 基础实现路径与 Java 9+ 多版本 JAR 路径均通过。这验证有界读取和字节一致性，不代表设备安装耗时或内存峰值。

默认与 ARM64 Release 的体积均明显下降。可复核数据见该目录的 `size-comparison.json`，各 ABI 分发候选与 mapping 在 `build/distributions/<abi>/`；这些都是本轮未签名候选，不是已发布版本。主机通过不要求回退优化；若用于 v0.0.6，仍应对选定的签名候选补做实际 Release 启动、JNI、本地模型、文档解析、Mobile Use 各后端、首次 RootFS 安装及升级/修复验证，不能继承旧 Debug 的设备结果。

设备、真实 Provider、媒体端到端：本轮 not requested，未运行。R8 运行行为、首次安装解码耗时和峰值内存仍需独立设备验证；构建和 JVM 测试不能代替该结论。HXA-122 的正式签名、商店审核和发布身份也不由本轮完成。

Owner 随后明确授权的 OnePlus/API34 与真实模型验证见[后续记录](device-ui-model-validation-2026-10-15.md)。其中隔离 R8 Release 的启动、真实工具调用和首次 RootFS 解压/验证已有当前运行证据；只更新该后续范围，不把本主机轮次改写为已运行设备，也不推导完整 JNI/本地模型/媒体端到端或安装峰值内存通过。
