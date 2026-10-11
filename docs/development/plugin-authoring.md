# 插件作者指南与组件支持范围

更新：2026-10-11。对应 [HXA-275](tasks/HXA-275.md) 和 [ADR-CONNECTORS-003](../adr/connectors/003-ownership-and-installation.md)。以下描述当前源码能力，不代表旧发布 APK 已支持。

## 格式与执行边界

标准包入口是根目录 `plugin.json`，采用生产解析器支持的 Agent Plugins `1.0.0` schema；不是 Standard 应用渠道。标准 MCP 配置位于 `mcp.json`。外部导入入口还识别 `.claude-plugin/plugin.json`、`.codex-plugin/plugin.json`、`.codebuddy-plugin/plugin.json`、`connector.json`、MCP JSON 导出和 `qwenwork.mcp/v1`。这里只承诺下表的组件子集，不承诺外部宿主完整运行语义或未来格式版本。

| 组件或条件 | 当前处理 | 作者/用户操作 |
| --- | --- | --- |
| `skills/<name>/SKILL.md` 与包内资源 | 支持统一内容贡献和准确 source/name/hash 引用；当前调用资格另行检查 | 安装后启用内容并在会话选择来源；用户专用 Skill 需可信输入显式使用 |
| HTTPS Streamable HTTP MCP | 支持解析、显式配置、握手、工具选择及原 Dispatcher 调用 | 在设置中配置账号并测试连接；安装和读取状态不连接服务 |
| HTTP loopback MCP | 仅接受 loopback 地址；执行仍受渠道、网络 scope、SSRF 与审批约束 | 仅用于受控本地服务；普通远程 HTTP 不受支持 |
| 认证 Header、动态占位值、缺失地址 | 需要配置；导入诊断不包含原凭据 | 使用现有账号设置和 SecretStore，不把密钥写进包 |
| APK 已知 native | 仅 App composition root 已注册工厂可提供执行能力 | 宿主开发者接入；导入同名 runtime 声明不会创建工厂 |
| 未知或外部 native runtime | 不导入执行组件 | 修改包使用受支持组件；不能以声明获得宿主权限 |
| stdio、桌面脚本、`package.json` 安装步骤 | 不执行；独立报告运行时条件 | 提供受支持的远程 MCP 或宿主已知 native；不会自动安装 Node/运行脚本 |
| SSE MCP | 不执行该 transport | 服务提供 Streamable HTTP endpoint |
| 注册 app 映射 | 不自动连接应用或账户 | 使用宿主已有连接入口，不能复用外部宿主授权 |
| hooks、Agent 定义、命令、LSP、rules/workflows/monitors | 明确报告不支持，受支持的其他组件可独立保留 | 移除不适用组件或使用已有工具；不会新增执行引擎 |
| 包依赖声明 | 报告必要/可选声明，未解析满足性、未下载、未按名称绑定 | 作者说明实际前置条件；声明不是依赖已满足的证明 |
| `userConfig`/配置 schema | 保留诊断，不生成外部宿主表单或应用默认值 | 使用 Helix 已有配置入口 |
| Skill `context`、`agent`、`hooks` | 保留元数据并报告具体字段；当前模型读取拒绝不支持执行语义 | 改写为当前支持的普通 Skill，不把 fork 内容静默交给主 Agent |
| Skill `allowed-tools`、`model` | 仅提示性元数据，不授予工具访问或改变模型路由 | 真实权限和模型由用户及宿主设置决定 |

Claude Code 的[清单参考](https://code.claude.com/docs/en/plugins/manifest-reference)与[故障排查](https://code.claude.com/docs/en/plugins/troubleshooting)用于比较“声明、配置、加载”和组件失败的区分；Helix 保持 Android 宿主及原权限链。当前 Claude 的 dependencies 是必要依赖；某些外部对象中的 `optional:true` 只作为可选声明诊断，不宣称是 Claude 标准或已实现依赖求解。

## 诊断与安全

预览、安装返回、组件就绪投影和模型结果共享 `PluginDiagnostics` 的结构化结果：`domain=PACKAGE_SUPPORT`、code、kind、逻辑组件/字段 path、state、action、omitted、requirement。path 是包内逻辑位置，不泄漏凭据值。`AVAILABLE` 仅表示包组件被支持，不等于已连接、已选中或获得执行批准；实时状态由 HXA-274 的同源 readiness 表达。支持范围默认折叠，展开仅查看。

目录逃逸、链接、恶意归档等安全错误整体拒绝。标准入口允许隔离的无效 Skill/MCP 组件只影响该组件；外部 Skill 的既有完整校验失败仍拒绝导入，不为了局部可用弱化安全校验。有效 Skill 与待配置 MCP 可共存。预览后安装固定内容 hash；内容变化或取消不提交原确认。

## 三种作者样例

样例在 [samples](../../extensions/plugin/src/test/resources/samples/README.md)。它们不是下载器、市场或新 SDK。

1. **纯 Skill**：`pure-workflow` 包含清单和文本工作流，无占位工具。用现有插件入口预览并安装，启用内容、会话选择，再通过 `skills.read` 读取准确 key；取消选择后不可再读。首次读取不自动发送信息或执行包内脚本。
2. **已知 native**：`known-native/plugin.json` 展示宿主自己的零工具 `chat-reply` 工作流，`browser-plugin.json` 展示有工具的 Browser。外部导入会明确省略 native；真实工厂来自 APK。使用应用已有来源、选择会话并读取其 Skill，停用后拒绝后续读取或工具调用。Browser 在双渠道提供，新会话默认选择可由用户修改；取消 Agent 选择不关闭手动标签。选择、导入清单或读取 Skill 都不创建页面或授予网站访问。
3. **远程 MCP**：`remote-mcp` 使用不可达占位域名，作者应替换为自己 HTTPS Streamable HTTP 服务。预览→安装→显式配置/连接→选择工具和会话→首次调用按真实授权链执行→取消选择及断开。设备回归使用受控 loopback JSON-RPC 服务代替真实账号，验证首次发送审批和停用后的网络零调用。

精确样例文件由 `PluginAuthorSampleTest` 使用生产解析器验证；`PluginAuthorExamplesDeviceTest` 使用同契约的唯一命名实例走生产安装、选择和 Dispatcher，避免同名资产冲突。真实模型沿用现有可信 Skill 输入回归，不操作第三方账号。

## 更新与卸载归属

内容快照按准确引用归属；包移除只撤销自己的引用，不能删除独立用户或其他包仍引用的内容。安装不连接服务，配置和凭据分开存储。更新保持现有安装事务、取消与内容 hash 规则；已有 endpoint 的用户配置按现有 identity 归属处理，不按相似 URL 自动合并。卸载清理所属 endpoint 配置/凭据和内容引用，不删除无关用户文件。捆绑 native 随 APK 更新，不提供外部包替换其工厂。

具体事务和共享引用以 [ADR-CONNECTORS-003](../adr/connectors/003-ownership-and-installation.md) 及现有 HXA-235 安装测试为准；本任务未增加数据迁移、依赖安装器、脚本 hooks 或第二份执行状态机。
