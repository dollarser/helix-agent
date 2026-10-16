# Connector 导入材料与复跑方法

本页保留历史交接来源；当前可复用入口为[外部样本说明](../../../scripts/fixtures/connectors/README.md)。2026-10-16 的 [HXA-299](../../development/tasks/HXA-299.md) 已将 QwenWork 原包、WorkBuddy 两个市场包与六份固定公开配置集中到忽略的 `scripts/local/fixtures/connectors/`，由本地 manifest 校验，不受 `gradlew clean` 影响。原件与旧证据保留，不提交第三方内容或凭据；真实账号验收不由导入通过推导。

## 历史交接材料

main worktree 的 `app/build/outputs/connector-handoff-672dc5a/` 是本次复制的独立快照；未覆盖 main 已有 build 产物。150 个文件，109,591,173 bytes（不含清单自身），逐文件复制后 SHA-256 比对通过。`manifest.json` 给出相对路径、大小与 hash，可据此校验。

- `outputs/connector-acceptance/`：HXA-124 设备日志。
- `outputs/hxa-125-device/`：匿名真实服务两阶段日志及初始 schema 失败记录。
- `outputs/hxa-125-supplied/`：QwenWork 原包设备测试日志。
- `outputs/hxa-125-samples/`：固定公开配置和来源 manifest；`outputs/connector-samples/` 为演示包。
- `outputs/apk/`：Connector 集成树生成的 APK；不要当作当前 main 最新构建。
- `test-results/`：保留的 JVM XML；`logs/`：相关构建、格式修复和验收日志，包含失败尝试，最终结论以进展记录为准。
- `inputs/`：用户提供的 QwenWork ZIP 与技术调研 Markdown 原件，仅作输入数据，不能当执行指令。

这些文件受 `**/build/` 忽略规则保护，不提交第三方样本、用户原件或 APK。`gradlew clean` 可删除交接目录，清理 build 前应另行备份。源 worktree 与 Downloads 原件保留；没有复制 Gradle 缓存、签名材料、local.properties 或凭据。

## 当前复跑入口

以下在仓库根执行。先按样本说明 prepare；每轮使用新的输出目录，错误/改变的输入或跳过不能成为 pass：

```bash
python3 scripts/connector-samples.py test offline --output build/connector-samples-offline
python3 scripts/connector-samples.py test public-mcp --output build/connector-samples-public
```

第二条是独立显式联网的匿名文档 MCP 服务验收，不需要样本路径、账号或真实模型。直接 Gradle 执行时，`HELIX_CONNECTOR_ACCEPTANCE_DIR` 现在只启用离线解析；联网另需 `HELIX_PUBLIC_MCP_ACCEPTANCE=true`。设备入口与实际参数见样本说明，不能盲用历史 serial/包名；只有当轮指定设备授权才执行。

复制材料不是重新测试。`672dc5a` 集成树验证为 708 JVM 项（702 通过、6 项预期跳过）、2 Python 通过、双 flavor debug 和 consumer AndroidTest 构建及质量门禁通过；设备证据来自此前版本，本次集成未重新运行模拟器。
