# 外部插件市场复用与工具发现指导

HXA-281。Owner明确要求参考两条会话并优化工具/插件逻辑，选择先支持导入、市场、Skill及可运行MCP，其他组件显示限制。参考会话只用于识别安装/加载/当前绑定、工具成功/UI显示、MCP OAuth/平台账户的分层；不使用其远程服务、账号、权限或私有源代码。

## 研究与选择

- [OpenAI包规范](https://developers.openai.com/plugins/build/plugins)：标准根及Codex兼容入口、Skill与MCP配置分工；沿原标准根权威规则，不混拼两个清单。
- [Claude Code清单](https://code.claude.com/docs/en/plugins-reference)：Skill路径数组/根目录及MCP有序合并。采用受支持的JSON/inline子集，MCP bundles、脚本和桌面运行时不执行。
- [Claude市场来源](https://code.claude.com/docs/en/plugin-marketplaces)：发现源与安装分开、相对来源按市场根解析、名称一致和真实内容检查。Helix采用显式本地/下载快照，移除目录保留已安装记录；不复制其自动卸载或远程执行行为。
- [CodeBuddy官方市场](https://cnb.cool/codebuddy/marketplace)、[清单参考](https://cnb.cool/codebuddy/marketplace/-/blob/main/plugins-reference.md)、[市场参考](https://cnb.cool/codebuddy/marketplace/-/blob/main/plugin-marketplaces.md)：核对.codebuddy-plugin与同类包结构；本机既有github/kling-ai-plugin样本沿原opt-in host fixture复验。另识别同结构.workbuddy-plugin作为兼容入口，不宣称新厂商标准。
- [GitHub官方远端MCP](https://github.com/github/github-mcp-server#remote-github-mcp-server)：精选GitHub地址修正到官方api.githubcopilot.com/mcp/；未连接真实账号。

参考行为不改变Android权限、稳定安装身份、Room提交、原binding或UNKNOWN语义。没有复制参考实现或增加执行域。

## 实现

工具名引用仅作发现线索，base/Browser描述就近提醒核对本请求schema；tools.search版本4返回schemaAvailability=next_request。外部mcp__限定名只在搜索中提取动作部分，不注册Dispatcher别名，撤销和当前身份仍过滤。

外部包增加实际路径数组及有序MCP声明；标准根权威和安全归档检查保持。市场目录与插件分开，内含包逐项预览/安装，只有索引时可选择对应原包。稳定市场/条目身份进入原PluginService，当前目录hash与安装revision双重检查；目录添加/更新/移除不连接服务、不更改实际安装/会话选择。所有磁盘读取/安装/卸载从市场UI迁到IO，取消传播；没有新增全局执行锁。

市场源保存准备后的投影而非原MCP配置，认证值不落发现缓存；根Skill排除已知MCP配置和宿主清单，保留普通引用资源。checksum检测损坏而非签名。全局插件启停按钮从市场移除，已安装与选中版本分开展示；支持范围可展开。无服务的Web Research占位项从精选发现移除，不删除既有记录。旧“至少9项”测试改为逐项真实契约/唯一身份/非占位端点验证，未删除有效测试。

## 当前验证

定向及整合JVM、spotless/detekt和两渠道AndroidTest编译通过（ignored build/hxa281-static-r7.txt）；包括13项新增发现/包/目录/投影边界回归。正常、取消发布、过期选择、并发替换、名称冲突、路径逃逸、损坏候选隔离、凭据不保留和投影损坏拒绝均有断言；无真实网络执行。两渠道WorkBuddy既有样本实际复验成功，资源逐文件保留，端点只解析而不连接。

最终代码的全仓主机门禁通过（ignored build/hxa281-full-host-r4.txt），完成记录整理后的完整复核同样exit0（build/hxa281-closeout-host.txt）。source/JVM、spotless/detekt、两渠道Debug/Release lint及APK、40份依赖锁和打包/运行时边界均通过。两渠道AndroidTest APK最终编译exit0（build/hxa281-test-apks-r3.txt）。新增MarketplaceSourceLifecycleDeviceTest覆盖目录添加不安装、取消不安装、原PluginService实际安装、会话显式选择、移除目录后保留安装及选择；该设备用例本轮只编译，未执行。

中途准备错误、长行/复杂度、远端fixture错误声明READ_ONLY被框架拒绝均修复后重新验证；没有放宽远端风险分类或门禁。完整主机后新增夹具长行和投影codec格式分别导致final/r3失败，修正后r4全部通过。工具发现阶段提交13c2c0cd，市场实现阶段提交8d626c18。后续修改需记录fresh结果。

设备、真实模型、真实OAuth/App UI本轮not requested；没有安装主APK或修改设备。当前Host通过不代表误调用率降低，也不继承上一轮API36/OnePlus/Qwen的通过结论。其他Provider/OEM/长期验收仍独立保留。
