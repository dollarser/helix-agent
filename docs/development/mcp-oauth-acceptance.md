# MCP/OAuth 真实服务验收

更新：2026-10-15。归属：[HXA-125](tasks/HXA-125.md)、[HXA-126](tasks/HXA-126.md)、[HXA-282](tasks/HXA-282.md)。本页是准备和复核步骤，不是已执行结果。

## 选择服务

Owner要求推荐两家可用个人账号接入的平台，也允许考虑自建Sites认证服务。优先采用Notion与Linear的官方远程MCP，避免把自建服务器兼容性当成两家独立厂商兼容性。

| 服务 | 接入与认证 | 验收约束 |
| --- | --- | --- |
| Notion | https://mcp.notion.com/mcp；官方OAuth，支持动态客户端注册及CIMD | 仅选测试工作区/页面；实际注册支持的redirect、组织准入和撤销行为需现场确认 |
| Linear | https://mcp.linear.app/mcp；官方OAuth 2.1/动态注册 | 本轮保留原端点并使用read scope，同样由服务端限制为只读；也可新配置/mcp/readonly，不为验收改动已有resource绑定 |
| GitHub（备选） | 官方远程MCP或已有Device Authorization配置 | 仓库存在历史Device Authorization开发记录，不能据此确认当前账号/MCP已验；PAT成功不等于OAuth授权码/刷新通过 |

官方依据：[Notion MCP](https://developers.notion.com/guides/mcp/overview)、[Notion客户端接入](https://developers.notion.com/guides/mcp/build-mcp-client)、[Linear MCP](https://linear.app/docs/mcp)、[GitHub远程服务](https://github.com/github/github-mcp-server/blob/main/docs/remote-server.md)。2026-10-14复核，未复制参考代码。远程文档支持某流程不代表当前Helix回调、账号和服务实际允许它。

## 必须分开的证据

1. 读取公开文档/元数据、导入包和编译是准备；不能记作登录/实际工具调用通过。
2. Sites自建两个有认证的MCP适合验证协议、错误、刷新轮换、回放和拒绝，但仍是自建受控服务，不满足两家真实独立厂商的验收口径。
3. 在ChatGPT或其他客户端连接成功，不能代替Helix客户端验收；不借用其账号凭据、Cookie或Client ID。
4. 本地断开不等于厂商已撤销。服务没有撤销endpoint时如实记录本地清除，再由账号后台撤销/另行核验；不能虚报vendorRevoked。

## 执行前冻结

- 明确当轮设备/API/渠道、主/测试APK hash、原数据处理、测试账号及精确测试对象；设备和真实账号须按AGENTS明确当轮范围。
- 客户端配置只填实际服务公开元数据及Owner控制的注册资料；密钥留本机受保护配置，不贴聊天或提交仓库。个人测试账号是否允许创建开发者应用，以当前平台账号政策为准。
- 显示真实issuer/resource/注册目标与精确redirect。CIMD需自有HTTPS身份，DCR仅在用户明确确认后提交；不从其他客户端复制身份，不自动扩大redirect，不以无state转发代替回调。
- 已有公共Client ID或授权继续复用，不为验收重复注册。没有自有CIMD文档且服务提供DCR时，先选择兼容注册，再核对确认对话框中的目标；仅打开配置、切换方式或取消不能发起POST。兼容入口的UI测试不等于真实厂商注册已经执行。
- 预先选一个存在的测试页面/议题，仅开放正常只读调用。授权失败、注册拒绝、redirect不支持均保留为失败，不静默降级成PAT并宣称OAuth通过。

## 固定旅程

1. 从市场/文件导入未配置包，确认未自动登录、未加入会话、未执行业务工具；查看连接设置的OAuth与实际就绪原因。
2. 用户启动登录，拒绝一次；核对未残留可用凭据，工具仍不可用。
3. 用户再次登录并仅授权测试对象；回调回到原尝试，核对一次性state、精确issuer/resource/client/redirect绑定。
4. 连接后列出实际工具，通过原Dispatcher只读调用一个已知对象，以独立事实核验返回内容；不要把工具数量固定为门禁。
5. 验证刷新及其持久化/轮换；若真实服务没有在窗口内触发刷新，记未执行，不用主动破坏Token或猜测过期时间换通过。
6. 本地断开后确认旧凭据不再准入；执行厂商提供的撤销，并核对旧授权不可用；重连生成新的授权尝试。
7. 仅在当轮授权范围内验证进程恢复/取消/迟到回调；恢复原设置和测试状态，分别记录正常、拒绝、撤销、失败及未执行项。

每个账号的登录/验证码由Owner完成。主机fixture继续验证损坏/超时/取消/重放分支，不用伪造真实厂商响应替代实际服务证据。

真实业务读取需要Owner提供可访问的Notion测试页面、Linear测试议题及可核对的标题/测试标记；不遍历整个个人工作区来找测试对象。真实刷新等待服务实际过期条件，不改写已有账号Token时间；撤销后重连需要Owner在线完成浏览器操作。缺少这些条件时分别记录pending，不撤销后留下无法恢复的日常连接。
