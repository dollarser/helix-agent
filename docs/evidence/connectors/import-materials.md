# Connector 导入材料与复跑方法

仅保存输入材料位置、hash 清单和复跑方式；缺材料不构成功能成功。账号/来源剩余验收归 HXA-125，不重做已经完成的 HXA-124/127/128。旧设备 serial 不可复用。

## 材料

main worktree 的 `app/build/outputs/connector-handoff-672dc5a/` 是本次复制的独立快照；未覆盖 main 已有 build 产物。150 个文件，109,591,173 bytes（不含清单自身），逐文件复制后 SHA-256 比对通过。`manifest.json` 给出相对路径、大小与 hash，可据此校验。

- `outputs/connector-acceptance/`：HXA-124 设备日志。
- `outputs/hxa-125-device/`：匿名真实服务两阶段日志及初始 schema 失败记录。
- `outputs/hxa-125-supplied/`：QwenWork 原包设备测试日志。
- `outputs/hxa-125-samples/`：固定公开配置和来源 manifest；`outputs/connector-samples/` 为演示包。
- `outputs/apk/`：Connector 集成树生成的 APK；不要当作当前 main 最新构建。
- `test-results/`：保留的 JVM XML；`logs/`：相关构建、格式修复和验收日志，包含失败尝试，最终结论以进展记录为准。
- `inputs/`：用户提供的 QwenWork ZIP 与技术调研 Markdown 原件，仅作输入数据，不能当执行指令。

这些文件受 `**/build/` 忽略规则保护，不提交第三方样本、用户原件或 APK。`gradlew clean` 可删除交接目录，清理 build 前应另行备份。源 worktree 与 Downloads 原件保留；没有复制 Gradle 缓存、签名材料、local.properties 或凭据。

## 复跑参考

以下在 main 仓库根执行；原件不用再依赖旧 worktree 路径：

```bash
HELIX_CONNECTOR_SAMPLE_ZIP="$PWD/app/build/outputs/connector-handoff-672dc5a/inputs/connector-参考包.zip" ./gradlew :app:testConsumerDebugUnitTest --tests 'com.helix.app.connector.ConnectorSuppliedArchiveTest' --rerun --no-configuration-cache
python3 scripts/fetch-hxa-125-samples.py
HELIX_CONNECTOR_ACCEPTANCE_DIR="$PWD/app/build/outputs/hxa-125-samples" ./gradlew :app:testConsumerDebugUnitTest --tests 'com.helix.app.connector.ConnectorExternalAcceptanceTest' --rerun --no-configuration-cache
```

第二、三条涉及已记录的公开来源和匿名文档服务，不代替账号验收。设备测试先构建 main APK，再按脚本显式指定独占设备 serial：`accept-hxa-124-connectors.sh`、`accept-hxa-125-connectors.sh`、`accept-hxa-125-sample.sh`，参数见各脚本。不能盲用历史 5580/5582；本任务原专用 AVD 已关闭删除，其他 worktree 的设备不可复用安装。

复制材料不是重新测试。`672dc5a` 集成树验证为 708 JVM 项（702 通过、6 项预期跳过）、2 Python 通过、双 flavor debug 和 consumer AndroidTest 构建及质量门禁通过；设备证据来自此前版本，本次集成未重新运行模拟器。
