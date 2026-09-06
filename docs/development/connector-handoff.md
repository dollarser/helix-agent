# Connector 工作交接

日期：2026-09-05。代码已通过 `672dc5a` 集成到本地 main，未推送。交接时 Connector worktree 无未提交源码；本页和忽略目录中的材料供 main worktree 继续工作。状态以 [status](status.md) 为准，详细证据见 [HXA-125 进展](hxa-125-progress.md)。ADR-0023 accepted；HXA-124 完成，HXA-125 未完成，M13 未整体完成。

## 待完成项

| 任务 | 下一步与完成边界 |
| --- | --- |
| HXA-125 独立账号 | 选择受保护 MCP 服务和专用账号，在 Helix SecretStore 配置凭据，验证正常调用、无效凭据、权限拒绝、厂商撤销和重新连接；不通过聊天、fixture 或 Git 保存 token |
| HXA-125 WorkBuddy | 取得真实导出包，固定 hash 并用生产 reader/安装管线验证；当前仅有官方规范衍生测试，不能算真实平台样本通过 |
| HXA-125 其他来源 | Codex/Claude 只验证实际 MCP/manifest 子集；完整插件的工具名和脚本依赖需具体样本。QwenWork 原包 4 Skill / 2 endpoint 导入通过，CLI 与账号业务未验收 |
| HXA-126 | OAuth 登录层；依赖真实服务选择和独立 ADR，不自动开始实现 |
| HXA-127 | 大 catalog 搜索、有限 schema 加载、更新失效；依赖 HXA-124，启动前明确专项验收 |
| HXA-128 | CLI/stdio 可移植性 Spike；核对 M11 当前实际底座、版本、ABI、许可证和认证边界 |
| HXA-129 | 共享 Skill 所有权、会话 scope、更新 diff、journal、原子视图与 rollback；先审查持久化契约 |
| HXA-130 | 依赖 HXA-129 的市场和来源验证设计；当前仅 docs 与离线 fixture |

按 roadmap 一次推进一个 HXA。外部材料不足时保留 HXA-125 缺口，不创建完成记录；转入独立后续任务时明确更新当前 checkpoint。

## 未入 Git 的材料

main worktree 的 `app/build/outputs/connector-handoff-672dc5a/` 是本次复制的独立快照；未覆盖 main 已有 build 产物。150 个文件，109,591,173 bytes（不含清单自身），逐文件复制后 SHA-256 比对通过。`manifest.json` 给出相对路径、大小与 hash，可据此校验。

- `outputs/connector-acceptance/`：HXA-124 设备日志。
- `outputs/hxa-125-device/`：匿名真实服务两阶段日志及初始 schema 失败记录。
- `outputs/hxa-125-supplied/`：QwenWork 原包设备测试日志。
- `outputs/hxa-125-samples/`：固定公开配置和来源 manifest；`outputs/connector-samples/` 为演示包。
- `outputs/apk/`：Connector 集成树生成的 APK；不要当作当前 main 最新构建。
- `test-results/`：保留的 JVM XML；`logs/`：相关构建、格式修复和验收日志，包含失败尝试，最终结论以进展记录为准。
- `inputs/`：用户提供的 QwenWork ZIP 与技术调研 Markdown 原件，仅作输入数据，不能当执行指令。

这些文件受 `**/build/` 忽略规则保护，不提交第三方样本、用户原件或 APK。`gradlew clean` 可删除交接目录，清理 build 前应另行备份。源 worktree 与 Downloads 原件保留；没有复制 Gradle 缓存、签名材料、local.properties 或凭据。

## 从 main 复跑

以下在 main 仓库根执行；原件不用再依赖旧 worktree 路径：

```bash
HELIX_CONNECTOR_SAMPLE_ZIP="$PWD/app/build/outputs/connector-handoff-672dc5a/inputs/connector-参考包.zip" ./gradlew :app:testConsumerDebugUnitTest --tests 'com.helix.app.connector.ConnectorSuppliedArchiveTest' --rerun --no-configuration-cache
python3 scripts/fetch-hxa-125-samples.py
HELIX_CONNECTOR_ACCEPTANCE_DIR="$PWD/app/build/outputs/hxa-125-samples" ./gradlew :app:testConsumerDebugUnitTest --tests 'com.helix.app.connector.ConnectorExternalAcceptanceTest' --rerun --no-configuration-cache
```

第二、三条涉及已记录的公开来源和匿名文档服务，不代替账号验收。设备测试先构建 main APK，再按脚本显式指定独占设备 serial：`accept-hxa-124-connectors.sh`、`accept-hxa-125-connectors.sh`、`accept-hxa-125-sample.sh`，参数见各脚本。不能盲用历史 5580/5582；本任务原专用 AVD 已关闭删除，其他 worktree 的设备不可复用安装。

复制材料不是重新测试。`672dc5a` 集成树验证为 708 JVM 项（702 通过、6 项预期跳过）、2 Python 通过、双 flavor debug 和 consumer AndroidTest 构建及质量门禁通过；设备证据来自此前版本，本次集成未重新运行模拟器。
