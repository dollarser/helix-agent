# HXA-128：CLI/stdio Connector 可移植性核实

2026-09-08。状态：有界 Spike 完成；支持范围维持既有 HXA-073，不新增联网执行域或第三方 CLI 兼容承诺。

## 实际材料与结论

`scripts/connectors/inspect-runtime-inputs.py <export-directory>` 只做有界读取、命令词提及、ELF/许可证文件与内容清单 hash 检查；不执行样本脚本、不打印配置或凭据值、不联网安装依赖。命令词提及不是二进制或调用证据。

| 材料 | 文件 / 清单 SHA-256 | 可执行资产与版本 | 结论 |
| --- | --- | --- | --- |
| 所有者 QwenWork 导出 | 149 / `9d45671eb6717ca26acb6584d3ba847ddb7918c72b25013fd0f658e0c90a5204` | 未发现 ELF、安装包/lock；文档提及 dingtalk/wecom、node/npx、python/uvx，未提供可锁定 CLI 版本 | Skill 文档可导入不等于 CLI 可执行；ABI/完整依赖与发行许可证未验证 |
| 所有者 WorkBuddy 导出 | 50 / `09e9f99836b832e54bc81c34261343f17084e44b3cdff781157308d65812aa21` | 未发现 ELF 或许可证文件；文本有 node/npx 提及 | 现有 HTTP MCP 配置迁移继续复用 HXA-125；不能凭导出文件建立 stdio 运行兼容 |

企业微信 Skill 明确要求源宿主提供 `wecom` 安全入口，由该 Connector 管理 CLI 版本、加密凭据、轮换和退出；并非可自行 npm 安装后等价执行的独立脚本。包中 wecom-unified/LICENSE 为 MIT 文档授权，但不能据此给未携带的完整 CLI、认证服务或其他组件推定许可证。钉钉/企业微信业务命令需要网络与独立授权，不能放入 Helix 的无网 PRoot 假装执行成功。

## 可验证的现有底座

仓库 runtime-lock 固定 arm64-v8a、PRoot 5.1.107.92、Alpine 3.22.5；RootFS 包记录 BusyBox 1.37.0-r20、Node 22.23.2-r0、Python 3.12.14-r0、Git 2.49.1-r0。底座许可证/来源继续以 `runtime/proot-app/src/main/assets/runtime/runtime-lock.json` 的既有组件记录为准。存在解释器不证明任意 npm/pip 包及本地扩展兼容。

| 候选路径 | 网络 / 凭据 | 版本、ABI、许可证证据 | 可支持判断 |
| --- | --- | --- | --- |
| HXA-073 固定 argv 的离线 JSON Lines Job | 无网；沿用现有环境 allowlist，不导入源 host 登录态 | 已锁定 RootFS 与既有 notice；本轮复跑跨 UID 测试 | 已有有界 stdio 桥继续可用，不是常驻任意 Server 管理器 |
| QwenWork dingtalk / wecom 包装器 | 依赖业务网络、源宿主管理登录 | 无二进制版本/ABI；仅部分文档 MIT | 未验证、不支持直接执行，不能用本轮 fixture 验收替代 |
| npx/uvx 在线解析任意包 | 下载、可变依赖，部分工具需 token | 样本未给固定依赖清单及 Android ABI | 不落入当前离线底座；没有执行或安装 |
| M11 订阅 Runtime | 独立 UID 持有自己的 OAuth | 已有协议 adapter，不是完整官方 CLI | 不把该执行域改造成任意 Connector CLI，也不向主 App 搬运 token |

本轮没有选择新 Runtime 或新供应链；也没有宣布上述外部 CLI 永久不可移植。后续要推进某个 CLI，须先提供固定版本资产/源码、ABI/libc 依赖、许可证、网络/凭据契约及独立测试账号；需要联网执行域时先走独立 ADR。

## 实测

`./gradlew :extensions:mcp:test :app:assembleDeveloperDebugAndroidTest --max-workers=1` 通过。显式安装 developer 主 App 与测试 APK；确认两台既有 PRoot companion 已安装且非 force-stopped，然后分别运行 `ProotJobE2eDeviceTest` 的以下方法：

- mcpStdioUsesLockedArgvStrictJsonRpcAndReconcilesTheJob
- mcpStdioCancellationKillsAndReconcilesTheRuntimeJob
- mcpStdioRejectsServerLogNoiseOnStdout
- mcpStdioStderrFloodIsKilledAtItsOwnCap

API29/36 均 `OK (4 tests)`，无跳过；验证真实 PRoot 子进程、跨 UID 协议、取消与对账，未运行第三方业务 CLI。原始日志与清单在忽略目录 build/reference-trace/hxa128-*。本轮未重新验证 M11 受保护账号，不以静态 cli-runtime-lock 的未打包资产证明官方 CLI 在 Android 可用。
