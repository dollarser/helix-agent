# MCP OAuth客户端配置收尾

日期：2026-10-15。归属[HXA-126](../../development/tasks/HXA-126.md)、[ADR-CONNECTORS-002](../../adr/connectors/002-oauth.md)。Owner要求继续上一轮Notion/Linear收尾；本记录不代替两家真实账号的业务/生命周期验收。

## 问题及选择

真实Notion/Linear同时声明CIMD与DCR；Helix原UI只显示CIMD，底层也无条件拒绝在CIMD可用时注册。Owner没有自有HTTPS元数据文档，因此前轮只能手动注册公共客户端，不能算内置注册路径通过。

参考[当前MCP客户端注册规范](https://modelcontextprotocol.io/specification/draft/basic/authorization/client-registration)、[Notion官方客户端接入](https://developers.notion.com/guides/mcp/build-mcp-client)和[Linear官方MCP](https://linear.app/docs/mcp)。指定历史日期的规范URL本轮浏览不可达，因此引用本轮实际读取的draft；保留CIMD优先及DCR已弃用的边界，不将兼容后备说成新客户端首选。未复制参考代码。

默认仍只显示CIMD。无自有文档的用户明确选择“使用兼容注册”，才出现注册按钮；之后再次确认issuer、注册URL与精确redirect才POST。没有registration_endpoint时不显示后备。传输/持久化层新增默认false的显式后备参数；普通调用仍拒绝在CIMD可用时注册，文档读取失败不自动降级。已有/未知注册仍拒绝覆盖或重试，元数据重新核对、native/public/PKCE、SSRF、取消及迟到发布保护保留。

## 验证与边界

定向JVM通过：协议注册10项、每渠道持久注册9项，共28项；覆盖默认拒绝且不写Secret、不发请求，显式后备及网络准入、持久复用且不重复POST，既有失败/未知/取消及竞态断言保留。最初格式任务因超长条件行失败，拆行后原门禁通过。Developer主/测试APK编译成功；最终主机及设备结果在下面追加，不从编译推断设备通过。

最终完整`check-all.sh --all`经host slot退出0：源码/全部JVM、spotless/detekt、双渠道Debug/Release lint/制品、41份依赖锁和制品边界通过（`all.log`）。随后Consumer测试APK编译退出0，Developer测试APK此前定向命令已编译通过。

Owner指定日常API36 Developer补验：同schema覆盖安装前停机备份，六张核心用户表逐行一致，完整性通过、通知监听组件恢复DEFAULT；不改库或重建。仅运行`OAuthClientSetupDeviceTest`三项，实际3/3 passed（`client-setup-ui.log`）：默认CIMD不出现DCR，原无CIMD注册需独立确认，新兼容入口需先选择再确认且取消不注册。测试使用受控回调计数，没有重复向真实厂商注册，不能记真实DCR POST passed；没有运行其他fixture或借真机结果。实际只读工具恢复结果在下方补充。

测试进程退出后，保留原Notion OAuth，重新经原生页面测试连接并只选回notion-search/fetch；终态实际为“OAuth 2.0 已授权”“工具已启用”（`notion-restored.xml`、`restore-notion-v2.log`）。首个恢复脚本因完成后的控件位于屏幕下方而等待，被停止后补充滚动定位再运行成功；没有改产品或跳过断言。设备临时XML已删除，无ADB临时转发；没有发起模型、业务tools/call、真实注册或撤销。文档/ADR/机密最终检查通过。

冻结Developer主APK SHA-256：`16fd8e6fff73d685930aa39cc23111bafc387780254140faa8b1473c74f4ff0c`；测试APK：`76f5af002824481fcb723bd3575b6cac929012c58d9a4e12ea30f69b69f27447`。忽略的`build/oauth-closeout-2026-10-15/`保存日志/制品/私有备份；本机脚本在同日local目录。

Owner当前未提供Notion测试页面及Linear测试议题，已发出询问；业务读取保持pending，不遍历个人工作区找样本。真实Token刷新需自然过期条件，不改写已有账号期限；厂商撤销/重连需Owner可完成浏览器操作后执行。本轮不重复注册已有真实客户端、不重置数据，不以主机fixture或确认对话框测试替代真实厂商注册/账号通过。
