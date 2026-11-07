# Helix

**简体中文** | [English](README.en.md)

**开源 Android AI Agent，让模型在你的手机上完成实际任务。**

Helix 将手机操作、浏览器、文件和工具放进同一会话。你选择模型、项目与访问范围，查看执行过程，继续处理生成的文件与结果。项目独立开发，自有源码采用 Apache-2.0，不依赖 OEM 预装集成。

[下载开发预览](https://github.com/dollarser/helix-agent/releases) · [更新日志](CHANGELOG.md) · [文档](docs/README.md) · [反馈](https://github.com/dollarser/helix-agent/issues) · [贡献](CONTRIBUTING.md)

> **开发预览，Android 10+，当前提供 ARM64 APK。** 项目尚未正式发布，尚非稳定版，使用既有开发签名而非商店生产签名。新版本不保证可以覆盖安装；即使覆盖安装成功，也不保证保留历史数据。升级前阅读[版本说明](docs/product/releases/v0.0.10.md)，另存重要内容；`main` 的功能不一定已随 APK 发布。

## 能做什么

| 能力 | 示例与范围 |
| --- | --- |
| 手机操作 | Mobile Use 观察界面、查找元素、点击、滚动和输入；按设备能力使用 Root、Shizuku 或无障碍 |
| 浏览器 | 查阅网页、对照资料；手动浏览与 Agent 任务页面分别管理 |
| 文件与成果 | 读取和编辑授权文件，预览、定位、分享结果；外部工作目录与会话私有存储分开 |
| 会话与项目 | 创建会话时选择或新建项目，关联目录，在同一项目下继续多个会话 |
| 本机知识库 | 从系统分享收集资料，管理知识与笔记，选择来源引用或检索；不自动把全库发送模型 |
| 能力组合 | 按会话选用插件、技能与专家，接入 MCP；引用或安装不等于授予执行权限 |
| 模型与语音 | 内置账号模型、自配 API、设备内模型；听写进入可编辑草稿，回复可朗读 |
| 备忘与备份 | 本机提醒、可选只读定时 Agent 分析，以及主动导出/恢复本地应用备份 |

完成质量取决于模型、授权、系统与目标应用。安全窗口、登录限制及不支持的内容可能无法操作；实际交付和未验证边界见[当前状态](docs/development/status.md)。

## 开始第一个任务

1. 从 [Releases](https://github.com/dollarser/helix-agent/releases) 选择安装包，核对该页 SHA-256 后安装。首次打开阅读说明；通知、省电和悬浮窗等权限按用途选择，可跳过。
2. 在模型选择中登录 Helix 账号使用内置服务，或配置自己的 API / 下载设备内模型。内置服务的目录、权限与额度以服务端实际状态为准；远程推理可能产生服务费用。
3. 新建会话，按需选择项目、工作目录与插件。先用测试资料尝试：

```text
把下面的待办按上午、下午、晚上分组，保存为“明日待办.md”：
上午十点开会，下午寄快递，晚上买牛奶。
不要补充我没说过的时间。保存后读回，确认内容一致。
```

4. 从会话管理或文件管理器查看成果、核对内容并分享。模型声称完成不替代实际结果核验。

### 操作手机

从输入框加号选择“选择插件”，选用手机操作（Mobile Use）。缺少配置时进入相关设置，返回后重新检查；不要求一次获得所有后端权限。默认范围为全手机，也可指定应用，具体调用仍经过范围与审批检查。

Root、Shizuku 和无障碍能力并不相同；任务屏依赖实际可用的后端，不能保证操作所有应用。当前源码的电脑 ADB helper 支持 **Advanced Debug / Release、Android 12+**，不需要 Root 或 Shizuku，由已授权的电脑 ADB 启动；没有应用内无线配对。**已公开的 v0.0.10 APK 不包含该入口，新路径尚未发布。** 见[启动与使用说明](docs/development/computer-adb-helper.md)。

## 选择安装包

| 渠道 | 包名 | 包含内容 |
| --- | --- | --- |
| Standard / consumer | `com.helix.agent` | 完整基础产品、API/本地模型及渠道允许的工具；不含第三方订阅 Runtime 和 PRoot |
| Advanced / developer | `com.helix.agent.developer` | 基础产品加进阶终端、Linux 环境和实验性第三方订阅适配 |

渠道由安装包决定，应用内无需选择权限档位；系统授权、访问范围和具体任务审批独立生效。订阅适配需要对应账号权益，不代表服务商官方授权。设备内模型及离线语音权重按需下载，不随 APK 提供。

当前预览保持 versionCode 为 1；曾安装 code 2 的设备可能被 Android 拒绝降级。不要为覆盖安装而随意卸载：卸载会删除应用私有数据。签名、升级影响及本版本验证范围以发布页为准。

## 数据、隐私与控制

- 本机执行不等于完全离线。使用远程模型/语音时，所需消息、上下文、工具结果、文件片段或图像会发送给所选服务；本地推理也不阻止联网工具访问网络。
- 线上语音首次使用需确认；听写只进入草稿，不自动发送。停止朗读与停止 Agent 是不同操作。
- 笔记、知识和资料位于共享 `Documents/Helix/library/`；会话、记忆及账号凭据等内部数据为私有。外部目录需要独立授权。
- 专用本地备份可恢复其白名单范围；普通聊天/配置不是因此加密，静态凭据为默认关闭的独立加密选项。账号/OAuth 登录、系统权限、运行进程及未决操作不会随备份恢复或自动重放。见[备份契约](docs/adr/workspace/008-content-backup.md)。诊断导出不等于可恢复备份。
- 检测到不兼容主库时，先展示影响范围，只有明确确认才重建；不会自动清库。数据库结构兼容也不保证旧内部配置格式兼容，详见升级说明。
- 停止任务不撤销已发生的外部操作；结果不明时先检查，再决定是否重试。

## 从源码构建

需要 **JDK 17、Android SDK、Python 3.11+**，确切 SDK/NDK、依赖和运行时准备步骤见[开发环境](docs/development/environment.md)。

```sh
git clone https://github.com/dollarser/helix-agent.git
cd helix-agent
python3 scripts/build-asr-assets.py
python3 scripts/build-tts-assets.py
./gradlew :app:assembleConsumerDebug
# 完成开发环境中的进阶运行时准备后：
./gradlew :app:assembleDeveloperDebug
```

APK 位于 `app/build/outputs/apk/`。快速源码检查为 `./scripts/check-all.sh --source`，常规 Debug 门禁为 `./scripts/check-all.sh`。本地 hooks 安装及分层 CI 见[贡献指南](CONTRIBUTING.md)和 [CI 说明](docs/development/ci.md)；构建通过不代表设备或真实服务验收。

## 帮助与贡献

- [使用与开发文档](docs/README.md) · [当前状态](docs/development/status.md) · [架构](docs/architecture/overview.md) · [设计决策](docs/adr/README.md)
- [报告问题](https://github.com/dollarser/helix-agent/issues)：附版本、渠道、Android/设备、模型和复现步骤，移除密钥、凭据与私人内容。
- [贡献指南](CONTRIBUTING.md) · 维护者 [dollarser](https://github.com/dollarser)

## 许可证

Helix 自有源码采用 [Apache License 2.0](LICENSE)。第三方库、运行时、工具和模型权重保留各自许可证；归属与分发要求见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，该文件不是完整发行 SBOM。确切附件、校验值和对应源码链接见各版本发布正文。
