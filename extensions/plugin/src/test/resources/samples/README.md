# 生产解析器作者样例

使用说明及矩阵见 [插件作者指南](../../../../../../docs/development/plugin-authoring.md)。所有内容来自 Helix 自有示例，不复制外部参考实现。

| 目录 | 目的 | 边界 |
| --- | --- | --- |
| `pure-workflow` | 文本 Skill，零工具插件 | 会话选择和调用资格仍检查，不自动运行脚本 |
| `known-native` | App 自有 Chat Reply 清单示例 | 导入该文件不会注册 native 工厂；实际贡献由 APK 提供 |
| `remote-mcp` | 标准 Streamable HTTP MCP 清单 | `example.invalid` 是占位域名，安装不连接；设备测试替换为受控 loopback 服务 |

`PluginAuthorSampleTest` 读取这些实际文件并使用生产解析器。`PluginAuthorExamplesDeviceTest` 验证受控实例的预览、安装、选择、首次调用及停用，保留原 Dispatcher/审批/网络边界。
