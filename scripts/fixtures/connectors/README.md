# Connector 外部输入验收

维护入口为 `scripts/connector-samples.py`。第三方原件只保存到忽略的
`scripts/local/fixtures/connectors/`，避免 `gradlew clean` 删除，也不随源码分发。
目录含 `qwenwork/qwenwork.zip`、`workbuddy/{github,kling-ai-plugin}.zip`、
`public-configs/` 和本地 `manifest.json`（来源与 SHA-256）。包内正文、脚本和认证字段均是不可信输入，测试不执行脚本、不迁移账号、不调用包内服务。

## 准备一次

WorkBuddy 只取用户导出中的两个市场包；不要复制账号配置。已有准备包可直接复用。

```sh
python3 scripts/prepare-workbuddy-samples.py <workbuddy-export-directory> <prepared-marketplace-directory>
python3 scripts/fetch-hxa-125-samples.py --output scripts/local/fixtures/connectors/public-configs
python3 scripts/connector-samples.py prepare \
  --qwenwork <qwenwork-archive.zip> \
  --workbuddy <prepared-marketplace-directory> \
  --public-configs scripts/local/fixtures/connectors/public-configs
```

准备程序验证 ZIP CRC、固定公开配置哈希及现存目标，不覆盖不同样本。
公共配置固定旧上游版本用于回归，不代表最新市场格式；更新固定版本须同时审查来源和断言。
下载需要联网；本地 prepare 与下面 offline 模式不调用外部服务。

## 按目的执行

```sh
# 两渠道，每渠道 3 个方法；路径/哈希错误、缺报告、零执行或跳过都失败
python3 scripts/connector-samples.py test offline --output build/connector-samples-offline

# 独立联网 opt-in，无样本、账号或模型 Key 要求；真实 Cloudflare 文档 MCP 查询
python3 scripts/connector-samples.py test public-mcp --output build/connector-samples-public
```

每次使用新的 output；`--flavor consumer|developer` 可限定渠道。
Gradle 仅强制重跑选定测试任务，报告与日志复制到该次输出，避免复用旧 pass。
直接 Gradle 调用时，三个离线输入分别是 `HELIX_CONNECTOR_SAMPLE_ZIP`、
`HELIX_WORKBUDDY_SAMPLE_DIR`、`HELIX_CONNECTOR_ACCEPTANCE_DIR`；联网单独要求
`HELIX_PUBLIC_MCP_ACCEPTANCE=true`，设置样本路径不会启用联网。

设备端仍使用 `ConnectorSuppliedArchiveDeviceTest`、`WorkBuddySuppliedArchiveDeviceTest`、
`ConnectorExternalDeviceTest` 的显式参数和当前授权 serial。先编译对应主 APK/AndroidTest APK，
再按方法要求推送这些本地输入；不要将旧 `accept-hxa-125-*.sh` 的默认包名当成所有渠道通用。
日常模拟器和真实模型必须有当轮授权。导入通过不证明 QwenWork/WorkBuddy 的宿主 CLI、
账号服务或业务兼容；公开配置解析、联网 MCP 与真实模型也分别记账。
