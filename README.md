# Helix

**简体中文** | [English](README.en.md)

**开源 Android AI Agent，让一句话变成手机上的实际行动。**

Helix 在 Android 本机执行任务：操作支持的应用界面、浏览网页、处理文件和运行工具。你选择模型与访问范围，Helix 展示执行过程，并把结果留在可查看、可继续使用的会话和文件中。

[下载预览版](https://github.com/dollarser/helix-agent/releases) · [使用入门](#开始第一个任务) · [文档](docs/README.md) · [反馈](https://github.com/dollarser/helix-agent/issues) · [参与贡献](CONTRIBUTING.md)

> **开发预览阶段，需要 Android 10+。** 当前发布包使用 debug 签名。开发期数据库结构不兼容时，覆盖升级会清空会话、配置和数据库记录，保留数据库外文件；升级前请导出重要内容并备份文件。`main` 中的新功能不一定已包含在发布 APK 中。

## 可以做什么

| 能力 | 使用方式 |
| --- | --- |
| **手机操作 · Mobile Use** | 观察屏幕、查找元素、点击、滚动和输入；根据可用能力使用无障碍、Shizuku 或 Root |
| **文件与成果** | 独立文件管理器；读取、整理、创建和修改已授权文件，预览、定位和分享结果 |
| **网页与工具** | 浏览网页、收集资料、调用工具；Developer 构建还包含进阶终端与运行环境 |
| **会话与项目** | 围绕同一项目组织会话、文件、指令和记忆；执行中可排队、调整或停止任务 |
| **模型选择** | API 服务、自建兼容服务和设备内模型；Developer 额外提供实验性第三方订阅接入 |
| **扩展** | 通过插件、技能和连接器组织能力，支持 MCP；按会话选择需要的扩展 |

这些能力的完成质量取决于模型、设备、授权和目标应用。当前不是 OEM 系统镜像，也不承诺兼容所有应用、网站或文件格式。实际交付与验证范围见[当前状态](docs/development/status.md)。

## 开始第一个任务

1. 从 [Releases](https://github.com/dollarser/helix-agent/releases) 下载 APK，按 Android 提示安装。版本说明和校验文件以对应发布页为准。
2. 在**模型**中添加服务，填写 API 地址、接口协议和 API Key；点击**添加模型**，手动输入服务端模型名称，或从服务获取模型后选择。显示名称可不填。也可以安装支持的设备内模型。
3. 新建会话并选择模型，从一个简单文件任务开始：

```text
帮我整理明天的待办：上午十点开会，下午寄快递，晚上买牛奶。
按上午、下午、晚上分组，保存为“明日待办.md”。
不要补充我没说过的时间。保存后读回文件，确认内容一致。
```

4. 查看执行状态并打开生成文件，核对结果。再尝试整理 CSV、汇总网页或操作测试应用。

网络模型可能产生服务方费用；本地模型需要额外下载权重并占用内存和存储。没有配置好的模型时，需要先完成模型设置。

### 操作手机应用

在扩展中启用 **Mobile Use**，完成插件所需配置，并在会话中选用。无障碍、Shizuku、Root 等系统授权由 Helix 统一管理，插件使用宿主已获得的能力。无需同时获得全部权限：部分能力就绪也可启用，具体动作仍需满足相应条件。

支持的操作按实际就绪能力优先使用 **Root → Shizuku → 无障碍**。不同后端的能力并不完全相同，高权限也不能保证读取或操作所有安全窗口。遇到不确定结果时应先核查，不盲目重复可能已经完成的操作。

## 选择安装包

| 构建渠道 | 包含的能力 |
| --- | --- |
| **Standard / consumer** | 完整基础产品：会话、API/本地模型及渠道允许的工具；不包含第三方订阅 Runtime 和 PRoot 终端 |
| **Advanced / developer** | 在基础产品上增加平台或渠道允许的进阶能力，包括订阅适配和 PRoot 终端；适合开发与进阶体验 |

安装包渠道与会话权限模式是不同概念。第三方订阅适配需要对应账号权益，兼容性逐服务验证，不代表服务提供商官方授权。使用 API 地址与 Key 的套餐仍属于 API 配置。

当前版本为 **[v0.0.5 开发预览版](https://github.com/dollarser/helix-agent/releases/tag/v0.0.5)**。推荐进阶体验下载 `helix-v0.0.5-developer-debug.apk`；只需基础渠道可下载 `helix-v0.0.5-consumer-debug.apk`。发布页附 SHA-256 校验值和构建信息。遇到签名冲突，请先保留数据，不要直接卸载或清除应用数据。

本版改进 Mobile Use 与宿主授权分工、模型添加与订阅设置、会话输入和扩展管理，并统一原生网络客户端的 hosts 域名解析。已修复 Release 依赖锁、Android 10 权限 API 检查和 APK 边界校验问题。完整主机 CI 已通过；本次发布未新增设备或真实模型验收，详情见[发布记录](docs/evidence/development/v0.0.5-release-2026-10-06.md)。

## 隐私与控制

- **本机执行不等于数据完全离线。** 使用远程模型时，对话和任务所需的文件片段、工具结果或图像可能发送给所选模型服务。
- 设备内模型在本地推理，但下载、网页和联网工具仍可能访问网络；APK 不包含模型权重。
- 系统权限与工具授权分别管理。按需开放访问范围，并查看实际执行状态；模型说“完成”不等于操作已被验证。
- 停止任务不会撤销已经发生的外部操作。首次体验请使用测试资料或副本。

## 从源码构建

准备 **JDK 17、Android SDK 和 Python 3**，按照[开发环境说明](docs/development/environment.md)配置 SDK/NDK 与依赖。版本以仓库锁定配置为准。

```sh
git clone https://github.com/dollarser/helix-agent.git
cd helix-agent
./gradlew :app:assembleConsumerDebug
# 需要进阶构建时：
./gradlew :app:assembleDeveloperDebug
```

APK 位于 `app/build/outputs/apk/`。主机源码门禁为 `./scripts/check-all.sh --source`；构建成功不等于设备、真实模型或正式发行验收通过。贡献流程与测试要求见[贡献指南](CONTRIBUTING.md)。

## 文档与参与

- [当前进展与已知限制](docs/development/status.md) · [路线索引](docs/development/roadmap.md)
- [架构](docs/architecture/overview.md) · [设计决策](docs/adr/README.md) · [完整文档](docs/README.md)
- [报告问题或提出建议](https://github.com/dollarser/helix-agent/issues)：附版本、构建渠道、Android/设备信息、模型和复现步骤；请先移除密钥、凭据与私人内容。
- 作者：[dollarser](https://github.com/dollarser)，欢迎社区参与改进。

## 开源协议

Helix 自有源码采用 **[Apache License 2.0](LICENSE)**。第三方库、运行时资产、命令行工具和模型权重遵循各自许可证，不因被 Helix 使用而改为 Apache-2.0。

第三方组件说明及发布核对要求见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。该文件目前是部分归属清单与核对规则，不代表完整发行 SBOM 或所有分发义务均已完成。
